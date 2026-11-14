package com.gcmodai.app.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import com.gcmodai.app.R
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.components.ui.switchItem
import kotlin.math.roundToInt

/**
 * 聊天页 + 号面板顶部的任务模式快捷条。
 *
 * 为什么放在这里而不是设置页：这个开关的使用时机是「发出去之后发现模型停了一半」，
 * 用户要的是当场就能改，而不是退回设置页翻三层菜单。所以主入口在这里，
 * 细节（轮数 / 完成标记 / 严格模式）才放到子页面。
 */
@Composable
internal fun TaskModeQuickBar(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val enabled = settings.taskModeEnabled
    SegmentedTopBar(
        leading = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(start = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                CardGroupIcon(
                    icon = HugeIcons.AiBrain01,
                    containerColor = if (enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        title = stringResource(R.string.setting_model_page_task_mode),
        supporting = when {
            !enabled -> stringResource(R.string.task_mode_status_off)
            settings.taskModeStrict -> stringResource(
                R.string.task_mode_status_strict,
                settings.taskModeMaxContinuations
            )

            else -> stringResource(R.string.task_mode_status_on, settings.taskModeMaxContinuations)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        onUpdateSettings { it.copy(taskModeEnabled = value) }
                    },
                )
                IconButton(onClick = onOpenDetail) {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = stringResource(R.string.task_mode_settings),
                    )
                }
            }
        },
    )
}

/** 任务模式详情页：开关、严格模式、轮数、完成标记、续写提示。 */
@Composable
internal fun TaskModePickerPage(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            title = stringResource(R.string.setting_model_page_task_mode_header),
            onBack = onBack,
        )

        CardGroup {
            switchItem(
                checked = settings.taskModeEnabled,
                onCheckedChange = { value -> onUpdateSettings { it.copy(taskModeEnabled = value) } },
                supportingContent = { Text(stringResource(R.string.setting_model_page_task_mode_desc)) },
                headlineContent = { Text(stringResource(R.string.setting_model_page_task_mode_enable)) },
            )
            if (settings.taskModeEnabled) {
                switchItem(
                    checked = settings.taskModeStrict,
                    onCheckedChange = { value -> onUpdateSettings { it.copy(taskModeStrict = value) } },
                    supportingContent = {
                        Text(stringResource(R.string.setting_model_page_task_mode_strict_desc))
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_task_mode_strict)) },
                )
                formItem(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_task_mode_rounds)) },
                    supportingContent = { Text(stringResource(R.string.setting_model_page_task_mode_rounds_desc)) },
                    trailingContent = { CardGroupValue(settings.taskModeMaxContinuations.toString()) },
                ) {
                    Slider(
                        value = settings.taskModeMaxContinuations.toFloat(),
                        onValueChange = { value ->
                            onUpdateSettings { it.copy(taskModeMaxContinuations = value.roundToInt()) }
                        },
                        // 0 等于关闭续写，和上面的开关语义重复，所以从 1 起
                        valueRange = 1f..20f,
                        steps = 18,
                    )
                }
            }
        }

        // 完成标记与续写提示是「调参」级别的东西，放在一组里、各自一行，点开才能改，
        // 免得把主界面撑满输入框
        if (settings.taskModeEnabled && settings.taskModeStrict) {
            CardGroup(title = { Text(stringResource(R.string.task_mode_advanced)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_task_mode_marker)) },
                    supportingContent = {
                        Text(
                            settings.taskModeDoneMarker.ifBlank {
                                stringResource(R.string.task_mode_marker_builtin)
                            }
                        )
                    },
                    trailingContent = { CardGroupValue(settings.taskModeDoneMarker.ifBlank { "内置" }) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_prompt_task_nudge)) },
                    supportingContent = {
                        val nudge = settings.taskModeNudge.ifBlank {
                            stringResource(R.string.task_mode_nudge_builtin)
                        }
                        Text(text = nudge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }

        Text(
            text = stringResource(R.string.task_mode_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 一行式标题条：左侧图标、标题、状态说明、右侧操作。 */
@Composable
private fun SegmentedTopBar(
    leading: @Composable () -> Unit,
    title: String,
    supporting: String,
    trailing: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

/** 子页面顶部：返回 + 标题。 */
@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = HugeIcons.ArrowLeft01, contentDescription = null)
        }
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

/** 详情页里「恢复默认」用的小按钮，留给后续扩展。 */
@Composable
internal fun ResetDefaultsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.setting_model_page_reset_to_default))
    }
}
