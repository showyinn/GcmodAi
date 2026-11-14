package com.gcmodai.app.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ModelAbility
import com.gcmodai.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import com.gcmodai.app.R
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.ui.components.ai.ModelField
import com.gcmodai.app.ui.components.ai.ModelListSheet
import com.gcmodai.app.ui.components.ai.ModelListState
import com.gcmodai.app.ui.components.ai.ReasoningButton
import com.gcmodai.app.ui.components.ai.rememberModelListState
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupScope
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.components.ui.PagerTab
import com.gcmodai.app.ui.components.ui.PagerTabs
import com.gcmodai.app.ui.components.ui.switchItem
import com.gcmodai.app.ui.theme.CustomColors
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import org.koin.androidx.compose.koinViewModel

private enum class ModelSettingTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    MODEL(HugeIcons.AiBrain01, R.string.setting_model_page_tab_model),
    PROMPT(HugeIcons.AiEditing, R.string.setting_model_page_tab_prompt);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ModelSettingTab.entries.size }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = ModelSettingTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            // 导航条的位置留在列表的内边距里，内容可以滚到它下面
            val contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (ModelSettingTab.entries[page]) {
                    ModelSettingTab.MODEL -> ModelSettingsPage(
                        settings = settings,
                        vm = vm,
                        contentPadding = contentPadding,
                    )

                    ModelSettingTab.PROMPT -> PromptSettingsPage(
                        settings = settings,
                        vm = vm,
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    // CardGroup 里放不了弹窗，各项的选择状态先在这里建好，弹窗统一放在列表后面
    val chatModel = rememberModelListState(
        modelId = settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val fastModel = rememberModelListState(
        modelId = settings.fastModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val memoryModel = rememberModelListState(
        modelId = settings.memoryModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val translateModel = rememberModelListState(
        modelId = settings.translateModeId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val ocrModel = rememberModelListState(
        modelId = settings.ocrModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    // 能力编辑器里的「当前编辑对象」，只影响这一项，不参与任何一个默认模型的设置
    var abilityModelId by rememberSaveable { mutableStateOf<Uuid?>(null) }
    val abilityModel = rememberModelListState(
        modelId = abilityModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.BubbleChat,
                    title = R.string.setting_model_page_chat_model,
                    description = R.string.setting_model_page_chat_model_desc,
                    state = chatModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            // 标题和聊天建议都由快速模型生成，相关的选项跟它放在一组
            CardGroup {
                modelItem(
                    icon = HugeIcons.Zap,
                    title = R.string.setting_model_page_fast_model,
                    description = R.string.setting_model_page_fast_model_desc,
                    state = fastModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = settings.fastModelReasoningLevel,
                            onUpdateReasoningLevel = {
                                vm.updateSettings { latest -> latest.copy(fastModelReasoningLevel = it) }
                            },
                        )
                    },
                )
                switchItem(
                    checked = settings.enableAutoTitle,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableAutoTitle = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_auto_title)) },
                )
                switchItem(
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableSuggestion = it) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                )
            }
        }
        item {
            // 没设置时用快速模型，配色和它保持一致
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            CardGroup {
                // 不设置时跟着快速模型走，所以要能清掉
                modelItem(
                    icon = HugeIcons.Brain02,
                    title = R.string.setting_model_page_memory_model,
                    description = R.string.setting_model_page_memory_model_desc,
                    state = memoryModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    placeholder = R.string.setting_model_page_memory_model_follow_fast,
                    onClear = { vm.updateSettings { latest -> latest.copy(memoryModelId = null) } },
                )
                // 没设置时推理强度也跟着快速模型
                if (memoryModel.currentModel != null) {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                        trailingContent = {
                            ReasoningButton(
                                reasoningLevel = settings.memoryModelReasoningLevel,
                                onUpdateReasoningLevel = {
                                    vm.updateSettings { latest -> latest.copy(memoryModelReasoningLevel = it) }
                                },
                            )
                        },
                    )
                }
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup {
                modelItem(
                    icon = HugeIcons.Translate,
                    title = R.string.setting_model_page_translate_model,
                    description = R.string.setting_model_page_translate_model_desc,
                    state = translateModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
                modelItem(
                    icon = HugeIcons.ScanText,
                    title = R.string.setting_model_page_ocr_model,
                    description = R.string.setting_model_page_ocr_model_desc,
                    state = ocrModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup(title = { Text(stringResource(R.string.setting_model_page_task_mode_header)) }) {
                switchItem(
                    checked = settings.taskModeEnabled,
                    onCheckedChange = { vm.updateSettings { latest -> latest.copy(taskModeEnabled = it) } },
                    leadingContent = {
                        CardGroupIcon(
                            HugeIcons.AiBrain01,
                            containerColor = containerColor,
                            contentColor = contentColor,
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.setting_model_page_task_mode_desc)) },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_task_mode_enable)) },
                )
                // 次数和完成标记只在开启时才有意义
                if (settings.taskModeEnabled) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_model_page_task_mode_rounds)) },
                        supportingContent = { Text(stringResource(R.string.setting_model_page_task_mode_rounds_desc)) },
                        trailingContent = { CardGroupValue(settings.taskModeMaxContinuations.toString()) },
                    ) {
                        Slider(
                            value = settings.taskModeMaxContinuations.toFloat(),
                            onValueChange = { value ->
                                vm.updateSettings { latest ->
                                    latest.copy(taskModeMaxContinuations = value.roundToInt())
                                }
                            },
                            // 0 就是关掉续写，和上面的开关不是一回事，所以从 1 起
                            valueRange = 1f..20f,
                            steps = 18,
                        )
                    }
                }
            }
        }
        item {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            CardGroup(title = { Text("模型能力") }) {
                abilityItem(
                    state = abilityModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    onUpdateAbilities = { model, abilities ->
                        val providerId = settings.findProviderIdOfModel(model.id)
                            ?: return@abilityItem
                        vm.updateSettings { latest ->
                            latest.updateModelAbilities(
                                providerId = providerId,
                                modelId = model.id,
                                abilities = abilities,
                            )
                        }
                    },
                )
            }
        }
    }

    ModelListSheet(state = chatModel) {
        vm.updateSettings { latest -> latest.copy(chatModelId = it.id) }
    }
    ModelListSheet(state = fastModel) {
        vm.updateSettings { latest -> latest.copy(fastModelId = it.id) }
    }
    ModelListSheet(state = memoryModel) {
        vm.updateSettings { latest -> latest.copy(memoryModelId = it.id) }
    }
    ModelListSheet(state = translateModel) {
        vm.updateSettings { latest -> latest.copy(translateModeId = it.id) }
    }
    ModelListSheet(state = ocrModel) {
        vm.updateSettings { latest -> latest.copy(ocrModelId = it.id) }
    }
    ModelListSheet(state = abilityModel) { model ->
        // 选中模型只决定下面勾选的是谁，不写进聊天模型设置
        abilityModelId = model.id
    }
}

// 一项默认模型：图标、用途说明，下面是显示当前模型的选择按钮
private fun CardGroupScope.modelItem(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
    @StringRes placeholder: Int = R.string.model_list_select_model,
    onClear: (() -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = { Text(stringResource(description)) },
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
        placeholder = stringResource(placeholder),
        onClear = onClear,
    )
}

// 模型能力编辑器：先选一个模型，再直接勾它的工具调用 / 推理能力。
// 勾选立刻写回设置，列表项上的能力标签随之更新。
private fun CardGroupScope.abilityItem(
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
    onUpdateAbilities: (Model, List<ModelAbility>) -> Unit,
) = formItem(
    headlineContent = { Text("工具调用与推理") },
    supportingContent = { Text("给选中的模型打开工具调用或推理能力，改完立即生效") },
    leadingContent = {
        CardGroupIcon(HugeIcons.Tools, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
        placeholder = stringResource(R.string.model_list_select_model),
    )
    state.currentModel?.let { model ->
        ModalAbilitySelector(
            abilities = model.abilities,
            onUpdateAbilities = { onUpdateAbilities(model, it) },
        )
    }
}

/**
 * 只替换指定提供商里的那个模型，其余提供商和它们的模型原样保留。
 *
 * 官方 provider 的模型列表会由后台同步流程整段重建，所以这里按 provider id 精确替换，
 * 不会连带改到别的提供商。
 */
private fun Settings.updateModelAbilities(
    providerId: Uuid,
    modelId: Uuid,
    abilities: List<ModelAbility>,
): Settings = copy(
    providers = providers.map { provider ->
        if (provider.id != providerId) {
            provider
        } else {
            provider.copyProvider(
                models = provider.models.map { model ->
                    if (model.id == modelId) model.copy(abilities = abilities) else model
                }
            )
        }
    }
)

/** 找到模型所属的提供商 id，找不到时返回 null。 */
private fun Settings.findProviderIdOfModel(modelId: Uuid): Uuid? =
    providers.firstOrNull { provider -> provider.models.any { it.id == modelId } }?.id
