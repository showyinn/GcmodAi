package com.gcmodai.app.ui.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃组件集。
 *
 * 统一走 `Modifier.drawBackdrop`：
 *   1. vibrancy   —— 提高透过玻璃的颜色的饱和度（iOS 玻璃的"鲜活"感就来自这一步）
 *   2. blur       —— 毛玻璃模糊
 *   3. lens       —— 边缘折射，模拟玻璃厚度（带色散，边缘会有极细的彩虹）
 *   4. highlight  —— 顶部高光描边
 *   5. shadow / innerShadow —— 外部落影 + 内部厚度阴影
 *   6. onDrawSurface —— 表面染色，保证文字对比度
 *
 * 所有参数来自 [LocalGlass]，改主题只改 spec，不动组件。
 */

private fun glassShape(radius: Dp = 26.dp): Shape = RoundedCornerShape(radius)

/** 一块最基础的玻璃板，内容自己排版 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    radius: Dp = glassSpec.cornerRadiusDp.dp,
    tint: Color = Color.Unspecified,
    surfaceAlphaScale: Float = 1f,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val visuals = LocalGlass.current
    val spec = visuals.spec
    val resolvedShape = shape ?: glassShape(radius)
    // Android 8~11 不支持 RenderEffect：模糊/折射会被库静默跳过，
    // 如果还按低透明度染色，字就糊在花壁纸上了。这里把染色加深兜底，保证可读。
    val fallbackBoost = if (isRenderEffectSupported()) 1f else 3.4f
    val surface = if (tint.isSpecified) {
        tint
    } else {
        visuals.surfaceTint.copy(
            alpha = (visuals.surfaceTint.alpha * surfaceAlphaScale * fallbackBoost)
                .coerceIn(0f, 0.94f)
        )
    }

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = visuals.backdrop,
                shape = { resolvedShape },
                effects = {
                    vibrancy()
                    blur(spec.blurDp.dp.toPx())
                    lens(
                        refractionHeight = spec.refractionHeightDp.dp.toPx(),
                        refractionAmount = spec.refractionAmountDp.dp.toPx(),
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Ambient.copy(alpha = spec.highlightAlpha) },
                shadow = { Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.13f)) },
                innerShadow = { InnerShadow(radius = 10.dp, alpha = spec.innerShadowAlpha) },
                onDrawSurface = { drawRect(surface) },
            )
            .clip(resolvedShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        content = content,
    )
}

/** 带纵向排版的玻璃卡片 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = glassSpec.cornerRadiusDp.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    tint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier,
        radius = radius,
        tint = tint,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** 玻璃按钮（胶囊形），主操作 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val visuals = LocalGlass.current
    val shape = RoundedCornerShape(percent = 50)
    val surface = if (tint.isSpecified) {
        tint
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    }
    Row(
        modifier = modifier
            .drawBackdrop(
                backdrop = visuals.backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(visuals.spec.blurDp.dp.toPx() * 0.8f)
                    lens(
                        refractionHeight = visuals.spec.refractionHeightDp.dp.toPx() * 0.8f,
                        refractionAmount = visuals.spec.refractionAmountDp.dp.toPx() * 0.8f,
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Ambient.copy(alpha = visuals.spec.highlightAlpha) },
                shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.16f)) },
                onDrawSurface = { drawRect(if (enabled) surface else surface.copy(alpha = 0.08f)) },
            )
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 44.dp)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 玻璃图标按钮（圆形） */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = LocalContentColor.current,
) {
    GlassPanel(
        modifier = modifier.size(44.dp),
        shape = RoundedCornerShape(percent = 50),
        onClick = if (enabled) onClick else null,
        surfaceAlphaScale = 0.8f,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 玻璃标签/胶囊（计数、状态、模型能力角标都用它） */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        tint = tint,
        surfaceAlphaScale = 0.7f,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 26.dp)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 玻璃列表项：设置页每一行都是它。
 *
 * 用整行点击（不只是图标），和 AGENTS.md 里「whole-row click targets」对齐。
 */
@Composable
fun GlassListItem(
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = onClick != null,
                interactionSource = null,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { onClick?.invoke() },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            headline()
            supporting?.invoke()
        }
        trailing?.invoke()
    }
}

/** 玻璃标题 + 内容分组（替代原来的 CardGroup，保留同样的调用形式） */
@Composable
fun GlassGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                Box(Modifier.padding(start = 8.dp, bottom = 2.dp)) { title() }
            }
        }
        GlassCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            content()
        }
        if (subtitle != null) {
            Box(Modifier.padding(start = 8.dp)) { subtitle() }
        }
    }
}

/** 玻璃对话框卡片：替代 AlertDialog 的白底，交给调用方自己画内容 */
@Composable
fun GlassDialogCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        radius = 30.dp,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 玻璃分组标题小字 */
@Composable
fun GlassSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 玻璃上的标题文字（保证对比度） */
@Composable
fun glassTextStyle(base: TextStyle = MaterialTheme.typography.bodyMedium): TextStyle =
    base.copy(color = LocalGlass.current.onGlass)
