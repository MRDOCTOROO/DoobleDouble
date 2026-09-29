package com.dooble.space.system

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.dooble.engine.SpaceRuntime
import com.dooble.engine.VirtualApp
import com.dooble.space.R
import com.dooble.space.ui.AppIconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把空间里的应用"发到桌面"。
 *
 * ## 为什么用 requestPinShortcut 而不是 setDynamicShortcuts
 *
 * 两者是完全不同的东西：
 *  - `setDynamicShortcuts` 只让快捷方式出现在**长按图标弹出的菜单**里，
 *    桌面上一片空白。用户说的"创建桌面快捷方式"不是这个。
 *  - `requestPinShortcut` 才会向启动器请求在**桌面上**钉一个图标，
 *    代价是必须弹一次系统确认框（这是系统强制的，应用无法绕过）。
 *
 * 所以本类的每一个方法都对应**一次用户确认**。刻意不做"批量钉 N 个"：
 * 多个钉请求同时到达启动器时，后面的会被静默丢弃，用户会以为功能坏了。
 * 让 UI 一次只钉一个，是唯一诚实的做法。
 *
 * ## 图标来源
 *
 * 复用宿主侧的 [AppIconLoader]——它读的是容器虚拟目录里那份 APK，
 * 与空间内实际运行的是同一个文件，所以桌面上看到的图标和容器里完全一致。
 * 拿不到图标时退回首字母方块（`IconCompat` 不接受 null，必须给点东西）。
 */
object ShortcutPublisher {

    /** 由 [ShortcutLaunchActivity] 消费 */
    const val EXTRA_SPACE_ID = "com.dooble.space.extra.SPACE_ID"
    const val EXTRA_PACKAGE_NAME = "com.dooble.space.extra.PACKAGE_NAME"
    const val EXTRA_SPACE_NAME = "com.dooble.space.extra.SPACE_NAME"

    private const val ID_APP_PREFIX = "dooble.app."
    private const val ID_SPACE_PREFIX = "dooble.space."

    /** 钉一个快捷方式的结果。**不抛异常**——UI 只需要一个能直接展示的结论 */
    sealed interface Result {
        data class Pinned(val label: String) : Result
        data class Rejected(val reason: String) : Result
    }

    fun isSupported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }
            .getOrDefault(false)

    /** 把空间内的某个应用钉到桌面 */
    suspend fun pinApp(
        context: Context,
        spaceId: String,
        app: VirtualApp,
    ): Result = withContext(Dispatchers.IO) {
        if (!isSupported(context)) {
            return@withContext Result.Rejected("当前启动器不支持固定快捷方式")
        }

        val icon = runCatching {
            AppIconLoader.forVirtualApp(context, app.packageName, app.apkPath)
                ?.asAndroidBitmap()
        }.getOrNull()

        val target = Intent(context, ShortcutLaunchActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_SPACE_ID, spaceId)
            putExtra(EXTRA_PACKAGE_NAME, app.packageName)
            // 启动器从别的任务里发出这条 PendingIntent，不带 NEW_TASK 会被塞进
            // 启动器自己的任务栈（返回键行为错乱、任务卡片里出现一条空记录）。
            // pinSpace 那边一直带着它，这里补齐保持一致。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        dispatch(
            context = context,
            id = ID_APP_PREFIX + stableId(spaceId, app.packageName),
            label = app.label,
            icon = icon,
            target = target,
        )
    }

    /** 把"打开这个空间"钉到桌面 */
    suspend fun pinSpace(context: Context, space: SpaceRuntime): Result =
        withContext(Dispatchers.IO) {
            if (!isSupported(context)) {
                return@withContext Result.Rejected("当前启动器不支持固定快捷方式")
            }

            // 空间本身没有图标，用启动器图标代替：桌面上至少能认出来是 Dooble
            val icon = runCatching {
                AppIconLoader.fromHostPackage(context, context.packageName)?.asAndroidBitmap()
            }.getOrNull()

            val target = Intent(context, com.dooble.space.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra(EXTRA_SPACE_ID, space.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            dispatch(
                context = context,
                id = ID_SPACE_PREFIX + stableId(space.id, ""),
                label = space.config.name,
                icon = icon,
                target = target,
            )
        }

    private fun dispatch(
        context: Context,
        id: String,
        label: String,
        icon: android.graphics.Bitmap?,
        target: Intent,
    ): Result {
        val shortLabel = label.trim().ifBlank { "未命名" }.take(24)

        val builder = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(shortLabel)
            .setIntent(target)

        // IconCompat 不接受 null。拿不到真图标时**绝不能**退回宿主自己的启动图标 ——
        // 那会让桌面上出现一个"看起来就是 Dooble"的方块，用户完全分不清这个
        // 快捷方式是哪个应用，会直接认为"图标全被双开软件替换了"。
        // 改用按标签生成的首字母方块：可区分、且诚实表达"图标没读到"。
        builder.setIcon(
            icon?.let { IconCompat.createWithBitmap(it) }
                ?: IconCompat.createWithBitmap(
                    AppIconLoader.letterTile(shortLabel).asAndroidBitmap()
                )
        )

        return runCatching {
            val ok = ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null)
            if (ok) Result.Pinned(shortLabel)
            else Result.Rejected("启动器拒绝了本次请求")
        }.getOrElse { Result.Rejected(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * 快捷方式 id 必须稳定：同一个 (空间, 应用) 反复钉，应当**覆盖**旧条目
     * 而不是在启动器里堆出一串重复项。
     *
     * 用哈希而不是拼原文，是因为 id 有长度上限，而包名 + UUID 很容易超。
     */
    private fun stableId(a: String, b: String): String =
        Integer.toHexString((a + '\u0000' + b).hashCode())
}
