package com.dooble.space.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dooble.engine.VirtualApp
import com.dooble.space.ui.rememberVirtualAppIcon
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Success

/**
 * 长按应用图标/行弹出的操作菜单。
 *
 * 提供「打开 / 发送到桌面 / 卸载（删除数据）」，替代原来的
 * 「弹窗 → 勾选已了解 → 再点卸载」三步流程。卸载是高风险动作，
 * 但菜单里明确标注了会删除数据，用户确认后一步到位。
 */
@Composable
fun AppActionSheet(
    app: VirtualApp,
    accent: Color,
    supported: Boolean,
    onLaunch: () -> Unit,
    onPin: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val icon = rememberVirtualAppIcon(app.packageName, app.apkPath, app.versionCode)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xF7F4F1EC), RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                .padding(vertical = 10.dp)
        ) {
            // 应用信息头
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconBadge(icon = icon, label = app.label, accent = accent, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            ActionSheetItem(
                icon = Icons.Filled.PlayArrow,
                label = "打开",
                tint = Ink,
                onClick = {
                    onLaunch()
                    onDismiss()
                },
            )
            ActionSheetItem(
                icon = Icons.Filled.Star,
                label = if (supported) "发送到桌面" else "发送到桌面（当前系统不支持）",
                tint = Success,
                enabled = supported,
                onClick = {
                    onPin()
                    onDismiss()
                },
            )
            ActionSheetItem(
                icon = Icons.Filled.Delete,
                label = "卸载（删除该应用全部数据）",
                tint = Danger,
                onClick = {
                    onDismiss()
                    onUninstall()
                },
            )

            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkSoft,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ActionSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else InkFaint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) tint else InkFaint,
        )
    }
}