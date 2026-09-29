package com.dooble.gms

import com.dooble.gms.MicroGVersionChecker.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * microG 版本比较与 release 资产选择的测试。
 *
 * ## 为什么值得单测
 *
 * 这两个函数是"能不能下到正确的包"的最后一公里，而且**失败是静默的**：
 *
 *  - [MicroGVersionChecker.isNewer] 决定 UI 要不要提示"有新版本"。
 *    比错方向 → 用户永远看不到升级提示（或者被反复骚扰）。
 *  - [Release.assetFor] 决定从 release 的几十个资产里挑哪一个。
 *    挑错 → 下载到的是另一个组件（比如要 vending 却拿到 gms），
 *    文件是合法 APK，装的时候才炸，且错误信息指向"安装失败"而非"选错资产"。
 *
 * 两者都是纯函数，正好适合钉死边界。
 */
class MicroGVersionCheckerTest {

    private fun asset(name: String, size: Long) = Release.Asset(
        name = name,
        url = "https://example.invalid/$name",
        sizeBytes = size,
    )

    // ───────────────────────── isNewer：版本段比较 ─────────────────────────

    @Test
    fun `更高的数字段判定为新`() {
        assertTrue(MicroGVersionChecker.isNewer("v0.3.17", "v0.3.16"))
        assertTrue(MicroGVersionChecker.isNewer("v0.4.0", "v0.3.16.252432"))
    }

    @Test
    fun `更低的数字段判定为旧`() {
        assertFalse(MicroGVersionChecker.isNewer("v0.3.15", "v0.3.16"))
        assertFalse(MicroGVersionChecker.isNewer("v0.2.99.999999", "v0.3.16"))
    }

    @Test
    fun `相等返回 false`() {
        // 相等不能算"新"，否则 UI 会永远显示升级提示。
        assertFalse(MicroGVersionChecker.isNewer("v0.3.16", "v0.3.16"))
    }

    @Test
    fun `末尾多出的零段视为相等`() {
        // 上游 tag 的段数不固定；补零比较才不会把 v0.3.16 和 v0.3.16.0 判成"新"。
        assertFalse(MicroGVersionChecker.isNewer("v0.3.16.252432.0", "v0.3.16.252432"))
        assertFalse(MicroGVersionChecker.isNewer("v0.3.16.0", "v0.3.16"))
    }

    @Test
    fun `段数不等时短的一方按零补齐`() {
        // v0.3.16.1 的第 4 段是 1，v0.3.16 补 0 → 1 > 0，所以是新版本。
        assertTrue(MicroGVersionChecker.isNewer("v0.3.16.1", "v0.3.16"))
        assertFalse(MicroGVersionChecker.isNewer("v0.3.16", "v0.3.16.1"))
    }

    @Test
    fun `前缀 v 与 V 都被容忍`() {
        // trimStart('v','V')：候选和基线各自独立处理，混用大小写也要能比。
        assertTrue(MicroGVersionChecker.isNewer("0.3.17", "v0.3.16"))
        assertTrue(MicroGVersionChecker.isNewer("V0.3.17", "v0.3.16"))
        assertTrue(MicroGVersionChecker.isNewer("v0.3.17", "0.3.16"))
    }

    @Test
    fun `非数字段被丢弃而不是当零`() {
        // `v0.3.16-rc1` → 段 "16-rc1" 无法转 Long，被 mapNotNull 丢掉，
        // 于是解析结果是 [0, 3]（只两段），而不是 [0, 3, 16]。
        // 结果：它比基线 v0.3.16.252432 的第三段 16 小 → 判为旧。
        // 这是刻意的取舍：预发布 tag 不应被当成正式升级。
        assertFalse(MicroGVersionChecker.isNewer("v0.3.16-rc1", "v0.3.16.252432"))
    }

    @Test
    fun `全非数字段退化为空列表按零处理`() {
        // parts 为空 → 所有位补 0 → 与基线首位 0 相等 → 逐位相等 → false。
        assertFalse(MicroGVersionChecker.isNewer("vNightly", "v0.3.16"))
        // 反过来：基线是空的，候选有任何非零位都是新。
        assertTrue(MicroGVersionChecker.isNewer("v0.0.1", "vNightly"))
    }

    @Test
    fun `默认基线是仓库里已验证的 RELEASE_TAG`() {
        // 单参重载必须等价于拿 RELEASE_TAG 当基线，否则 UI 提示会与回退下载地址脱节。
        assertEquals(MicroGRepository.RELEASE_TAG, "v0.3.16.252432")
        assertFalse(MicroGVersionChecker.isNewer(MicroGRepository.RELEASE_TAG))
        assertTrue(MicroGVersionChecker.isNewer("v0.3.16.252433"))
    }

    // ───────────────────────── assetFor：资产选择 ─────────────────────────

    private val gms = MicroGComponent.GMS_CORE.packageName
    private val vending = MicroGComponent.FAKE_STORE.packageName

    @Test
    fun `按包名前缀匹配资产`() {
        val r = Release(
            tag = "v0.3.16.252432",
            publishedAt = "",
            assets = listOf(
                asset("$gms-252432032.apk", 100),
                asset("$vending-84022632.apk", 200),
            ),
        )
        assertEquals("$gms-252432032.apk", r.assetFor(gms)?.name)
        assertEquals("$vending-84022632.apk", r.assetFor(vending)?.name)
    }

    @Test
    fun `前缀匹配必须带连字符避免误配同前缀的别的包`() {
        // 少了那个 '-'，`com.google.android.gms` 会匹配到 `com.google.android.gmsxxx-...`。
        // 这里 gmsxxx 的 size 故意更大：一旦误配，断言就会拿到它。
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(
                asset("$gms-252432032.apk", 100),
                asset("com.google.android.gmsxxx-1.apk", 999_999),
            ),
        )
        assertEquals("$gms-252432032.apk", r.assetFor(gms)?.name)
    }

    @Test
    fun `同池取体积最大的那个`() {
        // 上游会同时挂多份（不同 ABI / 不同构建）。取最大是"最完整"的近似判据。
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(
                asset("$gms-1.apk", 10),
                asset("$gms-2.apk", 300),
                asset("$gms-3.apk", 200),
            ),
        )
        assertEquals("$gms-2.apk", r.assetFor(gms)?.name)
    }

    @Test
    fun `默认不选华为变体`() {
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(
                asset("$gms-1.apk", 10),
                asset("$gms-2-hw.apk", 999),
            ),
        )
        assertEquals("$gms-1.apk", r.assetFor(gms, huawei = false)?.name)
    }

    @Test
    fun `显式指定华为时选 hw 变体`() {
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(
                asset("$gms-1.apk", 999),
                asset("$gms-2-hw.apk", 10),
            ),
        )
        assertEquals("$gms-2-hw.apk", r.assetFor(gms, huawei = true)?.name)
    }

    @Test
    fun `要华为但没有 hw 时回退到标准变体`() {
        // 上游某次发版可能漏了 -hw 包。此时宁可用标准包，也不要返回 null 让下载整条链路断掉。
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(asset("$gms-1.apk", 10)),
        )
        assertEquals("$gms-1.apk", r.assetFor(gms, huawei = true)?.name)
    }

    @Test
    fun `不要华为但只有 hw 时也回退`() {
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(asset("$gms-2-hw.apk", 10)),
        )
        assertEquals("$gms-2-hw.apk", r.assetFor(gms, huawei = false)?.name)
    }

    @Test
    fun `没有匹配资产返回 null`() {
        val r = Release(tag = "v1", publishedAt = "", assets = listOf(asset("other-1.apk", 1)))
        assertNull(r.assetFor(gms))
    }

    @Test
    fun `空资产列表返回 null`() {
        assertNull(Release("v1", "", emptyList()).assetFor(gms))
    }

    // ─────────────── 与 MicroGRepository.candidates 的衔接契约 ───────────────

    @Test
    fun `查到 release 时 candidates 用资产真实文件名而不是硬编码`() {
        // 这是这套机制存在的全部理由：上游发新版后文件名里的版本号会变，
        // 硬编码地址会 404。有 release 时必须以 release 的 url 为准。
        val r = Release(
            tag = "v0.9.9.999999",
            publishedAt = "",
            assets = listOf(
                asset("$gms-999999032.apk", 100).copy(url = "https://example.invalid/real-gms.apk"),
            ),
        )
        val list = MicroGRepository.candidates(MicroGComponent.GMS_CORE, release = r)
        assertTrue("至少要有直连一条", list.any { it == "https://example.invalid/real-gms.apk" })
        assertTrue(
            "所有候选都必须指向 release 里的真实地址，不能残留硬编码的 RELEASE_TAG",
            list.none { it.contains(MicroGRepository.RELEASE_TAG) },
        )
        assertEquals(
            "候选数量必须等于镜像前缀数量（每个镜像一条）",
            MicroGRepository.MIRROR_PREFIXES.size,
            list.size,
        )
    }

    @Test
    fun `release 里没有对应资产时回退到硬编码地址`() {
        // assetFor 返回 null → 走 fileName() 的固定版本，保证"能联网但选不到资产"时不至于断链。
        val r = Release(tag = "v1", publishedAt = "", assets = listOf(asset("other-1.apk", 1)))
        val list = MicroGRepository.candidates(MicroGComponent.GMS_CORE, release = r)
        assertTrue(list.any { it.contains(MicroGRepository.RELEASE_TAG) })
        assertTrue(list.any { it.endsWith(MicroGRepository.fileName(MicroGComponent.GMS_CORE)) })
    }

    @Test
    fun `无官方直链的组件在硬编码路径下返回空列表`() {
        // MapsV1 上游以系统库形式分发（/system/framework 下的 jar + 权限 xml），
        // 不是 APK，所以没有可下载的文件。GSF Proxy 则**有**直链（见下一条用例）。
        assertTrue(MicroGRepository.candidates(MicroGComponent.MAPS_V1).isEmpty())
        assertFalse(MicroGRepository.isDownloadable(MicroGComponent.MAPS_V1))
    }

    @Test
    fun `GSF Proxy 有自己的仓库与 tag 不再是无直链组件`() {
        // GSF Proxy 在 microg/GsfProxy，不在 GmsCore 仓库里；tag 也完全不同
        // （v0.1.0 vs v0.3.16.252432）。它此前被当成"只能手动导入"，
        // 实际上游一直有 21KB 的 GsfProxy.apk 可下。
        assertTrue(MicroGRepository.isDownloadable(MicroGComponent.GSF_PROXY))
        val list = MicroGRepository.candidates(MicroGComponent.GSF_PROXY)
        assertTrue(
            "直链必须指向 GsfProxy 仓库而不是 GmsCore",
            list.any { it.contains("/microg/GsfProxy/releases/download/v0.1.0/GsfProxy.apk") },
        )
        assertTrue(
            "不能带上 GmsCore 的 tag",
            list.none { it.contains(MicroGRepository.RELEASE_TAG) },
        )
    }

    @Test
    fun `不同仓库的 release 不能互相串用`() {
        // 把 GmsCore 的 release 传给 GSF Proxy，若不做仓库核对，
        // assetFor 会拿 GmsCore 的资产列表去匹配 gsf 前缀 —— 匹配到就是装错包。
        val gmsCoreRelease = Release(
            tag = "v0.3.16.252432",
            publishedAt = "",
            assets = listOf(asset("com.google.android.gsf-999.apk", 10)),
            repo = MicroGVersionChecker.GMS_CORE_REPO,
        )
        val list = MicroGRepository.candidates(MicroGComponent.GSF_PROXY, release = gmsCoreRelease)
        assertTrue(
            "仓库不匹配时必须忽略 release，回退到硬编码直链",
            list.any { it.contains("/microg/GsfProxy/") },
        )
        assertTrue(list.none { it.contains("com.google.android.gsf-999.apk") })
    }

    @Test
    fun `无仓库的组件即使 release 里有同名资产也不给直链`() {
        // MapsV1 的 downloadRepo 是空串（上游以系统库形式分发，没有 release 资产）。
        // 仓库核对会直接跳过 release 分支 —— 不会因为某个仓库恰好挂了 maps 的资产
        // 就凭空多出一个直链。这个守卫是加 repo 字段时一并加的，之前没有，
        // 表现是"GmsCore 的资产列表被拿去匹配 maps 前缀"。
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(asset("com.google.android.maps-1.apk", 10)),
        )
        assertFalse(MicroGRepository.isDownloadable(MicroGComponent.MAPS_V1))
        assertTrue(MicroGRepository.candidates(MicroGComponent.MAPS_V1, release = r).isEmpty())
    }

    @Test
    fun `镜像前缀是纯前缀拼接不会吞掉原地址`() {
        // 镜像代理的约定是 `prefix + 原始地址`。若哪天改成替换，这条会红。
        val r = Release(
            tag = "v1",
            publishedAt = "",
            assets = listOf(asset("$gms-1.apk", 10).copy(url = "https://example.invalid/a.apk")),
        )
        val list = MicroGRepository.candidates(MicroGComponent.GMS_CORE, release = r)
        MicroGRepository.MIRROR_PREFIXES.forEach { prefix ->
            assertTrue(
                "镜像 '$prefix' 必须产出 prefix+url 形式的候选",
                list.contains(prefix + "https://example.invalid/a.apk"),
            )
        }
    }
}
