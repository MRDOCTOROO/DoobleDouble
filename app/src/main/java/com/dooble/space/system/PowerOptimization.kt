package com.dooble.space.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 电源优化（电池优化白名单）与厂商自启管理的唤起。
 *
 * ## 为什么这对本项目是刚需，而不是"顺手加的开关"
 *
 * 虚拟应用是从宿主进程 fork 出来的。系统在做 Doze / App Standby 的进程回收时，
 * 判定依据是**宿主包的 uid**——只要宿主被判为"可优化"，整个进程组（宿主 + 全部
 * 空间内应用）会在熄屏一段时间后被一起冻结或杀掉。用户看到的现象是
 * "空间里的应用过一会儿就没了"，而它会误判成容器的 bug。
 *
 * 把宿主加进电池优化白名单，是唯一不需要 root 就能改变这个判定的办法。
 * 注意它**不是**保活服务：保活解决"进程优先级"，白名单解决"系统愿不愿意动你"。
 * 两者叠加才有效，所以设置页里两块挨着放。
 *
 * ## 关于 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限
 *
 * 走"直接弹确认框"这条路（[directIntent]）必须声明该权限，否则系统抛
 * SecurityException。Google Play 对这条权限有政策限制，但本工程不通过商店分发，
 * 且声明它本身不授予任何能力——最终仍要用户在系统弹窗上点"允许"。
 *
 * 拿不到直连（部分 ROM 会屏蔽这个 action）时回退到优化列表页 [settingsIntent]，
 * 用户在列表里自己找。**不假装成功**：返回值如实告诉调用方走的是哪条路。
 */
object PowerOptimization {

    private const val TAG = "DooblePower"

    /** 宿主的电池优化状态 */
    enum class State {
        /** 已加入白名单（不会因为 Doze 被回收） */
        IGNORED,

        /** 仍受优化（可能被 Doze 冻结/杀掉） */
        OPTIMIZED,

        /** 系统低于 6.0，没有这套机制；或者查询失败 */
        UNKNOWN,
    }

    /** 唤起结果。UI 据此决定提示语——两条路的用户动作完全不同 */
    enum class Route {
        /** 直接弹出"是否允许后台运行"确认框 */
        DIRECT_DIALOG,

        /** 只能打开系统的电池优化列表页，用户自己找本应用 */
        SETTINGS_LIST,

        /** 系统不支持 */
        UNSUPPORTED,
    }

    fun state(context: Context): State {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return State.IGNORED
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return State.UNKNOWN
        return runCatching {
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                State.IGNORED
            } else {
                State.OPTIMIZED
            }
        }.getOrDefault(State.UNKNOWN)
    }

    /**
     * 直连意图：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` uri。
     *
     * `@SuppressLint("BatteryLife")` 是必须的：lint 会因为"未在清单里声明权限"或
     * "Google Play 政策"报错，而这两点在本工程里都是有意为之（见类注释）。
     *
     * ## 为什么必须带 `FLAG_ACTIVITY_NEW_TASK`
     *
     * 调用方是 Application context（ViewModel 里 `getApplication()`），
     * 不是 Activity。用非 Activity 的 Context 启动 Activity 时若不带这个 flag，
     * 系统会抛 `AndroidRuntimeException: Calling startActivity() from outside of
     * an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
     *
     * 实测踩到：异常被 [request] 里的 `runCatching` 吞掉，于是两条路都被判为
     * "失败"，函数一路退化到 [Route.UNSUPPORTED]，用户看到的是"点了没反应 +
     * 一句系统没有入口"——而系统明明有这个页面。
     */
    @SuppressLint("BatteryLife")
    fun directIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 回退意图：系统的"电池优化"列表页（同样需要 NEW_TASK，理由见 [directIntent]） */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 尝试唤起。返回实际能走的那条路，**不抛异常**。
     *
     * 之所以要 `resolveActivity` 预检：不少国产 ROM 把这个 action 从
     * Settings 里摘掉了，直接 startActivity 会抛 ActivityNotFoundException，
     * 用户看到的是"点了没反应 + 一个崩溃日志"。
     *
     * 两条路都失败时**必须留日志**。早期版本这里是静默 return，
     * 结果 `FLAG_ACTIVITY_NEW_TASK` 缺失导致的 `AndroidRuntimeException`
     * 被完全吞掉，排查时只能看到"点了没反应"——代价是整轮真机验证。
     */
    fun request(context: Context): Route {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Route.UNSUPPORTED

        val pm = context.packageManager

        val direct = directIntent(context)
        if (pm.resolveActivity(direct, 0) == null) {
            Log.w(TAG, "系统没有 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 页面，回退到列表页")
        } else {
            val r = runCatching { context.startActivity(direct) }
            if (r.isSuccess) return Route.DIRECT_DIALOG
            Log.w(TAG, "拉起电源优化确认框失败，回退到列表页", r.exceptionOrNull())
        }

        val list = settingsIntent()
        if (pm.resolveActivity(list, 0) == null) {
            Log.w(TAG, "系统没有 IGNORE_BATTERY_OPTIMIZATION_SETTINGS 页面")
        } else {
            val r = runCatching { context.startActivity(list) }
            if (r.isSuccess) return Route.SETTINGS_LIST
            Log.w(TAG, "拉起电池优化列表失败", r.exceptionOrNull())
        }

        return Route.UNSUPPORTED
    }

    // ------------------------------------------------------------ 厂商自启管理

    /**
     * 厂商"自启动 / 后台弹出界面"管理页。
     *
     * 电池优化白名单管的是**系统**的 Doze，管不了国产 ROM 自己的后台冻结策略。
     * 这两套是并行的：小米的"神隐模式"、华为的"应用启动管理"、OPPO 的
     * "耗电保护"都不读 AOSP 的白名单。所以必须另外给一个入口。
     *
     * 下面这张表是社区长期验证过的组件名。**刻意做成"尽力而为"**：
     * 组件名随 ROM 版本变动是常态，找不到就返回 null，UI 如实说
     * "当前系统没有可用的自启管理入口"，而不是假装打开了一个页面。
     */
    private val AUTOSTART_PAGES: List<Pair<String, String>> = listOf(
        // 小米 / 红米（MIUI、HyperOS）
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为 / 荣耀（EMUI、MagicOS）
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // OPPO / 一加 / realme（ColorOS）
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // vivo / iQOO（OriginOS、Funtouch）
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // 三星（One UI）
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // 魅族（Flyme）
        "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
    )

    /**
     * 打开厂商自启管理页。返回 true 表示确实拉起了某个页面。
     */
    fun openAutostartSettings(context: Context): Boolean {
        val pm = context.packageManager
        for ((pkg, cls) in AUTOSTART_PAGES) {
            val component = ComponentName(pkg, cls)
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 部分 ROM 的入口只认这个 action，纯 Component 拉起会被拒
                if (cls.endsWith("SHOW_APPSEC")) action = cls
            }
            if (pm.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** 当前 ROM 是否命中已知的自启管理入口，UI 用它决定按钮是否可点 */
    fun hasAutostartSettings(context: Context): Boolean {
        val pm = context.packageManager
        return AUTOSTART_PAGES.any { (pkg, cls) ->
            pm.resolveActivity(Intent().setComponent(ComponentName(pkg, cls)), 0) != null
        }
    }

    /** 用于在 UI 上如实标注"为什么还要单独去 ROM 里设一遍" */
    fun romLabel(): String {
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val raw = "$manufacturer $brand".trim()
        return when {
            raw.isEmpty() -> "未知 ROM"
            raw.contains("xiaomi", true) || raw.contains("redmi", true) -> "MIUI / HyperOS"
            raw.contains("huawei", true) || raw.contains("honor", true) -> "EMUI / MagicOS"
            raw.contains("oppo", true) || raw.contains("oneplus", true) ||
                raw.contains("realme", true) -> "ColorOS"
            raw.contains("vivo", true) || raw.contains("iqoo", true) -> "OriginOS / Funtouch"
            raw.contains("samsung", true) -> "One UI"
            raw.contains("meizu", true) -> "Flyme"
            else -> raw
        }
    }
}
