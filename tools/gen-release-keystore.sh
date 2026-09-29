#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 生成 release 签名密钥库，并打印需要填进 GitHub Secrets 的四项。
#
# ## 为什么必须有一个**固定**的 release 密钥
#
# Android 只允许「同签名」的 APK 覆盖安装。GitHub Actions 每次都是全新 runner，
# `~/.android/debug.keystore` 是当场生成的 —— 也就是每次签名都不同。
# 用 debug 签名发版，用户装了第一版之后**再也装不上第二版**：
#   INSTALL_FAILED_UPDATE_INCOMPATIBLE
# 只能卸载重装，而卸载会清掉所有空间、空间内应用和已登录的谷歌账号。
#
# ## 用法
#
#   tools/gen-release-keystore.sh            # 已有则拒绝覆盖
#   tools/gen-release-keystore.sh --force    # 强制重建（会换签名，见下方警告）
#
# 产物（整个 keystore/ 都在 .gitignore 里，不入库）：
#   keystore/dooble-release.jks      密钥库
#   keystore/keystore.properties     本地构建用的口令（CI 不用这个文件）
#   keystore/.storepass              纯口令，方便脚本复用
#
# ## ⚠️ --force 的后果
#
# 换密钥 = 换签名 = **老版本无法覆盖安装**。已经装在设备上的用户必须卸载重装。
# 除非密钥泄露，否则不要重建。
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS_DIR="$ROOT/keystore"
JKS="$KS_DIR/dooble-release.jks"
PROPS="$KS_DIR/keystore.properties"

ALIAS="${DOOBLE_KEY_ALIAS:-dooble}"
VALIDITY_DAYS="${DOOBLE_KEY_VALIDITY_DAYS:-10950}"   # 30 年

KEYTOOL="${KEYTOOL:-}"
if [ -z "$KEYTOOL" ]; then
  for cand in \
    "${JAVA_HOME:-}/bin/keytool" \
    "C:/Users/admin/android-dev/jdk/jdk-17.0.20.1+1/bin/keytool.exe" \
    "$(command -v keytool 2>/dev/null || true)"
  do
    if [ -n "$cand" ] && [ -x "$cand" ]; then KEYTOOL="$cand"; break; fi
  done
fi
[ -n "$KEYTOOL" ] || { echo "[ERROR] 找不到 keytool。设 JAVA_HOME 或 KEYTOOL 环境变量。" >&2; exit 1; }

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

if [ -f "$JKS" ] && [ "$FORCE" -ne 1 ]; then
  echo "[SKIP] $JKS 已存在。"
  echo "       要重建请显式加 --force —— 注意换密钥会让老版本无法覆盖安装。"
  exit 0
fi

mkdir -p "$KS_DIR"

# 口令：优先用环境变量，否则随机生成 28 位。
# 刻意不用 openssl（Git Bash 里未必有），用 python 的 secrets 更稳。
PASS="${DOOBLE_STORE_PASSWORD:-}"
if [ -z "$PASS" ]; then
  PY="${PYTHON:-python}"
  PASS="$("$PY" -c 'import secrets,string; a=string.ascii_letters+string.digits; print("".join(secrets.choice(a) for _ in range(28)))')"
fi

echo "==> 生成密钥库（RSA 4096，$VALIDITY_DAYS 天）"
"$KEYTOOL" -genkeypair \
  -keystore "$JKS" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Dooble Double, OU=Personal, O=Dooble, L=Unknown, ST=Unknown, C=CN"

printf '%s' "$PASS" > "$KS_DIR/.storepass"

cat > "$PROPS" <<EOF
# 本地 release 签名配置（整个 keystore/ 目录都在 .gitignore 里，不入库）
#
# CI 上不用这个文件：workflow 从 GitHub Secrets 取
#   KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
# 并把 jks 解到 \$RUNNER_TEMP 下，通过 DOOBLE_KEYSTORE_PATH 指给 Gradle。
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

echo
echo "==> 指纹"
"$KEYTOOL" -list -v -keystore "$JKS" -storepass "$PASS" 2>/dev/null \
  | grep -E "SHA1:|SHA256:" | sed 's/^/    /'

echo
echo "==> 接下来把这些填进 GitHub Secrets"
echo "    仓库 → Settings → Secrets and variables → Actions → New repository secret"
echo
echo "    名称                   值"
echo "    --------------------   ------------------------------------------"
echo "    KEYSTORE_BASE64        （下面这串，整段复制）"
echo "    KEYSTORE_PASSWORD      $PASS"
echo "    KEY_ALIAS              $ALIAS"
echo "    KEY_PASSWORD           $PASS"
echo
echo "    ---- KEYSTORE_BASE64 开始 ----"
base64 -w0 "$JKS" 2>/dev/null || base64 "$JKS" | tr -d '\n'
echo
echo "    ---- KEYSTORE_BASE64 结束 ----"
echo
echo "    也可以用 tools/set-github-secrets.sh 一次性写进去（需要 pynacl）。"
echo
echo "[OK] 完成。keystore/ 整个目录都在 .gitignore 里，不会被提交。"
