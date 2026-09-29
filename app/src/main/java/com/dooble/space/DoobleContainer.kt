package com.dooble.space

import android.app.Application
import android.content.Context
import com.dooble.engine.EngineProvider
import com.dooble.engine.VirtualEngine
import com.dooble.engine.HostAppScanner
import com.dooble.engine.blackbox.BcoreEngineFactory
import com.dooble.engine.internal.SandboxEngine
import com.dooble.gms.GmsProvisioner
import com.dooble.gms.GmsSelfCheck
import com.dooble.gms.GoogleAccountBridge
import com.dooble.gms.GmsSignatureSpoof
import com.dooble.gms.StoreProvisioner
import com.dooble.nativehook.NativeBridge
import com.dooble.space.service.KeeperControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BootState {
    data object Booting : BootState

    data class Ready(
        val engineName: String,
        /** 是否降级到沙箱模式（没有真正的虚拟化能力） */
        val degraded: Boolean,
        /** Bcore AAR 是否已接入本工程 */
        val bcoreBundled: Boolean,
        val nativeAvailable: Boolean,
        val nativeVersion: String,
    ) : BootState

    data class Failed(val message: String) : BootState
}

/**
 * 依赖容器（Service Locator）。
 *
 * 为什么不用 Hilt/Koin：本工程的依赖图非常浅（一个引擎 + 三个供给层服务），
 * 引入 DI 框架只会增加构建复杂度和包体，收益为负。
 *
 * ## 两段式启动（顺序不能合并）
 *
 * 虚拟化容器的初始化天然分成两段，中间隔着一个**同步**的窗口：
 *
 * ```
 * Application.attachBaseContext(base)      ← 同步、不能等
 *     └─ bootstrapHost()  引擎在此完成宿主进程伪装
 * Application.onCreate()
 *     └─ bootstrap()      异步做重活（native 加载、VAServer、供给层）
 * ```
 *
 * 把 `bootstrapHost` 塞进异步流程是行不通的：`attachBaseContext` 返回时系统
 * 已经按宿主身份完成了进程初始化，之后再改 ClassLoader / 包名就来不及了，
 * 虚拟应用会启动即闪退。
 */
object DoobleContainer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var engineRef: VirtualEngine? = null

    @Volatile
    private var booted = false

    val engine: VirtualEngine
        get() = engineRef ?: error("DoobleContainer 尚未 bootstrap")

    /**
     * 引擎的**可空**访问口。
     *
     * 有些调用方（前台服务、设置页状态刷新）会在启动早期或引擎不可用时被调到，
     * 它们能优雅降级，不该因为"还没装好"就抛异常。业务主链路仍然用 [engine]，
     * 让缺失在开发期立刻暴露。
     */
    val engineOrNull: VirtualEngine? get() = engineRef

    private var hostScannerRef: HostAppScanner? = null
    private var provisionerRef: GmsProvisioner? = null
    private var storeProvisionerRef: StoreProvisioner? = null
    private var selfCheckRef: GmsSelfCheck? = null
    private var accountsRef: GoogleAccountBridge? = null
    private var signatureSpoofRef: GmsSignatureSpoof? = null

    val provisioner: GmsProvisioner get() = provisionerRef ?: error("供给层未初始化")
    val storeProvisioner: StoreProvisioner get() = storeProvisionerRef ?: error("商店供给层未初始化")
    val selfCheck: GmsSelfCheck get() = selfCheckRef ?: error("自检未初始化")
    val accounts: GoogleAccountBridge get() = accountsRef ?: error("账号桥接未初始化")

    /**
     * 签名伪造模块。
     *
     * UI 需要它来切换工作模式并展示当前证书指纹；`GmsSelfCheck` 内部用的是自己
     * 的实例。两者读写同一个策略文件，不共享状态也不会不一致。
     */
    val signatureSpoof: GmsSignatureSpoof get() = signatureSpoofRef ?: error("签名伪造模块未初始化")

    /** 宿主已装应用扫描器。不依赖引擎，所以随 bootstrap 一起就绪 */
    val hostScanner: HostAppScanner get() = hostScannerRef ?: error("宿主扫描器未初始化")

    private val _boot = MutableStateFlow<BootState>(BootState.Booting)
    val boot: StateFlow<BootState> = _boot.asStateFlow()

    /**
     * 第一段：在 `Application.attachBaseContext` 中同步调用。
     *
     * 只有虚拟化引擎才需要它；降级沙箱是空实现。任何异常都必须吞掉——
     * 这里抛出去宿主自己就起不来了，用户看到的是"应用一打开就崩"。
     */
    fun bootstrapHost(application: Application, base: Context) {
        if (engineRef != null) return
        val engine = runCatching { BcoreEngineFactory.createOrNull() }.getOrNull() ?: return
        engineRef = engine
        runCatching { engine.bootstrapHost(application, base) }
    }

    /** 第二段：在 `Application.onCreate` 中调用，异步执行重活 */
    fun bootstrap(app: Application) {
        if (booted) return
        booted = true

        scope.launch {
            try {
                // 复用第一段建好的实例；没有则现探测，再不行降级沙箱
                val engine: VirtualEngine = engineRef
                    ?: BcoreEngineFactory.createOrNull()
                    ?: SandboxEngine(app).also { engineRef = it }

                // 进程级初始化（加载 native、起 VAServer、等系统 hook 就绪）
                engine.initialize(app)
                EngineProvider.install(engine)
                engineRef = engine

                hostScannerRef = HostAppScanner(app)
                provisionerRef = GmsProvisioner(app, engine)
                storeProvisionerRef = StoreProvisioner(app, engine)
                selfCheckRef = GmsSelfCheck(app, engine)
                accountsRef = GoogleAccountBridge(app, engine)
                signatureSpoofRef = GmsSignatureSpoof(app, engine)

                _boot.value = BootState.Ready(
                    engineName = engine.name,
                    degraded = engine.name == SandboxEngine.NAME,
                    bcoreBundled = BcoreEngineFactory.available,
                    nativeAvailable = NativeBridge.available,
                    nativeVersion = NativeBridge.version,
                )

                // 上次退出时保活是开着的，就接着开——用户按下开关的意图是持久的，
                // 不该因为一次进程重启而静默失效。放在 Ready 之后是为了保证
                // 通知里的"空间数 / 驻留数"第一次就能取到真实值。
                if (KeeperControl.isEnabled(app)) {
                    KeeperControl.start(app)
                }
                KeeperControl.refresh(app)
            } catch (t: Throwable) {
                _boot.value = BootState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 引擎启动失败后的重试。
     *
     * 失败页上那个"重试"按钮不能是空实现——boot 走到 Failed 后没有任何
     * 路径能回到 Booting/Ready，用户唯一的自救是强杀进程。
     *
     * 只从 Failed 状态出发；第一段 `bootstrapHost` 已在 attachBaseContext 完成、
     * 无法重来，所以引擎实例照旧复用（它的 initialize 可以再跑一次）。
     */
    fun retryBootstrap(app: Application) {
        if (_boot.value !is BootState.Failed) return
        booted = false
        _boot.value = BootState.Booting
        bootstrap(app)
    }
}
