package com.dooble.space.system

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dooble.space.BootState
import com.dooble.space.DoobleContainer
import kotlinx.coroutines.flow.filterIsInstance
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

        if (spaceId.isNullOrBlank()) {
            toast("快捷方式已失效：空间不存在")
            finish()
            return
        }

        // 空间快捷方式（没有包名）走不到这里——它直接指向 MainActivity。
        // 这里只处理"钉某个应用"这一种。
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val ready = withTimeoutOrNull(ENGINE_BOOT_TIMEOUT_MS) {
                DoobleContainer.boot.filterIsInstance<BootState.Ready>().first()
            }

            if (ready == null) {
                toast("容器还没准备好，请先打开 Dooble Double")
                finish()
                return@launch
            }

            val result = runCatching {
                DoobleContainer.engine.launch(spaceId, packageName)
            }.getOrElse {
                toast("启动失败：${it.message ?: it.javaClass.simpleName}")
                finish()
                return@launch
            }

            if (!result.ok) toast(result.message)
            finish()
        }
    }

    private fun toast(message: String) {
        runCatching {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /**
         * 冷启动 + 首次 native 初始化在低端机上可能要十几秒。
         * 20 秒是"够慢的机器也能过、用户不至于干等"的折中。
         */
        const val ENGINE_BOOT_TIMEOUT_MS = 20_000L
    }
}
