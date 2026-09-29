package com.dooble.gms

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.dooble.engine.LaunchResult
import com.dooble.engine.VirtualEngine

data class GoogleAccountInfo(
    val name: String,
    val type: String,
    val spaceId: String,
)

/**
 * Google 账号桥接。
 *
 * ## 原理
 *
 * 应用"登录 Google 账号"这件事，在 Android 上走的是标准 AccountManager 流程：
 *  1. 应用调用 `AccountManager.addAccount("com.google", ...)`；
 *  2. 系统弹出一个实现了 `AbstractAccountAuthenticator` 的界面；
 *  3. 在真机上，这个实现是 `com.google.android.gms` 的 authenticator；
 *  4. 在我们这里，它是**空间内**的 microG GmsCore 提供的 authenticator。
 *
 * 所以只要容器把 AccountManager 的 Binder 调用也代理了（Bcore 会做），
 * 空间内就会有一个**与宿主完全隔离**的账号列表 —— 这正是"多开空间"
 * 能同时登录多个 Google 账号而不互相顶号的原因。
 *
 * ## ⚠️ 登录入口必须在**容器内**解析和启动（踩过的坑）
 *
 * 之前的实现是这样写的：
 *
 * ```kotlin
 * val i = Intent("org.microg.gms.auth.LOGIN")
 * if (context.packageManager.resolveActivity(i, 0) != null) return i   // ← 宿主 PMS
 * return Intent(Settings.ACTION_ADD_ACCOUNT_SETTINGS)                  // ← 兜底
 * ```
 *
 * 两处都是错的，合起来的表现就是**「点登录谷歌账号，弹出一个只有『添加账号』
 * 四个字、点不动的系统页面」**：
 *
 * | # | 问题 | 事实 |
 * |---|---|---|
 * | 1 | action 字符串是编的 | microG 实际声明的是 `com.google.android.gms.auth.login.LOGIN`（`LoginActivity` 的 intent-filter），`org.microg.gms.auth.LOGIN` 在清单里根本不存在 |
 * | 2 | 用**宿主** PMS 解析 | 空间里的包对宿主 PackageManager 不可见，`resolveActivity` 永远返回 null → 永远走兜底 |
 * | 3 | 兜底是系统设置页 | `ACTION_ADD_ACCOUNT_SETTINGS` 打开的是**宿主**的账号页，那里没有 microG 的 authenticator，所以只有一个空的"添加账号"入口 |
 *
 * 修法见 [signIn]：用 [VirtualEngine.resolveActivityInSpace] 在容器里解析，
 * 用 [VirtualEngine.launchIntent] 在容器里启动。
 *
 * ## microG 侧的真实组件（从 APK 清单里读出来的，不是猜的）
 *
 * | 组件 | 作用 |
 * |---|---|
 * | `org.microg.gms.auth.login.LoginActivity` | 登录界面（WebView 加载 accounts.google.com）。action `com.google.android.gms.auth.login.LOGIN`，`exported=true`，进程 `:ui` |
 * | `org.microg.gms.auth.loginservice.GoogleLoginService` | `AbstractAccountAuthenticator` 实现，声明 action `android.accounts.AccountAuthenticator` |
 * | `org.microg.tools.AccountPickerActivity` | 账号选择器，action `com.google.android.gms.common.account.CHOOSE_ACCOUNT` |
 *
 * 注意 microG 的 Google 登录目前仍是实验性的，部分应用会失败。
 * 这一点我们在自检面板里如实告知用户，而不是假装能 100% 成功。
 */
class GoogleAccountBridge(
    private val context: Context,
    private val engine: VirtualEngine,
) {

    /** 空间内的 Google 账号列表 */
    fun accounts(spaceId: String): List<GoogleAccountInfo> {
        virtualAccounts(spaceId)?.let { return it }
        // 降级：读宿主的 Google 账号（仅用于展示与调试）
        val am = AccountManager.get(context)
        return am.getAccountsByType(GOOGLE_TYPE).map {
            GoogleAccountInfo(it.name, it.type, spaceId)
        }
    }

    fun isSignedIn(spaceId: String): Boolean = accounts(spaceId).isNotEmpty()

    /**
     * 通过反射尝试拿到容器内该空间的账号列表。
     * 真引擎（Bcore）会暴露 `getAccounts(spaceId, type)` 之类的接口。
     */
    private fun virtualAccounts(spaceId: String): List<GoogleAccountInfo>? = runCatching {
        val m = engine.javaClass.methods.firstOrNull {
            it.name == "getAccounts" && it.parameterCount == 2
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        val raw = m.invoke(engine, spaceId, GOOGLE_TYPE) as? List<String> ?: return null
        raw.map { GoogleAccountInfo(it, GOOGLE_TYPE, spaceId) }
    }.getOrNull()

    /**
     * 在**空间内**打开 microG 的登录界面。
     *
     * 按优先级依次尝试，全部在容器里解析 —— 宿主看不到空间里的包，
     * 任何用宿主 PMS 做的判断都是错的。
     *
     * 1. 显式组件 `org.microg.gms.auth.login.LoginActivity`（最可靠：不依赖 action 匹配）
     * 2. action `com.google.android.gms.auth.login.LOGIN`（microG 官方声明的入口）
     * 3. 账号选择器 `CHOOSE_ACCOUNT`（已登录过账号时的合理落点）
     *
     * 三个都解析不到，说明空间里没有可用的 microG，如实返回失败原因 ——
     * **不要再回退到宿主系统设置页**，那只会给用户一个点不动的空页面。
     */
    suspend fun signIn(spaceId: String): LaunchResult {
        val attempts = listOf(
            "登录界面（显式组件）" to Intent().setComponent(
                ComponentName(GMS_PACKAGE, LOGIN_ACTIVITY)
            ),
            "登录界面（action）" to Intent(ACTION_MICROG_LOGIN).setPackage(GMS_PACKAGE),
            "账号选择器" to Intent(ACTION_CHOOSE_ACCOUNT).setPackage(GMS_PACKAGE),
        )

        var lastReason = "空间内没有可用的 Google 登录组件"
        for ((label, intent) in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = engine.resolveActivityInSpace(spaceId, intent)
            // 每一步都记日志：这条链路失败是**静默**的（只是弹个 toast 就没了），
            // 没有日志的话现场只有一个"点了没反应"，无法判断是
            // 容器没解析到、还是解析到了但启动失败。
            Log.i(TAG, "[$spaceId] $label 解析结果=$resolved")
            if (resolved == null) {
                lastReason = "$label 未在空间内注册"
                continue
            }
            val result = engine.launchIntent(spaceId, intent)
            Log.i(TAG, "[$spaceId] $label 启动结果 ok=${result.ok} msg=${result.message}")
            if (result.ok) return result
            lastReason = "$label 启动失败：${result.message}"
        }

        Log.w(TAG, "[$spaceId] 全部登录入口都不可用：$lastReason")
        return LaunchResult.fail(
            "$lastReason。请先在「谷歌环境」里装配 microG（GmsCore 是登录界面的提供方）。"
        )
    }

    /**
     * 移除空间内的某个 Google 账号。
     * 同样优先走容器通道，保证只影响该空间。
     */
    fun removeAccount(spaceId: String, name: String): Boolean = runCatching {
        val m = engine.javaClass.methods.firstOrNull {
            it.name == "removeAccount" && it.parameterCount == 3
        }
        if (m != null) {
            m.invoke(engine, spaceId, name, GOOGLE_TYPE) as? Boolean ?: false
        } else {
            val am = AccountManager.get(context)
            val acc = am.getAccountsByType(GOOGLE_TYPE).firstOrNull { it.name == name }
                ?: return false
            @Suppress("DEPRECATION")
            am.removeAccount(acc, null, null)
            true
        }
    }.getOrDefault(false)

    /** 供 UI 展示：账号头像/昵称占位（真实实现会查 People API，此处不联网） */
    fun displayName(account: GoogleAccountInfo): String =
        account.name.substringBefore('@').replace('.', ' ').replaceFirstChar { it.uppercase() }

    /**
     * 宿主上是否装着 Google 账号体系（用于提示"这个空间没有 microG"）。
     * 注意：这是**宿主**的判断，只用来给出更好的错误文案，不参与启动决策。
     */
    fun hostHasGoogleAccounts(): Boolean =
        runCatching { AccountManager.get(context).getAccountsByType(GOOGLE_TYPE).isNotEmpty() }
            .getOrDefault(false)

    companion object {
        private const val TAG = "DoobleAccount"

        const val GOOGLE_TYPE = "com.google"
        const val GMS_PACKAGE = "com.google.android.gms"

        /** microG 登录界面的显式组件 */
        const val LOGIN_ACTIVITY = "org.microg.gms.auth.login.LoginActivity"

        /**
         * microG 声明的登录 action。
         *
         * ⚠️ 不是 `org.microg.gms.auth.LOGIN` —— 那个字符串在 microG 的清单里
         * 不存在。正确值是 `LoginActivity` 的 intent-filter 里那一条。
         * 验证方式：`aapt2 dump xmltree --file AndroidManifest.xml <gms.apk>`
         * 然后搜 `auth.login`。
         */
        const val ACTION_MICROG_LOGIN = "com.google.android.gms.auth.login.LOGIN"

        /** 账号选择器（`org.microg.tools.AccountPickerActivity`） */
        const val ACTION_CHOOSE_ACCOUNT = "com.google.android.gms.common.account.CHOOSE_ACCOUNT"

        /**
         * 宿主侧的系统"添加账号"页。
         *
         * **不要**拿它当登录入口：它打开的是宿主的账号设置，
         * 而 microG 的 authenticator 在空间里，宿主看不到它 ——
         * 用户会看到一个只有"添加账号"文字、点不动的空页面。
         * 保留常量只是为了排障时能对照。
         */
        const val ACTION_ADD_ACCOUNT = "android.settings.ADD_ACCOUNT_SETTINGS"

        /** 供 UI 展示的说明：为什么点了没反应 */
        const val SIGN_IN_HINT =
            "登录界面由空间内的 microG GmsCore 提供，会在容器里打开。" +
                "如果提示「未在空间内注册」，说明这个空间还没装配谷歌环境。"

        fun accountOf(name: String) = Account(name, GOOGLE_TYPE)
    }
}
