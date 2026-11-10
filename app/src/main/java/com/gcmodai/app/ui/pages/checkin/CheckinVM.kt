package com.gcmodai.app.ui.pages.checkin

import androidx.lifecycle.ViewModel
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.user.CheckinResult
import com.gcmodai.app.data.user.CheckinStatus
import com.gcmodai.app.data.user.UserApi
import com.gcmodai.app.data.user.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 签到页状态。
 *
 * [status] 完全来自服务端，本地不缓存「今天签没签」，
 * 每次进页面都重新拉一次——因为用户可能刚在网站上签过，
 * 本地缓存会导致 App 显示「还能签」然后点了报错。
 */
data class CheckinUiState(
    val loading: Boolean = false,
    val checking: Boolean = false,
    val status: CheckinStatus? = null,
    val error: String? = null,
    /** 最近一次签到成功的奖励额度，用于弹出庆祝提示；消费后清空 */
    val justAwarded: Long? = null,
    /** 最近一次操作的提示语（"今日已签到"等） */
    val toast: String? = null,
)

class CheckinVM(
    private val repository: UserRepository,
    private val userApi: UserApi,
    private val scope: AppScope,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckinUiState())
    val state: StateFlow<CheckinUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 拉取最新签到状态。 */
    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val token = repository.sessionFlow.value?.accessToken.orEmpty()
            if (token.isBlank()) {
                _state.update { it.copy(loading = false, error = "请先登录账号") }
                return@launch
            }
            runCatching { userApi.getCheckinStatus(token) }
                .onSuccess { st ->
                    _state.update { it.copy(loading = false, status = st, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "获取签到状态失败")
                    }
                }
        }
    }

    /** 执行签到。成功后刷新状态与账户额度。 */
    fun checkin() {
        val current = _state.value
        if (current.checking) return
        // 服务端已明确今天签过，就不必再打一次接口
        if (current.status?.stats?.checkedInToday == true) {
            _state.update { it.copy(toast = "今日已签到，明天再来") }
            return
        }
        scope.launch {
            _state.update { it.copy(checking = true, error = null) }
            val token = repository.sessionFlow.value?.accessToken.orEmpty()
            if (token.isBlank()) {
                _state.update { it.copy(checking = false, error = "请先登录账号") }
                return@launch
            }

            val result = runCatching { userApi.checkin(token) }
                .getOrElse { CheckinResult.Failed(it.message ?: "签到失败") }

            when (result) {
                is CheckinResult.Success -> {
                    _state.update {
                        it.copy(checking = false, justAwarded = result.quotaAwarded)
                    }
                    // 额度变了，刷新资料让账户页同步
                    repository.refreshProfile()
                    refresh()
                }
                CheckinResult.AlreadyCheckedIn -> {
                    _state.update {
                        it.copy(checking = false, toast = "今日已签到，明天再来")
                    }
                    // 状态可能不同步（比如刚在网站签过），重拉一次对齐
                    refresh()
                }
                CheckinResult.Disabled -> {
                    _state.update {
                        it.copy(checking = false, toast = "签到功能暂未开放")
                    }
                }
                is CheckinResult.Failed -> {
                    _state.update { it.copy(checking = false, error = result.message) }
                }
            }
        }
    }

    fun consumeAward() {
        _state.update { it.copy(justAwarded = null) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
