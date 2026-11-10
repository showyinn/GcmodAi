package com.gcmodai.app.ui.pages.onboarding

import android.content.Context
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.SettingsStore
import com.gcmodai.app.data.user.OfficialProviderManager
import com.gcmodai.app.data.user.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 新手引导的状态持有者。
 *
 * 本项目里页面普遍直接用 `koinInject()` 拿仓库（见 AccountPage / TokenPage），
 * 这里沿用同一套惯例：Koin 单例 + 应用级作用域，而不是 AndroidViewModel——
 * 它需要随时读 SharedPreferences（Context）和多个仓库，做成 ViewModel 反而别扭。
 *
 * 注意 [scope] 的类型必须是具体的 [AppScope] 而不是 `CoroutineScope`：
 * Koin 里只注册了 `single { AppScope() }`（AppModule），**没有** `CoroutineScope` 的定义
 * （UserRepository 同样声明 `appScope: AppScope`）。写成 `CoroutineScope` 会让
 * `get()` 在解析时抛 NoDefinitionFoundException，表现为「一进引导页就闪退」。
 *
 * 所有判定都从既有 StateFlow 推导，因此退出登录 / 删除密钥后状态会自动回退，
 * 不需要写任何额外的同步逻辑。
 */
class OnboardingVM(
    private val context: Context,
    private val userRepository: UserRepository,
    private val settingsStore: SettingsStore,
    private val officialProviderManager: OfficialProviderManager,
    private val scope: AppScope,
) {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /**
     * 本次会话的出口 IP。
     *
     * 页面进入引导时会读它来标记「这个 IP 已经引导过」。
     * 由 VM 自己探测（而不是页面传），保证「判定要不要引导」和「记录已引导」用的是同一个值——
     * 多出口 NAT 下两次独立请求可能拿到不同地址，那会让同一 IP 反复触发。
     */
    private val _currentIp = MutableStateFlow<String?>(null)
    val currentIp: StateFlow<String?> = _currentIp.asStateFlow()

    init {
        scope.launch {
            _currentIp.value = IpProbe.fetch()
        }
        scope.launch {
            combine(
                userRepository.sessionFlow,
                settingsStore.settingsFlow,
                userRepository.groups,
                userRepository.busy,
            ) { session, settings, groups, busy ->
                Snapshot(session, settings, groups, busy)
            }.collect { snapshot ->
                val officialModels = officialModelsOf(snapshot.settings)
                _state.value = evaluate(
                    session = snapshot.session,
                    settings = snapshot.settings,
                    groups = snapshot.groups,
                    groupConfirmed = OnboardingPrefs.isGroupConfirmed(context),
                    officialModels = officialModels,
                ).copy(busy = snapshot.busy, error = _state.value.error)
            }
        }
    }

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Refresh -> scope.launch {
                clearError()
                userRepository.refreshTokens()
            }

            is OnboardingAction.ConfirmGroup -> confirmGroup(action.group)

            OnboardingAction.SyncModels -> scope.launch {
                clearError()
                try {
                    officialProviderManager.syncModelsAndApply()
                } catch (e: Exception) {
                    setError(e.message ?: "同步模型失败")
                }
            }

            is OnboardingAction.SelectModel -> scope.launch {
                clearError()
                settingsStore.update { it.copy(chatModelId = action.model.id) }
            }

            OnboardingAction.Complete -> OnboardingPrefs.setCompleted(context, true)
        }
    }

    /**
     * 记下用户确认过分组，并把选择写回密钥。
     *
     * 分组写回失败不阻断引导：标记已经落盘，用户下次进来不会卡在同一步。
     */
    private fun confirmGroup(group: String) {
        OnboardingPrefs.setGroupConfirmed(context, true)
        scope.launch {
            clearError()
            val session = userRepository.sessionFlow.value ?: return@launch
            val token = userRepository.tokens.value.firstOrNull { it.id == session.selectedTokenId }
            if (token == null) {
                userRepository.refreshTokens()
                return@launch
            }
            if (token.group != group) {
                userRepository.changeTokenGroup(token, group)
                    .exceptionOrNull()
                    ?.let { setError(it.message ?: "切换分组失败") }
            }
        }
    }

    /** 官方通道侧真正可用的模型；未启用或没同步过时为空 */
    private fun officialModelsOf(settings: Settings) =
        officialProviderManager.currentProvider(settings)
            ?.takeIf { it.enabled }
            ?.models
            .orEmpty()

    private fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    private fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    private data class Snapshot(
        val session: com.gcmodai.app.data.user.UserSession?,
        val settings: Settings,
        val groups: Map<String, com.gcmodai.app.data.user.GroupInfo>,
        val busy: Boolean,
    )
}
