package com.dooble.engine.internal

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.dooble.engine.EngineCapability
import com.dooble.engine.LaunchResult
import com.dooble.engine.RunningProcess
import com.dooble.engine.SpaceConfig
import com.dooble.engine.SpaceRuntime
import com.dooble.engine.SpaceState
import com.dooble.engine.VirtualApp
import com.dooble.engine.VirtualEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 降级沙箱引擎（Fallback Sandbox Engine）。
 *
 * 定位：**不是**真正的应用虚拟化实现，而是引擎抽象层的一个"诚实降级"。
 * 它把空间的目录结构、元数据、APK 装载、谷歌环境供给等**真实落盘**，
 * 使得：
 *  1. 在没有接入 native 容器引擎的设备/CI 上，整套 UI 与业务流程可以跑通；
 *  2. GMS 供给层的文件编排逻辑可以独立验证；
 *  3. 用户可以先用"系统已安装应用"模式体验空间管理。
 *
 * 它**不能**做到的事（也是接入 Bcore 后由真引擎补齐的部分）：
 *  - 运行未安装到系统的 APK（需要 AMS/PMS hook）；
 *  - 为容器内的应用伪造包签名（需要 PMS hook）；
 *  - 同一应用多开（需要 uid/进程视图隔离）。
 *
 * 因此 [capability] 中与虚拟化强相关的能力全部声明为 false，UI 会据此
 * 明确提示用户"当前为沙箱模式"。
 */
class SandboxEngine(context: Context) : VirtualEngine {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "dooble")
    private val virtualRoot = File(root, "virtual")
    private lateinit var store: SpaceStore

    @Volatile
    private var ready = false

    override val name: String = NAME

    override val capability = EngineCapability(
        supportsGms = false,
        supportsSignatureSpoof = false,
        supportsXposed = false,
        supportsMultiInstance = false,
        nativeHook = false,
        supports64Bit = true,
    )

    override suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        virtualRoot.mkdirs()
        store = SpaceStore(root)
        ready = true
    }

    override fun isReady(): Boolean = ready

    override suspend fun createSpace(config: SpaceConfig): SpaceRuntime = withContext(Dispatchers.IO) {
        val dir = virtualRoot.resolve(config.id)
        listOf("data", "user", "apk", "lib", "cache", "sdcard").forEach { dir.resolve(it).mkdirs() }
        store.put(SpaceStore.Record(config = config))
        runtime(config.id) ?: error("空间创建失败: ${config.id}")
    }

    override suspend fun deleteSpace(spaceId: String) {
        withContext(Dispatchers.IO) {
            store.remove(spaceId)
            virtualRoot.resolve(spaceId).deleteRecursively()
            Unit
        }
    }

    override fun listSpaces(): List<SpaceRuntime> =
        store.all().map { runtime(it.config.id) }.filterNotNull()

    override fun space(spaceId: String): SpaceRuntime? = runtime(spaceId)

    override suspend fun installApk(spaceId: String, apk: File, system: Boolean): VirtualApp =
        withContext(Dispatchers.IO) {
            val record = store.get(spaceId) ?: error("空间不存在: $spaceId")
            val info = parseApk(apk) ?: error("无法解析 APK: ${apk.name}")
            val dest = virtualRoot.resolve(spaceId).resolve("apk").resolve("${info.packageName}.apk")
            dest.parentFile?.mkdirs()
            apk.copyTo(dest, overwrite = true)

            val app = VirtualApp(
                packageName = info.packageName,
                label = info.label,
                versionName = info.versionName ?: "0",
                versionCode = info.versionCode,
                isSystem = system,
                installedAt = System.currentTimeMillis(),
                apkPath = dest.absolutePath,
                targetSdk = info.targetSdk,
                usesGoogleApis = info.usesGoogleApis,
            )
            val apps = record.apps.filterNot { it.packageName == app.packageName } + app.toRecord()
            store.put(record.copy(apps = apps))
            app
        }

    override suspend fun uninstall(spaceId: String, packageName: String) {
        withContext(Dispatchers.IO) {
            val record = store.get(spaceId)
            if (record != null) {
                record.apps.firstOrNull { it.packageName == packageName }?.let {
                    File(it.apkPath).delete()
                }
                store.put(record.copy(apps = record.apps.filterNot { it.packageName == packageName }))
                virtualRoot.resolve(spaceId).resolve("data").resolve(packageName).deleteRecursively()
            }
            Unit
        }
    }

    override fun listApps(spaceId: String): List<VirtualApp> =
        store.get(spaceId)?.apps?.map { it.toApp() } ?: emptyList()

    /**
     * 降级模式下无法凭空运行一个未安装的 APK。
     * 如果该包在宿主系统上真实存在，则退化为「直启宿主应用」，
     * 这样用户至少可以用空间来组织和分组自己已装的应用。
     */
    override suspend fun launch(spaceId: String, packageName: String): LaunchResult =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?: return@withContext LaunchResult.fail(
                    "沙箱模式无法启动未安装的应用「$packageName」。\n" +
                        "需要接入 native 虚拟化引擎（Bcore）后才能在容器内运行。"
                )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            LaunchResult.ok()
        }

    override suspend fun stop(spaceId: String, packageName: String) {
        // 降级模式下无独立进程可停
    }

    override fun runningProcesses(): List<RunningProcess> {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val prefix = appContext.packageName
        @Suppress("DEPRECATION")
        return am.runningAppProcesses.orEmpty()
            .filter { it.processName.startsWith(prefix) }
            .map {
                RunningProcess(
                    spaceId = "",
                    packageName = it.processName,
                    pid = it.pid,
                    memoryKb = memoryOf(it.pid),
                )
            }
    }

    private fun memoryOf(pid: Int): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1024
    }

    // ---------------------------------------------------------------- utils

    private fun runtime(spaceId: String): SpaceRuntime? {
        val record = store.get(spaceId) ?: return null
        val dir = virtualRoot.resolve(spaceId)
        return SpaceRuntime(
            id = spaceId,
            config = record.config,
            dataDir = dir,
            state = if (dir.exists()) SpaceState.READY else SpaceState.BROKEN,
            appCount = record.apps.size,
            // 降级引擎的数据确实写在 <root>/virtual/<spaceId>/ 下
            diskUsage = store.diskUsage(virtualRoot.resolve(spaceId)),
        )
    }

    private data class ApkInfo(
        val packageName: String,
        val label: String,
        val versionName: String?,
        val versionCode: Long,
        val targetSdk: Int,
        val usesGoogleApis: Boolean,
    )

    private fun parseApk(apk: File): ApkInfo? {
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA) ?: return null
        info.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        val label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }
            .getOrDefault(info.packageName)
        val declared = (info.requestedPermissions ?: emptyArray()).toSet()
        val usesGoogleApis = declared.any {
            it.startsWith("com.google.android.gms") ||
                it == "com.google.android.c2dm.permission.RECEIVE" ||
                it.startsWith("com.android.vending")
        }
        return ApkInfo(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            usesGoogleApis = usesGoogleApis,
        )
    }

    private fun VirtualApp.toRecord() = SpaceStore.AppRecord(
        packageName = packageName,
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        isSystem = isSystem,
        installedAt = installedAt,
        apkPath = apkPath,
        targetSdk = targetSdk,
        usesGoogleApis = usesGoogleApis,
    )

    companion object {
        /** 引擎标识。UI 与依赖容器用它判断是否处于降级状态，不要硬编码字面量 */
        const val NAME = "sandbox"
    }
}

