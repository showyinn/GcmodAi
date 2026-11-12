package com.gcmodai.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.gcmodai.app.ui.theme.presets.AutumnThemePreset
import com.gcmodai.app.ui.theme.presets.BlackThemePreset
import com.gcmodai.app.ui.theme.presets.ClaudeThemePreset
import com.gcmodai.app.ui.theme.presets.LiquidAuroraThemePreset
import com.gcmodai.app.ui.theme.presets.LiquidMidnightThemePreset
import com.gcmodai.app.ui.theme.presets.LiquidMintThemePreset
import com.gcmodai.app.ui.theme.presets.LiquidSunsetThemePreset
import com.gcmodai.app.ui.theme.presets.MinimalThemePreset
import com.gcmodai.app.ui.theme.presets.OceanThemePreset
import com.gcmodai.app.ui.theme.presets.SakuraThemePreset
import com.gcmodai.app.ui.theme.presets.SpringThemePreset

data class PresetTheme(
    val id: String,
    val name: @Composable () -> Unit,
    val standardLight: ColorScheme,
    val standardDark: ColorScheme,
) {
    fun getColorScheme(dark: Boolean): ColorScheme {
        return if (dark) standardDark else standardLight
    }
}

val PresetThemes by lazy {
    listOf(
        // 液态玻璃主题放最前面：新版默认就是玻璃，先让用户看到能换的 4 种玻璃配色
        LiquidAuroraThemePreset,
        LiquidMidnightThemePreset,
        LiquidMintThemePreset,
        LiquidSunsetThemePreset,
        SakuraThemePreset,
        OceanThemePreset,
        SpringThemePreset,
        AutumnThemePreset,
        BlackThemePreset,
        MinimalThemePreset,
        ClaudeThemePreset,
    )
}

fun findPresetTheme(id: String): PresetTheme {
    return PresetThemes.find { it.id == id } ?: SakuraThemePreset
}

fun findThemeById(id: String, customThemes: List<CustomTheme>): PresetTheme? {
    PresetThemes.find { it.id == id }?.let { return it }
    val custom = customThemes.find { it.id == id } ?: return null
    return PresetTheme(
        id = custom.id,
        name = { androidx.compose.material3.Text(custom.name) },
        standardLight = custom.generateColorScheme(dark = false),
        standardDark = custom.generateColorScheme(dark = true),
    )
}
