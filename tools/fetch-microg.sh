#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# fetch-microg.sh —— 把 microG 组件下载到 gms/src/main/assets/microg/
#
# 用途：把 GmsCore / FakeStore 预先打进 APK，实现「离线装配谷歌环境」。
#       不打进 APK 也能用——应用会在首次装配时自己下载，并缓存在
#       /data/data/<pkg>/files/dooble/gms-cache（跨空间共享）。
#
# 用法：
#   ./tools/fetch-microg.sh            # 下载到 assets
#   ./tools/fetch-microg.sh --hw       # 使用华为变体（-hw 后缀）
#   ./tools/fetch-microg.sh --clean    # 清空 assets 中的 APK
#
# 许可：microG 采用 Apache-2.0，允许再分发。分发时请保留其 LICENSE。
# ---------------------------------------------------------------------------
set -euo pipefail

TAG="v0.3.16.252432"
GH="https://github.com/microg/GmsCore/releases/download"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/gms/src/main/assets/microg"

HUAWEI=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --hw) HUAWEI=1 ;;
    --clean) CLEAN=1 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

if [ "$CLEAN" = "1" ]; then
  rm -f "$DEST"/*.apk
  echo "已清空 $DEST"
  exit 0
fi

mkdir -p "$DEST"

# 镜像前缀，按顺序回退
MIRRORS=(
  ""
  "https://ghfast.top/"
  "https://gh-proxy.com/"
)

fetch() {
  local remote_name="$1"
  local local_name="$2"
  local out="$DEST/$local_name"

  if [ -s "$out" ]; then
    echo "跳过（已存在）: $local_name"
    return 0
  fi

  for prefix in "${MIRRORS[@]}"; do
    local url="${prefix}${GH}/${TAG}/${remote_name}"
    echo "尝试: $url"
    if curl -L --fail --connect-timeout 15 --max-time 1800 \
            -o "$out.part" "$url" 2>/dev/null; then
      # 极简校验：ZIP 魔数
      if head -c 2 "$out.part" | grep -q "PK"; then
        mv "$out.part" "$out"
        echo "  ✓ $local_name  ($(du -h "$out" | cut -f1))"
        return 0
      else
        echo "  ✗ 内容不是 ZIP，换镜像"
        rm -f "$out.part"
      fi
    else
      echo "  ✗ 失败，换镜像"
      rm -f "$out.part" 2>/dev/null || true
    fi
  done

  echo "  !! $remote_name 全部镜像失败，请手动下载后放到 $DEST"
  return 1
}

if [ "$HUAWEI" = "1" ]; then
  fetch "com.google.android.gms-252432032-hw.apk" "com.google.android.gms.apk"
  fetch "com.android.vending-84022632-hw.apk" "com.android.vending.apk"
else
  fetch "com.google.android.gms-252432032.apk" "com.google.android.gms.apk"
  fetch "com.android.vending-84022632.apk" "com.android.vending.apk"
fi

echo
echo "完成。可选组件（GsfProxy / MapsV1）不在 GmsCore 仓库中，"
echo "如需可从以下仓库手动下载后放入同一目录："
echo "  GsfProxy : https://github.com/microg/GsfProxy/releases"
echo "  MapsV1   : https://github.com/microg/android_packages_apps_GmsCore"
echo
echo "注意：assets 中的 APK 已在 .gitignore 中排除，不会被提交。"
