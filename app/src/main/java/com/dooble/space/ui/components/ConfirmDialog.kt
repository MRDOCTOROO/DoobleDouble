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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dooble.space.ui.theme.Danger
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep
import com.dooble.space.ui.theme.Success

/**
 * 不可逆操作的二次确认。
 *
 * ## 为什么要"勾选后才放行"，而不是只有两个按钮
 *
 * 光弹一个「确定 / 取消」挡不住真正的误触：用户手指本来在别处，
 * 弹窗出现的瞬间正好落在"确定"上，一次误触就穿透两层。
 * 更常见的是**反射性连点**——弹窗刚出现就按习惯点右边那个按钮。
 *
 * 所以这里把确认拆成**两个动作**：先勾「我已了解…」，再点「永久删除」。
 * 勾选框默认不选，位置也在按钮上方，手指必须主动移动。
 * 这两步之间没有时间压力，用户有充分机会看清自己删的是什么。
 *
 * ## 为什么要列出 facts
 *
 * "删除空间"这四个字对用户没有信息量——他不知道代价是 200MB 还是 20GB，
 * 也不知道里面挂着几个登录中的账号。把具体数字摆出来，
 * 用户才会真的停下来读一遍，而不是把弹窗当成流程上的一个点击。
 *
 * @param facts 会被一并销毁的具体内容，显示为键值行。空列表则不渲染该区块。
 * @param ackText 需要用户主动勾选才放行确认按钮。传 null 则退化为普通两按钮确认。
 */
@Composable
fun BrutalConfirmDialog(
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
    // 每次弹出都重新开始，避免上一次的勾选状态被带到下一次
    var acked by remember { mutableStateOf(false) }
    val canConfirm = ackText == null || acked

    val headerBg = if (danger) Danger else Ink
    val confirmBg = if (danger) Danger else Ink

    Dialog(onDismissRequest = onDismiss) {
        BrutalSurface(
            modifier = Modifier.fillMaxWidth(),
            background = Paper,
            shadowOffset = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {

                // -------------------------------------------------- 警示条
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(headerBg)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (danger) "⚠  " + title else title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }

                Column(Modifier.padding(16.dp)) {

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )

                    // ---------------------------------------------- 代价明细
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .border(2.dp, Ink)
                                .background(PaperDeep)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
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

                    // ---------------------------------------------- 勾选放行
                    if (ackText != null) {
                        Spacer(Modifier.height(14.dp))
                        AckRow(
                            text = ackText,
                            checked = acked,
                            onToggle = { acked = !acked },
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // ---------------------------------------------- 按钮
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrutalButton(
                            text = dismissText,
                            onClick = onDismiss,
                            background = PaperDeep,
                            contentColor = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        BrutalButton(
                            text = confirmText,
                            onClick = onConfirm,
                            background = confirmBg,
                            contentColor = Color.White,
                            enabled = canConfirm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 勾选行。
 *
 * 与 [BrutalRadioRow] 的区别不只是方块/圆点：单选行的点击热区是**整行**，
 * 因为它表达的是"选一个"；这里的勾选是**授权**，热区收在方块 + 文字上，
 * 让"点空白处也能过"这种事不发生。
 */
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
                .size(22.dp)
                .border(3.dp, Ink)
                .background(if (checked) Success else Paper),
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
