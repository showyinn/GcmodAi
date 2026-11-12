package com.gcmodai.app.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import kotlin.math.cos
import kotlin.math.sin

/**
 * 液态玻璃宿主：整屏壁纸 + backdrop 注入。
 *
 * 为什么这么搭：
 *  · `rememberCanvasBackdrop { }` 让每块玻璃自己去「画一遍壁纸」再做模糊/折射，
 *    不需要抓取整棵视图树的图层（那种做法在 RecyclerView/长列表上非常耗内存）；
 *  · 壁纸只画一次（同一份画刷），玻璃面板复用同一份 backdrop，帧率稳定；
 *  · 主题换了只会重算画刷（remember 的 key 是颜色），玻璃参数继续沿用。
 *
 * 所有页面都应该被它包住一次，否则玻璃组件只能退化成分普通半透明。
 */
@Composable
fun GlassHost(
    spec: GlassSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 壁纸主色：直接从主题里长出来，所以 7 个内置主题 + 用户自定义主题都能自动匹配
    val colors = remember(
        scheme.primary, scheme.secondary, scheme.tertiary,
        scheme.surface, scheme.surfaceContainerLowest, isDark, spec.wallpaper,
    ) {
        wallpaperColors(scheme, isDark, spec.wallpaper)
    }

    val phase by if (spec.animatedWallpaper) {
        rememberInfiniteTransition(label = "glass-wallpaper").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 18_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "phase",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    val brush = remember(colors, phase) { wallpaperBrush(spec.wallpaper, colors, phase) }

    // backdrop 只按「颜色 + 风格」重建；phase 用 rememberUpdatedState 的方式喂进去，
    // 避免每帧都新建 backdrop 对象（那会疯狂重建运行时着色器）。
    val phaseState = remember { PhaseHolder() }
    phaseState.value = phase
    val backdropDraw: DrawScope.() -> Unit = remember(colors, spec.wallpaper, phaseState) {
        {
            drawWallpaper(spec.wallpaper, colors, phaseState.value)
        }
    }
    val backdrop = rememberCanvasBackdrop(backdropDraw)

    val visuals = remember(backdrop, brush, spec, scheme.surface, scheme.onSurface) {
        GlassVisuals(
            spec = spec,
            backdrop = backdrop,
            wallpaperBrush = brush,
            surfaceTint = if (isDark) {
                Color.White.copy(alpha = spec.tintAlpha * 0.7f)
            } else {
                Color.White.copy(alpha = spec.tintAlpha + 0.12f)
            },
            onGlass = scheme.onSurface,
        )
    }

    CompositionLocalProvider(LocalGlass provides visuals) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(brush)
                }
        ) {
            content()
        }
    }
}

/** 用一个稳定的可变持有者承接动画相位，保证 backdrop 的 lambda 身份不变 */
private class PhaseHolder {
    @Volatile
    var value: Float = 0f
}

// ---------------------------------------------------------------- 壁纸

private fun wallpaperColors(
    scheme: androidx.compose.material3.ColorScheme,
    isDark: Boolean,
    style: WallpaperStyle,
): List<Color> {
    val base = if (isDark) {
        listOf(
            scheme.surfaceContainerLowest,
            lerp(scheme.surface, scheme.primary, 0.20f),
            lerp(scheme.surface, scheme.tertiary, 0.16f),
        )
    } else {
        listOf(
            lerp(scheme.surface, scheme.primaryContainer, 0.55f),
            lerp(scheme.surface, scheme.tertiaryContainer, 0.45f),
            lerp(scheme.surface, scheme.secondaryContainer, 0.40f),
        )
    }
    return if (style == WallpaperStyle.SOLID) base else base + listOf(
        lerp(base[0], base[2], 0.5f),
    )
}

private fun wallpaperBrush(style: WallpaperStyle, colors: List<Color>, phase: Float): Brush {
    val drift = phase * 0.6f
    return when (style) {
        WallpaperStyle.AURORA -> Brush.linearGradient(
            colors = colors,
            start = Offset(0f, 0f),
            end = Offset(1200f * (0.6f + drift), 1600f),
        )

        WallpaperStyle.MESH -> Brush.radialGradient(
            colors = colors.take(3),
            center = Offset(240f + 420f * phase, 260f + 300f * phase),
            radius = 1400f,
        )

        WallpaperStyle.SILK -> Brush.linearGradient(
            colors = colors,
            start = Offset(0f, 900f * phase),
            end = Offset(1400f, 400f + 700f * drift),
        )

        WallpaperStyle.SOLID -> Brush.verticalGradient(colors.take(2))
    }
}

/** 真正的壁纸绘制：渐变底 + 几个大色团，玻璃折射后才有层次 */
private fun DrawScope.drawWallpaper(style: WallpaperStyle, colors: List<Color>, phase: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    drawRect(wallpaperBrush(style, colors, phase))

    if (style == WallpaperStyle.SOLID) return

    val c0 = colors[0]
    val c1 = colors[1]
    val c2 = colors[2]
    val drift = phase * 2f * Math.PI.toFloat()

    // 三个缓慢游走的大色团：AURORA/MESH 更明显，SILK 更淡
    val alphaScale = if (style == WallpaperStyle.SILK) 0.30f else 0.55f

    fun blob(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
    }

    blob(
        cx = w * (0.25f + 0.10f * sin(drift)),
        cy = h * (0.18f + 0.06f * cos(drift)),
        r = w * 0.75f,
        color = c1,
        alpha = 0.55f * alphaScale,
    )
    blob(
        cx = w * (0.80f + 0.08f * cos(drift * 0.8f)),
        cy = h * (0.55f + 0.08f * sin(drift * 0.7f)),
        r = w * 0.70f,
        color = c2,
        alpha = 0.50f * alphaScale,
    )
    blob(
        cx = w * (0.45f + 0.12f * sin(drift * 0.6f + 1.7f)),
        cy = h * (0.92f + 0.04f * cos(drift * 0.9f)),
        r = w * 0.85f,
        color = c0,
        alpha = 0.45f * alphaScale,
    )

    if (style == WallpaperStyle.SILK) {
        // 丝绸：几条斜向细带
        rotate(degrees = -18f + 6f * phase) {
            var x = -w * 0.3f
            var i = 0
            while (x < w * 1.4f) {
                val color = if (i % 2 == 0) c1 else c2
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, color.copy(alpha = 0.10f), Color.Transparent),
                        startX = x,
                        endX = x + w * 0.22f,
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(w * 0.22f, h * 1.6f),
                )
                x += w * 0.14f
                i++
            }
        }
    }
}
