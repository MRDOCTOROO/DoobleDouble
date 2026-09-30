package com.dooble.space

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dooble.space.system.ShortcutPublisher
import com.dooble.space.ui.Screen
import com.dooble.space.ui.ScreenNavHost
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.components.AuroraBackground
import com.dooble.space.ui.components.ButtonVariant
import com.dooble.space.ui.components.GlassButton
import com.dooble.space.ui.components.GlassCard
import com.dooble.space.ui.screens.SelfCheckScreen
import com.dooble.space.ui.screens.SettingsScreen
import com.dooble.space.ui.screens.SpaceDetailScreen
import com.dooble.space.ui.screens.SpacesScreen
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.DoobleTheme
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
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

@Composable
private fun DoobleRoot(pendingSpaceId: MutableStateFlow<String?>) {
    val boot by DoobleContainer.boot.collectAsState()
    val app = LocalContext.current.applicationContext as Application
    when (val state = boot) {
        is BootState.Booting -> BootScreen()
        is BootState.Failed -> BootFailureScreen(state.message, onRetry = {
            DoobleContainer.retryBootstrap(app)
        })
        is BootState.Ready -> DoobleMain(state, pendingSpaceId)
    }
}

@Composable
private fun DoobleMain(
    boot: BootState.Ready,
    pendingSpaceId: MutableStateFlow<String?>,
) {
    val vm: SpaceViewModel = viewModel()
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

    LifecycleResumeEffect(Unit) {
        vm.refreshPowerState()
        vm.refreshKeeper()
        onPauseOrDispose { }
    }

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

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ScreenNavHost(initial = Screen.Spaces) { screen, navigate, pop ->
                when (screen) {
                    is Screen.Spaces -> {
                        LaunchedEffect(pending, spaces) {
                            val id = pending ?: return@LaunchedEffect
                            if (spaces.none { it.id == id }) return@LaunchedEffect
                            vm.selectSpace(id)
                            navigate(Screen.Detail(id))
                            pendingSpaceId.value = null
                        }
                        SpacesScreen(
                            vm = vm,
                            boot = boot,
                            onOpenSpace = { id ->
                                vm.selectSpace(id)
                                navigate(Screen.Detail(id))
                            },
                            onOpenSettings = { navigate(Screen.Settings) },
                        )
                    }

                    is Screen.Settings -> SettingsScreen(
                        boot = boot,
                        keeper = keeper,
                        powerState = powerState,
                        romLabel = vm.romLabel(),
                        autostartAvailable = vm.autostartSettingsAvailable(),
                        onBack = pop,
                        onClearCache = vm::clearGmsCache,
                        onToggleKeeper = vm::toggleKeeper,
                        onReclaimNow = vm::reclaimNow,
                        onRequestIgnoreBattery = vm::requestIgnoreBatteryOptimizations,
                        onOpenAutostart = vm::openAutostartSettings,
                    )

                    is Screen.Detail -> SpaceDetailScreen(
                        vm = vm,
                        spaceId = screen.spaceId,
                        onBack = {
                            vm.selectSpace(null)
                            pop()
                        },
                        onOpenSelfCheck = { navigate(Screen.Check(screen.spaceId)) },
                    )

                    is Screen.Check -> SelfCheckScreen(
                        vm = vm,
                        spaceId = screen.spaceId,
                        onBack = pop,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 启动态

@Composable
private fun BootScreen() {
    AuroraBackground(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Dooble",
                    style = MaterialTheme.typography.displayLarge,
                    color = Ink,
                )
                Text(
                    text = "Double",
                    style = MaterialTheme.typography.displayLarge,
                    color = InkFaint,
                )
                GlassCard(
                    Modifier.padding(top = 18.dp),
                    contentPadding = 10.dp,
                ) {
                    Text("正在装配虚拟环境", style = MaterialTheme.typography.labelMedium, color = InkSoft)
                }
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 24.dp),
                    color = Ink,
                    strokeWidth = 3.dp,
                )
            }
        }
    }
}

@Composable
private fun BootFailureScreen(message: String, onRetry: () -> Unit) {
    AuroraBackground(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("引擎启动失败", style = MaterialTheme.typography.headlineMedium, color = Danger)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                    Text(
                        "常见原因：native 库与设备 ABI 不匹配、存储权限被拒、或空间目录被占用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint,
                    )
                    GlassButton(
                        text = "重试",
                        onClick = onRetry,
                        variant = ButtonVariant.Accent,
                        accent = Danger,
                    )
                }
            }
        }
    }
}