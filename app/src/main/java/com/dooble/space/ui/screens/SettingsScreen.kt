package com.dooble.space.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dooble.gms.MicroGRepository
import com.dooble.nativehook.NativeBridge
import com.dooble.space.BootState
import com.dooble.space.service.KeeperControl
import com.dooble.space.system.PowerOptimization
import com.dooble.space.ui.components.AuroraBackground
import com.dooble.space.ui.components.ButtonVariant
import com.dooble.space.ui.components.GlassButton
import com.dooble.space.ui.components.GlassCard
import com.dooble.space.ui.components.GlassSection
import com.dooble.space.ui.components.GlassTopBar
import com.dooble.space.ui.components.HairlineDivider
import com.dooble.space.ui.components.KeyValueRow
import com.dooble.space.ui.components.StatusDot
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.MorandiBlue
import com.dooble.space.ui.theme.MorandiClay
import com.dooble.space.ui.theme.MorandiSage
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Warning

@Composable
fun SettingsScreen(
    boot: BootState.Ready,
    keeper: KeeperControl.Status,
    powerState: PowerOptimization.State,
    romLabel: String,
    autostartAvailable: Boolean,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onToggleKeeper: () -> Unit,
    onReclaimNow: () -> Unit,
    onRequestIgnoreBattery: () -> Unit,
    onOpenAutostart: () -> Unit,
) {
    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GlassTopBar(
                title = "设置",
                subtitle = "Settings",
                onBack = onBack,
                largeTitle = false,
            )

            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {

                // -------------------------------------------------- 引擎
                GlassSection(
                    title = "引擎",
                    subtitle = "虚拟化实现与能力",
                    icon = Icons.Filled.Settings,
                    accent = MorandiBlue,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow("实现", boot.engineName)
                        HairlineDivider()
                        KeyValueRow("模式", if (boot.degraded) "降级沙箱（仅编排，不虚拟化）" else "完整虚拟化容器")
                        HairlineDivider()
                        KeyValueRow("容器级签名伪造", if (boot.degraded) "不支持" else "支持")
                        HairlineDivider()
                        KeyValueRow("多开", if (boot.degraded) "不支持" else "支持")
                    }
                }

                // -------------------------------------------------- Native
                GlassSection(
                    title = "Native 层",
                    subtitle = "IO 重定向与 ABI",
                    icon = Icons.Filled.Lock,
                    accent = MorandiSage,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow("libdooble_native", if (boot.nativeAvailable) "v${boot.nativeVersion}" else "未加载")
                        HairlineDivider()
                        KeyValueRow("IO 重定向", NativeBridge.summary())
                        HairlineDivider()
                        KeyValueRow("ABI", android.os.Build.SUPPORTED_ABIS.joinToString(", "))
                    }
                }

                // -------------------------------------------------- 空间保活
                GlassSection(
                    title = "空间保活",
                    subtitle = when {
                        !keeper.supported -> "当前模式不支持"
                        keeper.running -> "保活运行中"
                        keeper.enabled -> "已开启，服务未就绪"
                        else -> "已关闭"
                    },
                    icon = Icons.Filled.Refresh,
                    accent = if (keeper.running) Success else MorandiClay,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow(
                            key = "状态",
                            value = when {
                                !keeper.supported -> "当前模式不支持（没有虚拟进程可保活）"
                                keeper.enabled && keeper.running -> "保活中"
                                keeper.enabled -> "已开启，服务尚未就绪"
                                else -> "已关闭"
                            },
                            valueColor = when {
                                !keeper.supported -> InkSoft
                                keeper.enabled && keeper.running -> Success
                                keeper.enabled -> Warning
                                else -> InkSoft
                            },
                        )
                        HairlineDivider()
                        KeyValueRow("空间", keeper.spaceCount.toString())
                        HairlineDivider()
                        KeyValueRow("驻留进程", keeper.processCount.toString())
                        HairlineDivider()
                        KeyValueRow("钉住当前空间", if (keeper.pinnedSpaceId != null) "是" else "否")
                        keeper.lastReclaim?.let {
                            HairlineDivider()
                            KeyValueRow("最近回收", it)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "虚拟应用从宿主进程 fork 出来，宿主被回收，空间里的应用会一起消失。" +
                            "保活把宿主提到前台优先级，并在内存告急时主动回收最不常用的虚拟进程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassButton(
                        text = if (keeper.enabled) "关闭空间保活" else "开启空间保活",
                        onClick = onToggleKeeper,
                        variant = if (keeper.enabled) ButtonVariant.Secondary else ButtonVariant.Accent,
                        accent = Success,
                        enabled = keeper.supported,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton(
                        text = "立即按内存压力回收一次",
                        onClick = onReclaimNow,
                        variant = ButtonVariant.Secondary,
                        enabled = keeper.enabled && keeper.supported,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // -------------------------------------------------- 系统集成
                GlassSection(
                    title = "系统集成",
                    subtitle = when (powerState) {
                        PowerOptimization.State.IGNORED -> "已豁免电池优化"
                        PowerOptimization.State.OPTIMIZED -> "仍受优化"
                        PowerOptimization.State.UNKNOWN -> "状态未知"
                    },
                    icon = Icons.Filled.Lock,
                    accent = if (powerState == PowerOptimization.State.IGNORED) Success else Warning,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow(
                            key = "电源优化",
                            value = when (powerState) {
                                PowerOptimization.State.IGNORED -> "已加入白名单，Doze 不会冻结宿主"
                                PowerOptimization.State.OPTIMIZED -> "仍受优化，熄屏后可能被冻结"
                                PowerOptimization.State.UNKNOWN -> "系统低于 6.0 或查询失败"
                            },
                            valueColor = when (powerState) {
                                PowerOptimization.State.IGNORED -> Success
                                PowerOptimization.State.OPTIMIZED -> Warning
                                PowerOptimization.State.UNKNOWN -> InkSoft
                            },
                        )
                        HairlineDivider()
                        KeyValueRow("当前 ROM", romLabel)
                        HairlineDivider()
                        KeyValueRow(
                            key = "自启管理",
                            value = if (autostartAvailable) "有已知入口" else "无已知入口",
                            valueColor = if (autostartAvailable) Success else InkSoft,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    GlassButton(
                        text = when (powerState) {
                            PowerOptimization.State.IGNORED -> "已在白名单中，可再次确认"
                            else -> "申请关闭电源优化"
                        },
                        onClick = onRequestIgnoreBattery,
                        variant = if (powerState == PowerOptimization.State.IGNORED) ButtonVariant.Secondary else ButtonVariant.Accent,
                        accent = Success,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton(
                        text = "打开系统自启动管理",
                        onClick = onOpenAutostart,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // -------------------------------------------------- 谷歌环境来源
                GlassSection(
                    title = "谷歌环境来源",
                    subtitle = "microG 组件信息",
                    icon = Icons.Filled.ShoppingCart,
                    accent = MorandiBlue,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        KeyValueRow("组件", "microG（Apache-2.0）")
                        HairlineDivider()
                        KeyValueRow("版本", MicroGRepository.RELEASE_TAG)
                        HairlineDivider()
                        KeyValueRow("GmsCore", "com.google.android.gms")
                        HairlineDivider()
                        KeyValueRow("商店占位", "com.android.vending（FakeStore）")
                        HairlineDivider()
                        KeyValueRow("镜像回退", MicroGRepository.MIRROR_PREFIXES.size.toString() + " 级")
                    }

                    Spacer(Modifier.height(10.dp))
                    GlassButton(
                        text = "清理 microG 下载缓存",
                        onClick = onClearCache,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // -------------------------------------------------- 关于
                GlassSection(
                    title = "关于",
                    subtitle = "项目说明与免责声明",
                    icon = Icons.Filled.Info,
                    accent = MorandiSage,
                ) {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        Text(
                            "Dooble Double 是「多开空间 + 完整谷歌环境」的容器化运行框架。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "它复用两套成熟的开源方案：应用层虚拟化容器（Bcore / VirtualApp 血统）负责让未安装的 APK 跑起来、实现同应用多开；microG 提供 Play Services 的开源替代实现。两者之间的关键黏合剂是「容器级签名伪造」——不需要 root，也不需要改 ROM。",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "免责声明：本项目仅用于技术研究与个人多账号管理。请遵守你所在地区的法律法规以及各应用的服务条款。",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}