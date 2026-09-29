package com.dooble.space.ui.screens

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
import com.dooble.space.ui.components.BrutalButton
import com.dooble.space.ui.components.BrutalProgress
import com.dooble.space.ui.components.BrutalSurface
import com.dooble.space.ui.components.DoobleTopBar
import com.dooble.space.ui.components.SectionRule
import com.dooble.space.ui.theme.Acid
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Mint
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success
import com.dooble.space.ui.theme.Tangerine

@Composable
fun SelfCheckScreen(
    vm: SpaceViewModel,
    spaceId: String,
    onBack: () -> Unit,
) {
    val report by vm.report.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
    ) {
        DoobleTopBar(
            title = "谷歌环境自检",
            subtitle = "Self-Check · $spaceId",
            accent = Acid,
            onBack = onBack,
        )

        Column(Modifier.padding(16.dp)) {
            val r = report
            if (r == null) {
                BrutalSurface(Modifier.fillMaxWidth(), background = PaperDeep, contentPadding = 20.dp) {
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
            } else {
                // 总分
                BrutalSurface(Modifier.fillMaxWidth(), background = Paper, contentPadding = 16.dp) {
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
                        BrutalProgress(r.score / 100f, fill = statusColor(r.overall))
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

                Spacer(Modifier.height(20.dp))

                r.items.groupBy { it.group }.forEach { (group, items) ->
                    SectionRule(group, trailing = "${items.count { it.status == CheckStatus.PASS }}/${items.size}")
                    Spacer(Modifier.height(10.dp))
                    items.forEach { item ->
                        CheckRow(item)
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            Spacer(Modifier.height(6.dp))
            BrutalButton(
                text = "重新自检",
                onClick = { vm.runSelfCheck() },
                background = Ink,
                contentColor = Acid,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val color = statusColor(item.status)
    BrutalSurface(Modifier.fillMaxWidth(), background = Paper, shadowOffset = 3.dp, borderWidth = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(22.dp)
                    .border(3.dp, Ink)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusGlyph(item.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }
        }
    }
}

private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> Mint
    CheckStatus.WARN -> Tangerine
    CheckStatus.FAIL -> Danger
    CheckStatus.SKIP -> PaperDeep
}

private fun statusGlyph(status: CheckStatus): String = when (status) {
    CheckStatus.PASS -> "OK"
    CheckStatus.WARN -> "!"
    CheckStatus.FAIL -> "X"
    CheckStatus.SKIP -> "-"
}
