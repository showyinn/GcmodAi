package com.gcmodai.app.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.gcmodai.app.ui.theme.CustomTheme
import com.gcmodai.app.ui.theme.PresetTheme

/**
 * MD3 Expressive 高级配色组。
 *
 * 做法上刻意分成两类，覆盖"想省事"和"想较真"两种用户：
 *  1. 手工调过的 4 套（极光青 / 午夜紫 / 薄荷绿 / 落日橙）
 *     —— 直接给完整的 light/dark ColorScheme，明度对比经过手调，深浅都好看；
 *  2. 用 Material color spec 派生的 4 套（鲜艳 / 极简灰 / 跳脱 / 单色）
 *     —— 只给一颗种子色 + 一个 Variant，由 [CustomTheme.generateColorScheme] 生成整套配色，
 *        保证符合 Material 的对比度与色调曲线，不会出现"自己调出来的主题字看不清"。
 */
private fun preset(
    id: String,
    label: String,
    light: androidx.compose.material3.ColorScheme,
    dark: androidx.compose.material3.ColorScheme,
): PresetTheme = PresetTheme(
    id = id,
    name = { Text(label) },
    standardLight = light,
    standardDark = dark,
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

/** 手工配色的四套：明暗两套都给全，保证深浅模式都成立 */
private fun handTuned(
    id: String,
    label: String,
    seed: Color,
    accent: Color,
    tertiary: Color,
): PresetTheme = preset(
    id = id,
    label = label,
    light = lightColorScheme(
        primary = seed,
        onPrimary = Color.White,
        primaryContainer = seed.copy(alpha = 0.18f).compositeOver(Color.White),
        onPrimaryContainer = seed,
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color.White),
        onSecondaryContainer = accent,
        tertiary = tertiary,
        onTertiary = Color.White,
        background = Color(0xFFF6F7FB),
        onBackground = Color(0xFF14161C),
        surface = Color(0xFFFAFBFE),
        onSurface = Color(0xFF14161C),
        surfaceVariant = Color(0xFFE4E7F0),
        onSurfaceVariant = Color(0xFF454A57),
    ),
    dark = darkColorScheme(
        primary = seed,
        onPrimary = Color(0xFF0A0D12),
        primaryContainer = seed.copy(alpha = 0.26f).compositeOver(Color(0xFF0D1017)),
        onPrimaryContainer = seed,
        secondary = accent,
        onSecondary = Color(0xFF0A0D12),
        secondaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color(0xFF0D1017)),
        onSecondaryContainer = accent,
        tertiary = tertiary,
        onTertiary = Color(0xFF0A0D12),
        background = Color(0xFF08090D),
        onBackground = Color(0xFFEDEFF5),
        surface = Color(0xFF0B0D13),
        onSurface = Color(0xFFEDEFF5),
        surfaceVariant = Color(0xFF1A1E27),
        onSurfaceVariant = Color(0xFFB8BECB),
    ),
)

/** 用 Material color spec 派生的主题：只给种子色 + Variant */
private fun specDerived(
    id: String,
    label: String,
    seedArgb: Long,
    variant: String,
): PresetTheme {
    val spec = CustomTheme(
        id = id,
        name = label,
        primaryColorArgb = seedArgb,
        variant = variant,
    )
    return preset(
        id = id,
        label = label,
        light = spec.generateColorScheme(dark = false),
        dark = spec.generateColorScheme(dark = true),
    )
}

val AuroraBlueThemePreset = handTuned(
    id = "aurora_blue",
    label = "极光青",
    seed = Color(0xFF2F7BFF),
    accent = Color(0xFF00B3C7),
    tertiary = Color(0xFF7B5CFF),
)

val MidnightPurpleThemePreset = handTuned(
    id = "midnight_purple",
    label = "午夜紫",
    seed = Color(0xFF8B5CF6),
    accent = Color(0xFFC084FC),
    tertiary = Color(0xFF4F46E5),
)

val MintThemePreset = handTuned(
    id = "mint_green",
    label = "薄荷绿",
    seed = Color(0xFF10B981),
    accent = Color(0xFF34D399),
    tertiary = Color(0xFF0EA5E9),
)

val SunsetThemePreset = handTuned(
    id = "sunset_orange",
    label = "落日橙",
    seed = Color(0xFFF97316),
    accent = Color(0xFFFB7185),
    tertiary = Color(0xFFA855F7),
)

val VividThemePreset = specDerived(
    id = "spec_vivid",
    label = "鲜艳",
    seedArgb = 0xFFE11D48,
    variant = "VIBRANT",
)

val ExpressiveThemePreset = specDerived(
    id = "spec_expressive",
    label = "跳脱",
    seedArgb = 0xFF0EA5E9,
    variant = "EXPRESSIVE",
)

val MinimalGrayThemePreset = specDerived(
    id = "spec_neutral",
    label = "极简灰",
    seedArgb = 0xFF556070,
    variant = "NEUTRAL",
)

val MonoThemePreset = specDerived(
    id = "spec_mono",
    label = "单色",
    seedArgb = 0xFF2F5FE0,
    variant = "MONOCHROME",
)

/** 高级配色组：放在预设列表最前面 */
val AdvancedPresetThemes = listOf(
    AuroraBlueThemePreset,
    MidnightPurpleThemePreset,
    MintThemePreset,
    SunsetThemePreset,
    VividThemePreset,
    ExpressiveThemePreset,
    MinimalGrayThemePreset,
    MonoThemePreset,
)
