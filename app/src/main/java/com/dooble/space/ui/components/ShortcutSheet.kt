package com.dooble.space.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dooble.engine.VirtualApp
import com.dooble.space.ui.rememberVirtualAppIcon
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep

/**
 * 把空间里的应用发到桌面。
 *
 * ## 为什么一次只能钉一个
 *
 * `requestPinShortcut` 每次都会弹一次系统确认框，且**多个请求同时到达时
 * 后面的会被启动器静默丢弃**。所以这里不做"全选 + 一键发送"，
 * 而是每个应用一个按钮、点了就钉一个。用户点几下就是钉几个，
 * 行为与他的操作一一对应，不会出现"点了 5 个只成了 2 个"的迷惑结果。
 *
 * ## 为什么钉完还要提示"图标归启动器所有"
 *
 * 钉到桌面上的图标属于启动器，应用无权删除（系统有意如此，防止应用偷改用户桌面）。
 * 用户如果想撤销，只能自己在桌面上长按移除。UI 必须提前讲清楚，
 * 否则用户会来找"取消"按钮，然后发现根本没有。
 */
@Composable
fun ShortcutSheet(
    spaceName: String,
    apps: List<VirtualApp>,
    supported: Boolean,
    accent: Color,
    pinningPackage: String?,
    onPinSpace: () -> Unit,
    onPinApp: (VirtualApp) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Paper)) {

            // ---------------------------------------------------- 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Ink)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "发到桌面",
                        style = MaterialTheme.typography.titleLarge,
                        color = Acid,
                    )
                    Text(
                        text = spaceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = PaperDeep,
                    )
                }
                Box(
                    Modifier
                        .border(3.dp, Acid)
                        .background(Ink)
                        .brutalClickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("关闭", style = MaterialTheme.typography.labelMedium, color = Acid)
                }
            }

            if (!supported) {
                BrutalSurface(
                    Modifier.fillMaxWidth().padding(16.dp),
                    background = PaperDeep,
                    shadowOffset = 3.dp,
                    contentPadding = 14.dp,
                ) {
                    Text(
                        text = "当前启动器不支持固定快捷方式（部分精简 ROM 会移除该能力）。" +
                            "可以改从系统设置里手动把应用添加到桌面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                }
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    BrutalSurface(
                        Modifier.fillMaxWidth(),
                        background = PaperDeep,
                        shadowOffset = 3.dp,
                        contentPadding = 12.dp,
                    ) {
                        Text(
                            text = "每钉一个都会弹一次系统确认框——这是系统强制的，应用绕不过去。" +
                                "钉到桌面上的图标归启动器所有，想撤销需要在桌面上长按移除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSoft,
                        )
                    }
                }

                item {
                    PinRow(
                        icon = null,
                        fallbackGlyph = "▣",
                        title = "打开「$spaceName」",
                        subtitle = "进入这个空间的详情页",
                        accent = accent,
                        busy = false,
                        onClick = onPinSpace,
                    )
                }

                items(apps, key = { it.packageName }) { app ->
                    PinRow(
                        icon = rememberVirtualAppIcon(app.packageName, app.apkPath, app.versionCode),
                        fallbackGlyph = app.label.trim().take(1).uppercase().ifBlank { "?" },
                        title = app.label,
                        subtitle = app.packageName,
                        accent = accent,
                        busy = pinningPackage == app.packageName,
                        onClick = { onPinApp(app) },
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun PinRow(
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    fallbackGlyph: String,
    title: String,
    subtitle: String,
    accent: Color,
    busy: Boolean,
    onClick: () -> Unit,
) {
    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = Paper,
        shadowOffset = 4.dp,
        onClick = if (busy) null else onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(
                icon = icon,
                label = fallbackGlyph,
                accent = accent,
                size = 42.dp,
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(10.dp))

            Box(
                Modifier
                    .border(3.dp, Ink)
                    .background(if (busy) PaperDeep else Acid)
                    .brutalClickable(enabled = !busy) { onClick() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (busy) "…" else "钉",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (busy) InkFaint else Ink,
                )
            }
        }
    }
}

/** 桌面快捷方式在部分 ROM 上会被静默忽略，给 UI 一句如实的提示文案 */
internal val SHORTCUT_ROM_CAVEAT: String =
    "部分国产 ROM 的桌面需要额外开启「创建桌面快捷方式」权限，否则确认框点完不会有图标出现。"
