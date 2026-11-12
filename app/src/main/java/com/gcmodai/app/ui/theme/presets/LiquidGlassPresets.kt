package com.gcmodai.app.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.gcmodai.app.ui.theme.PresetTheme

/**
 * 液态玻璃主题组。
 *
 * 为什么单独放一组：原来的 7 个预设是"扁平卡片"时代调的配色（都是低饱和 surface），
 * 放到玻璃底下会显得灰。这 4 个专为玻璃调过：
 *  · 壁纸用高饱和的青/紫/绿/橙做色团，玻璃折射后才有层次
 *  · surface 保持中高明度，保证玻璃上的文字对比度
 *  · 每个主题配一个专属的强调色，按钮和选中态一眼能认出来
 */
private fun glassTheme(
    id: String,
    label: String,
    seed: Color,
    accent: Color,
    tertiary: Color,
): PresetTheme = PresetTheme(
    id = id,
    name = { Text(label) },
    standardLight = lightColorScheme(
        primary = seed,
        onPrimary = Color.White,
        primaryContainer = seed.copy(alpha = 0.22f).compositeOver(Color.White),
        onPrimaryContainer = seed,
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color.White),
        onSecondaryContainer = accent,
        tertiary = tertiary,
        onTertiary = Color.White,
        background = Color(0xFFF4F6FB),
        onBackground = Color(0xFF14161C),
        surface = Color(0xFFF7F9FD),
        onSurface = Color(0xFF14161C),
        surfaceVariant = Color(0xFFE6EAF2),
        onSurfaceVariant = Color(0xFF474C58),
    ),
    standardDark = darkColorScheme(
        primary = seed,
        onPrimary = Color(0xFF0B0E14),
        primaryContainer = seed.copy(alpha = 0.30f).compositeOver(Color(0xFF0E1118)),
        onPrimaryContainer = seed,
        secondary = accent,
        onSecondary = Color(0xFF0B0E14),
        secondaryContainer = accent.copy(alpha = 0.26f).compositeOver(Color(0xFF0E1118)),
        onSecondaryContainer = accent,
        tertiary = tertiary,
        onTertiary = Color(0xFF0B0E14),
        background = Color(0xFF070910),
        onBackground = Color(0xFFEDEFF5),
        surface = Color(0xFF0B0E15),
        onSurface = Color(0xFFEDEFF5),
        surfaceVariant = Color(0xFF1B2029),
        onSurfaceVariant = Color(0xFFB9BFCC),
    ),
)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    if (a >= 1f) return this
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

/** 极光青：默认款，最像 iOS 26 的冷色玻璃 */
val LiquidAuroraThemePreset = glassTheme(
    id = "liquid_aurora",
    label = "极光青",
    seed = Color(0xFF2F7BFF),
    accent = Color(0xFF00C2C7),
    tertiary = Color(0xFF7B5CFF),
)

/** 午夜紫：深色下最出效果 */
val LiquidMidnightThemePreset = glassTheme(
    id = "liquid_midnight",
    label = "午夜紫",
    seed = Color(0xFF8B5CF6),
    accent = Color(0xFFC084FC),
    tertiary = Color(0xFF4F46E5),
)

/** 薄荷绿：护眼、清爽 */
val LiquidMintThemePreset = glassTheme(
    id = "liquid_mint",
    label = "薄荷绿",
    seed = Color(0xFF10B981),
    accent = Color(0xFF34D399),
    tertiary = Color(0xFF0EA5E9),
)

/** 落日橙：暖色玻璃，晚上看着舒服 */
val LiquidSunsetThemePreset = glassTheme(
    id = "liquid_sunset",
    label = "落日橙",
    seed = Color(0xFFF97316),
    accent = Color(0xFFFB7185),
    tertiary = Color(0xFFA855F7),
)

val LiquidGlassPresets = listOf(
    LiquidAuroraThemePreset,
    LiquidMidnightThemePreset,
    LiquidMintThemePreset,
    LiquidSunsetThemePreset,
)
