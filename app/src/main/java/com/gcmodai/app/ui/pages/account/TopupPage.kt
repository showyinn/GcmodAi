package com.gcmodai.app.ui.pages.account

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Gift
import me.rerere.hugeicons.stroke.Wallet01
import org.koin.compose.koinInject
import com.gcmodai.app.data.user.SubscriptionPlan
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.data.user.formatQuotaUsd
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.openUrl
import com.gcmodai.app.utils.plus

private val PRESET_AMOUNTS = listOf(1L, 5L, 10L, 20L, 50L)

/** 站点实际可用的在线支付渠道（实测只有这两个） */
private val PAY_CHANNELS = listOf("alipay" to "支付宝", "wxpay" to "微信")

/**
 * 充值页。
 *
 * 三条真实通道：
 * 1. 钱包充值 —— POST /api/user/pay，字段是 payment_method(alipay/wxpay)，返回易支付收银台；
 * 2. 套餐订阅 —— POST /api/subscription/epay/pay，购买后按套餐解锁分组（如 vip）；
 * 3. 兑换码   —— POST /api/user/topup，即时到账。
 */
@Composable
fun TopupPage() {
    val repository: UserRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val session by repository.sessionFlow.collectAsStateWithLifecycle()
    val busy by repository.busy.collectAsStateWithLifecycle()

    var amount by rememberSaveable { mutableStateOf(10L) }
    var customAmount by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var payChannel by rememberSaveable { mutableStateOf("alipay") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf<List<SubscriptionPlan>>(emptyList()) }
    var plansLoading by remember { mutableStateOf(true) }

    // 服务端只接受整数金额，自定义输入也按整数处理
    val effectiveAmount: Long =
        customAmount.toLongOrNull() ?: if (customAmount.isBlank()) amount else 0L
    val amountValid = effectiveAmount >= 1

    LaunchedEffect(session?.user?.id) {
        if (session != null) {
            plansLoading = true
            repository.subscriptionPlans().onSuccess { plans = it }
            plansLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("充值") },
                navigationIcon = { BackButton() },
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
            // 当前余额
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("当前余额") },
                        supportingContent = {
                            Text(
                                session?.user?.let {
                                    "已用 ${formatQuotaUsd(it.usedUsd)} · 分组 ${it.groupName}"
                                } ?: "未登录"
                            )
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Wallet01) },
                        trailingContent = {
                            Text(
                                text = session?.user?.let { formatQuotaUsd(it.quotaUsd) } ?: "--",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }

            // 支付渠道（两个区块共用）
            item {
                CardGroup(title = { Text("支付方式") }) {
                    formItem {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            PAY_CHANNELS.forEach { (type, label) ->
                                FilterChip(
                                    selected = payChannel == type,
                                    onClick = { payChannel = type },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }

            // 套餐订阅（真实在线支付，购买后解锁分组）
            item {
                CardGroup(title = { Text("套餐订阅") }) {
                    if (plansLoading) {
                        formItem {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在获取套餐…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else if (plans.isEmpty()) {
                        formItem {
                            Text(
                                text = "暂时没有可用套餐",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        plans.forEach { plan ->
                            item(
                                onClick = {
                                    errorMessage = null
                                    infoMessage = null
                                    paying = true
                                    scope.launch {
                                        repository.purchasePlan(plan.id, payChannel)
                                            .onSuccess { url ->
                                                infoMessage = "正在打开收银台…"
                                                context.openUrl(url)
                                            }
                                            .onFailure { errorMessage = it.message ?: "下单失败" }
                                        paying = false
                                    }
                                },
                                headlineContent = { Text(plan.title.ifBlank { "套餐" }) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            if (plan.subtitle.isNotBlank()) append(plan.subtitle).append(" · ")
                                            append(plan.durationLabel)
                                            append(" · ")
                                            append(plan.groupLabel)
                                        }
                                    )
                                },
                                trailingContent = {
                                    CardGroupValue(
                                        text = "$" + trimAmount(plan.priceAmount),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // 钱包充值
            item {
                CardGroup(title = { Text("钱包充值") }) {
                    formItem(headlineContent = { Text("金额（整数）") }) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            PRESET_AMOUNTS.forEach { preset ->
                                FilterChip(
                                    selected = customAmount.isBlank() && amount == preset,
                                    onClick = { amount = preset; customAmount = "" },
                                    label = { Text("$$preset") },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = customAmount,
                            onValueChange = { input ->
                                // 只保留数字：小数金额会被服务端判为「参数错误」
                                customAmount = input.filter { it.isDigit() }.take(6)
                            },
                            label = { Text("自定义金额（整数）") },
                            isError = customAmount.isNotBlank() && !amountValid,
                            supportingText = {
                                Text(
                                    if (customAmount.isNotBlank() && !amountValid) "请输入不小于 1 的整数"
                                    else "本渠道仅支持整元充值"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = MaterialTheme.shapes.large,
                        )
                    }
                    formItem {
                        Button(
                            onClick = {
                                errorMessage = null
                                infoMessage = null
                                paying = true
                                scope.launch {
                                    repository.createPayOrder(effectiveAmount, payChannel)
                                        .onSuccess { url ->
                                            infoMessage = "正在打开收银台…"
                                            context.openUrl(url)
                                        }
                                        .onFailure { errorMessage = it.message ?: "下单失败" }
                                    paying = false
                                }
                            },
                            enabled = !busy && !paying && amountValid && session != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            if (paying) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("充值 $effectiveAmount")
                            }
                        }
                    }
                }
            }

            // 兑换码
            item {
                CardGroup(title = { Text("兑换码") }) {
                    formItem {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it; errorMessage = null },
                                label = { Text("输入兑换码") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.large,
                            )
                            OutlinedButton(
                                onClick = {
                                    errorMessage = null
                                    infoMessage = null
                                    scope.launch {
                                        repository.redeemCode(code)
                                            .onSuccess { msg ->
                                                infoMessage = msg.ifBlank { "兑换成功" }
                                                code = ""
                                                repository.refreshProfile()
                                            }
                                            .onFailure { errorMessage = it.message ?: "兑换失败" }
                                    }
                                },
                                enabled = !busy && code.isNotBlank() && session != null,
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("兑换")
                                }
                            }
                        }
                    }
                }
            }

            infoMessage?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            errorMessage?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            item {
                Text(
                    text = "支付在浏览器完成，成功后回到 App 点刷新即可看到余额变化",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

private fun trimAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", value)