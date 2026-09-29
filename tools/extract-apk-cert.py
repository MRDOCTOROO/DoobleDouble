#!/usr/bin/env python3
"""从一个 APK 里抠出**签名证书的原始 DER 字节**。

## 为什么要自己写

`keytool -printcert -jarfile` 只能读 v1（JAR）签名。2020 年之后发布的 APK
普遍是 v2/v3 签名，很多甚至**没有** v1 签名 —— 那些包用 keytool 会直接报
"Not a signed jar file"。而 Google 的 GMS 包正好属于这一类。

`apksigner verify --print-certs` 倒是能读 v2/v3，但它只打印指纹
（SHA-1/SHA-256 的十六进制），**拿不到证书字节本身**。
我们需要的恰恰是字节本身：容器侧是 `new Signature(byte[])`。

所以这里直接按规范解析 APK Signing Block。纯标准库，无第三方依赖。

## 两种签名格式

- **v2 / v3**：证书在 APK Signing Block 里（中央目录之前的一段），
  结构是层层嵌套的 length-prefixed 序列，路径固定：
  `signers[0] → signedData → certificates[0]`
- **v1**：证书在 `META-INF/*.RSA|DSA|EC` 里，是一个 PKCS#7 容器。
  Python 标准库解不了 PKCS#7，交给 `openssl pkcs7`。

用法：
    python tools/extract-apk-cert.py <apk> [-o out.der]

默认把 DER 写到标准输出（二进制），用 `-o` 写文件。
"""

from __future__ import annotations

import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# APK Signing Block 的魔数，位于中央目录起始位置往前 16 字节
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"

# 签名方案的 block id
BLOCK_ID_V2 = 0x7109871A
BLOCK_ID_V3 = 0xF05368C0
# v3.1 也在部分包上出现，结构同 v3
BLOCK_ID_V31 = 0x1B93AD61

V1_CERT_SUFFIXES = (".RSA", ".DSA", ".EC")


class ExtractError(Exception):
    pass


# --------------------------------------------------------------------------- v2/v3


def _read_length_prefixed(buf: bytes, pos: int) -> tuple[bytes, int]:
    """读一个 4 字节小端长度 + 内容"""
    if pos + 4 > len(buf):
        raise ExtractError("签名块结构被截断（读长度越界）")
    size = struct.unpack_from("<I", buf, pos)[0]
    start = pos + 4
    end = start + size
    if end > len(buf):
        raise ExtractError("签名块结构被截断（读内容越界）")
    return buf[start:end], end


def _find_signing_block(data: bytes) -> tuple[int, int]:
    """返回 (block_start, block_end)；找不到返回 (-1, -1)"""
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ExtractError("不是合法的 zip：找不到 EOCD")

    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset < 32 or cd_offset > len(data):
        raise ExtractError("EOCD 里的中央目录偏移不合理")

    if data[cd_offset - 16:cd_offset] != APK_SIG_BLOCK_MAGIC:
        # 没有签名块：可能是纯 v1 签名，或压根没签名
        return -1, -1

    # 块尾部的 size 与块头部的 size 必须一致，这是规范要求的自校验
    size_at_end = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    block_start = cd_offset - size_at_end - 8
    if block_start < 0:
        raise ExtractError("签名块起始位置越界")

    size_at_start = struct.unpack_from("<Q", data, block_start)[0]
    if size_at_start != size_at_end:
        raise ExtractError("签名块首尾长度不一致，文件可能被改动过")

    return block_start, cd_offset


def _iter_block_pairs(data: bytes, block_start: int, block_end: int):
    """遍历签名块里的 id-value 对"""
    pos = block_start + 8  # 跳过头部 size
    end = block_end - 24  # 跳过尾部 size(8) + magic(16)
    while pos < end:
        if pos + 8 > end:
            break
        pair_len = struct.unpack_from("<Q", data, pos)[0]
        pos += 8
        value_start = pos + 4
        value_end = pos + pair_len
        if value_end > end:
            raise ExtractError("签名块的 id-value 对越界")
        block_id = struct.unpack_from("<I", data, pos)[0]
        yield block_id, data[value_start:value_end]
        pos = value_end


def _cert_from_scheme_value(value: bytes) -> bytes:
    """从 v2/v3 的 scheme block 里取出第一张证书的 DER

    嵌套路径（每层都是 length-prefixed 序列）：
        signers[0] -> signer -> signedData -> (digests, certificates)
        certificates[0] -> 证书 DER
    """
    signers, _ = _read_length_prefixed(value, 0)
    signer, _ = _read_length_prefixed(signers, 0)
    signed_data, _ = _read_length_prefixed(signer, 0)
    # signedData 的第一项是 digests，第二项才是 certificates，跳过前者
    _, pos = _read_length_prefixed(signed_data, 0)
    certificates, _ = _read_length_prefixed(signed_data, pos)
    cert, _ = _read_length_prefixed(certificates, 0)
    if not cert:
        raise ExtractError("证书项为空")
    return cert


def extract_from_signing_block(apk: Path) -> bytes | None:
    data = apk.read_bytes()
    block_start, block_end = _find_signing_block(data)
    if block_start < 0:
        return None

    # v3 优先于 v2：同一张证书，但 v3 的信息更完整。
    # 真要命的是"有 v3 但没 v2"的新包，所以不能只看 v2。
    for wanted in (BLOCK_ID_V3, BLOCK_ID_V31, BLOCK_ID_V2):
        for block_id, value in _iter_block_pairs(data, block_start, block_end):
            if block_id == wanted:
                try:
                    return _cert_from_scheme_value(value)
                except ExtractError:
                    continue
    return None


# --------------------------------------------------------------------------- v1


def extract_from_v1(apk: Path) -> bytes | None:
    """从 META-INF/*.RSA 里取证书。需要 openssl 来解 PKCS#7 容器。"""
    openssl = shutil.which("openssl")
    if openssl is None:
        raise ExtractError(
            "这个 APK 只有 v1 签名，解 PKCS#7 需要 openssl，但 PATH 里找不到它"
        )

    with zipfile.ZipFile(apk) as zf:
        candidates = [
            n for n in zf.namelist()
            if n.upper().startswith("META-INF/") and n.upper().endswith(V1_CERT_SUFFIXES)
        ]
        if not candidates:
            return None
        # 取第一个即可：多签名者场景下容器也只需要"主签名"
        blob = zf.read(sorted(candidates)[0])

    with tempfile.TemporaryDirectory() as tmp:
        pkcs7 = Path(tmp) / "cert.pkcs7"
        pem = Path(tmp) / "cert.pem"
        der = Path(tmp) / "cert.der"
        pkcs7.write_bytes(blob)

        # PKCS#7 -> PEM
        r = subprocess.run(
            [openssl, "pkcs7", "-inform", "DER", "-in", str(pkcs7),
             "-print_certs", "-out", str(pem)],
            capture_output=True,
        )
        if r.returncode != 0 or not pem.exists():
            raise ExtractError(
                "openssl 解析 PKCS#7 失败：" + r.stderr.decode("utf-8", "replace").strip()
            )

        # PEM 里可能有多张证书，只取第一张
        text = pem.read_text("utf-8", errors="replace")
        begin = text.find("-----BEGIN CERTIFICATE-----")
        end = text.find("-----END CERTIFICATE-----")
        if begin < 0 or end < 0:
            raise ExtractError("PKCS#7 里没有找到证书")
        first = text[begin:end + len("-----END CERTIFICATE-----")] + "\n"
        first_pem = Path(tmp) / "first.pem"
        first_pem.write_text(first, "utf-8")

        r = subprocess.run(
            [openssl, "x509", "-in", str(first_pem), "-outform", "DER", "-out", str(der)],
            capture_output=True,
        )
        if r.returncode != 0 or not der.exists():
            raise ExtractError(
                "openssl 转 DER 失败：" + r.stderr.decode("utf-8", "replace").strip()
            )
        return der.read_bytes()


# --------------------------------------------------------------------------- 入口


def extract(apk: Path) -> bytes:
    if not apk.is_file():
        raise ExtractError(f"文件不存在：{apk}")

    # v2/v3 优先：它给出的就是原始 DER，不需要任何外部工具
    try:
        cert = extract_from_signing_block(apk)
    except ExtractError as e:
        print(f"[warn] 解析 APK Signing Block 失败：{e}", file=sys.stderr)
        cert = None
    if cert:
        return cert

    cert = extract_from_v1(apk)
    if cert:
        return cert

    raise ExtractError(
        "这个 APK 里既没有 v2/v3 签名块，也没有 v1 签名证书 —— 它可能是未签名的"
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="提取 APK 签名证书的原始 DER 字节",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("apk", type=Path, help="APK 文件路径")
    parser.add_argument("-o", "--out", type=Path, help="输出 DER 文件（省略则写标准输出）")
    args = parser.parse_args()

    try:
        der = extract(args.apk)
    except ExtractError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        return 1

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_bytes(der)
        print(f"[ok] {len(der)} 字节 -> {args.out}", file=sys.stderr)
    else:
        sys.stdout.buffer.write(der)
    return 0


if __name__ == "__main__":
    sys.exit(main())
