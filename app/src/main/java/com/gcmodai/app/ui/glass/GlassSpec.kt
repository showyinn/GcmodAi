package com.gcmodai.app.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * 液态玻璃（Liquid Glass）视觉参数。
 *
 * 灵感来自 iOS 26 的液态玻璃和 Kyant0/AndroidLiquidGlass（io.github.kyant0:backdrop）：
 * 一块玻璃 = 背景模糊 + 边缘折射 + 高光描边 + 内阴影 + 轻微染色。
 *
 * 这里把「强度」集中成几个可调字段，主题切换时不需要改任何组件代码，
 * 只换 spec 就能从「浓稠果冻」调到「清透薄片」。
 */
@Immutable
data class GlassSpec(
    /** 背景模糊半径（dp）。越大越雾，超过 30 就有点糊成一片了 */
    val blurDp: Float = 16f,
    /** 边缘折射高度（dp）：玻璃厚度感，用 0 就是普通毛玻璃 */
    val refractionHeightDp: Float = 12f,
    /** 折射强度（dp）：越大边缘扭曲越明显 */
    val refractionAmountDp: Float = 26f,
    /** 玻璃表面染色透明度（0~1）。字要清楚，一般 0.06~0.16 */
    val tintAlpha: Float = 0.10f,
    /** 高光描边透明度 */
    val highlightAlpha: Float = 0.55f,
    /** 内阴影透明度：让玻璃有厚度 */
    val innerShadowAlpha: Float = 0.10f,
    /** 圆角（dp） */
    val cornerRadiusDp: Float = 26f,
    /** 背景壁纸风格 */
    val wallpaper: WallpaperStyle = WallpaperStyle.AURORA,
    /** 壁纸是否缓慢流动 */
    val animatedWallpaper: Boolean = true,
) {
    /** 强度档位：轻 / 中 / 重，供设置页一键切换 */
    companion object {
        val Light = GlassSpec(
            blurDp = 8f, refractionHeightDp = 6f, refractionAmountDp = 12f,
            tintAlpha = 0.06f, highlightAlpha = 0.35f, innerShadowAlpha = 0.06f,
            cornerRadiusDp = 22f,
        )
        val Medium = GlassSpec()
        val Heavy = GlassSpec(
            blurDp = 26f, refractionHeightDp = 20f, refractionAmountDp = 44f,
            tintAlpha = 0.16f, highlightAlpha = 0.75f, innerShadowAlpha = 0.18f,
            cornerRadiusDp = 30f,
        )

        val Levels = listOf(Light, Medium, Heavy)
    }
}

/** 壁纸风格：不同主题下换一种背景，玻璃折射出来的味道完全不同 */
enum class WallpaperStyle {
    /** 极光：大色块斜向流动，最接近 iOS 26 */
    AURORA,

    /** 网格：多色小球叠在深底上，颗粒感强 */
    MESH,

    /** 丝绸：细长条带，像布料褶皱 */
    SILK,

    /** 纯色：只用主题色做柔和渐变，最省电 */
    SOLID,
}

/** 玻璃视觉上下文：组件从这里拿 backdrop 与 spec */
@Immutable
data class GlassVisuals(
    val spec: GlassSpec,
    val backdrop: Backdrop,
    /** 壁纸画刷，页面根节点用它铺底 */
    val wallpaperBrush: Brush,
    /** 玻璃表面染色（跟随主题 surface） */
    val surfaceTint: Color,
    /** 玻璃上的文字/图标颜色 */
    val onGlass: Color,
)

val LocalGlass = compositionLocalOf {
    GlassVisuals(
        spec = GlassSpec(),
        backdrop = EmptyBackdrop,
        wallpaperBrush = Brush.linearGradient(listOf(Color(0xFF1B1B2F), Color(0xFF2A1B3D))),
        surfaceTint = Color.White.copy(alpha = 0.10f),
        onGlass = Color.White,
    )
}

/** 兜底 backdrop：没有 GlassHost 时玻璃退化成普通半透明，不会崩 */
private object EmptyBackdrop : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) = Unit
}

/** 便捷：当前玻璃参数 */
val glassSpec: GlassSpec
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalGlass.current.spec
