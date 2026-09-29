package com.dooble.space.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.dooble.space.ui.components.BrutalButton
import com.dooble.space.ui.components.BrutalSurface
import com.dooble.space.ui.components.DoobleTopBar
import com.dooble.space.ui.components.KeyValueRow
import com.dooble.space.ui.components.SectionRule
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Tangerine

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
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        DoobleTopBar(
            title = "设置",
            subtitle = "Settings",
            accent = Acid,
            onBack = onBack,
        )

        Column(Modifier.padding(16.dp)) {

            SectionRule("引擎")
            Spacer(Modifier.height(10.dp))
            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    KeyValueRow("实现", boot.engineName)
                    KeyValueRow("模式", if (boot.degraded) "降级沙箱（仅编排，不虚拟化）" else "完整虚拟化容器")
                    KeyValueRow("容器级签名伪造", if (boot.degraded) "不支持" else "支持")
                    KeyValueRow("多开", if (boot.degraded) "不支持" else "支持")
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionRule("Native 层")
            Spacer(Modifier.height(10.dp))
            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    KeyValueRow("libdooble_native", if (boot.nativeAvailable) "v${boot.nativeVersion}" else "未加载")
                    KeyValueRow("IO 重定向", NativeBridge.summary())
                    KeyValueRow("ABI", android.os.Build.SUPPORTED_ABIS.joinToString(", "))
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionRule(
                label = "空间保活",
                trailing = when {
                    !keeper.supported -> "N/A"
                    keeper.running -> "RUNNING"
                    keeper.enabled -> "PENDING"
                    else -> "OFF"
                },
            )
            Spacer(Modifier.height(10.dp))
            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
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
                            keeper.enabled -> Tangerine
                            else -> InkSoft
                        },
                    )
                    KeyValueRow("空间", keeper.spaceCount.toString())
                    KeyValueRow("驻留进程", keeper.processCount.toString())
                    KeyValueRow("钉住当前空间", if (keeper.pinnedSpaceId != null) "是" else "否")
                    keeper.lastReclaim?.let { KeyValueRow("最近回收", it) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "虚拟应用是从宿主进程 fork 出来的，宿主被系统回收，空间里所有运行中的应用会一起消失。" +
                    "保活服务把宿主提到前台优先级，并在内存告急时主动回收最不常用的虚拟进程" +
                    "——主动丢掉一个后台应用，总好过被系统连宿主一起端掉。",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )

            Spacer(Modifier.height(12.dp))
            BrutalButton(
                text = if (keeper.enabled) "关闭空间保活" else "开启空间保活",
                onClick = onToggleKeeper,
                background = if (keeper.enabled) PaperDeep else Acid,
                contentColor = Ink,
                enabled = keeper.supported,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            BrutalButton(
                text = "立即按内存压力回收一次",
                onClick = onReclaimNow,
                background = Ink,
                contentColor = Acid,
                enabled = keeper.enabled && keeper.supported,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // ------------------------------------------------ 系统集成
            //
            // 保活服务解决的是"进程优先级"，这里解决的是"系统愿不愿意动你"。
            // 两件事都必须做：前台服务在 Doze 里照样会被冻结，而光有白名单
            // 也顶不住国产 ROM 自己的后台清理。所以两块挨着放，并且各自
            // 说清楚管的是哪一层。
            SectionRule(
                label = "系统集成",
                trailing = when (powerState) {
                    PowerOptimization.State.IGNORED -> "已豁免"
                    PowerOptimization.State.OPTIMIZED -> "受优化"
                    PowerOptimization.State.UNKNOWN -> "N/A"
                },
            )
            Spacer(Modifier.height(10.dp))

            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    KeyValueRow(
                        key = "电源优化",
                        value = when (powerState) {
                            PowerOptimization.State.IGNORED -> "已加入白名单，Doze 不会冻结宿主"
                            PowerOptimization.State.OPTIMIZED -> "仍受优化，熄屏后可能被冻结"
                            PowerOptimization.State.UNKNOWN -> "系统低于 6.0 或查询失败"
                        },
                        valueColor = when (powerState) {
                            PowerOptimization.State.IGNORED -> Success
                            PowerOptimization.State.OPTIMIZED -> Tangerine
                            PowerOptimization.State.UNKNOWN -> InkSoft
                        },
                    )
                    KeyValueRow("当前 ROM", romLabel)
                    KeyValueRow(
                        key = "自启管理",
                        value = if (autostartAvailable) "有已知入口" else "无已知入口",
                        valueColor = if (autostartAvailable) Success else InkSoft,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "虚拟应用是从宿主进程 fork 出来的，系统在做 Doze 回收时按宿主 uid 判定。" +
                    "宿主一旦被判为「可优化」，整个进程组会在熄屏后一起被冻结——" +
                    "表现就是「空间里的应用过一会儿就没了」。把它加入白名单是唯一" +
                    "不需要 root 就能改变这个判定的办法。",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )

            Spacer(Modifier.height(12.dp))
            BrutalButton(
                text = when (powerState) {
                    PowerOptimization.State.IGNORED -> "已在白名单中，可再次确认"
                    else -> "申请关闭电源优化"
                },
                onClick = onRequestIgnoreBattery,
                background = if (powerState == PowerOptimization.State.IGNORED) PaperDeep else Acid,
                contentColor = Ink,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            BrutalButton(
                text = "打开系统自启动管理",
                onClick = onOpenAutostart,
                background = PaperDeep,
                contentColor = Ink,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "电池优化白名单管的是 AOSP 的 Doze，管不了国产 ROM 自己的后台冻结策略" +
                    "（小米神隐模式、华为应用启动管理、OPPO 耗电保护都不读它）。" +
                    "所以在国产 ROM 上这两步都要做。",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )

            Spacer(Modifier.height(24.dp))

            SectionRule("谷歌环境来源")
            Spacer(Modifier.height(10.dp))
            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    KeyValueRow("组件", "microG（Apache-2.0）")
                    KeyValueRow("版本", MicroGRepository.RELEASE_TAG)
                    KeyValueRow("GmsCore", "com.google.android.gms")
                    KeyValueRow("商店占位", "com.android.vending（FakeStore）")
                    KeyValueRow("镜像回退", MicroGRepository.MIRROR_PREFIXES.size.toString() + " 级")
                }
            }

            Spacer(Modifier.height(14.dp))
            BrutalButton(
                text = "清理 microG 下载缓存",
                onClick = onClearCache,
                background = Ink,
                contentColor = Acid,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            SectionRule("关于")
            Spacer(Modifier.height(10.dp))
            BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 14.dp) {
                Column {
                    Text(
                        "Dooble Double 是一个「多开空间 + 完整谷歌环境」的容器化运行框架。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "它复用两套成熟的开源方案：\n" +
                            "· 应用层虚拟化容器（Bcore / VirtualApp 血统，Apache-2.0）负责让未安装的 APK 跑起来、实现同应用多开；\n" +
                            "· microG 负责提供 Play Services 的开源替代实现，让应用能连上 Google。\n\n" +
                            "两者之间的关键黏合剂是「容器级签名伪造」：容器本来就要 hook PackageManagerService，" +
                            "我们只是在同一个 hook 点上，让 com.google.android.gms 对外呈现 Google 官方签名。" +
                            "因此不需要 root，也不需要改 ROM。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "免责声明：本项目仅用于技术研究与个人多账号管理。" +
                            "请遵守你所在地区的法律法规以及各应用的服务条款。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
