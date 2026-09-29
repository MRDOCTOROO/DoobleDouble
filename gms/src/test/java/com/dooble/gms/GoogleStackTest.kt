package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「原版谷歌服务」边界说明的契约测试。
 *
 * ## 为什么值得测
 *
 * 这些字符串是**写进 UI 给用户看的结论**，而结论一旦错了，用户会照着做、
 * 然后卡在没法自查的地方（比如花时间去找原版 Play 商店的 APK，装完发现
 * 停在"设备未认证"）。所以这里钉住三件事：
 *  1. 该说的必须说到（不打包的理由、获取路径、认证要求、结论）；
 *  2. 判定函数与清单不脱节（共用包名的槽位判断必须与 [MicroGComponent] 一致）；
 *  3. 常量不能是空串（复制粘贴漏改会留下空说明，UI 上就是一片空白）。
 */
class GoogleStackTest {

    @Test
    fun `所有说明常量都非空且足够长`() {
        // 阈值取 20：短于此基本就是占位符或漏改，不可能是完整的说明。
        listOf(
            "WHY_NOT_BUNDLED" to GoogleStack.WHY_NOT_BUNDLED,
            "CONTAINER_SUPPORT" to GoogleStack.CONTAINER_SUPPORT,
            "HOW_TO_GET_OFFICIAL" to GoogleStack.HOW_TO_GET_OFFICIAL,
            "PLAY_STORE_VERDICT" to GoogleStack.PLAY_STORE_VERDICT,
            "SLOT_EXCLUSIVE" to GoogleStack.SLOT_EXCLUSIVE,
        ).forEach { (name, text) ->
            assertTrue("$name 不能为空", text.isNotBlank())
            assertTrue("$name 太短，像是占位符（${text.length} 字）", text.length >= 20)
        }
    }

    @Test
    fun `不打包的理由必须点名法律约束`() {
        // 这是本工程的核心红线，说明里必须出现"再分发/分发"这类关键词，
        // 否则用户会以为只是"我们偷懒没做"。
        assertTrue(GoogleStack.WHY_NOT_BUNDLED.contains("再分发"))
    }

    @Test
    fun `必须给出两条合法的获取路径`() {
        // 只说"不能内置"是没用的，用户需要知道替代做法。
        assertTrue(GoogleStack.HOW_TO_GET_OFFICIAL.contains("克隆"))
        assertTrue(GoogleStack.HOW_TO_GET_OFFICIAL.contains("导入"))
    }

    @Test
    fun `Play 商店结论必须点明设备认证与服务端判定`() {
        assertTrue(GoogleStack.PLAY_STORE_VERDICT.contains("设备认证"))
        assertTrue(GoogleStack.PLAY_STORE_VERDICT.contains("Integrity"))
        // 必须给出可用的替代品，否则用户没有出路
        assertTrue(GoogleStack.PLAY_STORE_VERDICT.contains("Aurora"))
    }

    @Test
    fun `Play 商店前置条件至少四条且含签名伪造与电源白名单`() {
        val reqs = GoogleStack.PLAY_STORE_REQUIREMENTS
        assertTrue("条件太少，说明没写全", reqs.size >= 4)
        reqs.forEach { assertTrue("条件不能为空", it.isNotBlank()) }
        val joined = reqs.joinToString("|")
        assertTrue("必须提到签名伪造", joined.contains("签名伪造"))
        assertTrue("必须提到电源优化白名单（Android 16 上后台下载会被冻结）", joined.contains("电源优化"))
        assertTrue("必须提到设备认证是过不去的", joined.contains("设备认证"))
    }

    @Test
    fun `槽位互斥说明必须点名两个共用包名`() {
        assertTrue(GoogleStack.SLOT_EXCLUSIVE.contains(MicroGComponent.GMS_CORE.packageName))
        assertTrue(GoogleStack.SLOT_EXCLUSIVE.contains(MicroGComponent.FAKE_STORE.packageName))
    }

    // ───────────────────── 判定函数 ─────────────────────

    @Test
    fun `只有 gms 与 vending 的原版需要 system 语义安装`() {
        // 这两个带 native 库、且会读自己的 ApplicationInfo.flags。
        // GSF Proxy 是 21KB 的纯 Java 包，storage 语义完全够用 ——
        // 把它也划进来会让用户以为"必须从宿主克隆"，白白缩小可选路径。
        assertTrue(GoogleStack.officialNeedsSystemInstall("com.google.android.gms"))
        assertTrue(GoogleStack.officialNeedsSystemInstall("com.android.vending"))
        assertFalse(GoogleStack.officialNeedsSystemInstall("com.google.android.gsf"))
        assertFalse(GoogleStack.officialNeedsSystemInstall("com.google.android.maps"))
        assertFalse(GoogleStack.officialNeedsSystemInstall("com.example.other"))
    }

    @Test
    fun `共用槽位判定与组件清单一致`() {
        MicroGComponent.entries.forEach {
            assertTrue(
                "${it.packageName} 应该被认成共用槽位",
                GoogleStack.isSharedSlot(it.packageName),
            )
        }
        assertFalse(GoogleStack.isSharedSlot("com.aurora.store"))
        assertFalse(GoogleStack.isSharedSlot("com.example.other"))
    }

    @Test
    fun `监听的包集合覆盖全部 microG 组件加上 Aurora`() {
        MicroGComponent.entries.forEach {
            assertTrue(
                "${it.packageName} 必须在监听集合里，否则 UI 不会显示克隆按钮",
                it.packageName in GoogleStack.WATCHED_PACKAGES,
            )
        }
        assertTrue("Aurora 也要能被克隆", "com.aurora.store" in GoogleStack.WATCHED_PACKAGES)
        // Aurora 必须与 StorePackages 那份名单一致，不能各写一份
        assertTrue(GoogleStack.WATCHED_PACKAGES.contains(com.dooble.engine.StorePackages.AURORA))
    }

    @Test
    fun `监听集合里没有多余或空项`() {
        GoogleStack.WATCHED_PACKAGES.forEach {
            assertTrue("包名不能为空白：'$it'", it.isNotBlank())
            assertTrue("包名不能含空格：'$it'", !it.contains(' '))
        }
    }
}
