package com.gcmodai.app.ui.pages.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ReceiptText
import me.rerere.hugeicons.stroke.Gift
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.hugeicons.stroke.CustomerService01
import me.rerere.hugeicons.stroke.Mail01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.hugeicons.stroke.BinaryCode
import me.rerere.hugeicons.stroke.Trophy
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.Wallet01
import org.koin.compose.koinInject
import com.gcmodai.app.data.user.OfficialProviderManager
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.data.remote.RemoteConfigRepository
import com.gcmodai.app.data.remote.OfficialGroup
import com.gcmodai.app.data.remote.GroupJoinAction
import androidx.core.net.toUri
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.Screen
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.plus
import java.text.DecimalFormat

/**
 * 我的账号（用户中心）。
 *
 * 展示 gcmod.online 账号的额度、分组、邀请码等信息；数据每次进入都从服务端拉最新。
 */
@Composable
fun AccountPage() {
    val navController = LocalNavController.current
    val repository: UserRepository = koinInject()
    val officialProviderManager: OfficialProviderManager = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val session by repository.sessionFlow.collectAsStateWithLifecycle()
    val busy by repository.busy.collectAsStateWithLifecycle()
    // 远程配置：官方群、公告、功能开关全部来自管理后台
    val remoteConfigRepo: RemoteConfigRepository = koinInject()
    val remoteConfig by remoteConfigRepo.config.collectAsStateWithLifecycle()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val user = session?.user

    // 进入页面时刷新一次资料，保证额度是最新的；同时把选中的密钥同步进官方供应商（真实计费通道）
    LaunchedEffect(user?.id) {
        if (user != null) {
            repository.refreshProfile().onFailure {
                errorMessage = it.message ?: "刷新失败"
            }
            officialProviderManager.syncModelsAndApply()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的账号") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    if (user != null) {
                        TextButton(
                            onClick = {
                                errorMessage = null
                                scope.launch {
                                    repository.refreshProfile().onFailure {
                                        errorMessage = it.message ?: "刷新失败"
                                    }
                                }
                            },
                            enabled = !busy,
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("刷新")
                            }
                        }
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (user == null) {
            // 未登录
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.UserCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Text(
                    text = "还没有登录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "登录后可查看额度、分组与邀请码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { navController.navigate(Screen.Login) }) {
                        Text("登录")
                    }
                    TextButton(onClick = { navController.navigate(Screen.Register) }) {
                        Text("注册")
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 头像与身份
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(84.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = user.displayLabel.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Text(
                        text = user.displayLabel,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "@${user.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (user.isBanned) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                text = "该账号已被禁用，请联系管理员",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // 额度
            item {
                CardGroup(title = { Text("额度") }) {
                    formItem(
                        headlineContent = { Text("剩余额度") },
                        leadingContent = { CardGroupIcon(HugeIcons.MoneyBag02) },
                        trailingContent = {
                            CardGroupValue(text = formatUsd(user.quotaUsd))
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(
                                progress = { user.remainingRatio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "已用 ${formatUsd(user.usedUsd)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "累计 ${formatUsd(user.totalUsd)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item(
                        headlineContent = { Text("请求次数") },
                        supportingContent = { Text("历史累计调用次数") },
                        trailingContent = { CardGroupValue(text = user.requestCount.toString()) },
                    )

                    // 每日签到：状态完全以服务端为准，网站签过这里就签不了
                    // 入口是否显示由管理后台 checkin_enabled_hint 控制
                    if (remoteConfig.checkinEnabled) {
                        item(
                            headlineContent = { Text("每日签到") },
                            supportingContent = { Text("每天领一次随机额度，与网站同步") },
                            leadingContent = { CardGroupIcon(HugeIcons.Gift) },
                            trailingContent = {
                                Icon(
                                    imageVector = HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = { navController.navigate(Screen.Checkin) },
                        )
                    }

                    // 账单明细：每次调用的真实扣费都能查到
                    // 入口是否显示由管理后台 billing_enabled 控制
                    if (remoteConfig.billingEnabled) {
                        item(
                            headlineContent = { Text("账单明细") },
                            supportingContent = { Text("每笔扣费的模型、token 与金额") },
                            leadingContent = { CardGroupIcon(HugeIcons.ReceiptText) },
                            trailingContent = {
                                Icon(
                                    imageVector = HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = { navController.navigate(Screen.Billing) },
                        )
                    }

                    // 排行榜：富豪榜（累计充值）+ 词元榜（GcmodAi 供应商 tokens）
                    // 只要在站内用过 GcmodAi 供应商的量就会参与排名，各榜展示前 10 名
                    item(
                        headlineContent = { Text("排行榜") },
                        supportingContent = { Text("富豪榜与词元榜，各榜前 10 名") },
                        leadingContent = { CardGroupIcon(HugeIcons.Trophy) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { navController.navigate(Screen.Leaderboard) },
                    )

                    // 二进制逆向工作台：SO/二进制/DEX/APK 解密、解析、反汇编，并交给 AI
                    item(
                        headlineContent = { Text("二进制 / SO 分析") },
                        supportingContent = { Text("解密、反编译 SO / 二进制 / DEX / APK，可直接交给 AI") },
                        leadingContent = { CardGroupIcon(HugeIcons.BinaryCode) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { navController.navigate(Screen.BinaryTool) },
                    )
                }
            }

            // 账户信息
            item {
                CardGroup(title = { Text("账户信息") }) {
                    item(
                        headlineContent = { Text("用户名") },
                        trailingContent = { CardGroupValue(text = user.username) },
                    )
                    item(
                        headlineContent = { Text("邮箱") },
                        supportingContent = {
                            Text(user.email.ifBlank { "未绑定" })
                        },
                    )
                    item(
                        headlineContent = { Text("分组") },
                        supportingContent = { Text("决定可用模型与费率") },
                        leadingContent = { CardGroupIcon(HugeIcons.Tag01) },
                        trailingContent = {
                            CardGroupValue(
                                text = when (user.groupName) {
                                    "vip" -> "VIP"
                                    "default" -> "默认"
                                    else -> user.groupName
                                }
                            )
                        },
                    )
                    if (user.affCode.isNotBlank()) {
                        item(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("aff_code", user.affCode))
                                Toast.makeText(context, "邀请码已复制", Toast.LENGTH_SHORT).show()
                            },
                            headlineContent = { Text("我的邀请码") },
                            supportingContent = { Text("已邀请 ${user.affCount} 人，点击复制") },
                            leadingContent = { CardGroupIcon(HugeIcons.Link01) },
                            trailingContent = { CardGroupValue(text = user.affCode) },
                        )
                    }
                    if (user.isAdmin) {
                        item(
                            headlineContent = { Text("账号角色") },
                            trailingContent = {
                                CardGroupValue(text = if (user.role >= 100L) "超级管理员" else "管理员")
                            },
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

            // 操作
            item {
                CardGroup {
                    item(
                        onClick = { navController.navigate(Screen.Tokens) },
                        headlineContent = { Text("API 密钥") },
                        supportingContent = { Text("创建/选择密钥与分组，选中后模型调用真实计费") },
                        leadingContent = { CardGroupIcon(HugeIcons.Key01) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Topup) },
                        headlineContent = { Text("充值") },
                        supportingContent = { Text("在线支付或兑换码充值") },
                        leadingContent = { CardGroupIcon(HugeIcons.Wallet01) },
                    )
                    item(
                        onClick = {
                            errorMessage = null
                            scope.launch {
                                repository.refreshProfile().onFailure {
                                    errorMessage = it.message ?: "刷新失败"
                                }
                            }
                        },
                        headlineContent = { Text("刷新资料") },
                        supportingContent = { Text("重新从服务端获取最新额度") },
                        leadingContent = { CardGroupIcon(HugeIcons.Refresh03) },
                    )
                    item(
                        onClick = { showLogoutDialog = true },
                        headlineContent = {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Logout03,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                    )
                }
            }

            // 官方群与支持（全部由管理后台配置）
            // 只要群或任一联系方式有值就显示这个分组
            if (
                OfficialGroup.isAvailable(
                    url = remoteConfig.officialGroupUrl,
                    qr = remoteConfig.officialGroupQr,
                    number = remoteConfig.officialGroupNumber,
                ) || remoteConfig.supportContact.isNotBlank()
                || remoteConfig.supportEmail.isNotBlank()
            ) {
                item {
                    CardGroup(title = { Text("官方群与支持") }) {
                        if (
                            OfficialGroup.isAvailable(
                                url = remoteConfig.officialGroupUrl,
                                qr = remoteConfig.officialGroupQr,
                                number = remoteConfig.officialGroupNumber,
                            )
                        ) {
                        item(
                            onClick = {
                                when (
                                    val action = OfficialGroup.resolve(
                                        url = remoteConfig.officialGroupUrl,
                                        qr = remoteConfig.officialGroupQr,
                                        number = remoteConfig.officialGroupNumber,
                                    )
                                ) {
                                    is GroupJoinAction.OpenUrl -> {
                                        val opened = runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, action.url.toUri())
                                            )
                                        }.isSuccess
                                        if (!opened) {
                                            // 没有浏览器/QQ 能接管时不能让用户卡死：
                                            // 退回复制群号，至少还有个手工路径
                                            copyGroupNumber(
                                                context,
                                                remoteConfig.officialGroupNumber,
                                                onFail = { errorMessage = "无法打开链接，且没有可用群号" },
                                            )
                                        }
                                    }

                                    is GroupJoinAction.CopyNumber -> {
                                        copyGroupNumber(
                                            context,
                                            action.number,
                                            onFail = { errorMessage = "复制失败，请手动搜索群号" },
                                        )
                                    }

                                    GroupJoinAction.Nothing -> Unit
                                }
                            },
                            headlineContent = { Text(remoteConfig.officialGroupName) },
                            supportingContent = {
                                Text(
                                    OfficialGroup.subtitle(
                                        desc = remoteConfig.officialGroupDesc,
                                        url = remoteConfig.officialGroupUrl,
                                        qr = remoteConfig.officialGroupQr,
                                        number = remoteConfig.officialGroupNumber,
                                    )
                                )
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.UserGroup) },
                            trailingContent = {
                                Icon(
                                    imageVector = HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        }
                        // 客服联系方式：后台配置了才显示，点击复制
                        if (remoteConfig.supportContact.isNotBlank()) {
                            item(
                                onClick = {
                                    copyText(
                                        context = context,
                                        text = remoteConfig.supportContact,
                                        clipboardLabel = "客服联系方式",
                                    )
                                },
                                headlineContent = { Text("联系客服") },
                                supportingContent = { Text(remoteConfig.supportContact) },
                                leadingContent = { CardGroupIcon(HugeIcons.CustomerService01) },
                            )
                        }
                        if (remoteConfig.supportEmail.isNotBlank()) {
                            item(
                                onClick = {
                                    copyText(
                                        context = context,
                                        text = remoteConfig.supportEmail,
                                        clipboardLabel = "客服邮箱",
                                    )
                                },
                                headlineContent = { Text("客服邮箱") },
                                supportingContent = { Text(remoteConfig.supportEmail) },
                                leadingContent = { CardGroupIcon(HugeIcons.Mail01) },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "账号数据来自 gcmod.online",
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

    // 公告已移到 GlobalAnnouncementHost（挂在导航容器外层）。
    // 原因：公告是全局信息，不该只在账户页渲染——那样用户进 app 落在聊天页就看不到，
    // 而且弹窗会一直挂在账户页上遮挡后续页面。

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新输入账号密码，本地聊天记录不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        scope.launch {
                            repository.logout()
                            navController.popBackStack()
                        }
                    }
                ) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            },
        )
    }
}

private fun formatUsd(value: Double): String {
    val fmt = DecimalFormat("#,##0.00")
    return "$${fmt.format(value)}"
}

/**
 * 把群号复制到剪贴板。
 *
 * 只在「没有可用链接」或「链接打不开」时兜底，让用户还有手工输入的路径。
 */
private fun copyGroupNumber(
    context: Context,
    number: String,
    onFail: () -> Unit,
) {
    val num = number.trim()
    if (num.isEmpty()) {
        onFail()
        return
    }
    val copied = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("官方群号", num))
    }.isSuccess
    if (copied) {
        Toast.makeText(context, "群号已复制：$num", Toast.LENGTH_SHORT).show()
    } else {
        onFail()
    }
}

/**
 * 通用文本复制（客服联系方式、邮箱等）。
 *
 * 失败静默：这类复制失败不影响任何主流程，弹错反而打扰用户。
 */
private fun copyText(context: Context, text: String, clipboardLabel: String) {
    val value = text.trim()
    if (value.isEmpty()) return
    val copied = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(clipboardLabel, value))
    }.isSuccess
    if (copied) {
        Toast.makeText(context, "已复制：$value", Toast.LENGTH_SHORT).show()
    }
}
