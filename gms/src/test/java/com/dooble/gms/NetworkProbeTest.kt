package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「解析到的 IP 像不像 Google」这个启发式的测试。
 *
 * ## 为什么值得测
 *
 * 它是自检里「DNS 可疑」这个结论的**唯一依据**。判错两个方向都糟：
 *  - 把 Google 的 IP 判成不像 → 用户被引去查 DNS，而问题其实在代理；
 *  - 把别人的 IP 判成像 → 明明 DNS 被污染，自检却说正常。
 *
 * ## 真实案例（本次真机排查踩到的）
 *
 * ```
 * www.google.com     → 69.171.235.22     ← Facebook 的段
 * www.wikipedia.org  → 31.13.73.169      ← 也是 Facebook 的段
 * ```
 * 被墙的域名被统一解析到无关地址，这是典型的 DNS 污染。
 */
class NetworkProbeTest {

    @Test
    fun `Google 常见段被认出来`() {
        listOf(
            "142.250.69.174",   // gstatic / google 常用
            "172.217.160.78",
            "216.58.200.46",
            "74.125.24.100",
            "108.177.122.100",
            "64.233.188.100",
            "173.194.174.100",
            "8.8.8.8",          // Google Public DNS
        ).forEach {
            assertTrue("$it 应被判为像 Google", NetworkProbe.looksLikeGoogleIp(it))
        }
    }

    @Test
    fun `真机上被污染的两个地址被判为不像`() {
        // 这两条是从真机 ping 输出里直接抄下来的，是本次排查的现场证据。
        assertFalse(
            "69.171.235.22 是 Facebook 的段，绝不该被判成 Google",
            NetworkProbe.looksLikeGoogleIp("69.171.235.22"),
        )
        assertFalse(
            "31.13.73.169 同样是 Facebook 的段",
            NetworkProbe.looksLikeGoogleIp("31.13.73.169"),
        )
    }

    @Test
    fun `私有地址与回环被判为不像`() {
        // 某些代理软件在 fake-ip 模式下会返回 198.18.x.x 之类的地址，
        // 那种情况也应当提示用户 —— 它不是 Google。
        listOf("127.0.0.1", "192.168.1.1", "10.0.0.1", "198.18.0.1", "172.19.0.1")
            .forEach {
                assertFalse("$it 不是 Google 的地址", NetworkProbe.looksLikeGoogleIp(it))
            }
    }

    @Test
    fun `前缀匹配不会误伤相似但不同的段`() {
        // 142.250. 是 Google 的；142.251. 也是（单独列出）。
        // 但 142.25. 或 142.2501. 不该被 142.250. 的前缀匹配到。
        assertFalse(NetworkProbe.looksLikeGoogleIp("142.25.1.1"))
        assertFalse(NetworkProbe.looksLikeGoogleIp("142.2501.1.1"))
    }

    @Test
    fun `IPv6 一律判为不像`() {
        // 真机上 curl 曾经尝试连 [2001::1]:443 —— 那是个明显的假地址。
        // 这里不试图识别 Google 的 IPv6 段（段太多、变化太快），
        // 统一判为"不像"，让用户至少能看到"解析结果可疑"。
        assertFalse(NetworkProbe.looksLikeGoogleIp("2001::1"))
        assertFalse(NetworkProbe.looksLikeGoogleIp("2400:cb00::1"))
    }

    @Test
    fun `空串不崩`() {
        assertFalse(NetworkProbe.looksLikeGoogleIp(""))
    }

    // ------------------------------------------------------------------ 判定逻辑
    //
    // 这一组守的是一个**真实发生过的 bug**：原来只按"Google 通用端点通不通"
    // 判定，于是"gstatic 通、checkin 不通"被判成 OK —— 而那种情况下 microG
    // 登录一定失败。用户看到自检全绿却登录不了，比不报还糟。

    @Test
    fun `三项全通才判 OK`() {
        assertEquals(
            NetworkProbe.Verdict.OK,
            NetworkProbe.decideVerdict(domesticCode = 200, googleCode = 204, checkinCode = 405),
        )
    }

    @Test
    fun `Google 通但 checkin 不通 判 CHECKIN_BLOCKED`() {
        // 这是真机现场：www.gstatic.com 返回 204，android.clients.google.com 连不上。
        // 表现就是"浏览器能开 google.com，microG 卡在请稍候"。
        assertEquals(
            NetworkProbe.Verdict.CHECKIN_BLOCKED,
            NetworkProbe.decideVerdict(domesticCode = 200, googleCode = 204, checkinCode = 0),
        )
    }

    @Test
    fun `checkin 的判据是连上了就行 不看状态码`() {
        // 对 /checkin 发 GET 本来就会返回 4xx。只要不是 0（连不上），
        // 就说明网络能到那台服务器。
        listOf(400, 403, 404, 405, 500).forEach { code ->
            assertEquals(
                "checkin 返回 $code 应视为'能连上'",
                NetworkProbe.Verdict.OK,
                NetworkProbe.decideVerdict(200, 204, code),
            )
        }
    }

    @Test
    fun `Google 不通但国内通 判 GOOGLE_BLOCKED`() {
        assertEquals(
            NetworkProbe.Verdict.GOOGLE_BLOCKED,
            NetworkProbe.decideVerdict(domesticCode = 200, googleCode = 0, checkinCode = 0),
        )
    }

    @Test
    fun `国内也不通 判 NO_NETWORK 且优先于其它判断`() {
        assertEquals(
            NetworkProbe.Verdict.NO_NETWORK,
            NetworkProbe.decideVerdict(domesticCode = 0, googleCode = 0, checkinCode = 0),
        )
        // 即使 Google 那一项"看起来通"，只要国内不通也应当判 NO_NETWORK ——
        // 那说明探测本身不可信（比如被 captive portal 劫持返回了 200）。
        assertEquals(
            NetworkProbe.Verdict.NO_NETWORK,
            NetworkProbe.decideVerdict(domesticCode = 0, googleCode = 204, checkinCode = 405),
        )
    }

    @Test
    fun `loginReady 只在 OK 时为真`() {
        // CHECKIN_BLOCKED 必须为 false —— 这正是那个 bug 的守门测试。
        assertTrue(NetworkProbe.Result(NetworkProbe.Verdict.OK, 200, 204, 405, null, false, "").loginReady)
        assertFalse(
            NetworkProbe.Result(NetworkProbe.Verdict.CHECKIN_BLOCKED, 200, 204, 0, null, false, "").loginReady,
        )
        assertFalse(
            NetworkProbe.Result(NetworkProbe.Verdict.GOOGLE_BLOCKED, 200, 0, 0, null, false, "").loginReady,
        )
    }
}
