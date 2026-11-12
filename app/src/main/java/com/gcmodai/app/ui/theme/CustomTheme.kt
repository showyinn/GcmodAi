package com.gcmodai.app.ui.theme

import androidx.compose.material3.ColorScheme
import dynamiccolor.ColorSpec
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import kotlinx.serialization.Serializable
import com.gcmodai.material3.toColorScheme
import palettes.TonalPalette
import kotlin.uuid.Uuid

@Serializable
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = 0xFF6750A4,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
    /**
     * 配色风格（Material color spec 的 Variant）。
     *
     * 同一颗种子色，换一个 Variant 出来的整套配色完全不同 —— 这是"DIY 风格"最省事也最专业的做法：
     *  · TONAL_SPOT  柔和、通用（Material 默认）
     *  · VIBRANT     高饱和、鲜艳
     *  · EXPRESSIVE  更跳的多色相
     *  · CONTENT     贴近种子色本身
     *  · NEUTRAL     近乎灰阶，极简
     *  · MONOCHROME  单色
     *  · FIDELITY    严格还原种子色
     *  · RAINBOW     彩虹感
     */
    val variant: String = "TONAL_SPOT",
) {
    /** 把字符串映射到 Variant，未知值回落默认 */
    val materialVariant: Variant
        get() = when (variant) {
            "VIBRANT" -> Variant.VIBRANT
            "EXPRESSIVE" -> Variant.EXPRESSIVE
            "CONTENT" -> Variant.CONTENT
            "NEUTRAL" -> Variant.NEUTRAL
            "MONOCHROME" -> Variant.MONOCHROME
            "FIDELITY" -> Variant.FIDELITY
            "RAINBOW" -> Variant.RAINBOW
            else -> Variant.TONAL_SPOT
        }

    fun generateColorScheme(dark: Boolean): ColorScheme {
        val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
        val specVersion = ColorSpec.SpecVersion.SPEC_2025
        val platform = DynamicScheme.DEFAULT_PLATFORM
        val contrastLevel = 0.0
        val colorSpec = ColorSpecs.get(specVersion)
        val v = materialVariant

        val primaryPalette = colorSpec.getPrimaryPalette(
            v, sourceHct, dark, platform, contrastLevel,
        )
        val secondaryPalette = if (secondaryColorArgb != null) {
            TonalPalette.fromInt(secondaryColorArgb.toInt())
        } else {
            colorSpec.getSecondaryPalette(v, sourceHct, dark, platform, contrastLevel)
        }
        val tertiaryPalette = if (tertiaryColorArgb != null) {
            TonalPalette.fromInt(tertiaryColorArgb.toInt())
        } else {
            colorSpec.getTertiaryPalette(v, sourceHct, dark, platform, contrastLevel)
        }

        val scheme = DynamicScheme(
            sourceHct,
            v,
            dark,
            contrastLevel,
            platform,
            specVersion,
            primaryPalette,
            secondaryPalette,
            tertiaryPalette,
            colorSpec.getNeutralPalette(v, sourceHct, dark, platform, contrastLevel),
            colorSpec.getNeutralVariantPalette(v, sourceHct, dark, platform, contrastLevel),
            colorSpec.getErrorPalette(v, sourceHct, dark, platform, contrastLevel),
        )
        return scheme.toColorScheme()
    }
}

/** DIY 可选的配色风格（顺序即 UI 展示顺序） */
val ThemeVariants: List<Pair<String, String>> = listOf(
    "TONAL_SPOT" to "柔和",
    "VIBRANT" to "鲜艳",
    "EXPRESSIVE" to "跳脱",
    "CONTENT" to "本色",
    "FIDELITY" to "还原",
    "RAINBOW" to "彩虹",
    "NEUTRAL" to "极简灰",
    "MONOCHROME" to "单色",
)
