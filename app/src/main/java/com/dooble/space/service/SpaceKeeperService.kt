package com.dooble.space.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dooble.space.BootState
import com.dooble.space.DoobleContainer
import com.dooble.space.MainActivity
import com.dooble.space.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 空间保活服务。
 *
 * ## 为什么需要它
 *
 * 虚拟应用并不是"另一个 App 的进程"，它是**从宿主进程 fork 出来的**。
 * 这意味着宿主进程一旦被系统回收，空间里所有正在运行的应用会一起消失
 * ——用户切回来看到的是重新冷启动，甚至登录态都要重走一遍。
 *
 * 把宿主提到前台服务优先级，是免 Root 前提下唯一能显著改善这件事的手段：
 * 前台进程的 `oom_adj` 更低，系统在内存吃紧时会优先牺牲别的应用。
 * 这是多开类应用体验好坏的分水岭。
 *
 * ## 三件事
 *
 * 1. **前台通知**：让进程活下来，同时如实告诉用户"现在有几个空间在跑"。
 * 2. **进程预热**：用户从空间里点开一个应用时把它记进 [WarmPool]，
 *    既是给回收决策提供依据，也让服务在重启后能立刻恢复"谁重要"的认知。
 * 3. **内存压力回收**：系统调 [onTrimMemory] 时按 [MemoryGovernor] 的决策
 *    主动回收最不重要的虚拟进程。**主动杀**比等着系统**连宿主一起杀**要好——
 *    前者只损失一个后台应用，后者损失整个空间的运行态。
 *
 * ## 为什么不用 `START_NOT_STICKY`
 *
 * 用 `START_STICKY`：服务被系统杀掉后会被重建，重建即重新进入前台。
 * 若用 `START_NOT_STICKY`，一次内存回收之后保活就永久失效了，
 * 用户下次打开 App 才会发现"怎么又要重新加载"。
 */
class SpaceKeeperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否已经调用过 startForeground。没进前台就不能 notify，否则会重复挂通知 */
    @Volatile
    private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_RECLAIM -> {
                promote()
                reclaim(MemoryGovernor.LEVEL_MANUAL, manual = true)
            }

            else -> {
                promote()
                KeeperControl.refresh(this)
            }
        }
        return START_STICKY
    }

    /**
     * 系统在内存吃紧时会回调这里。
     *
     * 实际能收到哪些档位，取决于系统版本：API 35 起只有
     * `TRIM_MEMORY_UI_HIDDEN` 和 `TRIM_MEMORY_BACKGROUND` 还在派发，
     * 其余档位已被标记废弃（详见 [MemoryGovernor] 的说明）。
     * 对我们的场景来说这反而更好——**用户离开 App 那一刻**就是最合适的瘦身时机。
     *
     * 注意这个回调**在 UI 线程**，而回收要跨进程查进程列表再逐个杀，
     * 所以立刻丢到 IO 协程里，绝不能在这里做同步等待。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!KeeperControl.isEnabled(this)) return
        reclaim(level, manual = false)
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        // 服务没了，快照里的 running/processCount 必须跟着归零，
        // 否则设置页会一直显示"保活中"而实际已经停了
        KeeperControl.refresh(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 前台化

    private fun promote() {
        if (promoted) {
            updateNotification()
            return
        }
        val notification = buildNotification()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14 起必须显式声明前台服务类型，且要与清单里的声明一致
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                // 低于 14 时两参版本会沿用清单里声明的类型
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess

        if (!ok) {
            // 常见原因：从后台启动前台服务被 Android 12+ 拦截。
            // 这时不能硬撑——继续跑下去系统会在几秒内以 ANR 收场。
            shutdown()
            return
        }
        promoted = true
    }

    private fun shutdown() {
        promoted = false
        isRunning = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun updateNotification() {
        if (!promoted) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val status = KeeperControl.status.value
        val engine = DoobleContainer.engineOrNull
        val virtualized = engine?.capability?.supportsMultiInstance == true

        val body = when {
            !virtualized -> "降级模式：当前引擎没有可保活的虚拟进程"
            DoobleContainer.boot.value !is BootState.Ready -> "容器正在装配…"
            else -> "${status.spaceCount} 个空间 · ${status.processCount} 个应用驻留"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keeper)
            .setContentTitle("DOOBLE 空间保活中")
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                status.lastReclaim?.let { setSubText(it) }
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "空间保活",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "维持空间内的虚拟应用进程存活，避免切回时重新冷启动"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- 回收

    private fun reclaim(level: Int, manual: Boolean) {
        scope.launch {
            val engine = DoobleContainer.engineOrNull ?: return@launch
            if (!engine.capability.supportsMultiInstance) {
                if (manual) KeeperControl.noteReclaim(this@SpaceKeeperService, "当前模式没有虚拟进程")
                return@launch
            }

            val running = runCatching { engine.runningProcesses() }.getOrDefault(emptyList())
            if (running.isEmpty()) {
                if (manual) KeeperControl.noteReclaim(this@SpaceKeeperService, "没有运行中的虚拟进程")
                return@launch
            }

            val plan = MemoryGovernor.plan(
                trimLevel = level,
                running = running,
                lru = WarmPool.order(this@SpaceKeeperService),
                pinnedSpaceId = KeeperControl.pinnedSpace(this@SpaceKeeperService),
            )

            if (!plan.changed) {
                if (manual) {
                    KeeperControl.noteReclaim(
                        this@SpaceKeeperService,
                        "无需回收（${running.size} 个进程都在预算内）",
                    )
                }
                return@launch
            }

            var killed = 0
            plan.evict.forEach { process ->
                // 进程名可能是 com.foo.bar:remote，停止接口要的是包名
                val pkg = process.packageName.substringBefore(':')
                if (pkg.isBlank()) return@forEach
                val ok = runCatching { engine.stop(process.spaceId, pkg) }.isSuccess
                if (ok) killed++
            }

            KeeperControl.noteReclaim(
                this@SpaceKeeperService,
                "${MemoryGovernor.noteFor(level)} → 回收 $killed 个进程",
            )
            updateNotification()
        }
    }

    companion object {

        const val ACTION_START = "com.dooble.space.keeper.START"
        const val ACTION_RECLAIM = "com.dooble.space.keeper.RECLAIM"
        const val ACTION_STOP = "com.dooble.space.keeper.STOP"

        private const val CHANNEL_ID = "dooble.keeper"
        private const val NOTIFICATION_ID = 0x0D00B1

        /**
         * 服务是否在跑。
         *
         * 服务和 UI 同进程，所以一个静态标记就够用，不必走 Binder 查询。
         * 若将来把服务拆到独立进程，这里要换成 `ActivityManager.getRunningServices`。
         */
        @Volatile
        var isRunning: Boolean = false
            internal set
    }
}
