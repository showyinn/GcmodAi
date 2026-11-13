package com.gcmodai.app.ui.pages.harden

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
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
    val toaster = com.gcmodai.app.ui.context.LocalToaster.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    var apkName by remember { mutableStateOf<String?>(null) }
    var apkBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ksName by remember { mutableStateOf<String?>(null) }
    var ksBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ksPass by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var stripDebug by remember { mutableStateOf(true) }
    var stripSo by remember { mutableStateOf(true) }
    // 反调试守卫：默认关闭。自己用 Studio 调试时装上会直接退出，属于预期行为。
    var antiDebug by remember { mutableStateOf(false) }
    // 加固模式：默认「标准」—— 不动代码逻辑，绝大多数包都安全
    var mode by remember { mutableStateOf("standard") }
    // ---- 高级防护（对照云镜的「防护配置」）----
    var rootCheck by remember { mutableStateOf(false) }
    var hookCheck by remember { mutableStateOf(false) }
    var proxyCheck by remember { mutableStateOf(false) }
    var sigMd5 by remember { mutableStateOf("") }
    var entryClass by remember { mutableStateOf("") }
    var soName by remember { mutableStateOf("") }
    var selectedAbis by remember { mutableStateOf(setOf("arm64-v8a")) }
    var classRules by remember { mutableStateOf("") }
    var stringFogRules by remember { mutableStateOf("") }
    var jobs by remember { mutableStateOf<List<HardenClient.JobSummary>>(emptyList()) }
    var jobsLoading by remember { mutableStateOf(false) }
    var jobTab by remember { mutableStateOf("active") }
    var showAdvanced by remember { mutableStateOf(false) }

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
    // 进页面就拉一次任务列表：以前必须手动点「刷新」，关掉页面再进来会以为历史丢了
    var jobsAutoLoaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!jobsAutoLoaded) {
            jobsAutoLoaded = true
            jobsLoading = true
            client.listJobs(30).onSuccess { jobs = it }
            jobsLoading = false
        }
    }
    // 当前任务跑完也自动刷新一次，列表里立刻能看到「已完成」
    androidx.compose.runtime.LaunchedEffect(busy) {
        if (!busy && jobId != null) {
            client.listJobs(30).onSuccess { jobs = it }
        }
    }

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
                    sizeText = apkBytes?.let { "%.1f MB".format(it.size / 1024f / 1024f) },
                    running = busy || scanning,
                    status = when {
                        busy || scanning -> "处理中"
                        resultBytes != null -> "已完成"
                        stateText.startsWith("提交失败") || stateText.startsWith("失败") -> "失败"
                        else -> null
                    },
                    statusDetail = if (resultBytes != null) savedInfo else stateText.ifBlank { null },
                )
            }

            // ---------------- 功能选择（对照云镜）：一眼看清这单开了什么 ----------------
            // ---------------- 任务列表（进行中 / 已完成） ----------------
            item("jobs") {
                SectionCard(
                    title = "任务列表",
                    trailing = {
                        TextButton(
                            enabled = !jobsLoading,
                            onClick = {
                                jobsLoading = true
                                scope.launch {
                                    client.listJobs(30)
                                        .onSuccess { jobs = it }
                                        .onFailure { toaster.show("读取任务失败：${it.message}") }
                                    jobsLoading = false
                                }
                            },
                        ) { Text(if (jobsLoading) "刷新中…" else "刷新") }
                    },
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = jobTab == "active",
                            onClick = { jobTab = "active" },
                            label = {
                                Text("进行中（${jobs.count { it.state == "running" || it.state == "queued" }}）")
                            },
                        )
                        FilterChip(
                            selected = jobTab == "done",
                            onClick = { jobTab = "done" },
                            label = {
                                Text("已完成（${jobs.count { it.state == "done" || it.state == "delivered" || it.state == "failed" }}）")
                            },
                        )
                    }
                    val shown = jobs.filter {
                        if (jobTab == "active") {
                            it.state == "running" || it.state == "queued"
                        } else {
                            it.state == "done" || it.state == "delivered" || it.state == "failed"
                        }
                    }.take(8)
                    if (shown.isEmpty()) {
                        Text(
                            if (jobs.isEmpty()) "还没有任务，选个 APK 跑一单吧" else "这个分类下暂无任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        shown.forEach { job ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch {
                                            client.status(job.id).onSuccess { st ->
                                                logs = st.log.takeLast(80)
                                                stateText = "历史任务：${st.state}（${st.elapsed}s）"
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = when (job.state) {
                                        "done", "delivered" -> MaterialTheme.colorScheme.primary
                                        "failed" -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.secondaryContainer
                                    },
                                ) {
                                    Text(
                                        when (job.state) {
                                            "queued" -> "排队"
                                            "running" -> "进行中"
                                            "done" -> "已完成"
                                            "delivered" -> "已下载"
                                            "failed" -> "失败"
                                            else -> job.state
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "%d MB · %ds".format((job.size ?: 0L) / 1024 / 1024, job.elapsed),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        job.logTail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (job.downloadable) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                client.download(job.id)
                                                    .onSuccess { bytes ->
                                                        resultBytes = bytes
                                                        runCatching {
                                                            filesManager.saveManagedFromBytes(
                                                                folder = FileFolders.HARDEN,
                                                                bytes = bytes,
                                                                displayName = "hardened-${job.id.take(6)}.apk",
                                                                mimeType = "application/vnd.android.package-archive",
                                                            )
                                                        }.onSuccess {
                                                            savedInfo = "已保存到「存储管理 → 加固包」"
                                                        }
                                                        stateText = "已取回结果（%.1f MB）"
                                                            .format(bytes.size / 1024f / 1024f)
                                                    }
                                                    .onFailure { toaster.show("下载失败：${it.message}") }
                                            }
                                        }
                                    ) { Text("下载", maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }

            item("features") {
                Md3Panel(
                    modifier = Modifier.fillMaxWidth(),
                    radius = 22.dp,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "功能选择",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val chips: List<Triple<String, Boolean, (Boolean) -> Unit>> = listOf(
                                Triple("应用防护", stripDebug) { v: Boolean -> stripDebug = v },
                                Triple("SO 加固", stripSo) { v: Boolean -> stripSo = v },
                                Triple("反调试", antiDebug) { v: Boolean -> antiDebug = v },
                                Triple("Root 检测", rootCheck) { v: Boolean -> rootCheck = v },
                                Triple("Hook 检测", hookCheck) { v: Boolean -> hookCheck = v },
                                Triple("抓包校验", proxyCheck) { v: Boolean -> proxyCheck = v },
                            )
                            chips.forEach { (label, selected, onToggle) ->
                                FilterChip(
                                    selected = selected,
                                    onClick = { onToggle(!selected) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text(
                            "提示：签名校验需要填 32 位小写 MD5（下方「防护配置（高级）」里）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                        // 加固模式：先说清楚"改动越小越不容易崩"，再让用户选强度
                        Text(
                            "加固模式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (mode) {
                                "safe" -> "只剥离调试信息 + 重新签名。改的东西最少，兼容性最好（推荐给线上包）"
                                "deep" -> "在标准模式上叠加类混淆 / dex2c / 反调试。防护最强，" +
                                    "但改动最大；服务端会做兼容性终检，不通过就不会出包"
                                else -> "剥离调试信息 + SO 符号剥离 + v1/v2/v3 签名 + 对齐。不动代码逻辑，" +
                                    "绝大多数 App 都安全"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "safe" to "最兼容",
                                "standard" to "标准",
                                "deep" to "深度加固",
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = mode == id,
                                    onClick = { mode = id },
                                    label = { Text(label) },
                                )
                            }
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
                        OptionRow(
                            title = "反调试保护",
                            desc = "注入 libGcmodGuard.so：ptrace / TracerPid / Frida 三重检测，命中直接退出。" +
                                "自己用 Studio 调试时不要开。",
                            checked = antiDebug,
                            onChange = { antiDebug = it },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        // ---- 高级防护：只在「深度加固」下生效的部分，折叠起来避免干扰 ----
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "防护配置（高级）",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Root / Hook / 抓包 / 签名校验，注入自定义入口类与 so 名",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                Text(if (showAdvanced) "收起" else "展开")
                            }
                        }
                        if (showAdvanced) {
                            OptionRow(
                                title = "Root 检测",
                                desc = "检测 su 路径、test-keys、remount 特征，命中直接退出",
                                checked = rootCheck,
                                onChange = { rootCheck = it },
                            )
                            OptionRow(
                                title = "Hook 检测",
                                desc = "扫描 Xposed / LSPosed / Substrate / Zygisk / Frida 特征",
                                checked = hookCheck,
                                onChange = { hookCheck = it },
                            )
                            OptionRow(
                                title = "抓包校验",
                                desc = "检测系统代理与 mitmproxy / Charles / libpcap 特征",
                                checked = proxyCheck,
                                onChange = { proxyCheck = it },
                            )
                            OutlinedTextField(
                                value = sigMd5,
                                onValueChange = { sigMd5 = it },
                                label = { Text("签名校验 MD5（可选，32 位小写）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = entryClass,
                                onValueChange = { entryClass = it },
                                label = { Text("入口类（可选，如 com.foo.App）") },
                                supportingText = { Text("注入加载代码的落点，留空自动找 Application") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = soName,
                                onValueChange = { soName = it },
                                label = { Text("自定义 so 名（可选，如 yjprotect）") },
                                supportingText = { Text("dex2c 产物与守卫库都用这个名字，自动补 lib/ .so") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "架构选择",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                "每多选一个架构，处理时间会 +1 倍",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                HardenClient.ABI_CHOICES.forEach { (abi, label) ->
                                    FilterChip(
                                        selected = abi in selectedAbis,
                                        onClick = {
                                            selectedAbis = if (abi in selectedAbis) {
                                                selectedAbis - abi
                                            } else {
                                                selectedAbis + abi
                                            }
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = stringFogRules,
                                onValueChange = { stringFogRules = it },
                                label = { Text("字符串加密规则（每行一条）") },
                                supportingText = {
                                    Text("命中的类会把 const-string 明文加密成运行时解密；留空=不加密")
                                },
                                minLines = 2,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = classRules,
                                onValueChange = { classRules = it },
                                label = { Text("保护类规则（每行一条）") },
                                supportingText = {
                                    Text("+com.foo.* 包含该包下所有类；-com.foo.Skip 排除；留空=按勾选")
                                },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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
                                            antiDebug = antiDebug,
                                            mode = mode,
                                            rootCheck = rootCheck,
                                            hookCheck = hookCheck,
                                            proxyCheck = proxyCheck,
                                            // 签名 MD5 必须 32 位十六进制，格式不对就不发（服务端也会兜底忽略）
                                            sigMd5 = sigMd5.trim().lowercase()
                                                .takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
                                                ?: "",
                                            customEntryClass = entryClass.trim(),
                                            customSoName = soName.trim(),
                                            abis = selectedAbis.toList(),
                                            classRules = classRules.lines()
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() },
                                            stringFogRules = stringFogRules.lines()
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() },
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "服务器日志",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                                toaster.show("日志已复制")
                            }
                        ) {
                            Text("复制日志")
                        }
                    }
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
private fun HeroCard(
    apkName: String?,
    sizeText: String?,
    running: Boolean,
    status: String? = null,
    statusDetail: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    GlasslessHero(
        apkName = apkName,
        sizeText = sizeText,
        running = running,
        status = status,
        statusDetail = statusDetail,
        colors = colors,
    )
}

/**
 * 顶部目标卡片（云镜式）：左侧图标 + 目标名 + 三行状态信息，右侧状态徽章。
 *
 * 用 tonal 渐变而不是纯色块：既保持"控制台"的稳重感，也不会像纯色卡片那样死板。
 */
@Composable
private fun GlasslessHero(
    apkName: String?,
    sizeText: String?,
    running: Boolean,
    status: String?,
    statusDetail: String?,
    colors: androidx.compose.material3.ColorScheme,
) {
    Md3Panel(
        modifier = Modifier.fillMaxWidth(),
        radius = 24.dp,
        containerColor = colors.surfaceContainerHigh,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = 0.50f),
                            colors.tertiaryContainer.copy(alpha = 0.28f),
                        )
                    )
                )
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            HugeIcons.Shield01,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colors.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "APK 加固",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "反编译 · 混淆 · dex2c · 重打包 · 签名",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (status != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = when (status) {
                                "已完成" -> colors.primary
                                "失败" -> colors.errorContainer
                                else -> colors.secondaryContainer
                            },
                            contentColor = when (status) {
                                "已完成" -> colors.onPrimary
                                "失败" -> colors.onErrorContainer
                                else -> colors.onSecondaryContainer
                            },
                        ) {
                            Text(
                                status,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                InfoLine("目标", apkName ?: "尚未选择安装包")
                InfoLine("大小", sizeText ?: "支持任意 APK（≤240MB）")
                InfoLine(
                    "策略",
                    if (running) "处理中…" else (statusDetail ?: "结果零留存 · 下载一次即清除"),
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
                    buildString {
                        append("方法 ${item.methods} · ${item.size / 1024} KB")
                        if (!item.obfuscatable) append(" · 不可混淆")
                        // 服务端安全体检结论：直接说明"为什么这个类抽不了"，
                        // 而不是把勾选框灰着却不给理由。
                        if (!item.dex2cOk && item.dex2cReason.isNotBlank()) {
                            append(" · 不可抽取：").append(item.dex2cReason)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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


@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Md3Panel(
        modifier = Modifier.fillMaxWidth(),
        radius = 22.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}
