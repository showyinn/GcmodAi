package com.gcmodai.app.ui.pages.harden

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcmodai.app.data.harden.HardenClient
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.theme.CustomColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject
import java.io.File

/**
 * APK 加固页。
 *
 * 这里只做「上传 → 轮询 → 下载」：真正的 DEX 混淆、dex2c、Ollvm 在服务器上跑
 * （apktool + apksigner + 后续接入的 NDK 工具链），手机端不承担编译。
 */
@Composable
fun HardenPage() {
    val context = LocalContext.current
    val client: HardenClient = koinInject()
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
    var jobId by remember { mutableStateOf<String?>(null) }
    var stateText by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultBytes by remember { mutableStateOf<ByteArray?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    // 类扫描结果与勾选（混淆 / dex2c 抽取）
    var classes by remember { mutableStateOf<List<HardenClient.DexClass>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var selectedObf by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDex by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun readUri(uri: Uri, maxBytes: Long = 240L * 1024 * 1024): Pair<String, ByteArray>? {
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
        runCatching { readUri(uri) }.getOrNull()?.let { (name, data) ->
            apkName = name
            apkBytes = data
            resultBytes = null
            savedPath = null
            stateText = "已选择：$name（${data.size / 1024} KB）"
        } ?: run { stateText = "读取失败或文件超过 240MB" }
    }

    val ksPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { readUri(uri, 2L * 1024 * 1024) }.getOrNull()?.let { (name, data) ->
            ksName = name
            ksBytes = data
        }
    }

    // 轮询任务状态
    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (isActive) {
            val r = client.status(id)
            r.onSuccess { s ->
                stateText = "状态：${s.state}（已用 ${s.elapsed}s）"
                logs = s.log
                if (s.state == "done") {
                    val d = client.download(id)
                    d.onSuccess {
                        resultBytes = it
                        stateText = "加固完成：${it.size / 1024} KB（服务器端已删除，仅此一份）"
                    }
                        .onFailure { stateText = "下载失败：${it.message}" }
                    busy = false
                    return@LaunchedEffect
                }
                if (s.state == "failed") {
                    stateText = "加固失败，请看下方日志"
                    busy = false
                    return@LaunchedEffect
                }
            }.onFailure {
                stateText = "查询失败：${it.message}"
            }
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
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            apkName ?: "未选择 APK",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { apkPicker.launch(arrayOf("*/*")) }) { Text("选择 APK") }
                            OutlinedButton(onClick = { ksPicker.launch(arrayOf("*/*")) }) {
                                Text(if (ksName == null) "选择签名证书" else "已选证书", maxLines = 1)
                            }
                        }
                        if (ksName != null) {
                            Text(
                                ksName!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "不选证书则用服务器证书签名，安装前需卸载原应用；选了自己的证书才能覆盖安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "零留存：源 APK 反编译后立即删除，中间文件随任务清理，加固结果只存在内存里，"
                                + "你下载一次后服务器立即清除，不会保存任何 APK。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (ksBytes != null) {
                item {
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                label = { Text("key 密码（通常与 store 相同）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("加固项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("剥离调试信息", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "删除 smali 的 .line/.source/.local，反编译后看不到行号与局部变量名",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = stripDebug, onCheckedChange = { stripDebug = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("SO 加固", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "剥离 .symtab/.strtab/.comment/.debug_*，静态分析时看不到符号与编译信息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = stripSo, onCheckedChange = { stripSo = it })
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("选择要处理的类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "先扫描类列表，再勾选：\"混淆\"=重命名该类，\"抽取\"=dex2c 抽到 native（产物固定 libGcmodAi.so）。\n" +
                                "含 $ 的内部类/生成类不可勾选；组件类会被服务端自动跳过。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                enabled = !busy && !scanning && apkBytes != null,
                                onClick = {
                                    val data = apkBytes ?: return@OutlinedButton
                                    scanning = true
                                    stateText = "扫描类列表中…（服务端会再上传一次同一 APK）"
                                    scope.launch {
                                        client.scan(data)
                                            .onSuccess { list ->
                                                classes = list
                                                selectedObf = emptySet()
                                                selectedDex = emptySet()
                                                stateText = "扫描完成：共 ${list.size} 个类可勾选"
                                            }
                                            .onFailure { stateText = "扫描失败：${it.message}" }
                                        scanning = false
                                    }
                                },
                            ) { Text(if (scanning) "扫描中…" else "扫描类", maxLines = 1) }
                            if (classes.isNotEmpty()) {
                                Text(
                                    "已选 混淆 ${selectedObf.size} / 抽取 ${selectedDex.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (classes.isNotEmpty()) {
                items(classes.take(200)) { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "方法 ${c.methods} · ${c.size / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(
                                checked = c.descriptor in selectedObf,
                                enabled = c.obfuscatable && !busy,
                                onCheckedChange = { on ->
                                    selectedObf = if (on) selectedObf + c.descriptor else selectedObf - c.descriptor
                                },
                            )
                            Text("混淆", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(
                                checked = c.descriptor in selectedDex,
                                enabled = c.dex2cOk && !busy,
                                onCheckedChange = { on ->
                                    selectedDex = if (on) selectedDex + c.descriptor else selectedDex - c.descriptor
                                },
                            )
                            Text("抽取", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = !busy && apkBytes != null,
                        onClick = {
                            val data = apkBytes ?: return@Button
                            busy = true
                            logs = emptyList()
                            resultBytes = null
                            savedPath = null
                            stateText = "上传中…（大包可能要几十秒）"
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
                                    .onSuccess { id ->
                                        jobId = id
                                        stateText = "已提交（$id），等待服务器处理…"
                                    }
                                    .onFailure { e ->
                                        busy = false
                                        stateText = "提交失败：${e.message}"
                                    }
                            }
                        },
                    ) { Text(if (busy) "处理中…" else "提交加固", maxLines = 1) }

                    OutlinedButton(
                        enabled = resultBytes != null,
                        onClick = {
                            val data = resultBytes ?: return@OutlinedButton
                            val dir = File(context.cacheDir, "harden-out").apply { mkdirs() }
                            val base = (apkName ?: "app.apk").substringBeforeLast(".")
                            val f = File(dir, "$base-hardened.apk")
                            runCatching { f.writeBytes(data) }
                                .onSuccess {
                                    savedPath = f.absolutePath
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("path", f.absolutePath))
                                }
                                .onFailure { stateText = "保存失败：${it.message}" }
                        },
                    ) { Text("下载加固包", maxLines = 1) }

                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (stateText.isNotBlank()) {
                item {
                    Text(
                        stateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            savedPath?.let { path ->
                item {
                    Text(
                        "已保存（路径已复制）：$path",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (logs.isNotEmpty()) {
                item {
                    Text(
                        "服务器日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(logs) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
