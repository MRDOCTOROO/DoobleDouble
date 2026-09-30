package com.dooble.space.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dooble.engine.VirtualApp
import com.dooble.space.ui.rememberVirtualAppIcon
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft

/**
 * 把空间里的应用发到桌面。
 *
 * 一次只钉一个：`requestPinShortcut` 每次弹一次系统确认框，多个请求同时到达时
 * 后面的会被启动器静默丢弃。
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
        Column(Modifier.fillMaxSize().background(Color(0xF5F2F0EB))) {

            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("发到桌面", style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text(spaceName, style = MaterialTheme.typography.labelSmall, color = InkFaint)
                }
                GlassButton(
                    text = "关闭",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    compact = true,
                )
            }

            if (!supported) {
                GlassCard(
                    Modifier.fillMaxWidth().padding(16.dp),
                    tint = Color.White.copy(alpha = 0.42f),
                    contentPadding = 14.dp,
                ) {
                    Text(
                        text = "当前启动器不支持固定快捷方式（部分精简 ROM 会移除该能力）。可以改从系统设置里手动把应用添加到桌面。",
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
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        tint = Color.White.copy(alpha = 0.42f),
                        contentPadding = 12.dp,
                    ) {
                        Text(
                            text = "每钉一个都会弹一次系统确认框——这是系统强制的。钉到桌面上的图标归启动器所有，想撤销需要在桌面上长按移除。",
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
    icon: ImageBitmap?,
    fallbackGlyph: String,
    title: String,
    subtitle: String,
    accent: Color,
    busy: Boolean,
    onClick: () -> Unit,
) {
    GlassCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = if (busy) null else onClick,
        contentPadding = 12.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(icon = icon, label = fallbackGlyph, accent = accent, size = 42.dp)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = InkSoft, maxLines = 1)
            }

            Spacer(Modifier.width(10.dp))

            GlassButton(
                text = if (busy) "…" else "钉",
                onClick = onClick,
                variant = if (busy) ButtonVariant.Secondary else ButtonVariant.Accent,
                accent = accent,
                enabled = !busy,
                compact = true,
            )
        }
    }
}

/** 桌面快捷方式在部分 ROM 上会被静默忽略 */
internal val SHORTCUT_ROM_CAVEAT: String =
    "部分国产 ROM 的桌面需要额外开启「创建桌面快捷方式」权限，否则确认框点完不会有图标出现。"