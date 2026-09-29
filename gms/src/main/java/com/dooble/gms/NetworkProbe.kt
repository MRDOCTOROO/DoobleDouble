package com.dooble.gms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        /** 国内外都通，且 microG 要用的 checkin 端点也通 —— 网络没问题 */
        OK,

        /**
         * Google 的通用端点通，但 **microG 登录要用的 checkin 端点不通**。
         *
         * 这是最容易被误判成"网络没问题"的一种情况，必须单独一档：
         * 浏览器能开 google.com，但 microG 登录就是卡在「请稍候」——
         * 因为代理规则把 `android.clients.google.com` 走了直连（或漏了这条）。
         *
         * 实测踩到过：`www.gstatic.com/generate_204` → 204，
         * 同一个网络下 `android.clients.google.com/checkin` → 连不上。
         */
        CHECKIN_BLOCKED,

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
        /** Google 探测端点解析到的 IP。DNS 被污染时这里会是一个完全不相干的地址 */
        val resolvedIp: String?,
        /** 解析结果看起来不像 Google 的 IP（启发式，不是铁证） */
        val dnsLooksPoisoned: Boolean,
        val detail: String,
    ) {
        /**
         * 能否直接用于 microG 登录。
         *
         * 判据是 [Verdict.OK] —— 也就是**必须包含 checkin 端点可达**。
         * 只看到"Google 能连"就放行会漏掉 [Verdict.CHECKIN_BLOCKED] 这一档，
         * 而那一档的表现正是"浏览器能开 google.com，microG 却登录不了"。
         */
        val loginReady: Boolean get() = verdict == Verdict.OK
    }

    /**
     * Google 常见的 IPv4 段（**启发式**，不是权威名单）。
     *
     * 用途只有一个：在 DNS 被污染时给用户一个能自己看懂的信号。
     * 实测踩到的例子：`www.google.com` 被解析到 `69.171.235.22`
     * —— 那是 Facebook 的段，与 Google 毫无关系。
     *
     * 刻意不做成"白名单"：Google 的地址段一直在变，写死了迟早误判。
     * 所以只在**明显不像**的时候提示，措辞也说清是"可能"。
     */
    private val GOOGLE_IP_PREFIXES = listOf(
        "142.250.", "172.217.", "216.58.", "74.125.", "108.177.",
        "64.233.", "173.194.", "209.85.", "66.102.", "216.239.",
        "142.251.", "172.253.",
        // Google Public DNS 只占 8.8.8.0/24 与 8.8.4.0/24。整个 8.8.0.0/16 是
        // Lumen 的（8.8.1.1 等），写 "8.8." 会把别人家的段判成 Google。
        // （"74.6." 曾在这里——那是 Yahoo 的 74.6.0.0/16，已删。）
        "8.8.8.", "8.8.4.",
    )

    /**
     * 这个 IP 看起来是不是 Google 的。
     *
     * 纯函数，可单测。抽出来是因为它是"DNS 污染"这个结论的唯一依据，
     * 判错了会把用户引向错误的排查方向。
     *
     * 注意**不是白名单**：返回 false 只说明"不像"，不说明"一定被污染"。
     * 调用方的措辞也必须保持这个分寸。
     */
    fun looksLikeGoogleIp(ip: String): Boolean =
        GOOGLE_IP_PREFIXES.any { ip.startsWith(it) }

    /**
     * 代理软件 fake-ip 模式使用的基准测试段（198.18.0.0/15）。
     *
     * 解析落在这里说明 DNS 已被代理接管，所有探测都经代理转发——
     * **不是**污染。不单独处理的话，正常工作的代理环境会被误报
     * "DNS 污染"，而同一句话里又写着"可以正常登录"，自相矛盾。
     */
    internal fun isFakeIp(ip: String): Boolean =
        ip.startsWith("198.18.") || ip.startsWith("198.19.")

    /**
     * "要不要提示 DNS 可疑"的统一判定。**纯函数，可单测。**
     *
     * 三类情况一律判 false（不提示）：
     *  - 解析失败（null）——detail 里已有专门分支，再叠加"可疑"是噪音；
     *  - IPv6——上面的前缀表全是 IPv4，对 IPv6 一律"不知道"；
     *  - fake-ip 保留段——那是代理接管了解析，属正常现象。
     */
    internal fun dnsLooksPoisoned(resolvedIp: String?): Boolean = when {
        resolvedIp == null -> false
        resolvedIp.contains(':') -> false
        isFakeIp(resolvedIp) -> false
        else -> !looksLikeGoogleIp(resolvedIp)
    }

    suspend fun run(timeoutMs: Int = 6000): Result = withContext(Dispatchers.IO) {
        // 三个探测**并发**跑。串行的话最坏要 3×timeout（18s），
        // 而自检面板是在用户"感觉坏了"的时候打开的，等 18 秒等于劝退。
        val domesticDeferred = async { probe(DOMESTIC, timeoutMs) }
        val googleDeferred = async { probe(GOOGLE, timeoutMs) }
        val checkinDeferred = async { probe(CHECKIN, timeoutMs) }
        val ipDeferred = async { resolveIp("www.gstatic.com") }

        val domestic = domesticDeferred.await()
        val google = googleDeferred.await()
        val checkin = checkinDeferred.await()
        // DNS 解析没有内建超时：系统 resolver 在丢包环境会重试到几十秒，
        // 而 DNS 故障恰恰是自检最需要快速给结论的场景。给 await 加两倍预算的
        // 兜底，超时按"解析失败"处理（后台那个解析协程让它自生自灭，不阻塞面板）。
        val ip = withTimeoutOrNull(timeoutMs * 2L) { ipDeferred.await() }

        val verdict = decideVerdict(domestic.code, google.code, checkin.code)
        val poisoned = dnsLooksPoisoned(ip)

        Result(
            verdict = verdict,
            domesticCode = domestic.code,
            googleCode = google.code,
            checkinCode = checkin.code,
            resolvedIp = ip,
            dnsLooksPoisoned = poisoned,
            detail = buildDetail(verdict, domestic, google, checkin, ip, poisoned),
        )
    }

    /**
     * 由三个探测结果得出结论。**纯函数，可单测。**
     *
     * 抽出来是因为这里出过一次**真 bug**：原来只按"Google 通用端点通不通"判定，
     * 于是"gstatic 通、checkin 不通"会被判成 OK —— 而那种情况下 microG
     * 登录**一定失败**。用户会看到自检全绿却登录不了，比不报还糟。
     *
     * 关于 checkin 的判据：**看有没有 HTTP 响应（code != 0），不看状态码**。
     * 对 `/checkin` 发 GET 本来就该返回 4xx，用 200..399 判会把
     * "能连上但方法不对"误判成"连不上"。
     *
     * 关于 google 的判据：**必须精确 204**。`generate_204` 返回 200/302 时，
     * 响应几乎总是 captive portal（强制门户）伪造的——把它当"通"会让用户
     * 在机场 WiFi 上看到自检全绿却永远登录不了。
     */
    internal fun decideVerdict(domesticCode: Int, googleCode: Int, checkinCode: Int): Verdict = when {
        domesticCode !in 200..399 -> Verdict.NO_NETWORK
        googleCode == 204 && checkinCode != 0 -> Verdict.OK
        googleCode == 204 -> Verdict.CHECKIN_BLOCKED
        else -> Verdict.GOOGLE_BLOCKED
    }

    /**
     * 解析一个域名，返回 IP 字符串。
     *
     * 单独做这一步是因为**"连不上"和"解析到假地址"要分开报**：
     * 前者用户要去查代理，后者要去查 DNS。混在一起说，用户不知道该动哪。
     */
    private fun resolveIp(host: String): String? =
        runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull()

    private fun buildDetail(
        verdict: Verdict,
        domestic: Probe,
        google: Probe,
        checkin: Probe,
        resolvedIp: String?,
        poisoned: Boolean,
    ): String {
        val dnsLine = when {
            resolvedIp == null -> "www.gstatic.com 解析失败或超时（DNS 不可用）。"
            isFakeIp(resolvedIp) ->
                "www.gstatic.com → $resolvedIp（代理 fake-ip 保留段，" +
                    "说明解析已被代理接管，属正常现象）。"
            resolvedIp.contains(':') ->
                "www.gstatic.com → $resolvedIp（IPv6 地址，本探测不对 IPv6 做污染判断）。"
            poisoned ->
                "⚠️ DNS 可疑：www.gstatic.com 被解析到 **$resolvedIp**，" +
                    "这不是 Google 常见的地址段 —— 典型的 **DNS 污染**。"
            else -> "DNS 解析正常（www.gstatic.com → $resolvedIp）。"
        }

        return when (verdict) {
            Verdict.OK ->
                "国内外站点都可达，microG 登录要用的 checkin 端点也通。" +
                    "可以正常登录 Google 账号。$dnsLine"

            Verdict.CHECKIN_BLOCKED ->
                "**Google 能连，但 microG 登录要用的端点连不上** —— 这正是" +
                    "「浏览器能打开 google.com，microG 却卡在请稍候」的原因。\n" +
                    "$GOOGLE → ${google.code}（通）\n" +
                    "$CHECKIN → 连不上${checkin.error?.let { "（$it）" } ?: ""}\n" +
                    dnsLine + "\n" +
                    "代理的规则大概率**只覆盖了 google.com / gstatic.com，漏了 " +
                    "android.clients.google.com**。修法（按优先级）：\n" +
                    "① 在代理规则里加一条 `DOMAIN,android.clients.google.com,PROXY`；" +
                    "② 把兜底/最终规则从 DIRECT 改成 PROXY；" +
                    "③ 如果是分应用代理，确认本应用在名单里；" +
                    "④ 换成「全局模式」试一次 —— 如果全局能登录，就说明是规则漏了这条域名。"

            Verdict.GOOGLE_BLOCKED ->
                "国内站点可达（$DOMESTIC → ${domestic.code}），但 Google 不可达" +
                    "（$GOOGLE → ${google.code}${google.error?.let { "，$it" } ?: ""}）。\n" +
                    // 2xx/3xx 却被判"不可达"，唯一解释是门户劫持：generate_204 正常
                    // 必须回 204，回 200/302 说明响应被中间设备伪造了
                    if (google.code in 200..399) {
                        "⚠️ $GOOGLE 返回了 ${google.code} 而不是 204 —— 这个响应多半来自 " +
                            "**captive portal（强制门户）**：机场/酒店 WiFi 的认证页或运营商劫持，" +
                            "并非真的连上了 Google。先完成门户认证或换网络。\n"
                    } else {
                        ""
                    } +
                    dnsLine + "\n" +
                    "说明**代理/加速没有生效**。排查顺序：" +
                    "① 代理软件里换一个节点，或看节点延迟是不是全红；" +
                    "② 确认代理不是「直连 / 规则模式漏了 Google」；" +
                    "③ 如果是分应用代理，把本应用加进代理名单；" +
                    "④ 用系统浏览器打开 google.com 验证 —— 如果浏览器也不行，" +
                    "问题在代理本身，与本应用无关。\n" +
                    "microG 登录必须能连上 $CHECKIN，请先修好网络再试。"

            Verdict.NO_NETWORK ->
                "连国内站点都不可达（$DOMESTIC → ${domestic.code}" +
                    "${domestic.error?.let { "，$it" } ?: ""}）。" +
                    "设备当前没有可用网络，或者被系统限制了联网权限。"
        }
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
