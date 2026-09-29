package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 厂商服务（华为/荣耀）槽位与判定的测试。
 *
 * ## 为什么值得测
 *
 * 这里的错误都是**用户可见的误导**：
 *  - 设备判定错了 → 华为用户看不到 HMS 槽位（或者小米用户看到一堆用不上的东西）；
 *  - 包名写错 → 克隆按钮点了必然失败，而失败信息是"装入失败"，
 *    用户会以为是容器坏了，不会想到是包名不对；
 *  - 说明文案里的关键结论（HMS ≠ GMS、纯血鸿蒙装不上）被改没了 → 用户
 *    会往完全错误的方向排查。
 */
class OemStackTest {

    // ───────────────────── 设备判定 ─────────────────────

    @Test
    fun `华为与荣耀都被识别`() {
        assertTrue(OemStack.isHuaweiDevice("HUAWEI", "HUAWEI"))
        assertTrue(OemStack.isHuaweiDevice("HONOR", "HONOR"))
        // 荣耀独立前的机型：MANUFACTURER 是 HUAWEI，BRAND 才是 HONOR
        assertTrue(OemStack.isHuaweiDevice("HUAWEI", "HONOR"))
        // 大小写不敏感
        assertTrue(OemStack.isHuaweiDevice("huawei", "honor"))
    }

    @Test
    fun `鸿蒙字样也算华为设备`() {
        // 部分机型的 MANUFACTURER 会被写成 HarmonyOS 相关字样
        assertTrue(OemStack.isHuaweiDevice("Harmony", "harmony"))
    }

    @Test
    fun `其它厂商不被误判`() {
        listOf(
            "Xiaomi" to "Xiaomi",
            "OPPO" to "OPPO",
            "vivo" to "vivo",
            "samsung" to "samsung",
            "Google" to "google",
        ).forEach { (m, b) ->
            assertFalse("$m 不该被认成华为设备", OemStack.isHuaweiDevice(m, b))
        }
    }

    @Test
    fun `厂商与品牌为空时不崩且不误判`() {
        assertFalse(OemStack.isHuaweiDevice(null, null))
        assertFalse(OemStack.isHuaweiDevice("", ""))
    }

    @Test
    fun `只有一个字段命中也能判定`() {
        assertTrue(OemStack.isHuaweiDevice("unknown", "HONOR"))
        assertTrue(OemStack.isHuaweiDevice("HUAWEI", null))
    }

    // ───────────────────── 槽位清单 ─────────────────────

    @Test
    fun `槽位包名互不重复且非空`() {
        val pkgs = OemStack.HUAWEI_SLOTS.map { it.packageName }
        assertEquals("槽位包名不能重复", pkgs.size, pkgs.toSet().size)
        pkgs.forEach {
            assertTrue("包名不能为空白：'$it'", it.isNotBlank())
            assertTrue("包名不能含空格：'$it'", !it.contains(' '))
        }
    }

    @Test
    fun `HMS Core 的包名是 com_huawei_hwid`() {
        // 这个包名是查证过的：腾讯应用宝 / 小米应用商店 / 华为官网的
        // "HMS Core（华为移动服务）"下载页都指向 com.huawei.hwid。
        // 写成 com.huawei.hms 是常见的想当然 —— 那个包名不是用户装的那个。
        assertTrue(
            "HMS Core 槽位必须存在",
            OemStack.HUAWEI_SLOTS.any { it.packageName == "com.huawei.hwid" },
        )
        assertFalse(
            "com.huawei.hms 不是用户可见的 HMS Core 包名",
            OemStack.HUAWEI_SLOTS.any { it.packageName == "com.huawei.hms" },
        )
    }

    @Test
    fun `每个槽位都有展示名、说明与缺失后果`() {
        // impact 为空会让用户不知道"装不装有什么区别"，于是干脆不装。
        OemStack.HUAWEI_SLOTS.forEach {
            assertTrue("${it.packageName} 缺 shortName", it.shortName.isNotBlank())
            assertTrue("${it.packageName} 缺 description", it.description.isNotBlank())
            assertTrue("${it.packageName} 缺 impact", it.impact.isNotBlank())
        }
    }

    @Test
    fun `监听集合与槽位清单一致`() {
        assertEquals(
            OemStack.HUAWEI_SLOTS.map { it.packageName }.toSet(),
            OemStack.WATCHED_PACKAGES,
        )
    }

    // ───────────────────── 关键结论文案 ─────────────────────

    @Test
    fun `必须说明 HMS 不能替代 GMS`() {
        // 这是用户最容易误解的一点：以为"华为装 HMS 就能用谷歌"。
        assertTrue(OemStack.HMS_IS_NOT_GMS.contains("HMS"))
        assertTrue(OemStack.HMS_IS_NOT_GMS.contains("GMS"))
        assertTrue(OemStack.HMS_IS_NOT_GMS.contains("microG"))
    }

    @Test
    fun `必须给出纯血鸿蒙的前置警告`() {
        assertTrue(OemStack.HARMONY_NEXT_WARNING.contains("NEXT"))
        assertTrue(OemStack.HARMONY_NEXT_WARNING.contains("APK"))
    }

    @Test
    fun `必须给出华为的启动管理提示`() {
        // EMUI / HarmonyOS 的"应用启动管理"默认冻结后台，虚拟进程从宿主 fork，
        // 宿主被冻结则空间内应用全灭。这条不提示，用户只会觉得"用一会儿就没了"。
        assertTrue(OemStack.HUAWEI_KEEPALIVE_HINT.contains("启动管理"))
        assertTrue(OemStack.HUAWEI_KEEPALIVE_HINT.contains("后台活动"))
    }
}
