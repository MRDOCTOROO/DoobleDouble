package com.dooble.space.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dooble.gms.CheckItem
import com.dooble.gms.CheckStatus
import com.dooble.space.ui.SpaceViewModel
import com.dooble.space.ui.components.AuroraBackground
import com.dooble.space.ui.components.ButtonVariant
import com.dooble.space.ui.components.GlassButton
import com.dooble.space.ui.components.GlassCard
import com.dooble.space.ui.components.GlassProgress
import com.dooble.space.ui.components.GlassSection
import com.dooble.space.ui.components.GlassTopBar
import com.dooble.space.ui.components.StatusDot
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.MorandiSage
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Warning

@Composable
fun SelfCheckScreen(
    vm: SpaceViewModel,
    spaceId: String,
    onBack: () -> Unit,
) {
    val report by vm.report.collectAsState()
    val running by vm.selfCheckRunning.collectAsState()

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GlassTopBar(
                title = "谷歌环境自检",
                subtitle = "Self-Check · $spaceId",
                onBack = onBack,
                largeTitle = false,
            )

            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                val r = report
                when {
                    running -> {
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                            Column {
                                Text("正在自检…", style = MaterialTheme.typography.titleLarge, color = Ink)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "其中「网络」一项会真的发请求去探 Google 端点，代理不通时最长要等 6 秒。请稍候。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSoft,
                                )
                            }
                        }
                    }

                    r == null -> {
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                            Column {
                                Text("尚未运行自检", style = MaterialTheme.typography.titleLarge, color = Ink)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "自检会逐项验证容器能力、microG 组件、签名伪造、设备指纹与账号状态。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSoft,
                                )
                            }
                        }
                    }

                    else -> {
                        // 总分
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = r.score.toString(),
                                        style = MaterialTheme.typography.displayLarge,
                                        color = statusColor(r.overall),
                                    )
                                    Text(
                                        text = " / 100",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = InkFaint,
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                GlassProgress(r.score / 100f, fill = statusColor(r.overall))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = when (r.overall) {
                                        CheckStatus.PASS -> "全部通过。可以尝试登录 Google 账号了。"
                                        CheckStatus.WARN -> "有 ${r.warnings.size} 项警告。功能可用，但部分应用可能受限。"
                                        CheckStatus.FAIL -> "有 ${r.failures.size} 项失败。请先修复再尝试登录。"
                                        CheckStatus.SKIP -> "已跳过全部检查。"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkSoft,
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        r.items.groupBy { it.group }.forEach { (group, items) ->
                            GlassSection(
                                title = group,
                                subtitle = "${items.count { it.status == CheckStatus.PASS }}/${items.size} 通过",
                                accent = statusColor(items.first().status),
                            ) {
                                Column {
                                    items.forEach { item ->
                                        CheckRow(item)
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                GlassButton(
                    text = if (running) "正在自检…" else "重新自检",
                    onClick = { vm.runSelfCheckFor(spaceId) },
                    variant = ButtonVariant.Accent,
                    accent = MorandiSage,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val color = statusColor(item.status)
    GlassCard(
        Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StatusDot(color, size = 10.dp, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(3.dp))
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }
        }
    }
}

private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> Success
    CheckStatus.WARN -> Warning
    CheckStatus.FAIL -> Danger
    CheckStatus.SKIP -> InkFaint
}