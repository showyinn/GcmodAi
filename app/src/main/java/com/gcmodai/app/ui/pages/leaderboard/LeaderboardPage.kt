package com.gcmodai.app.ui.pages.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcmodai.app.data.remote.LeaderboardData
import com.gcmodai.app.data.remote.LeaderboardItem
import com.gcmodai.app.data.remote.LeaderboardRepository
import com.gcmodai.app.data.remote.LeaderboardResult
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.theme.CustomColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 排行榜：富豪榜（累计充值）+ 词元榜（GcmodAi 供应商累计 tokens）。
 *
 * 数据来自 https://gcmod.online/api/leaderboard，服务端只返回前 10 名，
 * 且只统计在站内（供应商 GcmodAi）真正消耗过的账号——用自己外部渠道的用户不会出现。
 */
@Composable
fun LeaderboardPage() {
    val repository: LeaderboardRepository = koinInject()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(LeaderboardTab.Wealth) }
    var data by remember { mutableStateOf<LeaderboardData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            when (val result = repository.fetch()) {
                is LeaderboardResult.Ok -> data = result.data
                is LeaderboardResult.Err -> error = result.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("排行榜") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { load() }, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("刷新")
                        }
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == LeaderboardTab.Wealth,
                        onClick = { tab = LeaderboardTab.Wealth },
                        label = { Text("富豪榜") },
                    )
                    FilterChip(
                        selected = tab == LeaderboardTab.Tokens,
                        onClick = { tab = LeaderboardTab.Tokens },
                        label = { Text("词元榜") },
                    )
                }
            }

            val currentError = error
            if (currentError != null && data == null) {
                item {
                    Text(
                        text = "加载失败：$currentError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            val items = when (tab) {
                LeaderboardTab.Wealth -> data?.wealth.orEmpty()
                LeaderboardTab.Tokens -> data?.tokens.orEmpty()
            }

            if (items.isEmpty() && !loading && currentError == null) {
                item {
                    Text(
                        text = "榜单还在统计中，稍后刷新看看。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (items.isNotEmpty()) {
                item {
                    CardGroup(title = { Text(tab.title) }) {
                        items.forEach { entry ->
                            item(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "#${entry.rank}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = rankColor(entry.rank),
                                        )
                                        Text(
                                            text = "  ${entry.display.ifBlank { entry.name }}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                },
                                supportingContent = {
                                    Text(
                                        when (tab) {
                                            LeaderboardTab.Wealth ->
                                                "消耗 ${formatTokens(entry.tokens)} tokens"
                                            LeaderboardTab.Tokens ->
                                                "调用 ${entry.requests} 次 · 余额 ${formatTokens(entry.quota)}"
                                        }
                                    )
                                },
                                trailingContent = {
                                    CardGroupValue(
                                        text = when (tab) {
                                            LeaderboardTab.Wealth -> formatMoney(entry.amount)
                                            LeaderboardTab.Tokens -> formatTokens(entry.tokens)
                                        }
                                    )
                                },
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = tab.basis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private enum class LeaderboardTab(val title: String, val basis: String) {
    Wealth(
        title = "富豪榜",
        basis = "按站内累计充值金额排序（仅成功订单）；榜内用户名已打码，只统计使用供应商 GcmodAi 的用户，展示前 10 名。",
    ),
    Tokens(
        title = "词元榜",
        basis = "按 GcmodAi 供应商累计消耗 tokens 排序（输入 + 输出）；榜内用户名已打码，只统计使用供应商 GcmodAi 的用户，展示前 10 名。",
    ),
}

@Composable
private fun rankColor(rank: Int) = when (rank) {
    1 -> MaterialTheme.colorScheme.tertiary
    2, 3 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 1.72B / 15.39M / 7.48万 这样的紧凑写法，避免长数字把行撑开 */
internal fun formatTokens(value: Long): String = when {
    value >= 1_000_000_000L -> "%.2fB".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.2fM".format(value / 1_000_000.0)
    value >= 10_000L -> "%.2f万".format(value / 10_000.0)
    else -> value.toString()
}

internal fun formatMoney(value: Double): String {
    val rounded = (value * 100).toLong() / 100.0
    return if (rounded % 1.0 == 0.0) "¥${rounded.toLong()}" else "¥$rounded"
}

/** 供 UI 测试/预览用的空榜单占位 */
internal val EmptyLeaderboard = LeaderboardData()
