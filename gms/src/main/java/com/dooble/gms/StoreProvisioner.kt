package com.dooble.gms

import android.content.Context
import android.util.Log
import com.dooble.engine.VirtualApp
import com.dooble.engine.VirtualEngine
import com.dooble.engine.internal.BcoreStorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 商店供给的阶段 */
enum class StorePhase { CHECK, DOWNLOAD, INSTALL, DONE, ERROR }

data class StoreStep(
    val phase: StorePhase,
    val message: String,
    val progress: Float = 0f,
)

data class StoreReport(
    val spaceId: String,
    val success: Boolean,
    val installed: VirtualApp? = null,
    val message: String = "",
)

/**
 * 空间内应用商店的供给。
 *
 * ## 为什么是 Aurora Store 而不是 Play Store
 *
 * Play Store 与 GMS 是 Google 的专有软件，**不可再分发**；而且它要求设备通过
 * Play 保护机制认证（Play Integrity），在容器里跑必然要绕过这道校验——
 * 那是一条既不合法、又要和 Google 打持久战的路线（市面上的方案正是靠伪造机型
 * 绕过机型封禁，Google 一封就集体失效）。
 *
 * Aurora Store 是同一件事的**开源实现**（GPLv3，`com.aurora.store`）：
 *
 * | 能力 | Play Store | Aurora Store |
 * |---|---|---|
 * | 登录 Google 账号 | ✅ | ✅（也支持匿名会话） |
 * | 从 Google 服务器下载应用 | ✅ | ✅（走同一套 Play API） |
 * | 搜索 / 更新 / 已购列表 | ✅ | ✅ |
 * | 可再分发 | ❌ 专有 | ✅ GPLv3 |
 * | 需要设备认证 | ✅ 强制 | ❌ 不需要 |
 * | 与 microG 配合 | 冲突 | ✅ 官方明确支持 |
 *
 * 结论：**用户要的是"能在空间里登录谷歌账号、下载安装应用"，Aurora Store
 * 完整满足，且不需要踩任何合规红线。** 本类因此只供给 Aurora Store。
 *
 * ## 缓存策略
 *
 * 与 microG 一致：APK 缓存在 `<filesDir>/dooble/store-cache/`，跨空间共享。
 * 装十个空间也只下载一次。
 */
class StoreProvisioner(
    private val context: Context,
    private val engine: VirtualEngine,
) {

    private val cacheDir: File get() = File(context.filesDir, "dooble/store-cache").apply { mkdirs() }

    suspend fun provision(
        spaceId: String,
        onStep: (StoreStep) -> Unit = {},
    ): StoreReport = withContext(Dispatchers.IO) {
        onStep(StoreStep(StorePhase.CHECK, "检查空间内是否已装商店", 0.05f))

        val already = engine.listApps(spaceId).firstOrNull { it.packageName in STORE_PACKAGES }
        if (already != null) {
            // 空间里已经有商店：也顺势把安装方式钉到「原生安装程序」。
            // 见 configureAuroraInstaller 的注释。
            configureAuroraInstaller(spaceId)
            onStep(StoreStep(StorePhase.DONE, "商店已存在：${already.label}", 1f))
            return@withContext StoreReport(spaceId, true, already, "商店已在空间内")
        }

        val apk = resolveApk(onStep)
            ?: return@withContext StoreReport(
                spaceId = spaceId,
                success = false,
                message = "商店 APK 下载失败。可手动下载 $PRIMARY_URL 后用「从文件装入 APK」安装。",
            )

        onStep(StoreStep(StorePhase.INSTALL, "正在装入空间…", 0.9f))
        val app = runCatching { engine.installApk(spaceId, apk) }
            .getOrElse { t ->
                return@withContext StoreReport(
                    spaceId = spaceId,
                    success = false,
                    message = "装入失败：${t.message}",
                )
            }

        // 装入成功后把 Aurora 的安装方式钉到「原生安装程序」。
        // 见 configureAuroraInstaller 的注释。
        configureAuroraInstaller(spaceId)

        onStep(StoreStep(StorePhase.DONE, "已装入：${app.label}", 1f))
        StoreReport(spaceId, true, app, "已装入 ${app.label}")
    }

    /** 取本地缓存，没有就下载。多镜像回退，与 microG 同一套思路 */
    private suspend fun resolveApk(onStep: (StoreStep) -> Unit): File? {
        val cached = File(cacheDir, CACHE_NAME)
        // 缓存命中必须过 ZIP 结构检查：之前只看大小，弱网下截断的坏 APK
        // 一旦进缓存就**永久命中**，此后每次装配都在解析报错。
        if (cached.exists() && cached.length() > MIN_APK_BYTES && looksLikeApk(cached)) {
            onStep(StoreStep(StorePhase.CHECK, "使用本地缓存的商店 APK", 0.3f))
            return cached
        }

        for ((i, url) in URLS.withIndex()) {
            val label = if (i == 0) "官方源" else "镜像 $i"
            onStep(StoreStep(StorePhase.DOWNLOAD, "下载商店（$label）…", 0.4f))
            if (download(url, cached, onStep)) return cached
        }
        return null
    }

    /** 下载交给 [ApkHttp]：断点续传、同源重试退避、长度比对，与 microG 同一套实现 */
    private fun download(url: String, dest: File, onStep: (StoreStep) -> Unit): Boolean =
        ApkHttp.download(
            url = url,
            dest = dest,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 60_000,
            onProgress = { done, total ->
                if (total > 0) {
                    val p = 0.4f + 0.45f * (done.toFloat() / total)
                    onStep(StoreStep(StorePhase.DOWNLOAD, "下载中 ${human(done)} / ${human(total)}", p))
                }
            },
        )

    /**
     * 把 Aurora Store 的安装方式钉到「原生安装程序」（Installer.NATIVE，ordinal=1）。
     *
     * ## 为什么必须这么做
     *
     * MuMu 的 libhoudini 无法跨桥执行 `fsync()`。Aurora 默认的会话式安装
     * （PackageInstaller.Session）在写入分片时会抛
     * `NoSuchElementException: Collection is empty`——根因是容器代理的
     * `openWrite` 返回普通文件 PFD，框架 FileBridgeOutputStream 把命令头写进
     * 会话文件（APK 头被污染），fsync 又没有对端回执；即便容器侧已修复
     * FileBridge 协议，libhoudini 仍会在 Aurora 关闭源文件时再次触发跨桥
     * fsync 失败。因此 MuMu 上会话式安装不可用。
     *
     * 切到「原生安装程序」后 Aurora 走 `ACTION_INSTALL_PACKAGE` 意图式安装，
     * 配合容器补丁 0010（`isRequestInstall` 识别该 action）可稳定装入当前空间，
     * 不依赖 libhoudini 的 fsync。这是 AGENTS.md 里实测可用路径的程序化落地。
     *
     * Aurora 的 `Preferences.getPrefs` 在 vanilla flavor 下用
     * `PreferenceManager.getDefaultSharedPreferences`，即
     * `com.aurora.store_preferences.xml`；`PREFERENCE_INSTALLER_ID` 取
     * `Installer` 枚举的 ordinal：SESSION=0、NATIVE=1。
     */
    private fun configureAuroraInstaller(spaceId: String) {
        val userId = engine.space(spaceId)?.bcoreUserId ?: return
        if (userId <= 0) return
        val prefsDir = File(
            File(BcoreStorageLayout.userDir(context.dataDir, userId), PRIMARY_PACKAGE),
            "shared_prefs",
        )
        val prefsFile = File(prefsDir, "${PRIMARY_PACKAGE}_preferences.xml")
        val key = "PREFERENCE_INSTALLER_ID"
        val desired = "<int name=\"$key\" value=\"1\" />"
        try {
            prefsDir.mkdirs()
            val content = if (prefsFile.exists()) {
                prefsFile.readText()
            } else {
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
            }
            val updated = when {
                !content.contains(key) -> content.replace("</map>", "    $desired\n</map>")
                content.contains(desired) -> content
                else -> content.replace(
                    Regex("<int name=\"$key\" value=\"\\d+\" />"),
                    desired,
                )
            }
            if (updated != content) {
                prefsFile.writeText(updated)
                Log.i(TAG, "Aurora installer set to NATIVE for space $spaceId")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to configure Aurora native installer: $t")
        }
    }

    /** 清掉缓存（换版本或下载损坏时用） */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun human(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1048576.0)
    }

    companion object {
        private const val TAG = "StoreProvisioner"

        /**
         * 当前供给的商店包名。UI 用它判断空间里有没有商店。
         *
         * 引用 [com.dooble.engine.StorePackages] 而不是再写一遍字面量：
         * 引擎在解析 `market://` 链接时要拿同一份名单做路由，
         * 两处各写一遍迟早会脱节（表现是"装上了商店，点链接却没反应"）。
         */
        const val PRIMARY_PACKAGE = com.dooble.engine.StorePackages.AURORA

        /** Aurora Store 的包名。将来若要支持其它商店，往这里加 */
        val STORE_PACKAGES = setOf(PRIMARY_PACKAGE)

        const val PRIMARY_URL = "https://f-droid.org/repo/com.aurora.store_76.apk"

        private const val CACHE_NAME = "aurora-store.apk"

        /** 小于这个大小基本可以断定下载被劫持或截断了 */
        private const val MIN_APK_BYTES = 1_000_000L

        /**
         * F-Droid 主源 + 镜像。
         *
         * 都指向 F-Droid 官方仓库而不是 APK 聚合站：F-Droid 的包由它们自己
         * 从源码构建并签名，有构建日志可查，比来路不明的 APK 站点可靠得多。
         */
        val URLS = listOf(
            PRIMARY_URL,
            "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.aurora.store_76.apk",
            "https://mirror.cyberbits.eu/fdroid/repo/com.aurora.store_76.apk",
        )
    }
}
