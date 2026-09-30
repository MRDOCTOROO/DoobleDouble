package com.dooble.space.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dooble.engine.HostAppRules
import com.dooble.engine.VirtualApp
import com.dooble.gms.GoogleStack
import com.dooble.gms.MicroGComponent
import com.dooble.space.system.AppListAccess
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Warning

/**
 * 谷歌组件槽位面板。
 *
 * 每个槽位有三个来源：官方直链（microG 系）、从宿主克隆（含系统应用）、
 * 导入 APK（用户自备文件）。三者共用同一包名，必须摆在一起。
 */
@Composable
fun GmsSlotPanel(
    apps: List<VirtualApp>,
    hostGooglePackages: Set<String>,
    busyPackage: String?,
    accent: Color,
    actionsEnabled: Boolean,
    onCloneFromHost: (String) -> Unit,
    onImportApk: (Uri, MicroGComponent?) -> Unit,
) {
    var pendingComponent by remember { mutableStateOf<MicroGComponent?>(null) }
    var pendingIsSlot by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            onImportApk(uri, if (pendingIsSlot) pendingComponent else null)
        }
        pendingComponent = null
        pendingIsSlot = false
    }

    fun pick(component: MicroGComponent?) {
        pendingComponent = component
        pendingIsSlot = component != null
        picker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
    }

    MicroGComponent.ALL.forEach { comp ->
        val installed = apps.any { it.packageName == comp.packageName }
        val hostHas = comp.packageName in hostGooglePackages
        val busy = busyPackage == comp.packageName

        GmsSlotRow(
            comp = comp,
            installed = installed,
            installedVersion = apps.firstOrNull { it.packageName == comp.packageName }?.versionName,
            hostHas = hostHas,
            busy = busy,
            accent = accent,
            actionsEnabled = actionsEnabled,
            onClone = { onCloneFromHost(comp.packageName) },
            onImport = { pick(comp) },
        )
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(4.dp))

    GlassButton(
        text = "导入任意 APK 到空间",
        onClick = { pick(null) },
        variant = ButtonVariant.Secondary,
        enabled = actionsEnabled,
        compact = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GmsSlotRow(
    comp: MicroGComponent,
    installed: Boolean,
    installedVersion: String?,
    hostHas: Boolean,
    busy: Boolean,
    accent: Color,
    actionsEnabled: Boolean,
    onClone: () -> Unit,
    onImport: () -> Unit,
) {
    val statusColor = when {
        installed -> Success
        comp.required -> Danger
        else -> Warning
    }
    val statusText = when {
        installed -> installedVersion?.takeIf { it.isNotBlank() }?.let { "已装入 $it" } ?: "已装入"
        comp.manualOnlyReason != null -> "需手动导入"
        else -> "未安装"
    }

    GlassCard(
        Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        contentPadding = 12.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(statusColor, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = comp.shortName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installed) Success else InkSoft,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = comp.description,
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )

            comp.manualOnlyReason?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Warning)
            }

            if (comp.downloadable) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "官方直链：${comp.downloadRepo} ${comp.releaseTag}" +
                        if (hostHas) " · 宿主上也装了，可直接克隆" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostHas) {
                    GlassButton(
                        text = if (busy) "处理中…" else "从宿主克隆",
                        onClick = onClone,
                        variant = ButtonVariant.Accent,
                        accent = accent,
                        enabled = actionsEnabled && !busy,
                        compact = true,
                    )
                }
                GlassButton(
                    text = "导入 APK",
                    onClick = onImport,
                    variant = ButtonVariant.Secondary,
                    enabled = actionsEnabled && !busy,
                    compact = true,
                )
            }

            if (GoogleStack.officialNeedsSystemInstall(comp.packageName)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "提示：想装原版（非 microG）请用「从宿主克隆」。「导入 APK」不会给系统应用身份，原版 GMS / Play 商店缺了这个跑不起来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }
        }
    }
}

/** 「读取应用列表」被拦截时的引导条 */
@Composable
fun AppListAccessBanner(
    verdict: HostAppRules.VisibilityVerdict?,
    accent: Color,
    onOpen: (AppListAccess.GuideTarget) -> Unit,
) {
    if (verdict == null || !verdict.gated) return

    val context = LocalContext.current
    val targets = remember(verdict) { AppListAccess.guideIntents(context) }

    GlassCard(
        Modifier.fillMaxWidth(),
        tint = Danger.copy(alpha = 0.08f),
        borderColor = Danger.copy(alpha = 0.3f),
        contentPadding = 14.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Danger, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "应用列表被系统拦住了",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "系统只让本应用看到 ${verdict.visibleCount} 个包，但有桌面图标的就有 " +
                    "${verdict.launcherCount} 个 —— 差额 ${verdict.hiddenCount} 个被「读取应用列表」权限挡住。",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )

            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { target ->
                    GlassButton(
                        text = target.label,
                        onClick = { onOpen(target) },
                        variant = ButtonVariant.Accent,
                        accent = accent,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 空状态下的说明 */
@Composable
fun HostListEmptyHints(includeSystem: Boolean, gated: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!includeSystem) {
            Text(
                text = "系统应用默认不显示。如果这台设备上没装几个第三方应用，打开上面的「包含系统应用」就能看到预装的浏览器、相机等。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
                textAlign = TextAlign.Center,
            )
        }
        if (gated) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "系统拦住了应用列表权限，上面的引导可以打开对应的设置页。",
                style = MaterialTheme.typography.labelSmall,
                color = Danger,
                textAlign = TextAlign.Center,
            )
        }
    }
}