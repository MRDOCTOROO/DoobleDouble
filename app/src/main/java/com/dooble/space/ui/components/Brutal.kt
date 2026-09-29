package com.dooble.space.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft
import com.dooble.space.ui.theme.Paper
import com.dooble.space.ui.theme.PaperDeep

/**
 * 无涟漪的点击修饰符。
 * 后现代/粗野主义刻意不要 Material 的水波纹——那太"圆滑"了。
 *
 * 长按支持通过 [onLongClick] 传入（用于"长按图标发送到桌面"这类场景）。
 * 不需要长按的调用点不用改 —— 默认值是 null，行为与之前完全一致。
 *
 * 参数顺序刻意把 onClick 放最后 —— 让现有调用点 `brutalClickable { ... }` 的
 * trailing lambda 继续绑到 onClick 上，不用批量改。
 *
 * combinedClickable 是实验 API，但稳定已久且短期内不会动。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.brutalClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * 后现代风格的基础容器：粗黑描边 + 硬投影 + 可选微旋转。
 *
 * 所有视觉元素都刻意"暴露结构"——描边不隐藏，投影是纯色硬边而不是模糊，
 * 这是新粗野主义对现代主义"精致"的一次反叛。
 */
@Composable
fun BrutalSurface(
    modifier: Modifier = Modifier,
    background: Color = Paper,
    borderWidth: Dp = 3.dp,
    shadowOffset: Dp = 6.dp,
    shadowColor: Color = Ink,
    rotation: Float = 0f,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.graphicsLayer { rotationZ = rotation }) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .background(shadowColor)
        )
        Box(
            Modifier
                .border(borderWidth, Ink)
                .background(background)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * 应用图标方块。
 *
 * 拿得到真图标就画真图标，拿不到才退回首字母 —— 而不是反过来。
 * 首字母方块是**降级态**，它表达的是"这个包读不出图标"，不是一种设计风格；
 * 但两种状态共用同一个 3dp 黑框与同一块尺寸，切换时不会跳版。
 */
@Composable
fun AppIconBadge(
    icon: ImageBitmap?,
    label: String,
    accent: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 3.dp,
) {
    Box(
        modifier
            .size(size)
            .border(borderWidth, Ink)
            .background(if (icon == null) accent else PaperDeep),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            // 图标自带的留白已经够看，只留 2dp 防止贴边；ContentScale.Fit
            // 保证非正方形图标不会被拉变形
            Image(
                bitmap = icon,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().padding(2.dp),
            )
        } else {
            Text(
                text = label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )
        }
    }
}

/**
 * 粗野主义按钮：按下时整体向右下位移，压到投影上，
 * 模拟"物理按键被按下去"的触感。没有任何涟漪动画。
 */
@Composable
fun BrutalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = Color.White,
    borderWidth: Dp = 3.dp,
    shadowOffset: Dp = 5.dp,
    enabled: Boolean = true,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(
        targetValue = if (pressed && enabled) shadowOffset else 0.dp,
        label = "brutalPress",
    )

    Box(modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadowOffset - shift, y = shadowOffset - shift)
                .background(if (enabled) Ink else InkFaint)
        )
        Row(
            modifier = Modifier
                .offset(x = shift, y = shift)
                .border(borderWidth, Ink)
                .background(if (enabled) background else PaperDeep)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(
                    horizontal = if (compact) 12.dp else 18.dp,
                    vertical = if (compact) 8.dp else 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) contentColor else InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 等宽小标签，用来裸露"技术感" */
@Composable
fun BrutalChip(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = PaperDeep,
    contentColor: Color = Ink,
) {
    Box(
        modifier
            .border(2.dp, Ink)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
    }
}

/** 章节分隔：等宽大写标签 + 粗横线 */
@Composable
fun SectionRule(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(3.dp).background(Ink))
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = InkSoft,
                maxLines = 1,
            )
        }
    }
}

/** 大号数字 + 小标签的统计块 */
@Composable
fun StatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium,
            color = Ink,
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(top = 2.dp)
                .height(4.dp)
                .width(28.dp)
                .background(accent)
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
        )
    }
}

/** 极简进度条：硬边、无圆角 */
@Composable
fun BrutalProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier
            .height(14.dp)
            .border(2.dp, Ink)
            .background(Paper)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(fill)
        )
    }
}

/** 空态占位：一句话 + 一个巨大的斜杠符号，故意做得不"友好" */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "//",
            style = MaterialTheme.typography.displayLarge,
            color = InkFaint,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 键值对行，等宽键名 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
