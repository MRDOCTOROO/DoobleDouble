#!/usr/bin/env bash
# 从上游源码构建 Bcore 虚拟化引擎，产出 engine/libs/bcore.aar。
#
# 为什么要自己编而不是用现成产物：Bcore 以源码形式分发，没有官方 Maven 包。
# 它的 native 层（Dobby inline hook、xdl、ELF 解析）必须按目标 ABI 现场编译。
#
# 为什么要打补丁：容器级签名伪造需要在容器的 PackageManagerService 出口
# 挂一个接入点（tools/bcore-patch/）。补丁**只加接入点、不写死策略**——
# 宿主不注入钩子时，容器行为与未打补丁时完全一致，所以补丁本身是零风险的。
#
# 前置要求（比主工程苛刻，注意）：
#   - JDK 21     Bcore 的 build.gradle 用 Java 21 语法
#   - NDK 29     Bcore 的 native 构建脚本要求 r29
#   主工程只需要 JDK 17 + NDK 26；两者互不影响，可以并存。
#
# 用法：
#   tools/build-bcore.sh [上游仓库地址]
#   默认克隆到 $DOOBLE_DEV/nbb-src，可用环境变量 DOOBLE_DEV 覆盖根目录。
set -euo pipefail

REPO_URL="${1:-https://github.com/ALEX5402/NewBlackbox.git}"
DEV_ROOT="${DOOBLE_DEV:-$HOME/android-dev}"
SRC_DIR="$DEV_ROOT/nbb-src"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$PROJECT_ROOT/engine/libs"
PATCH_DIR="$PROJECT_ROOT/tools/bcore-patch"

JDK21="${JDK21_HOME:-$DEV_ROOT/jdk21/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$DEV_ROOT/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ ! -x "$JDK21/bin/java" ]; then
  echo "[ERROR] JDK 21 not found: $JDK21" >&2
  echo "        Set JDK21_HOME to a JDK 21 install and retry." >&2
  exit 1
fi
export JAVA_HOME="$JDK21"

if [ ! -d "$SRC_DIR" ]; then
  echo "==> Cloning Bcore upstream into $SRC_DIR"
  git clone --depth 1 "$REPO_URL" "$SRC_DIR"
fi

# ---------------------------------------------------------------------------
# 应用 Dooble 补丁（幂等）
#
# 三种情况都要能正确区分，否则重复执行脚本会炸：
#   1. 补丁没打过        -> apply
#   2. 补丁已经打过      -> skip（用 --reverse --check 探测）
#   3. 上游改了相关代码  -> 报错退出，让人工介入，而不是留下一个半成品 AAR
#
# 关于 `-C1`：探测"是否已应用"时把上下文压到 1 行。
#
# 补丁是顺序应用的，编号靠后的补丁如果改动落在靠前补丁 hunk 的 3 行上下文
# 窗口内，就会让靠前补丁的 `--reverse --check`（默认 3 行上下文）失败——
# 表现是"明明已经打过，却报上游改了代码"。
# 压缩探测上下文能容忍这种邻接；而**正向应用仍然用默认的 3 行**，
# 保证"真的冲突"依然会被拦住。
#
# 根治办法是让各补丁的改动互不落在对方的上下文窗口里（见 0003 里
# BlackBoxCore.getStorePackages() 的注释），`-C1` 只是兜底。
# ---------------------------------------------------------------------------
apply_patches() {
  [ -d "$PATCH_DIR" ] || return 0

  local patches=()
  while IFS= read -r -d '' f; do patches+=("$f"); done \
    < <(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' -print0 | sort -z)
  [ ${#patches[@]} -gt 0 ] || return 0

  # --- 戳记机制 ---------------------------------------------------------------
  # 为什么需要它：原来的做法是逐个补丁做 `git apply --reverse --check` 来判断
  # "是否已应用"。这个判据**会被后加的补丁破坏** —— 只要新补丁的改动落进了
  # 老补丁的上下文窗口（前后各 3 行），老补丁的 reverse-check 就会失败，
  # 于是脚本报 "Patch does not apply cleanly" 并退出。0005 就踩了这个。
  #
  # 所以：用「全部补丁内容的哈希」做戳记。戳记匹配 + 工作区是脏的（说明补丁
  # 确实已经打进去了），就直接跳过整个打补丁环节。补丁集合一变，哈希就变，
  # 戳记自动失效；工作区被 `git checkout .` 清干净时也会失效。
  # ---------------------------------------------------------------------------
  local stamp="$SRC_DIR/.dooble-patches.stamp"
  local want
  want="$(cat "${patches[@]}" | git hash-object --stdin)"

  if [ -f "$stamp" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$want" ]; then
    if [ -n "$(git -C "$SRC_DIR" status --porcelain 2>/dev/null)" ]; then
      echo "==> Dooble patches already applied (stamp matches), skipping"
      return 0
    fi
    echo "==> Stamp matched but working tree is clean; re-applying patches"
  fi

  echo "==> Applying Dooble patches"
  cd "$SRC_DIR"
  local p name
  for p in "${patches[@]}"; do
    name="$(basename "$p")"
    if git apply --check --whitespace=nowarn "$p" >/dev/null 2>&1; then
      git apply --whitespace=nowarn "$p"
      echo "    [apply] $name"
    elif git apply --reverse --check -C1 --whitespace=nowarn "$p" >/dev/null 2>&1; then
      echo "    [skip ] $name (already applied)"
    else
      echo "[ERROR] Patch does not apply cleanly: $name" >&2
      echo "" >&2
      echo "        两个常见原因，先分清再动手：" >&2
      echo "        1) 上游改了 —— 用 git -C \"\$SRC_DIR\" log --oneline -1 确认 HEAD，再重新生成补丁。" >&2
      echo "        2) 有人手工改了 \$SRC_DIR 里的文件。" >&2
      echo "           这会让先前补丁的 --reverse 自检失败，于是判不出 already applied。" >&2
      echo "           修法：git -C \"\$SRC_DIR\" checkout -- 被手改的文件，" >&2
      echo "           还原成上一个补丁之后的状态，再让脚本按序打补丁。" >&2
      echo "           正确做法是永远只通过 tools/bcore-patch 下的补丁改上游源码。" >&2
      exit 1
    fi
  done

  echo "$want" > "$stamp"
}

apply_patches

echo "==> Building :Bcore:assembleDebug (native compile is slow, ~10 min on first run)"
cd "$SRC_DIR"
./gradlew.bat :Bcore:assembleDebug --no-daemon

# ---------------------------------------------------------------------------
# 单独构建 :black-reflection
#
# Bcore 的 build.gradle 里写的是 `implementation(project(":black-reflection"))`，
# 而 :black-reflection 是上游源码树里的**独立 java-library 模块**（纯 JDK 反射框架，
# 零外部依赖）。两个后果叠加：
#   1. implementation 不传递 -> AAR 的 classes.jar 里根本没有 blackreflection 包；
#   2. 它是本地源码模块，没有 Maven 坐标 -> 无法像 FreeReflection 那样用
#      libs.xxx 声明，也不能靠 Gradle 自动传递依赖捞回来。
#
# 症状：安装后 App 能启动、UI 正常，但 Bcore 引擎初始化直接抛
#   NoClassDefFoundError: Failed resolution of:
#     Ltop/niunaijun/blackreflection/BlackReflection;
#     at top.niunaijun.blackbox.BlackBoxCore.<init>
# 而且因为它在 attachBaseContext 里，表现为「引擎静默降级」——很容易被当成
# 「模拟器架构不支持」而误判。实际是缺一个 20KB 的 jar。
#
# 所以这里把它单独编出来，作为第二个产物放到 engine/libs/，
# 由 :app 的 fileTree("../engine/libs") { include("*.aar", "*.jar") } 打进 APK。
#
# 验证方法（任何时候都值得重跑一遍）：
#   jdeps -verbose:class <bcore.aar 里的 classes.jar> | 找引用了但不在 jar 内的
#   top.niunaijun.* 类。修好后应为空。
# ---------------------------------------------------------------------------
echo "==> Building :black-reflection:jar (Bcore 的运行时依赖，AAR 不传递)"
cd "$SRC_DIR"
./gradlew.bat :black-reflection:jar --no-daemon

BR_JAR="$SRC_DIR/black-reflection/build/libs/black-reflection.jar"
if [ ! -f "$BR_JAR" ]; then
  echo "[ERROR] black-reflection.jar was not produced" >&2
  exit 1
fi

AAR="$(find "$SRC_DIR/Bcore/build/outputs/aar" -name "*.aar" | head -1)"
if [ -z "$AAR" ]; then
  echo "[ERROR] Build finished but no AAR was produced" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# AAR 瘦身：剔掉运行时用不到的上游 assets
#
# Bcore 的 assets 里有两个 jar：
#   empty.jar  1483 字节   **必需**。NativeCore / BActivityThread 拿它当
#                          兜底 DEX 加载目标（空 jar 才能被 DexClassLoader 接受）。
#   junit.jar  87KB        **不需要**。JarConfig 里它 required=false，
#                           全工程唯一的引用点是 JarManagerTest（上游的开发自测类）。
#                           删掉后 JarManager 只会打一条 "Optional JAR file missing" 警告。
#
# 为什么不改上游源码：那是上游的测试资产，动源码会让补丁集变大、更容易和上游冲突。
# 在这里后处理 AAR，改动面最小，且重新执行本脚本即可复现。
# ---------------------------------------------------------------------------
echo "==> Slimming AAR (dropping runtime-unused assets)"
SLIM_TMP="$(mktemp -d)"
cp "$AAR" "$SLIM_TMP/bcore.aar"
# 用 Python 而不是 `zip -d`：Git Bash / 精简容器里常常没有 zip，
# 而 Python 是构建 Android 工程的硬依赖，一定在。
# raw copy 其余条目，不重新压缩，避免产物哈希无谓变化。
PY="${PYTHON_BIN:-python}"
if "$PY" - "$SLIM_TMP/bcore.aar" <<'PYEOF'
import sys, zipfile, shutil, os
path = sys.argv[1]
DROP = {"assets/junit.jar"}
tmp = path + ".slim"
zin = zipfile.ZipFile(path, "r")
names = {i.filename for i in zin.infolist()}
if not (DROP & names):
    zin.close()
    print("    [skip ] assets/junit.jar (already absent)")
    sys.exit(0)
zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    if it.filename in DROP:
        continue
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = it.compress_type
    zi.external_attr = it.external_attr
    zout.writestr(zi, zin.read(it.filename))
zout.close()
zin.close()
shutil.move(tmp, path)
print("    [drop ] assets/junit.jar")
PYEOF
then :; else
  echo "[ERROR] AAR slimming failed; keeping the original AAR" >&2
  cp "$AAR" "$SLIM_TMP/bcore.aar"
fi

mkdir -p "$OUT_DIR"
cp "$SLIM_TMP/bcore.aar" "$OUT_DIR/bcore.aar"
cp "$BR_JAR" "$OUT_DIR/black-reflection.jar"
rm -rf "$SLIM_TMP"
echo "==> Output: $OUT_DIR/bcore.aar ($(du -h "$OUT_DIR/bcore.aar" | cut -f1))"
echo "            $OUT_DIR/black-reflection.jar ($(du -h "$OUT_DIR/black-reflection.jar" | cut -f1))"
echo "    Rebuild the main project to pick it up."
