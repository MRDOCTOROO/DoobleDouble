package com.dooble.space.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.dooble.gms.ProvisionPhase
import com.dooble.gms.SpoofMode
import com.dooble.gms.StorePhase
import com.dooble.gms.StoreProvisioner
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.rememberVirtualAppIcon
import com.dooble.space.ui.components.AppActionSheet
import com.dooble.space.ui.components.AppIconBadge
import com.dooble.space.ui.components.AppListAccessBanner
import com.dooble.space.ui.components.AuroraBackground
import com.dooble.space.ui.components.ButtonVariant
import com.dooble.space.ui.components.EmptyState
import com.dooble.space.ui.components.GlassButton
import com.dooble.space.ui.components.GlassCard
import com.dooble.space.ui.components.GlassChip
import com.dooble.space.ui.components.GlassIconButton
import com.dooble.space.ui.components.GlassProgress
import com.dooble.space.ui.components.GlassRadioRow
import com.dooble.space.ui.components.GlassSection
import com.dooble.space.ui.components.GlassTopBar
import com.dooble.space.ui.components.GmsSlotPanel
import com.dooble.space.ui.components.HairlineDivider
import com.dooble.space.ui.components.HostImportSheet
import com.dooble.space.ui.components.KeyValueRow
import com.dooble.space.ui.components.SHORTCUT_ROM_CAVEAT
import com.dooble.space.ui.components.ShortcutSheet
import com.dooble.space.ui.components.StatusDot
import com.dooble.space.ui.components.glassClickable
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.MorandiBlue
import com.dooble.space.ui.theme.MorandiClay
import com.dooble.space.ui.theme.MorandiSage
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Warning
import com.dooble.space.ui.theme.accentOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 当前供给的商店包名 */
private const val STORE_PACKAGE = StoreProvisioner.PRIMARY_PACKAGE

/** 桌面网格列数 */
private const val DESKTOP_COLUMNS = 4

/**
 * 容器内安装事件轮询间隔。
 *
 * 详情页常驻时每隔这么久读一次安装事件日志。间隔太短会频繁唤醒 IO，
 * 太长则商店装完应用后桌面更新有明显延迟。4 秒是"用户刚装完切回来
 * 大概率已经能看到"的合理折中。
 */
private const val INSTALL_POLL_INTERVAL_MS = 4_000L

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

    var showHostImport by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var actionApp by remember { mutableStateOf<VirtualApp?>(null) }

    val storeInstalled = apps.any { it.packageName == STORE_PACKAGE }
    val space = spaces.firstOrNull { it.id == spaceId }
    val accent = accentOf(space?.config?.accent ?: 0)
    val provisioned = vm.isProvisioned(spaceId)

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.installApk(it) }
    }

    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onReturnedFromSettings()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 详情页常驻时轻量轮询容器内安装事件：用户在空间里打开商店装完应用，
    // 不用退出详情页，新应用也会自动摆上「容器桌面」。
    // 轮询本身只读事件日志（peek），有本空间的新事件才重读应用列表。
    LaunchedEffect(spaceId) {
        while (true) {
            delay(INSTALL_POLL_INTERVAL_MS)
            vm.pollInstallEvents()
        }
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

    if (showDeleteConfirm && space != null) {
        DeleteSpaceDialog(
            space = space,
            provisioned = provisioned,
            accountCount = accounts.size,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                vm.deleteSpace(spaceId)
                onBack()
            },
        )
    }

    actionApp?.let { target ->
        AppActionSheet(
            app = target,
            accent = accent,
            supported = vm.shortcutsSupported(),
            onLaunch = { vm.launchApp(target.packageName) },
            onPin = { vm.pinApp(target) },
            onUninstall = { vm.uninstall(target.packageName) },
            onDismiss = { actionApp = null },
        )
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GlassTopBar(
                title = space?.config?.name ?: "空间",
                subtitle = "空间详情 · $spaceId",
                onBack = onBack,
                largeTitle = false,
                actions = {
                    GlassButton(
                        text = "自检",
                        onClick = onOpenSelfCheck,
                        variant = ButtonVariant.Secondary,
                        compact = true,
                    )
                },
            )

            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {

                // -------------------------------------------------- 容器桌面
                GlassSection(
                    title = "容器桌面",
                    subtitle = "${apps.size} 个应用 · 长按图标打开操作菜单 · 双击卸载",
                    icon = Icons.Filled.Star,
                    accent = accent,
                    initiallyExpanded = true,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        if (apps.isEmpty()) {
                            Text(
                                text = "空间里还没有应用。用下面的「从已安装应用导入」挑一个，或者先装好谷歌环境再装商店。",
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
                                                onLongClick = { actionApp = app },
                                                onDoubleClick = { vm.uninstall(app.packageName) },
                                            )
                                        }
                                        repeat(DESKTOP_COLUMNS - row.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------------- 谷歌环境
                GlassSection(
                    title = "谷歌环境",
                    subtitle = if (provisioned) "microG 栈已装配" else "尚未装配 microG 栈",
                    icon = Icons.Filled.CheckCircle,
                    accent = if (provisioned) Success else MorandiClay,
                    initiallyExpanded = !provisioned,
                ) {
                    GmsSlotPanel(
                        apps = apps,
                        hostGooglePackages = hostGooglePackages,
                        busyPackage = slotBusy,
                        accent = accent,
                        actionsEnabled = !busy,
                        onCloneFromHost = { vm.cloneFromHost(it) },
                        onImportApk = { uri, comp -> vm.importApkFromUri(uri, comp) },
                    )

                    step?.let { s ->
                        Spacer(Modifier.height(10.dp))
                        GlassProgress(
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
                    }

                    report?.let { r ->
                        if (!r.success && r.errors.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = r.errors.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            text = if (provisioned) "重新装配" else "装配谷歌环境",
                            onClick = { vm.provisionGoogleEnvironment() },
                            variant = ButtonVariant.Accent,
                            accent = accent,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton(
                            text = "自检",
                            onClick = { vm.runSelfCheck() },
                            variant = ButtonVariant.Secondary,
                            enabled = !busy,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "装配会从 microG 官方仓库下载 GmsCore（约 103MB）与 FakeStore（约 4MB），下载内容跨空间共享缓存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )

                    Spacer(Modifier.height(10.dp))
                }

                // -------------------------------------------------- 签名伪造
                GlassSection(
                    title = "签名伪造",
                    subtitle = spoofMode.label,
                    icon = Icons.Filled.Lock,
                    accent = when (spoofMode) {
                        SpoofMode.OFF -> InkFaint
                        SpoofMode.TEST -> Warning
                        SpoofMode.OFFICIAL -> Success
                    },
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow(
                            key = "当前模式",
                            value = spoofMode.label,
                            valueColor = when (spoofMode) {
                                SpoofMode.OFF -> InkSoft
                                SpoofMode.TEST -> Warning
                                SpoofMode.OFFICIAL -> Success
                            },
                        )
                        spoofCert?.let { cert ->
                            HairlineDivider()
                            KeyValueRow("证书来源", cert.source)
                            HairlineDivider()
                            KeyValueRow("SHA-1", cert.sha1)
                            HairlineDivider()
                            KeyValueRow(
                                key = "与官方一致",
                                value = if (cert.sha1 == GmsSignatureSpoof.GMS_SHA1) "是" else "否",
                                valueColor = if (cert.sha1 == GmsSignatureSpoof.GMS_SHA1) Success else Warning,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpoofMode.entries.forEach { mode ->
                            GlassRadioRow(
                                selected = spoofMode == mode,
                                title = mode.label,
                                subtitle = when (mode) {
                                    SpoofMode.OFF -> "默认。容器行为与未启用伪造时完全一致"
                                    SpoofMode.TEST -> "自签证书。能验证伪造链路是否打通，但骗不过 microG"
                                    SpoofMode.OFFICIAL -> "需自备证书。真正能让 microG 认可的模式"
                                },
                                accent = when (mode) {
                                    SpoofMode.OFF -> InkFaint
                                    SpoofMode.TEST -> Warning
                                    SpoofMode.OFFICIAL -> Success
                                },
                                onClick = { vm.setSpoofMode(mode) },
                            )
                        }
                    }

                    if (spoofMode != SpoofMode.OFF) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "证书投放位置：<filesDir>/dooble/signature/ 或 /sdcard/Android/data/<包名>/files/dooble/signature/。",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    }
                }

                // -------------------------------------------------- 谷歌账号
                GlassSection(
                    title = "谷歌账号",
                    subtitle = "${accounts.size} 个账号 · 空间间互相隔离",
                    icon = Icons.Filled.Person,
                    accent = MorandiBlue,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        if (accounts.isEmpty()) {
                            Text("该空间还没有登录 Google 账号。", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
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
                                    AppIconBadge(icon = null, label = acc.name, accent = accent, size = 34.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = acc.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    GlassButton(
                                        text = "移除",
                                        onClick = { vm.removeAccount(acc.name) },
                                        variant = ButtonVariant.Subtle,
                                        compact = true,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        GlassButton(
                            text = if (accounts.isEmpty()) "登录 Google 账号" else "再添加一个账号",
                            onClick = { vm.signIn() },
                            variant = ButtonVariant.Accent,
                            accent = MorandiBlue,
                            enabled = provisioned,
                            modifier = Modifier.fillMaxWidth(),
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
                            text = GoogleAccountBridge.SIGN_IN_HINT,
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    }
                }

                // -------------------------------------------------- 应用商店
                GlassSection(
                    title = "应用商店",
                    subtitle = if (storeInstalled) "Aurora Store 已就绪" else "未安装",
                    icon = Icons.Filled.ShoppingCart,
                    accent = if (storeInstalled) Success else MorandiClay,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        Text(
                            text = if (storeInstalled) "空间内已有应用商店" else "空间内还没有应用商店",
                            style = MaterialTheme.typography.titleLarge,
                            color = Ink,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "装入 Aurora Store 后，就能在这个空间里登录 Google 账号、搜索并下载应用。下载的 APK 由容器拦截后装进同一个空间，不会跑到宿主机上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )

                        if (storeInstalled) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp)
                                    .background(Warning.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            ) {
                                StatusDot(Warning, modifier = Modifier.padding(top = 4.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Aurora 里的「Google」按钮在容器内不可用，请改用「匿名」会话（Aurora 官方支持，不需要 Google 账号，一样能搜索和下载应用）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkSoft,
                                )
                            }
                        }

                        storeStep?.let { s ->
                            Spacer(Modifier.height(10.dp))
                            GlassProgress(
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
                                Spacer(Modifier.height(6.dp))
                                Text(r.message, style = MaterialTheme.typography.bodySmall, color = Danger)
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton(
                                text = if (storeInstalled) "重新装入商店" else "装入应用商店",
                                onClick = { vm.installStore() },
                                variant = ButtonVariant.Accent,
                                accent = MorandiClay,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            )
                            if (storeInstalled) {
                                GlassButton(
                                    text = "打开",
                                    onClick = {
                                        apps.firstOrNull { it.packageName == STORE_PACKAGE }
                                            ?.let { vm.launchApp(it.packageName) }
                                    },
                                    variant = ButtonVariant.Accent,
                                    accent = MorandiSage,
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                }

                // -------------------------------------------------- 空间内应用
                GlassSection(
                    title = "空间内应用",
                    subtitle = "${apps.size} 个应用 · 双击卸载 · 长按打开操作菜单",
                    icon = Icons.Filled.Star,
                    accent = accent,
                    initiallyExpanded = apps.isNotEmpty(),
                ) {
                    if (apps.isEmpty()) {
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
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
                                onLongClick = { actionApp = app },
                                onDoubleClick = { vm.uninstall(app.packageName) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    AppListAccessBanner(
                        verdict = hostVisibility,
                        accent = accent,
                        onOpen = { target -> openGuide(context, target) },
                    )
                    if (hostVisibility?.gated == true) Spacer(Modifier.height(8.dp))

                    GlassButton(
                        text = "从已安装应用导入",
                        onClick = {
                            showHostImport = true
                            vm.scanHostApps()
                        },
                        variant = ButtonVariant.Primary,
                        leading = {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton(
                        text = "从文件装入 APK",
                        onClick = {
                            apkPicker.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/octet-stream",
                                )
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "推荐用「从已安装应用导入」：容器直接读系统的安装路径，不需要存储权限。两条路目前都只装入基础 APK，AAB 拆分包会缺资源。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                }

                // -------------------------------------------------- 桌面快捷方式
                GlassSection(
                    title = "桌面快捷方式",
                    subtitle = if (apps.isEmpty()) "空间内还没有应用" else "${apps.size} 个可发",
                    icon = Icons.Filled.Star,
                    accent = MorandiSage,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        Text(
                            text = "把空间里的应用直接钉到系统桌面。点桌面图标就等于在空间里启动它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(SHORTCUT_ROM_CAVEAT, style = MaterialTheme.typography.labelSmall, color = InkFaint)
                        Spacer(Modifier.height(10.dp))
                        GlassButton(
                            text = "发到桌面…",
                            onClick = { showShortcuts = true },
                            variant = ButtonVariant.Accent,
                            accent = MorandiSage,
                            enabled = apps.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // -------------------------------------------------- 危险区
                GlassSection(
                    title = "危险操作",
                    subtitle = "不可逆，请谨慎",
                    icon = Icons.Filled.Delete,
                    accent = Danger,
                ) {
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        tint = Danger.copy(alpha = 0.06f),
                        borderColor = Danger.copy(alpha = 0.25f),
                        contentPadding = 14.dp,
                    ) {
                        Text(
                            "删除该空间会连同空间内全部数据、账号一起永久清除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassButton(
                            text = "删除该空间",
                            onClick = { showDeleteConfirm = true },
                            variant = ButtonVariant.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 子组件

@Composable
private fun DesktopTile(
    app: VirtualApp,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    val icon = rememberVirtualAppIcon(app.packageName, app.apkPath, app.versionCode)
    val (uninstallModifier, requestUninstall) = rememberUninstallAnimation(onDoubleClick)

    Column(
        modifier = modifier
            .then(uninstallModifier)
            .glassClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick?.let { { requestUninstall() } },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconBadge(icon = icon, label = app.label, accent = accent, size = 52.dp)
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
    accent: Color,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    val icon = rememberVirtualAppIcon(app.packageName, app.apkPath, app.versionCode)
    val (uninstallModifier, requestUninstall) = rememberUninstallAnimation(onDoubleClick)

    GlassCard(
        Modifier
            .fillMaxWidth()
            .then(uninstallModifier),
        shape = RoundedCornerShape(16.dp),
        onClick = onLaunch,
        onLongClick = onLongClick,
        onDoubleClick = onDoubleClick?.let { { requestUninstall() } },
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(icon = icon, label = app.label, accent = accent, size = 42.dp)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = InkSoft, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (app.usesGoogleApis) {
                        GlassChip(text = "依赖 GMS", background = Success.copy(alpha = 0.14f), contentColor = Success)
                    }
                    if (app.isMicroG) {
                        GlassChip(text = "microG 组件", background = Color.White.copy(alpha = 0.5f))
                    }
                    GlassChip(text = "v${app.versionName}", background = Color.White.copy(alpha = 0.5f))
                }
            }

            Spacer(Modifier.width(8.dp))

            GlassIconButton(
                icon = Icons.Filled.PlayArrow,
                contentDescription = "启动",
                onClick = onLaunch,
                tint = Ink,
                background = MorandiSage.copy(alpha = 0.3f),
                size = 32.dp,
            )
            GlassIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "结束运行",
                onClick = onStop,
                tint = Warning,
                background = Color.White.copy(alpha = 0.5f),
                size = 32.dp,
            )
        }
    }
}

/**
 * 双击卸载的动画辅助：先淡出+缩小，动画结束后才真正调用 onDoubleClick（卸载）。
 *
 * 返回 (动画 Modifier, 触发函数)。触发函数只在首次调用时生效，
 * 防止动画播放期间重复双击导致多次卸载。
 */
@Composable
private fun rememberUninstallAnimation(onDoubleClick: (() -> Unit)?): Pair<Modifier, () -> Unit> {
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val alpha by animateFloatAsState(
        targetValue = if (removing) 0f else 1f,
        animationSpec = tween(220),
        label = "uninstallAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (removing) 0.85f else 1f,
        animationSpec = tween(220),
        label = "uninstallScale",
    )
    val modifier = Modifier.graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
    }
    val request: () -> Unit = {
        if (!removing && onDoubleClick != null) {
            removing = true
            scope.launch {
                delay(240)
                onDoubleClick()
            }
        }
    }
    return modifier to request
}

private fun openGuide(context: android.content.Context, target: com.dooble.space.system.AppListAccess.GuideTarget) {
    runCatching { context.startActivity(target.intent) }
        .onFailure { android.util.Log.w("DoobleDetail", "打开引导入口失败：${target.label}", it) }
}