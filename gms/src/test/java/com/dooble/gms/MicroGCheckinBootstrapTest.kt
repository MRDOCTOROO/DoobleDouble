package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「装配时预写 microG checkin 开关」的回归测试。
 *
 * microG 的 checkin 默认关闭,而登录页会先等设备注册 —— 不开就永远卡在
 * 「正在建立到 Google 服务器的连接」(MuMu 实测)。装配时直接预写
 * SharedPreferences,赶在 microG 首次运行之前。
 */
class MicroGCheckinBootstrapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gmsDataDir(): File = tmp.newFolder("gms").resolve("com.google.android.gms")

    private fun prefsFileOf(dir: File) =
        dir.resolve("shared_prefs/com.google.android.gms_preferences.xml")

    @Test
    fun `首次装配时写入 checkin 开关`() {
        val dir = gmsDataDir()

        assertTrue(MicroGCheckinBootstrap.ensureEnabled(dir))

        val content = prefsFileOf(dir).readText()
        assertTrue("必须包含 checkin 键", content.contains(MicroGCheckinBootstrap.CHECKIN_KEY))
        assertTrue("必须是 true", content.contains("value=\"true\""))
    }

    @Test
    fun `XML 结构与 Android SharedPreferences 一致`() {
        val xml = MicroGCheckinBootstrap.checkinPrefsXml()
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<map>"))
        assertTrue(xml.contains("</map>"))
        assertTrue(xml.contains("<boolean name=\"${MicroGCheckinBootstrap.CHECKIN_KEY}\" value=\"true\" />"))
    }

    @Test
    fun `已开启的配置不被改写`() {
        val dir = gmsDataDir()
        val file = prefsFileOf(dir)
        file.parentFile.mkdirs()
        file.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"checkin_enable_service\" value=\"true\" />\n</map>\n")
        val before = file.readText()

        assertTrue(MicroGCheckinBootstrap.ensureEnabled(dir))

        assertEquals("已存在的文件必须原样保留", before, file.readText())
    }

    @Test
    fun `用户显式关闭时尊重现状不覆盖`() {
        val dir = gmsDataDir()
        val file = prefsFileOf(dir)
        file.parentFile.mkdirs()
        file.writeText("<map>\n    <boolean name=\"checkin_enable_service\" value=\"false\" />\n</map>\n")

        assertFalse("用户显式关闭应返回 false 且不覆盖", MicroGCheckinBootstrap.ensureEnabled(dir))
        assertTrue("不得改写用户配置", file.readText().contains("value=\"false\""))
    }

    @Test
    fun `microG 运行过但从未写过该键时补写`() {
        val dir = gmsDataDir()
        val file = prefsFileOf(dir)
        file.parentFile.mkdirs()
        file.writeText("<map>\n    <int name=\"lastVersionCodeUsed\" value=\"548115537\" />\n</map>\n")

        assertTrue(MicroGCheckinBootstrap.ensureEnabled(dir))

        assertTrue("应在既有配置中补上 checkin 键", file.readText().contains("value=\"true\""))
        assertTrue("不得破坏既有键", file.readText().contains("lastVersionCodeUsed"))
    }

    @Test
    fun `目录不可写时优雅失败`() {
        // 用一个"文件"冒充目录,制造不可写路径
        val block = tmp.newFile("blocker")

        assertFalse(MicroGCheckinBootstrap.ensureEnabled(block.resolve("com.google.android.gms")))
    }
}
