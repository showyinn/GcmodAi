package com.gcmodai.app.ui.pages.onboarding

import android.content.Context
import androidx.compose.runtime.Immutable
import com.gcmodai.ai.provider.Model
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.findModelById
import com.gcmodai.app.data.user.GroupInfo
import com.gcmodai.app.data.user.UserSession
import com.gcmodai.app.ui.hooks.readBooleanPreference
import com.gcmodai.app.ui.hooks.writeBooleanPreference

/**
 * 新手引导的步骤。
 *
 * 注意：这是**显示**用的步骤（共 4 步），不是判定的分支数。
 * [evaluate] 的短路顺序里有 6 个分支，其中「同步」不作为独立一步——
 * 它属于当前步内部的子内容（无分组时显示「正在同步」，无模型时显示「同步模型」按钮）。
 */
enum class OnboardingStep {
    /** 1/4 未登录 */
    Login,

    /** 2/4 已登录但还没选定 API 密钥 */
    Token,

    /** 3/4 有密钥但分组还没确认 */
    Group,

    /** 4/4 官方通道还没有可用模型，或还没选聊天模型 */
    Model,

    /** 全部就绪 */
    Done,
}

/** 引导页面用到的全部状态，纯展示、无业务。 */
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Login,
    /** 显示用步号，1..4；Done 时为 4 */
    val stepIndex: Int = 1,
    val totalSteps: Int = 4,
    /** 当前登录用户名，未登录为空 */
    val username: String = "",
    /** 可选分组，未拉到为空 */
    val groups: Map<String, GroupInfo> = emptyMap(),
    /** 当前选中的分组（空串代表「跟随账号」） */
    val selectedGroup: String = "",
    /** 官方通道已同步到的模型 */
    val models: List<Model> = emptyList(),
    /** 当前选中的聊天模型 */
    val chatModel: Model? = null,
    /** 共享的单飞标记，网络按钮统一 enabled = !busy */
    val busy: Boolean = false,
    /** 最近一次操作失败的原因 */
    val error: String? = null,
    /**
     * 是否已登录。
     *
     * 不复用 `username.isNotBlank()` 判断：用户名理论上可能为空串，
     * 而登录态是后续所有步骤的硬闸门，必须由 session 是否存在直接决定。
     */
    val loggedIn: Boolean = false,
) {
    /**
     * 只有第 1 步（登录）完成，后面的步骤才可交互。
     *
     * 产品要求：必须**先登录或注册**才能进行下一步。这里做成硬闸门，
     * 后三步在未登录时整体置灰且不响应点击，而不只是提示一句。
     */
    val canProceed: Boolean get() = loggedIn
}

/**
 * 引导页发出的动作。
 *
 * 只承载**业务动作**，不含导航——导航由 Composable 侧用 LocalNavController 完成
 * （LocalNavController 是 compositionLocal，ViewModel 拿不到）。
 */
sealed interface OnboardingAction {
    /** 重新评估状态（例如从其它页面返回后） */
    data object Refresh : OnboardingAction

    /** 用户确认了分组选择，记一次性标记 */
    data class ConfirmGroup(val group: String) : OnboardingAction

    /** 触发官方通道模型同步 */
    data object SyncModels : OnboardingAction

    /** 选中聊天模型 */
    data class SelectModel(val model: Model) : OnboardingAction

    /** 完成引导，写 onboarding_completed 标记 */
    data object Complete : OnboardingAction
}

/**
 * 引导相关的一次性标记。
 *
 * 放 SharedPreferences 而不是 Settings / UserSession：
 * - 放 UserSession 会被 logout 的 store.clear() 一起清掉；
 * - 放 Settings 要动 SettingsFields / SettingsNormalization / BackupManager 三处迁移链路。
 * 同级别的 `create_new_conversation_on_start` 也是这么存的。
 */
object OnboardingPrefs {
    const val KEY_COMPLETED = "onboarding_completed"
    const val KEY_GROUP_CONFIRMED = "onboarding_group_confirmed"

    fun isCompleted(context: Context): Boolean =
        context.readBooleanPreference(KEY_COMPLETED, false)

    fun setCompleted(context: Context, value: Boolean) =
        context.writeBooleanPreference(KEY_COMPLETED, value)

    fun isGroupConfirmed(context: Context): Boolean =
        context.readBooleanPreference(KEY_GROUP_CONFIRMED, false)

    fun setGroupConfirmed(context: Context, value: Boolean) =
        context.writeBooleanPreference(KEY_GROUP_CONFIRMED, value)

    /** 重置所有引导标记，用于「重新观看教程」 */
    fun reset(context: Context) {
        setCompleted(context, false)
        setGroupConfirmed(context, false)
    }
}

/**
 * 依据真实状态判定当前该停在哪一步，纯函数、幂等、无副作用。
 *
 * 顺序是短路的：只要能确定用户还卡在前面某一步，就直接返回那一步。
 * 所有判定都读既有的 StateFlow 值，不引入任何新的持久化字段——
 * 因此退出登录、删掉密钥后再次求值会自动回退到对应步骤，不需要额外的同步逻辑。
 *
 * 一个容易写错的点：**不能**用 `settings.findModelById(chatModelId) != null` 当闸门。
 * 内置的 DEFAULT_PROVIDERS 会让它几乎恒为真（Settings.kt:59 且 normalized() 每次补回），
 * 所以未登录用户也可能「已经有模型」。这里改用官方通道侧是否真的有模型。
 */
fun evaluate(
    session: UserSession?,
    settings: Settings,
    groups: Map<String, GroupInfo>,
    groupConfirmed: Boolean,
    officialModels: List<Model>,
): OnboardingUiState {
    // 1. 没登录
    if (session == null) {
        return OnboardingUiState(step = OnboardingStep.Login, stepIndex = 1, loggedIn = false)
    }

    val base = OnboardingUiState(
        username = session.user.username,
        groups = groups,
        selectedGroup = session.selectedTokenGroup,
        loggedIn = true,
    )

    // 2. 没有可用的 API 密钥
    if (session.selectedTokenId == 0L || session.selectedTokenKey.isBlank()) {
        return base.copy(step = OnboardingStep.Token, stepIndex = 2)
    }

    // 3. 分组未拉取，或还没确认过
    //    注意空串 "" 是合法取值（跟随账号），所以判定靠一次性标记而不是字符串非空
    if (groups.isEmpty() || !groupConfirmed) {
        return base.copy(step = OnboardingStep.Group, stepIndex = 3)
    }

    // 4. 官方通道没有模型，或还没选聊天模型
    val chatModel = settings.findModelById(settings.chatModelId)
    if (officialModels.isEmpty() || chatModel == null) {
        return base.copy(
            step = OnboardingStep.Model,
            stepIndex = 4,
            models = officialModels,
            chatModel = chatModel,
        )
    }

    // 5. 全部就绪
    return base.copy(
        step = OnboardingStep.Done,
        stepIndex = 4,
        models = officialModels,
        chatModel = chatModel,
    )
}
