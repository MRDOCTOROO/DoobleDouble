#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把当前 git 提交推送到 GitHub —— 走 REST API 而不是 `git push`。

## 为什么不用 `git push`

本机网络里 **`github.com` 主站被代理挡住**（`CONNECT tunnel failed, response 502`），
而 `git push` 走 HTTPS 必须访问 `github.com`。实测各域名：

    github.com                    000  ← 被挡，git push 走不通
    api.github.com                200  ← 通
    codeload.github.com           301  ← 通
    raw.githubusercontent.com     301  ← 通
    objects.githubusercontent.com 404  ← 通（Release 产物下载走这里）
    uploads.github.com            302  ← 通

所以：**推送改走 API，CI 与产物下载都不受影响**（Actions 跑在 GitHub 自己的
服务器上，跟本机网络无关）。

## 实现方式

用 Git Data API 复刻一次 push，而不是逐个文件调 Contents API：

    1. 从本地 git 对象库读 HEAD 的全部 blob（`git cat-file --batch`）
    2. 逐个 POST /git/blobs            → 每个文件的 sha
    3. POST /git/trees                 → 整棵树
    4. POST /git/commits               → 提交（带父提交，支持增量）
    5. POST/PATCH /git/refs/heads/<br> → 移动分支

好处：**推上去的内容与本地提交逐字节一致**，而且天然排除 .gitignore 里的东西
（读的是 git 对象，不是工作区目录）。

## 用法

    # 默认：推 HEAD 到 main 分支，仓库不存在则创建（私有）
    python tools/gh-publish.py --repo MRDOCTOROO/DoobleDouble

    # 只创建仓库不推
    python tools/gh-publish.py --repo OWNER/NAME --create-only

    # 公开仓库（默认私有）
    python tools/gh-publish.py --repo OWNER/NAME --public

令牌来源（按优先级）：--token > 环境变量 GH_TOKEN/GITHUB_TOKEN >
git 的 credential helper（本机 Windows 凭证管理器里存着）。
"""

import argparse
import base64
import http.client
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
UA = "dooble-publish/1.0"


# --------------------------------------------------------------------------- #
# 令牌
# --------------------------------------------------------------------------- #

def token_from_credential_helper() -> str | None:
    """从 git 的 credential helper 取令牌（本机是 Windows 凭证管理器）。

    注意不要把这个值打印出来 —— 它等价于账号密码。
    """
    helper = subprocess.run(
        ["git", "config", "--get", "credential.helper"],
        capture_output=True, text=True,
    ).stdout.strip()
    if not helper:
        return None
    # 配置里可能是 `!"C:/path/to/git-credential-wincred.exe"`：
    # 前导 `!` 表示"这是 shell 命令"，外面还包了一层双引号（路径含空格时必需）。
    # 两者都要剥掉，否则 Python 直接 exec 一个带引号的路径会 FileNotFoundError。
    helper = helper.lstrip("!").strip().strip('"').strip("'")
    try:
        out = subprocess.run(
            [helper, "get"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True, text=True,
        ).stdout
    except OSError:
        return None
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    return None


def resolve_token(explicit: str | None) -> str:
    tok = explicit or os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not tok:
        tok = token_from_credential_helper()
    if not tok:
        sys.exit("[ERROR] 找不到令牌。用 --token，或设 GH_TOKEN 环境变量。")
    return tok


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #

class Gh:
    def __init__(self, token: str):
        self.token = token

    def call(self, method: str, path: str, body=None, *, raw=False, retries=3):
        url = path if path.startswith("http") else API + path
        data = None
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": UA,
        }
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"

        last = None
        for attempt in range(retries):
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    payload = r.read()
                    return payload if raw else (json.loads(payload) if payload else None)
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", "replace")
                # 5xx / 429 重试，4xx 直接抛（4xx 重试没意义）
                if e.code >= 500 or e.code == 429:
                    last = f"HTTP {e.code}: {detail[:300]}"
                    time.sleep(2 * (attempt + 1))
                    continue
                raise SystemExit(f"[ERROR] {method} {url}\n        HTTP {e.code}: {detail[:600]}")
            except (urllib.error.URLError, http.client.HTTPException, OSError) as e:
                # 本机走的是一个会偶发掉连接的代理，实测出现过
                # `RemoteDisconnected: Remote end closed connection without response`
                # —— 那是一次**瞬时**故障，重试就好，不该让整次推送白跑。
                # OSError 覆盖 ConnectionResetError / TimeoutError 等。
                last = f"{type(e).__name__}: {e}"
                print(f"    [retry {attempt + 1}/{retries}] {last}")
                time.sleep(2 * (attempt + 1))
        raise SystemExit(f"[ERROR] {method} {url} 重试 {retries} 次仍失败：{last}")


# --------------------------------------------------------------------------- #
# 本地 git 对象
# --------------------------------------------------------------------------- #

def head_info() -> tuple[str, str, list[tuple[str, str, str]]]:
    """返回 (commit_sha, message, [(mode, sha, path), ...])"""
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()
    message = subprocess.run(
        ["git", "log", "-1", "--format=%B"], capture_output=True, text=True, check=True
    ).stdout.strip()

    raw = subprocess.run(
        ["git", "ls-tree", "-r", "-z", "HEAD"],
        capture_output=True, check=True,
    ).stdout
    entries = []
    for rec in raw.split(b"\0"):
        if not rec:
            continue
        meta, path = rec.split(b"\t", 1)
        mode, _typ, sha = meta.decode().split()
        entries.append((mode, sha, path.decode("utf-8")))
    return commit, message, entries


def read_blobs(shas: list[str]) -> dict[str, bytes]:
    """用 `git cat-file --batch` 一次性读出所有 blob，避免 N 次进程启动。"""
    proc = subprocess.run(
        ["git", "cat-file", "--batch"],
        input=("\n".join(shas) + "\n").encode(),
        capture_output=True,
        check=True,
    )
    out = proc.stdout
    blobs: dict[str, bytes] = {}
    pos = 0
    while pos < len(out):
        nl = out.index(b"\n", pos)
        header = out[pos:nl].decode()
        parts = header.split()
        if len(parts) < 3:  # "<sha> missing" 之类
            break
        sha, _typ, size = parts[0], parts[1], int(parts[2])
        start = nl + 1
        blobs[sha] = out[start:start + size]
        pos = start + size + 1  # 跳过结尾换行
    return blobs


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #

def ensure_repo(gh: Gh, owner: str, name: str, private: bool, create_only: bool):
    try:
        repo = gh.call("GET", f"/repos/{owner}/{name}")
        print(f"==> 仓库已存在：{repo['full_name']}（{'私有' if repo['private'] else '公开'}）")
        if repo["private"] != private:
            print(f"    注意：当前可见性与你要的（{'私有' if private else '公开'}）不一致，"
                  f"这里不会替你改 —— 要改请去网页 Settings 里改。")
        return repo
    except SystemExit as e:
        if "404" not in str(e):
            raise

    print(f"==> 创建仓库 {owner}/{name}（{'私有' if private else '公开'}）")
    body = {
        "name": name,
        "private": private,
        "auto_init": False,      # 关键：不要初始化，否则会有个默认 commit 挡着 refs/heads/main
        "has_issues": True,
        "has_wiki": False,
        "description": "免 root 的多开空间容器：每个空间一套独立虚拟 Android 环境，"
                       "内置 microG 谷歌环境（不打包原版 GMS）",
    }
    try:
        repo = gh.call("POST", "/user/repos", body)
    except SystemExit as e:
        if owner.lower() != owner or True:
            # 组织仓库走另一个端点
            repo = gh.call("POST", f"/orgs/{owner}/repos", body)
        else:
            raise e
    print(f"    {repo['html_url']}")
    if create_only:
        print("==> --create-only，跳过推送")
        sys.exit(0)
    return repo


def bootstrap_if_empty(gh: Gh, owner: str, name: str, branch: str) -> bool:
    """GitHub 的 Git Data API 在**空仓库**上会直接 409（"Git Repository is empty."）——
    必须先有一个 ref 才能建 blob/tree/commit。

    所以先探一下分支在不在；不在就用 Contents API 写一个占位文件，
    这一步会顺带把 `refs/heads/main` 建出来。之后真正的推送会把这个提交
    当作父提交，占位文件在下一个提交里被删掉（历史里会留一条记录，无妨）。
    """
    try:
        gh.call("GET", f"/repos/{owner}/{name}/git/ref/heads/{branch}")
        return False
    except SystemExit as e:
        # 分支不存在是 404；**仓库整个是空的**则是 409（"Git Repository is empty."）。
        # 两种情况都要当成"需要引导"，只判 404 会漏掉后者 —— 这个坑踩过一次。
        if "404" not in str(e) and "409" not in str(e):
            raise
    print(f"==> 仓库还是空的，先写一个占位提交把 {branch} 建出来")
    gh.call("PUT", f"/repos/{owner}/{name}/contents/.dooble-bootstrap", {
        "message": "chore: 初始化仓库（占位提交，下一步会被真实内容覆盖）",
        "content": base64.b64encode(
            b"This file only exists so that refs/heads/main can be created.\n"
            b"It is removed by the next commit.\n"
        ).decode(),
        "branch": branch,
    })
    return True


def push(gh: Gh, owner: str, name: str, branch: str):
    bootstrap_if_empty(gh, owner, name, branch)

    commit_sha, message, entries = head_info()
    print(f"==> 本地 HEAD {commit_sha[:8]}：{len(entries)} 个文件")

    blobs = read_blobs([sha for _m, sha, _p in entries])
    print(f"==> 已从 git 对象库读出 {len(blobs)} 个 blob")

    # 1) 上传 blob。同一个 sha 只传一次（git 里内容相同的文件共享 sha）。
    sha_map: dict[str, str] = {}
    unique = list(dict.fromkeys(sha for _m, sha, _p in entries))
    for i, sha in enumerate(unique, 1):
        content = blobs[sha]
        res = gh.call("POST", f"/repos/{owner}/{name}/git/blobs", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        sha_map[sha] = res["sha"]
        if i % 20 == 0 or i == len(unique):
            print(f"    blob {i}/{len(unique)}")

    # 2) 建树。100755（可执行）必须保留 —— gradlew 没了执行位，CI 直接跑不起来。
    tree = [
        {"path": path, "mode": mode, "type": "blob", "sha": sha_map[sha]}
        for mode, sha, path in entries
    ]
    tree_sha = gh.call("POST", f"/repos/{owner}/{name}/git/trees", {"tree": tree})["sha"]
    print(f"==> tree {tree_sha[:8]}")

    # 3) 提交。仓库里已有同名分支时挂上父提交，保住历史。
    parents = []
    try:
        cur = gh.call("GET", f"/repos/{owner}/{name}/git/ref/heads/{branch}")
        parents = [cur["object"]["sha"]]
        print(f"==> 分支 {branch} 已存在（{parents[0][:8]}），作为父提交")
    except SystemExit as e:
        if "404" not in str(e) and "409" not in str(e):
            raise
        print(f"==> 分支 {branch} 不存在，这是首次推送")

    if parents and parents[0] == commit_sha:
        print("==> 远端已经就是这个提交，无需推送")
        return

    new_commit = gh.call("POST", f"/repos/{owner}/{name}/git/commits", {
        "message": message,
        "tree": tree_sha,
        "parents": parents,
    })["sha"]
    print(f"==> commit {new_commit[:8]}")

    # 4) 移动分支
    if parents:
        gh.call("PATCH", f"/repos/{owner}/{name}/git/refs/heads/{branch}", {
            "sha": new_commit, "force": False,
        })
    else:
        gh.call("POST", f"/repos/{owner}/{name}/git/refs", {
            "ref": f"refs/heads/{branch}", "sha": new_commit,
        })
    print(f"==> 已推送：https://github.com/{owner}/{name}/tree/{branch}")


def main():
    ap = argparse.ArgumentParser(description="走 REST API 把当前提交推到 GitHub")
    ap.add_argument("--repo", required=True, help="OWNER/NAME")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--public", action="store_true", help="默认私有")
    ap.add_argument("--create-only", action="store_true")
    ap.add_argument("--token", default=None)
    args = ap.parse_args()

    owner, _, name = args.repo.partition("/")
    if not owner or not name:
        sys.exit("[ERROR] --repo 必须是 OWNER/NAME")

    gh = Gh(resolve_token(args.token))
    me = gh.call("GET", "/user")
    print(f"==> 令牌身份：{me['login']}")
    if me["login"].lower() != owner.lower():
        print(f"    注意：令牌属于 {me['login']}，与 --repo 的 owner（{owner}）不同，"
              f"会尝试往组织仓库推。")

    ensure_repo(gh, owner, name, private=not args.public, create_only=args.create_only)
    push(gh, owner, name, args.branch)


if __name__ == "__main__":
    main()
