package com.dooble.space.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Success

/**
 * 不可逆操作的二次确认（亚克力风格）。
 *
 * 保留了上一版的安全机制：代价明细 + 强制勾选后才放行确认按钮。
 * 视觉上换成圆角玻璃卡片。
 */
@Composable
fun GlassConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    facts: List<Pair<String, String>> = emptyList(),
    ackText: String? = null,
    dismissText: String = "取消",
    danger: Boolean = true,
) {
    var acked by remember { mutableStateOf(false) }
    val canConfirm = ackText == null || acked

    val accent = if (danger) Danger else Ink

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(20.dp, GlassShapes.Card, ambientColor = Color(0x33000000), spotColor = Color(0x22000000))
                .clip(GlassShapes.Card)
                .background(Color(0xF7FFFFFF))
                .border(1.dp, Color.White.copy(alpha = 0.8f), GlassShapes.Card)
                .verticalScroll(rememberScrollState())
        ) {
            // 顶部警示条
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
            }

            Column(Modifier.padding(18.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )

                if (facts.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.6f))
                            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        facts.forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = k,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkFaint,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = v,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink,
                                )
                            }
                        }
                    }
                }

                if (ackText != null) {
                    Spacer(Modifier.height(14.dp))
                    AckRow(
                        text = ackText,
                        checked = acked,
                        onToggle = { acked = !acked },
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(
                        text = dismissText,
                        onClick = onDismiss,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(
                        text = confirmText,
                        onClick = onConfirm,
                        variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                        enabled = canConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AckRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .border(2.dp, if (checked) Success else InkFaint, RoundedCornerShape(8.dp))
                .background(if (checked) Success else Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Ink,
            modifier = Modifier.weight(1f),
        )
    }
}