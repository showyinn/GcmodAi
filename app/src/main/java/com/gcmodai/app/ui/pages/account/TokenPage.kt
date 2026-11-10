package com.gcmodai.app.ui.pages.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.hugeicons.stroke.Zap
import org.koin.compose.koinInject
import com.gcmodai.app.Screen
import com.gcmodai.app.data.user.ApiToken
import com.gcmodai.app.data.user.GroupInfo
import com.gcmodai.app.data.user.OfficialProviderManager
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.data.user.formatQuotaUsd
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.plus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 密钥管理页：列表 / 创建 / 选中（选中后模型调用走官网真实扣费）/ 删除。
 * 分组在创建时选择，来源于官网 /api/user/self/groups。
 */
@Composable
fun TokenPage() {
    val navController = LocalNavController.current
    val repository: UserRepository = koinInject()
    val officialProviderManager: OfficialProviderManager = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val session by repository.sessionFlow.collectAsStateWithLifecycle()
    val tokens by repository.tokens.collectAsStateWithLifecycle()
    val groups by repository.groups.collectAsStateWithLifecycle()
    val busy by repository.busy.collectAsStateWithLifecycle()

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ApiToken?>(null) }
    var groupTarget by remember { mutableStateOf<ApiToken?>(null) }

    // 未登录引导去登录；进入时同步密钥/分组/模型
    LaunchedEffect(session?.user?.id) {
        if (session == null) {
            navController.navigate(Screen.Login)
        } else {
            repository.refreshTokens()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API 密钥") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(
                        onClick = {
                            errorMessage = null
                            scope.launch { repository.refreshTokens() }
                        },
                        enabled = !busy && session != null,
                    ) {
                        Text("刷新")
                    }
                    TextButton(onClick = { showCreateDialog = true }, enabled = session != null) {
                        Text("新建")
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (session == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "选中某个密钥后，模型调用将使用它通过官网网关（gcmod.online）计费，额度实时扣减。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (tokens.isEmpty() && !busy) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Key01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "还没有 API 密钥",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "创建一个密钥即可在 App 内调用官网模型",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { showCreateDialog = true }) {
                            Text("创建密钥")
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("我的密钥") }) {
                    tokens.forEach { token ->
                        val selected = session?.selectedTokenId == token.id
                        item(
                            onClick = {
                                errorMessage = null
                                scope.launch {
                                    repository.selectToken(token.id)
                                        .onSuccess {
                                            // 选中即生效：官方供应商换用该密钥，模型调用立即真实计费
                                            officialProviderManager.applySelectedToken()
                                        }
                                        .onFailure { errorMessage = it.message ?: "选中失败" }
                                }
                            },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Key01,
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(token.name, fontWeight = FontWeight.Medium)
                                    if (selected) {
                                        CardGroupValue(text = "使用中")
                                    }
                                }
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(token.statusLabel)
                                        append(" · ")
                                        append(token.group.ifBlank { "跟随用户分组" })
                                        if (token.unlimitedQuota) {
                                            append(" · 无限额度")
                                        } else {
                                            append(" · 剩余 ${formatQuotaUsd(token.remainQuota / 500000.0)}")
                                        }
                                        if (token.expiredTime > 0) {
                                            append(" · 到期 " + formatTime(token.expiredTime))
                                        }
                                    }
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { groupTarget = token }) {
                                        Text("分组")
                                    }
                                    TextButton(onClick = { deleteTarget = token }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (groups.isNotEmpty()) {
                item {
                    CardGroup(title = { Text("可选分组（倍率）") }) {
                        groups.forEach { (name, info) ->
                            item(
                                headlineContent = { Text(name.ifBlank { "跟随用户分组" }) },
                                leadingContent = { CardGroupIcon(HugeIcons.Tag01) },
                                supportingContent = { Text(info.desc.ifBlank { "倍率 ${info.ratio}" }) },
                                trailingContent = { CardGroupValue(text = "x${info.ratio}") },
                            )
                        }
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
        }
    }

    if (showCreateDialog) {
        CreateTokenDialog(
            groups = groups,
            busy = busy,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, group, unlimited, quota ->
                scope.launch {
                    repository.createToken(name, group, unlimited, quota)
                        .onSuccess {
                            showCreateDialog = false
                            repository.refreshTokens()
                        }
                        .onFailure { errorMessage = it.message ?: "创建失败" }
                }
            },
        )
    }

    groupTarget?.let { token ->
        ChangeGroupDialog(
            token = token,
            groups = groups,
            busy = busy,
            onDismiss = { groupTarget = null },
            onConfirm = { newGroup ->
                scope.launch {
                    repository.changeTokenGroup(token, newGroup)
                        .onSuccess {
                            // 改的正好是当前使用的密钥，同步到官方供应商
                            if (repository.sessionFlow.value?.selectedTokenId == token.id) {
                                officialProviderManager.applySelectedToken()
                            }
                            groupTarget = null
                        }
                        .onFailure { errorMessage = it.message ?: "更换分组失败" }
                }
            },
        )
    }

    deleteTarget?.let { token ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除密钥") },
            text = { Text("确定删除「${token.name}」吗？使用它的应用将无法继续调用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = token
                        deleteTarget = null
                        scope.launch {
                            repository.deleteToken(t.id)
                                .onFailure { errorMessage = it.message ?: "删除失败" }
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CreateTokenDialog(
    groups: Map<String, GroupInfo>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, group: String, unlimited: Boolean, remainQuota: Long) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("gcmod-app") }
    var group by rememberSaveable { mutableStateOf("") }
    var unlimited by rememberSaveable { mutableStateOf(true) }
    var quotaText by rememberSaveable { mutableStateOf("1") }
    var quotaError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建 API 密钥") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                )

                Text("分组", style = MaterialTheme.typography.labelLarge)
                // 分组可横向滚动，全部展示（含倍率），不再截断
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = group.isBlank(),
                        onClick = { group = "" },
                        label = { Text("跟随账号") },
                    )
                    groups.entries.filter { it.key.isNotBlank() }.forEach { (g, info) ->
                        FilterChip(
                            selected = group == g,
                            onClick = { group = g },
                            label = { Text("$g ×${info.ratio}") },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = unlimited, onCheckedChange = { unlimited = it })
                    Text("无限额度", style = MaterialTheme.typography.bodyMedium)
                }

                if (!unlimited) {
                    OutlinedTextField(
                        value = quotaText,
                        onValueChange = { quotaText = it; quotaError = null },
                        label = { Text("额度（美元）") },
                        isError = quotaError != null,
                        supportingText = quotaError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    )
                }

                Text(
                    text = "分组决定计费倍率与可调模型，创建后也能随时更换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val quotaUsd = quotaText.toDoubleOrNull()
                    if (!unlimited && (quotaUsd == null || quotaUsd <= 0)) {
                        quotaError = "请输入有效金额"
                        return@Button
                    }
                    onCreate(name, group, unlimited, ((quotaUsd ?: 0.0) * 500000).toLong())
                },
                enabled = !busy && name.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("创建")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatTime(unixSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(unixSeconds * 1000L))

/** 更换密钥分组：列出账号可选的全部分组（含倍率） */
@Composable
private fun ChangeGroupDialog(
    token: ApiToken,
    groups: Map<String, GroupInfo>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by rememberSaveable(token.id) { mutableStateOf(token.group) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更换分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "「${token.name}」当前分组：${token.group.ifBlank { "跟随账号" }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val candidates = buildList {
                    add("" to GroupInfo(desc = "跟随账号分组", ratio = 1.0))
                    groups.entries.filter { it.key.isNotBlank() }.forEach { add(it.key to it.value) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { (name, info) ->
                        val label = if (name.isBlank()) "跟随账号" else name
                        Surface(
                            onClick = { selected = name },
                            color = if (selected == name) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (selected == name) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text("×${info.ratio}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Text(
                    text = "国模系列倍率低，适合国内模型；改动立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
