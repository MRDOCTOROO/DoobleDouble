package com.dooble.gms

import com.dooble.engine.StorePackages

/**
 * 关于「原版谷歌服务」的边界说明。
 *
 * 这个对象里没有逻辑，只有**必须对用户说清楚的事实**，集中放在一处，
 * 免得散落在 UI 里各写一份、慢慢走样。
 *
 * ## 用户最常问的两件事
 *
 * 1. 「能不能把原版谷歌三件套（GMS Core / Play 商店 / GSF）预装进空间？」
 *    → 本工程**不打包**，原因见 [WHY_NOT_BUNDLED]。但可以做，路径见
 *      [HOW_TO_GET_OFFICIAL]，而且容器侧是支持的（见 [CONTAINER_SUPPORT]）。
 * 2. 「原版 Play 商店装进空间能用吗？」
 *    → 能装、能打开，但**下载功能大概率不可用**，原因见 [PLAY_STORE_REQUIREMENTS]。
 */
object GoogleStack {

    /**
     * 为什么不能随包分发。
     *
     * 这是本工程的核心红线之一，和「不打包原版 Play 商店」是同一条：
     * 原版 GMS 全家桶是 Google 的专有软件，其服务条款不允许再分发，
     * 打包进 APK 分发属于侵权。GBox 这类产品这么做了，代价是法律风险，
     * 而且一旦被投诉就得下架重做。
     */
    const val WHY_NOT_BUNDLED: String =
        "原版 GMS / Play 商店是 Google 的专有软件，服务条款不允许再分发，" +
            "所以本应用不内置、也无法替你下载。microG（Apache-2.0）是唯一可以合法随包分发的实现。"

    /**
     * 容器侧为什么能支持「原版」。
     *
     * 这条是查 Bcore 源码得到的结论，不是猜测：
     *
     * `BlackBoxCore.installPackageAsUser(String packageName, int userId)` 内部走
     * `InstallOption.installBySystem()`，即 **FLAG_SYSTEM 语义**。它在
     * `BPackageManagerService` 里做的是：
     * ```java
     * if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
     *     aPackage.applicationInfo =
     *         BlackBoxCore.getPackageManager().getPackageInfo(pkg, 0).applicationInfo;
     * }
     * ```
     * 也就是**直接引用宿主上的 ApplicationInfo**（含 `FLAG_SYSTEM` 与宿主
     * `nativeLibraryDir`），并且 `CopyExecutor` 不复制 APK、不抽 native lib。
     *
     * 于是容器内的应用查询这个包时，看到的就是一个**系统应用**——
     * 这正是 GMS / Play 商店在容器里能正常工作的前提，等价于社区方案里说的
     * "privileged factory slot"，只不过是在容器命名空间内实现的。
     */
    const val CONTAINER_SUPPORT: String =
        "容器支持：从宿主克隆时走的是 Bcore 的 system 语义（直接引用宿主 ApplicationInfo、" +
            "不复制 APK、不抽 native 库），容器内查询该包会看到系统应用身份 —— " +
            "这是 GMS / Play 商店这类预装应用能在容器里跑起来的前提。"

    /**
     * 拿到原版组件的两条合法路径。
     *
     * 都建立在"这是用户自己设备上的副本"之上，不涉及我们分发。
     */
    const val HOW_TO_GET_OFFICIAL: String =
        "两条合法路径：① 从宿主克隆 —— 你的手机是谷歌认证设备的话，系统里本来就有，" +
            "在下面的槽位点「从宿主克隆」；② 导入 APK —— 你自己从可信来源取得的 APK，" +
            "用「导入 APK」选文件。"

    /**
     * 原版 Play 商店真正可用需要什么。
     *
     * 依据是社区实测方案（microG + 官方 Play Store 达到 MEETS_DEVICE_INTEGRITY）：
     * 需要 root、需要把官方签名的 vending 放进**特权系统槽位**、
     * 需要 Play Integrity 相关的 attestation 组件（Play Integrity Fork / Tricky Store 之类）。
     * 而且即使全部满足，`MEETS_DEVICE_INTEGRITY` 也是**服务端判定**，
     * Google 改规则就失效。
     */
    val PLAY_STORE_REQUIREMENTS: List<String> = listOf(
        "签名伪造切到 OFFICIAL，且自备 Google 官方证书（本工程不附带）",
        "设备指纹伪装**真正生效** —— 注意：本工程只写出了配置，容器侧的 Build 改写尚未实现，这一条目前**不满足**",
        "电源优化白名单放行（否则 Android 16 上后台下载服务会被冻结）",
        "Google 服务端的设备认证 —— 这一步在免 root 容器里拿不到硬件凭据，过不了",
    )

    /**
     * 对原版 Play 商店的结论。
     *
     * 这条必须写得很直白，因为用户会花时间去找 APK、去装，然后卡在"设备未认证"。
     */
    const val PLAY_STORE_VERDICT: String =
        "结论：能装上、能打开，但登录与下载大概率停在「设备未认证」。" +
            "Play 商店的下载能力依赖 Google 服务端的 Play Integrity 判定，需要硬件凭据，" +
            "免 root 容器提供不了。所以本工程主推 Aurora Store —— " +
            "它走同一套 Play 接口、可以登录谷歌账号、官方明确兼容 microG，" +
            "且**不做设备认证**，因此实际可用。"

    /**
     * 槽位互斥说明：两个包名各只能装一个实现。
     */
    const val SLOT_EXCLUSIVE: String =
        "com.google.android.gms 与 com.android.vending 这两个包名各只能有一个实现：" +
            "microG 版与原版共用包名，后装的会替换先装的。"

    /**
     * UI 需要探测「宿主上有没有」的包。
     *
     * 含 Aurora Store 自己：用户在宿主上装了 Aurora，也可以直接克隆进空间。
     */
    val WATCHED_PACKAGES: Set<String> = setOf(
        MicroGComponent.GMS_CORE.packageName,
        MicroGComponent.FAKE_STORE.packageName,
        MicroGComponent.GSF_PROXY.packageName,
        MicroGComponent.MICROG_RE.packageName,
        StorePackages.AURORA,
    )

    /**
     * 这两个包名的**原版**实现是否必须走「从宿主克隆」。
     *
     * 注意限定词是"原版"。microG 版走官方直链 + storage 语义安装是**可用**的
     * （本工程的主链路就是这么装的，实测能跑）—— 它不带 native 库，
     * 也不检查自己的系统应用身份。
     *
     * 而原版 GMS / Play 商店两样都要：它们有 native 库，且会读自己的
     * `ApplicationInfo.flags` 判断是不是系统应用。storage 语义会把 APK 复制进
     * 容器并抽 native 库，但**不会**给系统应用身份 —— 装了也起不来。
     * 所以原版只能走「从宿主克隆」（system 语义）。
     */
    fun officialNeedsSystemInstall(packageName: String): Boolean =
        packageName == MicroGComponent.GMS_CORE.packageName ||
            packageName == MicroGComponent.FAKE_STORE.packageName

    /** 该包名是否与 microG 栈共用（即"二选一"的槽位） */
    fun isSharedSlot(packageName: String): Boolean =
        MicroGComponent.byPackage(packageName) != null
}
