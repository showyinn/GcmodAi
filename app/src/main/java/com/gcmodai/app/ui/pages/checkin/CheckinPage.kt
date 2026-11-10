package com.gcmodai.app.ui.pages.checkin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CheckmarkBadge01
import me.rerere.hugeicons.stroke.Gift
import me.rerere.hugeicons.stroke.MoneyBag02
import org.koin.compose.koinInject
import com.gcmodai.app.data.user.CheckinRecord
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.plus
import java.text.DecimalFormat

/**
 * 每日签到。
 *
 * 状态全部以服务端为准：数据库 `checkins` 表带唯一约束，
 * 网站和 App 共用同一张表，所以在网站签过之后这里会立刻显示「今日已签到」。
 * 本地不做任何日期推断。
 */
@Composable
fun CheckinPage() {
    val vm: CheckinVM = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 进页面先对齐一次服务端状态
    LaunchedEffect(Unit) { vm.refresh() }

    val status = state.status
    val stats = status?.stats
    val checkedToday = stats?.checkedInToday == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("每日签到") },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 今日签到卡 ----------
            item(key = "today") {
                TodayCard(
                    checkedToday = checkedToday,
                    enabled = status?.enabled ?: false,
                    checking = state.checking,
                    minUsd = status?.minQuotaUsd ?: 0.0,
                    maxUsd = status?.maxQuotaUsd ?: 0.0,
                    onCheckin = { vm.checkin() },
                )
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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

            // ---------- 累计统计 ----------
            if (stats != null) {
                item(key = "stats") {
                    CardGroup(title = { Text("我的签到") }) {
                        item(
                            headlineContent = { Text("累计签到") },
                            supportingContent = { Text("每天可签一次，网站与 App 同步") },
                            leadingContent = { CardGroupIcon(HugeIcons.Calendar03) },
                            trailingContent = { CardGroupValue(text = "${stats.totalCheckins} 天") },
                        )
                        item(
                            headlineContent = { Text("累计获得") },
                            supportingContent = { Text("签到发放的额度总额") },
                            leadingContent = { CardGroupIcon(HugeIcons.MoneyBag02) },
                            trailingContent = {
                                CardGroupValue(text = formatUsd(stats.totalQuotaUsd))
                            },
                        )
                        item(
                            headlineContent = { Text("最近签到") },
                            supportingContent = { Text("最多显示最近 3 次") },
                            leadingContent = { CardGroupIcon(HugeIcons.CheckmarkBadge01) },
                            trailingContent = {
                                CardGroupValue(text = "${stats.checkinCount} 次")
                            },
                        )
                    }
                }

                // ---------- 历史记录 ----------
                if (stats.records.isNotEmpty()) {
                    item(key = "records") {
                        CardGroup(title = { Text("签到记录") }) {
                            item {
                                Column {
                                    stats.records.forEach { r ->
                                        RecordRow(r)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 加载中 ----------
            if (state.loading && status == null) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    // 签到成功的庆祝提示
    AnimatedVisibility(
        visible = state.justAwarded != null,
        enter = scaleIn(),
        exit = scaleOut(),
    ) {
        state.justAwarded?.let { awarded ->
            SuccessDialog(
                quotaUsd = awarded / 500000.0,
                onDismiss = { vm.consumeAward() },
            )
        }
    }
}

/* ------------------------------ 今日签到卡 ------------------------------ */

@Composable
private fun TodayCard(
    checkedToday: Boolean,
    enabled: Boolean,
    checking: Boolean,
    minUsd: Double,
    maxUsd: Double,
    onCheckin: () -> Unit,
) {
    val containerColor = if (checkedToday) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (checkedToday) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (checkedToday) HugeIcons.CheckmarkBadge01 else HugeIcons.Gift,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Text(
                text = when {
                    !enabled -> "签到暂未开放"
                    checkedToday -> "今日已签到"
                    else -> "今日还未签到"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )

            Text(
                text = if (minUsd > 0 && maxUsd > 0) {
                    "每次随机获得 ${formatUsd(minUsd)} ~ ${formatUsd(maxUsd)} 额度"
                } else {
                    "每天可签到一次"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = onCheckin,
                enabled = enabled && !checkedToday && !checking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = containerColor,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = containerColor,
                    )
                } else {
                    Text(
                        text = when {
                            !enabled -> "暂未开放"
                            checkedToday -> "明天再来"
                            else -> "立即签到"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = "网站与 App 共用同一份签到记录，任一处签到后另一处立即同步",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* ------------------------------ 记录行 ------------------------------ */

@Composable
private fun RecordRow(record: CheckinRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = record.checkinDate,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+" + formatUsd(record.quotaUsd),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/* ------------------------------ 成功弹窗 ------------------------------ */

@Composable
private fun SuccessDialog(quotaUsd: Double, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = HugeIcons.CheckmarkBadge01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        },
        title = { Text("签到成功", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "+" + formatUsd(quotaUsd),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "额度已到账，可在「我的账号」查看余额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好") }
        },
    )
}

/* ------------------------------ 格式化 ------------------------------ */

private val usdFormat = DecimalFormat("$0.000000")

private fun formatUsd(value: Double): String = usdFormat.format(value)
