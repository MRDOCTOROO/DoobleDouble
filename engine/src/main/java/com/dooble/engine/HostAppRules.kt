package com.dooble.engine

/**
 * 宿主应用扫描的**纯逻辑**：谷歌依赖判定、列表过滤与排序、提取产物命名。
 *
 * ## 为什么要抽出来
 *
 * 这些规则原本散在 [HostAppScanner] 里，而那个类直接依赖 `PackageManager`
 * —— JVM 单测里 `PackageManager` 是 `Stub!` 桩，调用即抛异常，规则完全测不到。
 * 抽成只吃数据、只吐数据的对象后，规则本身可以被钉死。
 *
 * 为什么值得钉：这三条规则错了都是**用户可见但很难归因**的失败——
 *  - 谷歌依赖判错 → 该提示"这应用需要谷歌服务"的没提示，用户装完打不开；
 *  - 过滤顺序错 → 用户明明勾了"只看谷歌应用"，列表里却混着微信；
 *  - 命名错 → 提取出来的多 APK 归档后缀不是 `.apks`，容器侧按单 APK 处理，静默装不上。
 */
object HostAppRules {

    /**
     * 权限名判定：哪些声明意味着"这应用依赖谷歌服务"。
     *
     * 用 `startsWith` 而不是 `contains`：`com.google.android.gms.*` 下有一堆
     * 细分权限（`...gms.permission.AD_ID` 等），全都要算；但某个第三方
     * `com.example.gms.thing` 只是名字里带 gms，不该被误判。
     */
    fun usesGoogleApis(declaredPermissions: Collection<String>): Boolean =
        declaredPermissions.any {
            it.startsWith("com.google.android.gms") ||
                it == "com.google.android.c2dm.permission.RECEIVE" ||
                it.startsWith("com.android.vending")
        }

    /** 搜索词匹配：标签或包名任一命中即可，忽略大小写；空搜索词全部通过 */
    fun matchesQuery(info: HostAppInfo, query: String): Boolean {
        if (query.isBlank()) return true
        return info.label.contains(query, ignoreCase = true) ||
            info.packageName.contains(query, ignoreCase = true)
    }

    /**
     * 扫描结果的过滤 + 排序。
     *
     * 顺序即语义，不要随意调整：
     *  1. 排除宿主自己 —— 把 Dooble 自己装进空间是没有意义且必然出问题的；
     *  2. 系统应用默认不显示（[includeSystem]）—— 系统应用通常是拆分包，
     *     且克隆它们大多没有意义，默认隐藏可以显著缩短列表；
     *  3. 只看谷歌依赖（[onlyGmsDependent]）；
     *  4. 搜索词过滤；
     *  5. 按标签排序，用 `lowercase()` 归一大小写，避免 "Zebra" 排到 "apple" 前面。
     */
    fun filter(
        infos: List<HostAppInfo>,
        selfPackage: String,
        includeSystem: Boolean = false,
        onlyGmsDependent: Boolean = false,
        query: String = "",
    ): List<HostAppInfo> = infos.asSequence()
        .filter { it.packageName != selfPackage }
        .filter { includeSystem || !it.isSystem }
        .filter { !onlyGmsDependent || it.usesGoogleApis }
        .filter { matchesQuery(it, query) }
        .sortedBy { it.label.lowercase() }
        .toList()

    /**
     * 提取产物的文件名。
     *
     * @param multiApk 是否真的要把多个 APK 打包成一个归档。
     *                 注意：调用方必须先判断"用户想要拆分包 **且** 该应用确实有拆分包"，
     *                 否则会产出一个只有一个 `base.apk` 条目的 `.apks`，
     *                 而容器侧会当成多 APK 去解 —— 白绕一圈。
     */
    fun archiveName(packageName: String, multiApk: Boolean): String =
        if (multiApk) "$packageName.apks" else "$packageName.apk"

    /**
     * 多 APK 归档里的条目名，顺序固定：base 在最前，split 按原始顺序编号。
     *
     * 顺序有意义：容器侧按顺序解析，base 必须先到（`splitCodePaths` 要挂在
     * 已解析的 base 上）。编号用索引而非原文件名，是因为 split 的原始文件名
     * （`split_config.arm64_v8a.apk`）不保证唯一，且顺序才是 ABI 匹配的依据。
     */
    fun archiveEntries(splitCount: Int): List<String> = buildList {
        add("base.apk")
        repeat(splitCount) { add("split_$it.apk") }
    }

    // ───────────────────── 包可见性（Android 11+ / 国产 ROM） ─────────────────────

    /**
     * 「能看到多少应用」的判定结果。
     *
     * @param visibleCount `getInstalledPackages()` 返回的数量
     * @param launcherCount `queryIntentActivities(MAIN/LAUNCHER)` 返回的数量
     */
    data class VisibilityVerdict(
        val gated: Boolean,
        val visibleCount: Int,
        val launcherCount: Int,
    ) {
        /** 大概被隐藏了多少 */
        val hiddenCount: Int get() = (launcherCount - visibleCount).coerceAtLeast(0)
    }

    /**
     * 判断包可见性是否被拦截。
     *
     * ## 判据为什么是"launcher 比 visible 多"而不是一个固定阈值
     *
     * Android 11（API 30）起 `getInstalledPackages()` 只返回**对调用方可见**的包。
     * `QUERY_ALL_PACKAGES` 是 normal 权限、安装即授予，本该让全部可见；但
     * MIUI / ColorOS / OriginOS / EMUI 把它改成了用户开关（"读取应用列表"），
     * 关着的时候 `getInstalledPackages()` 就只剩自己。
     *
     * 同时，manifest 里声明了 `<queries>` 匹配 MAIN/LAUNCHER 之后，
     * `queryIntentActivities()` 仍然能看到全部有桌面图标的包 —— 那条路径走的是
     * intent 解析，不受"读取应用列表"开关限制。
     *
     * 于是两条路径的数量差就是**确定的**证据：launcher 能看到 100 个、
     * `getInstalledPackages` 只给 1 个，那就一定是被拦了。比"少于 N 个就报警"
     * 这种阈值判断可靠得多 —— 一台干净的测试机本来就可能只有十几个应用。
     *
     * 注意 `launcherCount == 0` 时返回 gated=false：那说明是查询本身失败了，
     * 不能据此断言"被拦截"，否则会给用户一个查不出原因的引导。
     */
    fun visibilityVerdict(visibleCount: Int, launcherCount: Int): VisibilityVerdict =
        VisibilityVerdict(
            gated = launcherCount > visibleCount,
            visibleCount = visibleCount,
            launcherCount = launcherCount,
        )

    /**
     * 按包名去重合并两个来源，[primary] 优先。
     *
     * 兜底路径（launcher 查询）拿到的信息比主路径少（没有 `GET_META_DATA`），
     * 所以主路径已有的条目一律保留，只用兜底补齐缺失的。
     */
    fun mergeDistinct(primary: List<HostAppInfo>, extra: List<HostAppInfo>): List<HostAppInfo> {
        val seen = primary.mapTo(HashSet()) { it.packageName }
        return primary + extra.filter { seen.add(it.packageName) }
    }
}
