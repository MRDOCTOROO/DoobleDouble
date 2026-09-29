package com.dooble.space.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.dooble.gms.OemStack
import com.dooble.space.system.AppListAccess
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Mint
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Tangerine

/**
 * 谷歌组件槽位面板。
 *
 * ## 为什么每个槽位有三个来源
 *
 * 用户想装的"谷歌组件"其实有三种形态，而它们的获取方式完全不同：
 *
 * | 来源 | 适用 | 走的容器语义 |
 * |---|---|---|
 * | 官方直链 | microG 系（可合法分发） | storage |
 * | 从宿主克隆 | 用户自己设备上的副本，含系统应用 | **system** |
 * | 导入 APK | 用户自备文件 | storage |
 *
 * 把它们摆在同一个槽位上，是因为它们**共用同一个包名**（`com.google.android.gms`
 * 之类），只能装一个 —— 分开做三个功能，用户根本不知道该点哪个。
 *
 * ## 关于「原版」
 *
 * 原版 GMS / Play 商店必须走「从宿主克隆」：它们带 native 库、且会检查自己的
 * 系统应用身份，而「导入 APK」走的是 storage 语义，两样都给不了。
 * 这个约束由 [GoogleStack.officialNeedsSystemInstall] 表达，UI 上会据此提示。
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
    // 文件选择器只建一个，用 pendingComponent 记住"这次是给哪个槽位选的"。
    // 每个槽位各建一个 launcher 会在 Compose 里创建一堆无用的 ActivityResult 注册。
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
        // 只筛 apk 类型；部分 ROM 的文档提供方不认这个 mime，所以不额外加扩展名过滤，
        // 免得用户明明选了文件却拿不到。
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

    Spacer(Modifier.height(6.dp))

    // 任意包名的 APK 导入：槽位覆盖不到的（比如某个特定版本的 Google 应用）走这里
    BrutalButton(
        text = "导入任意 APK 到空间",
        onClick = { pick(null) },
        background = PaperDeep,
        contentColor = Ink,
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
    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = Paper,
        shadowOffset = 4.dp,
        contentPadding = 12.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            when {
                                installed -> Success
                                comp.deprecated -> InkFaint
                                comp.required -> Danger
                                else -> Tangerine
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = comp.shortName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (comp.deprecated) InkFaint else Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        installed -> installedVersion?.takeIf { it.isNotBlank() }?.let { "已装入 $it" } ?: "已装入"
                        comp.deprecated -> "已废弃"
                        comp.manualOnlyReason != null -> "需手动导入"
                        else -> "未安装"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installed) Success else InkSoft,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = comp.description,
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft,
            )

            // 无直链 / 已废弃的原因必须显示出来，否则用户会一直找"为什么没有下载按钮"
            comp.manualOnlyReason?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Tangerine)
            }
            comp.deprecatedReason?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = InkFaint)
            }

            if (comp.downloadable && !comp.deprecated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "官方直链：${comp.downloadRepo} ${comp.releaseTag}" +
                        if (hostHas) " · 宿主上也装了，可直接克隆" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }

            Spacer(Modifier.height(10.dp))
            // 已废弃的组件不给操作按钮：上游根本没有可安装的文件，
            // 给个"导入 APK"只会让用户去找一个不存在的 APK。
            if (!comp.deprecated) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hostHas) {
                        BrutalButton(
                            text = if (busy) "处理中…" else "从宿主克隆",
                            onClick = onClone,
                            background = accent,
                            contentColor = Ink,
                            enabled = actionsEnabled && !busy,
                            compact = true,
                        )
                    }
                    BrutalButton(
                        text = "导入 APK",
                        onClick = onImport,
                        background = PaperDeep,
                        contentColor = Ink,
                        enabled = actionsEnabled && !busy,
                        compact = true,
                    )
                }
            }

            // 原版必须走 system 语义 —— 这条提示只在共用包名的槽位上出现
            if (GoogleStack.officialNeedsSystemInstall(comp.packageName)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "提示：想装**原版**（非 microG）请用「从宿主克隆」。" +
                        "「导入 APK」走的是复制进容器的语义，不会给系统应用身份，" +
                        "原版 GMS / Play 商店缺了这个跑不起来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }
        }
    }
}

/**
 * 「关于原版谷歌服务」说明卡。
 *
 * 内容全部来自 [GoogleStack] 里的常量 —— 那些是查 Bcore 源码与社区实测方案得到的
 * 结论，不是猜测。集中放在一处，免得 UI 各处各写一份、慢慢走样。
 */
@Composable
fun OfficialGoogleNote(accent: Color) {
    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = PaperDeep,
        shadowOffset = 4.dp,
        contentPadding = 14.dp,
    ) {
        Column {
            Text(
                text = "关于「原版谷歌三件套」",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Spacer(Modifier.height(8.dp))

            NoteParagraph("为什么不内置", GoogleStack.WHY_NOT_BUNDLED, Danger)
            NoteParagraph("怎么拿到", GoogleStack.HOW_TO_GET_OFFICIAL, Ink)
            NoteParagraph("容器支持", GoogleStack.CONTAINER_SUPPORT, Ink)
            NoteParagraph("槽位互斥", GoogleStack.SLOT_EXCLUSIVE, Tangerine)

            Spacer(Modifier.height(6.dp))
            Text(
                text = "原版 Play 商店要真正可用，需要同时满足：",
                style = MaterialTheme.typography.labelMedium,
                color = Ink,
            )
            Spacer(Modifier.height(4.dp))
            GoogleStack.PLAY_STORE_REQUIREMENTS.forEachIndexed { i, line ->
                Row(Modifier.padding(bottom = 3.dp)) {
                    Text(
                        text = "${i + 1}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, Ink)
                    .background(Mint)
                    .padding(10.dp)
            ) {
                Text(
                    text = GoogleStack.PLAY_STORE_VERDICT,
                    style = MaterialTheme.typography.labelSmall,
                    color = Success,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "推荐做法：GmsCore 用 microG（官方直链一键装），商店用 Aurora Store。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
    }
}

@Composable
private fun NoteParagraph(title: String, body: String, color: Color) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Ink,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft,
            )
        }
    }
}

/**
 * 「读取应用列表」被拦截时的引导条。
 *
 * 只在 [verdict] 判定为被拦时显示。给出的入口来自 [AppListAccess]，
 * 里面已经做过滤 —— 列出来的都是当前 ROM 上**真的能打开**的设置页。
 */
@Composable
fun AppListAccessBanner(
    verdict: HostAppRules.VisibilityVerdict?,
    accent: Color,
    onOpen: (AppListAccess.GuideTarget) -> Unit,
) {
    if (verdict == null || !verdict.gated) return

    // guideIntents 内部会对每个 ROM 深链做一次 resolveActivity（binder 调用）。
    // 放进 remember，免得每次重组都跑一遍。
    val context = LocalContext.current
    val targets = remember(verdict) { AppListAccess.guideIntents(context) }

    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = Paper,
        shadowOffset = 4.dp,
        contentPadding = 14.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Danger))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "应用列表被系统拦住了",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "系统只让本应用看到 ${verdict.visibleCount} 个包，但有桌面图标的就有 " +
                    "${verdict.launcherCount} 个 —— 差额 ${verdict.hiddenCount} 个被「读取应用列表」" +
                    "权限挡住了（MIUI / ColorOS / OriginOS / EMUI 默认关闭它）。",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已经用桌面图标那条路兜底了，所以列表里应该还能看到大部分应用。" +
                    "但**没有桌面图标**的（后台服务、插件、宿主上的 GMS / Play 商店这类系统应用）" +
                    "必须开权限才能看到。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { target ->
                    BrutalButton(
                        text = target.label,
                        onClick = { onOpen(target) },
                        background = accent,
                        contentColor = Ink,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "从设置页返回后会自动重新扫描。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
    }
}

/** 空状态下的说明：列出"为什么可能是空的"，避免用户以为功能坏了 */
@Composable
fun HostListEmptyHints(includeSystem: Boolean, gated: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!includeSystem) {
            Text(
                text = "系统应用默认不显示。如果这台设备上没装几个第三方应用，" +
                    "打开上面的「包含系统应用」就能看到预装的浏览器、相机等。",
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

/**
 * 厂商自有服务槽位（华为/荣耀）。
 *
 * 只在**设备是华为/荣耀**或**宿主上确实装着这些包**时渲染。
 * 非华为设备上宿主没有这些包，整个面板不出现 —— 不需要用户去理解
 * "为什么这里有一堆用不上的东西"。
 *
 * ## 为什么面板上要专门写"HMS ≠ GMS"
 *
 * 用户从 GBox 的宣传里得到的印象是"华为要用 HMS 才能有更好的兼容性"，
 * 很容易理解成"装了 HMS 谷歌服务就能用了"。那会让他们在排查谷歌登录失败时
 * 往完全错误的方向找。所以这条说明放在面板最前面，而不是折叠在文档里。
 */
@Composable
fun OemSlotPanel(
    slots: List<OemStack.Slot>,
    apps: List<VirtualApp>,
    hostPackages: Set<String>,
    busyPackage: String?,
    accent: Color,
    actionsEnabled: Boolean,
    isHuaweiDevice: Boolean,
    onCloneFromHost: (String) -> Unit,
) {
    // 非华为设备且宿主也没有这些包 → 整块不显示
    val anyOnHost = slots.any { it.packageName in hostPackages }
    val anyInstalled = slots.any { s -> apps.any { it.packageName == s.packageName } }
    if (!isHuaweiDevice && !anyOnHost && !anyInstalled) return

    BrutalSurface(
        Modifier.fillMaxWidth(),
        background = Paper,
        shadowOffset = 4.dp,
        contentPadding = 14.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "厂商服务（华为 / 荣耀）",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = OemStack.HMS_IS_NOT_GMS,
                style = MaterialTheme.typography.labelSmall,
                color = Tangerine,
            )

            if (isHuaweiDevice) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = OemStack.HARMONY_NEXT_WARNING,
                    style = MaterialTheme.typography.labelSmall,
                    color = Danger,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = OemStack.HUAWEI_KEEPALIVE_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }

            Spacer(Modifier.height(12.dp))

            slots.forEach { slot ->
                val installed = apps.any { it.packageName == slot.packageName }
                val hostHas = slot.packageName in hostPackages
                val busy = busyPackage == slot.packageName

                Column(Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(
                                if (installed) Success else if (hostHas) Tangerine else InkFaint
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = slot.shortName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (installed) "已装入" else if (hostHas) "宿主有" else "宿主没有",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (installed) Success else InkSoft,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = slot.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft,
                    )
                    Text(
                        text = slot.impact,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = slot.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                    if (hostHas) {
                        Spacer(Modifier.height(8.dp))
                        BrutalButton(
                            text = if (busy) "处理中…" else "从宿主克隆",
                            onClick = { onCloneFromHost(slot.packageName) },
                            background = accent,
                            contentColor = Ink,
                            enabled = actionsEnabled && !busy,
                            compact = true,
                        )
                    }
                }
            }

            Text(
                text = "HMS 组件是华为/荣耀设备自带的，本应用不打包也不下载，" +
                    "只支持从你自己的设备克隆（与谷歌组件的同一条合规红线）。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
    }
}
