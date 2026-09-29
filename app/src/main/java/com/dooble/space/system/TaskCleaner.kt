package com.dooble.space.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * 收掉虚拟应用残留在系统「最近任务」里的卡片。
 *
 * ## 为什么需要它
 *
 * 引擎侧的 `stopPackage(pkg, userId)` 只做一件事：杀掉那个包的进程。
 * 它**不碰**容器的 `ActivityStack`，也不碰 system_server 里的 `TaskRecord`。
 * 而最近任务里的卡片是 system_server 按 stub Activity 建的任务记录 ——
 * 进程死了，任务记录照样躺着。用户看到的是「点了结束运行，卡片还在，
 * 再点一次又能把应用拉起来」，会认为结束运行没生效。
 *
 * ## 为什么在宿主侧做，而不是再打一个容器补丁
 *
 * 容器侧要修得改 `BProcessManagerService` 或新增 `BActivityManager` 能力，
 * 意味着重建 AAR（约 7 分钟）+ 再走一遍补丁幂等性验证。
 * 而任务记录是 system_server 的公开对象，宿主进程通过 `ActivityManager`
 * 就能操作自己名下的任务 —— 没必要为了这点事动容器内核。
 *
 * ## 怎么认出「这个卡片属于哪个虚拟应用」
 *
 * 虚拟 Activity 在系统眼里是宿主的 stub，任务卡片天然挂在宿主名下，
 * 所以 `getAppTasks()` 能拿到全部空间的任务。判定规则全部在
 * [VirtualTaskResolver] 里（那部分是可单测的纯逻辑）。
 */
object TaskCleaner {

    private const val TAG = "DoobleTask"

    /**
     * 移除 [packageName] 这个虚拟应用在最近任务里的全部卡片。
     *
     * @param userId 目标空间的 `bcoreUserId`（用 [VirtualTaskResolver.filterUserId]
     *   从空间记录换算）。传 null 表示**不区分空间**，把同名应用在所有空间的
     *   卡片一起收掉 —— 卸载场景就该这样（应用从所有空间都没了）。
     *   而"结束运行"只作用于一个空间，必须传具体值，否则会顺手把另一个空间里
     *   还在运行的同名应用的卡片也收掉，那个应用就再也点不开了。
     * @return 实际移除的卡片数。0 是正常结果（该应用本来就没有卡片，
     *   比如它只有后台服务没开过界面）。
     */
    fun removeTasksOf(context: Context, packageName: String, userId: Int? = null): Int {
        if (packageName.isEmpty()) return 0

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Log.w(TAG, "拿不到 ActivityManager，跳过任务清理")
            return 0
        }

        val tasks = runCatching { am.appTasks }.getOrElse {
            // 部分 ROM 会限制 getAppTasks，静默跳过即可 —— 卡片残留比崩溃好得多
            Log.w(TAG, "读取任务列表失败，跳过清理", it)
            return 0
        }

        var removed = 0
        for (task in tasks) {
            val info = runCatching { task.taskInfo }.getOrNull() ?: continue
            val base = info.baseIntent ?: continue
            val virtual = virtualTaskOf(base) ?: continue
            if (!VirtualTaskResolver.matches(virtual, packageName, userId)) continue

            val ok = runCatching { task.finishAndRemoveTask() }.isSuccess
            if (ok) {
                removed++
            } else {
                Log.w(TAG, "移除任务卡片失败：$packageName（taskId=${info.id}）")
            }
        }

        if (removed > 0) {
            Log.i(TAG, "已收掉 $removed 张任务卡片：$packageName（userId=${userId ?: "任意"}）")
        }
        return removed
    }

    /** 读三个 extra，判定交给 [VirtualTaskResolver] */
    private fun virtualTaskOf(base: Intent): VirtualTaskResolver.VirtualTask? =
        VirtualTaskResolver.resolve(
            activityInfoPackage = readParcelable<ActivityInfo>(
                base, VirtualTaskResolver.EXTRA_ACTIVITY_INFO,
            )?.packageName,
            targetPackage = readParcelable<Intent>(
                base, VirtualTaskResolver.EXTRA_TARGET,
            )?.component?.packageName,
            // hasExtra 与 getIntExtra(key, 0) 是两件事：后者在 extra 缺失时也返回 0，
            // 会把"未知"混同成"userId 就是 0"。必须用 hasExtra 区分出来。
            userId = if (base.hasExtra(VirtualTaskResolver.EXTRA_USER_ID)) {
                base.getIntExtra(VirtualTaskResolver.EXTRA_USER_ID, 0)
            } else {
                null
            },
        )

    /**
     * API 33 起 `getParcelableExtra(String)` 被废弃，但它仍然能用；
     * 这里按版本分流，避免在新系统上走已废弃的弱类型重载。
     */
    private inline fun <reified T : Parcelable> readParcelable(intent: Intent, key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { intent.getParcelableExtra(key, T::class.java) }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { intent.getParcelableExtra(key) as? T }.getOrNull()
        }
}
