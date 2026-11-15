package com.gcmodai.app.ui.pages.harden

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkBadge01
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Rocket
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SourceCode
import me.rerere.hugeicons.stroke.TaskDone01
import com.gcmodai.app.data.files.FileFolders
import com.gcmodai.app.data.files.FilesManager
import com.gcmodai.app.data.db.entity.ManagedFileEntity
import com.gcmodai.app.data.harden.HardenClient
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.context.LocalToaster
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.ui.toast.ToastType
import org.koin.compose.koinInject

/**
 * APK 加固页。
 *
 * 设计目标：这是给做逆向/打包的人用的**工具仪表盘**，不是普通设置页。
 * 用等宽字体、分阶段时间轴、状态灯、细边玻璃卡表达「机器在替你干活」的专业感。
 * 深浅两套配色全部从 MaterialTheme.colorScheme 派生，跟随系统自动切换。
 *
 * 两套引擎：
 * - `classic`：apktool 瘦身链路（去调试信息 / 类重命名 / StringFog / dex2c）
 * - `xop`：XopProtector 壳（DEX 加密 / 空壳还原 / PVM1+P 真 VMP / SO 加密 / RASP）
 * - `both`：先瘦身再加壳，防护最完整
 *
 * 所有开关都真的进命令行参数，服务端不做静默降级。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardenPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val hardenClient: HardenClient = koinInject()
    val filesManager: FilesManager = koinInject()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // ---- 输入 ----
    var apkName by remember { mutableStateOf<String?>(null) }
    var apkBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ksName by remember { mutableStateOf<String?>(null) }
    var ksBytes by remember { mutableStateOf<ByteArray?>(null) }
    var ksPass by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }

    // ---- 引擎 ----
    var engine by remember { mutableStateOf(Engine.Classic) }

    // ---- classic ----
    var modeIdx by remember { mutableStateOf(1) }
    var stripDebug by remember { mutableStateOf(true) }
    var stripSo by remember { mutableStateOf(true) }
    var renameClasses by remember { mutableStateOf(false) }
    var antiDebug by remember { mutableStateOf(false) }
    var rootCheck by remember { mutableStateOf(false) }
    var hookCheck by remember { mutableStateOf(false) }
    var proxyCheck by remember { mutableStateOf(false) }
    var defaultSig by remember { mutableStateOf("") }
    var entryClass by remember { mutableStateOf("") }
    var soName by remember { mutableStateOf("") }
    var selectedAbis by remember { mutableStateOf(setOf("arm64-v8a")) }
    var classRules by remember { mutableStateOf("") }
    var stringFogRules by remember { mutableStateOf("") }

    // ---- XopProtector ----
    var xopProfileIdx by remember { mutableStateOf(0) }
    var hollowPrefix by remember { mutableStateOf("") }
    var vmpPrefix by remember { mutableStateOf("") }
    var trueVmpPrefix by remember { mutableStateOf("") }
    var protectSoIdx by remember { mutableStateOf(1) }
    var soBudgetMb by remember { mutableStateOf(12) }
    var soMaxFileMb by remember { mutableStateOf(8) }
    var soLazy by remember { mutableStateOf(false) }
    var encryptAssets by remember { mutableStateOf(false) }
    var resProtect by remember { mutableStateOf(false) }
    var raspExit by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf("") }

    // ---- 运行态 ----
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var jobState by remember { mutableStateOf("") }
    var elapsed by remember { mutableStateOf(0L) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultBytes by remember { mutableStateOf<ByteArray?>(null) }
    var savedInfo by remember { mutableStateOf<String?>(null) }
    // 本机已保存的加固产物：直接读 filesDir/harden 的索引
    var savedFiles by remember { mutableStateOf<List<ManagedFileEntity>>(emptyList()) }
    var classes by remember { mutableStateOf<List<HardenClient.DexClass>>(emptyList()) }
    var selectedObf by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDex by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ---- 历史 ----
    var jobs by remember { mutableStateOf<List<HardenClient.JobSummary>>(emptyList()) }
    var jobsLoading by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // ---- 折叠 ----
    var showClassic by remember { mutableStateOf(false) }
    var showXop by remember { mutableStateOf(false) }
    var showClasses by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("读取失败或文件超过 240MB")
                apkBytes = bytes
                apkName = uri.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
                logs = emptyList()
                resultBytes = null
                savedInfo = null
                jobId = null
                jobState = ""
                classes = emptyList()
                selectedObf = emptySet()
                selectedDex = emptySet()
            }.onFailure { toaster.show(it.message ?: "读取失败", type = ToastType.Error) }
            busy = false
        }
    }
    val ksPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("读取失败")
                ksBytes = bytes
                ksName = uri.lastPathSegment?.substringAfterLast('/') ?: "release.jks"
            }.onFailure { toaster.show(it.message ?: "读取失败", type = ToastType.Error) }
        }
    }

    suspend fun refreshJobs() {
        jobsLoading = true
        hardenClient.listJobs().onSuccess { jobs = it }
            .onFailure { if (jobs.isEmpty()) logs = logs + "读取任务失败：${it.message}" }
        jobsLoading = false
    }

    // 进页面就把已保存的加固产物读出来，避免用户以为「存了但不见了」
    LaunchedEffect(Unit) {
        savedFiles = runCatching { filesManager.list(FileFolders.HARDEN) }.getOrDefault(emptyList())
    }

    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (true) {
            delay(1500)
            val st = hardenClient.status(id).getOrElse {
                logs = logs + "查询失败：${it.message}"
                break
            }
            jobState = st.state
            elapsed = st.elapsed
            if (st.log.isNotEmpty()) logs = st.log
            if (st.classes.isNotEmpty()) classes = st.classes
            if (st.state == "done") {
                val bytes = hardenClient.download(id).getOrElse {
                    logs = logs + "下载失败：${it.message}"
                    break
                }
                resultBytes = bytes
                logs = logs + "加固完成：${bytes.size / 1024} KB（服务器已删除）"
                break
            }
            if (st.state == "error" || st.state == "failed") {
                logs = logs + "加固失败，见上方日志"
                break
            }
        }
    }

    fun saveResult(bytes: ByteArray) {
        scope.launch {
            runCatching {
                filesManager.createChatFilesByByteArrays(
                    byteArrays = listOf(bytes),
                    displayName = (apkName?.removeSuffix(".apk") ?: "app") + "-hardened.apk",
                    mimeType = "application/vnd.android.package-archive",
                    folder = FileFolders.HARDEN,
                ).firstOrNull()
            }.onSuccess {
                savedInfo = "已保存到本机「加固包」目录"
                // 刷新页内的产物列表，让用户当场看到刚存的文件
                savedFiles = runCatching { filesManager.list(FileFolders.HARDEN) }.getOrDefault(emptyList())
                toaster.show("已保存到本机「加固包」目录", type = ToastType.Success)
            }.onFailure {
                toaster.show("本地保存失败：${it.message}", type = ToastType.Error)
            }
        }
    }

    fun submit() {
        val bytes = apkBytes ?: return
        scope.launch {
            busy = true
            logs = emptyList()
            resultBytes = null
            savedInfo = null
            jobId = null
            jobState = "queued"
            val options = HardenClient.Options(
                mode = Mode3[modeIdx].key,
                stripSmaliDebug = stripDebug,
                stripSo = stripSo,
                renameClasses = renameClasses,
                obfuscateClasses = selectedObf.toList(),
                dex2cClasses = selectedDex.toList(),
                classRules = classRules.lines().map { it.trim() }.filter { it.isNotEmpty() },
                stringFogRules = stringFogRules.lines().map { it.trim() }.filter { it.isNotEmpty() },
                antiDebug = antiDebug,
                rootCheck = rootCheck,
                hookCheck = hookCheck,
                proxyCheck = proxyCheck,
                sigMd5 = defaultSig.trim(),
                customEntryClass = entryClass.trim(),
                customSoName = soName.trim(),
                abis = selectedAbis.toList(),
                keystore = ksBytes,
                keystorePass = ksPass,
                keyAlias = keyAlias,
                keyPass = keyPass,
                engine = engine.key,
                xopProfile = XopProfiles[xopProfileIdx].key,
                hollowPrefix = hollowPrefix.lines().map { it.trim() }.filter { it.isNotEmpty() },
                vmpPrefix = vmpPrefix.lines().map { it.trim() }.filter { it.isNotEmpty() },
                trueVmpPrefix = trueVmpPrefix.lines().map { it.trim() }.filter { it.isNotEmpty() },
                protectSoMode = SoModes[protectSoIdx].key,
                protectSoBudgetMb = soBudgetMb,
                protectSoMaxFileMb = soMaxFileMb,
                soDecryptMode = if (soLazy) "lazy" else "eager",
                encryptAssets = encryptAssets,
                resProtect = resProtect,
                raspAction = if (raspExit) "exit" else "report",
                channel = channel.trim(),
            )
            hardenClient.submit(bytes, options)
                .onSuccess { jobId = it }
                .onFailure {
                    jobState = "failed"
                    logs = listOf("提交失败：${it.message}")
                }
            busy = false
        }
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("APK 加固") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    IconButton(onClick = {
                        val opening = !showHistory
                        showHistory = opening
                        if (opening) {
                            // 任务列表在页面最底部：只切换状态不滚动，用户会以为按钮没反应。
                            // 这里刷新数据并滚到底，点一下就能看到东西。
                            scope.launch {
                                refreshJobs()
                                runCatching {
                                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
                                }
                            }
                        }
                    }) {
                        Icon(HugeIcons.TaskDone01, contentDescription = "任务列表")
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusHero(
                    apkName = apkName,
                    sizeMb = apkBytes?.let { it.size / 1048576f },
                    jobState = jobState,
                    elapsed = elapsed,
                    done = resultBytes != null,
                    engineLabel = engine.label,
                    busy = busy,
                    onPickApk = { apkPicker.launch(arrayOf("*/*")) },
                    onRun = ::submit,
                    onScan = {
                        val bytes = apkBytes ?: return@StatusHero
                        scope.launch {
                            scanning = true
                            hardenClient.scan(bytes)
                                .onSuccess { classes = it; showClasses = true }
                                .onFailure { toaster.show(it.message ?: "扫描失败", type = ToastType.Error) }
                            scanning = false
                        }
                    },
                    scanning = scanning,
                )
            }

            item {
                GlassCard {
                    SectionHeader(
                        title = "加固引擎",
                        subtitle = "两套可组合：先瘦身再加壳，防护最完整",
                        icon = HugeIcons.Layers01,
                    )
                    EngineSelector(selected = engine) { engine = it }
                }
            }

            if (engine != Engine.Xop) {
                item {
                    GlassCard {
                        CollapsibleHeader(
                            title = "瘦身与混淆",
                            subtitle = "apktool 链路：去调试信息 / 类重命名 / StringFog / dex2c",
                            icon = HugeIcons.SourceCode,
                            expanded = showClassic,
                        ) { showClassic = !showClassic }
                        AnimatedVisibility(showClassic) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "加固模式",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LevelSelector(
                                    levels = Mode3.map { Level(it.label, it.hint) },
                                    selected = modeIdx,
                                ) { modeIdx = it }
                                DashedDivider()
                                ToggleLine("剥离调试信息", "去掉 smali 的 .line/.source，反编译看不到行号", stripDebug) { stripDebug = it }
                                ToggleLine("剥离 SO 符号", "去掉 .symtab/.strtab/.comment，静态分析看不到符号", stripSo) { stripSo = it }
                                ToggleLine("类重命名", "把选中的类改成无语义名，增加阅读成本", renameClasses) { renameClasses = it }
                                OutlinedTextField(
                                    value = classRules,
                                    onValueChange = { classRules = it },
                                    label = { Text("保护类规则（每行一条）") },
                                    supportingText = { Text("+com.foo.* 包含；-com.foo.Skip 排除；留空=按勾选") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 5,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HardenTheme.mono),
                                )
                                DashedDivider()
                                // 字符串加密做成规则选择器：预设覆盖常见取舍，
                                // 手改过的规则自动落到「自定义」，不会被预设覆盖掉
                                LabeledLabel("字符串加密")
                                StringFogSelector(
                                    presets = StringFogPresets.ALL,
                                    currentRules = stringFogRules.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                    onPickPreset = { preset ->
                                        stringFogRules = preset.rules.joinToString("\n")
                                    },
                                    onEditCustom = { stringFogRules = it },
                                )
                                OutlinedTextField(
                                    value = soName,
                                    onValueChange = { soName = it },
                                    label = { Text("自定义 SO 名（可选）") },
                                    supportingText = { Text("dex2c 产物与守卫库都用这个名字") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            if (engine != Engine.Classic) {
                item {
                    GlassCard(accent = HardenTheme.running()) {
                        CollapsibleHeader(
                            title = "加壳与 VMP",
                            subtitle = "DEX 加密 · 空壳还原 · 方法虚拟化 · SO 加密",
                            icon = HugeIcons.Shield01,
                            expanded = showXop,
                        ) { showXop = !showXop }
                        AnimatedVisibility(showXop) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                LabeledLabel("空壳力度")
                                LevelSelector(
                                    levels = XopProfiles.map { Level(it.label, it.hint) },
                                    selected = xopProfileIdx,
                                ) { xopProfileIdx = it }

                                DashedDivider()
                                LabeledLabel("业务 SO 加密")
                                LevelSelector(
                                    levels = SoModes.map { Level(it.label, it.hint) },
                                    selected = protectSoIdx,
                                ) { protectSoIdx = it }

                                if (protectSoIdx > 0) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        MetricTile(
                                            label = "体积预算",
                                            value = soBudgetMb.toString(),
                                            unit = "MB",
                                            modifier = Modifier.weight(1f),
                                        )
                                        MetricTile(
                                            label = "单文件上限",
                                            value = soMaxFileMb.toString(),
                                            unit = "MB",
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(6, 12, 24).forEach { v ->
                                            SmallChip("${v}MB") {
                                                soBudgetMb = v
                                                soMaxFileMb = (v / 2).coerceAtLeast(4)
                                            }
                                        }
                                    }
                                }
                                ToggleLine(
                                    "延迟解密 SO",
                                    "首次 dlopen 才解密，启动更快；关闭则冷启动全解密更稳",
                                    soLazy,
                                ) { soLazy = it }

                                DashedDivider()
                                OutlinedTextField(
                                    value = hollowPrefix,
                                    onValueChange = { hollowPrefix = it },
                                    label = { Text("空壳类前缀（每行一条，可选）") },
                                    supportingText = { Text("如 Lcom/foo —— 留空则按上面的力度档自动选") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HardenTheme.mono),
                                )
                                OutlinedTextField(
                                    value = vmpPrefix,
                                    onValueChange = { vmpPrefix = it },
                                    label = { Text("PVM1 打包前缀（每行一条，可选）") },
                                    supportingText = { Text("方法打包后运行时还原成 Dalvik，不是解释执行") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HardenTheme.mono),
                                )
                                OutlinedTextField(
                                    value = trueVmpPrefix,
                                    onValueChange = { trueVmpPrefix = it },
                                    label = { Text("PVM2 真 VMP 前缀（每行一条，可选）") },
                                    supportingText = { Text("交给 native 解释执行，强度最高、兼容风险也最高，建议先小范围试") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HardenTheme.mono),
                                )

                                DashedDivider()
                                ToggleLine("加密 assets", "把 assets 目录整体加密到 protector/aenc（AES-GCM）", encryptAssets) { encryptAssets = it }
                                ToggleLine("资源路径保护", "缩短 res 路径并重写 resources.arsc", resProtect) { resProtect = it }
                                ToggleLine("RASP 命中直接退出", "关闭时只上报；打开后检测到 Root/Hook/调试即退出", raspExit) { raspExit = it }
                                OutlinedTextField(
                                    value = channel,
                                    onValueChange = { channel = it },
                                    label = { Text("渠道号（可选）") },
                                    supportingText = { Text("留空不打渠道；需要提供签名证书") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            item {
                GlassCard(accent = HardenTheme.warn()) {
                    SectionHeader(
                        title = "应用防护",
                        subtitle = "命中即阻断；自己用 Studio 调试时不要开",
                        icon = HugeIcons.FingerPrint,
                    )
                    ToggleLine("反调试保护", "ptrace / TracerPid / Frida 三重检测", antiDebug) { antiDebug = it }
                    ToggleLine("Root 检测", "su 路径、test-keys、remount 特征", rootCheck) { rootCheck = it }
                    ToggleLine("Hook 检测", "Xposed / LSPosed / Substrate / Zygisk", hookCheck) { hookCheck = it }
                    ToggleLine("抓包校验", "系统代理与 mitmproxy / Charles / libpcap 特征", proxyCheck) { proxyCheck = it }
                }
            }

            item {
                GlassCard {
                    SectionHeader(
                        title = "签名",
                        subtitle = "用你自己的证书，加固后的包才能覆盖安装",
                        icon = HugeIcons.LockKey,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = { ksPicker.launch(arrayOf("*/*")) }) {
                            Icon(HugeIcons.Files02, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(ksName ?: "选择证书")
                        }
                        if (ksBytes != null) {
                            TextButton(onClick = { ksBytes = null; ksName = null }) { Text("清除") }
                        }
                    }
                    if (ksBytes != null) {
                        OutlinedTextField(
                            value = ksPass, onValueChange = { ksPass = it },
                            label = { Text("证书密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = keyAlias, onValueChange = { keyAlias = it },
                            label = { Text("别名 alias") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = keyPass, onValueChange = { keyPass = it },
                            label = { Text("别名密码（留空同证书密码）") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "不提供证书时会用服务器证书签名，装之前要先卸载旧版。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = defaultSig, onValueChange = { defaultSig = it },
                        label = { Text("签名校验 MD5（可选，32 位小写）") },
                        supportingText = { Text("填了就会在运行时校验签名，不匹配即退出") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HardenTheme.mono),
                    )
                }
            }

            item {
                GlassCard {
                    SectionHeader(
                        title = "目标架构与入口",
                        subtitle = "每多选一个架构，处理时间大约翻一倍",
                        icon = HugeIcons.Settings02,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("arm64-v8a", "armeabi-v7a", "x86_64").forEach { abi ->
                            val on = abi in selectedAbis
                            SmallChip(abi, selected = on) {
                                selectedAbis = if (on) selectedAbis - abi else selectedAbis + abi
                            }
                        }
                    }
                    OutlinedTextField(
                        value = entryClass, onValueChange = { entryClass = it },
                        label = { Text("入口类（可选）") },
                        supportingText = { Text("注入加载代码的落点，留空自动找 Application") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (classes.isNotEmpty()) {
                item {
                    GlassCard {
                        CollapsibleHeader(
                            title = "类清单",
                            subtitle = "共 ${classes.size} 个 · 已选混淆 ${selectedObf.size} / dex2c ${selectedDex.size}",
                            icon = HugeIcons.SourceCode,
                            expanded = showClasses,
                        ) { showClasses = !showClasses }
                        AnimatedVisibility(showClasses) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallChip("全选可混淆") {
                                        selectedObf = classes.filter { it.obfuscatable }.map { it.descriptor }.toSet()
                                    }
                                    SmallChip("全选可 dex2c") {
                                        selectedDex = classes.filter { it.dex2cOk }.map { it.descriptor }.toSet()
                                    }
                                    SmallChip("清空") { selectedObf = emptySet(); selectedDex = emptySet() }
                                }
                                classes.take(300).forEach { c ->
                                    ClassRow(
                                        dexClass = c,
                                        obfSelected = c.descriptor in selectedObf,
                                        dexSelected = c.descriptor in selectedDex,
                                        onToggleObf = {
                                            selectedObf = if (c.descriptor in selectedObf) {
                                                selectedObf - c.descriptor
                                            } else {
                                                selectedObf + c.descriptor
                                            }
                                        },
                                        onToggleDex = {
                                            selectedDex = if (c.descriptor in selectedDex) {
                                                selectedDex - c.descriptor
                                            } else {
                                                selectedDex + c.descriptor
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (jobId != null || logs.isNotEmpty()) {
                item {
                    GlassCard(accent = HardenTheme.running()) {
                        SectionHeader(
                            title = "处理进度",
                            subtitle = if (jobId != null) {
                                "任务 ${jobId?.take(8)} · 已用 ${elapsed}s"
                            } else {
                                "等待提交"
                            },
                            icon = HugeIcons.Rocket,
                            trailing = {
                                StatusLamp(
                                    text = stateLabel(jobState),
                                    color = stateColor(jobState),
                                    pulsing = jobState == "running" || jobState == "queued",
                                )
                            },
                        )
                        StageTimeline(logs = logs, jobState = jobState, engine = engine)
                        if (logs.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f))
                                    .padding(10.dp)
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                logs.takeLast(120).forEach { LogLine(it) }
                            }
                        }
                    }
                }
            }

            resultBytes?.let { bytes ->
                item {
                    GlassCard(accent = HardenTheme.ok()) {
                        SectionHeader(
                            title = "加固完成",
                            subtitle = "服务器已删除源包，结果只在你的手机上",
                            icon = HugeIcons.CheckmarkBadge01,
                            trailing = { StatusLamp("${bytes.size / 1024} KB", HardenTheme.ok()) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { saveResult(bytes) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(HugeIcons.Download04, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("保存到本机")
                            }
                            OutlinedButton(onClick = { resultBytes = null; savedInfo = null }) {
                                Text("清除")
                            }
                        }
                        savedInfo?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = HardenTheme.ok())
                        }
                    }
                }
            }

            if (showHistory) {
                item {
                    GlassCard {
                        SectionHeader(
                            title = "任务列表",
                            subtitle = if (jobsLoading) "刷新中…" else "共 ${jobs.size} 条",
                            icon = HugeIcons.TaskDone01,
                            trailing = {
                                IconButton(onClick = { scope.launch { refreshJobs() } }) {
                                    Icon(HugeIcons.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                                }
                            },
                        )
                        if (jobs.isEmpty() && !jobsLoading) {
                            Text(
                                "还没有任务，选个 APK 跑一单吧",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        jobs.take(20).forEach { j ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusLamp(text = stateLabel(j.state), color = stateColor(j.state))
                                Text(
                                    text = j.id.take(8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = HardenTheme.mono,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                j.size?.let {
                                    Text(
                                        "${it / 1024}KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = HardenTheme.mono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 本机加固产物 ----
            // 「存储管理」页是代码产物托管（上传到服务器的文件），不是本地文件浏览器。
            // 加固结果落在 filesDir/harden，所以必须在这里列出来，否则用户无处可找。
            if (savedFiles.isNotEmpty()) {
                item {
                    GlassCard(accent = HardenTheme.ok()) {
                        SectionHeader(
                            title = "本机加固包",
                            subtitle = "共 ${savedFiles.size} 个 · 点条目可分享，长按删除",
                            icon = HugeIcons.CheckmarkBadge01,
                        )
                        savedFiles.take(10).forEach { f ->
                            SavedFileRow(
                                file = f,
                                onShare = {
                                    scope.launch {
                                        runCatching {
                                            val uri = filesManager.getFile(f).toUri()
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_SEND
                                            ).apply {
                                                type = f.mimeType
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                android.content.Intent.createChooser(intent, "分享加固包")
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }.onFailure {
                                            toaster.show("分享失败：${it.message}", type = ToastType.Error)
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        runCatching {
                                            filesManager.getFile(f).delete()
                                            savedFiles = filesManager.list(FileFolders.HARDEN)
                                        }.onSuccess {
                                            toaster.show("已删除", type = ToastType.Success)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "零留存：源包反编译后立即删除，结果只存内存，下载一次即清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/* ------------------------------ 子组件 ------------------------------ */

internal enum class Engine(val key: String, val label: String, val hint: String) {
    Classic("classic", "瘦身链路", "apktool 去调试信息 + 类重命名 + StringFog + dex2c"),
    Xop("xop", "加壳引擎", "DEX 加密 + 空壳还原 + VMP + SO 加密 + RASP"),
    Both("both", "两者叠加", "先瘦身再加壳，防护最完整，耗时最长"),
}

private data class ModeItem(val key: String, val label: String, val hint: String)

private val Mode3 = listOf(
    ModeItem("safe", "最兼容", "只剥离调试信息 + 重新签名。改动最少，兼容性最好（推荐线上包）"),
    ModeItem("standard", "标准", "剥离调试信息 + SO 符号剥离 + v1/v2/v3 签名 + 对齐。不动代码逻辑，绝大多数 App 都安全"),
    ModeItem("deep", "深度加固", "在标准模式上叠加类混淆 / dex2c / 反调试。防护最强但改动最大，服务端会做兼容性终检"),
)

private data class XopProfileItem(val key: String, val label: String, val hint: String)

private val XopProfiles = listOf(
    XopProfileItem("balanced", "平衡", "只抽空 applicationId 包的类，保留大部分代码 AOT 友好，启动最快"),
    XopProfileItem("industry", "行业", "跳过主流 SDK 与组件后抽空业务类，适合工具/工业类应用"),
    XopProfileItem("aggressive", "激进", "跳过框架与组件，抽空其余业务类，防护更强"),
    XopProfileItem("perf", "性能", "与平衡同策略但优先 AOT 友好，追求冷启动速度"),
    XopProfileItem("max", "最大", "几乎全抽（仅跳过 android/ 与 androidx/），防护最强、启动最慢"),
)

private data class SoModeItem(val key: String, val label: String, val hint: String)

private val SoModes = listOf(
    SoModeItem("off", "关闭", "不加密业务 SO，包体不变，启动最快"),
    SoModeItem("safe", "安全", "加密安全范围内的 .text，跳过重定位补丁过的 SO，有体积预算"),
    SoModeItem("aggressive", "激进", "只跳过壳自身与 text 重定位，仍有体积裁剪"),
    SoModeItem("max", "最大", "加密所有符合条件的 SO，忽略体积预算，包体会明显变大"),
)

@Composable
private fun StatusHero(
    apkName: String?,
    sizeMb: Float?,
    jobState: String,
    elapsed: Long,
    done: Boolean,
    engineLabel: String,
    busy: Boolean,
    onPickApk: () -> Unit,
    onRun: () -> Unit,
    onScan: () -> Unit,
    scanning: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(accent = scheme.primary) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RingGauge(
                progress = when {
                    done -> 1f
                    jobState == "running" -> 0.55f
                    jobState == "queued" -> 0.15f
                    sizeMb != null -> 0.06f
                    else -> 0f
                },
                center = when {
                    done -> "OK"
                    jobState == "running" -> "${elapsed}s"
                    sizeMb != null -> "就绪"
                    else -> "空"
                },
                sub = when {
                    done -> "已完成"
                    jobState == "running" -> "处理中"
                    else -> "待选择"
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = apkName ?: "还没有选择 APK",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (sizeMb != null) "%.1f MB · %s".format(sizeMb, engineLabel) else "点下面按钮选一个 APK",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = HardenTheme.mono,
                    color = scheme.onSurfaceVariant,
                )
                if (!done && jobState != "running" && jobState != "queued") {
                    StatusLamp(
                        text = if (sizeMb != null) "待提交" else "空闲",
                        color = if (sizeMb != null) HardenTheme.warn() else scheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                HugeIcons.CloudUpload,
                contentDescription = null,
                tint = scheme.primary.copy(alpha = 0.32f),
                modifier = Modifier.size(34.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPickApk, modifier = Modifier.weight(1f)) {
                Icon(HugeIcons.Files02, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (sizeMb == null) "选择 APK" else "换一个")
            }
            Button(
                onClick = onRun,
                modifier = Modifier.weight(1f),
                enabled = sizeMb != null && !busy && jobState != "running" && jobState != "queued",
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = scheme.onPrimary,
                    )
                } else {
                    Icon(HugeIcons.Rocket, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始加固")
                }
            }
        }
        if (sizeMb != null && jobState != "running" && jobState != "queued") {
            TextButton(onClick = onScan, enabled = !scanning) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (scanning) "扫描中…" else "扫描类清单（用于勾选混淆 / dex2c）")
            }
        }
    }
}

@Composable
private fun EngineSelector(selected: Engine, onSelect: (Engine) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Engine.entries.forEach { e ->
            val on = e == selected
            val borderColor by animateColorAsState(
                if (on) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f),
                label = "border",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) scheme.primary.copy(alpha = 0.09f) else Color.Transparent)
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .clickable { onSelect(e) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (on) scheme.primary else scheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(scheme.primary)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        e.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        e.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsibleHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            SectionHeader(title = title, subtitle = subtitle, icon = icon)
        }
        Text(
            text = if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LabeledLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToggleLine(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SmallChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontFamily = HardenTheme.mono,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ClassRow(
    dexClass: HardenClient.DexClass,
    obfSelected: Boolean,
    dexSelected: Boolean,
    onToggleObf: () -> Unit,
    onToggleDex: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = dexClass.name.ifBlank { dexClass.descriptor },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = HardenTheme.mono,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = scheme.onSurface,
        )
        if (dexClass.dex2cReason.isNotBlank()) {
            Text(
                text = dexClass.dex2cReason.take(16),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = HardenTheme.warn(),
                maxLines = 1,
            )
        }
        SmallChip("混淆", selected = obfSelected) { if (dexClass.obfuscatable) onToggleObf() }
        SmallChip("dex2c", selected = dexSelected) { if (dexClass.dex2cOk) onToggleDex() }
    }
}

/**
 * 阶段时间轴：把流式日志里的关键字映射成六阶段状态。
 *
 * 服务端日志没有结构化阶段字段，这里做一次轻量推断：
 * 出现某阶段关键字就认为它已开始，下一个阶段出现则认为上一个完成。
 * 推断不出来的显示「等待」，不假装成功。
 */
@Composable
private fun StageTimeline(
    logs: List<String>,
    jobState: String,
    engine: Engine,
) {
    val joined = logs.joinToString("\n").lowercase()
    val stages = buildList {
        if (engine != Engine.Xop) {
            add("解包" to listOf("apktool", "decod", "解包"))
            add("瘦身混淆" to listOf("stringfog", "rename", "混淆", "strip"))
        }
        add("DEX 加密" to listOf("code.bin", "dexes.zip", "pdx1", "hollow", "加密"))
        add("SO 保护" to listOf("libprotector", "rc4", "sokeys", ".text", "so "))
        add("重打包" to listOf("zipalign", "apktool b", "packag", "重打包"))
        add("签名校验" to listOf("apksigner", "签名", "verify"))
    }

    var reached = 0
    stages.forEachIndexed { i, (_, keys) ->
        if (keys.any { it in joined }) reached = i + 1
    }
    val active = if (jobState == "running") reached.coerceAtMost(stages.lastIndex) else -1

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StageProgress(total = stages.size, done = reached, active = active)
        Spacer(Modifier.height(6.dp))
        stages.forEachIndexed { i, (title, keys) ->
            val state = when {
                jobState == "error" || jobState == "failed" ->
                    if (i < reached) StageState.Done else if (i == reached) StageState.Failed else StageState.Pending

                i < reached -> StageState.Done
                i == reached && jobState == "running" -> StageState.Active
                else -> StageState.Pending
            }
            StageRow(
                index = i,
                title = title,
                detail = keys.firstOrNull { it in joined } ?: "",
                state = state,
                isLast = i == stages.lastIndex,
            )
        }
    }
}

/**
 * 本机加固产物的一行：显示名字与体积，点一下分享，右侧按钮删除。
 *
 * 为什么把入口放在加固页里：加固结果落在 filesDir/harden，
 * 而「存储管理」页管的是上传到服务器的代码产物，两者不是一回事。
 */
@Composable
private fun SavedFileRow(
    file: ManagedFileEntity,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onShare() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            HugeIcons.Download04,
            contentDescription = null,
            tint = HardenTheme.ok(),
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = HardenTheme.mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "%.1f MB".format(file.sizeBytes / 1048576.0),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text("删除", style = MaterialTheme.typography.labelSmall, color = HardenTheme.danger())
        }
    }
}

internal fun stateLabel(state: String): String = when (state) {
    "queued" -> "排队"
    "running" -> "处理中"
    "done" -> "已完成"
    "error", "failed" -> "失败"
    else -> "空闲"
}

@Composable
private fun stateColor(state: String): Color = when (state) {
    "done" -> HardenTheme.ok()
    "running", "queued" -> HardenTheme.running()
    "error", "failed" -> HardenTheme.danger()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
