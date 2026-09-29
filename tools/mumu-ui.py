#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MuMu 上的 UI 自动化小工具。

## 为什么需要它

本工程是单 Activity + Compose 内部路由，`dumpsys` 看不到页面跳转，
只能靠 UI 层级 + 截图。而手工 `uiautomator dump` 有两个固定坑：

1. **属性名是 `content-desc` 不是 `desc`**。写成 `desc` 会让所有描述恒为空，
   而本工程大量使用只有 content-desc 的图标按钮（「结束运行」「启动」「卸载」），
   表现是"界面上明明有这个按钮，脚本就是找不到"。
2. **文本节点本身不可点**。Compose 里 `Text` 没有 clickable，
   必须向上找第一个 `clickable="true"` 的祖先节点，用它的 bounds 去点。

## 用法

    python tools/mumu-ui.py dump                 # 打印可交互/有文字的节点
    python tools/mumu-ui.py tap "登录 Google 账号"  # 按文本或描述点击
    python tools/mumu-ui.py tap-desc "结束运行"
    python tools/mumu-ui.py shot out.png         # 截图
    python tools/mumu-ui.py wait "谷歌账号" 10     # 等文本出现（秒）

设备串号默认 `127.0.0.1:16384`（MuMu），可用 `--serial` 覆盖。
每次调用都会自己 `start-server` + `connect` —— adb daemon 会在两次
shell 调用之间被回收，不这么做就会 `device not found`。
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get(
    "ADB",
    r"C:/Users/admin/android-dev/sdk/platform-tools/adb.exe",
)
DEFAULT_SERIAL = "127.0.0.1:16384"


def adb(serial, *args, binary=False):
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    if binary:
        return subprocess.run(cmd, stdout=subprocess.PIPE, check=False).stdout
    return subprocess.run(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False
    ).stdout.decode("utf-8", "replace")


def connect(serial):
    subprocess.run([ADB, "start-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(
        [ADB, "connect", serial], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    )


def dump_xml(serial):
    """把 uiautomator 的层级 dump 到设备上再读回来。

    注意读的是 `/sdcard/window_dump.xml`，用 `exec-out cat` 而不是
    `shell cat` —— 后者会经过 PTY，Windows 上会把换行转成 CRLF。
    """
    for _ in range(3):
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
        raw = adb(serial, "exec-out", "cat", "/sdcard/window_dump.xml", binary=True)
        if raw and b"<hierarchy" in raw:
            return raw.decode("utf-8", "replace")
        time.sleep(0.4)
    raise SystemExit("拿不到 UI 层级（uiautomator dump 失败）")


def parse_bounds(s):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", s or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2


def center(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def walk(root):
    """返回 (node, parent_map) —— 需要向上找 clickable 祖先，所以要有父指针"""
    parents = {}
    stack = [(root, None)]
    while stack:
        n, p = stack.pop()
        parents[n] = p
        for c in n:
            stack.append((c, n))
    return parents


def describe(node):
    return {
        "text": node.get("text") or "",
        "desc": node.get("content-desc") or "",
        "cls": node.get("class") or "",
        "bounds": parse_bounds(node.get("bounds")),
        "clickable": node.get("clickable") == "true",
        "id": node.get("resource-id") or "",
    }


def visible_nodes(xml):
    root = ET.fromstring(xml)
    parents = walk(root)
    out = []
    for n in parents:
        d = describe(n)
        if d["bounds"] and (d["text"] or d["desc"]):
            d["node"] = n
            out.append(d)
    return out, parents


def find_clickable(node, parents):
    """向上找第一个 clickable 祖先；找不到就返回自己（有些节点自身可点）"""
    cur = node
    while cur is not None:
        if cur.get("clickable") == "true" and parse_bounds(cur.get("bounds")):
            return cur
        cur = parents.get(cur)
    return node


def cmd_dump(serial, _args):
    xml = dump_xml(serial)
    nodes, _ = visible_nodes(xml)
    for d in nodes:
        tag = "CLICK" if d["clickable"] else "     "
        print(f"{tag} {str(d['bounds']):<24} text={d['text']!r:<34} desc={d['desc']!r}")


def _tap_by(serial, key, needle, exact=False):
    xml = dump_xml(serial)
    nodes, parents = visible_nodes(xml)
    if exact:
        hits = [d for d in nodes if d[key].strip() == needle.strip()]
    else:
        hits = [d for d in nodes if needle in d[key]]
    if not hits:
        print(f"找不到：{key}={needle!r}{' (精确)' if exact else ''}", file=sys.stderr)
        for d in nodes[:40]:
            print(f"  可选：text={d['text']!r} desc={d['desc']!r}", file=sys.stderr)
        return 1
    target = find_clickable(hits[0]["node"], parents)
    b = parse_bounds(target.get("bounds"))
    x, y = center(b)
    print(f"tap {key}={needle!r} -> ({x},{y})")
    adb(serial, "shell", "input", "tap", str(x), str(y))
    return 0


def cmd_tap(serial, args):
    return _tap_by(serial, "text", args.needle, exact=args.exact)


def cmd_tap_desc(serial, args):
    return _tap_by(serial, "desc", args.needle, exact=args.exact)


def cmd_shot(serial, args):
    png = adb(serial, "exec-out", "screencap", "-p", binary=True)
    with open(args.path, "wb") as f:
        f.write(png)
    print(f"已保存 {args.path}（{len(png)} 字节）")
    return 0


def cmd_wait(serial, args):
    deadline = time.time() + args.seconds
    while time.time() < deadline:
        xml = dump_xml(serial)
        nodes, _ = visible_nodes(xml)
        if any(args.needle in d["text"] or args.needle in d["desc"] for d in nodes):
            print(f"已出现：{args.needle!r}")
            return 0
        time.sleep(0.6)
    print(f"超时未出现：{args.needle!r}", file=sys.stderr)
    return 1


def cmd_scroll_tap(serial, args):
    """滚动直到目标出现，然后**立刻**点击。

    为什么不能分成"先 dump 找位置，再单独发 tap"两步：Compose 的滚动有惯性，
    两步之间页面会继续滑动，按旧坐标点下去就会落在别的控件上 ——
    表现是"脚本说点了，界面上什么都没发生"。这里在同一个函数里
    找完立刻点，把窗口压到最小。
    """
    up = args.direction == "up"
    for i in range(args.max_scrolls + 1):
        xml = dump_xml(serial)
        nodes, parents = visible_nodes(xml)
        needle = args.needle
        hits = [
            d for d in nodes
            if (d["text"].strip() == needle or d["desc"].strip() == needle)
            if args.exact
        ] if args.exact else [
            d for d in nodes if needle in d["text"] or needle in d["desc"]
        ]
        # 只点落在可视区域内的（避免点到被裁剪的节点）
        hits = [d for d in hits if d["bounds"][1] >= 0 and d["bounds"][3] <= args.screen_h]
        if hits:
            target = find_clickable(hits[0]["node"], parents)
            b = parse_bounds(target.get("bounds"))
            x, y = center(b)
            print(f"第 {i} 次滚动后命中 {needle!r} -> ({x},{y})")
            adb(serial, "shell", "input", "tap", str(x), str(y))
            return 0
        y1, y2 = (args.screen_h - 120, 220) if up else (220, args.screen_h - 120)
        adb(serial, "shell", "input", "swipe", str(args.screen_w // 2), str(y1),
            str(args.screen_w // 2), str(y2), "260")
        time.sleep(args.settle)
    print(f"滚动 {args.max_scrolls} 次仍未找到：{args.needle!r}", file=sys.stderr)
    return 1


def main():
    ap = argparse.ArgumentParser(description="MuMu UI 自动化小工具")
    ap.add_argument("--serial", default=DEFAULT_SERIAL)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("dump").set_defaults(fn=cmd_dump)

    p = sub.add_parser("tap")
    p.add_argument("needle")
    p.add_argument("--exact", action="store_true", help="整串相等匹配，而不是子串包含")
    p.set_defaults(fn=cmd_tap)

    p = sub.add_parser("tap-desc")
    p.add_argument("needle")
    p.add_argument("--exact", action="store_true", help="整串相等匹配，而不是子串包含")
    p.set_defaults(fn=cmd_tap_desc)

    p = sub.add_parser("shot")
    p.add_argument("path")
    p.set_defaults(fn=cmd_shot)

    p = sub.add_parser("wait")
    p.add_argument("needle")
    p.add_argument("seconds", nargs="?", type=float, default=10.0)
    p.set_defaults(fn=cmd_wait)

    p = sub.add_parser("scroll-tap", help="滚动直到目标出现再点击（避免点偏）")
    p.add_argument("needle")
    p.add_argument("--direction", choices=["up", "down"], default="up",
                   help="up=内容向上滚（看后面的内容）")
    p.add_argument("--exact", action="store_true")
    p.add_argument("--max-scrolls", type=int, default=15)
    p.add_argument("--settle", type=float, default=0.5)
    p.add_argument("--screen-w", type=int, default=720)
    p.add_argument("--screen-h", type=int, default=1280)
    p.set_defaults(fn=cmd_scroll_tap)

    args = ap.parse_args()
    connect(args.serial)
    sys.exit(args.fn(args.serial, args))


if __name__ == "__main__":
    main()
