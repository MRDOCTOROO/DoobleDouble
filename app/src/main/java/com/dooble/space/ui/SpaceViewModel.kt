package com.dooble.space.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dooble.engine.DeviceSpoofProfile
import com.dooble.engine.HostAppInfo
import com.dooble.engine.HostAppRules
import com.dooble.engine.SpaceInstallEvent
import com.dooble.engine.SpaceConfig
import com.dooble.engine.SpaceRuntime
import com.dooble.engine.VirtualApp
import com.dooble.gms.GoogleAccountInfo
import com.dooble.gms.GmsSignatureSpoof
import com.dooble.gms.GoogleStack
import com.dooble.gms.MicroGComponent
import com.dooble.gms.OemStack
import com.dooble.gms.ProvisionReport
import com.dooble.gms.ProvisionStep
import com.dooble.gms.SelfCheckReport
import com.dooble.gms.SpoofMode
import com.dooble.gms.StoreReport
import com.dooble.gms.StoreStep
import com.dooble.space.DoobleContainer
import com.dooble.space.service.KeeperControl
import com.dooble.space.service.WarmPool
import com.dooble.space.system.PowerOptimization
import com.dooble.space.system.ShortcutPublisher
import com.dooble.space.system.TaskCleaner
import com.dooble.space.system.VirtualTaskResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 主界面的状态与操作中枢。
 *
 * 所有耗时动作（装 APK、下载 microG、跑自检）都在 IO 线程，
 * UI 只消费 StateFlow。**不允许**在 Composable 里直接碰引擎。
 */
class SpaceViewModel(app: Application) : AndroidViewModel(app) {

    private val engine get() = DoobleContainer.engine

    /**
     * 空间 id → 容器虚拟 userId，供收任务卡片时按空间过滤。
     *
     * 拿不到就返回 null（不做空间过滤）。宁可多收一张卡片，也不能因为
     * 列表还没加载完就一张都不收 —— 后者会让"结束运行"看起来完全失效。
     */
    private fun taskUserIdOf(spaceId: String): Int? =
        _spaces.value.firstOrNull { it.id == spaceId }
            ?.let { VirtualTaskResolver.filterUserId(it.bcoreUserId) }

    private val _spaces = MutableStateFlow<List<SpaceRuntime>>(emptyList())
    val spaces: StateFlow<List<SpaceRuntime>> = _spaces.asStateFlow()

    private val _currentSpaceId = MutableStateFlow<String?>(null)
    val currentSpaceId: StateFlow<String?> = _currentSpaceId.asStateFlow()

    private val _apps = MutableStateFlow<List<VirtualApp>>(emptyList())
    val apps: StateFlow<List<VirtualApp>> = _apps.asStateFlow()

    private val _provisionStep = MutableStateFlow<ProvisionStep?>(null)
    val provisionStep: StateFlow<ProvisionStep?> = _provisionStep.asStateFlow()

    private val _provisionReport = MutableStateFlow<ProvisionReport?>(null)
    val provisionReport: StateFlow<ProvisionReport?> = _provisionReport.asStateFlow()

    private val _report = MutableStateFlow<SelfCheckReport?>(null)
    val report: StateFlow<SelfCheckReport?> = _report.asStateFlow()

    /**
     * 自检是否正在跑。
     *
     * 必须暴露出来：自检里有一项要**真的发网络请求**（见 `NetworkProbe`），
     * 在代理不通的环境下最坏要 6 秒，加上本地检查整体可能几十秒。
     * 没有这个状态的话，用户点完按钮看到的是"什么都没发生" ——
     * 实测有人因此以为按钮坏了，反复点。
     */
    private val _selfCheckRunning = MutableStateFlow(false)
    val selfCheckRunning: StateFlow<Boolean> = _selfCheckRunning.asStateFlow()

    private val _accounts = MutableStateFlow<List<GoogleAccountInfo>>(emptyList())
    val accounts: StateFlow<List<GoogleAccountInfo>> = _accounts.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _hostApps = MutableStateFlow<List<HostAppInfo>>(emptyList())
    val hostApps: StateFlow<List<HostAppInfo>> = _hostApps.asStateFlow()

    private val _hostScanning = MutableStateFlow(false)
    val hostScanning: StateFlow<Boolean> = _hostScanning.asStateFlow()

    /**
     * 导入列表是否包含系统应用。
     *
     * **默认关闭**是有意的：系统应用里混着 Settings、SystemUI 这类"装进空间
     * 毫无意义"的东西，默认全列出来只会淹没真正想导入的第三方应用。
     * 但也不能像原来那样**永远**不显示——国产 ROM 上第三方应用常常只有几个，
     * 用户会以为"导入功能是空的、坏的"。
     */
    private val _hostIncludeSystem = MutableStateFlow(false)
    val hostIncludeSystem: StateFlow<Boolean> = _hostIncludeSystem.asStateFlow()

    /** 只列出声明了 GMS 权限的应用——想验证"谷歌环境"时用它缩小范围 */
    private val _hostOnlyGms = MutableStateFlow(false)
    val hostOnlyGms: StateFlow<Boolean> = _hostOnlyGms.asStateFlow()

    /**
     * 最近一次搜索关键词。
     *
     * 筛选开关变化时要拿它重扫，否则用户输入"goo"再打开「包含系统应用」，
     * 列表会突然退回全量结果——搜索词看起来"失效了"。
     */
    private var hostQuery: String = ""

    private val _storeStep = MutableStateFlow<StoreStep?>(null)
    val storeStep: StateFlow<StoreStep?> = _storeStep.asStateFlow()

    private val _storeReport = MutableStateFlow<StoreReport?>(null)
    val storeReport: StateFlow<StoreReport?> = _storeReport.asStateFlow()

    /**
     * 空间内应用自己发起安装的结果（商店下载完自动装、或应用内更新）。
     * 只保留最近若干条，纯展示用。
     */
    private val _recentInstalls = MutableStateFlow<List<SpaceInstallEvent>>(emptyList())
    val recentInstalls: StateFlow<List<SpaceInstallEvent>> = _recentInstalls.asStateFlow()

    /**
     * 当前空间的签名伪造模式。
     *
     * 默认 OFF，这是**刻意的默认值**：microG + Aurora Store 的主链路
     * （登录谷歌账号、下载安装、多开）不依赖签名伪造，把它默认打开只会
     * 让本地测试平白多出一堆"看起来坏了"的红字。
     */
    private val _spoofMode = MutableStateFlow(SpoofMode.OFF)
    val spoofMode: StateFlow<SpoofMode> = _spoofMode.asStateFlow()

    /**
     * 上一次扫描发现的应用列表可见性问题。
     *
     * 非 null 且 `gated == true` 时，UI 要给出"去开读取应用列表权限"的引导。
     * 见 [AppListAccess] 与 `HostAppRules.visibilityVerdict` 的说明。
     */
    private val _hostVisibility = MutableStateFlow<HostAppRules.VisibilityVerdict?>(null)
    val hostVisibility: StateFlow<HostAppRules.VisibilityVerdict?> = _hostVisibility.asStateFlow()

    /**
     * 正在处理的组件槽位（包名）。非 null 时对应的槽位按钮显示为进行中并禁用。
     *
     * 用包名而不是布尔量：槽位是并列的一排，用一个全局 busy 会让用户
     * 看不出到底是哪一个在动。
     */
    private val _slotBusy = MutableStateFlow<String?>(null)
    val slotBusy: StateFlow<String?> = _slotBusy.asStateFlow()

    /**
     * 宿主上存在、且与"谷歌环境"相关的包集合。
     *
     * UI 靠它决定要不要显示"从宿主克隆"按钮 —— 宿主上没装的包，
     * 显示一个点了必然失败的按钮只会让用户困惑。
     */
    private val _hostGooglePackages = MutableStateFlow<Set<String>>(emptySet())
    val hostGooglePackages: StateFlow<Set<String>> = _hostGooglePackages.asStateFlow()

    /**
     * 当前设备是不是华为/荣耀。
     *
     * 只算一次：`Build.MANUFACTURER` / `Build.BRAND` 在整个进程生命周期内不会变，
     * 放进 StateFlow 只是为了让 UI 能订阅。
     */
    private val _isHuaweiDevice = MutableStateFlow(
        OemStack.isHuaweiDevice(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
    )
    val isHuaweiDevice: StateFlow<Boolean> = _isHuaweiDevice.asStateFlow()

    private val _spoofCert = MutableStateFlow<GmsSignatureSpoof.CertInfo?>(null)
    val spoofCert: StateFlow<GmsSignatureSpoof.CertInfo?> = _spoofCert.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * 宿主当前的电池优化状态。
     *
     * 存在 ViewModel 里而不是每次读系统：`isIgnoringBatteryOptimizations` 是一次
     * binder 调用，而它要在用户从系统弹窗返回时刷新——用 StateFlow 让 UI 自己订阅，
     * 比在每个 Composable 里现查要省得多。
     */
    private val _powerState = MutableStateFlow(PowerOptimization.State.UNKNOWN)
    val powerState: StateFlow<PowerOptimization.State> = _powerState.asStateFlow()

    /** 当前正在往桌面钉的包名（钉的过程要读 APK 图标，不是瞬时的） */
    private val _pinningPackage = MutableStateFlow<String?>(null)
    val pinningPackage: StateFlow<String?> = _pinningPackage.asStateFlow()

    /**
     * 空间保活面板的状态。直接暴露 [KeeperControl] 的快照——保活是全局的，
     * 不属于任何一个空间，没必要在 ViewModel 里再存一份镜像。
     */
    val keeper: StateFlow<KeeperControl.Status> = KeeperControl.status

    init {
        refreshSpaces()
        KeeperControl.refresh(getApplication())
        refreshPowerState()
        refreshHostGooglePackages()
    }

    // ------------------------------------------------------------- 空间

    fun refreshSpaces() {
        viewModelScope.launch {
            runCatching {
                _spaces.value = withContext(Dispatchers.IO) { engine.listSpaces() }
                    .sortedByDescending { it.config.createdAt }
            }.onFailure { _toast.value = "读取空间失败：${it.message}" }
        }
    }

    fun currentSpace(): SpaceRuntime? =
        _spaces.value.firstOrNull { it.id == _currentSpaceId.value }

    fun selectSpace(id: String?) {
        _currentSpaceId.value = id
        // 钉住当前空间：用户正开着它，里面的应用随时可能被点开，
        // 内存回收时不能动——否则切回来就是一次冷启动
        KeeperControl.pin(getApplication(), id)
        if (id != null) {
            refreshApps()
            refreshAccounts()
            refreshSpoof()
            _hostApps.value = emptyList()
            _report.value = null
            _provisionReport.value = null
        }
    }

    fun createSpace(
        name: String,
        accent: Int,
        googleEnvironment: Boolean,
        profile: DeviceSpoofProfile,
        redirectStorage: Boolean,
    ) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val config = SpaceConfig.new(name.ifBlank { "未命名空间" }, accent).copy(
                    googleEnvironment = googleEnvironment,
                    deviceSpoof = profile,
                    redirectStorage = redirectStorage,
                )
                withContext(Dispatchers.IO) { engine.createSpace(config) }
                refreshSpaces()
                _currentSpaceId.value = config.id
                refreshApps()

                // 勾了「启用谷歌环境」就**当场装配**。
                //
                // 之前只创建、不装配 —— 那个勾选只是写进了 SpaceConfig.googleEnvironment，
                // **没有任何地方消费它**。用户以为勾了就有，结果进空间发现 microG 一个都没有，
                // 必须自己再去点一次「装配谷歌环境」，看起来就像勾选失效。
                //
                // 装配要下 100MB+，所以提示语要说清楚在干什么，不能只说"已创建"。
                if (googleEnvironment) {
                    _toast.value = "空间已创建，正在装配谷歌环境（首次需下载约 107MB）…"
                    provisionInto(config.id)
                } else {
                    _toast.value = "空间「${config.name}」已创建"
                }
            }.onFailure { _toast.value = "创建失败：${it.message}" }
            _busy.value = false
        }
    }

    fun deleteSpace(id: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                withContext(Dispatchers.IO) { engine.deleteSpace(id) }
                KeeperControl.onSpaceRemoved(getApplication(), id)
                if (_currentSpaceId.value == id) _currentSpaceId.value = null
                _toast.value = "空间已删除"
                refreshSpaces()
            }.onFailure { _toast.value = "删除失败：${it.message}" }
            _busy.value = false
        }
    }

    // ------------------------------------------------------------- 应用

    fun refreshApps() {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            runCatching {
                // 先消费容器内安装事件——它可能刚刚往这个空间里装了东西，
                // 顺序反了会让用户看到"装完了但列表里没有"
                consumeInstallEvents(id)
                _apps.value = withContext(Dispatchers.IO) { engine.listApps(id) }
                    .sortedBy { it.label }
            }.onFailure { _toast.value = "读取应用失败：${it.message}" }
        }
    }

    /**
     * 消费「容器内应用自己发起的安装」结果。
     *
     * 这条路径的触发方是空间里的应用商店（或任何应用内的安装动作）：
     * 它在虚拟进程里下载 APK 并触发安装，由容器拦截后装进当前空间。
     * 结果只能通过事件日志回传，所以要在这里主动捞一次。
     */
    private suspend fun consumeInstallEvents(spaceId: String) {
        val events = runCatching {
            withContext(Dispatchers.IO) { engine.drainInstallEvents() }
        }.getOrDefault(emptyList())
        if (events.isEmpty()) return

        val mine = events.filter { it.spaceId == spaceId }
        if (mine.isEmpty()) return

        _recentInstalls.value = (mine + _recentInstalls.value).take(MAX_RECENT_INSTALLS)
        val last = mine.last()
        _toast.value = if (last.ok) {
            "已装入：${last.label}"
        } else {
            "装入失败：${last.message}"
        }
    }

    // ------------------------------------------------------------- 应用商店

    /**
     * 往当前空间装一个应用商店（Aurora Store）。
     *
     * 装完后用户就能在空间内登录 Google 账号、搜索并下载应用——下载的 APK
     * 会经由容器的安装拦截装进**同一个空间**，不会跑到宿主机上。
     */
    fun installStore() {
        val id = _currentSpaceId.value ?: run {
            _toast.value = "请先进入一个空间"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _storeStep.value = null
            val report = DoobleContainer.storeProvisioner.provision(id) { step ->
                _storeStep.value = step
            }
            _storeReport.value = report
            _toast.value = report.message
            refreshApps()
            refreshSpaces()
            _busy.value = false
        }
    }

    fun clearStoreCache() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { DoobleContainer.storeProvisioner.clearCache() }
                _toast.value = "已清理商店下载缓存"
            }.onFailure { _toast.value = "清理失败：${it.message}" }
        }
    }

    /** 从 SAF 选中的 APK 装入当前空间 */
    fun installApk(uri: Uri) {
        val id = _currentSpaceId.value ?: run {
            _toast.value = "请先进入一个空间"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val ctx = getApplication<Application>()
                val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.apk")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: error("无法读取所选文件")
                }
                val app = withContext(Dispatchers.IO) { engine.installApk(id, tmp) }
                tmp.delete()
                _toast.value = "已装入：${app.label}"
                refreshApps()
                refreshSpaces()
            }.onFailure { _toast.value = "装入失败：${it.message}" }
            _busy.value = false
        }
    }

    /**
     * 扫描宿主已安装的应用。
     *
     * 这条路径比"让用户去文件管理器找 APK"实用得多：用户手机上本来就有这个应用，
     * 他想要的是"再复制一份到空间里"，而不是先想办法把 APK 导出来。
     */
    fun scanHostApps(query: String = "") {
        hostQuery = query
        viewModelScope.launch {
            _hostScanning.value = true
            runCatching {
                val scanner = DoobleContainer.hostScanner
                _hostApps.value = withContext(Dispatchers.IO) {
                    scanner.scan(
                        includeSystem = _hostIncludeSystem.value,
                        onlyGmsDependent = _hostOnlyGms.value,
                        query = query,
                    )
                }
                // 扫描顺带把"包可见性是否被 ROM 拦了"带出来。UI 用它决定要不要
                // 显示"去开读取应用列表权限"的引导 —— 用户看到空列表时，
                // 唯一能解释原因的线索就在这里。
                _hostVisibility.value = scanner.lastVisibility
            }.onFailure { _toast.value = "扫描已安装应用失败：${it.message}" }
            _hostScanning.value = false
        }
    }

    /**
     * 切换导入列表的筛选条件，并立即用当前搜索词重扫。
     *
     * 两个开关合并成一个入口而不是两个函数：它们共享"当前搜索词"这份上下文，
     * 分开写必然出现"改了一个忘了传另一个"的错。
     */
    fun setHostFilters(includeSystem: Boolean, onlyGmsDependent: Boolean) {
        if (_hostIncludeSystem.value == includeSystem && _hostOnlyGms.value == onlyGmsDependent) return
        _hostIncludeSystem.value = includeSystem
        _hostOnlyGms.value = onlyGmsDependent
        scanHostApps(hostQuery)
    }

    /**
     * 把宿主已安装的应用导入当前空间。
     *
     * 与 [installApk] 的区别：这里只传包名，由容器直接读系统的安装路径，
     * 因此不需要存储权限、也不用先导出 APK。
     *
     * 注意：容器侧 `installPackageAsUser(String, int)` 目前**只透传 sourceDir**，
     * `splitSourceDirs` 会被丢弃。所以 AAB 拆分包（2019 年后 Google Play 应用的
     * 常态）走这条路径也只会装入基础 APK，运行时多半缺资源。
     * 详见 docs/09-应用安装与宿主克隆.md 第 3 节。
     */
    fun importHostApp(packageName: String) {
        val id = _currentSpaceId.value ?: run {
            _toast.value = "请先进入一个空间"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val app = withContext(Dispatchers.IO) { engine.installHostApp(id, packageName) }
                _toast.value = "已装入：${app.label}"
                refreshApps()
                refreshSpaces()
            }.onFailure { _toast.value = "导入失败：${it.message}" }
            _busy.value = false
        }
    }

    // ------------------------------------------------------------- 组件槽位

    /**
     * 刷新「宿主上装了哪些谷歌相关包」。
     *
     * UI 靠它决定要不要显示「从宿主克隆」按钮。宿主上没装的包给一个
     * 点了必然失败的按钮，只会让用户以为功能坏了。
     */
    fun refreshHostGooglePackages() {
        viewModelScope.launch {
            val scanner = runCatching { DoobleContainer.hostScanner }.getOrNull() ?: return@launch
            // 谷歌侧 + 厂商侧（华为/荣耀）。厂商那几个包在非华为设备上不存在，
            // 查出来就是空集，UI 自然不会显示对应槽位 —— 不需要额外分支。
            val wanted = GoogleStack.WATCHED_PACKAGES + OemStack.WATCHED_PACKAGES
            val found = withContext(Dispatchers.IO) {
                wanted.filterTo(HashSet()) { scanner.isInstalledOnHost(it) }
            }
            _hostGooglePackages.value = found
        }
    }

    /**
     * 从宿主克隆一个包进当前空间（**含系统应用**）。
     *
     * 这条路径不是"复制 APK"：引擎侧走的是 Bcore 的 system 语义
     * （`installBySystem`），容器直接引用宿主的 `ApplicationInfo`，
     * 所以 GMS / Play 商店这类预装应用在容器内会呈现为系统应用。
     * 详见 [GoogleStack.CONTAINER_SUPPORT]。
     *
     * 另外它天然支持 AAB 拆分包 —— 引擎读的是宿主的 `sourceDir` +
     * `splitSourceDirs`，不需要用户先把 APK 导出来。
     */
    fun cloneFromHost(packageName: String) {
        val id = _currentSpaceId.value ?: run {
            _toast.value = "请先进入一个空间"
            return
        }
        viewModelScope.launch {
            _slotBusy.value = packageName
            runCatching {
                val app = withContext(Dispatchers.IO) { engine.installHostApp(id, packageName) }
                _toast.value = "已从宿主装入：${app.label}"
                refreshApps()
                refreshSpaces()
            }.onFailure { _toast.value = "克隆失败：${it.message ?: packageName}" }
            _slotBusy.value = null
        }
    }

    /**
     * 从本地文件导入一个 APK 并装入当前空间。
     *
     * @param component 非 null 时，同时把 APK 存进 microG 缓存 —— 这样下次点
     *   「装配谷歌环境」会直接命中缓存（`resolveApk` 的第 2 级），不会再去下载。
     *   对 GSF Proxy 这种上游只有 21KB 但可能下载不到的场景尤其有用。
     *
     * 注意走的是 storage 语义（复制进容器 + 抽 native 库），**不会**给系统应用身份。
     * 原版 GMS / Play 商店需要 system 语义，请改用 [cloneFromHost]。
     */
    fun importApkFromUri(uri: Uri, component: MicroGComponent? = null) {
        val id = _currentSpaceId.value ?: run {
            _toast.value = "请先进入一个空间"
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _slotBusy.value = component?.packageName ?: uri.toString()
            runCatching {
                val apk = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "imported-apk").apply { mkdirs() }
                    // 用时间戳命名：用户可能连续导入同一文件的不同版本，
                    // 固定名会被覆盖，而覆盖后失败时现场就没了。
                    val dest = File(dir, "import-${System.currentTimeMillis()}.apk")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { input.copyTo(it) }
                    } ?: error("无法读取所选文件")
                    if (dest.length() < 1024L) error("文件太小，不像是 APK（${dest.length()} 字节）")
                    dest
                }

                if (component != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { DoobleContainer.provisioner.importComponentApk(component, apk) }
                    }
                }

                val app = withContext(Dispatchers.IO) { engine.installApk(id, apk, system = false) }
                _toast.value = "已装入：${app.label}"
                refreshApps()
                refreshSpaces()
            }.onFailure { _toast.value = "导入失败：${it.message}" }
            _slotBusy.value = null
        }
    }

    /**
     * 用户从系统设置返回后调用：重扫宿主应用并刷新槽位可用性。
     *
     * 单独一个入口而不是让 UI 直接调两个函数：这两件事必须一起做，
     * 分开写必然出现"只刷了一个"的错。
     */
    fun onReturnedFromSettings() {
        scanHostApps(hostQuery)
        refreshHostGooglePackages()
    }

    fun launchApp(packageName: String) {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { engine.launch(id, packageName) }
            if (!result.ok) {
                _toast.value = result.message
            } else {
                // 只有真的起来了才记进 LRU。启动失败还记的话，
                // 内存回收时会误判"这是用户最近在用的"而保住一个死进程名额
                KeeperControl.onAppLaunched(getApplication(), id, packageName)
            }
        }
    }

    fun uninstall(packageName: String) {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                withContext(Dispatchers.IO) { engine.uninstall(id, packageName) }
                KeeperControl.onAppRemoved(getApplication(), id, packageName)
                // 应用都卸了，指向它的任务卡片必须一起收掉，否则点开是空的。
                // 带上 userId：卸载只作用于这一个空间，别的空间里还有同名应用，
                // 它的卡片不该被顺手收掉。
                withContext(Dispatchers.IO) {
                    TaskCleaner.removeTasksOf(getApplication(), packageName, taskUserIdOf(id))
                }
                refreshApps()
                refreshSpaces()
            }.onFailure { _toast.value = "卸载失败：${it.message}" }
            _busy.value = false
        }
    }

    /**
     * 结束某个应用在空间内的全部进程。
     *
     * 只杀这一个包：引擎侧的 `stopPackage(packageName, userId)` 是按
     * (包名, userId) 精确匹配的，同一空间里其他应用、以及其他空间里的
     * 同名应用都不受影响。这一点和"强制停止宿主"完全不同——后者会把
     * 所有空间的虚拟进程一起带走，因为虚拟进程都是从宿主 fork 出来的。
     */
    fun stopApp(packageName: String) {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { engine.stop(id, packageName) }
                // 顺手把它从 LRU 里摘掉：进程已经没了，留着它只会让
                // 下一次内存回收误判"这是用户最近在用的"而白保一个死名额
                KeeperControl.onAppRemoved(getApplication(), id, packageName)
                // 引擎只杀进程，不动 system_server 里的 TaskRecord —— 进程死了
                // 最近任务里的卡片照样躺着，用户会认为"结束运行没生效"。
                // 宿主侧按 stub intent 里的虚拟包名反查并收掉（见 TaskCleaner）。
                // 必须带上 userId：同一应用可能在多个空间里都开着，
                // 只收当前空间的那张卡片，否则另一个空间的实例会被"断掉入口"。
                withContext(Dispatchers.IO) {
                    TaskCleaner.removeTasksOf(getApplication(), packageName, taskUserIdOf(id))
                }
                _toast.value = "已结束运行：$packageName"
            }.onFailure { _toast.value = "结束运行失败：${it.message}" }
        }
    }

    // ------------------------------------------------------------- 桌面快捷方式

    fun shortcutsSupported(): Boolean =
        ShortcutPublisher.isSupported(getApplication())

    /**
     * 把一个应用钉到桌面。
     *
     * 用 [pinningPackage] 做"进行中"标记而不是全局 busy：钉的过程要读 APK 里的
     * 图标，几百毫秒起步，但用户完全可以在这期间去点别的行——只有当前这一行
     * 需要显示"进行中"。
     */
    fun pinApp(app: VirtualApp) {
        val id = _currentSpaceId.value ?: return
        if (_pinningPackage.value != null) return
        _pinningPackage.value = app.packageName
        viewModelScope.launch {
            val result = ShortcutPublisher.pinApp(getApplication(), id, app)
            _pinningPackage.value = null
            android.util.Log.i(
                "DoobleShortcut",
                "pinApp(${app.packageName}) -> ${result::class.simpleName} $result",
            )
            _toast.value = when (result) {
                is ShortcutPublisher.Result.Pinned ->
                    "已在桌面创建「${result.label}」。若没出现，请在系统确认框上点允许。"
                is ShortcutPublisher.Result.Rejected ->
                    "无法创建快捷方式：${result.reason}"
            }
        }
    }

    /** 把"打开这个空间"钉到桌面 */
    fun pinSpace() {
        val id = _currentSpaceId.value ?: return
        val space = _spaces.value.firstOrNull { it.id == id } ?: run {
            _toast.value = "空间信息还没加载出来，请稍后再试"
            return
        }
        if (_pinningPackage.value != null) return
        _pinningPackage.value = ""
        viewModelScope.launch {
            val result = ShortcutPublisher.pinSpace(getApplication(), space)
            _pinningPackage.value = null
            _toast.value = when (result) {
                is ShortcutPublisher.Result.Pinned ->
                    "已请求创建空间快捷方式。若没出现，请在系统确认框上点允许。"
                is ShortcutPublisher.Result.Rejected ->
                    "无法创建快捷方式：${result.reason}"
            }
        }
    }

    // ------------------------------------------------------------- 电源优化

    fun refreshPowerState() {
        _powerState.value = PowerOptimization.state(getApplication())
    }

    /**
     * 申请加入电池优化白名单。
     *
     * 三条路都试：直连确认框 → 优化列表页 → 都没有就如实报不支持。
     * 提示语按实际走的那条路写，**不笼统地说"已加入白名单"**——
     * 用户在列表页里还得自己找本应用，说成"已加入"会让他以为已经生效了。
     */
    fun requestIgnoreBatteryOptimizations() {
        val ctx = getApplication<Application>()
        when (PowerOptimization.request(ctx)) {
            PowerOptimization.Route.DIRECT_DIALOG ->
                _toast.value = "请在系统弹窗上点「允许」，之后空间内的应用才不会被 Doze 冻结"
            PowerOptimization.Route.SETTINGS_LIST ->
                _toast.value = "已打开电池优化列表，请在里面把「Dooble Double」设为不优化"
            PowerOptimization.Route.UNSUPPORTED ->
                _toast.value = "当前系统没有可用的电池优化入口，请到系统设置里手动查找"
        }
        // 用户可能马上就返回，这里先按当前值刷一次；真正的状态在 onResume 时再刷
        refreshPowerState()
    }

    fun autostartSettingsAvailable(): Boolean =
        PowerOptimization.hasAutostartSettings(getApplication())

    fun openAutostartSettings() {
        val ok = PowerOptimization.openAutostartSettings(getApplication<Application>())
        _toast.value = if (ok) {
            "请在「自启动 / 后台运行」列表里把 Dooble Double 打开"
        } else {
            "当前 ROM 没有已知的自启管理入口，请到系统设置里手动查找"
        }
    }

    fun romLabel(): String = PowerOptimization.romLabel()

    // ------------------------------------------------------------- 谷歌环境

    /**
     * 装配谷歌环境的**核心流程**。
     *
     * 抽出来是为了让「创建空间时自动装配」和「用户手动点装配」走**同一条路** ——
     * 两处各写一份必然走样（自动那份很容易忘了刷新自检、忘了刷新应用列表）。
     *
     * 调用方负责 `_busy` 的开关：自动装配时外层已经持有，不要在这里重复置位。
     */
    private suspend fun provisionInto(
        spaceId: String,
        huaweiVariant: Boolean = false,
        includeOptional: Boolean = false,
    ) {
        _provisionStep.value = null
        val report = DoobleContainer.provisioner.provision(
            spaceId = spaceId,
            huaweiVariant = huaweiVariant,
            includeOptional = includeOptional,
        ) { step -> _provisionStep.value = step }
        _provisionReport.value = report
        refreshApps()
        refreshSpaces()
        // 装配完立刻自检：签名伪造/设备指纹这两步是否真的落地，只有自检能回答。
        // 静默跑，结果展示在自检面板里，不弹 toast 打扰。
        runSelfCheckFor(spaceId, silent = true)
    }

    fun provisionGoogleEnvironment(huaweiVariant: Boolean = false, includeOptional: Boolean = false) {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            _busy.value = true
            provisionInto(id, huaweiVariant, includeOptional)
            _toast.value =
                if (_provisionReport.value?.success == true) "谷歌环境就绪"
                else "环境不完整，请查看自检"
            _busy.value = false
        }
    }

    fun runSelfCheck(silent: Boolean = false) {
        val id = _currentSpaceId.value ?: return
        runSelfCheckFor(id, silent)
    }

    /**
     * 对**指定空间**跑自检。
     *
     * 必须显式传 spaceId，不要读 `_currentSpaceId`：
     * 自检页自己就带着 spaceId，而 `_currentSpaceId` 是"当前选中的空间"这个
     * **另一套状态**。两者不一致时（比如从通知/深链直接进自检页），
     * 读隐式状态会去查另一个空间，甚至因为它是 null 而**静默什么都不做** ——
     * 用户点了按钮毫无反应，也看不到任何提示。这个坑真实发生过。
     */
    fun runSelfCheckFor(spaceId: String, silent: Boolean = false) {
        if (_selfCheckRunning.value) return
        _selfCheckRunning.value = true
        viewModelScope.launch {
            try {
                runCatching {
                    val r = withContext(Dispatchers.IO) { DoobleContainer.selfCheck.run(spaceId) }
                    _report.value = r
                    if (!silent && r.overall == com.dooble.gms.CheckStatus.PASS) {
                        _toast.value = "自检全部通过"
                    }
                }.onFailure { _toast.value = "自检失败：${it.message}" }
            } finally {
                _selfCheckRunning.value = false
            }
        }
    }

    fun isProvisioned(spaceId: String): Boolean =
        runCatching { DoobleContainer.selfCheck.isProvisioned(spaceId) }.getOrDefault(false)

    // ------------------------------------------------------------- 签名伪造

    fun refreshSpoof() {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            runCatching {
                val spoofer = DoobleContainer.signatureSpoof
                _spoofMode.value = withContext(Dispatchers.IO) { spoofer.currentMode(id) }
                _spoofCert.value = withContext(Dispatchers.IO) { spoofer.certInfoOf(id) }
            }
        }
    }

    /**
     * 切换签名伪造模式。
     *
     * 三种模式的语义差别很大，所以成功提示要**如实**说清楚当前能做到什么，
     * 而不是笼统地报一句"已开启"——用户最容易误解的就是
     * 「用了本地测试证书 = microG 会认」。
     */
    fun setSpoofMode(mode: SpoofMode) {
        val id = _currentSpaceId.value ?: run {
            _toast.value = "请先进入一个空间"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val spoofer = DoobleContainer.signatureSpoof
                val result = withContext(Dispatchers.IO) { spoofer.apply(id, mode) }
                _spoofMode.value = mode
                _spoofCert.value = withContext(Dispatchers.IO) { spoofer.certInfoOf(id) }

                _toast.value = result.error ?: when (mode) {
                    SpoofMode.OFF ->
                        "已关闭签名伪造。账号登录、下载安装、多开都不受影响。"
                    SpoofMode.TEST ->
                        "已启用本地测试证书（${result.targetCount} 个包）。" +
                            "容器会如实返回它，但 microG 不会认可——这张证书只用来验证伪造链路。"
                    SpoofMode.OFFICIAL ->
                        "已启用 Google 官方证书（${result.targetCount} 个包）。"
                }
            }.onFailure { _toast.value = "切换签名伪造模式失败：${it.message}" }
            _busy.value = false
        }
    }

    // ------------------------------------------------------------- 账号

    fun refreshAccounts() {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            runCatching {
                _accounts.value = withContext(Dispatchers.IO) { DoobleContainer.accounts.accounts(id) }
            }
        }
    }

    /**
     * 打开空间内的 Google 登录界面。
     *
     * **必须在容器内解析并启动**：登录界面由空间里的 microG GmsCore 提供，
     * 而空间内的包对宿主 PackageManager 不可见。之前用
     * `context.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT_SETTINGS))` 兜底，
     * 用户看到的是一个只有「添加账号」四个字、点不动的宿主系统页面。
     */
    fun signIn() {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    DoobleContainer.accounts.signIn(id)
                }
                _toast.value = if (result.ok) {
                    "已打开空间内的 Google 登录页"
                } else {
                    result.message
                }
            }.onFailure {
                _toast.value = "无法打开登录页：${it.message}"
            }
        }
    }

    fun removeAccount(name: String) {
        val id = _currentSpaceId.value ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { DoobleContainer.accounts.removeAccount(id, name) }
                refreshAccounts()
            }.onFailure { _toast.value = "移除失败：${it.message}" }
        }
    }

    // ------------------------------------------------------------- 空间保活

    /**
     * 开关空间保活。
     *
     * 关闭时会顺带清空 LRU：用户关掉保活通常意味着"我现在不需要它们常驻"，
     * 留着过期的使用顺序，下次再打开时第一次内存回收就会按旧数据误判。
     */
    fun toggleKeeper() {
        val ctx = getApplication<Application>()
        val on = !KeeperControl.isEnabled(ctx)
        KeeperControl.setEnabled(ctx, on)
        if (!on) WarmPool.clear(ctx)
        _toast.value = if (on) "已开启空间保活" else "已关闭空间保活"
    }

    /** 立即按"内存偏低"档做一次回收，用于验证策略是否符合预期 */
    fun reclaimNow() {
        val ctx = getApplication<Application>()
        if (!KeeperControl.isEnabled(ctx)) {
            _toast.value = "请先开启空间保活"
            return
        }
        KeeperControl.reclaimNow(ctx)
        _toast.value = "已触发一次回收"
    }

    fun refreshKeeper() {
        KeeperControl.refresh(getApplication())
    }

    // ------------------------------------------------------------- 杂项

    fun clearGmsCache() {
        viewModelScope.launch {
            runCatching {
                val ctx = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    com.dooble.gms.GmsProvisioner.clearCache(File(ctx.filesDir, "dooble/gms-cache"))
                }
                _toast.value = "已清理 microG 下载缓存"
            }.onFailure { _toast.value = "清理失败：${it.message}" }
        }
    }

    fun toast(message: String) {
        _toast.value = message
    }

    fun consumeToast() {
        _toast.value = null
    }

    private companion object {
        /** 「最近装入」列表的展示上限，多了没意义 */
        const val MAX_RECENT_INSTALLS = 20
    }
}
