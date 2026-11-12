package com.gcmodai.app.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.gcmodai.app.ui.hooks.rememberUserSettingsState

/**
 * 液态玻璃外壳：把用户设置里的强度/壁纸风格喂给 [GlassHost]。
 *
 * 单独抽出来是为了让「主题切换」变成纯数据驱动：
 * 设置页改 `glassLevel` / `glassWallpaper` / `glassAnimated`，
 * 这里立刻重算 spec，整屏玻璃（含所有页面）一起变，不需要重启。
 */
@Composable
fun GlassShell(content: @Composable () -> Unit) {
    val settings by rememberUserSettingsState()

    val level = settings.glassLevel.coerceIn(0, GlassSpec.Levels.lastIndex)
    val base = GlassSpec.Levels[level]
    val wallpaper = WallpaperStyle.entries[
        settings.glassWallpaper.coerceIn(0, WallpaperStyle.entries.lastIndex)
    ]
    val spec = base.copy(
        wallpaper = wallpaper,
        animatedWallpaper = settings.glassAnimated,
    )

    GlassHost(spec = spec) {
        content()
    }
}
