package com.gcmodai.app.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import com.gcmodai.app.ui.design.ButtonSize
import com.gcmodai.app.ui.design.ListDensity
import com.gcmodai.app.ui.design.LocalUiStyle
import com.gcmodai.app.ui.design.UiStyle
import com.gcmodai.app.ui.hooks.rememberAmoledDarkMode
import com.gcmodai.app.ui.hooks.rememberCurrentColorMode
import com.gcmodai.app.ui.hooks.rememberUserSettingsState
import com.gcmodai.app.utils.getActivity

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun GcmodAiTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    // 用户 DIY 的界面风格：圆角倍率 / 按钮尺寸 / 列表密度
    val uiStyle = remember(settings.cornerScale, settings.buttonSize, settings.listDensity) {
        UiStyle(
            cornerScale = settings.cornerScale,
            buttonSize = ButtonSize.entries[
                settings.buttonSize.coerceIn(0, ButtonSize.entries.lastIndex)
            ],
            density = ListDensity.entries[
                settings.listDensity.coerceIn(0, ListDensity.entries.lastIndex)
            ],
        )
    }

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    val activity = view.context.getActivity()
    // 浮窗可能使用 Application Context，没有可更新系统栏的 Activity。
    if (!view.isInEditMode && activity != null) {
        DisposableEffect(view, activity, darkTheme) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
        LocalUiStyle provides uiStyle,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            shapes = expressiveShapes(uiStyle.cornerScale),
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

/**
 * 由用户圆角倍率生成整站 Shapes。
 *
 * MD3 组件的圆角都从 MaterialTheme.shapes 取（Card、Button、TextField、Dialog…），
 * 所以只改这里，整站圆角风格就统一变了 —— 不需要每个组件单独传 shape。
 */
private fun expressiveShapes(scale: Float): Shapes {
    val s = scale.coerceIn(0.5f, 1.8f)
    return Shapes(
        extraSmall = RoundedCornerShape((4f * s).dp),
        small = RoundedCornerShape((8f * s).dp),
        medium = RoundedCornerShape((12f * s).dp),
        large = RoundedCornerShape((16f * s).dp),
        extraLarge = RoundedCornerShape((28f * s).dp),
    )
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
