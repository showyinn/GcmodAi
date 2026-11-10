package com.gcmodai.app.ui.pages.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.ReceiptText
import me.rerere.hugeicons.stroke.Wallet01
import org.koin.compose.koinInject
import com.gcmodai.app.data.user.BillingLog
import com.gcmodai.app.data.user.BillingSummary
import com.gcmodai.app.data.user.ModelSpend
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.plus
import java.text.DecimalFormat

/**
 * 账单明细。
 *
 * 每次模型调用扣的钱都在这里：请求时间、模型、分组、输入/输出 token、
 * 扣费金额、剩余余额、请求 id，一条不落。数据直连网关 /api/log/self。
 */
@Composable
fun BillingPage() {
    val vm: BillingVM = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()

    // 滚到接近底部就自动加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.hasMore, state.loadingMore) {
        if (shouldLoadMore && state.hasMore && !state.loadingMore) vm.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账单明细") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        if (state.loading) {
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
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 余额 ----------
            item(key = "balance") {
                BalanceCard(
                    balanceUsd = state.balanceUsd,
                    usedUsd = state.usedUsd,
                    usedQuota = state.usedQuota,
                    balanceQuota = state.balanceQuota,
                )
            }

            // ---------- 汇总 ----------
            item(key = "summary") {
                SummaryCard(state.summary)
            }

            // ---------- 筛选 ----------
            item(key = "filter") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.onlyCharge,
                        onClick = { vm.setOnlyCharge(true) },
                        label = { Text("只看消费") },
                    )
                    FilterChip(
                        selected = !state.onlyCharge,
                        onClick = { vm.setOnlyCharge(false) },
                        label = { Text("全部记录") },
                    )
                }
            }

            // ---------- 错误 ----------
            state.error?.let { msg ->
                item(key = "error") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.clearError() }) { Text("知道了") }
                        }
                    }
                }
            }

            // ---------- 空态 ----------
            if (!state.loading && state.filtered.isEmpty() && state.error == null) {
                item(key = "empty") {
                    EmptyState(state.onlyCharge)
                }
            }

            // ---------- 明细列表 ----------
            items(state.filtered, key = { "log-${it.id}-${it.createdAt}" }) { log ->
                LogCard(log)
            }

            // ---------- 加载更多 ----------
            if (state.loadingMore) {
                item(key = "loading_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (!state.hasMore && state.filtered.isNotEmpty()) {
                item(key = "end") {
                    Text(
                        text = "已加载全部记录 · 共 ${state.total} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

/* ------------------------------ 余额卡片 ------------------------------ */

@Composable
private fun BalanceCard(
    balanceUsd: Double,
    usedUsd: Double,
    usedQuota: Long,
    balanceQuota: Long,
) {
    val total = balanceQuota + usedQuota
    val ratio = if (total > 0) (balanceQuota.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.Wallet01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = "当前余额",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    Text(
                        text = formatUsd(balanceUsd),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "累计已用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = formatUsd(usedUsd),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "累计总额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = formatUsd(balanceUsd + usedUsd),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/* ------------------------------ 汇总卡片 ------------------------------ */

@Composable
private fun SummaryCard(summary: BillingSummary) {
    CardGroup(title = { Text("消费汇总") }) {
        formItem(
            leadingContent = {
                CardGroupIcon(
                    icon = HugeIcons.ReceiptText,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            },
            supportingContent = { Text("已加载 ${summary.totalRequests} 次模型调用") },
        ) {
            Text("消费概览")
        }
        // CardGroup 的 content lambda 不是 @Composable，必须用 item 包一层
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                StatLine("今日消费", formatUsd(summary.todaySpentUsd))
                StatLine("今日调用", "${summary.todayRequests} 次")
                StatLine(
                    "今日 token",
                    "${summary.todayPromptTokens + summary.todayCompletionTokens}"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                StatLine("加载范围消费", formatUsd(summary.totalSpentUsd))
                StatLine("输入 token", "${summary.totalPromptTokens}")
                StatLine("输出 token", "${summary.totalCompletionTokens}")
                StatLine("合计 token", "${summary.totalTokens}")
            }
        }
    }

    if (summary.byModel.isNotEmpty()) {
        CardGroup(title = { Text("按模型统计") }) {
            item {
                Column {
                    summary.byModel.take(8).forEach { m ->
                        ModelSpendRow(m)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSpendRow(spend: ModelSpend) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spend.model,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${spend.requests} 次 · ${spend.totalTokens} token",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatUsd(spend.spentUsd),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/* ------------------------------ 单条明细 ------------------------------ */

@Composable
private fun LogCard(log: BillingLog) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 第一行：时间 + 扣费
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = log.timeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (log.quota > 0) {
                    Text(
                        text = "-" + formatUsd(log.quotaUsd),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = log.typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 第二行：模型名
            if (log.modelName.isNotBlank()) {
                Text(
                    text = log.modelName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    text = log.content.ifBlank { log.typeLabel },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 第三行：token 与金额细项
            if (log.isCharge) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MiniStat("输入", "${log.promptTokens}")
                    MiniStat("输出", "${log.completionTokens}")
                    MiniStat("合计", "${log.totalTokens}")
                    MiniStat("扣费", formatRaw(log.quota))
                }
            }

            // 第四行：补充信息
            val extra = buildList {
                if (log.group.isNotBlank()) add("分组 ${log.group}")
                if (log.tokenName.isNotBlank()) add("密钥 ${log.tokenName}")
                if (log.detailExtra.isNotBlank()) add(log.detailExtra)
            }
            if (extra.isNotEmpty()) {
                Text(
                    text = extra.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 第五行：请求 id（可复制去后台排查）
            if (log.requestId.isNotBlank()) {
                Text(
                    text = "ID ${log.requestId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ------------------------------ 空态 ------------------------------ */

@Composable
private fun EmptyState(onlyCharge: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = HugeIcons.MoneyBag02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(
            text = if (onlyCharge) "还没有消费记录" else "还没有账单记录",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (onlyCharge) "随便聊一句，这里就会立刻出现扣费明细" else "调用模型后这里会显示全部计费日志",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/* ------------------------------ 格式化 ------------------------------ */

private val usdFormat = DecimalFormat("$0.000000")

private fun formatUsd(value: Double): String = usdFormat.format(value)

private fun formatRaw(quota: Long): String {
    return if (quota >= 10000) {
        DecimalFormat("#,##0").format(quota)
    } else {
        quota.toString()
    }
}
