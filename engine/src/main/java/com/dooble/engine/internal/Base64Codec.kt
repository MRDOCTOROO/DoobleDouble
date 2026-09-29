package com.dooble.engine.internal

/**
 * 极简 Base64 编解码（RFC 4648 标准字母表）。
 *
 * ## 为什么不用现成的
 *
 * 策略文件的两端跨模块、跨进程：
 *  - **写入方**在 `gms` 模块的主进程；
 *  - **读取方**在引擎的容器服务进程。
 *
 * 两端用的必须是**同一套编码**，否则证书字节会静默错位。而两个现成选项都不合适：
 *
 * | 方案 | 问题 |
 * |---|---|
 * | `android.util.Base64` | JVM 单元测试里是抛 `RuntimeException("Stub!")` 的桩实现——用它意味着「格式契约测试」根本跑不起来 |
 * | `java.util.Base64` | 要 API 26，本工程 minSdk 是 24 |
 *
 * 三十行纯 Kotlin 换来「编解码可被单测覆盖」，这笔账很划算。
 */
object Base64Codec {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private const val PAD = '='

    /** 反查表。非法字符留 -1，解码时会被拒绝而不是当成 0 静默产出垃圾字节 */
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0

        // 整三字节组
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
                .append(ALPHABET[(n ushr 12) and 0x3F])
                .append(ALPHABET[(n ushr 6) and 0x3F])
                .append(ALPHABET[n and 0x3F])
            i += 3
        }

        // 尾部 1 或 2 字节，补 '='
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                    .append(ALPHABET[(n ushr 12) and 0x3F])
                    .append(PAD).append(PAD)
            }

            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                    .append(ALPHABET[(n ushr 12) and 0x3F])
                    .append(ALPHABET[(n ushr 6) and 0x3F])
                    .append(PAD)
            }
        }

        return out.toString()
    }

    /**
     * @throws IllegalArgumentException 遇到非法字符或长度不合法时抛出。
     *         调用方**必须**接住——策略文件坏掉应该是"不伪造"，而不是"容器崩了"。
     */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 4 == 0) { "Base64 长度必须是 4 的倍数：${clean.length}" }

        val out = java.io.ByteArrayOutputStream(clean.length / 4 * 3)
        var buffer = 0
        var bits = 0

        for (ch in clean) {
            if (ch == PAD) break
            val value = if (ch.code < 128) DECODE_TABLE[ch.code] else -1
            require(value >= 0) { "非法的 Base64 字符：'$ch'" }

            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }

        return out.toByteArray()
    }
}
