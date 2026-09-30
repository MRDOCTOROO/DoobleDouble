package com.dooble.gms

/**
 * microG 组件清单。
 *
 * 为什么要用 microG 而不是原版 GMS：
 *  1. **体积**：原版 GMS 全家桶 300MB+ 且必须常驻多个进程；microG GmsCore 单包约 100MB，
 *     且可按需只启用在用到的 API，内存占用是原版的三分之一左右；
 *  2. **可分发**：Apache-2.0，允许随应用分发、允许二次修改，原版 GMS 不允许；
 *  3. **可签名伪装**：microG 的设计目标就是"以 Google 的包名和签名对外呈现"，
 *     这与容器内的签名伪造天然契合。
 *
 * 包名对应关系（这是整套方案的关键，不能写错）：
 *  - `com.google.android.gms`   ← GmsCore 本体
 *  - `com.android.vending`      ← FakeStore（让应用以为 Play 商店存在）
 *  - `com.google.android.gsf`   ← GSF Proxy
 */
enum class MicroGComponent(
    val packageName: String,
    /**
     * **本地**缓存 / 离线资产的文件名。这是本工程自己的命名，与上游无关，
     * 所以改上游版本时它不用动 —— 用户预先放进 `assets/microg/` 的文件不会失效。
     */
    val assetName: String,
    val system: Boolean,
    val required: Boolean,
    val approxSizeMb: Int,
    /**
     * UI 上显示的名字。
     *
     * 不用包名：`com.google.android.gms` 去掉 `com.` 再被标签字体转成大写，
     * 会渲染成 `GOOGLE.ANDROID.GMS`，在界面上非常难读。包名该出现在
     * 排障日志和文档里，不该出现在用户第一眼看到的清单里。
     */
    val shortName: String,
    val description: String,
    /**
     * 上游仓库，形如 `owner/repo`。
     *
     * GSF Proxy 是 microG 的**另一个独立仓库**（`microg/GsfProxy`），
     * 不在 GmsCore 的 release 里 —— 这正是它此前"无直链"的原因。
     */
    val downloadRepo: String,
    /** 该组件在上游仓库里的**已知可用** release tag。各仓库的 tag 互不相同。 */
    val releaseTag: String,
    /** 上游 release 里非华为变体的文件名；空串表示无官方直链 */
    val remoteName: String,
    /** 上游 release 里华为/荣耀变体的文件名；null 表示上游没有出这个变体 */
    val remoteNameHw: String? = null,
    /**
     * 在 release 资产列表里匹配文件名时用的**前缀**。
     *
     * 默认用包名（官方 microG 的资产名就是 `com.google.android.gms-xxx.apk`）。
     * microG RE 的资产名是 `microg-7.1.1-*.apk`，不以包名开头，必须显式声明。
     */
    val assetPrefix: String = packageName,
    /**
     * 无官方直链的原因。非 null 表示这个组件**拿不到可直接安装的文件**，
     * UI 必须走"手动导入"路径，并把这个理由显示出来。
     */
    val manualOnlyReason: String? = null,
) {
    GMS_CORE(
        packageName = "com.google.android.gms",
        assetName = "com.google.android.gms.apk",
        system = true,
        required = true,
        approxSizeMb = 103,
        shortName = "GmsCore",
        description = "microG Services Core：Play Services 的开源实现，Google 账号登录、位置、推送、地图 API 的主体",
        downloadRepo = "microg/GmsCore",
        releaseTag = "v0.3.16.252432",
        remoteName = "com.google.android.gms-252432032.apk",
        remoteNameHw = "com.google.android.gms-252432032-hw.apk",
    ),

    FAKE_STORE(
        packageName = "com.android.vending",
        assetName = "com.android.vending.apk",
        system = true,
        required = true,
        approxSizeMb = 4,
        shortName = "FakeStore",
        description = "FakeStore：以 Play 商店的包名占位，让应用认为商店可用（本身不是真正的商店）",
        downloadRepo = "microg/GmsCore",
        releaseTag = "v0.3.16.252432",
        remoteName = "com.android.vending-84022632.apk",
        remoteNameHw = "com.android.vending-84022632-hw.apk",
    ),

    GSF_PROXY(
        packageName = "com.google.android.gsf",
        assetName = "com.google.android.gsf.apk",
        system = true,
        required = false,
        approxSizeMb = 1,
        shortName = "GSF Proxy",
        description = "GSF Proxy：把老的 C2DM/GCM 客户端库的调用转发给 GmsCore。只服务 2016 年前的老库，现代应用（FCM）不需要",
        downloadRepo = "microg/GsfProxy",
        releaseTag = "v0.1.0",
        remoteName = "GsfProxy.apk",
    ),

    MICROG_RE(
        packageName = "app.revanced.android.gms",
        assetName = "microg-re.apk",
        system = true,
        required = false,
        approxSizeMb = 112,
        shortName = "microG RE",
        description = "microG RE（ReVanced 分支）：为 ReVanced/Morphe 修补应用提供 GMS 能力。" +
            "注意它使用**独立包名** app.revanced.android.gms，不是 com.google.android.gms，" +
            "普通 Google 应用不会把它识别为 Play 服务，也不参与 Google 账号登录。",
        downloadRepo = "morpheapp/MicroG-RE",
        releaseTag = "7.1.1",
        remoteName = "microg-7.1.1.apk",
        remoteNameHw = "microg-7.1.1-noicon.apk",
        assetPrefix = "microg",
    );

    /** 是否有可下载的官方直链 */
    val downloadable: Boolean get() = remoteName.isNotEmpty()

    /** 按变体取上游文件名；无直链返回空串 */
    fun remoteFileName(huawei: Boolean = false): String =
        if (huawei) (remoteNameHw ?: remoteName) else remoteName

    /** 上游 release 页面，便于排障时人工核对 */
    val releaseUrl: String
        get() = if (downloadRepo.isEmpty()) "" else "https://github.com/$downloadRepo/releases/tag/$releaseTag"

    companion object {
        /** 空间内谷歌环境是否就绪，看这几个包 */
        val REQUIRED: List<MicroGComponent> = entries.filter { it.required }
        val ALL: List<MicroGComponent> = entries.toList()

        /**
         * 值得在 UI 上作为"可装配槽位"呈现的组件。
         */
        val INSTALLABLE: List<MicroGComponent> = entries.toList()

        /** 有官方直链、可以直接装配的组件 */
        val DOWNLOADABLE: List<MicroGComponent> = entries.filter { it.downloadable }

        fun byPackage(pkg: String): MicroGComponent? = entries.firstOrNull { it.packageName == pkg }
    }
}
