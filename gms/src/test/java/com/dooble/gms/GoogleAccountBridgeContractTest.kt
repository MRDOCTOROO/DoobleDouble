package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * microG 登录入口的协议常量测试。
 *
 * ## 为什么需要它
 *
 * 这里曾经有一个**纯靠猜**的字符串，代价是用户直接看到功能坏了：
 * 点「登录 Google 账号」弹出一个只有「添加账号」四个字、点不动的系统页面。
 *
 * 完整的因果链（三条同时成立才会出这个现象）：
 *
 * 1. action 常量写成了 `org.microg.gms.auth.LOGIN` —— microG 清单里**没有**这一条；
 * 2. 用**宿主**的 `PackageManager.resolveActivity()` 去查 → 永远 null
 *    （空间里的包对宿主不可见）；
 * 3. 兜底是 `ACTION_ADD_ACCOUNT_SETTINGS` → 打开宿主的账号设置页，
 *    那里没有 microG 的 authenticator，只剩一个空入口。
 *
 * 这三条里最容易再犯的是第 1 条：字符串打错了编译期无感、运行期静默降级。
 * 所以把正确值钉死在测试里。
 *
 * ## 这些值是怎么来的（可复现）
 *
 * 不是查文档、不是猜，是从 APK 清单里读出来的：
 *
 * ```bash
 * # 1. 从设备上把 microG 的 APK 取出来（exec-out 才是二进制安全的）
 * adb exec-out run-as <宿主包名> cat \
 *   /data/data/<宿主包名>/files/dooble/gms-cache/com.google.android.gms.apk > gms.apk
 * # 2. 解出清单，搜 auth.login
 * aapt2 dump xmltree --file AndroidManifest.xml gms.apk | grep -i auth.login
 * ```
 *
 * 实测输出：
 * ```
 * activity name="org.microg.gms.auth.login.LoginActivity"  exported=true process=":ui"
 *   intent-filter → action name="com.google.android.gms.auth.login.LOGIN"
 *                   category name="android.intent.category.DEFAULT"
 * ```
 */
class GoogleAccountBridgeContractTest {

    @Test
    fun `登录 action 必须是 microG 实际声明的那一条`() {
        assertEquals(
            "com.google.android.gms.auth.login.LOGIN",
            GoogleAccountBridge.ACTION_MICROG_LOGIN,
        )
    }

    @Test
    fun `登录 action 不能是曾经写错的那个`() {
        // 回归守卫：这个字符串看起来"更合理"（org.microg 前缀 + AUTH + LOGIN），
        // 但它不存在。谁要是觉得现在这个太啰嗦又改回去，这条会立刻红。
        assertNotEquals(
            "org.microg.gms.auth.LOGIN",
            GoogleAccountBridge.ACTION_MICROG_LOGIN,
        )
        assertFalse(
            GoogleAccountBridge.ACTION_MICROG_LOGIN.startsWith("org.microg."),
        )
    }

    @Test
    fun `登录 activity 的类名与包名正确`() {
        assertEquals("org.microg.gms.auth.login.LoginActivity", GoogleAccountBridge.LOGIN_ACTIVITY)
        assertEquals("com.google.android.gms", GoogleAccountBridge.GMS_PACKAGE)
    }

    @Test
    fun `账号选择器 action 正确`() {
        // 对应 org.microg.tools.AccountPickerActivity
        assertEquals(
            "com.google.android.gms.common.account.CHOOSE_ACCOUNT",
            GoogleAccountBridge.ACTION_CHOOSE_ACCOUNT,
        )
    }

    @Test
    fun `三个 action 互不相同`() {
        val actions = setOf(
            GoogleAccountBridge.ACTION_MICROG_LOGIN,
            GoogleAccountBridge.ACTION_CHOOSE_ACCOUNT,
            GoogleAccountBridge.ACTION_ADD_ACCOUNT,
        )
        assertEquals("三个 action 不能有重复", 3, actions.size)
    }

    @Test
    fun `宿主系统账号页的 action 只保留作对照`() {
        // 它的值是对的（系统常量），但**绝不能**当登录入口用 ——
        // 见类注释里的第 3 条。这条测试存在的意义是把"它还在"这件事记下来，
        // 免得有人以为常量没用就删掉，然后在排障时找不到对照物。
        assertEquals(
            "android.settings.ADD_ACCOUNT_SETTINGS",
            GoogleAccountBridge.ACTION_ADD_ACCOUNT,
        )
        assertNotEquals(
            GoogleAccountBridge.ACTION_ADD_ACCOUNT,
            GoogleAccountBridge.ACTION_MICROG_LOGIN,
        )
    }

    @Test
    fun `Google 账号类型常量不变`() {
        // 这个值要和容器内 microG 注册的 authenticator 类型一致，改了就找不到账号。
        assertEquals("com.google", GoogleAccountBridge.GOOGLE_TYPE)
    }

    @Test
    fun `提示文案说明了登录界面在容器里打开`() {
        // 用户在"点了没反应"时唯一的自查线索就是这句。
        assertTrue(GoogleAccountBridge.SIGN_IN_HINT.contains("容器"))
        assertTrue(GoogleAccountBridge.SIGN_IN_HINT.contains("microG"))
    }
}
