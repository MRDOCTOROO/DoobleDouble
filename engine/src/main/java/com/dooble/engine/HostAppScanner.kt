package com.dooble.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 宿主已安装应用的描述信息。
 *
 * 之所以需要它：绝大多数用户的诉求不是"从网上下 APK"，而是
 * "我已经装了这个应用，把它也放一份到空间里"。
 */
data class HostAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    /** 基础 APK 路径 */
    val apkPath: String,
    /** 拆分包（Android App Bundle 安装后的产物） */
    val splitApks: List<String>,
    val isSystem: Boolean,
    val usesGoogleApis: Boolean,
    val sizeBytes: Long,
    val installedAt: Long,
) {
    /** 是否是拆分包应用。容器需要支持多 APK 安装才能完整还原 */
    val hasSplits: Boolean get() = splitApks.isNotEmpty()

    /** 是否值得提示"该应用依赖谷歌服务" */
    val needsGoogleEnvironment: Boolean get() = usesGoogleApis
}

/**
 * 扫描宿主已安装的应用，并支持把 APK 提取出来装入空间。
 *
 * ## 拆分包问题（重要，不要假装不存在）
 *
 * 2019 年之后上架 Google Play 的应用基本都是 AAB 拆分包：一个基础 APK +
 * 若干个按 ABI / 密度 / 语言拆分的配置 APK。直接复制基础 APK 通常**装不上**
 * 或运行时报资源缺失。
 *
 * 本类如实暴露 [HostAppInfo.hasSplits]，由 UI 提示用户。完整还原需要容器支持
 * 多 APK 安装（Bcore 的 `installApk` 支持传入包名列表），属于后续工作。
 */
class HostAppScanner(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * 上一次扫描时发现的可见性问题（null 表示还没扫过）。
     *
     * UI 拿它决定要不要提示"去打开读取应用列表权限"。
     * 不用 suspend 返回值传递，是因为调用方（Composable）只想在列表为空
     * 或明显偏少时才需要这个信息，多一个返回值会让调用点变啰嗦。
     */
    @Volatile
    var lastVisibility: HostAppRules.VisibilityVerdict? = null
        private set

    suspend fun scan(
        includeSystem: Boolean = false,
        onlyGmsDependent: Boolean = false,
        query: String = "",
    ): List<HostAppInfo> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

        // 过滤/排序规则全部在 HostAppRules 里，这里只负责"把 PackageInfo 翻成 HostAppInfo"
        val primary = packages.mapNotNull { toInfo(it) }

        // 主路径只看到很少的包？大概率是"读取应用列表"被 ROM 拦了。
        // 用 MAIN/LAUNCHER 的 intent 查询补一遍 —— 那条路不受该开关限制
        // （前提是 manifest 里声明了对应的 <queries>）。
        val launcherPackages = launcherPackageNames()
        val verdict = HostAppRules.visibilityVerdict(primary.size, launcherPackages.size)
        lastVisibility = verdict

        val merged = if (verdict.gated) {
            val known = primary.mapTo(HashSet()) { it.packageName }
            val extra = launcherPackages.asSequence()
                .filterNot { it in known }
                .filter { it != context.packageName }
                .mapNotNull { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() }
                .mapNotNull { toInfo(it) }
                .toList()
            HostAppRules.mergeDistinct(primary, extra)
        } else {
            primary
        }

        HostAppRules.filter(
            infos = merged,
            selfPackage = context.packageName,
            includeSystem = includeSystem,
            onlyGmsDependent = onlyGmsDependent,
            query = query,
        )
    }

    /**
     * 有桌面图标的包的集合。
     *
     * 走 intent 解析而不是 `getInstalledPackages`：前者在 Android 11+ 的包可见性
     * 规则下只要 manifest 声明了 `<queries>` 就能看到，是判断"是否被拦截"的基准线。
     */
    private fun launcherPackageNames(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * 直接按包名找宿主上已安装的应用（含系统应用）。
     *
     * 给"把宿主上的 GMS / Play 商店克隆进空间"用：那两个都是系统应用，
     * 而导入列表默认不显示系统应用，靠列表勾选走不通。
     * 找不到返回 null（宿主上没装这个包）。
     */
    suspend fun findHostApp(packageName: String): HostAppInfo? = withContext(Dispatchers.IO) {
        runCatching { pm.getPackageInfo(packageName, PackageManager.GET_META_DATA) }
            .getOrNull()
            ?.let { toInfo(it) }
    }

    /**
     * 宿主上是否装着某个包。比 [findHostApp] 便宜（不构造 HostAppInfo），
     * 适合 UI 每帧判断"要不要显示克隆按钮"。
     */
    fun isInstalledOnHost(packageName: String): Boolean =
        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    private fun toInfo(pkg: android.content.pm.PackageInfo): HostAppInfo? {
        val app: ApplicationInfo = pkg.applicationInfo ?: return null
        val base = app.sourceDir ?: return null
        val splits = app.splitSourceDirs?.toList().orEmpty()

        val label = runCatching { pm.getApplicationLabel(app).toString() }
            .getOrDefault(pkg.packageName)

        val declared = (pkg.requestedPermissions ?: emptyArray()).toSet()
        val usesGoogle = HostAppRules.usesGoogleApis(declared)

        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }

        val size = File(base).length() + splits.sumOf { File(it).length() }

        return HostAppInfo(
            packageName = pkg.packageName,
            label = label,
            versionName = pkg.versionName ?: "0",
            versionCode = versionCode,
            apkPath = base,
            splitApks = splits,
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            usesGoogleApis = usesGoogle,
            sizeBytes = size,
            installedAt = pkg.firstInstallTime,
        )
    }

    /**
     * 把宿主应用的 APK 提取到临时目录，供 [VirtualEngine.installApk] 使用。
     *
     * @param includeSplits 是否连带拆分包一起提取（会打包成一个 .apks 风格的多条目文件，
     *                      需要容器侧支持；当前默认关闭）
     * @return 提取出的 APK 文件；失败返回 null
     */
    suspend fun extract(
        info: HostAppInfo,
        destDir: File,
        includeSplits: Boolean = false,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            val src = File(info.apkPath)
            if (!src.exists() || src.length() == 0L) return@runCatching null

            val multiApk = includeSplits && info.hasSplits
            if (!multiApk) {
                val dest = File(destDir, HostAppRules.archiveName(info.packageName, multiApk = false))
                src.copyTo(dest, overwrite = true)
                return@runCatching dest
            }

            // 多 APK：打成一个 zip，容器侧按 entries 逐个安装
            val dest = File(destDir, HostAppRules.archiveName(info.packageName, multiApk = true))
            val entries = HostAppRules.archiveEntries(info.splitApks.size)
            java.util.zip.ZipOutputStream(dest.outputStream().buffered()).use { zip ->
                fun put(file: File, name: String) {
                    if (!file.exists()) return
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                put(src, entries.first())
                info.splitApks.forEachIndexed { i, path ->
                    put(File(path), entries[i + 1])
                }
            }
            dest
        }.getOrNull()
    }

    /** 提取缓存目录 */
    fun cacheDir(): File = File(context.cacheDir, "host-import").apply { mkdirs() }
}
