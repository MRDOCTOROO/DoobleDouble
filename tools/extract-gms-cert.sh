#!/usr/bin/env bash
# 从**你自己合法持有**的 GMS APK 里导出 Google 平台证书，用于「官方证书」模式。
#
# ============================================================================
# 先说清楚合规边界，这比脚本本身重要
# ============================================================================
#
# Google 平台证书（CN=Android, O=Google Inc.）是 Google 的资产，**不随本项目
# 分发**。这与"不打包原版 GMS"是同一条红线：本仓库里没有任何 Google 的二进制
# 内容，也不提供下载渠道。
#
# 这个脚本做的事只有一件：把**你设备上本来就有的**那个 GMS 包里的公开证书
# 取出来。证书是公开信息（每个装了 GMS 的设备上都能读到），但重新分发它属于
# 另一回事，所以：
#   - 产物写到 local/signature/，该目录已在 .gitignore 里，不进版本库；
#   - 请自行确认你所在地区对 GMS 的使用条款。
#
# ============================================================================
# 什么时候需要它
# ============================================================================
#
# **本地测试不需要。** microG + Aurora Store 的主链路（登录谷歌账号、下载安装、
# 多开）全程不校验签名。默认模式 OFF 就能跑通。
#
# 只有当你遇到"某个应用主动校验 GMS 签名、否则拒绝启动"时，才需要这张证书。
#
# ============================================================================
# 证书从哪来
# ============================================================================
#
# 三条路，任选其一：
#
#   1) 从真机/模拟器上拉（最推荐，来源最明确）
#        adb shell pm path com.google.android.gms
#        # 输出形如 package:/data/app/~~xxx/com.google.android.gms-yyy/base.apk
#        adb pull /data/app/~~xxx/com.google.android.gms-yyy/base.apk gms.apk
#
#   2) 从你手上已有的 GMS APK 文件（例如系统镜像里提取的）
#
#   3) 从 microG 的官方发布页拿 **GmsCore** 的 APK —— 注意：那是 microG 自己的
#      签名，**不是** Google 官方证书。用在这里会被脚本识别出来并警告。
#
# 用法：
#   tools/extract-gms-cert.sh <gms.apk> [输出目录]
#   默认输出到 <工程>/local/signature/
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

APK="${1:-}"
OUT_DIR="${2:-$PROJECT_ROOT/local/signature}"

# Google 官方 GMS 签名 SHA-1。公开且固定，microG 的
# Constants.GMS_PACKAGE_SIGNATURE_SHA1 用的也是同一个值。
EXPECTED_SHA1="38918a453d07199354f8b19af05ec6562ced5788"

# 与 GmsSignatureSpoof.CERT_ASSET_OFFICIAL 保持一致
DER_NAME="google_platform.der"

PYTHON_BIN="${PYTHON:-}"
if [ -z "$PYTHON_BIN" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      PYTHON_BIN="$candidate"
      break
    fi
  done
fi

if [ -z "$APK" ]; then
  cat >&2 <<EOF
用法：tools/extract-gms-cert.sh <gms.apk> [输出目录]

先拿到 GMS 的 APK：
  adb shell pm path com.google.android.gms
  adb pull <上面输出的路径> gms.apk
  tools/extract-gms-cert.sh gms.apk
EOF
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "[ERROR] 找不到文件：$APK" >&2
  exit 1
fi

if [ -z "$PYTHON_BIN" ]; then
  echo "[ERROR] 需要 python3（用来解析 APK 签名块）" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
DER="$OUT_DIR/$DER_NAME"

echo "==> 读取 APK 签名证书"
echo "    apk      : $APK"
echo "    extractor: $PYTHON_BIN"

# ------------------------------------------------------------------ 提取 DER
"$PYTHON_BIN" "$PROJECT_ROOT/tools/extract-apk-cert.py" "$APK" -o "$DER"

if [ ! -s "$DER" ]; then
  echo "[ERROR] 提取失败：$DER 是空的" >&2
  exit 1
fi

# ------------------------------------------------------------------ 指纹与主体
if ! command -v openssl >/dev/null 2>&1; then
  echo "[ERROR] 需要 openssl 来计算指纹并核对证书主体" >&2
  exit 1
fi

SHA1="$(openssl dgst -sha1 -binary "$DER" | od -An -tx1 | tr -d ' \n')"
SHA256="$(openssl dgst -sha256 -binary "$DER" | od -An -tx1 | tr -d ' \n')"
SUBJECT="$(openssl x509 -inform DER -in "$DER" -noout -subject 2>/dev/null | sed 's/^subject=//')"
NOT_AFTER="$(openssl x509 -inform DER -in "$DER" -noout -enddate 2>/dev/null | sed 's/^notAfter=//')"

echo ""
echo "==> 证书信息"
echo "    主体     : ${SUBJECT:-（无法解析）}"
echo "    有效期至 : ${NOT_AFTER:-（无法解析）}"
echo "    SHA-1    : $SHA1"
echo "    SHA-256  : $SHA256"
echo ""

# ------------------------------------------------------------------ 判定
if [ "$SHA1" = "$EXPECTED_SHA1" ]; then
  echo "==> ✅ 这就是 Google 官方平台证书（SHA-1 与 microG 的硬编码常量一致）"
  VERDICT=0
else
  cat <<EOF
==> ⚠️  这不是 Google 官方平台证书

    期望 SHA-1 : $EXPECTED_SHA1
    实际 SHA-1 : $SHA1

    常见原因：
      · 传进来的是 microG（GmsCore）的 APK —— 那是 microG 自己的签名；
      · 传进来的是某个第三方改包；
      · APK 有多个签名者，脚本取了第一张，而官方证书不是它。

    文件已经写出来了，但**不要**把它当官方证书用：
    容器会如实返回它，microG 也不会认可 —— 表现和「本地测试证书」模式一样。
    排障方向请看 docs/05-谷歌环境与签名伪造.md。
EOF
  VERDICT=1
fi

echo ""
echo "==> 产物"
echo "    $DER"
echo ""

# ------------------------------------------------------------------ 投递说明
cat <<EOF
==> 投递到设备（debug 包不需要 root）
    adb push "$DER" /sdcard/Android/data/com.dooble.space.debug/files/dooble/signature/

==> 然后在应用里
    空间详情 → 签名伪造 → 选「Google 官方证书」

    也可以先只看链路通不通：同一个页面的「自检」会比对
    策略期望值 vs 容器实际返回值，两者一致说明 hook 生效了。

==> 注意
    该文件位于 local/ 下，已在 .gitignore 里，不会进版本库。
    请勿把它提交、分发或打包进 APK。
EOF

exit "$VERDICT"
