package com.dooble.space.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.dooble.space.ui.theme.Ink
import com.dooble.space.ui.theme.InkFaint
import com.dooble.space.ui.theme.InkSoft

// ---------------------------------------------------------------------------
// 形状
// ---------------------------------------------------------------------------

object GlassShapes {
    val Card = RoundedCornerShape(20.dp)
    val CardSmall = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(14.dp)
    val Field = RoundedCornerShape(14.dp)
    val Chip = RoundedCornerShape(50)
    val Sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}

// ---------------------------------------------------------------------------
// 点击修饰符（无涟漪）
// ---------------------------------------------------------------------------

/**
 * 无涟漪的点击修饰符。支持按压态缩放反馈（配合 [GlassCard] / [GlassButton]）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.glassClickable(
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    if (onLongClick != null || onDoubleClick != null) {
        combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
        )
    } else {
        clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

// ---------------------------------------------------------------------------
// 亚克力背景
// ---------------------------------------------------------------------------

/**
 * 全屏亚克力画布：暖灰底 + 几团莫兰迪色的柔光渐变。
 * 所有页面都包在它里面，玻璃卡片才有「透出底色」的质感。
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        // 三团低饱和柔光，模拟透过玻璃的朦胧色块
        SoftBlob(
            color = com.dooble.space.ui.theme.MorandiSage,
            x = -80f,
            y = -60f,
            size = 360,
        )
        SoftBlob(
            color = com.dooble.space.ui.theme.MorandiLavender,
            x = 180f,
            y = 240f,
            size = 320,
        )
        SoftBlob(
            color = com.dooble.space.ui.theme.MorandiBeige,
            x = -60f,
            y = 520f,
            size = 300,
        )
        content()
    }
}

@Composable
private fun SoftBlob(color: Color, x: Float, y: Float, size: Int) {
    Box(
        Modifier
            .offset(x = x.dp, y = y.dp)
            .size(size.dp)
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
                )
            )
    )
}

// ---------------------------------------------------------------------------
// 玻璃卡片
// ---------------------------------------------------------------------------

/**
 * 亚克力卡片：半透明白 + 高光描边 + 柔和阴影，圆角 20dp。
 * 可选点击（带轻微按压缩放）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    shape: Shape = GlassShapes.Card,
    tint: Color = Color.White.copy(alpha = 0.58f),
    borderColor: Color = Color.White.copy(alpha = 0.72f),
    elevation: Dp = 8.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = tween(120),
        label = "glassCardScale",
    )
    val clickable = when {
        onClick != null -> Modifier.glassClickable(
            interactionSource = interaction,
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
            onClick = onClick,
        )
        else -> Modifier
    }

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, shape, ambientColor = Color(0x1A000000), spotColor = Color(0x12000000))
            .clip(shape)
            .background(tint)
            .border(1.dp, borderColor, shape)
            .then(clickable)
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

// ---------------------------------------------------------------------------
// 按钮
// ---------------------------------------------------------------------------

enum class ButtonVariant {
    /** 深色主按钮（近似 iOS 的黑色实心按钮） */
    Primary,

    /** 莫兰迪主题色实心按钮 */
    Accent,

    /** 半透明白按钮 + 细描边 */
    Secondary,

    /** 危险操作 */
    Danger,

    /** 纯文字按钮 */
    Subtle,
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(100),
        label = "glassButtonScale",
    )

    val (bg, fg, borderColor) = when (variant) {
        ButtonVariant.Primary -> Triple(Ink, Color.White, Color.Transparent)
        ButtonVariant.Accent -> Triple(accent, Ink, Color.White.copy(alpha = 0.4f))
        ButtonVariant.Secondary -> Triple(Color.White.copy(alpha = 0.6f), Ink, Color.White.copy(alpha = 0.8f))
        ButtonVariant.Danger -> Triple(com.dooble.space.ui.theme.Danger, Color.White, Color.Transparent)
        ButtonVariant.Subtle -> Triple(Color.Transparent, InkSoft, Color.Transparent)
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(GlassShapes.Button)
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .border(1.dp, if (enabled) borderColor else Color.Transparent, GlassShapes.Button)
            .glassClickable(
                interactionSource = interaction,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(
                horizontal = if (compact) 14.dp else 18.dp,
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
            color = if (enabled) fg else InkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 圆形玻璃图标按钮 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ink,
    background: Color = Color.White.copy(alpha = 0.62f),
    size: Dp = 36.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(100),
        label = "glassIconScale",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(4.dp, RoundedCornerShape(size / 2), ambientColor = Color(0x14000000), spotColor = Color(0x0A000000))
            .clip(RoundedCornerShape(size / 2))
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(size / 2))
            .glassClickable(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

// ---------------------------------------------------------------------------
// 标签 / 统计 / 分隔线
// ---------------------------------------------------------------------------

/** 小药丸标签 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color.White.copy(alpha = 0.6f),
    contentColor: Color = InkSoft,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clip(GlassShapes.Chip)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.6f), GlassShapes.Chip)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 概览统计格：大数字 + 小标签 */
@Composable
fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(width = 20.dp, height = 3.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = InkFaint,
            maxLines = 1,
        )
    }
}

/** 键值行 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink,
    keyWidth: Dp = 104.dp,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
            modifier = Modifier.width(keyWidth),
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

/** 细分割线（iOS 分组列表风格） */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = com.dooble.space.ui.theme.Hairline,
    indent: Dp = 16.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(1.dp)
            .background(color)
    )
}

// ---------------------------------------------------------------------------
// 折叠区块
// ---------------------------------------------------------------------------

/**
 * 可折叠区块：点标题行展开 / 收起内容。所有长内容区块都用它，
 * 保证首屏紧凑，用户按需展开。
 */
@Composable
fun GlassSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    initiallyExpanded: Boolean = false,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .glassClickable { expanded = !expanded }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            } else {
                Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = InkFaint, maxLines = 1)
                }
            }
            if (headerTrailing != null) {
                headerTrailing()
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = InkFaint,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(content = content)
        }
    }
}

// ---------------------------------------------------------------------------
// 进度条 / 空状态
// ---------------------------------------------------------------------------

/** 圆角细进度条 */
@Composable
fun GlassProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 6.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "glassProgress",
    )
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color.White.copy(alpha = 0.55f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height)
                .background(fill, RoundedCornerShape(height / 2))
        )
    }
}

/** 空状态 */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Info,
) {
    Column(
        modifier.padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = InkFaint, modifier = Modifier.size(24.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 应用图标
// ---------------------------------------------------------------------------

/**
 * 应用图标：真图标优先；拿不到时退化为「莫兰迪色首字母圆角块」。
 * 两种状态共用同一圆角与尺寸，切换不跳版。
 */
@Composable
fun AppIconBadge(
    icon: ImageBitmap?,
    label: String,
    accent: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val corner = size * 0.22f
    Box(
        modifier
            .size(size)
            .shadow(3.dp, RoundedCornerShape(corner), ambientColor = Color(0x16000000), spotColor = Color(0x0A000000))
            .clip(RoundedCornerShape(corner))
            .background(if (icon == null) accent.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.8f))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().padding(3.dp),
            )
        } else {
            Text(
                text = label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 小状态圆点 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, RoundedCornerShape(size / 2)))
}