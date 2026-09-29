package com.dooble.engine

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 虚拟化引擎抽象层。
 *
 * 设计原则：上层（UI、GMS 供给层、自检）只依赖本接口，不感知底层是
 * Bcore、自研 native 容器还是降级沙箱。这样做的收益：
 *  1. 引擎可替换/可升级，业务代码零改动；
 *  2. 可以在没有真机/没有 native 引擎的环境下用降级实现跑通全链路 UI；
 *  3. 便于按空间维度做能力降级（比如某空间禁用 Xposed 以省内存）。
 */
interface VirtualEngine {

    /** 引擎标识，例如 "bcore" / "sandbox" */
    val name: String

    /** 引擎能力声明，UI 据此决定展示哪些开关 */
    val capability: EngineCapability

    /**
     * 宿主 `Application.attachBaseContext` 阶段的**同步**初始化。
     *
     * 为什么必须是同步的、且必须在这个时刻：虚拟化容器需要把宿主进程伪装成
     * 被代理应用的进程（替换 ClassLoader、伪造包名、安装 Binder 代理）。
     * 这些动作一旦晚于 `attachBaseContext`，系统已经按宿主身份完成了
     * 进程初始化，再改就来不及了——表现是虚拟应用启动后立刻闪退。
     *
     * 默认空实现：降级沙箱不需要进程伪装。
     */
    fun bootstrapHost(application: Application, base: Context) = Unit

    /** 进程级一次性初始化（加载 native、注册 hook、准备 VAServer） */
    suspend fun initialize(context: Context)

    fun isReady(): Boolean

    /** 创建一个空间，返回其运行时句柄 */
    suspend fun createSpace(config: SpaceConfig): SpaceRuntime

    suspend fun deleteSpace(spaceId: String)

    fun listSpaces(): List<SpaceRuntime>

    fun space(spaceId: String): SpaceRuntime?

    /** 把一个 APK 装进指定空间（不安装到系统） */
    suspend fun installApk(spaceId: String, apk: File, system: Boolean = false): VirtualApp

    /**
     * 把宿主**已安装**的应用装入空间（按包名）。
     *
     * 相比让用户手工挑 APK 文件，这条路径有三个优势：
     *  1. 不需要存储权限，也不产生临时文件；
     *  2. 引擎能直接读到系统的 `sourceDir` 与 `splitSourceDirs`，
     *     AAB 拆分包可被完整还原——手工挑基础 APK 做不到；
     *  3. 系统应用、预装应用同样适用。
     *
     * 默认实现抛异常，表示当前引擎不具备该能力（UI 会如实提示）。
     */
    suspend fun installHostApp(spaceId: String, packageName: String): VirtualApp =
        throw UnsupportedOperationException("当前引擎不支持按包名从宿主装入应用")

    /**
     * 查询容器内某包**对外呈现**的签名 SHA-1。
     *
     * 这条通道是容器级签名伪造的验收依据：microG 组件在容器内必须
     * 呈现为 Google 官方签名，依赖 GMS 的应用才会认它。
     * 降级引擎返回 null。
     */
    fun virtualSignatureSha1(spaceId: String, packageName: String): String? = null

    /**
     * 签名伪造的**接入点此刻是否就位**。
     *
     * 与 [EngineCapability.supportsSignatureSpoof] 的区别很关键：
     * 那个说的是"这类引擎具备该能力"，这个说的是"当前这份 AAR 里确实有接入点，
     * 且钩子已经注册"。两者都可能为 false，但排障方向完全不同——
     * 前者意味着用错了引擎，后者意味着 AAR 没打补丁、或钩子没在本进程注册。
     *
     * 默认 false：没有接入点的引擎如实回答。
     */
    fun signatureHookReady(): Boolean = false

    suspend fun uninstall(spaceId: String, packageName: String)

    fun listApps(spaceId: String): List<VirtualApp>

    /** 在空间内拉起应用 */
    suspend fun launch(spaceId: String, packageName: String): LaunchResult

    /**
     * 在空间内**解析**一个 intent，返回容器里真正能处理它的组件。
     *
     * ## 为什么需要它
     *
     * [launch] 只能按包名拉起"该应用的启动入口"。但有些场景要启动的是
     * **包内某个特定 Activity**，而它没有 LAUNCHER 入口 —— 典型例子是
     * microG 的登录界面 `org.microg.gms.auth.login.LoginActivity`，
     * 它只声明了一个自定义 action（`com.google.android.gms.auth.login.LOGIN`），
     * 不是启动入口，`launch("com.google.android.gms")` 永远打不开它。
     *
     * ## 为什么必须在容器内解析
     *
     * 空间里的包对**宿主** PackageManager 是不可见的，用
     * `context.packageManager.resolveActivity()` 查必然返回 null。
     * 这正是「点登录谷歌账号却弹出系统"添加账号"空页面」的根因 ——
     * 宿主查不到 microG 的登录界面，只好回退到系统设置页。
     *
     * 默认实现返回 null（降级引擎没有容器）。
     */
    suspend fun resolveActivityInSpace(spaceId: String, intent: Intent): ComponentName? = null

    /**
     * 在空间内启动一个 intent。
     *
     * 与 [launch] 的分工：[launch] 是"打开这个应用"，这个是"把这个 intent
     * 交给空间里的某个组件处理"。容器会用虚拟的 PMS / AMS 解析它，
     * 所以 intent 里的包名、action 都按**空间内**的视角解释。
     *
     * 默认实现返回失败（降级引擎没有容器）。
     */
    suspend fun launchIntent(spaceId: String, intent: Intent): LaunchResult =
        LaunchResult.fail("当前引擎不支持在空间内启动指定 intent")

    suspend fun stop(spaceId: String, packageName: String)

    fun runningProcesses(): List<RunningProcess>

    /**
     * 读取并清空「容器内应用自己发起的安装」的结果事件。
     *
     * 场景：空间里的应用商店（或任何应用）下载了 APK 并触发安装。
     * 这类安装发生在**虚拟应用进程**里，UI 无法同步拿到结果，因此走事件日志。
     * 默认实现返回空——降级引擎没有容器，也就没有这条路径。
     */
    fun drainInstallEvents(): List<SpaceInstallEvent> = emptyList()
}

/**
 * 容器内安装事件（已把引擎内部的 userId 还原成空间 id）。
 */
data class SpaceInstallEvent(
    /** 发起安装的空间；userId 无法对应到已知空间时为空串 */
    val spaceId: String,
    val packageName: String,
    val label: String,
    val ok: Boolean,
    val message: String,
    val timestamp: Long,
)

/** 引擎能力声明 */
data class EngineCapability(
    /** 能否在容器内提供 GMS（microG 栈） */
    val supportsGms: Boolean,
    /** 能否在容器层伪造包签名（microG 的硬前提） */
    val supportsSignatureSpoof: Boolean,
    /** 是否内置 Xposed 兼容层 */
    val supportsXposed: Boolean,
    /** 是否支持同一应用无限多开 */
    val supportsMultiInstance: Boolean,
    /** 是否具备 native 层 hook（IO 重定向 / JNI 拦截） */
    val nativeHook: Boolean,
    /** 是否支持 64 位虚拟应用 */
    val supports64Bit: Boolean,
)

data class SpaceRuntime(
    val id: String,
    val config: SpaceConfig,
    val dataDir: File,
    val state: SpaceState,
    val appCount: Int = 0,
    /** 该空间虚拟环境已占用的磁盘字节数 */
    val diskUsage: Long = 0L,
    /**
     * 该空间在容器里的虚拟 userId。`<= 0` 表示尚未分配
     * （空间刚建好还没装过东西，或引擎是 [com.dooble.engine.internal.SandboxEngine] 降级实现）。
     *
     * 之所以要暴露到运行时：系统「最近任务」里的卡片是 system_server 按 stub
     * Activity 建的，宿主侧只能靠 stub intent 里的 `_B_|_user_id_` 反查归属。
     * 没有它就只能按包名匹配 —— 同名应用在多个空间时会互相误伤。
     */
    val bcoreUserId: Int = 0,
)

enum class SpaceState {
    /** 正在创建虚拟环境 */
    CREATING,

    /** 就绪，可安装/启动应用 */
    READY,

    /** 有应用正在该空间内运行 */
    RUNNING,

    /** 环境损坏，需要重建 */
    BROKEN,
}

data class VirtualApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val installedAt: Long,
    val apkPath: String,
    val targetSdk: Int = 0,
    val usesGoogleApis: Boolean = false,
) {
    val isMicroG: Boolean
        get() = packageName in MICROG_PACKAGES

    companion object {
        val MICROG_PACKAGES = setOf(
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.maps",
        )
    }
}

data class LaunchResult(val ok: Boolean, val message: String, val pid: Int = -1) {
    companion object {
        fun ok(pid: Int = -1) = LaunchResult(true, "已启动", pid)
        fun fail(msg: String) = LaunchResult(false, msg)
    }
}

data class RunningProcess(
    val spaceId: String,
    val packageName: String,
    val pid: Int,
    val memoryKb: Long,
    val isMain: Boolean = true,
)
