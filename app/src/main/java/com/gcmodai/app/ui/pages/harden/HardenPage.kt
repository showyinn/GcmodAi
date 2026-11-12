package com.gcmodai.app.ui.pages.harden

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcmodai.app.Screen
import com.gcmodai.app.data.files.FileFolders
import com.gcmodai.app.data.files.FilesManager
import com.gcmodai.app.data.harden.HardenClient
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.design.Md3Panel
import com.gcmodai.app.ui.theme.CustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Shield01
import org.koin.compose.koinInject
import java.io.File

/**
 * APK 加固页（服务器端 apktool + apksigner 流水线）。
 *
 * 性能上做了三件事，避免列表卡顿：
 *   1. 类列表分页：默认渲染 60 行，需要时再「显示更多」，不一次铺 3000 行；
 *   2. 稳定 key：items(key = descriptor)，勾选只影响对应行；
 *   3. 日志截断：只保留最近 80 行，轮询不会让状态无限增长。
 */
@Composable
fun HardenPage() {
    val context = LocalContext.current
    val client: HardenClient = koinInject()
    val filesManager: FilesManager = koinInject()
    val navController = com.gcmodai.app.ui.context.LocalNavController.current
    val scope = rememberCoroutineScope()

    var apkName by remember { mutableStateOf<String?>(null) }
    var apkBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ksName by remember { mutableStateOf<String?>(null) }
    var ksBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ksPass by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var stripDebug by remember { mutableStateOf(true) }
    var stripSo by remember { mutableStateOf(true) }

    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var stateText by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultBytes by remember { mutableStateOf<ByteArray?>(null) }
    var savedInfo by remember { mutableStateOf<String?>(null) }

    var classes by remember { mutableStateOf<List<HardenClient.DexClass>>(emptyList()) }
    var showAllClasses by remember { mutableStateOf(false) }
    var selectedObf by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDex by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 只渲染前 60 行；勾选变化不会重建整个列表
    val visibleClasses by remember(classes, showAllClasses) {
        derivedStateOf { if (showAllClasses) classes else classes.take(60) }
    }
    val pickCount by remember { derivedStateOf { selectedObf.size to selectedDex.size } }

    fun readUri(uri: Uri, maxBytes: Long): Pair<String, ByteArray>? {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > maxBytes) return null
        return name to bytes
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readUri(uri, 240L * 1024 * 1024) } }
                .getOrNull()?.let { (name, data) ->
                    apkName = name
                    apkBytes = data
                    classes = emptyList()
                    selectedObf = emptySet()
                    selectedDex = emptySet()
                    resultBytes = null
                    savedInfo = null
                    stateText = "已选择 ${data.size / 1024 / 1024} MB"
                } ?: run { stateText = "读取失败或文件超过 240MB" }
        }
    }

    val ksPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readUri(uri, 2L * 1024 * 1024) } }
                .getOrNull()?.let { (name, data) -> ksName = name; ksBytes = data }
        }
    }

    // 轮询：只在有 jobId 时运行；日志只保留尾部
    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (isActive) {
            client.status(id).onSuccess { s ->
                stateText = when (s.state) {
                    "queued" -> "排队中…"
                    "running" -> "服务器处理中，已用 ${s.elapsed}s"
                    "done" -> "加固完成"
                    "failed" -> "加固失败，见下方日志"
                    else -> s.state
                }
                logs = s.log.takeLast(80)
                if (s.state == "done") {
                    client.download(id).onSuccess { bytes ->
                        resultBytes = bytes
                        // 落地到 App 存储：存储管理 → 加固包，用户随时能再找回来
                        runCatching {
                            filesManager.saveManagedFromBytes(
                                folder = FileFolders.HARDEN,
                                bytes = bytes,
                                displayName = "GcmodAi-" + (apkName ?: "app").substringBeforeLast(".") + "-hardened.apk",
                                mimeType = "application/vnd.android.package-archive",
                            )
                        }.onSuccess { savedInfo = "已保存到 存储管理 → 加固包" }
                            .onFailure { savedInfo = "本地保存失败：${it.message}" }
                        stateText = "加固完成：${bytes.size / 1024} KB（服务器已删除）"
                    }.onFailure { stateText = "下载失败：${it.message}" }
                    busy = false
                    return@LaunchedEffect
                }
                if (s.state == "failed") {
                    busy = false
                    return@LaunchedEffect
                }
            }.onFailure { stateText = "查询失败：${it.message}" }
            delay(3000)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APK 加固") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------------- 顶部渐变区 ----------------
            item("hero") {
                HeroCard(
                    apkName = apkName,
                    sizeText = apkBytes?.let { "${it.size / 1024 / 1024} MB" },
                    running = busy || scanning,
                )
            }

            // ---------------- 选择与选项 ----------------
            item("pick") {
                Md3Panel(modifier = Modifier.fillMaxWidth(), radius = 22.dp, contentPadding = PaddingValues(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { apkPicker.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(HugeIcons.Shield01, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("选择 APK", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { ksPicker.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(if (ksName == null) "签名证书（可选）" else "已选证书", maxLines = 1)
                            }
                        }
                        ksName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        OptionRow(
                            title = "剥离调试信息",
                            desc = "去掉 smali 的 .line/.source，反编译看不到行号与局部变量",
                            checked = stripDebug,
                            onChange = { stripDebug = it },
                        )
                        OptionRow(
                            title = "SO 加固",
                            desc = "剥离 .symtab/.strtab/.comment，静态分析看不到符号",
                            checked = stripSo,
                            onChange = { stripSo = it },
                        )
                        Text(
                            "零留存：源包反编译后立即删除，结果只存内存，下载一次即清除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ---------------- 签名参数 ----------------
            if (ksBytes != null) {
                item("ks") {
                    Md3Panel(modifier = Modifier.fillMaxWidth(), radius = 22.dp, contentPadding = PaddingValues(16.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("签名证书参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = ksPass, onValueChange = { ksPass = it },
                                    label = { Text("store 密码") }, singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = keyAlias, onValueChange = { keyAlias = it },
                                    label = { Text("别名") }, singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedTextField(
                                value = keyPass, onValueChange = { keyPass = it },
                                label = { Text("key 密码（通常同上）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---------------- 类选择 ----------------
            item("scan") {
                Md3Panel(modifier = Modifier.fillMaxWidth(), radius = 22.dp, contentPadding = PaddingValues(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "选择要处理的类",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (classes.isNotEmpty()) {
                                Text(
                                    "混淆 ${pickCount.first} · 抽取 ${pickCount.second}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            "勾「混淆」= 重命名该类；勾「抽取」= dex2c 抽到 native（产物 libGcmodAi.so）。" +
                                "含 \$ 的生成类与清单组件会被禁用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            enabled = !busy && !scanning && apkBytes != null,
                            shape = RoundedCornerShape(14.dp),
                            onClick = {
                                val data = apkBytes ?: return@OutlinedButton
                                scanning = true
                                stateText = "扫描类列表中…"
                                scope.launch {
                                    client.scan(data)
                                        .onSuccess { list ->
                                            classes = list
                                            showAllClasses = false
                                            selectedObf = emptySet()
                                            selectedDex = emptySet()
                                            stateText = "共 ${list.size} 个类可勾选"
                                        }
                                        .onFailure { stateText = "扫描失败：${it.message}" }
                                    scanning = false
                                }
                            },
                        ) {
                            if (scanning) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (scanning) "扫描中…" else "扫描类", maxLines = 1)
                        }
                    }
                }
            }

            items(visibleClasses, key = { it.descriptor }) { c ->
                ClassRow(
                    item = c,
                    obfChecked = c.descriptor in selectedObf,
                    dexChecked = c.descriptor in selectedDex,
                    enabled = !busy,
                    onToggleObf = { on ->
                        selectedObf = if (on) selectedObf + c.descriptor else selectedObf - c.descriptor
                    },
                    onToggleDex = { on ->
                        selectedDex = if (on) selectedDex + c.descriptor else selectedDex - c.descriptor
                    },
                )
            }

            if (classes.isNotEmpty() && !showAllClasses && classes.size > visibleClasses.size) {
                item("more") {
                    TextButton(onClick = { showAllClasses = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("显示更多（共 ${classes.size} 个）")
                    }
                }
            }

            // ---------------- 执行与结果 ----------------
            item("run") {
                Md3Panel(modifier = Modifier.fillMaxWidth(), radius = 22.dp, contentPadding = PaddingValues(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !busy && apkBytes != null,
                                shape = RoundedCornerShape(14.dp),
                                onClick = {
                                    val data = apkBytes ?: return@Button
                                    busy = true
                                    logs = emptyList()
                                    resultBytes = null
                                    savedInfo = null
                                    stateText = "上传中…"
                                    scope.launch {
                                        val opts = HardenClient.Options(
                                            stripSmaliDebug = stripDebug,
                                            stripSo = stripSo,
                                            keystore = ksBytes,
                                            keystorePass = ksPass,
                                            keyAlias = keyAlias,
                                            keyPass = keyPass,
                                            obfuscateClasses = selectedObf.toList(),
                                            dex2cClasses = selectedDex.toList(),
                                        )
                                        client.submit(data, opts)
                                            .onSuccess { id -> jobId = id; stateText = "已提交，等待服务器处理…" }
                                            .onFailure { e -> busy = false; stateText = "提交失败：${e.message}" }
                                    }
                                },
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (busy) "处理中…" else "开始加固", maxLines = 1)
                            }
                            if (resultBytes != null) {
                                Button(
                                    shape = RoundedCornerShape(14.dp),
                                    onClick = {
                                        val bytes = resultBytes ?: return@Button
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                val dir = File(context.cacheDir, "harden-out").apply { mkdirs() }
                                                val base = (apkName ?: "app.apk").substringBeforeLast(".")
                                                File(dir, "$base-hardened.apk").writeBytes(bytes)
                                            }
                                            savedInfo = "已保存到 存储管理 → 加固包（本地缓存也已写入）"
                                        }
                                    },
                                ) { Text("下载加固包", maxLines = 1) }
                            }
                        }

                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                            Text(
                                "服务器加固中，通常 2–4 分钟（大包更久）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (stateText.isNotBlank()) {
                            Text(
                                stateText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        savedInfo?.let {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { navController.navigate(Screen.SettingFiles) }) {
                                        Text("打开", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (logs.isNotEmpty()) {
                item("logtitle") {
                    Text(
                        "服务器日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item("log") {
                    Md3Panel(
                        modifier = Modifier.fillMaxWidth(),
                        radius = 16.dp,
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            logs.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp,
                                    softWrap = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(apkName: String?, sizeText: String?, running: Boolean) {
    val colors = MaterialTheme.colorScheme
    // 顶部主卡片：玻璃底 + 主题色渐变染色。以前是纯渐变填充（一块不透明色板），
    // 现在渐变只当"玻璃上的染色"，底下仍然是壁纸在折射，和整站风格统一。
    Md3Panel(
        modifier = Modifier
            .fillMaxWidth(),
        radius = 26.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = 0.55f),
                            colors.secondaryContainer.copy(alpha = 0.35f),
                        )
                    )
                )
                .padding(18.dp),
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface.copy(alpha = 0.35f),
                ) {
                    Icon(
                        HugeIcons.Shield01,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "服务器加固",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "反编译 · 混淆 · 重打包 · 签名",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                apkName ?: "还没有选择安装包",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(sizeText ?: "支持任意 APK（≤240MB）")
                    if (running) append("  ·  处理中") else append("  ·  结果零留存")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClassRow(
    item: HardenClient.DexClass,
    obfChecked: Boolean,
    dexChecked: Boolean,
    enabled: Boolean,
    onToggleObf: (Boolean) -> Unit,
    onToggleDex: (Boolean) -> Unit,
) {
    Md3Panel(modifier = Modifier.fillMaxWidth(), radius = 16.dp, contentPadding = PaddingValues(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "方法 ${item.methods} · ${item.size / 1024} KB" +
                        if (!item.obfuscatable) " · 不可混淆" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(
                    checked = obfChecked,
                    enabled = enabled && item.obfuscatable,
                    onCheckedChange = onToggleObf,
                )
                Text("混淆", style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(
                    checked = dexChecked,
                    enabled = enabled && item.dex2cOk,
                    onCheckedChange = onToggleDex,
                )
                Text("抽取", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
