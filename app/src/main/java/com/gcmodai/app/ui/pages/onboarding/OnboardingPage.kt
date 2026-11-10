package com.gcmodai.app.ui.pages.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.UserCircle
import com.gcmodai.ai.provider.Model
import com.gcmodai.app.Screen
import com.gcmodai.app.data.user.GroupInfo
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.components.ui.ConnectedChoiceButtons
import com.gcmodai.app.ui.components.ui.ConnectedChoiceLabel
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.plus
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

/**
 * 新手引导页。
 *
 * 纯展示：状态从 [OnboardingVM] 取，用户操作通过 [OnboardingAction] 发回。
 * 导航不走 Action —— LocalNavController 是 compositionLocal，ViewModel 拿不到，
 * 所以跳页在这里直接做（和 AccountPage 的写法一致）。
 */
@Composable
fun OnboardingPage() {
    val vm: OnboardingVM = koinInject()
    val uiState by vm.state.collectAsStateWithLifecycle()
    val currentIp by vm.currentIp.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 进入引导页即把当前出口 IP 记为「已触发过」。
    // 这样同一 IP 下次启动不再重复弹；换 IP 后 OnboardingGate 才会再次放行。
    LaunchedEffect(Unit) {
        OnboardingIpGate.markTriggered(context, currentIp)
    }

    OnboardingPage(
        uiState = uiState,
        onAction = vm::onAction,
    )
}

/** 纯展示重载：便于 Preview，也方便接线方换数据源 */
@Composable
fun OnboardingPage(
    uiState: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("新手引导 · ${uiState.stepIndex}/${uiState.totalSteps}") },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OnboardingIntroCard(
                    uiState = uiState,
                )
            }

            when (uiState.step) {
                OnboardingStep.Login -> item {
                    LoginStep(
                        onLogin = { navController.navigate(Screen.Login) },
                        onRegister = { navController.navigate(Screen.Register) },
                    )
                }

                OnboardingStep.Token -> item {
                    TokenStep(
                        uiState = uiState,
                        onOpenTokens = { navController.navigate(Screen.Tokens) },
                    )
                }

                OnboardingStep.Group -> item {
                    GroupStep(
                        uiState = uiState,
                        onAction = onAction,
                    )
                }

                OnboardingStep.Model -> item {
                    ModelStep(
                        uiState = uiState,
                        onAction = onAction,
                    )
                }

                OnboardingStep.Done -> item {
                    DoneStep(
                        uiState = uiState,
                        onStartChat = {
                            onAction(OnboardingAction.Complete)
                            navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
                        },
                    )
                }
            }

            uiState.error?.let { message ->
                item {
                    OnboardingNotice(text = message, error = true)
                }
            }

            // 出口：本引导服务的是「走官方网关」这条主路径。
            // 想用自己的渠道和密钥的用户不该被教程扣住（引导页没有返回键），
            // 所以留一个弱化入口——用 TextButton 而不是主按钮，避免削弱引导强度。
            if (uiState.step != OnboardingStep.Done) {
                item {
                    TextButton(
                        onClick = {
                            onAction(OnboardingAction.Complete)
                            navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("跳过引导，我自己配置模型")
                    }
                }
            }

            item {
                Text(
                    text = "账号与额度来自 gcmod.online",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
            }
        }
    }
}

/* ---------------------------------- 顶部介绍卡 ---------------------------------- */

/**
 * 引导页顶部：进度指示 + 一句话说明。
 *
 * 手机端适配要点（这里的写法都是为了让窄屏不崩）：
 * - 整块用 `fillMaxWidth()` 铺满可用宽度，不设固定宽度，小屏（320dp）也不溢出；
 * - 进度条按步数等分并带 `weight(1f)`，文字不参与挤压，长标题也不会把进度条挤没；
 * - 所有文字用 `softWrap = true` + 合理的 `lineHeight`，不靠 `maxLines` 截断，
 *   因为中文说明被截断比换行更难看；
 * - 纵向留白用 dp 常量，不写死像素，配合 `scaffold` 的 innerPadding 才能正确避开
 *   状态栏与手势导航条（刘海屏、曲面屏都覆盖）。
 */
@Composable
private fun OnboardingIntroCard(uiState: OnboardingUiState) {
    CardGroup(
        title = { Text("新手引导") },
    ) {
        formItem {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (uiState.loggedIn) {
                        "已登录 ${uiState.username}，继续完成剩下的步骤就能开始聊天。"
                    } else {
                        "首次使用请先登录或注册账号，登录后才能创建密钥、同步模型。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 分步进度条：已完成的步画满，当前步半亮，未开始步留白
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(uiState.totalSteps) { index ->
                        val stepNo = index + 1
                        val active = stepNo <= uiState.stepIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (uiState.canProceed) "第 ${uiState.stepIndex} 步 / 共 ${uiState.totalSteps} 步"
                        else "第 1 步 / 共 ${uiState.totalSteps} 步 · 需先登录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/* ---------------------------------- 1/4 未登录 ---------------------------------- */

@Composable
private fun LoginStep(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
) {
    OnboardingStepCard(
        index = 1,
        total = 4,
        title = "登录 GcmodAi 账号",
        done = false,
        enabled = true,
    ) {
        formItem {
            OnboardingEmptyState(
                icon = HugeIcons.UserCircle,
                title = "还没有登录",
                description = "模型调用走 gcmod.online 网关，需要账号额度才能使用。",
            )
        }
        item(
            onClick = onLogin,
            headlineContent = { Text("登录账号") },
            supportingContent = { Text("使用 gcmod.online 账号，登录后可查看额度") },
            leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
        )
        item(
            onClick = onRegister,
            headlineContent = { Text("还没有账号？使用 QQ 邮箱注册") },
            supportingContent = { Text("注册后即可创建密钥") },
            leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
        )
    }
}

/* ---------------------------------- 2/4 无密钥 ---------------------------------- */

@Composable
private fun TokenStep(
    uiState: OnboardingUiState,
    onOpenTokens: () -> Unit,
) {
    // 硬闸门：必须先在 1/4 登录，才允许往下走。
    // 这里不是「提示一句」，而是整张卡置灰且点击无效，避免用户绕过登录直接配密钥。
    val unlocked = uiState.canProceed
    OnboardingStepCard(
        index = 2,
        total = 4,
        title = "创建 / 选择 API 密钥",
        done = false,
        enabled = unlocked,
    ) {
        if (!unlocked) {
            formItem {
                OnboardingNotice(
                    text = "请先在上面完成登录或注册，登录后才能创建密钥。",
                    error = true,
                )
            }
            return@OnboardingStepCard
        }
        item(
            onClick = onOpenTokens,
            headlineContent = { Text("去创建 / 选择密钥") },
            supportingContent = { Text("创建后自动选中，模型调用真实计费") },
            leadingContent = { CardGroupIcon(HugeIcons.Key01) },
            trailingContent = { CardGroupValue(text = "→") },
        )
        formItem {
            OnboardingNotice(text = "选中密钥后模型调用会通过官网网关计费，额度实时扣减。")
        }
    }
}

/* --------------------------- 3/4 无分组 / 未确认分组 --------------------------- */

@Composable
private fun GroupStep(
    uiState: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    OnboardingStepCard(
        index = 3,
        total = 4,
        title = "同步模型与分组",
        done = false,
        enabled = true,
    ) {
        if (uiState.groups.isEmpty()) {
            formItem {
                OnboardingLoadingRow("正在从官网同步分组…")
            }
            item(
                onClick = { if (!uiState.busy) onAction(OnboardingAction.Refresh) },
                headlineContent = { Text("重新同步") },
                supportingContent = {
                    Text(
                        if (uiState.busy) "正在同步，请稍候" else "没拉到分组时点这里重试"
                    )
                },
                leadingContent = { CardGroupIcon(HugeIcons.Tick02) },
            )
        } else {
            // 空串是合法取值：跟随账号分组
            val options = listOf("") + uiState.groups.keys.toList()
            formItem {
                ConnectedChoiceButtons(
                    options = options,
                    selected = uiState.selectedGroup,
                    onSelect = { onAction(OnboardingAction.ConfirmGroup(it)) },
                ) { group, selected ->
                    ConnectedChoiceLabel(
                        text = if (group.isBlank()) "跟随账号" else group,
                        selected = selected,
                        icon = HugeIcons.Tick02,
                    )
                }
            }
            formItem {
                OnboardingNotice(
                    text = "分组决定计费倍率与可调模型，选一次即可，之后随时能在密钥页更换。"
                )
            }
        }
    }
}

/* ------------------------ 4/4 无模型 / 未选聊天模型 ------------------------ */

@Composable
private fun ModelStep(
    uiState: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    OnboardingStepCard(
        index = 4,
        total = 4,
        title = "选择聊天模型",
        done = false,
        enabled = true,
    ) {
        if (uiState.models.isEmpty()) {
            item(
                onClick = { if (!uiState.busy) onAction(OnboardingAction.SyncModels) },
                headlineContent = { Text("同步官方模型") },
                supportingContent = {
                    Text(
                        if (uiState.busy) {
                            "正在同步，请稍候"
                        } else {
                            "从 gcmod.online 拉取当前分组可用模型"
                        }
                    )
                },
                leadingContent = { CardGroupIcon(HugeIcons.Brain02) },
                trailingContent = { CardGroupValue(text = "→") },
            )
            if (uiState.busy) {
                formItem {
                    OnboardingLoadingRow("正在同步模型…")
                }
            }
        } else {
            // 这里直接用同步下来的官方模型列表，而不是 ModelSelector：
            // 本页是纯展示签名（只拿得到 uiState），而 ModelSelector 需要完整 providers，
            // 且它的交互是「按钮 + 底部弹层」两步。引导阶段把候选直接铺成条目，
            // 点一下即选中，少一步跳转，也更贴合「跟着步骤走」的语境。
            uiState.models.forEach { model ->
                val selected = uiState.chatModel?.id == model.id
                item(
                    onClick = { onAction(OnboardingAction.SelectModel(model)) },
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(model.displayName.ifBlank { model.modelId })
                            if (selected) {
                                CardGroupValue(text = "已选")
                            }
                        }
                    },
                    supportingContent = { Text(model.modelId) },
                    leadingContent = { CardGroupIcon(HugeIcons.Brain02) },
                )
            }
            formItem {
                OnboardingNotice(text = "已同步 ${uiState.models.size} 个可用模型，选一个就能开始聊天。")
            }
        }
    }
}

/* ---------------------------------- 全部就绪 ---------------------------------- */

@Composable
private fun DoneStep(
    uiState: OnboardingUiState,
    onStartChat: () -> Unit,
) {
    OnboardingStepCard(
        index = 4,
        total = 4,
        title = "全部就绪",
        done = true,
        enabled = true,
    ) {
        item(
            headlineContent = { Text("当前账号") },
            trailingContent = { CardGroupValue(text = uiState.username.ifBlank { "已登录" }) },
        )
        uiState.chatModel?.let { model ->
            item(
                headlineContent = { Text("聊天模型") },
                supportingContent = { Text(model.modelId) },
                trailingContent = { CardGroupValue(text = "已选") },
            )
        }
        formItem {
            Button(
                onClick = onStartChat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始聊天")
            }
        }
    }
}

/* ---------------------------------- Preview ---------------------------------- */

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingLoginPreview() {
    OnboardingPreview(
        OnboardingUiState(step = OnboardingStep.Login, stepIndex = 1, totalSteps = 4)
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingTokenPreview() {
    OnboardingPreview(
        OnboardingUiState(
            step = OnboardingStep.Token,
            stepIndex = 2,
            username = "gcmod",
        )
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingGroupPreview() {
    OnboardingPreview(
        OnboardingUiState(
            step = OnboardingStep.Group,
            stepIndex = 3,
            username = "gcmod",
            groups = linkedMapOf(
                "" to GroupInfo(desc = "跟随账号分组", ratio = 1.0),
                "vip" to GroupInfo(desc = "高倍率分组", ratio = 3.0),
            ),
        )
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingModelPreview() {
    OnboardingPreview(
        OnboardingUiState(
            step = OnboardingStep.Model,
            stepIndex = 4,
            username = "gcmod",
            models = listOf(Model(modelId = "deepseek-chat", displayName = "deepseek-chat")),
        )
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun OnboardingDonePreview() {
    OnboardingPreview(
        OnboardingUiState(
            step = OnboardingStep.Done,
            stepIndex = 4,
            username = "gcmod",
            models = listOf(Model(modelId = "deepseek-chat", displayName = "deepseek-chat")),
            chatModel = Model(modelId = "deepseek-chat", displayName = "deepseek-chat"),
        )
    )
}

/** Preview 里没有导航环境，套一层主题壳只为把页面渲染出来 */
@Composable
private fun OnboardingPreview(state: OnboardingUiState) {
    MaterialTheme {
        OnboardingPage(uiState = state, onAction = {})
    }
}
