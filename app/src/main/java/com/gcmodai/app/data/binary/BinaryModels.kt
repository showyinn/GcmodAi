package com.gcmodai.app.data.binary

/** 文件格式。 */
enum class BinaryFormat(val label: String) {
    ELF("ELF（Android/Linux 可执行与 SO）"),
    PE("PE（Windows 可执行）"),
    MACHO("Mach-O（iOS/macOS）"),
    DEX("DEX（Android 字节码）"),
    ZIP("ZIP/APK（归档）"),
    UNKNOWN("未知二进制"),
}

/** 统一分析结果：所有解析器都收敛到这里，UI 与 AI 报告只认这一种结构。 */
data class BinaryReport(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: BinaryFormat,
    val arch: String,
    val bits: Int,
    val endian: String,
    val entryPoint: Long? = null,
    /** 一句话概括：例如「AArch64 动态库，导出 128 个函数，熵 6.1」 */
    val summary: String = "",
    val sections: List<SectionInfo> = emptyList(),
    val segments: List<SegmentInfo> = emptyList(),
    val neededLibs: List<String> = emptyList(),
    val soname: String? = null,
    val exportedSymbols: List<SymbolInfo> = emptyList(),
    val importedSymbols: List<SymbolInfo> = emptyList(),
    val strings: List<String> = emptyList(),
    val packerHints: List<String> = emptyList(),
    val overallEntropy: Double = 0.0,
    /** DEX/APK 专用 */
    val dexClasses: List<DexClassInfo> = emptyList(),
    val apkEntries: List<ApkEntry> = emptyList(),
    val manifest: ApkManifest? = null,
    val notes: List<String> = emptyList(),
) {
    val isElf: Boolean get() = format == BinaryFormat.ELF
    val isDex: Boolean get() = format == BinaryFormat.DEX
    val isApk: Boolean get() = format == BinaryFormat.ZIP

    /** 高熵节区（可能被加密/加壳）。 */
    fun highEntropySections(threshold: Double = 7.2): List<SectionInfo> =
        sections.filter { it.size >= 256 && it.entropy >= threshold }

    /**
     * 渲染成 Markdown 报告，供「交给 AI」使用。
     *
     * 为什么要压成文本而不是直接把二进制丢给模型：模型的上下文是文本，
     * 结构化的符号/节区/指令骨架才是它能推理的东西；原始字节只会浪费 token。
     */
    fun toMarkdown(maxStrings: Int = 120, maxSymbols: Int = 200): String = buildString {
        appendLine("# 二进制分析报告：$fileName")
        appendLine()
        appendLine("- 大小：${ByteTools.humanSize(sizeBytes)}（$sizeBytes 字节）")
        appendLine("- SHA256：`$sha256`")
        appendLine("- 格式：${format.label}")
        appendLine("- 架构：$arch / ${bits} 位 / $endian 端序")
        entryPoint?.let { appendLine("- 入口点：0x${java.lang.Long.toHexString(it)}") }
        appendLine("- 整体熵：%.2f / 8.00".format(overallEntropy))
        if (summary.isNotBlank()) appendLine("- 概要：$summary")
        if (packerHints.isNotEmpty()) {
            appendLine()
            appendLine("## 加壳 / 加密迹象")
            packerHints.forEach { appendLine("- $it") }
        }
        if (highEntropySections().isNotEmpty()) {
            appendLine()
            appendLine("## 高熵节区（可能是加密数据或压缩壳）")
            highEntropySections().forEach {
                appendLine("- ${it.name}：${ByteTools.humanSize(it.size.toLong())}，熵 %.2f".format(it.entropy))
            }
        }
        if (neededLibs.isNotEmpty()) {
            appendLine()
            appendLine("## 依赖库")
            neededLibs.forEach { appendLine("- $it") }
        }
        if (soname != null) {
            appendLine()
            appendLine("- SONAME：`$soname`")
        }
        if (exportedSymbols.isNotEmpty()) {
            appendLine()
            appendLine("## 导出符号（前 ${minOf(maxSymbols, exportedSymbols.size)} / 共 ${exportedSymbols.size}）")
            exportedSymbols.take(maxSymbols).forEach { appendLine("- `${it.name}` @0x${java.lang.Long.toHexString(it.value)}") }
        }
        if (importedSymbols.isNotEmpty()) {
            appendLine()
            appendLine("## 导入符号（前 ${minOf(maxSymbols, importedSymbols.size)} / 共 ${importedSymbols.size}）")
            importedSymbols.take(maxSymbols).forEach { appendLine("- `${it.name}`") }
        }
        if (sections.isNotEmpty()) {
            appendLine()
            appendLine("## 节区")
            appendLine("| 名称 | 类型 | 地址 | 大小 | 熵 |")
            appendLine("|---|---|---|---|---|")
            sections.take(40).forEach {
                appendLine("| ${it.name} | ${it.typeName} | 0x${java.lang.Long.toHexString(it.addr)} | " +
                    "${ByteTools.humanSize(it.size.toLong())} | %.2f |".format(it.entropy))
            }
        }
        if (dexClasses.isNotEmpty()) {
            appendLine()
            appendLine("## DEX 类（前 60 / 共 ${dexClasses.size}）")
            dexClasses.take(60).forEach { c ->
                appendLine("- `${c.descriptor.replace('/', '.')}`（${c.accessFlags}，方法 ${c.methods.size}，字段 ${c.fields.size}）")
                c.methods.take(8).forEach { appendLine("    - `$it`") }
            }
        }
        if (apkEntries.isNotEmpty()) {
            appendLine()
            appendLine("## APK 内容")
            apkEntries.take(60).forEach { appendLine("- ${it.path}（${ByteTools.humanSize(it.sizeBytes)}）") }
        }
        manifest?.let { m ->
            appendLine()
            appendLine("## AndroidManifest 摘要")
            appendLine("- package：`${m.packageName}`")
            appendLine("- versionName：`${m.versionName}` / versionCode：`${m.versionCode}`")
            appendLine("- minSdk：${m.minSdk} / targetSdk：${m.targetSdk}")
            if (m.permissions.isNotEmpty()) {
                appendLine("- 权限（${m.permissions.size}）：")
                m.permissions.take(40).forEach { appendLine("    - $it") }
            }
            if (m.hasDebuggable) appendLine("- ⚠️ application 标记了 `android:debuggable=\"true\"`")
            if (m.exportedComponents.isNotEmpty()) {
                appendLine("- 对外暴露组件：")
                m.exportedComponents.take(30).forEach { appendLine("    - $it") }
            }
        }
        if (strings.isNotEmpty()) {
            appendLine()
            appendLine("## 字符串样本（前 ${minOf(maxStrings, strings.size)} / 共 ${strings.size}）")
            strings.take(maxStrings).forEach { appendLine("- `$it`") }
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("## 备注")
            notes.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("---")
        appendLine("以上是客户端解析出的结构信息，请据此判断：该文件是否被加壳/加密、关键函数在做什么、" +
            "有没有可疑行为（外联、动态加载、反调试、隐私采集），并给出下一步分析建议。")
    }
}

data class SectionInfo(
    val name: String,
    val typeName: String,
    val addr: Long,
    val offset: Long,
    val size: Long,
    val flags: String,
    val entropy: Double,
)

data class SegmentInfo(
    val typeName: String,
    val offset: Long,
    val vaddr: Long,
    val fileSize: Long,
    val memSize: Long,
    val flags: String,
)

data class SymbolInfo(val name: String, val value: Long, val size: Long, val bind: String, val type: String)

data class DexClassInfo(
    val descriptor: String,
    val accessFlags: String,
    val methods: List<String>,
    val fields: List<String>,
)

data class ApkEntry(val path: String, val sizeBytes: Long, val compressedBytes: Long)

data class ApkManifest(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val exportedComponents: List<String>,
    val hasDebuggable: Boolean,
)
