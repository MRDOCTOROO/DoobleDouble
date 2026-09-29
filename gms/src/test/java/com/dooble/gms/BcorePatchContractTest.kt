package com.dooble.gms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Bcore 补丁 0005（账号选择器改道）的契约测试。
 *
 * ## 为什么值得为一个 .patch 文件写测试
 *
 * 这个补丁踩过两个**只有跑起来才会发现**的坑，两个都不会被编译期拦住：
 *
 * 1. **改道点位置**：放在通用路径上永远不会执行（容器解析不到那个 intent，
 *    代码在 `resolveInfo == null` 的兜底分支就 return 了）。表现是"补丁明明
 *    打进去了，logcat 里连 probe 都没有"，白跑一轮 9 分钟的 AAR 构建。
 * 2. **改道目标**：曾经设成 microG 的 `AccountPickerActivity` —— 它自己会转调
 *    系统账号选择器，于是被改道回它自己，200ms 内无限递归刷屏。
 *
 * 这两条都是"改一行就复现"的东西，而改的人多半是为了别的目的顺手动的。
 * 所以把关键形状钉死：改道目标必须是 `LoginActivity`，且必须有环路保护。
 *
 * 用 `Assume` 而不是硬断言文件存在：补丁目录在正常的开发/CI 环境里都在，
 * 但万一有人只 checkout 了部分目录，这条应该跳过而不是红。
 */
class BcorePatchContractTest {

    private val patch = File("../tools/bcore-patch/0005-account-chooser-redirect.patch")

    private fun patchText(): String {
        assumeTrue("找不到补丁文件（跳过）：${patch.absolutePath}", patch.isFile)
        return patch.readText()
    }

    /**
     * 只取补丁**新增**的行（`+` 开头，排除 `+++` 头）。
     *
     * 为什么不能直接对整个补丁文本做 `contains` / `indexOf`：unified diff 里
     * 同一个代码块会以 `-`（旧）和 `+`（新）两种形态各出现一次，
     * 于是 `indexOf` 很可能命中**旧版本**那一份，得到完全相反的结论。
     * 第一次就是这么写错的。
     */
    private fun addedText(): String = patchText().lineSequence()
        .filter { it.startsWith("+") && !it.startsWith("+++") }
        .joinToString("\n")

    @Test
    fun `改道目标必须是 microG 的登录界面而不是账号选择器`() {
        val added = addedText()
        assertTrue(
            "改道目标必须包含 LoginActivity",
            added.contains("org.microg.gms.auth.login.LoginActivity"),
        )

        // 这条是回归守卫：AccountPickerActivity 会转调系统账号选择器，
        // 被改道回它自己 → 无限递归。谁要是觉得它"语义更合适"又改回去，这里立刻红。
        // 注意只检查**声明块**，因为注释里会提到这个名字来解释"为什么不能用它"。
        val decl = added.substringAfter("MICROG_ACCOUNT_ENTRY = {", "")
            .substringBefore("};", "")
        assertTrue("必须能找到改道目标的声明块", decl.isNotBlank())
        assertFalse(
            "不能用 AccountPickerActivity 作为改道目标：它会转调系统账号选择器，形成递归",
            decl.contains("AccountPickerActivity"),
        )
    }

    @Test
    fun `必须有环路保护标记`() {
        val added = addedText()
        assertTrue(
            "改道时必须往 intent 里塞标记 extra 做环路保护",
            added.contains("_dooble_account_redirect_"),
        )
        assertTrue(
            "进入钩子时要先检查该标记",
            added.contains("hasExtra(EXTRA_REDIRECTED)"),
        )
    }

    @Test
    fun `改道点必须在 null 兜底分支里`() {
        val added = addedText()
        // 判据：改道调用必须出现在 `resolveStoreIntent(intent);` 之后、
        // `intent.setPackage(origPackage);` 之前 —— 也就是那个兜底分支内部。
        val store = added.indexOf("resolveInfo = resolveStoreIntent(intent);")
        val redirect = added.indexOf("resolveInfo = resolveAccountChooser(intent);")
        val fallback = added.indexOf("intent.setPackage(origPackage);")
        assertTrue("新增行里应出现 resolveStoreIntent 调用", store >= 0)
        assertTrue("新增行里应出现改道调用", redirect >= 0)
        assertTrue("新增行里应出现兜底 setPackage", fallback >= 0)
        assertTrue(
            "改道必须在 resolveStoreIntent 之后（顺序即优先级）",
            store < redirect,
        )
        assertTrue(
            "改道必须在 setPackage(origPackage) 之前 —— 否则它永远不会执行",
            redirect < fallback,
        )
    }

    @Test
    fun `只认宿主系统包 android 不劫持别的包`() {
        val added = addedText()
        assertTrue(
            "必须显式判断 component.packageName 等于 android",
            added.contains("\"android\".equals(comp.getPackageName())"),
        )
    }

    @Test
    fun `空间里没有 microG 时保持原样`() {
        val added = addedText()
        assertTrue(
            "探不到 microG 时必须返回 null（保持原样），而不是造一个不存在的组件名",
            added.contains("leaving as-is"),
        )
    }
}
