package com.dooble.gms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络连通性自检。
 *
 * ## 为什么必须做这个
 *
 * microG 的 Google 登录第一步是 **checkin**（向 `android.clients.google.com`
 * 注册设备）。这一步失败时，microG 只会弹一句
 * 「与 Google 服务器通信时发生问题，请稍后重试」—— 用户完全无从判断
 * 是自己网络的问题、还是容器/应用坏了。
 *
 * 实测（真机 Xiaomi / Android 16 / FlClash 代理）：
 * ```
 * W/GmsAuthLoginBrowser: Checkin failed
 *   javax.net.ssl.SSLHandshakeException: connection closed
 *   Caused by: java.io.EOFException: connection closed
 * ```
 * 同时设备上 `curl https://www.google.com` 返回 `000`，
 * `www.google.com` 被解析到 `69.171.235.22`（Facebook 的 IP 段）——
 * **DNS 被污染 + 代理没有生效**。这是纯环境问题，但用户看到的只是
 * 「登录失败」，会一路怀疑到容器实现上。
 *
 * 所以这里做一次**可归因**的探测：分别试一个国内站点和一个 Google 端点，
 * 用两者的组合把结论说死。
 */
object NetworkProbe {

    /** 一个国内可达的站点。用它区分"完全没网"和"只有被墙的站点不通" */
    private const val DOMESTIC = "https://www.baidu.com"

    /**
     * Google 的连通性探针端点。
     *
     * 选 `generate_204` 而不是 `accounts.google.com`：前者就是为连通性检测设计的，
     * 正常返回 204 且响应体为空，不会因为地区重定向而误判。
     */
    private const val GOOGLE = "https://www.gstatic.com/generate_204"

    /** microG 登录真正要用的端点，单独测一次 */
    private const val CHECKIN = "https://android.clients.google.com/checkin"

    enum class Verdict {
        /** 国内外都通 —— 网络没问题 */
        OK,

        /** 国内通、Google 不通 —— 代理没生效，或被墙 */
        GOOGLE_BLOCKED,

        /** 连国内都不通 —— 设备本身没有可用网络 */
        NO_NETWORK,
    }

    data class Result(
        val verdict: Verdict,
        val domesticCode: Int,
        val googleCode: Int,
        val checkinCode: Int,
        val detail: String,
    ) {
        /** 能否直接用于 microG 登录 */
        val loginReady: Boolean get() = verdict == Verdict.OK
    }

    suspend fun run(timeoutMs: Int = 6000): Result = withContext(Dispatchers.IO) {
        // 三个探测**并发**跑。串行的话最坏要 3×timeout（18s），
        // 而自检面板是在用户"感觉坏了"的时候打开的，等 18 秒等于劝退。
        val domesticDeferred = async { probe(DOMESTIC, timeoutMs) }
        val googleDeferred = async { probe(GOOGLE, timeoutMs) }
        val checkinDeferred = async { probe(CHECKIN, timeoutMs) }

        val domestic = domesticDeferred.await()
        val google = googleDeferred.await()
        val checkin = checkinDeferred.await()

        val verdict = when {
            google.code in 200..399 -> Verdict.OK
            domestic.code in 200..399 -> Verdict.GOOGLE_BLOCKED
            else -> Verdict.NO_NETWORK
        }

        Result(
            verdict = verdict,
            domesticCode = domestic.code,
            googleCode = google.code,
            checkinCode = checkin.code,
            detail = buildDetail(verdict, domestic, google, checkin),
        )
    }

    private fun buildDetail(
        verdict: Verdict,
        domestic: Probe,
        google: Probe,
        checkin: Probe,
    ): String = when (verdict) {
        Verdict.OK ->
            "国内外站点都可达，microG 的 checkin 端点也通。可以正常登录 Google 账号。"

        Verdict.GOOGLE_BLOCKED ->
            "国内站点可达（$DOMESTIC → ${domestic.code}），但 Google 不可达" +
                "（$GOOGLE → ${google.code}${google.error?.let { "，$it" } ?: ""}）。" +
                "说明**代理/加速没有生效**，或者被中间设备阻断了。" +
                "常见原因：① 代理订阅过期或节点全挂；② 代理规则把 Google 走了直连；" +
                "③ DNS 被污染（把 www.google.com 解析到别的公司的 IP）。" +
                "microG 登录必须能连上 $CHECKIN，请先修好网络再试。"

        Verdict.NO_NETWORK ->
            "连国内站点都不可达（$DOMESTIC → ${domestic.code}" +
                "${domestic.error?.let { "，$it" } ?: ""}）。" +
                "设备当前没有可用网络，或者被系统限制了联网权限。"
    }

    private data class Probe(val code: Int, val error: String?)

    /**
     * 探一次。**不抛异常** —— 网络失败是常态，调用方要的是"结论"而不是异常。
     *
     * 返回的 `code` 用 0 表示"连不上"，与 HTTP 状态码区分开。
     */
    private fun probe(url: String, timeoutMs: Int): Probe = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            requestMethod = "GET"
            // 有些代理会按 UA 分流；用一个明确的 UA 避免被当成爬虫拦掉
            setRequestProperty("User-Agent", "DoobleDouble/1.0 (Android)")
        }
        try {
            Probe(conn.responseCode, null)
        } finally {
            conn.disconnect()
        }
    }.getOrElse { t ->
        Probe(0, t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
    }
}
