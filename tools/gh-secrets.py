#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 release 签名密钥库写进 GitHub Actions Secrets —— 走 REST API。

## 为什么需要它

`.github/workflows/android.yml` 从四个 Secret 里取签名信息：

    KEYSTORE_BASE64        keystore/dooble-release.jks 的 base64
    KEYSTORE_PASSWORD      密钥库口令
    KEY_ALIAS              密钥别名（dooble）
    KEY_PASSWORD           密钥口令

手工去网页上逐个粘贴很容易出错（base64 那串有 6KB），而且**密钥库绝不能入库**
（`.gitignore` 里已经排除了 `/keystore/`）。所以用 API 直接写。

## 加密方式

GitHub 要求 Secret 值用仓库公钥做 **libsodium sealed box** 加密后提交，
不是明文。依赖 `pynacl`：

    <managed-python> -m pip install pynacl

## 用法

    python tools/gh-secrets.py --repo MRDOCTOROO/DoobleDouble
    python tools/gh-secrets.py --repo OWNER/NAME --keystore keystore/dooble-release.jks

默认从 `keystore/.storepass`（tools/gen-release-keystore.sh 产出）取口令。
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
UA = "dooble-secrets/1.0"


def resolve_token(explicit: str | None) -> str:
    tok = explicit or os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not tok:
        helper = subprocess.run(
            ["git", "config", "--get", "credential.helper"],
            capture_output=True, text=True,
        ).stdout.strip().lstrip("!").strip().strip('"').strip("'")
        if helper:
            out = subprocess.run(
                [helper, "get"],
                input="protocol=https\nhost=github.com\n\n",
                capture_output=True, text=True,
            ).stdout
            for line in out.splitlines():
                if line.startswith("password="):
                    tok = line[len("password="):]
                    break
    if not tok:
        sys.exit("[ERROR] 找不到令牌。用 --token，或设 GH_TOKEN 环境变量。")
    return tok


def call(token: str, method: str, path: str, body=None):
    url = API + path
    data = json.dumps(body).encode() if body is not None else None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": UA,
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            payload = r.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"[ERROR] {method} {path}\n        HTTP {e.code}: {detail[:500]}")


def main():
    ap = argparse.ArgumentParser(description="把签名密钥库写进 GitHub Actions Secrets")
    ap.add_argument("--repo", required=True, help="OWNER/NAME")
    ap.add_argument("--keystore", default="keystore/dooble-release.jks")
    ap.add_argument("--password-file", default="keystore/.storepass")
    ap.add_argument("--alias", default="dooble")
    ap.add_argument("--token", default=None)
    args = ap.parse_args()

    try:
        from nacl import encoding, public  # noqa: WPS433
    except ImportError:
        sys.exit("[ERROR] 缺 pynacl。装它：\n"
                 "        <managed-python> -m pip install pynacl")

    if not os.path.isfile(args.keystore):
        sys.exit(f"[ERROR] 找不到密钥库：{args.keystore}\n"
                 f"        先跑 tools/gen-release-keystore.sh 生成。")
    if not os.path.isfile(args.password_file):
        sys.exit(f"[ERROR] 找不到口令文件：{args.password_file}")

    password = open(args.password_file, encoding="utf-8").read().strip()
    if not password:
        sys.exit("[ERROR] 口令文件是空的")

    token = resolve_token(args.token)
    owner, _, name = args.repo.partition("/")

    # 1) 取仓库公钥
    key = call(token, "GET", f"/repos/{owner}/{name}/actions/secrets/public-key")
    pk = public.PublicKey(key["key"].encode(), encoding.Base64Encoder())

    def seal(value: str) -> str:
        boxed = public.SealedBox(pk).encrypt(value.encode())
        return base64.b64encode(boxed).decode()

    keystore_b64 = base64.b64encode(open(args.keystore, "rb").read()).decode()

    secrets = {
        "KEYSTORE_BASE64": keystore_b64,
        "KEYSTORE_PASSWORD": password,
        "KEY_ALIAS": args.alias,
        "KEY_PASSWORD": password,
    }

    for key_name, value in secrets.items():
        call(token, "PUT", f"/repos/{owner}/{name}/actions/secrets/{key_name}", {
            "encrypted_value": seal(value),
            "key_id": key["key_id"],
        })
        shown = f"{len(value)} 字节" if key_name == "KEYSTORE_BASE64" else "已写入"
        print(f"    [OK] {key_name:<20} {shown}")

    print(f"\n==> 完成。四个 Secret 已写入 {args.repo}")
    print(f"    https://github.com/{args.repo}/settings/secrets/actions")


if __name__ == "__main__":
    main()
