package com.dooble.space.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dooble.engine.DeviceSpoofProfile
import com.dooble.engine.SpaceRuntime
import com.dooble.space.BootState
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.components.BrutalButton
import com.dooble.space.ui.components.BrutalChip
import com.dooble.space.ui.components.BrutalConfirmDialog
import com.dooble.space.ui.components.BrutalRadioRow
import com.dooble.space.ui.components.BrutalSurface
import com.dooble.space.ui.components.BrutalTextField
import com.dooble.space.ui.components.DoobleTopBar
import com.dooble.space.ui.components.EmptyState
import com.dooble.space.ui.components.KeyValueRow
import com.dooble.space.ui.components.SectionRule
import com.dooble.space.ui.components.StatBlock
import com.dooble.space.ui.components.brutalClickable
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.HotPink
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Mint
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success
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
    // 卡片上那个 28dp 的删除图标和"打开空间"是同一个点击区域，
    // 手指偏几个像素就是不可逆的数据销毁。所以这里只记录"想删哪个"，
    // 真正的删除必须再走一遍确认对话框。
    var pendingDelete by remember { mutableStateOf<SpaceRuntime?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        DoobleTopBar(
            title = "Dooble Double",
            subtitle = "多开空间 · 完整谷歌环境",
            accent = Acid,
            actions = {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(3.dp, Ink)
                        .background(PaperDeep)
                        .brutalClickable { onOpenSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Ink)
                }
            },
        )

        // 装饰性跑马灯条，纯视觉
        MarqueeStrip()

        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            if (boot.degraded) {
                DegradedBanner()
                Spacer(Modifier.height(16.dp))
            }

            // 统计区
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatBlock(
                    value = spaces.size.toString().padStart(2, '0'),
                    label = "空间",
                    accent = accentOf(0),
                )
                StatBlock(
                    value = spaces.sumOf { it.appCount }.toString().padStart(2, '0'),
                    label = "已装应用",
                    accent = HotPink,
                )
                StatBlock(
                    value = formatBytes(spaces.sumOf { it.diskUsage }),
                    label = "磁盘占用",
                    accent = Mint,
                )
            }

            Spacer(Modifier.height(24.dp))

            SectionRule(label = "空间列表", trailing = "${spaces.size} 个")

            Spacer(Modifier.height(14.dp))

            if (spaces.isEmpty()) {
                BrutalSurface(
                    Modifier.fillMaxWidth(),
                    background = PaperDeep,
                    contentPadding = 24.dp,
                ) {
                    EmptyState(
                        title = "还没有空间",
                        hint = "创建一个空间，装上应用和谷歌环境",
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            BrutalButton(
                text = "＋  新建空间",
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth(),
                background = Ink,
                contentColor = Acid,
            )

            Spacer(Modifier.height(28.dp))

            // 引擎信息
            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    SectionRule("运行环境")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("虚拟化引擎", boot.engineName + if (boot.degraded) "（降级）" else "")
                    KeyValueRow("Native 层", if (boot.nativeAvailable) "已加载 v${boot.nativeVersion}" else "未加载")
                    KeyValueRow("IO 重定向", if (boot.nativeAvailable) "可用" else "不可用")
                }
            }

            Spacer(Modifier.height(40.dp))
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
        val provisioned = vm.isProvisioned(target.id)
        DeleteSpaceDialog(
            space = target,
            provisioned = provisioned,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                vm.deleteSpace(target.id)
                pendingDelete = null
            },
        )
    }
}

// ------------------------------------------------------------------ 子组件

@Composable
private fun MarqueeStrip() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink)
            .padding(vertical = 5.dp)
    ) {
        Text(
            text = "  GOOGLE SERVICES  ·  MULTI-INSTANCE  ·  SANDBOX  ·  MICROG  ·  NO ROOT  ·  ISOLATED STORAGE  ",
            style = MaterialTheme.typography.labelSmall,
            color = Acid,
            maxLines = 1,
        )
    }
}

@Composable
private fun DegradedBanner() {
    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = Acid,
        shadowOffset = 4.dp,
        contentPadding = 14.dp,
    ) {
        Column {
            Text("当前为沙箱模式", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "工程里还没有接入 native 虚拟化引擎（Bcore），因此：\n" +
                    "· 空间与谷歌环境的编排逻辑可以正常走通\n" +
                    "· 但未安装到系统的 APK 无法在容器内真正运行\n" +
                    "把 Bcore 的 AAR 放进 engine/libs/ 即可自动启用。",
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
            )
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
    val rotation = if (index % 2 == 0) -0.5f else 0.4f

    BrutalSurface(
        modifier = Modifier.fillMaxWidth(),
        background = Paper,
        rotation = rotation,
        onClick = onOpen,
        shadowOffset = 7.dp,
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 左侧色条
            Box(Modifier.width(10.dp).height(IntrinsicSize.Min).background(accent))

            Column(Modifier.weight(1f).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = space.config.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .border(2.dp, Ink)
                            .background(Paper)
                            .brutalClickable { onDelete() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = Danger,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrutalChip(
                        text = if (space.config.googleEnvironment) {
                            if (provisioned) "谷歌环境 · 就绪" else "谷歌环境 · 待装配"
                        } else {
                            "无谷歌环境"
                        },
                        background = if (space.config.googleEnvironment && provisioned) Mint else PaperDeep,
                        contentColor = if (space.config.googleEnvironment && provisioned) Success else Ink,
                    )
                    BrutalChip(text = "${space.appCount} 应用")
                    if (space.config.deviceSpoof.enabled) {
                        BrutalChip(text = space.config.deviceSpoof.model, background = accent.copy(alpha = 0.25f))
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "ID ${space.id}   ·   ${formatBytes(space.diskUsage)}   ·   ${space.state.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}

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
        BrutalSurface(
            modifier = Modifier.fillMaxWidth(),
            background = Paper,
            shadowOffset = 8.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("新建空间", style = MaterialTheme.typography.displayMedium, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "每个空间是独立的虚拟 Android 环境",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )

                Spacer(Modifier.height(16.dp))
                SectionRule("名称")
                Spacer(Modifier.height(8.dp))
                BrutalTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "例如：工作 / 游戏小号",
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                SectionRule("主题色")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(7) { i ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .border(if (accent == i) 4.dp else 2.dp, Ink)
                                .background(accentOf(i))
                                .brutalClickable { accent = i }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionRule("设备指纹")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEachIndexed { i, p ->
                        BrutalRadioRow(
                            selected = profileIndex == i,
                            title = p.first,
                            subtitle = p.second,
                            accent = accentOf(accent),
                            onClick = { profileIndex = i },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionRule("选项")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrutalRadioRow(
                        selected = google,
                        title = "启用谷歌环境",
                        subtitle = "装配 microG（GmsCore + FakeStore），可登录 Google 账号",
                        accent = accentOf(accent),
                        onClick = { google = !google },
                    )
                    BrutalRadioRow(
                        selected = redirect,
                        title = "存储重定向",
                        subtitle = "把 /sdcard 写入隔离到空间私有目录，避免多开数据串号",
                        accent = accentOf(accent),
                        onClick = { redirect = !redirect },
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrutalButton(
                        text = "取消",
                        onClick = onDismiss,
                        background = PaperDeep,
                        contentColor = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    BrutalButton(
                        text = "创建",
                        onClick = { onConfirm(name, accent, google, profiles[profileIndex].third, redirect) },
                        background = Ink,
                        contentColor = Acid,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
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
