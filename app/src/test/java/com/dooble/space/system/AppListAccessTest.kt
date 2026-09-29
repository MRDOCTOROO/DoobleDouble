package com.dooble.space.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROM 判定与权限页候选的测试。
 *
 * ## 为什么值得测
 *
 * [AppListAccess.guideIntents] 的可用性完全建立在"先猜对 ROM，再猜对组件名"之上。
 * 猜错的代价是用户点了一堆按钮都没反应 —— 而这个功能的**唯一**目的就是
 * 把用户送到正确的设置页。所以 ROM 判定必须准，候选列表必须有序、不能有重复、
 * 且不能给非目标 ROM 的组件（否则等于让用户白点）。
 *
 * 判定只吃厂商/品牌字符串，因此可以在 JVM 单测里完整覆盖。
 */
class AppListAccessTest {

    @Test
    fun `小米系全部识别为 MIUI`() {
        assertEquals(AppListAccess.Rom.MIUI, AppListAccess.romOf("Xiaomi", "Xiaomi"))
        assertEquals(AppListAccess.Rom.MIUI, AppListAccess.romOf("Xiaomi", "Redmi"))
        assertEquals(AppListAccess.Rom.MIUI, AppListAccess.romOf("Xiaomi", "POCO"))
        // 大小写不敏感
        assertEquals(AppListAccess.Rom.MIUI, AppListAccess.romOf("xiaomi", "redmi"))
    }

    @Test
    fun `一加与 realme 归到 ColorOS 阵营`() {
        // 三家厂商名不同但权限页在同一个包里，按厂商名分别建一套候选是白费功夫。
        assertEquals(AppListAccess.Rom.COLOR_OS, AppListAccess.romOf("OPPO", "OPPO"))
        assertEquals(AppListAccess.Rom.COLOR_OS, AppListAccess.romOf("OnePlus", "OnePlus"))
        assertEquals(AppListAccess.Rom.COLOR_OS, AppListAccess.romOf("realme", "realme"))
    }

    @Test
    fun `vivo 与 iQOO 识别为 OriginOS`() {
        assertEquals(AppListAccess.Rom.ORIGIN_OS, AppListAccess.romOf("vivo", "vivo"))
        assertEquals(AppListAccess.Rom.ORIGIN_OS, AppListAccess.romOf("vivo", "iQOO"))
    }

    @Test
    fun `华为与荣耀识别为 EMUI`() {
        assertEquals(AppListAccess.Rom.EMUI, AppListAccess.romOf("HUAWEI", "HUAWEI"))
        assertEquals(AppListAccess.Rom.EMUI, AppListAccess.romOf("HONOR", "HONOR"))
    }

    @Test
    fun `三星识别为 One UI`() {
        assertEquals(AppListAccess.Rom.SAMSUNG, AppListAccess.romOf("samsung", "samsung"))
    }

    @Test
    fun `未知厂商落到 OTHER 且不给任何候选`() {
        // 猜错的组件名会让用户点了没反应。宁可只给系统标准入口。
        assertEquals(AppListAccess.Rom.OTHER, AppListAccess.romOf("Google", "google"))
        assertEquals(AppListAccess.Rom.OTHER, AppListAccess.romOf("Sony", "Sony"))
        assertTrue(AppListAccess.permissionPageCandidates(AppListAccess.Rom.OTHER).isEmpty())
    }

    @Test
    fun `厂商与品牌都为空时不崩且落到 OTHER`() {
        // Build.MANUFACTURER 理论上不为空，但测试机 / 定制 ROM 上见过空串。
        assertEquals(AppListAccess.Rom.OTHER, AppListAccess.romOf(null, null))
        assertEquals(AppListAccess.Rom.OTHER, AppListAccess.romOf("", ""))
    }

    @Test
    fun `只有一个字段命中也能判定`() {
        // 有的 ROM 把厂商填成 "unknown" 而品牌是对的，反之亦然。
        assertEquals(AppListAccess.Rom.MIUI, AppListAccess.romOf("unknown", "Redmi"))
        assertEquals(AppListAccess.Rom.COLOR_OS, AppListAccess.romOf("OnePlus", null))
    }

    // ───────────────────── 候选列表的形状 ─────────────────────

    @Test
    fun `每个已知 ROM 都至少有一个候选组件`() {
        listOf(
            AppListAccess.Rom.MIUI,
            AppListAccess.Rom.COLOR_OS,
            AppListAccess.Rom.ORIGIN_OS,
            AppListAccess.Rom.EMUI,
            AppListAccess.Rom.SAMSUNG,
        ).forEach {
            assertTrue("${it.name} 没有候选组件，用户将无路可走", AppListAccess.permissionPageCandidates(it).isNotEmpty())
        }
    }

    @Test
    fun `候选组件名格式合法且互不重复`() {
        AppListAccess.Rom.entries.forEach { rom ->
            val list = AppListAccess.permissionPageCandidates(rom)
            list.forEach { flat ->
                // 必须是 package/class 形式，否则 ComponentName.unflattenFromString 会返回 null，
                // 运行时被静默跳过 —— 表现为"这个 ROM 的候选全都不见了"。
                assertTrue("不是合法的扁平静态组件名：$flat", flat.count { it == '/' } == 1)
                assertTrue("包名段为空：$flat", flat.substringBefore('/').isNotBlank())
                assertTrue("类名段为空：$flat", flat.substringAfter('/').isNotBlank())
                assertTrue("类名段含空格：$flat", !flat.contains(' '))
            }
            assertEquals("${rom.name} 的候选里有重复项", list.size, list.toSet().size)
        }
    }

    @Test
    fun `各 ROM 的候选不互相串台`() {
        // 把 MIUI 的组件给 ColorOS 用，等于让用户白点一次。
        val miui = AppListAccess.permissionPageCandidates(AppListAccess.Rom.MIUI)
        assertTrue(miui.all { it.startsWith("com.miui.") })

        val color = AppListAccess.permissionPageCandidates(AppListAccess.Rom.COLOR_OS)
        assertTrue(color.all { it.startsWith("com.coloros.") || it.startsWith("com.oplus.") })

        val origin = AppListAccess.permissionPageCandidates(AppListAccess.Rom.ORIGIN_OS)
        assertTrue(origin.all { it.startsWith("com.vivo.") || it.startsWith("com.iqoo.") })

        val emui = AppListAccess.permissionPageCandidates(AppListAccess.Rom.EMUI)
        assertTrue(emui.all { it.startsWith("com.huawei.") })

        val samsung = AppListAccess.permissionPageCandidates(AppListAccess.Rom.SAMSUNG)
        assertTrue(samsung.all { it.startsWith("com.samsung.") })
    }

    @Test
    fun `romLabel 对每个 ROM 都有非空标签`() {
        AppListAccess.Rom.entries.forEach {
            assertTrue("${it.name} 缺展示名", AppListAccess.romLabel(it).isNotBlank())
        }
    }
}
