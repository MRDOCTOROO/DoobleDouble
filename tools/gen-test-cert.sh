#!/usr/bin/env bash
# 生成一张本地自签的「测试用平台证书」，用于验证容器级签名伪造链路。
#
# ⚠️ 重要：这张证书**骗不过 microG**。
#    microG 的校验逻辑是「对证书原始 DER 字节做 SHA-1，与硬编码常量
#    38918a453d07199354f8b19af05ec6562ced5788 比对」，而 SHA-1 无法反推。
#    所以任何自签证书都不可能通过 microG 的官方签名校验。
#
#    它真正的用途是让下面这条链路变得**可验证**：
#        策略下发 → 容器 PMS 拦截 → 返回值被替换
#    链路通了，把证书换成真证书就能生效；链路不通，换什么证书都没用。
#
#    而且——本地测试本来就不需要签名伪造。microG + Aurora Store 的主链路
#    （登录谷歌账号、下载安装、多开）不依赖它。
#
# 用法：
#   tools/gen-test-cert.sh [输出目录]
#   默认输出到 <工程>/local/signature/
#
# 生成后如何让应用读到（二选一）：
#   A. adb push（推荐，debug 包无需 root）
#        adb push local/signature/test_platform.der \
#            /sdcard/Android/data/com.dooble.space.debug/files/dooble/signature/
#   B. 重新打包进 assets/signature/
set -euo pipefail

DEV_ROOT="${DOOBLE_DEV:-$HOME/android-dev}"
JDK="${JAVA_HOME:-$DEV_ROOT/jdk/jdk-17.0.20.1+1}"
KEYTOOL="$JDK/bin/keytool"

OUT_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/local/signature}"

ALIAS="dooble_test_platform"
STOREPASS="dooble-test"
KEYPASS="dooble-test"
KEYSTORE="$OUT_DIR/test_platform.p12"
DER="$OUT_DIR/test_platform.der"

# 主体名刻意使用 O=Dooble Test Platform 而不是 O=Google Inc.。
# 字段结构与真实平台证书一致（便于测试那些按 DN 解析的代码路径），
# 但一眼能看出是测试证书，不会被误当成官方证书。
SUBJECT="CN=Android, OU=Android, O=Dooble Test Platform, L=Mountain View, ST=California, C=US"

# 30 年。测试证书没必要轮换，短有效期只会在某天莫名其妙地失效。
VALIDITY=10950

if [ ! -x "$KEYTOOL" ]; then
  echo "[ERROR] keytool not found: $KEYTOOL" >&2
  echo "[ERROR] Set JAVA_HOME to a JDK 17+ install and retry." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

echo "==> Generating self-signed test certificate"
echo "    output dir : $OUT_DIR"
echo "    subject    : $SUBJECT"

rm -f "$KEYSTORE" "$DER"

"$KEYTOOL" -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity "$VALIDITY" \
  -dname "$SUBJECT" \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$STOREPASS" \
  -keypass "$KEYPASS" \
  >/dev/null

# 导出为 DER（二进制）。必须用 -rfc 的反面：容器侧是 new Signature(byte[])，
# 要的是原始 DER 字节，不是 PEM 文本。
"$KEYTOOL" -exportcert \
  -alias "$ALIAS" \
  -keystore "$KEYSTORE" \
  -storepass "$STOREPASS" \
  -file "$DER" \
  >/dev/null

SHA1="$(openssl dgst -sha1 -binary "$DER" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
SHA256="$(openssl dgst -sha256 -binary "$DER" 2>/dev/null | od -An -tx1 | tr -d ' \n')"

echo ""
echo "==> Done"
echo "    cert (DER) : $DER"
echo "    keystore   : $KEYSTORE"
echo "    SHA-1      : $SHA1"
echo "    SHA-256    : $SHA256"
echo ""
echo "    microG expects official SHA-1: 38918a453d07199354f8b19af05ec6562ced5788"
if [ "$SHA1" = "38918a453d07199354f8b19af05ec6562ced5788" ]; then
  echo "    (never equal in practice -- a match would mean breaking SHA-1 preimage)"
else
  echo "    -> not equal, as expected: this cert validates the hook chain only,"
  echo "       it can NOT pass microG signature verification."
fi
echo ""
echo "==> Push to device (debug build):"
echo "    adb push \"$DER\" /sdcard/Android/data/com.dooble.space.debug/files/dooble/signature/"
echo ""
echo "==> Then in the app: Space detail -> Signature spoof -> \"Local test certificate\""
