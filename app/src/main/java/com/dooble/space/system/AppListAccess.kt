package com.dooble.space.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 「读取应用列表」被拦截时的引导。
 *
 * ## 背景：为什么需要这个
 *
 * Android 11（API 30）起 `getInstalledPackages()` 只返回**对调用方可见**的包。
 * 正规做法是声明 `QUERY_ALL_PACKAGES`（normal 权限，安装即授予），
 * 但 MIUI / ColorOS / OriginOS / EMUI 把它改成了**用户开关**，
 * 默认关闭，关着的时候那个 API 只返回本应用自己。
 *
 * 用户的体感是"点了导入已安装的应用，列表是空的"，而系统里装着一百多个应用。
 * 这个功能不给出引导的话，用户根本不知道要去哪里开。
 *
 * ## 两条腿走路
 *
 * 1. **能绕就绕**：`AndroidManifest` 里声明 `<queries>` 匹配 MAIN/LAUNCHER，
 *    `queryIntentActivities()` 就能看到全部有桌面图标的包 —— 这条路不受开关影响。
 *    所以 [com.dooble.engine.HostAppScanner] 会用它兜底，绝大多数应用照样能导入。
 * 2. **绕不过就引导**：`<queries>` 看不到没有桌面图标的应用（后台服务、插件、
 *    以及宿主上的 GMS / Play 商店这类系统应用）。这些必须用户去开权限。
 *
 * ## 关于 ROM 深链
 *
 * 各厂商的设置页组件名随版本反复变，没有任何一个是可靠的。所以这里的策略是
 * **候选列表 + 逐个 `resolveActivity` 探测**，最后一定兜底到
 * `ACTION_APPLICATION_DETAILS_SETTINGS`（所有 Android 都支持的标准入口）。
 * 这样最坏情况是"多点两下"，而不是"点了没反应"。
 */
object AppListAccess {

    /** ROM 家族。判定只依赖厂商/品牌字符串，所以是可单测的纯逻辑。 */
    enum class Rom { MIUI, COLOR_OS, ORIGIN_OS, EMUI, SAMSUNG, OTHER }

    /**
     * 从厂商与品牌字符串判定 ROM 家族。
     *
     * 两个参数都传进来是因为各家填得不一样：小米的 `MANUFACTURER` 是 `Xiaomi`，
     * 但红米/POCO 的 `BRAND` 会是 `Redmi` / `POCO`；
     * 一加/realme 属于 ColorOS 阵营但厂商名各不相同。
     */
    fun romOf(manufacturer: String?, brand: String?): Rom {
        val m = manufacturer.orEmpty().lowercase()
        val b = brand.orEmpty().lowercase()
        fun any(vararg keys: String) = keys.any { it in m || it in b }

        return when {
            any("xiaomi", "redmi", "poco") -> Rom.MIUI
            any("oppo", "oneplus", "realme", "coloros") -> Rom.COLOR_OS
            any("vivo", "iqoo") -> Rom.ORIGIN_OS
            any("huawei", "honor", "harmony") -> Rom.EMUI
            any("samsung") -> Rom.SAMSUNG
            else -> Rom.OTHER
        }
    }

    /**
     * 该 ROM 上值得尝试的"权限管理页"组件名，按优先级排列。
     *
     * 返回 `package/class` 形式的字符串（而不是 `ComponentName`）：这样纯逻辑
     * 可以在 JVM 单测里跑 —— `ComponentName` 在单测环境是 `Stub!` 桩。
     */
    fun permissionPageCandidates(rom: Rom): List<String> = when (rom) {
        Rom.MIUI -> listOf(
            // MIUI 的"权限管理 → 应用权限管理"，接受 extra_pkgname 直接跳到本应用
            "com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity",
            "com.miui.securitycenter/com.miui.permcenter.permissions.AppPermissionsEditorActivity",
            // 老 MIUI 的类名就是这么拼的（MainAcitivty），不是笔误
            "com.miui.securitycenter/com.miui.permcenter.MainAcitivty",
        )

        Rom.COLOR_OS -> listOf(
            "com.coloros.securitypermission/com.coloros.securitypermission.permission.PermissionGroupsActivity",
            "com.oplus.securitypermission/com.oplus.securitypermission.permission.PermissionGroupsActivity",
            "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        )

        Rom.ORIGIN_OS -> listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.PurviewTabActivity",
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )

        Rom.EMUI -> listOf(
            "com.huawei.systemmanager/com.huawei.permissionmanager.ui.MainActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        )

        Rom.SAMSUNG -> listOf(
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm_cn/com.samsung.android.sm.ui.battery.BatteryActivity",
        )

        Rom.OTHER -> emptyList()
    }

    /**
     * 构造实际可用的引导入口，按优先级排列。**最后一项一定是系统标准入口**，
     * 所以返回的列表永远非空（只要有 ActivityManager 能解析）。
     */
    fun guideIntents(context: Context): List<GuideTarget> {
        val self = context.packageName
        val out = mutableListOf<GuideTarget>()

        // 1) 先试"应用详情页"。它虽然不是"读取应用列表"开关所在的位置，
        //    但几乎所有 ROM 的权限入口都能从它进去，而且是标准 API、必定可用。
        out += GuideTarget(
            label = "打开本应用的系统设置",
            hint = "在「权限」里找「读取应用列表」/「获取应用列表」并允许",
            intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", self, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

        // 2) 再补 ROM 专属的权限管理页（探测过才加，避免点了没反应）
        val rom = romOf(Build.MANUFACTURER, Build.BRAND)
        permissionPageCandidates(rom).forEach { flat ->
            val cn = ComponentName.unflattenFromString(flat) ?: return@forEach
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // MIUI 的权限页靠这个 extra 定位到具体应用，没有它就会停在列表页
                putExtra("extra_pkgname", self)
                putExtra("packageName", self)
            }
            if (runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() != null) {
                out += GuideTarget(
                    label = "打开${romLabel(rom)}的权限管理",
                    hint = "允许「读取应用列表」后回到本页重新扫描",
                    intent = intent,
                )
            }
        }

        // 3) 兜底：应用详情页打不开的极端情况，至少让用户能进系统设置自己找
        out += GuideTarget(
            label = "打开系统设置",
            hint = "手动进入「应用管理 → Dooble → 权限」",
            intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

        return out
    }

    fun romLabel(rom: Rom): String = when (rom) {
        Rom.MIUI -> "MIUI / 澎湃"
        Rom.COLOR_OS -> "ColorOS"
        Rom.ORIGIN_OS -> "OriginOS"
        Rom.EMUI -> "EMUI / 鸿蒙"
        Rom.SAMSUNG -> "One UI"
        Rom.OTHER -> "系统"
    }

    data class GuideTarget(val label: String, val hint: String, val intent: Intent)
}
