package com.dooble.space.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dooble.engine.VirtualApp
import com.dooble.gms.GmsSignatureSpoof
import com.dooble.gms.GoogleAccountBridge
import com.dooble.gms.MicroGComponent
import com.dooble.gms.OemStack
import com.dooble.gms.ProvisionPhase
import com.dooble.gms.SpoofMode
import com.dooble.gms.StorePhase
import com.dooble.gms.StoreProvisioner
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.rememberVirtualAppIcon
import com.dooble.space.ui.components.AppIconBadge
import com.dooble.space.ui.components.AppListAccessBanner
import com.dooble.space.ui.components.BrutalButton
import com.dooble.space.ui.components.BrutalChip
import com.dooble.space.ui.components.BrutalProgress
import com.dooble.space.ui.components.BrutalRadioRow
import com.dooble.space.ui.components.BrutalSurface
import com.dooble.space.ui.components.DoobleTopBar
import com.dooble.space.ui.components.EmptyState
import com.dooble.space.ui.components.GmsSlotPanel
import com.dooble.space.ui.components.HostImportSheet
import com.dooble.space.ui.components.KeyValueRow
import com.dooble.space.ui.components.OemSlotPanel
import com.dooble.space.ui.components.OfficialGoogleNote
import com.dooble.space.ui.components.SHORTCUT_ROM_CAVEAT
import com.dooble.space.ui.components.SectionRule
import com.dooble.space.ui.components.ShortcutSheet
import com.dooble.space.ui.components.brutalClickable
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Electric
import com.dooble.space.ui.theme.HotPink
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Mint
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Tangerine
import com.dooble.space.ui.theme.accentOf

/** 当前供给的商店包名，从供给层取，避免在 UI 里硬编码字符串 */
private const val STORE_PACKAGE = StoreProvisioner.PRIMARY_PACKAGE

/**
 * 桌面网格列数。
 *
 * 固定 4 列而不是按屏宽自适应：后现代/粗野主义讲究"格子就是格子"，
 * 图标尺寸恒定比"恰好铺满"更重要。4 列在 360dp 到 480dp 的屏上都好看。
 */
private const val DESKTOP_COLUMNS = 4

@Composable
fun SpaceDetailScreen(
    vm: SpaceViewModel,
    spaceId: String,
    onBack: () -> Unit,
    onOpenSelfCheck: () -> Unit,
) {
    val spaces by vm.spaces.collectAsState()
    val apps by vm.apps.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val step by vm.provisionStep.collectAsState()
    val report by vm.provisionReport.collectAsState()
    val busy by vm.busy.collectAsState()
    val hostApps by vm.hostApps.collectAsState()
    val hostScanning by vm.hostScanning.collectAsState()
    val hostIncludeSystem by vm.hostIncludeSystem.collectAsState()
    val hostOnlyGms by vm.hostOnlyGms.collectAsState()
    val storeStep by vm.storeStep.collectAsState()
    val storeReport by vm.storeReport.collectAsState()
    val recentInstalls by vm.recentInstalls.collectAsState()
    val spoofMode by vm.spoofMode.collectAsState()
    val spoofCert by vm.spoofCert.collectAsState()
    val pinningPackage by vm.pinningPackage.collectAsState()
    val slotBusy by vm.slotBusy.collectAsState()
    val hostGooglePackages by vm.hostGooglePackages.collectAsState()
    val hostVisibility by vm.hostVisibility.collectAsState()
    val isHuaweiDevice by vm.isHuaweiDevice.collectAsState()

    var showHostImport by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 卸载按钮和「结束运行」是紧邻的两个 34dp 方块，一个可恢复一个不可恢复。
    // 所以点卸载只记录目标，真正删除走确认框。
    var pendingUninstall by remember { mutableStateOf<VirtualApp?>(null) }

    val storeInstalled = apps.any { it.packageName == STORE_PACKAGE }

    val space = spaces.firstOrNull { it.id == spaceId }
    val accent = accentOf(space?.config?.accent ?: 0)
    val provisioned = vm.isProvisioned(spaceId)

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.installApk(it) }
    }

    val context = LocalContext.current

    // 用户去系统设置开完「读取应用列表」回来，必须重新扫描，否则列表还是旧的。
    // 放在 ON_RESUME 而不是等用户手动下拉：从设置返回是唯一会让列表变化的外部事件，
    // 不在这里刷就只能在用户重新打开导入面板时才刷 —— 那时他已经以为没生效了。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onReturnedFromSettings()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showHostImport) {
        HostImportSheet(
            apps = hostApps,
            scanning = hostScanning,
            accent = accent,
            includeSystem = hostIncludeSystem,
            onlyGmsDependent = hostOnlyGms,
            visibility = hostVisibility,
            onSearch = { vm.scanHostApps(it) },
            onToggleSystem = { vm.setHostFilters(it, hostOnlyGms) },
            onToggleGms = { vm.setHostFilters(hostIncludeSystem, it) },
            onImport = { pkg ->
                vm.importHostApp(pkg)
                showHostImport = false
            },
            onOpenGuide = { target -> openGuide(context, target) },
            onDismiss = { showHostImport = false },
        )
    }

    if (showShortcuts) {
        ShortcutSheet(
            spaceName = space?.config?.name ?: "空间",
            apps = apps,
            supported = vm.shortcutsSupported(),
            accent = accent,
            pinningPackage = pinningPackage,
            onPinSpace = { vm.pinSpace() },
            onPinApp = { vm.pinApp(it) },
            onDismiss = { showShortcuts = false },
        )
    }

    // space 可能已经不存在（例如在列表页删掉了同一个空间）。此时不渲染对话框——
    // 让用户对着一个不存在的空间确认删除没有意义。
    // 这里用 `&&` 而不是在分支里回写状态：组合期间写状态是副作用，
    // 条件不成立时对话框自然消失，效果一样但更干净。
    if (showDeleteConfirm && space != null) {
        DeleteSpaceDialog(
            space = space,
            provisioned = provisioned,
            accountCount = accounts.size,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                vm.deleteSpace(spaceId)
                // 空间没了，这个页面已经没有可渲染的对象。
                // 停在原地只会渲染出一个残缺页面，所以直接退回列表。
                onBack()
            },
        )
    }

    pendingUninstall?.let { target ->
        UninstallAppDialog(
            app = target,
            onDismiss = { pendingUninstall = null },
            onConfirm = {
                vm.uninstall(target.packageName)
                pendingUninstall = null
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        DoobleTopBar(
            title = space?.config?.name ?: "空间",
            subtitle = "空间详情 · ${spaceId}",
            accent = accent,
            onBack = onBack,
            actions = {
                Box(
                    Modifier
                        .border(3.dp, Ink)
                        .background(Acid)
                        .brutalClickable { onOpenSelfCheck() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("自检", style = MaterialTheme.typography.labelMedium, color = Ink)
                }
            },
        )

        Column(Modifier.padding(16.dp)) {

            // ---------------------------------------------- 桌面
            //
            // 放在最前面是刻意的：用户打开一个空间，第一件想确认的事是
            // "这里面有什么、商店装上没有"。图标是最直接的答案——
            // 下面的详细列表承担信息量，这里承担"一眼看到"。
            SectionRule("容器桌面", trailing = "${apps.size} 个应用")
            Spacer(Modifier.height(12.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = PaperDeep, contentPadding = 14.dp) {
                Column {
                    if (apps.isEmpty()) {
                        Text(
                            text = "空间里还没有应用。用下面的「从已安装应用导入」挑一个，" +
                                "或者先装好谷歌环境再装商店——装好后这里会出现它们的图标。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            apps.chunked(DESKTOP_COLUMNS).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    row.forEach { app ->
                                        DesktopTile(
                                            app = app,
                                            accent = accent,
                                            modifier = Modifier.weight(1f),
                                            onClick = { vm.launchApp(app.packageName) },
                                            // 长按钉到桌面，pinApp 内部会 toast。
                                            // 之所以只在桌面视图（这里是 chunked 后的网格）
                                            // 接而不是 AppRow：长按 AppRow 有别的语义
                                            // （弹菜单/卸载），加长按钉桌面会和它冲突。
                                            onLongClick = { vm.pinApp(app) },
                                        )
                                    }
                                    // 补齐末行的空位：否则最后一行会被拉伸成宽格子，
                                    // 看起来像"这几个图标比别的大"
                                    repeat(DESKTOP_COLUMNS - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        // 长按功能的可发现性提示。放在桌面网格下方、一行字，
                        // 不喧宾夺主但让用户知道这能力存在。
                        Text(
                            text = "提示：长按图标可发送到桌面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            // ---------------------------------------------- 谷歌环境
            SectionRule("谷歌环境", trailing = if (provisioned) "就绪" else "待装配")
            Spacer(Modifier.height(12.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(if (provisioned) Success else HotPink))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (provisioned) "microG 栈已装配" else "尚未装配 microG 栈",
                            style = MaterialTheme.typography.titleLarge,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 组件槽位：每个槽位都能从"官方直链 / 宿主克隆 / 导入 APK"三种来源填。
                    // 三种来源共用同一个包名，所以必须摆在一起 —— 分开做三个功能，
                    // 用户根本不知道该点哪个（原版与 microG 版会互相替换）。
                    GmsSlotPanel(
                        apps = apps,
                        hostGooglePackages = hostGooglePackages,
                        busyPackage = slotBusy,
                        accent = accent,
                        actionsEnabled = !busy,
                        onCloneFromHost = { vm.cloneFromHost(it) },
                        onImportApk = { uri, comp -> vm.importApkFromUri(uri, comp) },
                    )

                    Spacer(Modifier.height(14.dp))

                    // 进度
                    step?.let { s ->
                        BrutalProgress(
                            progress = if (s.total > 0) s.index.toFloat() / s.total else 0f,
                            fill = when (s.phase) {
                                ProvisionPhase.ERROR -> Danger
                                ProvisionPhase.DONE -> Success
                                else -> accent
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "[${s.index}/${s.total}] ${s.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.phase == ProvisionPhase.ERROR) Danger else InkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    report?.let { r ->
                        if (!r.success && r.errors.isNotEmpty()) {
                            Text(
                                text = r.errors.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrutalButton(
                            text = if (provisioned) "重新装配" else "装配谷歌环境",
                            onClick = { vm.provisionGoogleEnvironment() },
                            background = accent,
                            contentColor = Ink,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        BrutalButton(
                            text = "自检",
                            onClick = { vm.runSelfCheck() },
                            background = PaperDeep,
                            contentColor = Ink,
                            enabled = !busy,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "装配会从 microG 官方仓库下载 GmsCore（约 103MB）与 FakeStore（约 4MB）。" +
                            "下载内容跨空间共享缓存，不会重复占用空间。" +
                            "GSF Proxy 在 microG 的另一个仓库（约 21KB），可选。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 华为/荣耀设备专属：HMS 槽位 + 鸿蒙 NEXT 前置警告 + 启动管理提示。
            // 非华为设备上宿主没有这些包，整个面板不出现。
            OemSlotPanel(
                slots = OemStack.HUAWEI_SLOTS,
                apps = apps,
                hostPackages = hostGooglePackages,
                busyPackage = slotBusy,
                accent = accent,
                actionsEnabled = !busy,
                isHuaweiDevice = isHuaweiDevice,
                onCloneFromHost = { vm.cloneFromHost(it) },
            )
            if (isHuaweiDevice) Spacer(Modifier.height(14.dp))

            // 「原版谷歌三件套能不能内置」是用户最常问的问题，答案必须写在界面上，
            // 而不是只留在文档里 —— 用户不会去翻文档。
            OfficialGoogleNote(accent = accent)

            Spacer(Modifier.height(22.dp))

            // ---------------------------------------------- 签名伪造
            SectionRule("签名伪造", trailing = spoofMode.label.uppercase())
            Spacer(Modifier.height(12.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    KeyValueRow(
                        key = "当前模式",
                        value = spoofMode.label,
                        valueColor = when (spoofMode) {
                            SpoofMode.OFF -> InkSoft
                            SpoofMode.TEST -> Tangerine
                            SpoofMode.OFFICIAL -> Success
                        },
                    )
                    spoofCert?.let { cert ->
                        KeyValueRow("证书来源", cert.source)
                        KeyValueRow("SHA-1", cert.sha1)
                        KeyValueRow(
                            key = "与官方一致",
                            value = if (cert.sha1 == GmsSignatureSpoof.GMS_SHA1) "是" else "否",
                            valueColor = if (cert.sha1 == GmsSignatureSpoof.GMS_SHA1) Success else Tangerine,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "签名伪造只影响那些主动校验 GMS 签名的第三方应用。" +
                    "登录 Google 账号、在商店里下载安装应用、多开——这些都不依赖它，" +
                    "所以本地测试可以放心选「关闭」。",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )

            Spacer(Modifier.height(12.dp))
            SpoofMode.entries.forEach { mode ->
                BrutalRadioRow(
                    selected = spoofMode == mode,
                    title = mode.label,
                    subtitle = when (mode) {
                        SpoofMode.OFF ->
                            "默认。容器行为与未启用伪造时完全一致"
                        SpoofMode.TEST ->
                            "自签证书。能验证伪造链路是否打通，但骗不过 microG"
                        SpoofMode.OFFICIAL ->
                            "需自备证书。真正能让 microG 认可的模式"
                    },
                    accent = when (mode) {
                        SpoofMode.OFF -> InkSoft
                        SpoofMode.TEST -> Tangerine
                        SpoofMode.OFFICIAL -> Success
                    },
                    onClick = { vm.setSpoofMode(mode) },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (spoofMode != SpoofMode.OFF) {
                Text(
                    text = "证书投放位置：" +
                        "<filesDir>/dooble/signature/ 或 " +
                        "/sdcard/Android/data/<包名>/files/dooble/signature/。" +
                        "本地测试证书用 tools/gen-test-cert.sh 生成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint,
                )
            }

            Spacer(Modifier.height(22.dp))

            // ---------------------------------------------- 谷歌账号
            SectionRule("谷歌账号", trailing = "${accounts.size} 个")
            Spacer(Modifier.height(12.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    if (accounts.isEmpty()) {
                        Text(
                            "该空间还没有登录 Google 账号。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSoft,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "每个空间的账号列表互相隔离——这正是可以同时挂多个 Google 账号的原因。",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    } else {
                        accounts.forEach { acc ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(32.dp).border(2.dp, Ink).background(accent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = acc.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Ink,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = acc.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Ink,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    Modifier
                                        .border(2.dp, Ink)
                                        .background(PaperDeep)
                                        .brutalClickable { vm.removeAccount(acc.name) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("移除", style = MaterialTheme.typography.labelSmall, color = Danger)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    BrutalButton(
                        text = if (accounts.isEmpty()) "登录 Google 账号" else "再添加一个账号",
                        onClick = { vm.signIn() },
                        background = Electric,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = provisioned,
                    )
                    if (!provisioned) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "需要先装配谷歌环境，否则登录页无法拉起。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 登录界面是空间内 microG 提供的，会在容器里打开。
                        // 说明这一点是为了让用户在"点了没反应"时知道该查哪里 ——
                        // 之前这里会回退到宿主的系统账号页，只有一个点不动的
                        // 「添加账号」入口，用户完全无从判断。
                        text = GoogleAccountBridge.SIGN_IN_HINT,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // ---------------------------------------------- 应用商店
            SectionRule("应用商店", trailing = if (storeInstalled) "已就绪" else "未安装")
            Spacer(Modifier.height(12.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    Text(
                        text = if (storeInstalled) "空间内已有应用商店" else "空间内还没有应用商店",
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "装入 Aurora Store 后，就能在这个空间里登录 Google 账号、" +
                            "搜索并下载应用。下载的 APK 由容器拦截后装进同一个空间，" +
                            "不会跑到宿主机上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Aurora Store 是 Google Play 的开源客户端（GPLv3），" +
                            "走的是同一套 Play 接口，但不需要设备认证——" +
                            "这也是本工程不内置原版 Play 商店的原因。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )

                    if (storeInstalled) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .border(2.dp, Ink)
                                .background(PaperDeep)
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Aurora 里的「Google」按钮在容器内不可用",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Tangerine,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    // 这条必须写在界面上：用户点下去只会看到"没反应"，
                                    // 无从判断是被容器限制还是 Aurora 自己坏了。
                                    // 详见 docs/10 第 6 节（含完整 logcat 证据链）。
                                    text = "它会调系统账号选择器 android.accounts.ChooseTypeAndAccountActivity，" +
                                        "而那个页面在**宿主进程**里跑，看不到空间内的 microG。" +
                                        "所以点「Google」会立刻返回取消、页面不动。" +
                                        "请改用「匿名」会话（Aurora 官方支持，不需要 Google 账号，" +
                                        "一样能搜索和下载应用）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkSoft,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "想在空间内登录 Google 账号，请用上面的「登录 Google 账号」——" +
                                        "那条链路走的是容器内 microG，是可用的。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkFaint,
                                )
                            }
                        }
                    }

                    storeStep?.let { s ->
                        Spacer(Modifier.height(12.dp))
                        BrutalProgress(
                            progress = s.progress.coerceIn(0f, 1f),
                            fill = if (s.phase == StorePhase.ERROR) Danger else accent,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.phase == StorePhase.ERROR) Danger else InkSoft,
                        )
                    }

                    storeReport?.let { r ->
                        if (!r.success) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = r.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrutalButton(
                            text = if (storeInstalled) "重新装入商店" else "装入应用商店",
                            onClick = { vm.installStore() },
                            background = Tangerine,
                            contentColor = Ink,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        if (storeInstalled) {
                            BrutalButton(
                                text = "打开",
                                onClick = {
                                    apps.firstOrNull { it.packageName == STORE_PACKAGE }
                                        ?.let { vm.launchApp(it.packageName) }
                                },
                                background = Acid,
                                contentColor = Ink,
                                enabled = !busy,
                            )
                        }
                    }
                }
            }

            // 容器内应用自己发起的安装结果（商店下载完自动装 / 应用内更新）
            if (recentInstalls.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                BrutalSurface(Modifier.fillMaxWidth(), background = PaperDeep, contentPadding = 12.dp) {
                    Column {
                        Text(
                            text = "空间内安装记录",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink,
                        )
                        Spacer(Modifier.height(8.dp))
                        recentInstalls.take(5).forEach { e ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(8.dp)
                                        .background(if (e.ok) Success else Danger)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = e.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    text = if (e.ok) "已装入" else e.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (e.ok) Success else Danger,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // ---------------------------------------------- 应用
            SectionRule("空间内应用", trailing = "${apps.size} 个")
            Spacer(Modifier.height(12.dp))

            if (apps.isEmpty()) {
                BrutalSurface(Modifier.fillMaxWidth(), background = PaperDeep, contentPadding = 20.dp) {
                    EmptyState(
                        title = "空间是空的",
                        hint = "从已安装应用里挑一个导入，或选择本地 APK 文件",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                apps.forEach { app ->
                    AppRow(
                        app = app,
                        accent = accent,
                        onLaunch = { vm.launchApp(app.packageName) },
                        onStop = { vm.stopApp(app.packageName) },
                        onUninstall = { pendingUninstall = app },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 应用列表被 ROM 拦住时，先给引导再给按钮 —— 否则用户点进导入面板
            // 看到一片空白，只会以为功能坏了。
            AppListAccessBanner(
                verdict = hostVisibility,
                accent = accent,
                onOpen = { target -> openGuide(context, target) },
            )
            if (hostVisibility?.gated == true) Spacer(Modifier.height(12.dp))

            BrutalButton(
                text = "⧉  从已安装应用导入",
                onClick = {
                    showHostImport = true
                    vm.scanHostApps()
                },
                background = Ink,
                contentColor = Acid,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

            Spacer(Modifier.height(10.dp))

            BrutalButton(
                text = "＋  从文件装入 APK",
                onClick = {
                    apkPicker.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/octet-stream",
                        )
                    )
                },
                background = PaperDeep,
                contentColor = Ink,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "推荐用「从已安装应用导入」：容器直接读系统的安装路径，" +
                    "不需要存储权限，也不用先导出 APK。文件装入适合手头已有独立 APK 的场景。" +
                    "注意：两条路目前都只装入基础 APK，AAB 拆分包会缺资源。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )

            Spacer(Modifier.height(26.dp))

            // ---------------------------------------------- 桌面快捷方式
            SectionRule("桌面快捷方式", trailing = if (apps.isEmpty()) "—" else "${apps.size} 个可发")
            Spacer(Modifier.height(12.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    Text(
                        text = "把空间里的应用直接钉到系统桌面。点桌面图标就等于在空间里启动它，" +
                            "不需要先打开 Dooble 再点两层。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = SHORTCUT_ROM_CAVEAT,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                    Spacer(Modifier.height(12.dp))
                    BrutalButton(
                        text = "发到桌面…",
                        onClick = { showShortcuts = true },
                        background = Acid,
                        contentColor = Ink,
                        enabled = apps.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ---------------------------------------------- 危险区
            SectionRule("危险操作")
            Spacer(Modifier.height(12.dp))
            BrutalButton(
                text = "删除该空间及其全部数据",
                onClick = { showDeleteConfirm = true },
                background = Danger,
                contentColor = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun DesktopTile(
    app: VirtualApp,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val icon = rememberVirtualAppIcon(app.packageName, app.apkPath, app.versionCode)

    Column(
        modifier = modifier.brutalClickable(
            onClick = onClick,
            // 长按 = 直接把这个应用钉到桌面。
            // 比"打开快捷方式面板再选'钉到桌面'"少两步，并且符合用户在桌面上
            // 长按图标 = 弹快捷操作的肌肉记忆。
            onLongClick = onLongClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconBadge(
            icon = icon,
            label = app.label,
            accent = accent,
            size = 52.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AppRow(
    app: VirtualApp,
    accent: androidx.compose.ui.graphics.Color,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    // 图标从空间自己的虚拟目录里那份 APK 上取——与空间内实际运行的是同一份文件，
    // 所以图标和空间里看到的完全一致，不会串到宿主那份上去
    val icon = rememberVirtualAppIcon(app.packageName, app.apkPath, app.versionCode)

    BrutalSurface(Modifier.fillMaxWidth(), background = Paper, shadowOffset = 4.dp, onClick = onLaunch) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(
                icon = icon,
                label = app.label,
                accent = accent,
                size = 44.dp,
            )

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleLarge,
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
                    if (app.usesGoogleApis) {
                        BrutalChip(text = "依赖 GMS", background = Mint, contentColor = Success)
                    }
                    if (app.isMicroG) {
                        BrutalChip(text = "microG 组件", background = PaperDeep)
                    }
                    BrutalChip(text = "v${app.versionName}")
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(34.dp)
                        .border(3.dp, Ink)
                        .background(Acid)
                        .brutalClickable { onLaunch() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "启动", tint = Ink)
                }
                // 结束运行：只杀这一个包在当前空间里的进程。
                // 之所以单独给一个按钮而不是让用户去系统设置里"强行停止"——
                // 后者停的是宿主，会把所有空间的应用一起带走。
                Box(
                    Modifier
                        .size(34.dp)
                        .border(3.dp, Ink)
                        .background(PaperDeep)
                        .brutalClickable { onStop() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "结束运行",
                        tint = Tangerine,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .border(3.dp, Ink)
                        .background(PaperDeep)
                        .brutalClickable { onUninstall() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "卸载",
                        tint = Danger,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * 打开一个引导入口。
 *
 * 单独抽出来是因为三处都要用（详情页的横幅、导入面板的横幅、空状态提示），
 * 而 startActivity 必须吞异常：候选组件名是靠 `resolveActivity` 探测出来的，
 * 但部分 ROM 的 `resolveActivity` 会返回非 null 却仍然抛
 * `ActivityNotFoundException`（导出状态与实际不一致）。吞掉之后最坏是"点了没反应"，
 * 比崩溃好得多；而且列表最后一项一定是系统标准入口，用户不会走投无路。
 */
private fun openGuide(context: android.content.Context, target: com.dooble.space.system.AppListAccess.GuideTarget) {
    runCatching { context.startActivity(target.intent) }
        .onFailure { android.util.Log.w("DoobleDetail", "打开引导入口失败：${target.label}", it) }
}
