package com.dooble.gms

import android.content.Context
import com.dooble.engine.VirtualEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class ProvisionPhase { RESOLVE, DOWNLOAD, VERIFY, INSTALL, CONFIGURE, DONE, ERROR }

data class ProvisionStep(
    val component: MicroGComponent?,
    val index: Int,
    val total: Int,
    val phase: ProvisionPhase,
    val message: String,
    val progress: Float = 0f,
)

data class ProvisionReport(
    val spaceId: String,
    val success: Boolean,
    val installed: List<String>,
    val skipped: List<String>,
    val errors: List<String>,
)

/**
 * 谷歌环境供给器：把一个空间从"空容器"变成"有完整谷歌环境"。
 *
 * 完整流程（顺序不能变）：
 *  1. 校验引擎能力：必须有容器级签名伪造，否则 microG 装了也会被应用拒绝；
 *  2. 准备 microG APK（本地 assets → 本地缓存 → 远端下载，三级回退）；
 *  3. 以**系统应用**身份把 GmsCore / FakeStore 装进空间的虚拟 /system 视图；
 *  4. 写入签名伪造白名单（让容器内的 PMS 对 com.google.android.gms 返回 Google 官方签名）；
 *  5. 写入设备指纹伪装，避免 GMS 侧把设备判为未认证；
 *  6. 预热：首次拉起 GmsCore 完成自注册，随后拉起账号登录页。
 *
 * 第 4、5 步是"能不能登录谷歌账号"的分水岭。缺任何一步，
 * 表现都是"应用提示 Google Play 服务不可用"或"登录时白屏/报错 12500"。
 */
class GmsProvisioner(
    private val context: Context,
    private val engine: VirtualEngine,
    /** 缓存的 microG APK 存放目录，跨空间共享，避免每个空间各下一份 */
    private val cacheDir: File = File(context.filesDir, "dooble/gms-cache"),
) {

    private val spoof = GmsSignatureSpoof(context, engine)
    private val deviceProfile = DeviceProfileSpoofer(context, engine)

    /** 空间是否已经具备可用的谷歌环境 */
    fun isProvisioned(spaceId: String): Boolean {
        val installed = engine.listApps(spaceId).map { it.packageName }.toSet()
        return MicroGComponent.REQUIRED.all { it.packageName in installed }
    }

    suspend fun provision(
        spaceId: String,
        huaweiVariant: Boolean = false,
        includeOptional: Boolean = false,
        onStep: (ProvisionStep) -> Unit = {},
    ): ProvisionReport = withContext(Dispatchers.IO) {
        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // 已废弃的组件不进装配流程：上游根本没有可安装的文件，
        // 拉进来只会得到一条"跳过"，白白让进度条多走一格。
        val components = if (includeOptional) MicroGComponent.INSTALLABLE else MicroGComponent.REQUIRED
        val total = components.size + 2 // +2：签名伪造配置、设备指纹配置

        // 0. 能力前置校验
        if (!engine.capability.supportsSignatureSpoof) {
            onStep(
                ProvisionStep(
                    null, 0, total, ProvisionPhase.ERROR,
                    "当前引擎（${engine.name}）不支持容器级签名伪造，microG 无法通过应用的签名校验。",
                )
            )
            return@withContext ProvisionReport(
                spaceId, false, emptyList(), emptyList(),
                listOf("引擎 ${engine.name} 缺少 supportsSignatureSpoof 能力，请接入 Bcore 等支持 PMS hook 的引擎"),
            )
        }

        // 0.5 解析上游最新 release（失败不阻断，回退到内置的已知可用版本）
        val release = when (val r = MicroGVersionChecker.latest()) {
            is MicroGVersionChecker.Result.Ok -> {
                onStep(
                    ProvisionStep(
                        null, 0, total, ProvisionPhase.RESOLVE,
                        "已解析 microG 最新版本 ${r.release.tag}（共 ${r.release.assets.size} 个资产）",
                    )
                )
                r.release
            }

            is MicroGVersionChecker.Result.Failed -> {
                onStep(
                    ProvisionStep(
                        null, 0, total, ProvisionPhase.RESOLVE,
                        "无法查询最新版本（${r.reason}），回退到内置版本 ${MicroGRepository.RELEASE_TAG}",
                    )
                )
                null
            }
        }

        components.forEachIndexed { i, component ->
            val idx = i + 1
            try {
                onStep(ProvisionStep(component, idx, total, ProvisionPhase.RESOLVE, "准备 ${component.packageName}"))
                val apk = resolveApk(component, huaweiVariant, release) { done, len ->
                    val p = if (len > 0) done.toFloat() / len else 0f
                    onStep(
                        ProvisionStep(
                            component, idx, total, ProvisionPhase.DOWNLOAD,
                            "下载 ${component.packageName} ${human(done)} / ${human(len)}", p,
                        )
                    )
                }
                if (apk == null) {
                    // 把"为什么没有直链"如实说出来。只写"需手动导入"会让用户
                    // 去找一个上游根本不存在的文件（MapsV1 就是这种）。
                    val why = component.manualOnlyReason
                        ?: "无官方直链，需手动导入 APK"
                    val msg = "跳过 ${component.packageName}：$why"
                    if (component.required) errors += msg else skipped += component.packageName
                    onStep(ProvisionStep(component, idx, total, ProvisionPhase.ERROR, msg))
                    return@forEachIndexed
                }

                onStep(ProvisionStep(component, idx, total, ProvisionPhase.VERIFY, "校验 ${apk.name}"))
                if (!looksLikeApk(apk)) {
                    apk.delete()
                    val msg = "${component.packageName} 下载内容不是合法 APK"
                    errors += msg
                    onStep(ProvisionStep(component, idx, total, ProvisionPhase.ERROR, msg))
                    return@forEachIndexed
                }

                onStep(ProvisionStep(component, idx, total, ProvisionPhase.INSTALL, "装入空间：${component.packageName}"))
                engine.installApk(spaceId, apk, system = component.system)
                installed += component.packageName
            } catch (t: Throwable) {
                errors += "${component.packageName}: ${t.message}"
                onStep(ProvisionStep(component, idx, total, ProvisionPhase.ERROR, t.message ?: "未知错误"))
            }
        }

        // 签名伪造：按空间当前模式下发策略。
        //
        // 默认是 OFF，而且这**不是**降级——microG + Aurora Store 的主链路
        // （登录谷歌账号、下载安装应用、多开）本来就不依赖签名伪造。它只影响
        // 那些主动校验 GMS 签名的第三方应用。所以这里失败不阻断装配，只记 skipped。
        onStep(ProvisionStep(null, total - 1, total, ProvisionPhase.CONFIGURE, "下发签名伪造策略"))
        runCatching { spoof.apply(spaceId) }
            .onSuccess { result ->
                if (result.error != null) {
                    skipped += "签名伪造（${result.mode.label}）：${result.error}"
                } else {
                    onStep(
                        ProvisionStep(
                            null, total - 1, total, ProvisionPhase.CONFIGURE,
                            if (result.mode == SpoofMode.OFF)
                                "签名伪造：已关闭（不影响账号登录与应用下载）"
                            else
                                "签名伪造：${result.mode.label}，覆盖 ${result.targetCount} 个包",
                        )
                    )
                }
            }
            .onFailure { errors += "签名伪造配置失败: ${it.message}" }

        // 设备指纹：GMS 会读取 Build.* 判断设备是否认证
        onStep(ProvisionStep(null, total, total, ProvisionPhase.CONFIGURE, "应用设备指纹伪装"))
        val cfg = engine.space(spaceId)?.config
        if (cfg != null) {
            runCatching { deviceProfile.apply(spaceId, cfg.deviceSpoof) }
                .onFailure { errors += "设备指纹配置失败: ${it.message}" }
        }

        val success = MicroGComponent.REQUIRED.all { it.packageName in installed }
        onStep(
            ProvisionStep(
                null, total, total,
                if (success) ProvisionPhase.DONE else ProvisionPhase.ERROR,
                if (success) "谷歌环境就绪" else "谷歌环境不完整：${errors.joinToString("; ")}",
            )
        )

        ProvisionReport(spaceId, success, installed, skipped, errors)
    }

    // ------------------------------------------------------------------ APK 解析

    /**
     * 三级回退拿到 APK：
     *  1. `assets/microg/<assetName>`（离线内置）
     *  2. `cacheDir/<assetName>`（上次下载的）
     *  3. 远端下载（多镜像回退）
     */
    suspend fun resolveApk(
        component: MicroGComponent,
        huawei: Boolean,
        release: MicroGVersionChecker.Release? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? {
        cacheDir.mkdirs()

        // 1) 内置资产
        val assetPath = "microg/${component.assetName}"
        val fromAsset = runCatching {
            context.assets.open(assetPath).use { input ->
                val out = File(cacheDir, component.assetName)
                FileOutputStream(out).use { input.copyTo(it) }
                out
            }
        }.getOrNull()
        if (fromAsset != null && fromAsset.length() > 0) return fromAsset

        // 2) 本地缓存（含用户手动导入的）
        val cached = File(cacheDir, component.assetName)
        if (cached.exists() && cached.length() > 0 && looksLikeApk(cached)) return cached

        // 3) 远端
        val urls = MicroGRepository.candidates(component, huawei, release)
        if (urls.isEmpty()) return null
        for (url in urls) {
            val ok = runCatching { download(url, cached, onProgress) }.getOrDefault(false)
            if (ok && looksLikeApk(cached)) return cached
        }
        return null
    }

    /**
     * 手动导入组件 APK。
     *
     * 用于那些没有官方直链的可选组件（GsfProxy / MapsV1），
     * 以及用户想锁定某个特定版本、不走网络下载的场景。
     * 导入后会被 [resolveApk] 的第 2 级（本地缓存）命中。
     */
    suspend fun importComponentApk(component: MicroGComponent, source: File): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!looksLikeApk(source)) return@runCatching null
                cacheDir.mkdirs()
                val dest = File(cacheDir, component.assetName)
                source.copyTo(dest, overwrite = true)
                dest
            }.getOrNull()
        }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DoobleDouble/1.0 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) return false
            val len = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 1_000_000) {
                            last = done
                            onProgress(done, len)
                        }
                    }
                    onProgress(done, len)
                }
            }
            tmp.renameTo(dest)
            return dest.exists()
        } finally {
            conn.disconnect()
            if (tmp.exists() && !dest.exists()) tmp.delete()
        }
    }

    /** 极简 APK 合法性校验：ZIP 魔数 PK\x03\x04 + 内含 AndroidManifest.xml */
    private fun looksLikeApk(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null
            }
        }.getOrDefault(false)
    }

    private fun human(bytes: Long): String = when {
        bytes <= 0 -> "--"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1048576.0)
    }

    companion object {
        /** 清理下载缓存，释放空间 */
        fun clearCache(cacheDir: File) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}
