#!/usr/bin/env bash
# 在 MuMu 模拟器上安装 / 启动 / 抓日志。
#
# 为什么要有这个脚本：adb server 在两次 shell 调用之间会被回收，
# 所以「连接 → 安装 → 启动 → 抓日志」必须在**同一次进程**里完成，
# 否则会一直看到 device not found。
#
# MuMu 的 adb 端口在不同版本里不一样，脚本会逐个探测。
#
# 用法：
#   tools/run-on-mumu.sh              # 安装 + 启动 + 抓 15 秒日志
#   tools/run-on-mumu.sh logcat       # 只抓日志
#   tools/run-on-mumu.sh abi          # 只看架构与 ABI 选择结果
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB="C:/Users/admin/android-dev/sdk/platform-tools/adb.exe"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$PROJECT_ROOT/dist/dooble-lite-debug.apk"
PKG="com.dooble.space.debug"

"$ADB" start-server >/dev/null 2>&1

# MuMu 12 用 16384；老版本 MuMu 用 7555；标准 AVD 用 5555。
DEV=""
for port in 16384 7555 5555; do
  "$ADB" connect "127.0.0.1:$port" >/dev/null 2>&1
done
sleep 2

# 挑一台 x86_64 的（MuMu），而不是残留的 offline 条目
for line in $("$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}'); do
  abi="$("$ADB" -s "$line" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
  if [ "$abi" = "x86_64" ]; then DEV="$line"; break; fi
  [ -z "$DEV" ] && DEV="$line"
done

if [ -z "$DEV" ]; then
  echo "[ERROR] 没有找到可用设备。请确认 MuMu 已启动且开启了 ADB 调试。" >&2
  "$ADB" devices >&2
  exit 1
fi

echo "==> 设备：$DEV"
echo "    ABI:        $("$ADB" -s "$DEV" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "    支持列表:   $("$ADB" -s "$DEV" shell getprop ro.product.cpu.abilist | tr -d '\r')"
echo "    Android:    $("$ADB" -s "$DEV" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" -s "$DEV" shell getprop ro.build.version.sdk | tr -d '\r'))"
echo "    Native桥:   $("$ADB" -s "$DEV" shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"

case "${1:-run}" in
abi)
  echo
  echo "==> 已安装包的 ABI 选择结果"
  "$ADB" -s "$DEV" shell "dumpsys package $PKG | grep -E 'primaryCpuAbi|secondaryCpuAbi|codePath|nativeLibraryDir'" 2>&1 | tr -d '\r'
  exit 0
  ;;
logcat)
  echo
  echo "==> logcat（Ctrl+C 结束）"
  "$ADB" -s "$DEV" logcat -v brief \
    DoobleApp:V DoobleEngine:V BcoreEngine:V BlackBoxCore:V JarManager:V \
    AndroidRuntime:E libc:F DEBUG:F "*:S"
  exit 0
  ;;
esac

if [ ! -f "$APK" ]; then
  echo "[ERROR] APK 不存在：$APK" >&2
  echo "        先跑：./gradlew.bat :app:assembleLiteDebug && cp app/build/outputs/apk/lite/debug/app-lite-debug.apk dist/" >&2
  exit 1
fi

echo
echo "==> 安装 $(basename "$APK")"
"$ADB" -s "$DEV" install -r -t "$APK" 2>&1 | tr -d '\r'

echo
echo "==> 安装后的 ABI 选择结果（这决定了 libblackbox.so 能不能被找到）"
"$ADB" -s "$DEV" shell "dumpsys package $PKG | grep -E 'primaryCpuAbi|secondaryCpuAbi|nativeLibraryDir'" 2>&1 | tr -d '\r'

echo
echo "==> 清空日志并启动"
"$ADB" -s "$DEV" logcat -c 2>/dev/null
"$ADB" -s "$DEV" shell am start -n "$PKG/com.dooble.space.MainActivity" 2>&1 | tr -d '\r'

echo
echo "==> 等待 12 秒，收集崩溃与引擎日志"
sleep 12
"$ADB" -s "$DEV" logcat -d -v brief 2>/dev/null | grep -aiE \
  "dooble|blackbox|bcore|AndroidRuntime|UnsatisfiedLink|dlopen|native bridge|JarManager|FATAL" \
  | tail -60

echo
echo "==> 进程状态"
"$ADB" -s "$DEV" shell "ps -A | grep dooble" 2>&1 | tr -d '\r'
echo "(若上面为空，说明进程已退出)"
