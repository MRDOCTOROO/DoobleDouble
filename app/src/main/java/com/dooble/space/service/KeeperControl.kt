package com.dooble.space.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.dooble.space.DoobleContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 空间保活的**控制面**（UI 与服务共用的那一层）。
 *
 * 拆出它的原因：保活这件事有三个互不相干的关注点——
 *   1. **服务**（[SpaceKeeperService]）只关心"进程怎么活下去"；
 *   2. **UI** 只关心"开没开、现在几个进程、刚才回收了什么"；
 *   3. **策略**（[MemoryGovernor]）只关心"该杀谁"。
 *
 * 把开关状态、钉住的空间、状态快照集中放在这里，服务和 UI 都只跟它打交道，
 * 谁也不必知道对方的生命周期。服务和 UI 同进程，所以 [status] 这个
 * StateFlow 可以直接给 Compose 用，不需要跨进程通信。
 */
object KeeperControl {

    private const val PREFS = "dooble_keeper"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PINNED = "pinned_space"

    /**
     * 保活面板的一次快照。
     *
     * [supported] 是**如实声明**的一部分：降级沙箱引擎根本没有虚拟进程，
     * 保活服务对它毫无意义。此时 UI 必须直说"当前模式不支持"，而不是
     * 让用户开着一个什么也不做的通知。
     */
    data class Status(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val supported: Boolean = false,
        val spaceCount: Int = 0,
        val processCount: Int = 0,
        val pinnedSpaceId: String? = null,
        val lastReclaim: String? = null,
        val lastReclaimAt: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 开关

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start(ctx) else stop(ctx)
        refresh(ctx)
    }

    /**
     * 拉起保活服务。
     *
     * 用 `startForegroundService` 而不是 `startService`：Android 8 起后台启动的
     * 普通 Service 会被系统在几秒内掐掉，只有前台服务才活得下来。
     * 注意 Android 12+ 禁止从后台启动前台服务，所以调用点必须是
     * 用户可见的场景（点开关、启动应用、App 刚打开）——本项目全部满足。
     */
    fun start(ctx: Context) {
        val intent = Intent(ctx, SpaceKeeperService::class.java)
            .setAction(SpaceKeeperService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, SpaceKeeperService::class.java)) }
    }

    // ---------------------------------------------------------------- 钉住

    /** 用户当前打开的空间。回收时不会动它，避免"切回来发现冷启动了" */
    fun pinnedSpace(ctx: Context): String? =
        prefs(ctx).getString(KEY_PINNED, null)?.takeIf { it.isNotBlank() }

    fun pin(ctx: Context, spaceId: String?) {
        prefs(ctx).edit().putString(KEY_PINNED, spaceId.orEmpty()).apply()
        refresh(ctx)
    }

    // ---------------------------------------------------------------- 事件

    /**
     * 用户从空间里启动了一个应用。
     *
     * 两个副作用：把它顶到 LRU 队首；如果保活开着就顺手刷一次通知，
     * 让通知上的"驻留应用数"跟得上。
     */
    fun onAppLaunched(ctx: Context, spaceId: String, packageName: String) {
        WarmPool.touch(ctx, spaceId, packageName)
        if (isEnabled(ctx)) start(ctx)
        refresh(ctx)
    }

    fun onAppRemoved(ctx: Context, spaceId: String, packageName: String) {
        WarmPool.forget(ctx, spaceId, packageName)
        refresh(ctx)
    }

    fun onSpaceRemoved(ctx: Context, spaceId: String) {
        WarmPool.forgetSpace(ctx, spaceId)
        if (pinnedSpace(ctx) == spaceId) pin(ctx, null)
        refresh(ctx)
    }

    /** 手动触发一次回收（设置页的按钮），用的是"内存偏低"档预算 */
    fun reclaimNow(ctx: Context) {
        val intent = Intent(ctx, SpaceKeeperService::class.java)
            .setAction(SpaceKeeperService.ACTION_RECLAIM)
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
    }

    // ---------------------------------------------------------------- 状态

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化刷新。并发的调用排队而不是被丢弃，否则最后一次状态可能刷不进去 */
    private val refreshLock = Mutex()

    /**
     * 重算快照。
     *
     * 必须异步：`runningProcesses()` 在真引擎里是一次跨进程查询，UI 线程上
     * 调用会直接掉帧。所以本函数只是"发起刷新"，返回时快照可能还没更新——
     * 调用方应当消费 [status] 这个 Flow，而不是指望返回即有值。
     */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            refreshLock.withLock {
                val engine = DoobleContainer.engineOrNull
                val supported = engine?.capability?.supportsMultiInstance == true

                val spaces = if (supported) {
                    runCatching { engine!!.listSpaces().size }.getOrDefault(0)
                } else {
                    0
                }
                val processes = if (supported && SpaceKeeperService.isRunning) {
                    runCatching { engine!!.runningProcesses().size }.getOrDefault(0)
                } else {
                    0
                }

                _status.value = _status.value.copy(
                    enabled = isEnabled(app),
                    running = SpaceKeeperService.isRunning,
                    supported = supported,
                    spaceCount = spaces,
                    processCount = processes,
                    pinnedSpaceId = pinnedSpace(app),
                )
            }
        }
    }

    /** 由服务在完成一次回收后调用，把结果写进快照供 UI 展示 */
    internal fun noteReclaim(ctx: Context, note: String) {
        _status.value = _status.value.copy(
            lastReclaim = note,
            lastReclaimAt = System.currentTimeMillis(),
        )
        refresh(ctx)
    }
}
