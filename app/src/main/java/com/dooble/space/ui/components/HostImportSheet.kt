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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dooble.engine.HostAppInfo
import com.dooble.engine.HostAppRules
import com.dooble.space.system.AppListAccess
import com.dooble.space.ui.rememberHostAppIcon
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Mint
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success

/**
 * 从宿主已安装应用里挑一个导入空间。
 *
 * 用全屏 Dialog 而不是 BottomSheet：这个列表通常有一两百项，需要搜索框常驻，
 * 底部抽屉的高度限制会让可用区域变得很小。
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
                .background(Paper)
        ) {
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
                        text = "从已安装应用导入",
                        style = MaterialTheme.typography.titleLarge,
                        color = Acid,
                    )
                    Text(
                        text = "共 ${apps.size} 个可导入应用",
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

            // ---------------------------------------------------- 搜索
            Box(Modifier.padding(16.dp)) {
                BrutalTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onSearch(it)
                    },
                    placeholder = "搜索应用名或包名…",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------------------------------------------------- 筛选
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterToggle(
                    label = "包含系统应用",
                    checked = includeSystem,
                    accent = accent,
                    onCheckedChange = { onToggleSystem(it) },
                )
                FilterToggle(
                    label = "只看依赖 GMS",
                    checked = onlyGmsDependent,
                    accent = accent,
                    onCheckedChange = { onToggleGms(it) },
                )
            }

            Spacer(Modifier.height(14.dp))

            // ---------------------------------------------------- 说明
            BrutalSurface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                background = PaperDeep,
                shadowOffset = 3.dp,
                contentPadding = 12.dp,
            ) {
                Text(
                    text = "导入由容器直接读取系统的安装路径，不需要存储权限。" +
                        "宿主上是系统应用的（GMS / Play 商店等）也能导入 —— " +
                        "这条路径走容器的 system 语义，容器内会以系统应用身份呈现。" +
                        "注意：带「拆分包」标记的应用（AAB 分发）目前只会装入基础 APK，" +
                        "运行时多半会缺资源——容器侧的多 APK 安装尚未实现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                )
            }

            // ---------------------------------------------------- 权限引导
            if (visibility?.gated == true) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.padding(horizontal = 16.dp)) {
                    AppListAccessBanner(
                        verdict = visibility,
                        accent = accent,
                        onOpen = onOpenGuide,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------------------------------------------------- 列表
            if (scanning && apps.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "正在扫描已安装应用…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkFaint,
                    )
                }
            } else if (apps.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("//", style = MaterialTheme.typography.displayMedium, color = InkFaint)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (query.isBlank()) "没有可导入的应用" else "没有匹配「$query」的应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSoft,
                        )
                        // 列表为空最常见的几个原因，直接说出来，别让用户以为功能坏了
                        Spacer(Modifier.height(10.dp))
                        HostListEmptyHints(
                            includeSystem = includeSystem,
                            gated = visibility?.gated == true,
                        )
                    }
                }
            } else {
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

@Composable
private fun FilterToggle(
    label: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .border(3.dp, Ink)
            .background(if (checked) accent else Paper)
            .brutalClickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 用方块而不是 Material 的 Switch：与全站的新粗野主义风格一致，
        // 而且 3dp 描边的方块在等宽字体标签旁边视觉重量更对
        Box(
            Modifier
                .size(11.dp)
                .border(2.dp, Ink)
                .background(if (checked) Ink else Paper)
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

    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = Paper,
        shadowOffset = 4.dp,
        onClick = onImport,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(
                icon = icon,
                label = app.label,
                accent = accent,
                size = 42.dp,
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (app.needsGoogleEnvironment) {
                        BrutalChip(text = "依赖 GMS", background = Mint, contentColor = Success)
                    }
                    if (app.hasSplits) {
                        // 用警示色而不是普通的 Tangerine：这个标记意味着
                        // "导进去也不会完整"，是风险提示，不是信息标签
                        BrutalChip(
                            text = "拆分包 ${app.splitApks.size + 1} 项 · 仅装基础包",
                            background = PaperDeep,
                            contentColor = Danger,
                        )
                    }
                    if (app.isSystem) {
                        BrutalChip(text = "系统应用", background = PaperDeep, contentColor = InkFaint)
                    }
                    BrutalChip(text = formatSize(app.sizeBytes), background = PaperDeep)
                }
            }

            Spacer(Modifier.width(10.dp))

            Box(
                Modifier
                    .border(3.dp, Ink)
                    .background(Acid)
                    .brutalClickable { onImport() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("导入", style = MaterialTheme.typography.labelMedium, color = Ink)
            }
        }
    }
}

/**
 * 人类可读的体积。
 *
 * 刻意用 1024 进制：用户对比的是系统"应用信息"页里的数字，
 * 那里用的就是 1024 进制，用 1000 进制会显示成两个不一样的数。
 */
internal fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024 -> "${bytes / 1024}KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
    else -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
}
