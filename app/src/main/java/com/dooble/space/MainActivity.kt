package com.dooble.space

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dooble.space.system.ShortcutPublisher
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.components.BrutalButton
import com.dooble.space.ui.components.BrutalSurface
import com.dooble.space.ui.screens.SelfCheckScreen
import com.dooble.space.ui.screens.SettingsScreen
import com.dooble.space.ui.screens.SpaceDetailScreen
import com.dooble.space.ui.screens.SpacesScreen
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.DoobleTheme
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Paper
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * 由桌面快捷方式带进来的空间 id。
     *
     * 用 StateFlow 而不是直接读 intent：`singleTask` 的 Activity 被再次拉起时
     * 走的是 [onNewIntent]，Compose 侧的 `remember` 不会重建，必须有一个
     * 能触发重组的状态源。空串表示"没有待处理的目标"。
     */
    private val pendingSpaceId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingSpaceId.value = intent?.getStringExtra(ShortcutPublisher.EXTRA_SPACE_ID)
        setContent {
            DoobleTheme {
                DoobleRoot(pendingSpaceId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ShortcutPublisher.EXTRA_SPACE_ID)?.let {
            pendingSpaceId.value = it
        }
    }
}

/** 界面路由。只有 4 个目的地，用状态机比引入 Navigation 组件更直接。 */
sealed interface Screen {
    data object Spaces : Screen
    data object Settings : Screen
    data class Detail(val spaceId: String) : Screen
    data class Check(val spaceId: String) : Screen
}

@Composable
private fun DoobleRoot(pendingSpaceId: MutableStateFlow<String?>) {
    val boot by DoobleContainer.boot.collectAsState()
    when (val state = boot) {
        is BootState.Booting -> BootScreen()
        is BootState.Failed -> BootFailureScreen(state.message)
        is BootState.Ready -> DoobleMain(state, pendingSpaceId)
    }
}

@Composable
private fun DoobleMain(
    boot: BootState.Ready,
    pendingSpaceId: MutableStateFlow<String?>,
) {
    val vm: SpaceViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Spaces) }
    val snackbar = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()
    val keeper by vm.keeper.collectAsState()
    val powerState by vm.powerState.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val pending by pendingSpaceId.collectAsState()

    LaunchedEffect(toast) {
        val msg = toast
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.consumeToast()
        }
    }

    /*
     * 桌面快捷方式带进来的空间 id。
     *
     * 必须等 `spaces` 加载完再跳：`SpaceDetailScreen` 是从 spaces 里按 id 找空间的，
     * 列表还空着的时候跳过去会渲染出一个标题为"空间"的空页面。
     */
    LaunchedEffect(pending, spaces) {
        val id = pending ?: return@LaunchedEffect
        if (spaces.none { it.id == id }) return@LaunchedEffect
        vm.selectSpace(id)
        screen = Screen.Detail(id)
        pendingSpaceId.value = null
    }

    /*
     * 从系统弹窗返回时刷新电池优化状态。
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 是**没有返回值**的——
     * 系统弹窗点完，应用侧拿不到任何回调。唯一可靠的做法是在
     * ON_RESUME 时重新查一次。不用 LifecycleEventObserver 的 DisposableEffect
     * 是因为这里已经在 Compose 作用域里，`LifecycleResumeEffect` 更直接。
     */
    LifecycleResumeEffect(Unit) {
        vm.refreshPowerState()
        vm.refreshKeeper()
        onPauseOrDispose { }
    }

    /*
     * 通知权限只在用户真正开启保活时申请，不在冷启动时弹。
     *
     * 理由：Android 13 的权限弹窗是"一次性"的，第一次被拒之后系统不再弹。
     * 若在冷启动就弹，用户还没理解保活是干什么的，很容易随手拒绝，
     * 之后就再也申请不到了。放在用户主动按下开关的时刻，通过率完全不同。
     *
     * 被拒也不阻断流程：前台服务照常运行、照常获得前台优先级，
     * 只是通知栏上看不到那条常驻通知而已。
     */
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val context = LocalContext.current
    LaunchedEffect(keeper.enabled) {
        if (!keeper.enabled) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 返回键：从二级页面回到列表
    BackHandler(enabled = screen != Screen.Spaces) {
        screen = Screen.Spaces
        vm.selectSpace(null)
    }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                is Screen.Spaces -> SpacesScreen(
                    vm = vm,
                    boot = boot,
                    onOpenSpace = { id ->
                        vm.selectSpace(id)
                        screen = Screen.Detail(id)
                    },
                    onOpenSettings = { screen = Screen.Settings },
                )

                is Screen.Settings -> SettingsScreen(
                    boot = boot,
                    keeper = keeper,
                    powerState = powerState,
                    romLabel = vm.romLabel(),
                    autostartAvailable = vm.autostartSettingsAvailable(),
                    onBack = { screen = Screen.Spaces },
                    onClearCache = vm::clearGmsCache,
                    onToggleKeeper = vm::toggleKeeper,
                    onReclaimNow = vm::reclaimNow,
                    onRequestIgnoreBattery = vm::requestIgnoreBatteryOptimizations,
                    onOpenAutostart = vm::openAutostartSettings,
                )

                is Screen.Detail -> SpaceDetailScreen(
                    vm = vm,
                    spaceId = s.spaceId,
                    onBack = {
                        vm.selectSpace(null)
                        screen = Screen.Spaces
                    },
                    onOpenSelfCheck = { screen = Screen.Check(s.spaceId) },
                )

                is Screen.Check -> SelfCheckScreen(
                    vm = vm,
                    spaceId = s.spaceId,
                    onBack = { screen = Screen.Detail(s.spaceId) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 启动态

@Composable
private fun BootScreen() {
    Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "DOOBLE",
                style = MaterialTheme.typography.displayLarge,
                color = Ink,
            )
            Text(
                text = "DOUBLE",
                style = MaterialTheme.typography.displayLarge,
                color = Ink,
            )
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(180.dp)
                    .background(Acid)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("正在装配虚拟环境", style = MaterialTheme.typography.labelMedium, color = Ink)
            }
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 24.dp),
                color = Ink,
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
private fun BootFailureScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Paper).padding(24.dp), contentAlignment = Alignment.Center) {
        BrutalSurface(background = Paper, contentPadding = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("引擎启动失败", style = MaterialTheme.typography.headlineMedium, color = Danger)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                Text(
                    "常见原因：native 库与设备 ABI 不匹配、存储权限被拒、或空间目录被占用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
                BrutalButton(text = "重试", onClick = { }, background = Acid, contentColor = Ink)
            }
        }
    }
}
