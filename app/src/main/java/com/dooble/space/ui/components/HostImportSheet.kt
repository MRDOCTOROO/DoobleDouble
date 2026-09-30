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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dooble.engine.HostAppInfo
import com.dooble.engine.HostAppRules
import com.dooble.space.system.AppListAccess
import com.dooble.space.ui.rememberHostAppIcon
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Success

/**
 * 从宿主已安装应用里挑一个导入空间。
 *
 * 用全屏 Dialog 而不是 BottomSheet：列表通常有一两百项，需要搜索框常驻。
 */
@Composable
fun HostImportSheet(
    apps: List<HostAppInfo>,
    scanning: Boolean,
    accent: Color,
    includeSystem: Boolean,
    onlyGmsDependent: Boolean,
    visibility: HostAppRules.VisibilityVerdict?,
    onSearch: (String) -> Unit,
    onToggleSystem: (Boolean) -> Unit,
    onToggleGms: (Boolean) -> Unit,
    onImport: (String) -> Unit,
    onOpenGuide: (AppListAccess.GuideTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF5F2F0EB))
        ) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("从已安装应用导入", style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text(
                        "共 ${apps.size} 个可导入应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                }
                GlassButton(
                    text = "关闭",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    compact = true,
                )
            }

            // 搜索
            Box(Modifier.padding(horizontal = 16.dp)) {
                GlassTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    placeholder = "搜索应用名或包名…",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 筛选
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterToggle(
                    label = "包含系统应用",
                    checked = includeSystem,
                    accent = accent,
                    onCheckedChange = onToggleSystem,
                )
                FilterToggle(
                    label = "只看依赖 GMS",
                    checked = onlyGmsDependent,
                    accent = accent,
                    onCheckedChange = onToggleGms,
                )
            }

            // 说明
            GlassCard(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                tint = Color.White.copy(alpha = 0.42f),
                contentPadding = 12.dp,
            ) {
                Text(
                    text = "导入由容器直接读取系统的安装路径，不需要存储权限。宿主上的系统应用（GMS / Play 商店等）也能导入——这条路径走容器的 system 语义。注意：带「拆分包」标记的应用目前只会装入基础 APK。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                )
            }

            // 权限引导
            if (visibility?.gated == true) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.padding(horizontal = 16.dp)) {
                    AppListAccessBanner(verdict = visibility, accent = accent, onOpen = onOpenGuide)
                }
            }

            Spacer(Modifier.height(12.dp))

            // 列表
            when {
                scanning && apps.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("正在扫描已安装应用…", style = MaterialTheme.typography.bodyMedium, color = InkFaint)
                    }
                }

                apps.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无", style = MaterialTheme.typography.displayMedium, color = InkFaint)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (query.isBlank()) "没有可导入的应用" else "没有匹配「$query」的应用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSoft,
                            )
                            Spacer(Modifier.height(10.dp))
                            HostListEmptyHints(
                                includeSystem = includeSystem,
                                gated = visibility?.gated == true,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            HostAppRow(
                                app = app,
                                accent = accent,
                                onImport = { onImport(app.packageName) },
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterToggle(
    label: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.55f))
            .border(1.dp, if (checked) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .glassClickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .border(2.dp, if (checked) accent else InkFaint, RoundedCornerShape(6.dp))
                .background(if (checked) accent else Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) Ink else InkSoft,
        )
    }
}

@Composable
private fun HostAppRow(
    app: HostAppInfo,
    accent: Color,
    onImport: () -> Unit,
) {
    val icon = rememberHostAppIcon(app.packageName)

    GlassCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(icon = icon, label = app.label, accent = accent, size = 42.dp)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = InkSoft, maxLines = 1)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (app.needsGoogleEnvironment) {
                        GlassChip(
                            text = "依赖 GMS",
                            background = Success.copy(alpha = 0.14f),
                            contentColor = Success,
                        )
                    }
                    if (app.hasSplits) {
                        GlassChip(
                            text = "拆分包 ${app.splitApks.size + 1} 项 · 仅装基础包",
                            background = Color.White.copy(alpha = 0.5f),
                            contentColor = Danger,
                        )
                    }
                    if (app.isSystem) {
                        GlassChip(text = "系统应用", background = Color.White.copy(alpha = 0.5f), contentColor = InkFaint)
                    }
                    GlassChip(text = formatSize(app.sizeBytes), background = Color.White.copy(alpha = 0.5f))
                }
            }

            Spacer(Modifier.width(10.dp))

            GlassButton(
                text = "导入",
                onClick = onImport,
                variant = ButtonVariant.Accent,
                accent = accent,
                compact = true,
            )
        }
    }
}

/** 人类可读的体积（1024 进制，与系统应用信息页一致） */
internal fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024 -> "${bytes / 1024}KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
    else -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
}