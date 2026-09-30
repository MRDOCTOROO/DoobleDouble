package com.dooble.space.ui.screens

import androidx.compose.runtime.Composable
import com.dooble.engine.SpaceRuntime
import com.dooble.engine.VirtualApp
import com.dooble.space.ui.components.GlassConfirmDialog

/*
 * 这个文件只放"不可逆操作"的确认框。
 *
 * 为什么把它们集中在这里而不是散在各个 Screen 里：判断"什么操作需要二次确认"
 * 是个跨页面的策略问题，不是某个页面的局部决定。集中放一起，
 * 新增破坏性操作时容易发现"这里已经有一套约定"，而不是各自发挥。
 *
 * 判定标准：**用户数据会消失，且没有撤销入口**。
 */

/**
 * 删除空间的二次确认。
 *
 * 删除空间是**整个应用里唯一不可逆的操作**：它连带清掉空间内的应用、
 * 每个应用的数据目录、存储重定向产生的文件，以及已登录的 Google 账号。
 * 确认框把具体代价摆出来（几个应用、多少 MB、谷歌环境是否已装配），
 * 并强制用户先勾选才能点最终按钮。
 *
 * @param accountCount 该空间已登录的 Google 账号数。列表页拿不到这个数
 *   （账号查询只针对当前选中空间），传 0 时不渲染该行。
 */
@Composable
internal fun DeleteSpaceDialog(
    space: SpaceRuntime,
    provisioned: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    accountCount: Int = 0,
) {
    val facts = buildList {
        add("空间名称" to space.config.name)
        add("已装应用" to "${space.appCount} 个")
        add("占用磁盘" to formatBytes(space.diskUsage))
        add(
            "谷歌环境" to when {
                !space.config.googleEnvironment -> "未启用"
                provisioned -> "已装配（microG 栈）"
                else -> "未装配"
            }
        )
        if (accountCount > 0) add("已登录账号" to "$accountCount 个")
    }

    GlassConfirmDialog(
        title = "删除空间",
        message = "这个操作不可撤销。空间内的应用、每个应用的数据目录、" +
            "存储重定向产生的文件都会被一并清除，无法找回。",
        facts = facts,
        ackText = "我已了解：删除后无法恢复，空间内的一切都会永久消失",
        confirmText = "永久删除",
        dismissText = "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * 卸载空间内应用的二次确认。
 *
 * 卸载会带走应用在空间内的全部数据：登录状态、缓存、本地存档。
 * 宿主上那份安装不受影响。microG 组件（GMS/FakeStore）卸载等于拆掉
 * 整个谷歌环境，文案必须单独说明。
 */
@Composable
internal fun UninstallAppDialog(
    app: VirtualApp,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isGoogleStack = app.isMicroG

    val facts = buildList {
        add("应用名称" to app.label)
        add("包名" to app.packageName)
        add("版本" to "v${app.versionName}")
        if (app.usesGoogleApis) add("依赖 GMS" to "是")
    }

    val message = if (isGoogleStack) {
        "这是谷歌环境的组成部分。卸载后空间里所有依赖 GMS 的应用都会立刻失效，" +
            "必须重新执行一次「重新装配」才能恢复。应用在空间内的数据也会一并删除。"
    } else {
        "卸载会连同这个应用在空间内的数据一起删除——登录状态、缓存、本地存档都找不回来。" +
            "宿主上安装的那一份不受影响。"
    }

    GlassConfirmDialog(
        title = if (isGoogleStack) "卸载谷歌环境组件" else "卸载应用",
        message = message,
        facts = facts,
        ackText = if (isGoogleStack) {
            "我已了解：这会破坏空间的谷歌环境，需要重新装配才能恢复"
        } else {
            "我已了解：该应用在空间内的数据会被永久删除"
        },
        confirmText = "卸载",
        dismissText = "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}