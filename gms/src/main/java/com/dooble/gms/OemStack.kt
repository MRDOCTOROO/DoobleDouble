package com.dooble.gms

/**
 * 厂商自有服务框架（目前只做华为/荣耀）。
 *
 * ## 用户最常问的问题
 *
 * 「GBox 在华为手机上要用 HMS 核心组件提供更好兼容性，我的应用也要做吗？」
 *
 * 这个说法的**因果是反的**，必须先把事实摆清楚，否则用户会以为
 * "装了 HMS 谷歌服务就能用了"：
 *
 * | 事实 | 说明 |
 * |---|---|
 * | HMS 与 GMS 是**两套并行**的服务框架 | HMS = 华为移动服务，GMS = 谷歌移动服务。装 HMS **不会**让谷歌服务变好，反之亦然 |
 * | HMS 组件华为/荣耀设备**自带** | 不需要用户装，也不需要我们内置 |
 * | GBox 在华为上"必须用"的真正原因 | 2019-05-16 之后发布的华为机型**无法取得 GMS 合法授权**（被制裁），预装三件套跑不起来。GBox 自带的三件套绕过了这一点，**跟 HMS 没有关系** |
 * | HMS 在本工程里的唯一作用 | 让**华为自家应用**（应用市场、华为账号、华为视频等）能在空间里跑起来 —— 它们要调 HMS |
 *
 * 所以本工程对 HMS 的处理很简单：**它是一个可克隆的槽位，不是必需品**。
 * 非华为设备上宿主没有这些包，UI 自然不会显示。
 *
 * ## 红线：纯血鸿蒙装不上本应用
 *
 * HarmonyOS NEXT（鸿蒙 5.0 及以后，"纯血鸿蒙"）**不再兼容 APK**，
 * 只有 .hap 应用能运行。本应用是 APK，在纯血鸿蒙上**根本无法安装**。
 * 能用的只有 HarmonyOS 4.x 及更早（AOSP 内核）的机型。
 * 这一点必须在 UI 上说明，否则华为用户会装不上然后来问为什么。
 *
 * ## 法律边界
 *
 * 与 [GoogleStack] 同一条：HMS 也是华为的专有软件，本工程不打包、不下载，
 * 只支持从**用户自己的设备**克隆。
 */
object OemStack {

    /** 一个厂商服务槽位。字段刻意保持最少 —— 它只用于展示与克隆 */
    data class Slot(
        val packageName: String,
        val shortName: String,
        val description: String,
        /** 缺失时的后果，UI 上直接显示 */
        val impact: String,
    )

    /**
     * 华为/荣耀设备上值得克隆进空间的包。
     *
     * 包名是查证的，不是猜的：
     *  - `com.huawei.hwid` 就是**用户看到的那个"HMS Core / 华为移动服务"**，
     *    腾讯应用宝、小米应用商店、华为官网的下载页都指向这个包名；
     *  - `com.huawei.appmarket` 是华为应用市场；
     *  - `com.huawei.android.pushagent` 是华为推送服务，空间内的华为应用靠它收推送。
     */
    val HUAWEI_SLOTS: List<Slot> = listOf(
        Slot(
            packageName = "com.huawei.hwid",
            shortName = "HMS Core",
            description = "华为移动服务：华为账号登录、应用内支付、消息通知等基础服务。" +
                "华为/荣耀设备自带，装进空间后华为自家应用才能在空间内正常工作",
            impact = "缺失时：空间内的华为应用无法登录华为账号、无法完成支付与推送",
        ),
        Slot(
            packageName = "com.huawei.appmarket",
            shortName = "华为应用市场",
            description = "华为的应用商店。装进空间后可以在空间内独立安装华为渠道的应用",
            impact = "缺失时：空间内没有华为渠道的商店（不影响 Aurora Store）",
        ),
        Slot(
            packageName = "com.huawei.android.pushagent",
            shortName = "华为推送服务",
            description = "HMS Push 的推送代理。空间内的华为应用靠它接收消息",
            impact = "缺失时：空间内的华为应用收不到推送（FCM 走的是 microG，不受影响）",
        ),
    )

    /** 需要探测"宿主上有没有"的包 */
    val WATCHED_PACKAGES: Set<String> = HUAWEI_SLOTS.map { it.packageName }.toSet()

    /**
     * 是否是华为/荣耀设备。判定只吃厂商与品牌字符串，因此可单测。
     *
     * 品牌要单独看：荣耀在 2020 年独立后 `MANUFACTURER` 仍是 `HONOR`，
     * 但早期机型的 `MANUFACTURER` 是 `HUAWEI`、`BRAND` 才是 `HONOR`。
     */
    fun isHuaweiDevice(manufacturer: String?, brand: String?): Boolean {
        val m = manufacturer.orEmpty().lowercase()
        val b = brand.orEmpty().lowercase()
        return listOf("huawei", "honor", "harmony").any { it in m || it in b }
    }

    /**
     * 纯血鸿蒙的判定说明。UI 用它在华为设备上给出前置警告。
     *
     * 判据取 `Build.DISPLAY` / `Build.VERSION.RELEASE` 都不稳（各家改法不一），
     * 所以不做自动判定，只在设备是华为时**无条件提示一次**。
     * 宁可多提示一次，也不要让用户装完才发现跑不起来。
     */
    const val HARMONY_NEXT_WARNING: String =
        "注意：HarmonyOS NEXT（鸿蒙 5.0 及以后，即「纯血鸿蒙」）不再兼容 APK，" +
            "本应用在那些机型上根本无法安装。能用的只有 HarmonyOS 4.x 及更早的机型。"

    /** 为什么 HMS 帮不了谷歌服务 */
    const val HMS_IS_NOT_GMS: String =
        "HMS（华为移动服务）与 GMS（谷歌移动服务）是两套并行的框架，互不替代。" +
            "装 HMS 不会让谷歌服务变好 —— 谷歌侧由 microG 提供；" +
            "HMS 只影响**华为自家应用**能不能在空间里跑。"

    /**
     * 在华为设备上，保活与后台限制更狠，值得单独提一句。
     *
     * 依据：EMUI / HarmonyOS 的"应用启动管理"默认把第三方应用设为"自动管理"，
     * 会在熄屏后冻结其后台进程。虚拟进程从宿主 fork，宿主被冻结则空间内应用全灭。
     * 所以华为用户必须把 Dooble 加入"手动管理 → 允许自启动 / 允许后台活动"。
     */
    const val HUAWEI_KEEPALIVE_HINT: String =
        "华为/荣耀设备的「应用启动管理」默认会自动冻结后台应用。" +
            "请把 Dooble 改成「手动管理」并打开「允许自启动」「允许关联启动」「允许后台活动」，" +
            "否则熄屏后空间内的应用会被一起冻结。"
}
