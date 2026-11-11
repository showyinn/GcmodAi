package com.gcmodai.app.data.binary

import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

/**
 * 二进制分析总入口：识别格式 → 分发到对应解析器 → 汇总成统一报告。
 *
 * 加壳/加密判断只依据客观事实（熵、节区名、符号数量、魔数），不做猜测性结论；
 * 语义层面的判断（这段代码在干什么、有没有恶意）交给 AI 在报告之上推理。
 */
object BinaryAnalyser {

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun detect(r: ByteReader): BinaryFormat = when {
        ElfParser.isElf(r) -> BinaryFormat.ELF
        DexParser.isDex(r) -> BinaryFormat.DEX
        r.matches(0, ByteReader.ZIP_MAGIC) -> BinaryFormat.ZIP
        r.matches(0, ByteReader.PE_MAGIC) -> BinaryFormat.PE
        isMachO(r) -> BinaryFormat.MACHO
        else -> BinaryFormat.UNKNOWN
    }

    private fun isMachO(r: ByteReader): Boolean {
        if (r.size < 8) return false
        val m = r.u32(0, false)
        return m == 0xFEEDFACEL || m == 0xFEEDFACFL || m == 0xCAFEBABEL || m == 0xCFFAEDFEL
    }

    fun analyse(data: ByteArray, fileName: String): BinaryReport {
        val r = ByteReader(data)
        val hash = sha256(data)
        val format = detect(r)
        val base = when (format) {
            BinaryFormat.ELF -> ElfParser.parse(r, fileName, hash)
            BinaryFormat.DEX -> dexReport(data, fileName, hash)
            BinaryFormat.ZIP -> ApkInspector.inspect(data, fileName, hash)
            BinaryFormat.PE -> peReport(r, fileName, hash)
            BinaryFormat.MACHO -> machOReport(r, fileName, hash)
            BinaryFormat.UNKNOWN -> null
        }
        val report = base ?: BinaryReport(
            fileName = fileName,
            sizeBytes = data.size.toLong(),
            sha256 = hash,
            format = BinaryFormat.UNKNOWN,
            arch = "未知",
            bits = 8,
            endian = "-",
            overallEntropy = ByteTools.entropy(data, 0, minOf(data.size, 1024 * 1024)),
            strings = ByteTools.asciiStrings(data, limit = 200),
            summary = "无法识别的格式（前 16 字节：" + ByteTools.hex(data, 16) + "）",
            notes = listOf("不是 ELF/PE/Mach-O/DEX/APK。如果是加密过的文件，先走「XOR 解密」再分析。"),
        )
        return withPackerHints(report)
    }

    // ---------------------------------------------------------------- DEX
    private fun dexReport(data: ByteArray, fileName: String, hash: String): BinaryReport {
        val r = ByteReader(data)
        val (classes, strings) = DexParser.parse(r)
        val version = DexParser.dexVersion(r)
        val methodCount = classes.sumOf { it.methods.size }
        return BinaryReport(
            fileName = fileName,
            sizeBytes = data.size.toLong(),
            sha256 = hash,
            format = BinaryFormat.DEX,
            arch = "Dalvik/ART",
            bits = 32,
            endian = "小端",
            summary = "DEX $version，类 ${classes.size}，方法 $methodCount，字符串 ${strings.size}",
            dexClasses = classes,
            strings = strings.take(500),
            overallEntropy = ByteTools.entropy(data, 0, minOf(data.size, 1024 * 1024)),
        )
    }

    // ---------------------------------------------------------------- PE
    private fun peReport(r: ByteReader, fileName: String, hash: String): BinaryReport {
        val notes = ArrayList<String>()
        val peOff = r.u32(0x3C, true).toInt()
        if (peOff <= 0 || peOff + 24 > r.size || !r.matches(peOff, byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0))) {
            return BinaryReport(
                fileName, r.size.toLong(), hash, BinaryFormat.PE, "未知", 32, "小端",
                summary = "MZ 头存在但 PE 头解析失败", notes = listOf("可能被截断或加了壳"),
            )
        }
        val machine = r.u16(peOff + 4, true)
        val sectionCount = r.u16(peOff + 6, true)
        val optSize = r.u16(peOff + 20, true)
        val optStart = peOff + 24
        val magic = r.u16(optStart, true)
        val is64 = magic == 0x20B
        val arch = when (machine) {
            0x014C -> "x86"
            0x8664 -> "x86-64"
            0xAA64 -> "ARM64"
            0x01C4 -> "ARM"
            else -> "0x%X".format(machine)
        }
        val entryRva = if (is64) r.u32(optStart + 16, true) else r.u32(optStart + 16, true)

        val sections = ArrayList<SectionInfo>()
        val secStart = optStart + optSize
        data class Sec(val name: String, val va: Long, val rawSize: Long, val rawPtr: Long)
        val secs = ArrayList<Sec>()
        for (i in 0 until minOf(sectionCount, 96)) {
            val base = secStart + i * 40
            if (base + 40 > r.size) break
            val name = r.ascii(base, 8).trimEnd('\u0000', ' ')
            val vsize = r.u32(base + 8, true)
            val va = r.u32(base + 12, true)
            val rawSize = r.u32(base + 16, true)
            val rawPtr = r.u32(base + 20, true)
            secs.add(Sec(name, va, rawSize, rawPtr))
            sections.add(
                SectionInfo(
                    name = name.ifBlank { "<unnamed>" },
                    typeName = "PE_SECTION",
                    addr = va,
                    offset = rawPtr,
                    size = rawSize,
                    flags = if (vsize > 0) "vsize=0x${java.lang.Long.toHexString(vsize)}" else "-",
                    entropy = if (rawSize in 1 until Int.MAX_VALUE) ByteTools.entropy(r.raw(), rawPtr.toInt(), minOf(rawSize, 2L * 1024 * 1024).toInt()) else 0.0,
                )
            )
        }

        // 导入表（DLL 名 + 函数名）
        val imported = ArrayList<SymbolInfo>()
        val libs = ArrayList<String>()
        runCatching {
            val ddOff = if (is64) optStart + 112 else optStart + 96
            val importRva = r.u32(ddOff + 8, true)
            if (importRva > 0) {
                val sec = secs.firstOrNull { importRva >= it.va && importRva < it.va + maxOf(it.rawSize, 1) }
                if (sec != null) {
                    var p = (sec.rawPtr + (importRva - sec.va)).toInt()
                    var guard = 0
                    while (p + 20 <= r.size && guard++ < 64) {
                        val nameRva = r.u32(p + 12, true)
                        if (nameRva == 0L) break
                        val nsec = secs.firstOrNull { nameRva >= it.va && nameRva < it.va + maxOf(it.rawSize, 1) }
                        if (nsec != null) {
                            val dll = r.cString((nsec.rawPtr + (nameRva - nsec.va)).toInt())
                            if (dll.isNotBlank()) libs.add(dll)
                        }
                        p += 20
                    }
                }
            }
        }.onFailure { notes.add("导入表解析失败：${it.message}") }
        libs.distinct().forEach { imported.add(SymbolInfo("$it!*", 0, 0, "IMPORT", "DLL")) }

        return BinaryReport(
            fileName = fileName,
            sizeBytes = r.size.toLong(),
            sha256 = hash,
            format = BinaryFormat.PE,
            arch = arch,
            bits = if (is64) 64 else 32,
            endian = "小端",
            entryPoint = entryRva,
            summary = "PE，$arch，节区 ${sections.size}，依赖 ${libs.size} 个 DLL",
            sections = sections,
            neededLibs = libs.distinct(),
            importedSymbols = imported,
            strings = ByteTools.asciiStrings(r.raw(), limit = 400),
            overallEntropy = ByteTools.entropy(r.raw(), 0, minOf(r.size, 2 * 1024 * 1024)),
            notes = notes,
        )
    }

    // ---------------------------------------------------------------- Mach-O
    private fun machOReport(r: ByteReader, fileName: String, hash: String): BinaryReport {
        val magic = r.u32(0, false)
        val is64 = magic == 0xFEEDFACFL || magic == 0xCFFAEDFEL
        val cputype = r.u32(4, true).toInt()
        val arch = when (cputype) {
            7 -> "x86"
            0x01000007 -> "x86-64"
            12 -> "ARM"
            0x0100000C -> "ARM64"
            else -> "0x%X".format(cputype)
        }
        return BinaryReport(
            fileName = fileName,
            sizeBytes = r.size.toLong(),
            sha256 = hash,
            format = BinaryFormat.MACHO,
            arch = arch,
            bits = if (is64) 64 else 32,
            endian = "小端",
            summary = "Mach-O，$arch",
            strings = ByteTools.asciiStrings(r.raw(), limit = 300),
            overallEntropy = ByteTools.entropy(r.raw(), 0, minOf(r.size, 1024 * 1024)),
            notes = listOf("Mach-O 目前只识别头部与架构；函数级分析请结合 iOS 侧的符号表或直接交给 AI 结合字符串判断。"),
        )
    }

    // ---------------------------------------------------------------- 加壳提示
    private fun withPackerHints(report: BinaryReport): BinaryReport {
        val hints = ArrayList<String>()
        val data = report.strings.joinToString("\n")
        if (report.format == BinaryFormat.ELF) {
            if (data.contains("UPX!")) hints.add("检测到 `UPX!` 标记：很可能被 UPX 压缩壳处理过")
            report.sections.filter { it.name.startsWith(".upx") }.forEach { hints.add("存在节区 `${it.name}`（UPX 特征）") }
            if (report.overallEntropy >= 7.5) hints.add("整体熵 %.2f 偏高：可能被加密或压缩".format(report.overallEntropy))
            val high = report.highEntropySections(7.5)
            if (high.isNotEmpty()) hints.add("高熵节区：${high.joinToString("、") { it.name }}")
            if (report.exportedSymbols.isEmpty() && report.sections.any { it.name == ".text" }) {
                hints.add("有 .text 段但没有任何导出符号：符表可能被裁剪，或加了壳")
            }
            if (report.neededLibs.isEmpty() && report.sections.isNotEmpty()) {
                hints.add("没有 DT_NEEDED 依赖：静态链接或动态段被破坏")
            }
            val textSection = report.sections.firstOrNull { it.name == ".text" }
            if (textSection != null && textSection.entropy >= 7.4) {
                hints.add(".text 段熵 %.2f：代码段被加密的可能性较大".format(textSection.entropy))
            }
        }
        if (report.format == BinaryFormat.DEX) {
            if (report.dexClasses.isEmpty()) hints.add("解析出的类为 0：可能是壳的 dex（真正的 dex 在运行时释放）")
        }
        if (report.format == BinaryFormat.ZIP) {
            val soCount = report.apkEntries.count { it.path.endsWith(".so") }
            if (soCount > 0) hints.add("包含 $soCount 个 .so 原生库，可逐个解密/反汇编分析")
            val dexCount = report.apkEntries.count { it.path.matches(Regex("classes\\d*\\.dex")) }
            if (dexCount > 1) hints.add("包含 $dexCount 个 dex（多 dex 或壳的形态）")
        }
        return if (hints.isEmpty()) report else report.copy(packerHints = hints)
    }
}
