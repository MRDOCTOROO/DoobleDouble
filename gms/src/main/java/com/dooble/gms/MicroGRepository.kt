package com.dooble.gms

/**
 * microG 组件的获取来源。
 *
 * 设计取舍：**默认不把 100MB 的 GmsCore 打进宿主 APK**。
 * GBox/GSpace 这类产品也是首启再拉取，原因一样：
 *  - 宿主包体积从 ~110MB 降到 ~5MB，安装快、更新省流量；
 *  - 用户可以选择不启用谷歌环境，那就完全不下载。
 *
 * 同时也支持离线：把 APK 放到 `gms/src/main/assets/microg/<assetName>`，
 * 或用 `tools/fetch-microg.sh` 预先下载，供给层会优先用本地文件。
 *
 * ## 各组件不在同一个仓库
 *
 * GmsCore 与 FakeStore 在 `microg/GmsCore`，而 GSF Proxy 在 `microg/GsfProxy`
 * —— 两个仓库的 release tag 也完全不同（`v0.3.16.252432` vs `v0.1.0`）。
 * 所以仓库与 tag 都由 [MicroGComponent] 自己带，不能在这里写死一个。
 */
object MicroGRepository {

    /** GmsCore 仓库里已验证过的版本。GSF Proxy 的 tag 见 [MicroGComponent.GSF_PROXY] */
    const val RELEASE_TAG = "v0.3.16.252432"

    /** 国内镜像前缀，按顺序尝试；空串表示直连 */
    val MIRROR_PREFIXES = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
    )

    /**
     * @param huawei 是否使用 -hw 变体（华为/荣耀设备上对 GMS 的自定义改动更少，兼容性更好）
     *               上游没有出 -hw 变体的组件会自动回退到标准变体。
     * @return 上游文件名；该组件无官方直链时返回空串
     */
    fun fileName(component: MicroGComponent, huawei: Boolean = false): String =
        component.remoteFileName(huawei)

    /** 返回所有候选下载地址（镜像依次回退）；无官方直链时返回空列表 */
    fun candidates(
        component: MicroGComponent,
        huawei: Boolean = false,
        release: MicroGVersionChecker.Release? = null,
    ): List<String> {
        // 优先用运行时查到的 release：资产文件名里内嵌版本号，硬编码会随上游发版失效。
        // 但必须先核对仓库 —— 把 GmsCore 的资产列表拿去匹配 GSF Proxy 的前缀，
        // 匹配不到只是运气好，匹配到就是装错包。
        if (release != null && release.repo == component.downloadRepo) {
            release.assetFor(component.packageName, huawei)?.let { asset ->
                return MIRROR_PREFIXES.map { prefix ->
                    if (prefix.isEmpty()) asset.url else prefix + asset.url
                }
            }
        }

        // 回退：已知可用的固定版本
        val name = component.remoteFileName(huawei)
        if (name.isEmpty() || component.downloadRepo.isEmpty()) return emptyList()
        val direct =
            "https://github.com/${component.downloadRepo}/releases/download/${component.releaseTag}/$name"
        return MIRROR_PREFIXES.map { prefix ->
            if (prefix.isEmpty()) direct else prefix + direct
        }
    }

    /** 组件是否有官方直链 */
    fun isDownloadable(component: MicroGComponent): Boolean = component.downloadable

    /**
     * 该组件在上游 release 里的 SHA-256 摘要（hex，不含 `sha256:` 前缀）。
     *
     * 与 [candidates] 用**同一套仓库核对逻辑**（GmsCore 的资产列表拿去匹配
     * GSF Proxy 前缀，匹配到就是装错包）。release 为 null（走了内置版本的
     * 回退路径）或该资产没有 digest 时返回 null——此时下载方退化为
     * 长度比对 + ZIP 结构校验，仍不能跳过完整性检查。
     */
    fun expectedDigest(
        component: MicroGComponent,
        huawei: Boolean = false,
        release: MicroGVersionChecker.Release? = null,
    ): String? {
        if (release != null && release.repo == component.downloadRepo) {
            return release.assetFor(component.packageName, huawei)?.digestSha256
        }
        return null
    }
}
