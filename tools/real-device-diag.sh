#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 真机诊断：一次把排查需要的现场全部抓下来，存到 tmp-real-diag/。
#
# ## 为什么要有这个脚本
#
# 真机排查最痛的不是"看不出问题"，而是**要来回跑十几条 adb 命令**，而
# 无线调试经常会话超时掉线 —— 每掉一次就得让用户重新看端口、重新配对。
# 所以这里把「连接 → 抓取 → 落盘」压成一次调用，掉线时直接重跑。
#
# ## 用法
#
#   tools/real-device-diag.sh 10.28.31.44:39919
#   tools/real-device-diag.sh 10.28.31.44:39919 --pair 10.28.31.44:33325 971502
#
# ## 抓什么
#
#   1. 设备与系统信息（型号/版本/SDK/ABI）
#   2. 我们应用的版本、primaryCpuAbi、安装时间
#   3. 宿主上的谷歌系包（判断"从宿主克隆"能拿到什么）
#   4. 空间元数据 spaces.json（**需要 debug 版**，release 读不了）
#   5. 容器内的应用列表（从 spaces.json 推）
#   6. 崩溃与 ANR：logcat + /data/anr/ 的最近几份 trace
#   7. 我们自己的日志（DoobleAccount / DoobleBcore / DoobleTask / CommonStub）
#   8. 关键系统日志：ActivityTaskManager 的 START、AccountChooser、chromium
#
# ## ⚠️ 一个前置条件
#
# `run-as` 只对 **debuggable** 的应用有效。真机上如果装的是 release 版
# （`com.dooble.space`，CI 出的那个），第 4、5 步会失败 —— 这是预期的，
# 不是脚本坏了。要深挖容器内部状态，请装 debug 版（`com.dooble.space.debug`）。
# ---------------------------------------------------------------------------
set -uo pipefail

SERIAL="${1:-}"
[ -n "$SERIAL" ] || { echo "用法: $0 <serial> [--pair <pair_addr> <code>]" >&2; exit 1; }
shift

PAIR_ADDR=""; PAIR_CODE=""
if [ "${1:-}" = "--pair" ]; then PAIR_ADDR="${2:-}"; PAIR_CODE="${3:-}"; fi

ADB="${ADB:-C:/Users/admin/android-dev/sdk/platform-tools/adb.exe}"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/tmp-real-diag"
mkdir -p "$OUT"

export MSYS_NO_PATHCONV=1

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
# 每条命令都自己 start-server：adb daemon 在两次 shell 调用之间会被回收，
# 不这么做第二条命令就 device not found。
run() { "$ADB" start-server >/dev/null 2>&1; "$ADB" -s "$SERIAL" "$@"; }

say "连接 $SERIAL"
"$ADB" start-server >/dev/null 2>&1
if [ -n "$PAIR_ADDR" ] && [ -n "$PAIR_CODE" ]; then
  echo "配对 $PAIR_ADDR …"
  "$ADB" pair "$PAIR_ADDR" "$PAIR_CODE" 2>&1 | tail -2
fi
for i in 1 2 3; do
  "$ADB" connect "$SERIAL" 2>&1 | tail -1
  sleep 2
  if "$ADB" devices 2>&1 | grep -q "$SERIAL[[:space:]]*device$"; then
    echo "✅ 已连接"
    break
  fi
  [ "$i" = 3 ] && { echo "❌ 连不上。请确认：① 手机已解锁；② 无线调试仍开启；③ 端口没变。" >&2; exit 1; }
done

say "1/8 设备与系统"
{
  for p in ro.product.model ro.product.manufacturer ro.product.brand \
           ro.build.version.release ro.build.version.sdk ro.product.cpu.abilist \
           ro.build.fingerprint ro.build.type; do
    printf '%-28s %s\n' "$p" "$(run shell getprop "$p" 2>/dev/null | tr -d '\r')"
  done
} | tee "$OUT/01-device.txt"

say "2/8 我们的应用"
{
  run shell dumpsys package com.dooble.space 2>/dev/null | grep -E "versionName|versionCode|primaryCpuAbi|firstInstallTime|lastUpdateTime" | head -8
  echo "--- debug 版 ---"
  run shell dumpsys package com.dooble.space.debug 2>/dev/null | grep -E "versionName|versionCode|primaryCpuAbi" | head -4
} | tee "$OUT/02-app.txt"

say "3/8 宿主上的谷歌系 / 商店包"
run shell pm list packages 2>/dev/null | grep -iE "vending|\.gms|gsf|aurora|maps|huawei" | sort \
  | tee "$OUT/03-host-packages.txt"

say "4/8 空间元数据（需要 debug 版）"
for pkg in com.dooble.space.debug com.dooble.space; do
  echo "--- $pkg ---"
  run shell run-as "$pkg" cat "/data/data/$pkg/files/dooble/spaces.json" 2>&1 | head -c 8000
  echo
done | tee "$OUT/04-spaces.json"

say "5/8 容器内的应用列表"
for pkg in com.dooble.space.debug com.dooble.space; do
  echo "--- $pkg blackbox/data/app ---"
  run shell run-as "$pkg" ls -la "/data/data/$pkg/blackbox/data/app/" 2>&1 | head -30
  echo "--- $pkg gms-cache ---"
  run shell run-as "$pkg" ls -la "/data/data/$pkg/files/dooble/gms-cache/" 2>&1 | head -15
done | tee "$OUT/05-container-apps.txt"

say "6/8 崩溃与 ANR"
{
  echo "=== /data/anr 最近 5 份 ==="
  run shell ls -t /data/anr/ 2>/dev/null | head -5
  echo
  echo "=== 最新一份 ANR 的 Subject（谁真的卡了）==="
  LATEST="$(run shell ls -t /data/anr/ 2>/dev/null | head -1 | tr -d '\r')"
  if [ -n "$LATEST" ]; then
    run exec-out cat "/data/anr/$LATEST" 2>/dev/null | head -5
    # 主线程栈：ANR 的根因几乎总在这里
    run exec-out cat "/data/anr/$LATEST" 2>/dev/null \
      | awk '/^"main"/{f=1} f&&c<40{print;c++}'
  fi
} | tee "$OUT/06-anr.txt"

say "7/8 我们自己的日志"
run logcat -d -v time 2>/dev/null \
  | grep -E "DoobleAccount|DoobleBcore|DoobleTask|DoobleDetail|CommonStub|AccountChooser|AccountPicker" \
  | tail -200 | tee "$OUT/07-our-logs.txt"

say "8/8 关键系统日志"
run logcat -d -v time 2>/dev/null \
  | grep -E "ActivityTaskManager: START|Start proc|tile_manager|AndroidRuntime|FATAL|am_crash|microg|GmsCore|org\.microg" \
  | tail -200 | tee "$OUT/08-system.txt"

say "完成"
echo "现场已存到：$OUT"
echo
echo "下一步：把 06-anr.txt（谁卡了 + 主线程栈）和 08-system.txt（microG 崩溃）贴给我。"
