package com.dooble.space.system

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dooble.space.BootState
import com.dooble.space.DoobleContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桌面快捷方式的落地点。
 *
 * ## 为什么必须单独开一个 Activity，而不是让快捷方式直接指向 MainActivity
 *
 * 快捷方式的目标是"空间里的某个应用"，而拉起虚拟应用需要一个**已就绪的引擎**。
 * 从桌面冷启动时，宿主进程是全新的，`DoobleContainer.bootstrap()` 还在跑。
 * 如果直接复用 MainActivity，用户会先看到完整的空间列表界面闪一下、
 * 甚至可能手动点走，然后应用才莫名其妙地弹出来。
 *
 * 这个 Activity 的主题是透明的（`Theme.Dooble.Trampoline`），它做三件事：
 *   1. 等引擎进入 Ready（最长 20 秒）；
 *   2. 调 `engine.launch()`；
 *   3. `finish()`。
 *
 * 整个过程用户看到的是"桌面图标按下后直接进入目标应用"，中间没有闪屏。
 *
 * ## 失败必须让用户看见
 *
 * 引擎起不来、空间被删了、应用被卸载了——这些都只能用一个 Toast 告诉用户，
 * 因为此时没有任何宿主界面在屏幕上。静默 finish 会让用户以为快捷方式坏了
 * 却又说不清哪里坏了。
 */
class ShortcutLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val spaceId = intent.getStringExtra(ShortcutPublisher.EXTRA_SPACE_ID)
        val packageName = intent.getStringExtra(ShortcutPublisher.EXTRA_PACKAGE_NAME)
        val startedAt = android.os.SystemClock.elapsedRealtime()

        // 快捷方式这条链路上用户**看不到任何界面**，出了问题只能靠日志定位。
        // 每一步都留痕：没有这些日志时，"点了没反应"根本无从下手 ——
        // 分不清是启动器没把 intent 发过来、还是引擎没起来、还是目标应用没有入口。
        log("onCreate space=$spaceId pkg=$packageName")

        if (spaceId.isNullOrBlank()) {
            log("空间 id 为空，快捷方式已失效")
            toast("快捷方式已失效：空间不存在")
            finish()
            return
        }

        // 空间快捷方式（没有包名）走不到这里——它直接指向 MainActivity。
        // 这里只处理"钉某个应用"这一种。
        if (packageName.isNullOrBlank()) {
            log("没有包名，交给主界面处理")
            finish()
            return
        }

        lifecycleScope.launch {
            // ⚠️ 这里**必须同时等 Ready 和 Failed**，不能只等 Ready。
            //
            // 只等 Ready 的话，bootstrap 一旦走到 Failed（native 加载失败、
            // 引擎初始化异常……），这个 flow 就永远不会发出 Ready，
            // 于是只能靠 20 秒超时兜底 —— 用户看到一句"请先打开主程序"，
            // 然后**什么都没有发生**。快捷方式等于坏了，而且说不清哪里坏。
            //
            // 现在三种结局都有明确出口：就绪 → 拉起应用；失败 → 说清原因并
            // 退回主界面；超时 → 说明白并退回主界面。**任何一条路都不会让
            // 用户停在空白处。**
            val state = withTimeoutOrNull(ENGINE_BOOT_TIMEOUT_MS) {
                DoobleContainer.boot.first {
                    it is BootState.Ready || it is BootState.Failed
                }
            }

            when (state) {
                null -> {
                    // 超时：不装作没事，也不把用户丢在原地。打开主界面让他能看到
                    // 当前状态（主界面自己会显示 boot 进度/失败原因）。
                    log("等待引擎就绪超时（${ENGINE_BOOT_TIMEOUT_MS}ms），转主界面")
                    toast("容器启动较慢，已为你打开主界面")
                    openMain()
                    finish()
                }

                is BootState.Failed -> {
                    log("引擎启动失败：${state.message}")
                    toast("容器启动失败：${state.message}")
                    openMain()
                    finish()
                }

                else -> {
                    val bootMs = android.os.SystemClock.elapsedRealtime() - startedAt
                    log("引擎已就绪（耗时 ${bootMs}ms），开始拉起 $packageName")
                    val result = runCatching {
                        DoobleContainer.engine.launch(spaceId, packageName)
                    }.getOrElse {
                        log("launch 抛异常：${it.javaClass.simpleName}: ${it.message}")
                        toast("启动失败：${it.message ?: it.javaClass.simpleName}")
                        openMain()
                        finish()
                        return@launch
                    }

                    log("launch 结果 ok=${result.ok} msg=${result.message}")
                    if (!result.ok) {
                        toast(result.message)
                        // 启动失败时退回主界面，而不是 finish 到桌面 ——
                        // 用户刚点了一个图标，应该看到"为什么没起来"，
                        // 而不是回到桌面怀疑自己没点中。
                        openMain()
                    }
                    finish()
                }
            }
        }
    }

    /**
     * 打开主界面。
     *
     * 带 `CLEAR_TOP`：主界面可能已经在栈里（比如用户刚从那儿出来），
     * 不要叠出第二个实例。带 `NEW_TASK` 是因为本 Activity 在自己的
     * taskAffinity 上，跨任务启动必须显式声明。
     */
    private fun openMain() {
        runCatching {
            startActivity(
                Intent(this, com.dooble.space.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }

    private fun log(message: String) {
        android.util.Log.i(TAG, message)
    }

    private fun toast(message: String) {
        runCatching {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "DoobleShortcut"

        /**
         * 冷启动 + 首次 native 初始化在低端机上可能要十几秒。
         * 20 秒是"够慢的机器也能过、用户不至于干等"的折中。
         *
         * 超时不会走进死胡同：会转去主界面并把情况说清楚（见 onCreate）。
         */
        const val ENGINE_BOOT_TIMEOUT_MS = 20_000L
    }
}
