package com.gcmodai.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MD3 Expressive 面板体系。
 *
 * 设计原则（不追求花哨，追求"高级"）：
 *  · 只用主题的 tonal surface（surfaceContainer / …High / …Highest），不手写颜色，
 *    这样任何主题（含用户 DIY 的种子色）都能自动协调；
 *  · 层次靠"色调差 + 细描边 + 极小阴影"，不靠强烈阴影或纯色块；
 *  · 圆角由 [LocalUiStyle] 统一控制，整站一致；
 *  · 只有一个 Surface + 一个 Column，没有 graphicsLayer/渲染效果，滚动不掉帧。
 */

/** 一级面板：页面主卡片、工具主视觉 */
@Composable
fun Md3Panel(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    /** 直接给圆角（不传则用 LocalUiStyle 的整站圆角） */
    radius: Dp? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    tonalElevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUiStyle.current
    val resolvedShape = shape ?: RoundedCornerShape(radius ?: ui.cardCorner)
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
    )
    val stroke = border ?: BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = resolvedShape,
            colors = colors,
            border = stroke,
            elevation = CardDefaults.cardElevation(defaultElevation = tonalElevation),
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = resolvedShape,
            colors = colors,
            border = stroke,
            elevation = CardDefaults.cardElevation(defaultElevation = tonalElevation),
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 二级卡片：组内的小块内容（日志、参数、提示） */
@Composable
fun Md3SubCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUiStyle.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ui.itemCorner),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 设置分组：标题 + 一张卡里若干行。
 *
 * 和 Material 3 列表规范一致：整组是一个容器，行之间不需要分割线
 * （靠间距和字重区分），最后一行也不再是"半圆角卡片"。
 */
@Composable
fun Md3Group(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ui = LocalUiStyle.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Row(
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ui.cardCorner),
            color = containerColor,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
            ),
        ) {
            Column(Modifier.padding(vertical = ui.sectionSpacing / 3), content = content)
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 分段标题（页面内的大分区） */
@Composable
fun Md3SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Column(modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
