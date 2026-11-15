package com.gcmodai.app.ui.pages.harden

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import com.gcmodai.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 加固页的视觉层。
 *
 * 设计取向：这是给「做逆向/打包」的人用的工具页，不是普通设置页。
 * 所以走**工具仪表盘**的语言——等宽字体、分阶段时间轴、状态灯、
 * 细边框玻璃卡、发光进度。深浅两套都做，用 MaterialTheme.colorScheme
 * 取色，跟随系统自动切换，不做写死的深色。
 *
 * 关键取舍：
 * - 不用大面积纯色块，用 surface + 1dp 描边 + 极淡渐变，避免廉价感；
 * - 状态用「灯 + 等宽文字」而不是彩色大标签，一眼能扫；
 * - 进度用发光条 + 阶段刻度，不要一个孤零零的圆圈转；
 * - 全部数字用 tabular-nums，跳动时不抖。
 */
internal object HardenTheme {

    /** 等宽字体：日志、哈希、体积、耗时都用它 */
    val mono = FontFamily.Monospace

    // ---- 语义色：从主题派生，深浅两套自动适配 ----

    /** 成功 / 已通过 */
    @Composable
    fun ok(): Color = MaterialTheme.colorScheme.primary

    /** 进行中 */
    @Composable
    fun running(): Color = MaterialTheme.colorScheme.tertiary

    /** 警告：能做但有代价 */
    @Composable
    fun warn(): Color = MaterialTheme.colorScheme.secondary

    /** 失败 / 危险 */
    @Composable
    fun danger(): Color = MaterialTheme.colorScheme.error

    /** 玻璃卡底色：比背景略亮一点，带一点主题色倾向 */
    @Composable
    fun glass(): Color = MaterialTheme.colorScheme.surfaceContainerLow
}

/**
 * 玻璃卡：细描边 + 极淡斜向渐变，深浅主题都成立。
 *
 * 为什么不用纯 surface：纯色在大片深色里会显得「平」，
 * 加一层从主题色 6% 到透明的斜向渐变，会有一点点"玻璃下面有光"的层次，
 * 又不会像真玻璃那样在浅色下糊成一片。
 */
@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScopeCompat.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = accent ?: scheme.primary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = HardenTheme.glass(),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 0.dp,
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        tint.copy(alpha = 0.06f),
                        Color.Transparent,
                        tint.copy(alpha = 0.02f),
                    ),
                    start = Offset.Zero,
                    end = Offset(900f, 900f),
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ColumnScopeCompat.content()
            }
        }
    }
}

/** 让 GlassCard 的 content 里能直接用 Column 的作用域函数 */
internal object ColumnScopeCompat

/** 小节标题：左侧一根 3dp 竖条 + 标题 + 右侧可选状态 */
@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.primary)
        )
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = scheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 状态灯：一个小圆点 + 等宽状态文字。
 *
 * pulsing=true 时外圈呼吸，用来表示「正在跑」。
 * 呼吸用 alpha 动画而不是缩放：缩放在小尺寸上会显得抖。
 */
@Composable
internal fun StatusLamp(
    text: String,
    color: Color,
    pulsing: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "lamp")
    val halo by transition.animateFloat(
        initialValue = if (pulsing) 0.18f else 0f,
        targetValue = if (pulsing) 0.55f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (pulsing) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = halo))
                )
            }
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = HardenTheme.mono,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 阶段时间轴的一格。
 *
 * 为什么用时间轴而不是进度条：加固是「解包 → 混淆 → 加密 → 重打包 → 签名 → 校验」
 * 六个阶段，用户想知道卡在哪一步。一个百分比说不出这件事。
 */
@Composable
internal fun StageRow(
    index: Int,
    title: String,
    detail: String,
    state: StageState,
    isLast: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when (state) {
        StageState.Done -> HardenTheme.ok()
        StageState.Active -> HardenTheme.running()
        StageState.Failed -> HardenTheme.danger()
        StageState.Pending -> scheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    Row(Modifier.fillMaxWidth()) {
        // 左侧：圆点 + 竖线
        Column(
            modifier = Modifier.width(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (state == StageState.Pending) Color.Transparent else color.copy(alpha = 0.14f))
                    .border(1.5.dp, color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    StageState.Done -> Text("✓", fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
                    StageState.Active -> Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    StageState.Failed -> Text("×", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                    StageState.Pending -> Text(
                        (index + 1).toString(),
                        fontSize = 10.sp,
                        color = color,
                        fontFamily = HardenTheme.mono,
                    )
                }
            }
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.5.dp)
                        .height(26.dp)
                        .background(color.copy(alpha = if (state == StageState.Done) 0.5f else 0.18f))
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (state == StageState.Active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == StageState.Pending) scheme.onSurfaceVariant else scheme.onSurface,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = HardenTheme.mono,
                    color = if (state == StageState.Pending) {
                        scheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        scheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

internal enum class StageState { Done, Active, Failed, Pending }

/**
 * 发光进度条。
 *
 * 段数对应阶段数，走过的段实心、当前段呼吸、未到的段只有描边。
 * 比一个连续条更能表达「现在在第几阶段」。
 */
@Composable
internal fun StageProgress(
    total: Int,
    done: Int,
    active: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "bar")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "activeAlpha",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(total) { i ->
            val filled = i < done
            val isActive = i == active
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            filled -> HardenTheme.ok()
                            isActive -> HardenTheme.running().copy(alpha = alpha)
                            else -> scheme.surfaceContainerHighest
                        }
                    )
            )
        }
    }
}

/**
 * 指标格：一个小方块显示「标签 + 等宽数值」。
 * 用于体积、耗时、类数、加固档位这类一眼要看到的数。
 */
@Composable
internal fun MetricTile(
    label: String,
    value: String,
    unit: String? = null,
    accent: Color? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = accent ?: scheme.primary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = HardenTheme.mono,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            if (unit != null) {
                Text(
                    text = " " + unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 等级选择：一排可点的力度格。
 *
 * 加固力度是「兼容性 ↔ 防护强度」的连续权衡，
 * 用一排格子 + 当前档位说明，用户能立刻理解代价。
 *
 * 注意：整块必须挂 clickable。之前只有背景和边框，没有点击响应——
 * 表现就是「点了没反应」，看起来像按钮其实是死的。
 */
@Composable
internal fun LevelSelector(
    levels: List<Level>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            levels.forEachIndexed { i, level ->
                val active = i == selected
                val container by animateColorAsState(
                    targetValue = if (active) {
                        scheme.primary.copy(alpha = 0.13f)
                    } else {
                        scheme.surfaceContainerHigh.copy(alpha = 0.55f)
                    },
                    label = "levelBg",
                )
                val stroke by animateColorAsState(
                    targetValue = if (active) scheme.primary else scheme.outlineVariant.copy(alpha = 0.65f),
                    label = "levelBorder",
                )
                val contentColor = if (active) scheme.primary else scheme.onSurfaceVariant

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(container)
                        .border(if (active) 2.dp else 1.dp, stroke, RoundedCornerShape(16.dp))
                        // 整块可点，且带水波纹：没有这一行，格子就是死的
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                        ) { onSelect(i) }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 力度指示：几条竖杠，越多越强（与截图里的 ｜ ｜｜ ｜｜｜ 一致）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(i + 1) {
                            Box(
                                Modifier
                                    .size(width = 3.5.dp, height = 14.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(contentColor)
                            )
                        }
                    }
                    Text(
                        text = level.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        levels.getOrNull(selected)?.let { level ->
            Text(
                text = level.hint,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

internal data class Level(val label: String, val hint: String)

/**
 * 字符串加密规则选择器：一排预设 + 可在「自定义」下直接编辑。
 *
 * 交互约定：
 * - 点预设 → 立刻把对应规则写进 stringFogRules，选中态高亮
 * - 规则被手改后不再匹配任何预设 → 自动落到「自定义」并展开输入框，
 *   避免「我改了规则但显示还是某个预设」的错位
 */
@Composable
internal fun StringFogSelector(
    presets: List<StringFogPreset>,
    currentRules: List<String>,
    onPickPreset: (StringFogPreset) -> Unit,
    onEditCustom: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val active = StringFogPresets.match(currentRules) ?: StringFogPresets.byKey("custom")
    val isCustom = active.key == "custom"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 五档在窄屏放不下一行，用可换行的流式布局
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                val selected = preset.key == active.key
                val container by animateColorAsState(
                    if (selected) {
                        scheme.primary.copy(alpha = 0.13f)
                    } else {
                        scheme.surfaceContainerHigh.copy(alpha = 0.55f)
                    },
                    label = "fogBg",
                )
                val stroke by animateColorAsState(
                    if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.65f),
                    label = "fogBorder",
                )
                Surface(
                    onClick = { onPickPreset(preset) },
                    shape = RoundedCornerShape(12.dp),
                    color = container,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, stroke),
                ) {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Text(
            text = active.hint,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )

        if (isCustom) {
            OutlinedTextField(
                value = currentRules.joinToString("\n"),
                onValueChange = onEditCustom,
                label = { Text("加密规则（每行一条）") },
                supportingText = { Text("+com.foo.* 包含；-com.foo.Skip 排除；留空=不加密") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HardenTheme.mono),
            )
        } else {
            // 非自定义时把实际生效的规则摊出来，避免「选了但不知道到底加密了啥」
            Text(
                text = currentRules.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = HardenTheme.mono,
                color = scheme.primary,
            )
        }
    }
}


/** 日志行：等宽，带时间戳位置，长行自动换 */
@Composable
internal fun LogLine(text: String, highlight: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = HardenTheme.mono,
        color = if (highlight) HardenTheme.ok() else scheme.onSurfaceVariant,
        lineHeight = 16.sp,
    )
}

/** 虚线分隔，用于「高级选项」这类可折叠区域 */
@Composable
internal fun DashedDivider() {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = scheme.outlineVariant,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                )
            }
    )
}

/** 环形刻度：加固完成度视觉，中心显示百分比或数字 */
@Composable
internal fun RingGauge(
    progress: Float,
    center: String,
    sub: String,
    size: Int = 92,
) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceContainerHighest
    val accent = HardenTheme.ok()
    Box(
        modifier = Modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(size.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = track,
                startAngle = -220f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = accent,
                startAngle = -220f,
                sweepAngle = 260f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = center,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = HardenTheme.mono,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
