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
 * Dooble Double 视觉令牌 —— 亚克力 / 毛玻璃 + 莫兰迪。
 *
 * 设计语言从上一版的「新粗野主义」整体迁移到：
 *
 *  - **亚克力玻璃**：半透明白色叠加层 + 柔和高斯阴影 + 1dp 高光描边，
 *    卡片像一块悬浮在暖灰色画布上的磨砂玻璃。
 *  - **莫兰迪色板**：低饱和、带灰度的蓝 / 鼠尾草绿 / 雾粉 / 米白 / 灰紫，
 *    视觉安静，长时间阅读不刺眼。
 *  - **苹果式排版**：大标题左对齐 + 小字灰色副标题；正文 15sp、
 *    说明 13sp、脚注 11sp，层级靠字重与字号而非色块表达。
 *  - **折叠收纳**：所有长内容区块默认可折叠，展开才显示细节，
 *    首屏保持紧凑。
 */

// ------------------------------------------------------------------ 画布与文字

/** 页面底色：暖灰白，接近纸感但不刺眼 */
val Canvas = Color(0xFFF2F0EB)
val CanvasDeep = Color(0xFFE7E3DC)
val Ink = Color(0xFF3E3B36)
val InkSoft = Color(0xFF6F6A62)
val InkFaint = Color(0xFFA39D93)
val Hairline = Color(0x143E3B36)

// ------------------------------------------------------------------ 莫兰迪色板

val MorandiSage = Color(0xFFA3B5A6)
val MorandiBlue = Color(0xFF91A9B8)
val MorandiPink = Color(0xFFC7A8AE)
val MorandiBeige = Color(0xFFC9B79E)
val MorandiLavender = Color(0xFFAAA2B6)
val MorandiOlive = Color(0xFFB4AE8F)
val MorandiSlate = Color(0xFF8E9BA3)
val MorandiClay = Color(0xFFBE9E93)
val MorandiMoss = Color(0xFF8FA58F)

val Danger = Color(0xFFBC6F6F)
val Success = Color(0xFF7C9A80)
val Warning = Color(0xFFC0A06A)

/** 空间主题色循环。新建空间时按序号取色。 */
val SpaceAccents = listOf(
    MorandiBlue,
    MorandiSage,
    MorandiPink,
    MorandiBeige,
    MorandiLavender,
    MorandiClay,
    MorandiOlive,
)

fun accentOf(index: Int): Color =
    SpaceAccents[((index % SpaceAccents.size) + SpaceAccents.size) % SpaceAccents.size]

// ------------------------------------------------------------------ 兼容别名
// 保留旧命名，防止历史引用在迁移过程中漏改导致编译失败。

val Paper = Canvas
val PaperDeep = CanvasDeep
val Acid = MorandiOlive
val Electric = MorandiBlue
val HotPink = MorandiPink
val Mint = MorandiSage
val Tangerine = MorandiClay
val Violet = MorandiLavender
val Sky = MorandiSlate

// ------------------------------------------------------------------ 字体

private val Display = FontFamily.SansSerif

val DoobleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
    ),
)

// ------------------------------------------------------------------ 主题

private val DoobleColorScheme = lightColorScheme(
    primary = MorandiBlue,
    onPrimary = Color.White,
    primaryContainer = MorandiSage.copy(alpha = 0.35f),
    onPrimaryContainer = Ink,
    secondary = MorandiPink,
    onSecondary = Ink,
    secondaryContainer = MorandiLavender.copy(alpha = 0.35f),
    onSecondaryContainer = Ink,
    tertiary = MorandiClay,
    onTertiary = Ink,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White.copy(alpha = 0.6f),
    onSurface = Ink,
    surfaceVariant = Color.White.copy(alpha = 0.4f),
    onSurfaceVariant = InkSoft,
    outline = InkFaint,
    outlineVariant = Hairline,
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