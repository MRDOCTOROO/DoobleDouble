package com.dooble.gms

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APK 下载的**共享实现**：断点续传、同源重试退避、完整性三道闸门。
 *
 * ## 为什么抽成一个文件
 *
 * microG 与 Aurora Store 两条供给链路各写过一份下载循环，缺陷是成对出现的：
 *  - 服务器**优雅断流**时 `read` 返回 -1、循环正常退出，不比对"实际收到的字节数"
 *    就把半截文件当成功——80MB 只下了 30MB 也照装，坏缓存还会永久命中；
 *  - 100MB 级的 GmsCore 没有断点续传：一次读停顿就废弃全部已传数据、
 *    换镜像从零重下，弱网下基本不可用且极度费流量；
 *  - 镜像（ghfast.top / gh-proxy.com）是第三方代理，TLS 在代理侧终结，
 *    字节可被替换——没有哈希校验等于把"承载 Google 登录态的 APK"交出去。
 *
 * 这里把三件事一次性做对，两条链路共用，避免将来再各错各的。
 *
 * ## 三道完整性闸门（顺序即优先级）
 *
 *  1. **Content-Range 前缀核对**：续传前确认服务器内容与本地 `.part` 接得上，
 *     对不上（上游换过文件）就作废重下，绝不拼出混合内容；
 *  2. **长度比对**：`Content-Length` 与实际字节数必须一致——这是发现
 *     "优雅断流"的唯一手段，长度不符保留 `.part` 续传重试；
 *  3. **SHA-256**：调用方传入上游 digest 时（GitHub release 的 `digest` 字段），
 *     校验不过整份作废重下。镜像只是加速器，**校验不过的字节绝不能进缓存**。
 */
internal object ApkHttp {

    /** 同一个源的重试次数（含第一次）。重试之间指数退避，重试仍走断点续传 */
    private const val ATTEMPTS_PER_SOURCE = 3

    private const val RETRY_BACKOFF_BASE_MS = 1_500L

    private const val BUF_BYTES = 128 * 1024

    /**
     * 下载 [url] 到 [dest]（原子：先写 `dest.name + ".part"`，成功后 rename）。
     *
     * @param expectedSha256 上游摘要（hex，不含 `sha256:` 前缀）。null 表示
     *   该源没有可信摘要（如 F-Droid 直链），退化为长度 + ZIP 结构校验。
     * @return true 表示 [dest] 已就绪且通过了全部闸门。
     */
    fun download(
        url: String,
        dest: File,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        expectedSha256: String? = null,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")

        for (attempt in 0 until ATTEMPTS_PER_SOURCE) {
            val retry = downloadAttempt(url, tmp, dest, connectTimeoutMs, readTimeoutMs, expectedSha256, onProgress)
            when {
                retry is AttemptResult.Done -> return true
                retry is AttemptResult.Fatal -> return false
                // AttemptResult.Retry：保留/清理后的 .part 还在，退避后续传
                attempt < ATTEMPTS_PER_SOURCE - 1 -> {
                    try {
                        Thread.sleep(RETRY_BACKOFF_BASE_MS shl attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
        }
        return false
    }

    private sealed interface AttemptResult {
        /** dest 已就绪 */
        data object Done : AttemptResult

        /** 重试无意义（4xx 之类），直接放弃本源 */
        data object Fatal : AttemptResult

        /** 本次尝试失败：保留 .part 退避重试 */
        data object Retry : AttemptResult
    }

    private fun downloadAttempt(
        url: String,
        tmp: File,
        dest: File,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): AttemptResult {
        var resumeFrom = if (tmp.isFile) tmp.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DoobleDouble/1.0 (Android)")
        }
        try {
            if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            val code = conn.responseCode
            // 4xx（除 408/429）重试没有意义：资源不存在或被拒绝，换下一个源
            if (code !in 200..299) {
                return if (code == 408 || code == 429 || code in 500..599) AttemptResult.Retry
                else AttemptResult.Fatal
            }

            if (code == 206) {
                // 服务器答应续传了，但要核对起点：`bytes <resumeFrom>-...` 对不上
                // 说明 .part 与当前上游内容不衔接（文件变过/缓存串了），作废重来
                val range = conn.getHeaderField("Content-Range").orEmpty()
                if (!range.startsWith("bytes $resumeFrom-")) {
                    tmp.delete()
                    return AttemptResult.Retry
                }
            } else {
                resumeFrom = 0 // 服务器不支持 Range（或返回了全新 200）：全量重下
            }

            val expectedTotal = if (code == 206) {
                resumeFrom + conn.contentLengthLong // 206 的 Content-Length 是剩余长度
            } else {
                conn.contentLengthLong
            }

            conn.inputStream.use { input ->
                FileOutputStream(tmp, /* append = */ code == 206).use { out ->
                    val buf = ByteArray(BUF_BYTES)
                    var done = resumeFrom
                    var lastReported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReported > 1_000_000) {
                            lastReported = done
                            onProgress(done, expectedTotal)
                        }
                    }
                    onProgress(done, expectedTotal)
                }
            }

            // ---- 闸门 2：长度。服务器优雅断流时循环是"正常"退出的，
            // 只有这里能发现少了一截。不符 → 保留 .part，退避后续传。
            if (expectedTotal > 0 && tmp.length() != expectedTotal) return AttemptResult.Retry

            // ---- 闸门 3：哈希。不过 = 字节被替换/损坏，整份作废重下。
            if (expectedSha256 != null) {
                val actual = sha256Hex(tmp)
                if (actual == null || !actual.equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    return AttemptResult.Retry
                }
            }

            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return AttemptResult.Retry
            }
            return AttemptResult.Done
        } catch (_: Throwable) {
            // 读超时 / 连接中断：保留 .part，退避后接着下
            return AttemptResult.Retry
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

/** 极简 APK 合法性校验：ZIP 魔数 PK\x03\x04 + 内含 AndroidManifest.xml */
internal fun looksLikeApk(file: File): Boolean {
    if (!file.exists() || file.length() < 1024) return false
    return runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null
        }
    }.getOrDefault(false)
}
