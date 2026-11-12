package com.gcmodai.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局 UI 风格（MD3 Expressive）。
 *
 * 用户能 DIY 的三件事在这里集中定义，组件只读不写：
 *  · 圆角倍率：0.5x（利落）~ 1.8x（软糯），所有卡片/弹窗/输入框一起缩放
 *  · 按钮尺寸：紧凑 / 标准 / 宽松，影响按钮高度、图标按钮大小、行内边距
 *  · 列表密度：紧凑 / 标准 / 舒适，影响设置项与列表项的纵向间距
 *
 * 为什么不用改每个组件的参数：组件从 [LocalUiStyle] 读，切风格只换一个值，
 * 整站风格立即统一，也不会出现"设置页改了但聊天页没改"的割裂。
 */
@Immutable
data class UiStyle(
    /** 圆角倍率，0.5 ~ 1.8 */
    val cornerScale: Float = 1f,
    /** 按钮尺寸 */
    val buttonSize: ButtonSize = ButtonSize.MEDIUM,
    /** 列表密度 */
    val density: ListDensity = ListDensity.NORMAL,
) {

    /** 卡片/面板圆角：基准 24dp */
    val cardCorner: Dp get() = (24f * cornerScale).dp

    /** 内嵌项圆角：基准 16dp */
    val itemCorner: Dp get() = (16f * cornerScale).dp

    /** 弹窗圆角：基准 28dp */
    val dialogCorner: Dp get() = (28f * cornerScale).dp

    /** 输入框/胶囊圆角：基准 28dp */
    val pillCorner: Dp get() = (28f * cornerScale).dp

    /** 主按钮高度 */
    val buttonHeight: Dp get() = when (buttonSize) {
        ButtonSize.COMPACT -> 36.dp
        ButtonSize.MEDIUM -> 44.dp
        ButtonSize.COMFORTABLE -> 52.dp
    }

    /** 图标按钮边长 */
    val iconButtonSize: Dp get() = when (buttonSize) {
        ButtonSize.COMPACT -> 36.dp
        ButtonSize.MEDIUM -> 42.dp
        ButtonSize.COMFORTABLE -> 48.dp
    }

    /** 按钮水平内边距 */
    val buttonHorizontalPadding: Dp get() = when (buttonSize) {
        ButtonSize.COMPACT -> 14.dp
        ButtonSize.MEDIUM -> 18.dp
        ButtonSize.COMFORTABLE -> 24.dp
    }

    /** 列表项纵向内边距 */
    val rowVerticalPadding: Dp get() = when (density) {
        ListDensity.COMPACT -> 8.dp
        ListDensity.NORMAL -> 12.dp
        ListDensity.COMFORTABLE -> 16.dp
    }

    /** 分组之间 / 分组内的节奏间距 */
    val sectionSpacing: Dp get() = when (density) {
        ListDensity.COMPACT -> 10.dp
        ListDensity.NORMAL -> 14.dp
        ListDensity.COMFORTABLE -> 18.dp
    }

    /** 页面左右边距 */
    val pagePadding: Dp get() = when (density) {
        ListDensity.COMPACT -> 12.dp
        ListDensity.NORMAL -> 16.dp
        ListDensity.COMFORTABLE -> 20.dp
    }
}

enum class ButtonSize(val label: String) {
    COMPACT("紧凑"),
    MEDIUM("标准"),
    COMFORTABLE("宽松"),
}

enum class ListDensity(val label: String) {
    COMPACT("紧凑"),
    NORMAL("标准"),
    COMFORTABLE("舒适"),
}

val LocalUiStyle = compositionLocalOf { UiStyle() }
