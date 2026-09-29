package com.dooble.space.ui.screens

import androidx.compose.runtime.Composable
import com.dooble.engine.SpaceRuntime
import com.dooble.engine.VirtualApp
import com.dooble.space.ui.components.BrutalConfirmDialog

/*
 * 这个文件只放"不可逆操作"的确认框。
 *
 * 为什么把它们集中在这里而不是散在各个 Screen 里：判断"什么操作需要二次确认"
 * 是个跨页面的策略问题，不是某个页面的局部细节。集中放一起，
 * 新增破坏性操作时容易发现"这里已经有一套约定"，而不是各自发挥。
 *
 * 判定标准：**用户数据会消失，且没有撤销入口**。
 * 按这条：
 *   - 删除空间        → 要（见 [DeleteSpaceDialog]）
 *   - 卸载空间内应用  → 要（见 [UninstallAppDialog]）
 *   - 结束运行        → 不要（进程而已，再启动就回来了）
 *   - 移除 Google 账号 → 不要（重新登录即可恢复，且入口在账号区块内不挨着别的按钮）
 *   - 清空 microG 缓存 → 不要（只是下载缓存，用户数据不在里面）
 */

/**
 * 删除空间的二次确认。
 *
 * ## 为什么这个确认框必须存在
 *
 * 删除空间是**整个应用里唯一不可逆的操作**：它连带清掉空间内的应用、
 * 每个应用的数据目录、存储重定向产生的文件，以及已登录的 Google 账号。
 * 而这些数据用户往往已经攒了很久（登录态、游戏进度、下载的文件）。
 *
 * 两个入口都有误触风险：
 * - **空间列表卡片**：删除图标只有 28dp，且和"打开空间"同处一张卡片，
 *   手指偏几个像素就从"打开"变成"永久删除"。
 * - **空间详情页**：一个整宽的红色按钮，滑到底部时很容易顺手点到。
 *
 * 所以确认框不只是问一句"确定吗"，而是把**具体代价**摆出来
 * （几个应用、多少 MB、谷歌环境是否已装配），并强制用户先勾选
 * 才能点最终按钮。见 [BrutalConfirmDialog] 的注释。
 *
 * @param accountCount 该空间已登录的 Google 账号数。列表页拿不到这个数
 *   （账号查询只针对当前选中空间），传 0 时不渲染该行——**宁可不显示，
 *   也不显示一个猜出来的 0**。
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

    BrutalConfirmDialog(
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
 * ## 为什么卸载也要确认
 *
 * 卸载按钮是 34dp 的方块，**紧挨着同样 34dp 的「结束运行」**。这两个按钮
 * 一个可恢复（杀掉进程，再点启动就回来了）、一个不可恢复（应用数据全没）。
 * 手指偏 34dp 就是"永久"和"临时"的区别——这种布局下不加确认框是说不过去的。
 *
 * 卸载会带走应用在空间内的全部数据：登录状态、缓存、本地存档。
 * 宿主上那份安装**不受影响**（空间用的是自己的虚拟目录副本），
 * 这一点要在文案里讲清楚，否则用户会以为"卸载了空间里的就等于卸载了系统的"。
 *
 * ## microG 组件要额外警告
 *
 * 卸载 `com.google.android.gms` / `com.android.vending` 这类包，
 * 等于**亲手拆掉整个谷歌环境**：空间里所有依赖 GMS 的应用会立刻失效，
 * 而且必须重新走一遍「重新装配」。这不是普通的应用卸载，
 * 所以文案要明确说出来，不能和普通应用共用一套措辞。
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

    BrutalConfirmDialog(
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
