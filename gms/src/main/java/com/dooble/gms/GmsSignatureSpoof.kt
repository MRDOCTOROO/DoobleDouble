package com.dooble.gms

import android.content.Context
import com.dooble.engine.VirtualEngine
import com.dooble.engine.internal.BcoreStorageLayout
import com.dooble.engine.internal.Base64Codec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 签名伪造的工作模式。
 *
 * 这个三态划分是被一次现实问题逼出来的：**Google 官方平台证书不可能伪造**。
 * microG 的校验逻辑是「对证书原始 DER 字节做 SHA-1，与硬编码常量比对」，
 * 而 SHA-1 无法反推。所以「随便拿张证书糊弄过去」这条路根本不存在。
 *
 * 那为什么还要 [TEST]？因为「伪造没生效」和「伪造生效了但证书不对」是**两个
 * 完全不同的问题**，排障时必须能分开：
 *
 *  - [TEST] 用本地自签证书，可以验证**容器 hook 链路本身**是否打通
 *    （策略下发 → 容器 PMS 拦截 → 返回值被替换）。链路通了，换成真证书就成。
 *  - [OFFICIAL] 才是真正能骗过 microG 的模式，需要使用者自备证书。
 *
 * [OFF] 是**默认值**，也是本地测试的推荐配置——因为 microG + Aurora Store 的
 * 主链路（登录谷歌账号、下载安装应用、多开）**本来就不依赖签名伪造**。
 * 它只影响那些主动校验 GMS 签名的第三方应用。
 */
enum class SpoofMode {
    /** 不伪造。容器行为与未打补丁时完全一致 */
    OFF,

    /** 本地自签测试证书。用于验证 hook 链路，**骗不过 microG** */
    TEST,

    /** 真实 Google 平台证书。需要使用者自备，不随项目分发 */
    OFFICIAL;

    val label: String
        get() = when (this) {
            OFF -> "关闭"
            TEST -> "本地测试证书"
            OFFICIAL -> "Google 官方证书"
        }

    val canSatisfyMicroG: Boolean get() = this == OFFICIAL
}

/**
 * 容器级签名伪造（Container-level Signature Spoofing）。
 *
 * ## 为什么必须有它
 *
 * 所有依赖 Google Play 服务的应用，在连接前都会做一次签名校验：
 * 它向系统查询 `com.google.android.gms` 的签名，比对是否为 Google 官方签名。
 * microG 用的是自己的密钥，直接装上去必然校验失败，表现就是
 * 「Google Play 服务不可用」或登录时报 `12500`。
 *
 * ## 传统做法 vs 本方案
 *
 * | 方案 | 前提 | 缺点 |
 * |---|---|---|
 * | 改 ROM（LineageOS 的 signature spoofing patch） | 刷机 | 门槛极高 |
 * | Magisk 模块（microG Installer Revived） | Root | 门槛高、有封号风险 |
 * | **容器内 PMS hook（本方案）** | 无 | 需要虚拟化引擎支持 |
 *
 * 关键洞察：**虚拟化引擎本来就已经 hook 了 PackageManagerService**（不然它没法
 * 让未安装的 APK "看起来已安装"）。我们只需要在同一个 hook 点上多挂一条规则。
 *
 * ## 谁在什么时候读这份策略
 *
 * 本类只负责**把策略写成一个 JSON 文件**，不参与查询。真正读它的是跑在
 * 容器服务进程里的 `PackageSignatureSpoofer.Hook` 实现——因为签名查询发生在
 * 那边，跨进程同步一份内存状态没有必要，文件是最简单也最不会出错的一致性载体。
 *
 * 落盘位置：`<filesDir>/dooble/virtual/<spaceId>/.dooble/spoof_policy.json`。
 * 路径由 `BcoreStorageLayout.spoofPolicyFile` 统一给出——读取方（容器服务进程里的
 * `BcoreSignatureSpoofer`）用的是**同一个函数**。别自己另拼一份：曾经两端各拼一次、
 * 读方多了一段 `data/`，文件永远找不到，签名伪造整条链路静默失效。
 */
class GmsSignatureSpoof(
    private val context: Context,
    private val engine: VirtualEngine,
) {

    @Serializable
    data class SpoofPolicy(
        /** [SpoofMode] 的 name。老版本策略文件里没有这个字段，反序列化时按 OFF 处理 */
        val mode: String = SpoofMode.OFF.name,
        /** 需要被伪造签名的包。OFF 模式为空 */
        val targets: List<SpoofTarget> = emptyList(),
        val enabled: Boolean = false,
        val version: Int = POLICY_VERSION,
    )

    @Serializable
    data class SpoofTarget(
        val packageName: String,
        /** 对外呈现的签名 SHA-1（由 [derBase64] 现场算出，不是硬编码） */
        val sha1: String,
        /** 证书原始 DER 字节的 Base64。容器侧 `new Signature(bytes)` 直接用它 */
        val derBase64: String,
        /** 该包由 microG 提供，真实 APK 是 microG 的签名 */
        val provider: String = "microG",
    )

    /** 证书的来源与指纹，用于在 UI 上如实展示"你现在用的是哪张证书" */
    data class CertInfo(
        val source: String,
        val sha1: String,
        val sha256: String,
        val subjectHint: String,
    )

    /** [apply] 的结果 */
    data class ApplyResult(
        val mode: SpoofMode,
        val policyFile: File,
        val targetCount: Int,
        val cert: CertInfo?,
        val error: String? = null,
    ) {
        /** 关闭模式天然算成功；其余模式要求证书确实加载到了 */
        val ok: Boolean get() = error == null
    }

    /** 端到端校验结果：策略期望值 vs 容器实际返回值 */
    data class VerifyResult(
        val expectedSha1: String?,
        val actualSha1: String?,
    ) {
        /** 容器返回的签名与策略一致 → hook 链路是通的 */
        val chainOk: Boolean get() = expectedSha1 != null && expectedSha1 == actualSha1

        /** 且这个签名就是 Google 官方的 → microG 才会认 */
        val isOfficial: Boolean get() = actualSha1 == GMS_SHA1
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 按指定模式把策略写入空间私有目录。
     *
     * 注意 [SpoofMode.OFF] 也会写文件（内容为 `enabled=false`），而不是删掉它——
     * 这样自检能区分「明确关闭」和「从未配置过」，这两种状态的排障方向完全不同。
     */
    fun apply(spaceId: String, mode: SpoofMode = currentMode(spaceId)): ApplyResult {
        val file = policyFile(spaceId)
        file.parentFile?.mkdirs()

        if (mode == SpoofMode.OFF) {
            val policy = SpoofPolicy(mode = mode.name, targets = emptyList(), enabled = false)
            file.writeText(JSON.encodeToString(SpoofPolicy.serializer(), policy))
            return ApplyResult(mode, file, 0, null)
        }

        val bundle = loadCertificate(mode)
            ?: run {
                val policy = SpoofPolicy(mode = mode.name, targets = emptyList(), enabled = false)
                file.writeText(JSON.encodeToString(SpoofPolicy.serializer(), policy))
                return ApplyResult(
                    mode, file, 0, null,
                    error = "${mode.label}未就绪：找不到证书 ${assetNameOf(mode)}。${hintFor(mode)}",
                )
            }

        val sha1 = digest(bundle.der, "SHA-1")
        val targets = SPOOF_PACKAGES.map { pkg ->
            SpoofTarget(
                packageName = pkg,
                sha1 = sha1,
                // 用引擎自带的 Base64Codec 而不是 android.util.Base64：
                // 读写两端必须同一套编码，而引擎侧那份还得能在 JVM 单测里跑
                derBase64 = Base64Codec.encode(bundle.der),
            )
        }
        val policy = SpoofPolicy(mode = mode.name, targets = targets, enabled = true)
        file.writeText(JSON.encodeToString(SpoofPolicy.serializer(), policy))

        return ApplyResult(mode, file, targets.size, bundle.info)
    }

    fun policyOf(spaceId: String): SpoofPolicy? = runCatching {
        val f = policyFile(spaceId)
        if (!f.exists()) null else JSON.decodeFromString<SpoofPolicy>(f.readText())
    }.getOrNull()

    fun currentMode(spaceId: String): SpoofMode {
        val raw = policyOf(spaceId)?.mode ?: return SpoofMode.OFF
        return SpoofMode.entries.firstOrNull { it.name == raw } ?: SpoofMode.OFF
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 校验某个包在当前空间内对外呈现的签名是否为策略期望的那个。
     *
     * 走引擎接口读容器 PMS **真正返回给虚拟应用**的值，因此这是一次端到端验证——
     * 伪造没生效会如实返回 false，而不是拿策略文件自己跟自己对账。
     */
    fun verify(spaceId: String, packageName: String): Boolean =
        verifyDetailed(spaceId, packageName).chainOk

    fun verifyDetailed(spaceId: String, packageName: String): VerifyResult {
        val expected = policyOf(spaceId)?.targets?.firstOrNull { it.packageName == packageName }?.sha1
        val actual = runCatching { engine.virtualSignatureSha1(spaceId, packageName) }.getOrNull()
        return VerifyResult(expected, actual)
    }

    /** 空间当前生效的证书信息（OFF 或证书缺失时为 null） */
    fun certInfoOf(spaceId: String): CertInfo? {
        val mode = currentMode(spaceId)
        if (mode == SpoofMode.OFF) return null
        return loadCertificate(mode)?.info
    }

    // ------------------------------------------------------------------ 证书来源

    private class CertBundle(val der: ByteArray, val info: CertInfo)

    /**
     * 证书查找顺序：
     *  1. `<filesDir>/dooble/signature/` —— 内部私有目录。
     *  2. `<externalFilesDir>/dooble/signature/` —— 外部私有目录
     *     （`/sdcard/Android/data/<pkg>/files/...`）。**本地测试主要走这条**：
     *     `adb push` 即可投递，debug 包不需要 root，也不会把证书写进版本库。
     *  3. APK 的 `assets/signature/` —— 随包分发（仅适用于自签测试证书；
     *     官方证书按合规红线不进版本库）。
     */
    private fun loadCertificate(mode: SpoofMode): CertBundle? {
        val name = assetNameOf(mode)

        val runtimeCandidates = listOfNotNull(
            File(context.filesDir, "dooble/signature/$name"),
            context.getExternalFilesDir(null)?.let { File(it, "dooble/signature/$name") },
        )
        runtimeCandidates.firstOrNull { it.isFile && it.length() > 0 }?.let { file ->
            return runCatching {
                val der = file.readBytes()
                CertBundle(der, describe(der, "运行时投放 · ${file.absolutePath}"))
            }.getOrNull()
        }

        return runCatching {
            context.assets.open("signature/$name").use { it.readBytes() }
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { CertBundle(it, describe(it, "APK 内置 · assets/signature/$name")) }
    }

    private fun describe(der: ByteArray, source: String): CertInfo = CertInfo(
        source = source,
        sha1 = digest(der, "SHA-1"),
        sha256 = digest(der, "SHA-256"),
        subjectHint = subjectHintOf(der),
    )

    /**
     * 从 DER 里尽力解析出主体名，纯粹为了让人眼能核对"这到底是不是 Google 的证书"。
     * 解析失败不影响任何功能——这只是展示信息。
     */
    private fun subjectHintOf(der: ByteArray): String = runCatching {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(der.inputStream())
        (cert as java.security.cert.X509Certificate).subjectX500Principal.name
    }.getOrDefault("（无法解析证书主体）")

    // ------------------------------------------------------------------ 工具

    private fun digest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    /** 策略文件位置与容器读取方（BcoreSignatureSpoofer）严格同源，见 BcoreStorageLayout.spoofPolicyFile */
    private fun policyFile(spaceId: String): File =
        BcoreStorageLayout.spoofPolicyFile(context.filesDir, spaceId)

    private fun assetNameOf(mode: SpoofMode): String = when (mode) {
        SpoofMode.TEST -> CERT_ASSET_TEST
        else -> CERT_ASSET_OFFICIAL
    }

    private fun hintFor(mode: SpoofMode): String = when (mode) {
        SpoofMode.TEST -> "已内置自签证书，可直接使用。"
        SpoofMode.OFFICIAL -> "从你合法持有的 GMS APK 中导出证书，放到 ${'$'}filesDir/dooble/signature/$CERT_ASSET_OFFICIAL。"
        SpoofMode.OFF -> ""
    }

    companion object {
        const val POLICY_VERSION = 4

        /**
         * Google 官方签名 SHA-1。这些值是公开且固定的，microG 的
         * `Constants.GMS_PACKAGE_SIGNATURE_SHA1` 用的也是同一个。
         *
         * 注意：**不要**把它当成"填进去就能伪造"的魔法值。容器返回的必须是
         * 真实的证书 DER 字节，这个常量只用来做**比对**。
         */
        const val GMS_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788"

        /** 需要伪造签名的包。microG 栈的核心 */
        val SPOOF_PACKAGES = listOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
        )

        /**
         * 应用侧可能还会校验这些"需要被伪造"的第三方包（microG 的
         * `signature_want_fake` 列表里的常客）。按需扩展。
         */
        val OPTIONAL_PACKAGES = listOf(
            "com.spotify.music",
            "com.airbnb.android",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
        )

        /** 本地自签测试证书的文件名（不含目录） */
        const val CERT_ASSET_TEST = "test_platform.der"

        /** 真实 Google 平台证书的文件名（不含目录） */
        const val CERT_ASSET_OFFICIAL = "google_platform.der"

        private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
