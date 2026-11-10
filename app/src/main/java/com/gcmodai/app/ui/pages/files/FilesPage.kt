package com.gcmodai.app.ui.pages.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
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
import com.gcmodai.app.data.files.FilesRepository
import com.gcmodai.app.data.files.HostedFile
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Globe
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Refresh03
import kotlinx.coroutines.delay

/**
 * 代码产物托管页。
 *
 * 取代原来的「Web 服务器」页：那套是手机本地 Ktor 服务器，只能同局域网访问；
 * 现在改为公网托管，链接可分享给任何人，移动数据下也能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesPage(vm: FilesVM) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    var pendingDelete by remember { mutableStateOf<HostedFile?>(null) }
    var showUploadHint by remember { mutableStateOf(false) }

    // 选文件：允许任意类型，因为用户可能传 zip 或图片
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "upload.bin"
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        // 用文件名（去扩展名）作为部署目录，保证同一网页的 css/js 能相对引用
        val entry = name.substringBeforeLast('.').ifBlank { "site" }
        vm.upload(name = name, bytes = bytes, entry = entry)
    }

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

    // 上传成功后自动收起提示，避免一直占位
    LaunchedEffect(state.lastUploaded) {
        if (state.lastUploaded != null) {
            delay(60_000)
            vm.clearLastUploaded()
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("代码产物托管") },
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
            // ---- 配额卡片 ----
            item {
                QuotaCard(state.quota, state.loading)
            }

            // ---- 错误提示（含具体原因与重试）----
            if (state.error != null) {
                item {
                    ErrorCard(
                        message = state.error ?: "",
                        detail = state.errorDetail,
                        onRetry = { vm.refresh() },
                    )
                }
            }

            // ---- 上传入口 ----
            item {
                CardGroup(title = { Text("部署网站") }) {
                    item(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        headlineContent = { Text("选择文件上传") },
                        supportingContent = {
                            Text(
                                if (state.uploading) "正在上传…"
                                else "选 index.html 即可部署整站，同目录资源自动关联"
                            )
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                        trailingContent = {
                            if (state.uploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    item(
                        onClick = { showUploadHint = true },
                        headlineContent = { Text("部署说明") },
                        supportingContent = { Text("怎么让网页能被别人打开") },
                        leadingContent = { CardGroupIcon(HugeIcons.Globe) },
                    )
                }
            }

            // ---- 刚上传的链接（最显眼的位置）----
            state.lastUploaded?.let { f ->
                item {
                    LastUploadCard(
                        file = f,
                        onCopy = { copyToClipboard(context, f.fullUrl, "链接已复制") },
                        onOpen = { openUrl(context, f.fullUrl) },
                        onDismiss = { vm.clearLastUploaded() },
                    )
                }
            }

            // ---- 文件列表 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已托管文件（${state.files.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.files.isNotEmpty()) {
                        Text(
                            "24 小时后自动删除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.files.isEmpty()) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("还没有托管文件") },
                            supportingContent = {
                                Text("上传一个 html 文件，就能得到一个公网可访问的网址")
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                        )
                    }
                }
            } else {
                items(state.files, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onCopy = { copyToClipboard(context, file.fullUrl, "链接已复制") },
                        onOpen = { openUrl(context, file.fullUrl) },
                        onDelete = { pendingDelete = file },
                    )
                }
            }

            // ---- 规则说明 ----
            item {
                CardGroup(title = { Text("托管规则") }) {
                    item(
                        headlineContent = { Text("每人 100 MB") },
                        supportingContent = { Text("所有文件合计上限，超出需先删除旧文件") },
                        leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                    )
                    item(
                        headlineContent = { Text("保留 24 小时") },
                        supportingContent = { Text("到期自动删除，重要内容请自行备份") },
                        leadingContent = { CardGroupIcon(HugeIcons.Refresh03) },
                    )
                    item(
                        headlineContent = { Text("公网可访问") },
                        supportingContent = { Text("任何网络下都能打开，链接可直接分享") },
                        leadingContent = { CardGroupIcon(HugeIcons.Globe) },
                    )
                }
            }
        }
    }

    // ---- 删除确认 ----
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件") },
            text = { Text("确定删除「${file.name}」？删除后链接立即失效。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(file.id)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // ---- 部署说明 ----
    if (showUploadHint) {
        AlertDialog(
            onDismissRequest = { showUploadHint = false },
            title = { Text("怎么部署一个网站") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "1. 让 AI 帮你写好网页，通常会得到 index.html",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "2. 点「选择文件上传」，选那个 index.html",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "3. 上传完会得到一个公网链接，复制发给别人就能打开",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "4. 如果网页还要 css / js / 图片，按同样文件名分别上传即可，" +
                            "它们会和 index.html 放在同一个目录下，相对路径自动生效",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "提示：文件保留 24 小时，到期自动删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showUploadHint = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun QuotaCard(quota: com.gcmodai.app.data.files.FilesQuota?, loading: Boolean) {
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
                "存储配额",
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
                        "保留 ${quota.retentionHours} 小时",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun LastUploadCard(
    file: HostedFile,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
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
                    "上传成功，链接已可用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(
                file.fullUrl,
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
                    Icon(HugeIcons.Link01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("打开", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: HostedFile,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    CardGroup {
        item(
            onClick = onOpen,
            headlineContent = {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text("${file.sizeLabel} · ${file.remainingLabel(nowSeconds())}")
            },
            leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            HugeIcons.Copy01,
                            contentDescription = "复制链接",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            HugeIcons.Delete02,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
    }
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

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

/** 从 content Uri 拿显示名（文件名）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}

@Composable
private fun ErrorCard(message: String, detail: String?, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "获取托管信息失败",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!detail.isNullOrBlank()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "收起技术详情" else "查看技术详情",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (expanded) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}
