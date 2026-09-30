package com.dooble.engine.bcore

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.dooble.engine.EngineCapability
import com.dooble.engine.LaunchResult
import com.dooble.engine.RunningProcess
import com.dooble.engine.SpaceConfig
import com.dooble.engine.SpaceRuntime
import com.dooble.engine.SpaceState
import com.dooble.engine.SpaceInstallEvent
import com.dooble.engine.VirtualApp
import com.dooble.engine.VirtualEngine
import com.dooble.engine.internal.BcoreStorageLayout
import com.dooble.engine.internal.InstallEventLog
import com.dooble.engine.internal.SpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File
import java.security.MessageDigest

/**
 * Bcore 虚拟化引擎适配器（编译期绑定）。
 *
 * ## 为什么是编译期绑定而不是反射
 *
 * 早先的实现走反射，理由是"让工程在没有 AAR 时也能编过"。但实测发现这条路
 * 走不通，且危险：
 *
 *  1. Bcore 的启动入口 `doAttachBaseContext(Context, ClientConfiguration)` 要求
 *     传入一个**抽象类**的实例。Java 动态代理只能代理接口，无法实例化抽象类，
 *     反射方案在这一步就断了。
 *  2. 反射调用的方法名一旦写错（本工程就踩过：把 `launchApk` 写成 `launchApp`、
 *     把 `installPackageAsUser` 当成返回 `String`），编译期毫无察觉，
 *     运行期静默返回 null，UI 却显示"启动成功"。这类 bug 极难排查。
 *
 * 因此改为：AAR 存在时**编译期绑定**，方法名/签名/返回值类型全部由编译器校验；
 * AAR 缺失时整个源集不参与编译，由 [com.dooble.engine.bcore.BcoreEngineFactory]
 * 探测失败后降级到沙箱引擎。
 *
 * ## Bcore 的生命周期契约（照抄自官方示例，顺序不可调换）
 *
 * ```
 * Application.attachBaseContext(base):
 *   1. closeCodeInit()                              // 关闭内置初始化，改由我们控制
 *   2. onBeforeMainApplicationAttach(this, base)    // 宿主进程伪装前置
 *   3. doAttachBaseContext(base, ClientConfiguration)  // 安装 Binder 代理
 *   4. onAfterMainApplicationAttach(this, base)     // 收尾
 * Application.onCreate():
 *   5. doCreate()                                   // 起 VAServer、注册系统 hook
 * ```
 */
class BcoreEngine private constructor() : VirtualEngine {

    override val name: String = "bcore"

    override val capability = EngineCapability(
        supportsGms = true,
        supportsSignatureSpoof = true,
        supportsXposed = false,
        supportsMultiInstance = true,
        nativeHook = true,
        supports64Bit = true,
        // 如实回答 false：Bcore 上游没有改写 android.os.Build 的注入点，
        // 我们也没打这个补丁。DeviceProfileSpoofer 写出的 JSON 目前**没有任何消费方**，
        // 所以容器对外呈现的仍是真机指纹。
        // 要做成 true，需要在容器侧新增一个补丁：虚拟进程 fork 后、
        // Application.onCreate 之前，按空间配置改写 Build 的静态字段
        // （或拦 SystemProperties.get("ro.product.*")）。
        supportsDeviceSpoof = false,
    )

    private lateinit var store: SpaceStore
    private lateinit var root: File
    private lateinit var virtualRoot: File

    @Volatile
    private var hostBootstrapped = false

    @Volatile
    private var initialized = false

    /** 宿主 Application Context。标签解析、包名查询都要用它 */
    private var coreContext: Context? = null

    private val core: BlackBoxCore get() = BlackBoxCore.get()

    // ------------------------------------------------------------ 宿主启动

    override fun bootstrapHost(application: Application, base: Context) {
        if (hostBootstrapped) return
        // 这里抛异常必须吞掉：attachBaseContext 失败会让宿主自己起不来。
        // 失败时 initialized 保持 false，上层会降级到沙箱引擎。
        runCatching {
            core.closeCodeInit()
            core.onBeforeMainApplicationAttach(application, base)
            core.doAttachBaseContext(base, DoobleClientConfig(base.packageName))
            core.onAfterMainApplicationAttach(application, base)
            hostBootstrapped = true
        }
    }

    override suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        coreContext = ctx
        root = File(ctx.filesDir, "dooble")
        virtualRoot = File(root, "virtual")
        virtualRoot.mkdirs()
        store = SpaceStore(root)

        // 容器内安装事件要落盘给 UI 看，路径必须在这里先定好——
        // 虚拟应用进程里的 relay 会往同一个文件追加
        InstallEventLog.init(ctx.filesDir)

        // 注册签名伪造钩子。这一步必须在**每个进程**都执行，尤其是容器服务进程
        // （:black）——签名查询真正发生在那里，主进程注册了也没用。
        // 钩子本身只读策略文件、带缓存，不注入时容器行为与未打补丁时完全一致。
        // 传 filesDir：策略文件路径统一由 BcoreStorageLayout.spoofPolicyFile 计算，
        // 与写入方（gms.GmsSignatureSpoof）严格同源。
        BcoreSignatureSpoofer.install(ctx.filesDir)

        // 若宿主没走 bootstrapHost（例如引擎是运行期才探测到的），
        // 这里补一次最小初始化，避免 doCreate 时状态缺失。
        if (!hostBootstrapped) {
            runCatching {
                core.onBeforeMainApplicationAttach(ctx as? Application ?: return@runCatching, ctx)
                core.doAttachBaseContext(ctx, DoobleClientConfig(ctx.packageName))
            }
        }

        core.doCreate()

        // 等 VAServer 起来。doCreate 是异步的，立刻调用会拿到空服务。
        awaitServices(SERVICE_WAIT_MS)
        initialized = true
    }

    private suspend fun awaitServices(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { core.areServicesAvailable() }.getOrDefault(false)) return
            kotlinx.coroutines.delay(80)
        }
    }

    override fun isReady(): Boolean = initialized

    // ------------------------------------------------------------ 空间

    /**
     * 空间 → Bcore userId 的映射。
     *
     * Bcore 的 userId 0 保留给宿主自身，所以用户空间从 1 开始。
     * 用 `users` 列表的**实际内容**推算，而不是用列表下标——用户可能删掉中间
     * 某个空间，下标会错位，导致两个空间共用一个 userId（数据串味）。
     */
    private fun userIdOf(spaceId: String): Int =
        store.get(spaceId)?.let { rec -> rec.bcoreUserId } ?: 0

    /** 分配一个未被占用的 userId，并写回空间记录 */
    @Synchronized
    private fun allocateUserId(spaceId: String): Int {
        val used = store.all().map { it.bcoreUserId }.toMutableSet()
        used += 0 // 宿主保留
        var candidate = 1
        while (candidate in used) candidate++
        // 高水位兜底：spaces.json 一旦损坏重建（见 SpaceStore.load 的留档处理），
        // 内存注册表就认不出"哪些 userId 已经发出去过"——而 Bcore 侧旧 user 的
        // 数据目录还在，复用等于让新空间直接继承上一个空间的应用与账号。
        // 高水位文件跨删除单调递增、永不回退，从根上堵死这条路。
        candidate = maxOf(candidate, store.userIdFloor() + 1)
        store.get(spaceId)?.let { store.put(it.copy(bcoreUserId = candidate)) }
        store.markUserIdUsed(candidate)
        return candidate
    }

    override suspend fun createSpace(config: SpaceConfig): SpaceRuntime = withContext(Dispatchers.IO) {
        val dir = virtualRoot.resolve(config.id)
        listOf("data", "user", "apk", "lib", "cache", "sdcard").forEach { dir.resolve(it).mkdirs() }

        // 先落盘（此时 userId 为 0 占位），再分配 userId 并回写
        store.put(SpaceStore.Record(config = config))
        val userId = allocateUserId(config.id)
        runCatching { core.createUser(userId) }

        runtime(config.id) ?: error("空间创建后无法读取：${config.id}")
    }

    override suspend fun deleteSpace(spaceId: String) {
        withContext(Dispatchers.IO) {
            val userId = userIdOf(spaceId)
            if (userId > 0) {
                runCatching { core.deleteUser(userId) }
                // deleteUser 返回 void：Bcore 侧删失败了**不会抛异常**，必须回查确认。
                // 删失败却照常释放记录的话，这个 userId 立刻能被 allocateUserId
                // 分给新空间，Bcore 里没删干净的应用与账号会被新空间整个继承。
                // 回查失败（拿不到用户列表）时按"还在"处理：宁可不让删，不让用户重试。
                val stillThere = runCatching {
                    core.getUsers().any { it.id == userId }
                }.getOrDefault(true)
                if (stillThere) {
                    error("容器删除 userId=$userId 未完成，已保留空间记录，请重试删除")
                }
            }
            // 先删目录、后删记录：中途失败/被杀时记录还在，重试能继续；
            // 反过来先删记录，残留目录就永远没人认领了（无任何入口能再清它）。
            val dir = virtualRoot.resolve(spaceId)
            if (dir.exists() && !dir.deleteRecursively()) {
                error("空间目录删除失败，已保留空间记录，请重试")
            }
            store.remove(spaceId)
            Unit
        }
    }

    override fun listSpaces(): List<SpaceRuntime> =
        store.all().mapNotNull { runtime(it.config.id) }

    override fun space(spaceId: String): SpaceRuntime? = runtime(spaceId)

    // ------------------------------------------------------------ 应用

    override suspend fun installApk(spaceId: String, apk: File, system: Boolean): VirtualApp =
        withContext(Dispatchers.IO) {
            require(apk.exists()) { "APK 不存在：${apk.absolutePath}" }
            val userId = userIdOf(spaceId).takeIf { it > 0 }
                ?: error("空间未分配 userId，请重建空间")

            val result = core.installPackageAsUser(apk, userId)
            if (!result.success) error("装入失败：${result.msg ?: apk.name}")

            val pkg = result.packageName ?: error("装入返回包名为空：${apk.name}")
            upsert(spaceId, describe(pkg, userId, apk.absolutePath, system))
        }

    override suspend fun installHostApp(spaceId: String, packageName: String): VirtualApp =
        withContext(Dispatchers.IO) {
            val userId = userIdOf(spaceId).takeIf { it > 0 }
                ?: error("空间未分配 userId，请重建空间")

            // Bcore 这条路会读取系统 sourceDir + splitSourceDirs，AAB 拆分包能完整还原。
            // 它内部走 InstallOption.installBySystem()，也就是 **FLAG_SYSTEM 语义**：
            // 不复制 APK、不抽 native lib，直接引用宿主路径，并把宿主的 ApplicationInfo
            // （含 FLAG_SYSTEM）原样交给容器。所以容器内查询这些包时会看到系统应用身份 ——
            // 这正是 GMS / Play 商店这类预装应用在容器内能正常工作的前提。
            val result = core.installPackageAsUser(packageName, userId)
            if (!result.success) error("装入失败：${result.msg ?: packageName}")

            val pkg = result.packageName ?: packageName
            // fallbackSystem = true：这条路本身就是 system 语义，拿不到 PackageInfo 时
            // 按 true 记比按 false 记更接近事实。
            upsert(spaceId, describe(pkg, userId, "", true))
        }

    override suspend fun uninstall(spaceId: String, packageName: String) {
        withContext(Dispatchers.IO) {
            val userId = userIdOf(spaceId)
            // userId<=0 说明空间不存在或尚未分配：打给 user 0 等于操作宿主保留区，
            // 与 launch/resolveActivity 的防护保持一致
            if (userId > 0) runCatching { core.uninstallPackageAsUser(packageName, userId) }
            store.get(spaceId)?.let { rec ->
                store.put(rec.copy(apps = rec.apps.filterNot { it.packageName == packageName }))
            }
            Unit
        }
    }

    override fun listApps(spaceId: String): List<VirtualApp> {
        val userId = userIdOf(spaceId)
        if (userId <= 0) return emptyList()

        val installed = runCatching {
            core.getInstalledPackages(PKG_FLAGS, userId)
        }.getOrNull()

        if (installed.isNullOrEmpty()) {
            // 服务还没起来时退回本地记录，避免 UI 出现"应用凭空消失"
            return store.get(spaceId)?.apps?.map { it.toApp() } ?: emptyList()
        }

        return installed.mapNotNull { toVirtualApp(it, userId) }.sortedBy { it.label }
    }

    override suspend fun launch(spaceId: String, packageName: String): LaunchResult =
        withContext(Dispatchers.IO) {
            val userId = userIdOf(spaceId)
            if (userId <= 0) return@withContext LaunchResult.fail("空间未分配 userId")

            // launchApk 返回 false 的常见原因：该包在空间内没有可启动的 Activity
            val ok = runCatching { core.launchApk(packageName, userId) }.getOrDefault(false)
            if (ok) LaunchResult.ok()
            else LaunchResult.fail("启动失败：$packageName（可能没有可启动的入口，或空间环境异常）")
        }

    override suspend fun stop(spaceId: String, packageName: String) {
        // userId<=0 时空间本身没就绪，容器里不可能有它的进程；
        // 放行会让 stopPackage 打到宿主保留的 user 0 上
        val userId = userIdOf(spaceId)
        if (userId <= 0) return
        runCatching { core.stopPackage(packageName, userId) }
    }

    /**
     * 在容器里解析 intent。
     *
     * 走容器的 `BPackageManager.resolveActivity(intent, flags, resolvedType, userId)`，
     * 而不是宿主的 `PackageManager` —— 空间内的包对宿主是不可见的。
     *
     * `resolvedType` 传 null：调用方（GoogleAccountBridge 之类）用的是显式
     * action/component，不涉及 MIME 匹配；传 null 也是 AMS 内部对
     * "无 data 的 intent"的标准做法。
     */
    override suspend fun resolveActivityInSpace(
        spaceId: String,
        intent: Intent,
    ): ComponentName? = withContext(Dispatchers.IO) {
        val userId = userIdOf(spaceId)
        if (userId <= 0) return@withContext null

        runCatching {
            val info = top.niunaijun.blackbox.fake.frameworks.BPackageManager.get()
                .resolveActivity(intent, 0, null, userId)
            val activity = info?.activityInfo ?: return@runCatching null
            ComponentName(activity.packageName, activity.name)
        }.getOrNull()
    }

    override suspend fun launchIntent(spaceId: String, intent: Intent): LaunchResult =
        withContext(Dispatchers.IO) {
            val userId = userIdOf(spaceId)
            if (userId <= 0) return@withContext LaunchResult.fail("空间未分配 userId")

            // 容器必须自己解析一遍：宿主进程直接 startActivity 会把 intent 交给
            // system_server，而 intent 里写的包名在宿主上是**不存在**的
            // （它是空间内的虚拟包），结果是 ActivityNotFoundException 或
            // 打开宿主的同名应用。
            val resolved = resolveActivityInSpace(spaceId, intent)
                ?: return@withContext LaunchResult.fail(
                    "空间内没有组件能处理该请求（${intent.action ?: intent.component}）"
                )

            val explicit = Intent(intent).apply {
                component = resolved
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val ok = runCatching { core.startActivity(explicit, userId) }
                .onFailure { Log.w("DoobleBcore", "容器内启动失败：$resolved", it) }
                .isSuccess
            if (ok) LaunchResult.ok()
            else LaunchResult.fail("启动失败：$resolved")
        }

    override fun runningProcesses(): List<RunningProcess> {
        // Bcore 的进程查询按 userId 逐个拉取，跨全部空间合并。
        // 拿不到就返回空列表——UI 侧会把"没有运行中进程"当作正常状态展示。
        return store.all().mapNotNull { rec ->
            val userId = rec.bcoreUserId
            if (userId <= 0) return@mapNotNull null
            runCatching {
                top.niunaijun.blackbox.fake.frameworks.BActivityManager.get()
                    .getRunningAppProcesses(rec.config.id, userId)
            }.getOrNull()?.mAppProcessInfoList.orEmpty().map { info ->
                RunningProcess(
                    spaceId = rec.config.id,
                    packageName = info.processName ?: "",
                    pid = info.pid,
                    memoryKb = 0L,
                    isMain = info.pkgList?.contains(info.processName) ?: false,
                )
            }
        }.flatten()
    }

    // ------------------------------------------------------------ 容器内安装事件

    /**
     * 把 relay 写下的 userId 还原成空间 id。
     *
     * 反查而不是让 relay 直接写空间 id：relay 跑在虚拟应用进程里，
     * 它拿不到最新的空间列表（空间可能在安装期间被删掉）。
     * 统一在这里用权威数据源做映射，避免两边不一致。
     */
    override fun drainInstallEvents(): List<SpaceInstallEvent> {
        val byUserId = store.all()
            .filter { it.bcoreUserId > 0 }
            .associate { it.bcoreUserId to it.config.id }

        return InstallEventLog.drain().map { e ->
            SpaceInstallEvent(
                spaceId = byUserId[e.userId].orEmpty(),
                packageName = e.packageName,
                label = e.label.ifBlank { e.packageName },
                ok = e.ok,
                message = e.message,
                timestamp = e.timestamp,
            )
        }
    }

    /**
     * 只读快照安装事件，**不清空**日志。
     *
     * UI 用它在空间详情页做轻量轮询：详情页常驻时，商店在后台装完应用，
     * 这个调用能发现新事件并触发真正的 [listApps] 刷新，不用等用户退出再进。
     * 与 [drainInstallEvents] 共用同一份 userId → 空间 id 映射逻辑。
     */
    override fun peekInstallEvents(): List<SpaceInstallEvent> {
        val byUserId = store.all()
            .filter { it.bcoreUserId > 0 }
            .associate { it.bcoreUserId to it.config.id }

        return InstallEventLog.peek().map { e ->
            SpaceInstallEvent(
                spaceId = byUserId[e.userId].orEmpty(),
                packageName = e.packageName,
                label = e.label.ifBlank { e.packageName },
                ok = e.ok,
                message = e.message,
                timestamp = e.timestamp,
            )
        }
    }

    // ------------------------------------------------------------ 签名伪造验收

    /**
     * 接入点是否就位。
     *
     * 直接问 Bcore：钩子注册上了没有。为 false 的两种常见原因：
     *  1. `engine/libs/bcore.aar` 是从**未打补丁**的上游源码编的；
     *  2. 本进程没走到 [initialize]（钩子是在那里注册的）。
     */
    override fun signatureHookReady(): Boolean =
        runCatching { top.niunaijun.blackbox.core.system.pm.PackageSignatureSpoofer.getHook() != null }
            .getOrDefault(false)

    override fun virtualSignatureSha1(spaceId: String, packageName: String): String? {
        val userId = userIdOf(spaceId)
        if (userId <= 0) return null
        val info = runCatching {
            core.getInstalledPackages(PackageManager.GET_SIGNATURES, userId)
                .firstOrNull { it.packageName == packageName }
        }.getOrNull() ?: return null

        @Suppress("DEPRECATION")
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val first = sigs?.firstOrNull() ?: return null
        return sha1(first.toByteArray())
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ 内部工具

    private fun describe(
        packageName: String,
        userId: Int,
        apkPath: String,
        system: Boolean,
    ): VirtualApp {
        val pkg = runCatching {
            core.getInstalledPackages(PKG_FLAGS, userId).firstOrNull { it.packageName == packageName }
        }.getOrNull()
        return toVirtualApp(pkg, userId, packageName, apkPath, system)
            ?: VirtualApp(
                packageName = packageName,
                label = packageName,
                versionName = "",
                versionCode = 0,
                isSystem = system,
                installedAt = System.currentTimeMillis(),
                apkPath = apkPath,
            )
    }

    private fun toVirtualApp(
        pkg: PackageInfo?,
        userId: Int,
        fallbackName: String = "",
        fallbackApk: String = "",
        fallbackSystem: Boolean = false,
    ): VirtualApp? {
        if (pkg == null) {
            if (fallbackName.isBlank()) return null
            return VirtualApp(
                packageName = fallbackName,
                label = fallbackName,
                versionName = "",
                versionCode = 0,
                isSystem = fallbackSystem,
                installedAt = System.currentTimeMillis(),
                apkPath = fallbackApk,
            )
        }
        val app: ApplicationInfo? = pkg.applicationInfo
        val pm = coreContext?.packageManager
        val resolvedApk = app?.sourceDir ?: fallbackApk

        // 先试从 APK 文件里读，再退回容器给的 ApplicationInfo。
        // 顺序不能反 —— 原因见 [labelFromApk]。
        val label = labelFromApk(resolvedApk)
            ?: runCatching { app?.let { pm?.getApplicationLabel(it)?.toString() } }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: pkg.packageName

        return VirtualApp(
            packageName = pkg.packageName,
            label = label,
            versionName = pkg.versionName ?: "",
            versionCode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else {
                @Suppress("DEPRECATION") pkg.versionCode.toLong()
            },
            isSystem = (app?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0,
            installedAt = pkg.firstInstallTime,
            apkPath = app?.sourceDir ?: fallbackApk,
            targetSdk = app?.targetSdkVersion ?: 0,
            usesGoogleApis = VirtualApp.MICROG_PACKAGES.contains(pkg.packageName),
        )
    }

    /**
     * 从 APK 文件里读应用名。
     *
     * ## 为什么不直接用 `pm.getApplicationLabel(appInfo)`
     *
     * 容器给回来的 `ApplicationInfo.sourceDir` 指向虚拟目录，而**宿主
     * PackageManager 里根本没有这个包的记录**。`ApplicationInfo.loadLabel()`
     * 在解析不出 `labelRes` 时会**静默退化成包名** —— 不抛异常、不报错，
     * 表现就是空间里所有应用都叫 `com.xxx.yyy`，看起来像"应用名丢了"。
     *
     * 这个坑在模拟器实测里被截图抓到过一次：图标已经是真的了，
     * 名字却还是包名，两者不一致非常显眼。
     *
     * 修法和自己加载图标一样：`getPackageArchiveInfo` 解析清单拿到
     * `labelRes`，再把 `sourceDir` / `publicSourceDir` 补上，
     * 这样 `getApplicationLabel` 才找得到资源表。
     */
    /**
     * APK 应用名解析缓存：`apkPath -> (mtime, label)`。
     *
     * [listApps] 对容器里的每个包都要解析一次 APK 取名字，而容器包列表里
     * 混着宿主全部系统包（几百个），模拟器/翻译层环境下一次全解析要分钟级。
     * key 带上文件 mtime：覆盖安装后路径不变内容变，mtime 变了自然重解析。
     */
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun labelFromApk(apkPath: String?): String? {
        if (apkPath.isNullOrBlank()) return null
        val apkFile = File(apkPath)
        if (!apkFile.isFile) return null
        val mtime = runCatching { apkFile.lastModified() }.getOrDefault(0L)
        labelCache[apkPath]?.let { (cachedAt, label) ->
            if (cachedAt == mtime) return label
        }
        val pm = coreContext?.packageManager ?: return null
        val label = runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(apkPath, 0)?.applicationInfo
                ?: return@runCatching null
            info.sourceDir = apkPath
            info.publicSourceDir = apkPath
            pm.getApplicationLabel(info).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        // 防御性上限：包列表再大也就几百项，超过说明有异常路径在灌缓存
        if (labelCache.size > 1024) labelCache.clear()
        labelCache[apkPath] = mtime to label
        return label
    }

    private fun upsert(spaceId: String, app: VirtualApp): VirtualApp {
        store.get(spaceId)?.let { rec ->
            store.put(
                rec.copy(
                    apps = rec.apps.filterNot { it.packageName == app.packageName } + app.toRecord(),
                )
            )
        }
        return app
    }

    private fun runtime(spaceId: String): SpaceRuntime? {
        val record = store.get(spaceId) ?: return null
        val dir = virtualRoot.resolve(spaceId)
        return SpaceRuntime(
            id = spaceId,
            config = record.config,
            dataDir = dir,
            state = if (dir.exists()) SpaceState.READY else SpaceState.BROKEN,
            appCount = record.apps.size,
            diskUsage = diskUsageOf(record),
            bcoreUserId = record.bcoreUserId,
        )
    }

    /**
     * 该空间实际占用的字节数。
     *
     * 注意：Bcore 的数据**不在** `<filesDir>/dooble/virtual/` 下——那个目录是引擎
     * 早期设计的遗留占位，实测只有 1485 字节；真实数据在 Bcore 自己的虚拟根
     * `/data/data/<宿主包名>/blackbox/` 下，实测同一空间是 223MB。
     *
     * 路径全部由 [BcoreStorageLayout] 给出（那里有单元测试钉着），不要就地拼字符串。
     */
    private fun diskUsageOf(record: SpaceStore.Record): Long {
        val ctx = coreContext ?: return 0L
        val dirs = BcoreStorageLayout.spaceDirs(
            dataDir = ctx.dataDir,
            externalFilesDir = ctx.getExternalFilesDir(null),
            userId = record.bcoreUserId,
            packages = record.apps.map { it.packageName },
        )
        return store.diskUsage(*dirs.toTypedArray())
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

    /** 宿主包名与 Context 必须在 doAttachBaseContext 之前就绪，供 ClientConfiguration 使用 */
    private class DoobleClientConfig(private val hostPkg: String) : ClientConfiguration() {
        override fun getHostPackageName(): String = hostPkg

        /** 本方案全程免 Root，直接对外宣称"无 root"最干净 */
        override fun isHideRoot(): Boolean = true

        /** 常驻守护进程：虚拟应用被系统回收后能自动拉起，是"多开保活"的关键 */
        override fun isEnableDaemonService(): Boolean = true

        /** 不劫持网络。走 VPN 会与用户自己的代理/加速器冲突 */
        override fun isUseVpnNetwork(): Boolean = false

        /** 不禁用 FLAG_SECURE——截图防护是应用自己的正当权利，不该由容器剥夺 */
        override fun isDisableFlagSecure(): Boolean = false

        /** 由我们的 UI 负责安装流程，容器不需要再弹系统安装器 */
        override fun requestInstallPackage(file: File, userId: Int): Boolean =
            ContainerInstallRelay.handle(file, userId)

        /**
         * 会话式安装（商店走的那条路）完成后的结果。
         *
         * 这条通道没有"请求时机"可拦——容器自己装完了才通知我们。
         * 落进同一份事件日志即可，UI 侧会把它显示成「最近装入」。
         */
        override fun onInstallResult(
            file: File,
            userId: Int,
            success: Boolean,
            packageName: String?,
            message: String?,
        ) {
            ContainerInstallRelay.onSessionResult(file, userId, success, packageName, message)
        }

        /** 我们的 UI 自己实现启动器，不需要 Bcore 再生成一个入口 Activity */
        override fun isEnableLauncherActivity(): Boolean = false

        /**
         * 容器内的商店包名，按优先级排列。
         *
         * 用途：空间里的应用用 `market://details?id=xxx` 或
         * `https://play.google.com/store/apps/details?id=xxx` 唤起商店时，
         * 容器先按 intent-filter 解析；**解析不到**才会用这份列表逐个
         * `setPackage()` 重试，命中就把链接交给容器内的商店。
         *
         * 名单来自 [com.dooble.engine.StorePackages]，与供给层用的是同一份，
         * 避免"装的是 A、链接却路由到 B"。
         */
        override fun getStorePackages(): List<String> = com.dooble.engine.StorePackages.ALL
    }

    companion object {
        /**
         * 查询容器内包信息时用的 flags：**0**。
         *
         * ## 为什么不能带 GET_ACTIVITIES | GET_META_DATA
         *
         * 这是模拟器实测抓出来的：`getInstalledPackages` 的单次 Binder 回包
         * 达到 **3,979,080 字节**，超过 Binder 的事务上限，直接抛异常：
         *
         * ```
         * W/BlackBoxCore: Large reply transaction of 3979080 bytes,
         *     interface descriptor IBPackageManagerService, code 12
         * W/System.err:   at BcoreEngine.listApps(BcoreEngine.kt:239)
         * ```
         *
         * 根因在容器侧的 `PackageManagerCompat.generatePackageInfo()`：
         * 带 `GET_ACTIVITIES` 会为**每个包**构造完整的 `ActivityInfo[]`，
         * 再叠加 `GET_META_DATA` 还会给每个组件填 metaData Bundle。
         * 容器的 `mPackages` 里装着宿主全部系统包，几百个包乘下来就是几 MB。
         *
         * 而我们真正要用的字段 —— `packageName` / `versionName` /
         * `versionCode` / `applicationInfo.sourceDir` / `targetSdkVersion` /
         * `flags` —— **全部与 flags 无关**（见 `generateApplicationInfo`，
         * sourceDir、dataDir、nativeLibraryDir、uid 都是无条件赋值的）。
         *
         * 所以这里传 0。应用名走 [labelFromApk] 从 APK 里读，启动入口由
         * `launchApk` 自己解析，都不需要这两个 flag。
         *
         * 实测效果：`listApps` 从"必然失败后回退本地记录"变成真正可用。
         */
        private const val PKG_FLAGS = 0

        private const val SERVICE_WAIT_MS = 5_000L

        /**
         * 构造引擎。**只做探测，不做重活**——它会在
         * `Application.attachBaseContext` 里被同步调用，必须够快。
         */
        @JvmStatic
        fun create(): BcoreEngine = BcoreEngine()
    }
}
