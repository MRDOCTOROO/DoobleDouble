package com.dooble.space.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 后现代设计令牌。
 *
 * 视觉主张（Postmodern / Neo-Brutalist）：
 *  - **不掩饰结构**：3dp 纯黑描边 + 硬投影，没有圆角、没有渐变、没有柔和阴影。
 *    后现代反对现代主义的"无装饰即是高级"，把装饰和结构重新摆到台面上。
 *  - **高饱和撞色**：酸性黄、电光蓝、荧光粉并置，故意不做和谐过渡。
 *  - **字体混排**：标题用超粗无衬线，标签用等宽字体（monospace），
 *    像系统日志一样裸露技术感。
 *  - **轻微错位**：卡片允许 ±1° 旋转，打破网格的绝对秩序。
 *
 * 同时它必须**在浅色主题下可用**：底色是纸白，文字是纯黑，
 * 所有色块都保证 4.5:1 以上对比度。
 */

// ------------------------------------------------------------------ 色板

val Paper = Color(0xFFF3EFE6)
val PaperDeep = Color(0xFFE6DFD0)
val Ink = Color(0xFF111111)
val InkSoft = Color(0xFF4A4A4A)
val InkFaint = Color(0xFF8A857A)

val Acid = Color(0xFFE8FF3A)
val Electric = Color(0xFF2B4CFF)
val HotPink = Color(0xFFFF4D9D)
val Mint = Color(0xFF9BF6C1)
val Tangerine = Color(0xFFFF7A3D)
val Violet = Color(0xFFB48CFF)
val Sky = Color(0xFF6FD3FF)
val Danger = Color(0xFFD62B2B)
val Success = Color(0xFF14875A)

/** 空间主题色循环。新建空间时按序号取色。 */
val SpaceAccents = listOf(Electric, HotPink, Tangerine, Mint, Violet, Sky, Acid)

fun accentOf(index: Int): Color = SpaceAccents[((index % SpaceAccents.size) + SpaceAccents.size) % SpaceAccents.size]

// ------------------------------------------------------------------ 字体

val Display = FontFamily.SansSerif
val Mono = FontFamily.Monospace

val DoobleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        letterSpacing = 0.6.sp,
    ),
)

// ------------------------------------------------------------------ 主题

private val DoobleColorScheme = lightColorScheme(
    primary = Electric,
    onPrimary = Color.White,
    primaryContainer = Acid,
    onPrimaryContainer = Ink,
    secondary = HotPink,
    onSecondary = Ink,
    secondaryContainer = Mint,
    onSecondaryContainer = Ink,
    tertiary = Tangerine,
    onTertiary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = Ink,
    outlineVariant = InkFaint,
    error = Danger,
    onError = Color.White,
)

@Composable
fun DoobleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DoobleColorScheme,
        typography = DoobleTypography,
        content = content,
    )
}
