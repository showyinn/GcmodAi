package com.gcmodai.app.ui.pages.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.user.BillingLog
import com.gcmodai.app.data.user.BillingSummary
import com.gcmodai.app.data.user.UserApi
import com.gcmodai.app.data.user.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账单明细页的状态。
 *
 * [logs] 是当前已加载的全部日志（分页累加），[summary] 由它现场聚合，
 * 这样用户翻到第几页、汇总就统计到第几页，不会出现「汇总和列表对不上」。
 */
data class BillingUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val logs: List<BillingLog> = emptyList(),
    val summary: BillingSummary = BillingSummary(),
    val page: Int = 1,
    val total: Long = 0L,
    val hasMore: Boolean = false,
    val error: String? = null,
    val onlyCharge: Boolean = true,
    /** 余额（quota），来自用户资料 */
    val balanceQuota: Long = 0L,
    val usedQuota: Long = 0L,
) {
    val balanceUsd: Double get() = balanceQuota / 500000.0
    val usedUsd: Double get() = usedQuota / 500000.0
    val filtered: List<BillingLog>
        get() = if (onlyCharge) logs.filter { it.isCharge } else logs
}

/**
 * 账单明细 ViewModel。
 *
 * 依赖注入用 [AppScope]——本项目里 Koin 只注册了 AppScope，
 * 声明成裸 CoroutineScope 会在 koinInject 时抛 NoDefinitionFoundException，
 * 这是踩过的坑（OnboardingVM 就是同样的原因闪退过）。
 */
class BillingVM(
    private val repository: UserRepository,
    private val userApi: UserApi,
    private val scope: AppScope,
) : ViewModel() {

    private val _state = MutableStateFlow(BillingUiState())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    private val pageSize = 20

    init {
        refresh()
    }

    /** 从头拉第一页，同时刷新余额。 */
    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val token = repository.sessionFlow.value?.accessToken.orEmpty()
            if (token.isBlank()) {
                _state.update { it.copy(loading = false, error = "请先登录账号") }
                return@launch
            }

            // 余额和日志并行拉：谁失败都不该拖垮整页
            repository.refreshProfile()

            runCatching {
                userApi.listBillingLogs(accessToken = token, page = 1, pageSize = pageSize)
            }.onSuccess { pageData ->
                val session = repository.sessionFlow.value
                _state.update {
                    it.copy(
                        loading = false,
                        logs = pageData.items,
                        summary = BillingSummary.from(pageData.items),
                        page = 1,
                        total = pageData.total,
                        hasMore = pageData.items.size >= pageSize,
                        error = null,
                        balanceQuota = session?.user?.quota ?: it.balanceQuota,
                        usedQuota = session?.user?.usedQuota ?: it.usedQuota,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = e.message ?: "获取账单明细失败")
                }
            }
        }
    }

    /** 加载下一页并累加。 */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore) return
        scope.launch {
            _state.update { it.copy(loadingMore = true) }
            val token = repository.sessionFlow.value?.accessToken.orEmpty()
            if (token.isBlank()) {
                _state.update { it.copy(loadingMore = false) }
                return@launch
            }
            val next = current.page + 1
            runCatching {
                userApi.listBillingLogs(accessToken = token, page = next, pageSize = pageSize)
            }.onSuccess { pageData ->
                _state.update {
                    val merged = it.logs + pageData.items
                    it.copy(
                        loadingMore = false,
                        logs = merged,
                        summary = BillingSummary.from(merged),
                        page = next,
                        total = pageData.total,
                        hasMore = pageData.items.size >= pageSize,
                        error = null,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载更多失败") }
            }
        }
    }

    /** 切换「只看消费」筛选。 */
    fun setOnlyCharge(value: Boolean) {
        _state.update { it.copy(onlyCharge = value) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
