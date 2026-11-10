package com.gcmodai.app.ui.pages.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gcmodai.app.data.workspace.WorkspaceEntry
import com.gcmodai.app.data.workspace.WorkspaceQuota
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.SourceCode
import me.rerere.hugeicons.stroke.Upload04

/**
 * 服务器工作区页面。
 *
 * 每人 100MB 隔离空间：AI 写代码直接落到这里，用户能看到文件、编辑、
 * 并把网页部署成公网可访问的站点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyWorkspacePage(vm: MyWorkspaceVM) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    var pendingDelete by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var showNewFile by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    // ---------- 编辑器模式 ----------
    if (state.inEditor) {
        val file = state.editing!!
        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(file.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.closeEditor() }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "关闭编辑")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.save() }, enabled = !state.saving) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                )
                            } else {
                                Icon(HugeIcons.Upload04, contentDescription = "保存")
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (file.warnings.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            file.warnings.forEach {
                                Text(
                                    "⚠ ${it.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = state.editDraft,
                    onValueChange = { vm.updateDraft(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("文件内容") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { vm.save() },
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.saving) "保存中…" else "保存") }
                    OutlinedButton(
                        onClick = { vm.closeEditor() },
                        modifier = Modifier.weight(1f),
                    ) { Text("返回") }
                }
            }
        }
        return
    }

    // ---------- 浏览模式 ----------
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("我的工作区") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh03, contentDescription = "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { QuotaCard(state.quota, state.loading) }

            // 部署结果
            state.deployedUrl?.let { url ->
                item {
                    DeployCard(
                        url = url,
                        onCopy = { copyToClipboard(context, url, "链接已复制") },
                        onOpen = { openUrl(context, url) },
                        onClose = { vm.clearDeployed() },
                    )
                }
            }

            // 路径与操作
            item {
                CardGroup(title = { Text("操作") }) {
                    if (state.currentDir.isNotBlank()) {
                        item(
                            onClick = { vm.goUp() },
                            headlineContent = { Text("返回上级") },
                            supportingContent = { Text("当前：/${state.currentDir}") },
                            leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                        )
                    }
                    item(
                        onClick = { showNewFile = true },
                        headlineContent = { Text("新建文件") },
                        supportingContent = { Text("手写 html / css / js") },
                        leadingContent = { CardGroupIcon(HugeIcons.SourceCode) },
                    )
                    item(
                        onClick = { vm.deploy() },
                        headlineContent = { Text("部署成网站") },
                        supportingContent = {
                            Text(
                                if (state.hasIndexHtml) "当前目录有 index.html，可直接部署"
                                else "需要先有 index.html 才能部署"
                            )
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Globe) },
                        trailingContent = {
                            if (state.deploying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                )
                            } else {
                                CardGroupValue(text = "部署")
                            }
                        },
                    )
                }
            }

            // 文件列表
            item {
                Text(
                    "文件（${state.entries.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (state.entries.isEmpty()) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("工作区是空的") },
                            supportingContent = {
                                Text("让 AI 帮你写个网页，或点「新建文件」自己写")
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.SourceCode) },
                        )
                    }
                }
            } else {
                items(state.entries, key = { it.path }) { e ->
                    if (e.isDir) {
                        CardGroup {
                            item(
                                onClick = { vm.enterDir(e.path) },
                                headlineContent = { Text(e.name) },
                                supportingContent = { Text("目录") },
                                leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                                trailingContent = {
                                    Icon(
                                        HugeIcons.ArrowRight01,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    } else {
                        CardGroup {
                            item(
                                onClick = { vm.openFile(e.path) },
                                headlineContent = { Text(e.name) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(e.sizeLabel)
                                            if (e.deployable) append(" · 可作网站入口")
                                        }
                                    )
                                },
                                leadingContent = { CardGroupIcon(HugeIcons.SourceCode) },
                                trailingContent = {
                                    IconButton(onClick = { pendingDelete = e }) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("说明") }) {
                    item(
                        headlineContent = { Text("每人 100 MB") },
                        supportingContent = { Text("AI 写的代码直接存在这里，不需要下载") },
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                    )
                    item(
                        headlineContent = { Text("公网可访问") },
                        supportingContent = { Text("部署后任何网络都能打开，含移动数据") },
                        leadingContent = { CardGroupIcon(HugeIcons.Globe) },
                    )
                    item(
                        headlineContent = { Text("互相隔离") },
                        supportingContent = { Text("每个用户独立目录，无法访问他人文件") },
                        leadingContent = { CardGroupIcon(HugeIcons.SourceCode) },
                    )
                    item(
                        headlineContent = { Text("24 小时无操作自动清空") },
                        supportingContent = {
                            Text("只要还在读写或访问站点就会续期；闲置满 24 小时腾出空间")
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Refresh03) },
                    )
                    item(
                        headlineContent = { Text("危险代码会被拦截") },
                        supportingContent = { Text("webshell、挖矿、钓鱼表单等禁止上传") },
                        leadingContent = { CardGroupIcon(HugeIcons.Cancel01) },
                    )
                }
            }
        }
    }

    // 删除确认
    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件") },
            text = { Text("确定删除「${e.name}」？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(e.path)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 新建文件
    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("新建文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("文件名") },
                        placeholder = { Text("index.html") },
                        singleLine = true,
                    )
                    Text(
                        "常见入口：index.html、style.css、app.js",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = newFileName.trim()
                        if (n.isNotEmpty()) {
                            vm.createFile(n)
                            newFileName = ""
                            showNewFile = false
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFile = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun QuotaCard(quota: WorkspaceQuota?, loading: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "工作区空间",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (quota != null) quota.usedLabel else if (loading) "读取中…" else "未知",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (quota != null) {
                LinearProgressIndicator(
                    progress = { quota.usedFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        quota.freeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${quota.fileCount} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                // 闲置倒计时：让用户清楚知道还有多久会被清空
                if (quota.expiresAt > 0) {
                    val now = remember(quota.expiresAt) {
                        System.currentTimeMillis() / 1000
                    }
                    Text(
                        "闲置 ${quota.retentionHours} 小时自动清空 · ${quota.remainingLabel(now)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeployCard(
    url: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "网站已上线",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("复制链接", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(HugeIcons.Globe, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("打开", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, tip: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("链接", text))
        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}
