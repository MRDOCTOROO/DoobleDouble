package com.dooble.space.ui.screens

import androidx.compose.runtime.Composable
import com.dooble.engine.SpaceRuntime
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