package com.gcmodai.app.ui.pages.binary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcmodai.app.Screen
import com.gcmodai.app.data.binary.Arm64Lite
import com.gcmodai.app.data.binary.BinaryAnalyser
import com.gcmodai.app.data.binary.BinaryEditor
import com.gcmodai.app.data.binary.BinaryFormat
import com.gcmodai.app.data.binary.BinaryReport
import com.gcmodai.app.data.binary.ByteTools
import com.gcmodai.app.data.binary.DisasmLine
import com.gcmodai.app.data.binary.X86Lite
import com.gcmodai.app.data.binary.XorDecryptor
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.theme.CustomColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

private const val MAX_FILE_BYTES = 96L * 1024 * 1024

private enum class BinaryTab(val label: String) {
    Overview("概览"),
    Sections("节区"),
    Symbols("符号"),
    Strings("字符串"),
    Classes("类/方法"),
    Entries("包内文件"),
    Disasm("反汇编"),
}

/**
 * 二进制逆向工作台：SO / 可执行 / DEX / APK 的解密、结构解析、反汇编与 AI 交接。
 *
 * 全部在设备本地完成，不联网、不上传——用户拿到的 .so 往往是自己的商业包，
 * 这一点比「分析得多全」更重要。分析结果可以一键生成 Markdown 交给对话里的模型。
 */
@Composable
fun BinaryToolPage() {
    val context = LocalContext.current
    val navController = com.gcmodai.app.ui.context.LocalNavController.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf<String?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var report by remember { mutableStateOf<BinaryReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(BinaryTab.Overview) }
    var autoKey by remember { mutableStateOf<XorDecryptor.XorKey?>(null) }
    var manualKey by remember { mutableStateOf("") }
    var sectionKey by remember { mutableStateOf<XorDecryptor.SingleByteResult?>(null) }
    var sectionTarget by remember { mutableStateOf<com.gcmodai.app.data.binary.SectionInfo?>(null) }
    var disasm by remember { mutableStateOf<List<DisasmLine>>(emptyList()) }
    var patchOffset by remember { mutableStateOf("") }
    var patchBytes by remember { mutableStateOf("") }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }

    fun toast(msg: String) {
        status = msg
    }

    fun analyse(name: String, data: ByteArray) {
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) { BinaryAnalyser.analyse(data, name) }
            }.onSuccess { r ->
                report = r
                disasm = emptyList()
                tab = BinaryTab.Overview
                autoKey = null
                toast("分析完成：${r.format.label}")
            }.onFailure { toast("分析失败：${it.message}") }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    } ?: "binary.bin"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("无法打开文件")
                    val data = input.use { it.readBytes() }
                    name to data
                }
            }.onSuccess { (name, data) ->
                if (data.size > MAX_FILE_BYTES) {
                    toast("文件超过 96MB，只分析前 96MB")
                }
                val clipped = if (data.size > MAX_FILE_BYTES) data.copyOf(MAX_FILE_BYTES.toInt()) else data
                fileName = name
                bytes = clipped
                analyse(name, clipped)
            }.onFailure { toast("读取失败：${it.message}"); busy = false }
        }
    }

    // 进页自动拉起文件选择：从附件面板的「SO 分析」快捷入口进来时少一步操作。
    // 只在首次进入且还没选过文件时触发，避免每次返回页面都弹选择器。
    var autoPickOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoPickOnce && bytes == null) {
            autoPickOnce = true
            runCatching { picker.launch(arrayOf("*/*")) }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("二进制 / SO 分析") },
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
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fileName ?: "未选择文件",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        bytes?.let {
                            Text(
                                "${ByteTools.humanSize(it.size.toLong())} · ${report?.format?.label ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // FlowRow：窄屏（小屏/大字体）下按钮自动换行，不会把「交给 AI 分析」挤到屏幕外
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                                Text("选择文件", maxLines = 1)
                            }
                            if (report != null) {
                                OutlinedButton(onClick = {
                                    val markdown = report!!.toMarkdown()
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("binary-report", markdown))
                                    toast("报告已复制到剪贴板")
                                }) { Text("复制报告", maxLines = 1) }
                                Button(onClick = {
                                    val markdown = report!!.toMarkdown()
                                    navController.navigate(
                                        Screen.Chat(Uuid.random().toString(), text = markdown)
                                    )
                                }) { Text("交给 AI 分析", maxLines = 1) }
                            }
                        }
                        if (status.isNotBlank()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            val r = report
            if (r == null) {
                item {
                    Text(
                        "支持：.so / ELF 可执行、PE(.exe/.dll)、Mach-O、DEX、APK。\n" +
                            "全部在本机解析，不上传任何文件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }

            // ---- 解密 ----
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("解密（循环 XOR · 已知明文）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(enabled = !busy && bytes != null, onClick = {
                                val data = bytes ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    val key = withContext(Dispatchers.Default) { XorDecryptor.recoverKey(data) }
                                    autoKey = key
                                    toast(if (key == null) "未找到可用的 XOR 密钥（可能不是 XOR，或已被强加密）"
                                    else "命中：${key.magic}，密钥长度 ${key.keyLength} 字节")
                                    busy = false
                                }
                            }) { Text("自动试解密") }
                            OutlinedButton(enabled = autoKey != null && !busy, onClick = {
                                val data = bytes ?: return@OutlinedButton
                                val key = autoKey ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    val decrypted = withContext(Dispatchers.Default) { XorDecryptor.apply(data, key.key) }
                                    bytes = decrypted
                                    autoKey = null
                                    analyse(fileName ?: "decrypted.bin", decrypted)
                                }
                            }) { Text("应用解密") }
                        }
                        autoKey?.let {
                            Text(
                                "密钥（HEX）：${it.hex}\n密钥（文本）：${it.asText()}\n解出格式：${it.magic}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualKey,
                                onValueChange = { manualKey = it },
                                label = { Text("手动密钥（HEX，如 1A2B3C）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(enabled = manualKey.isNotBlank() && !busy, onClick = {
                                val data = bytes ?: return@OutlinedButton
                                val key = XorDecryptor.parseKey(manualKey)
                                if (key == null || key.isEmpty()) {
                                    toast("密钥格式不对")
                                    return@OutlinedButton
                                }
                                busy = true
                                scope.launch {
                                    val out = withContext(Dispatchers.Default) { XorDecryptor.apply(data, key) }
                                    bytes = out
                                    analyse(fileName ?: "decrypted.bin", out)
                                }
                            }) { Text("应用") }
                        }

                        // ---- 节区级探测：整文件没有 XOR 迹象时，问题往往只出在某个节区 ----
                        Text(
                            "整文件找不到密钥时，多半是「只对某个节区加密」（常见于 SO 混淆与加固）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(enabled = !busy && bytes != null, onClick = {
                                val data = bytes ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.Default) {
                                            r.sections
                                                .filter { it.size >= 256 && it.entropy >= 6.5 }
                                                .sortedByDescending { it.entropy }
                                                .firstNotNullOfOrNull { sec ->
                                                    val off = sec.offset.toInt()
                                                    if (off < 0 || off >= data.size) return@firstNotNullOfOrNull null
                                                    val len = minOf(
                                                        sec.size,
                                                        (data.size - off).toLong(),
                                                        200_000L,
                                                    ).toInt()
                                                    if (len < 64) return@firstNotNullOfOrNull null
                                                    XorDecryptor.bruteForceSingleByte(data.copyOfRange(off, off + len))
                                                        ?.let { it to sec }
                                                }
                                        }
                                    }.onSuccess { found ->
                                        if (found == null) {
                                            sectionKey = null
                                            sectionTarget = null
                                            toast("各节区都没有单字节 XOR 特征：更可能是多字节/流加密或厂商加固（壳）")
                                        } else {
                                            sectionKey = found.first
                                            sectionTarget = found.second
                                            toast(
                                                "节区 `${found.second.name}` 候选密钥 0x${found.first.keyHex}" +
                                                    "（熵 %.2f → %.2f）%s".format(
                                                        found.first.entropyBefore,
                                                        found.first.entropyAfter,
                                                        if (found.first.hint.isNotEmpty()) "，解出 ${found.first.hint}" else "",
                                                    )
                                            )
                                        }
                                    }.onFailure { toast("节区探测失败：${it.message}") }
                                    busy = false
                                }
                            }) { Text("节区暴力试解密") }

                            OutlinedButton(
                                enabled = sectionKey != null && sectionTarget != null && !busy,
                                onClick = {
                                    val data = bytes ?: return@OutlinedButton
                                    val key = sectionKey ?: return@OutlinedButton
                                    val sec = sectionTarget ?: return@OutlinedButton
                                    val off = sec.offset.toInt()
                                    val len = minOf(sec.size, (data.size - off).toLong()).toInt()
                                    val out = data.copyOf()
                                    for (i in 0 until len) {
                                        out[off + i] = (out[off + i].toInt() xor key.key).toByte()
                                    }
                                    bytes = out
                                    toast("已对节区 `${sec.name}` 应用密钥 0x${key.keyHex}")
                                    analyse(fileName ?: "decrypted.bin", out)
                                },
                            ) { Text("应用到该节区") }
                        }
                        sectionKey?.let {
                            Text(
                                "节区 ${sectionTarget?.name}：密钥 0x${it.keyHex}，熵 %.2f → %.2f%s".format(
                                    it.entropyBefore,
                                    it.entropyAfter,
                                    if (it.hint.isNotEmpty()) "（解出 ${it.hint}）" else "",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            // ---- 编辑 ----
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("编辑并另存", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = patchOffset, onValueChange = { patchOffset = it },
                                label = { Text("偏移(HEX)") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = patchBytes, onValueChange = { patchBytes = it },
                                label = { Text("新字节(HEX)") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedButton(enabled = patchOffset.isNotBlank() && patchBytes.isNotBlank() && !busy, onClick = {
                            val data = bytes ?: return@OutlinedButton
                            val off = patchOffset.trim().removePrefix("0x").toIntOrNull(16)
                            val repl = XorDecryptor.parseKey(patchBytes)
                            if (off == null || repl == null) {
                                toast("偏移或字节格式不对")
                                return@OutlinedButton
                            }
                            runCatching { BinaryEditor.patchAt(data, off, repl) }
                                .onSuccess { res ->
                                    bytes = res.data
                                    saveAndReport(context, res.data, fileName ?: "patched.bin", res.description) { msg ->
                                        toast(msg)
                                        analyse(fileName ?: "patched.bin", res.data)
                                    }
                                }
                                .onFailure { toast("写入失败：${it.message}") }
                        }) { Text("写入字节") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = findText, onValueChange = { findText = it },
                                label = { Text("查找") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = replaceText, onValueChange = { replaceText = it },
                                label = { Text("替换(等长)") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedButton(
                            enabled = findText.isNotBlank() && findText.length == replaceText.length && !busy,
                            onClick = {
                                val data = bytes ?: return@OutlinedButton
                                runCatching { BinaryEditor.replaceString(data, findText, replaceText) }
                                    .onSuccess { res ->
                                        if (res.changedBytes == 0) {
                                            toast("没有找到 \"$findText\"")
                                        } else {
                                            bytes = res.data
                                            saveAndReport(context, res.data, fileName ?: "edited.bin", res.description) { msg ->
                                                toast(msg)
                                                analyse(fileName ?: "edited.bin", res.data)
                                            }
                                        }
                                    }
                                    .onFailure { toast("替换失败：${it.message}") }
                            },
                        ) { Text("替换并另存") }
                    }
                }
            }

            // ---- Tab 切换 ----
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BinaryTab.entries.forEach { t ->
                        val visible = when (t) {
                            BinaryTab.Classes -> r.dexClasses.isNotEmpty()
                            BinaryTab.Entries -> r.apkEntries.isNotEmpty()
                            BinaryTab.Symbols -> r.exportedSymbols.isNotEmpty() || r.importedSymbols.isNotEmpty()
                            BinaryTab.Disasm -> r.format == BinaryFormat.ELF || r.format == BinaryFormat.PE
                            else -> true
                        }
                        if (visible) {
                            FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                        }
                    }
                }
            }

            // ---- 内容区 ----
            when (tab) {
                BinaryTab.Overview -> {
                    item { OverviewCard(r) }
                    if (r.packerHints.isNotEmpty()) {
                        item {
                            Card {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("加壳 / 加密迹象", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    r.packerHints.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }

                BinaryTab.Sections -> items(r.sections) { s ->
                    MonoRow(
                        left = s.name,
                        right = "${s.typeName} ${s.flags}  0x${java.lang.Long.toHexString(s.addr)}  " +
                            "${ByteTools.humanSize(s.size)}  熵 %.2f".format(s.entropy),
                    )
                }

                BinaryTab.Symbols -> {
                    items(r.exportedSymbols.take(400)) { sym ->
                        MonoRow(left = sym.name, right = "导出 0x${java.lang.Long.toHexString(sym.value)} ${sym.type}")
                    }
                    items(r.importedSymbols.take(400)) { sym ->
                        MonoRow(left = sym.name, right = "导入 ${sym.type}")
                    }
                }

                // 字符串页：base64/URL 这类超长不换行串在窄屏上会溢出色块，
                // 这里限制 3 行并省略，完整内容仍可从「复制报告」导出
                BinaryTab.Strings -> items(r.strings.take(400)) { s ->
                    Text(
                        s,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                BinaryTab.Classes -> items(r.dexClasses.take(300)) { c ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                c.descriptor.replace('/', '.'),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${c.accessFlags} · 方法 ${c.methods.size} · 字段 ${c.fields.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            c.methods.take(10).forEach {
                                Text(
                                    "  $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                BinaryTab.Entries -> items(r.apkEntries.take(400)) { e ->
                    MonoRow(left = e.path, right = ByteTools.humanSize(e.sizeBytes))
                }

                BinaryTab.Disasm -> {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = {
                                val data = bytes ?: return@Button
                                busy = true
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.Default) {
                                            // 取代码段：优先 .text，其次任何可执行节区
                                            val text = r.sections.firstOrNull { it.name == ".text" }
                                                ?: r.sections.firstOrNull { it.flags.contains("X") }
                                            val base = text?.addr ?: (r.entryPoint ?: 0L)
                                            val start = (text?.offset ?: 0L)
                                                .coerceIn(0L, (data.size - 1).toLong().coerceAtLeast(0L))
                                                .toInt()
                                            // 关键：节区的 size 是「节区内大小」，可能大于文件中剩余字节，
                                            // 不夹取边界会直接 copyOfRange 越界崩溃
                                            val len = minOf(
                                                text?.size ?: (data.size - start).toLong(),
                                                (data.size - start).toLong(),
                                                64L * 1024,
                                            ).toInt()
                                            val code = if (len > 0) data.copyOfRange(start, start + len) else ByteArray(0)
                                            when {
                                                r.arch.contains("x86", ignoreCase = true) ->
                                                    X86Lite.disassemble(code, base, maxLines = 600)
                                                else ->
                                                    Arm64Lite.disassemble(code, base, maxLines = 600)
                                            }
                                        }
                                    }.onSuccess { lines ->
                                        disasm = lines
                                        toast(
                                            when {
                                                r.arch.contains("x86", true) && lines.isNotEmpty() ->
                                                    "已按 x86-64 轻量反汇编 ${lines.size} 条"
                                                lines.isNotEmpty() ->
                                                    "已按 AArch64 轻量反汇编 ${lines.size} 条"
                                                else -> "该文件没有可反汇编的代码段（可能是加壳的壳代码或纯数据）"
                                            }
                                        )
                                    }.onFailure { toast("反汇编失败：${it.message}") }
                                    busy = false
                                }
                            }) { Text("反汇编代码段") }
                        }
                        if (disasm.isNotEmpty()) {
                            Text(
                                "当前架构：${r.arch}（未识别指令以 .byte/.word 保留原始编码，不会错位）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(disasm) { line ->
                        Text(
                            "0x${java.lang.Long.toHexString(line.address)}  %08X  %s".format(line.word, line.text),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(report: BinaryReport) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoLine("文件", report.fileName)
            InfoLine("大小", ByteTools.humanSize(report.sizeBytes))
            InfoLine("格式", report.format.label)
            InfoLine("架构", "${report.arch} / ${report.bits} 位 / ${report.endian}")
            report.entryPoint?.let { InfoLine("入口", "0x" + java.lang.Long.toHexString(it)) }
            InfoLine("整体熵", "%.2f / 8.00".format(report.overallEntropy))
            InfoLine("SHA256", report.sha256)
            if (report.summary.isNotBlank()) InfoLine("概要", report.summary)
            if (report.neededLibs.isNotEmpty()) InfoLine("依赖库", report.neededLibs.joinToString(" "))
            report.soname?.let { InfoLine("SONAME", it) }
            report.manifest?.let { m ->
                InfoLine("包名", m.packageName)
                InfoLine("版本", "${m.versionName} (${m.versionCode}) · minSdk ${m.minSdk} / targetSdk ${m.targetSdk}")
                if (m.permissions.isNotEmpty()) InfoLine("权限", "${m.permissions.size} 项")
                if (m.hasDebuggable) InfoLine("⚠️", "application 标记了 debuggable=true")
            }
            report.notes.forEach { InfoLine("备注", it) }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            // softWrap：SHA256、依赖库列表这类长值在窄屏上换行，而不是被截断
            softWrap = true,
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Composable
private fun MonoRow(left: String, right: String) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                left,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.58f),
            )
            Text(
                right,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.42f),
            )
        }
    }
}

/** 把编辑结果写到应用私有目录，并提示路径（可用分享面板导出）。 */
private fun saveAndReport(
    context: Context,
    data: ByteArray,
    name: String,
    description: String,
    done: (String) -> Unit,
) {
    runCatching {
        val dir = File(context.cacheDir, "binary-out").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "out-$safe")
        file.writeBytes(data)
        done("$description\n已另存：${file.absolutePath}")
    }.onFailure { done("保存失败：${it.message}") }
}
