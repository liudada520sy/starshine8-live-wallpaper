package com.galaxy.starshine8.wallpaper.ui.theme

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galaxy.starshine8.wallpaper.core.contract.WallpaperPaletteColors

/**
 * 2025款吉利银河星耀8 黑曜石毛玻璃自适应座舱主题（StarshineCockpitTheme）。
 * 严格遵循 ADR-001 第 6 节与 garrytan-gstack /design-review 规范：
 * 1. 严禁高饱和霓虹紫/蓝渐变或刺眼纯白背景，卡片基底固定为深曜石半透明（#0B0E14, 75% Alpha）。
 * 2. 动态读取当前激活壁纸 manifest.json 中的 Palette 五色（vibrantHex / dominantHex / lightVibrantHex），
 *    在毛玻璃卡片背后投射柔和环境光晕，并绘制 1dp 线性渐变顶部高光描边（#1FFFFFFF -> 透明）。
 * 3. 驻车画报数字时钟采用 72sp 极细等宽字体（Tabular Light Numerals），正文字号不低于 18sp，保障驾驶位一臂距离外清晰可读。
 */
@Immutable
data class StarshineGlassPalette(
    val obsidianSurface: Color = Color(0xBF0B0E14),      // #0B0E14 at 75% Alpha
    val obsidianDeep: Color = Color(0xE607090D),         // #07090D at 90% Alpha
    val specularTopBorder: Color = Color(0x33FFFFFF),    // 顶部微高光描边
    val specularBottomBorder: Color = Color(0x0DFFFFFF), // 底部柔和收边
    val dominantAccent: Color = Color(0xFF1B2A38),
    val vibrantAccent: Color = Color(0xFFD49B54),
    val mutedAccent: Color = Color(0xFF4A6070),
    val darkVibrantAccent: Color = Color(0xFF0B131B),
    val lightVibrantText: Color = Color(0xFFF2DFC4),
    val secondaryText: Color = Color(0xB3F2DFC4)
)

val LocalStarshineGlassPalette = staticCompositionLocalOf { StarshineGlassPalette() }

val StarshineCockpitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 72.sp,
        lineHeight = 80.sp,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = "tnum" // 等宽数字对齐，防止秒针跳动抖动
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.25.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp, // 严格遵守 18sp 最小正文字号红线
        lineHeight = 26.sp,
        letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun StarshineCockpitTheme(
    manifestPalette: WallpaperPaletteColors = WallpaperPaletteColors(),
    content: @Composable () -> Unit
) {
    val glassPalette = remember(manifestPalette) {
        StarshineGlassPalette(
            dominantAccent = parseHexColorSafely(manifestPalette.dominantHex, Color(0xFF1B2A38)),
            vibrantAccent = parseHexColorSafely(manifestPalette.vibrantHex, Color(0xFFD49B54)),
            mutedAccent = parseHexColorSafely(manifestPalette.mutedHex, Color(0xFF4A6070)),
            darkVibrantAccent = parseHexColorSafely(manifestPalette.darkVibrantHex, Color(0xFF0B131B)),
            lightVibrantText = parseHexColorSafely(manifestPalette.lightVibrantHex, Color(0xFFF2DFC4))
        )
    }

    val colorScheme = remember(glassPalette) {
        darkColorScheme(
            primary = glassPalette.vibrantAccent,
            secondary = glassPalette.dominantAccent,
            tertiary = glassPalette.mutedAccent,
            background = Color.Transparent,
            surface = glassPalette.obsidianSurface,
            onPrimary = Color(0xFF0B0E14),
            onSecondary = glassPalette.lightVibrantText,
            onBackground = glassPalette.lightVibrantText,
            onSurface = glassPalette.lightVibrantText
        )
    }

    CompositionLocalProvider(LocalStarshineGlassPalette provides glassPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StarshineCockpitTypography,
            content = content
        )
    }
}

/**
 * 黑曜石毛玻璃容器修饰符：
 * 叠加 #0B0E14 (75% Alpha) 基底、自适应 Palette 径向环境光晕与 1dp 线性渐变高光描边。
 */
@Composable
fun Modifier.obsidianGlassCard(
    cornerRadius: Dp = 24.dp,
    glowIntensity: Float = 0.22f
): Modifier {
    val palette = LocalStarshineGlassPalette.current
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            palette.specularTopBorder,
            palette.vibrantAccent.copy(alpha = 0.28f),
            palette.specularBottomBorder
        ),
        start = Offset(0f, 0f),
        end = Offset(400f, 400f)
    )

    return this
        .clip(shape)
        .background(palette.obsidianSurface, shape)
        .drawBehind {
            // 叠加来自当前壁纸 vibrantHex 与 dominantHex 的柔和座舱环境光晕
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.vibrantAccent.copy(alpha = glowIntensity),
                        palette.dominantAccent.copy(alpha = glowIntensity * 0.6f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.18f, size.height * 0.15f),
                    radius = size.maxDimension * 0.85f
                )
            )
        }
        .border(width = 1.dp, brush = borderBrush, shape = shape)
}

private fun parseHexColorSafely(hex: String, fallback: Color): Color {
    return runCatching {
        Color(AndroidColor.parseColor(hex))
    }.getOrDefault(fallback)
}
