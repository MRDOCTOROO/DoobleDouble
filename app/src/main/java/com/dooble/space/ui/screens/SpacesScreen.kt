package com.dooble.space.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dooble.engine.DeviceSpoofProfile
import com.dooble.engine.SpaceRuntime
import com.dooble.space.BootState
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.components.AppIconBadge
import com.dooble.space.ui.components.AuroraBackground
import com.dooble.space.ui.components.ButtonVariant
import com.dooble.space.ui.components.GlassButton
import com.dooble.space.ui.components.GlassCard
import com.dooble.space.ui.components.GlassChip
import com.dooble.space.ui.components.GlassConfirmDialog
import com.dooble.space.ui.components.GlassIconButton
import com.dooble.space.ui.components.GlassRadioRow
import com.dooble.space.ui.components.GlassSection
import com.dooble.space.ui.components.GlassTextField
import com.dooble.space.ui.components.GlassTopBar
import com.dooble.space.ui.components.HairlineDivider
import com.dooble.space.ui.components.KeyValueRow
import com.dooble.space.ui.components.StatCell
import com.dooble.space.ui.components.StatusDot
import com.dooble.space.ui.components.glassClickable
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.MorandiBlue
import com.dooble.space.ui.theme.MorandiSage
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Warning
import com.dooble.space.ui.theme.accentOf

@Composable
fun SpacesScreen(
    vm: SpaceViewModel,
    boot: BootState.Ready,
    onOpenSpace: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spaces by vm.spaces.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SpaceRuntime?>(null) }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GlassTopBar(
                title = "多开空间",
                subtitle = "Dooble Double · 完整谷歌环境",
                largeTitle = true,
                actions = {
                    GlassIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = "设置",
                        onClick = onOpenSettings,
                    )
                },
            )

            Column(Modifier.padding(horizontal = 16.dp)) {

                // -------------------------------------------------- 概览
                OverviewCard(
                    spaceCount = spaces.size,
                    appCount = spaces.sumOf { it.appCount },
                    diskUsage = formatBytes(spaces.sumOf { it.diskUsage }),
                )

                Spacer(Modifier.height(8.dp))

                if (boot.degraded) {
                    DegradedBanner()
                    Spacer(Modifier.height(8.dp))
                }

                // -------------------------------------------------- 空间列表
                GlassSection(
                    title = "我的空间",
                    subtitle = "点击进入 · 右侧图标删除",
                    icon = Icons.Filled.Add,
                    accent = accentOf(0),
                    initiallyExpanded = true,
                ) {
                    if (spaces.isEmpty()) {
                        GlassCard(
                            Modifier.fillMaxWidth(),
                            contentPadding = 20.dp,
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    "还没有空间，点击下方按钮创建一个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSoft,
                                )
                            }
                        }
                    } else {
                        spaces.forEachIndexed { index, space ->
                            SpaceCard(
                                space = space,
                                index = index,
                                provisioned = vm.isProvisioned(space.id),
                                onOpen = { onOpenSpace(space.id) },
                                onDelete = { pendingDelete = space },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    GlassButton(
                        text = "新建空间",
                        onClick = { showCreate = true },
                        variant = ButtonVariant.Accent,
                        accent = accentOf(spaces.size),
                        leading = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = Ink,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // -------------------------------------------------- 引擎状态
                GlassSection(
                    title = "运行环境",
                    subtitle = "引擎与 Native 层状态",
                    icon = Icons.Filled.Info,
                    accent = MorandiBlue,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow("虚拟化引擎", boot.engineName + if (boot.degraded) "（沙箱）" else "")
                        HairlineDivider()
                        KeyValueRow("Native 层", if (boot.nativeAvailable) "已加载 v${boot.nativeVersion}" else "未加载")
                        HairlineDivider()
                        KeyValueRow("IO 重定向", if (boot.nativeAvailable) "可用" else "不可用")
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showCreate) {
        CreateSpaceDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, accent, google, profile, redirect ->
                vm.createSpace(name, accent, google, profile, redirect)
                showCreate = false
            },
        )
    }

    pendingDelete?.let { target ->
        DeleteSpaceDialog(
            space = target,
            provisioned = vm.isProvisioned(target.id),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                vm.deleteSpace(target.id)
                pendingDelete = null
            },
        )
    }
}

// ------------------------------------------------------------------ 工具

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0fK".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1fM".format(bytes / 1048576.0)
    else -> "%.2fG".format(bytes / 1073741824.0)
}

// ------------------------------------------------------------------ 子组件

@Composable
private fun OverviewCard(spaceCount: Int, appCount: Int, diskUsage: String) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                value = spaceCount.toString().padStart(2, '0'),
                label = "空间",
                accent = accentOf(0),
            )
            StatCell(
                value = appCount.toString().padStart(2, '0'),
                label = "已装应用",
                accent = MorandiSage,
            )
            StatCell(
                value = diskUsage,
                label = "磁盘占用",
                accent = MorandiBlue,
            )
        }
    }
}

@Composable
private fun DegradedBanner() {
    GlassCard(
        Modifier.fillMaxWidth(),
        tint = Warning.copy(alpha = 0.12f),
        borderColor = Warning.copy(alpha = 0.35f),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StatusDot(Warning)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("当前为沙箱模式", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "未接入 native 虚拟化引擎（Bcore），空间与谷歌环境的编排逻辑可正常走通，但未安装到系统的 APK 无法在容器内真正运行。把 Bcore 的 AAR 放进 engine/libs/ 即可自动启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }
        }
    }
}

@Composable
private fun SpaceCard(
    space: SpaceRuntime,
    index: Int,
    provisioned: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = accentOf(space.config.accent)
    val googleReady = space.config.googleEnvironment && provisioned

    GlassCard(
        Modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(
                icon = null,
                label = space.config.name,
                accent = accent,
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = space.config.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassChip(
                        text = if (space.config.googleEnvironment) {
                            if (provisioned) "谷歌就绪" else "待装配"
                        } else {
                            "无谷歌"
                        },
                        background = if (googleReady) Success.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.6f),
                        contentColor = if (googleReady) Success else InkSoft,
                    )
                    GlassChip(text = "${space.appCount} 应用")
                    if (space.config.deviceSpoof.enabled) {
                        GlassChip(text = space.config.deviceSpoof.model, background = accent.copy(alpha = 0.16f))
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            GlassIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = "删除空间",
                onClick = onDelete,
                tint = Danger,
                background = Color.White.copy(alpha = 0.5f),
                size = 32.dp,
            )
        }
    }
}

// ------------------------------------------------------------------ 新建空间

@Composable
private fun CreateSpaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Boolean, DeviceSpoofProfile, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var accent by remember { mutableStateOf(0) }
    var google by remember { mutableStateOf(true) }
    var redirect by remember { mutableStateOf(true) }
    var profileIndex by remember { mutableStateOf(0) }

    val profiles = listOf(
        Triple("Google Pixel 8 Pro", "husky · Android 14 · 认证成功率最高", DeviceSpoofProfile.PIXEL_8_PRO),
        Triple("Google Pixel 7", "panther · Android 14", DeviceSpoofProfile.PIXEL_7),
        Triple("Xiaomi 14", "houji · Android 14", DeviceSpoofProfile.XIAOMI_14),
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Text("新建空间", style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text(
                    "每个空间是独立的虚拟 Android 环境",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )

                Spacer(Modifier.height(16.dp))
                Text("名称", style = MaterialTheme.typography.labelMedium, color = InkSoft)
                Spacer(Modifier.height(6.dp))
                GlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "例如：工作 / 游戏小号",
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Text("主题色", style = MaterialTheme.typography.labelMedium, color = InkSoft)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(7) { i ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(accentOf(i), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .border(
                                    if (accent == i) 2.dp else 1.dp,
                                    if (accent == i) Ink else Color.White.copy(alpha = 0.7f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                )
                                .glassClickable { accent = i }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("设备指纹", style = MaterialTheme.typography.labelMedium, color = InkSoft)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEachIndexed { i, p ->
                        GlassRadioRow(
                            selected = profileIndex == i,
                            title = p.first,
                            subtitle = p.second,
                            accent = accentOf(accent),
                            onClick = { profileIndex = i },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("选项", style = MaterialTheme.typography.labelMedium, color = InkSoft)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassRadioRow(
                        selected = google,
                        title = "启用谷歌环境",
                        subtitle = "装配 microG（GmsCore + FakeStore），可登录 Google 账号",
                        accent = accentOf(accent),
                        onClick = { google = !google },
                    )
                    GlassRadioRow(
                        selected = redirect,
                        title = "存储重定向",
                        subtitle = "把 /sdcard 写入隔离到空间私有目录，避免多开数据串号",
                        accent = accentOf(accent),
                        onClick = { redirect = !redirect },
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        text = "取消",
                        onClick = onDismiss,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(
                        text = "创建",
                        onClick = { onConfirm(name, accent, google, profiles[profileIndex].third, redirect) },
                        variant = ButtonVariant.Accent,
                        accent = accentOf(accent),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}