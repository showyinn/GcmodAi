package com.gcmodai.app.data.binary

/**
 * ELF（.so / 可执行）结构解析。
 *
 * 只做「读结构」这一件事，不猜测语义：
 * 头 → 程序头 → 节头 → 动态符号/依赖库 → 逐节熵。
 * 加密/加壳的判断留给 [BinaryAnalyser] 基于这些事实去做。
 */
object ElfParser {

    private const val SHT_SYMTAB = 2
    private const val SHT_STRTAB = 3
    private const val SHT_DYNAMIC = 6
    private const val SHT_DYNSYM = 11

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2

    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L
    private const val DT_SONAME = 14L

    private fun machineName(m: Int): String = when (m) {
        0x03 -> "x86"
        0x08 -> "MIPS"
        0x28 -> "ARM (32 位)"
        0x3E -> "x86-64"
        0xB7 -> "AArch64 (ARM64)"
        0xF3 -> "RISC-V"
        else -> "未知架构(0x%X)".format(m)
    }

    private fun typeName(t: Long): String = when (t.toInt()) {
        0 -> "NULL"
        1 -> "PROGBITS"
        2 -> "SYMTAB"
        3 -> "STRTAB"
        4 -> "RELA"
        5 -> "HASH"
        6 -> "DYNAMIC"
        7 -> "NOTE"
        8 -> "NOBITS"
        9 -> "REL"
        11 -> "DYNSYM"
        14 -> "INIT_ARRAY"
        15 -> "FINI_ARRAY"
        16 -> "PREINIT_ARRAY"
        17 -> "GROUP"
        18 -> "SYMTAB_SHNDX"
        else -> "0x%X".format(t)
    }

    private fun segTypeName(t: Long): String = when (t.toInt()) {
        0 -> "PT_NULL"
        1 -> "PT_LOAD"
        2 -> "PT_DYNAMIC"
        3 -> "PT_INTERP"
        4 -> "PT_NOTE"
        6 -> "PT_PHDR"
        7 -> "PT_TLS"
        0x6474E551.toInt() -> "PT_GNU_STACK"
        0x6474E552.toInt() -> "PT_GNU_RELRO"
        else -> "0x%X".format(t)
    }

    private fun flagsToString(f: Long): String {
        val sb = StringBuilder()
        sb.append(if (f and 4L != 0L) "R" else "-")
        sb.append(if (f and 2L != 0L) "W" else "-")
        sb.append(if (f and 1L != 0L) "X" else "-")
        return sb.toString()
    }

    fun isElf(r: ByteReader): Boolean = r.matches(0, ByteReader.ELF_MAGIC)

    fun parse(r: ByteReader, fileName: String, sha256: String): BinaryReport? {
        if (!isElf(r)) return null
        val is64 = r.u8(4) == 2
        val little = r.u8(5) == 1
        val type = r.u16(16, little).toLong()
        val machine = r.u16(18, little)

        val entry: Long
        val phoff: Long
        val shoff: Long
        val phentsize: Int
        val phnum: Int
        val shentsize: Int
        val shnum: Int
        val shstrndx: Int

        if (is64) {
            entry = r.u64(24, little)
            phoff = r.u64(32, little)
            shoff = r.u64(40, little)
            phentsize = r.u16(54, little)
            phnum = r.u16(56, little)
            shentsize = r.u16(58, little)
            shnum = r.u16(60, little)
            shstrndx = r.u16(62, little)
        } else {
            entry = r.u32(24, little)
            phoff = r.u32(28, little)
            shoff = r.u32(32, little)
            phentsize = r.u16(42, little)
            phnum = r.u16(44, little)
            shentsize = r.u16(46, little)
            shnum = r.u16(48, little)
            shstrndx = r.u16(50, little)
        }

        val notes = ArrayList<String>()
        // 扩大节区表（e_shnum=0 时真实数量在 sh_size 里）这种边角情况只在必要时处理
        val safeShnum = if (shnum == 0 && shoff > 0) {
            runCatching {
                val first = shoff.toInt()
                if (first + 32 <= r.size) r.u32(first + (if (is64) 32 else 20), little).toInt() else 0
            }.getOrDefault(0)
        } else shnum

        // ---- 节区 ----
        val sections = ArrayList<SectionInfo>()
        val sectionRaw = ArrayList<LongArray>() // nameOff,type,addr,offset,size,flags
        if (shoff > 0 && shentsize >= (if (is64) 64 else 40)) {
            for (i in 0 until minOf(safeShnum, 512)) {
                val base = (shoff + i.toLong() * shentsize).toInt()
                if (base < 0 || base + shentsize > r.size) break
                val nameOff = r.u32(base, little)
                val sType = r.u32(base + 4, little)
                val addr: Long
                val off: Long
                val size: Long
                val flags: Long
                if (is64) {
                    flags = r.u64(base + 8, little)
                    addr = r.u64(base + 16, little)
                    off = r.u64(base + 24, little)
                    size = r.u64(base + 32, little)
                } else {
                    flags = r.u32(base + 8, little)
                    addr = r.u32(base + 12, little)
                    off = r.u32(base + 16, little)
                    size = r.u32(base + 20, little)
                }
                sectionRaw.add(longArrayOf(nameOff, sType, addr, off, size, flags))
            }

            var shstrOff = -1L
            if (shstrndx in 0 until sectionRaw.size) shstrOff = sectionRaw[shstrndx][3]
            for (s in sectionRaw) {
                val name = if (shstrOff >= 0) r.cString((shstrOff + s[0]).toInt()) else ""
                val size = s[4]
                val entropy = if (size > 0 && size < Int.MAX_VALUE) {
                    ByteTools.entropy(r.raw(), s[3].toInt(), minOf(size, 4L * 1024 * 1024).toInt())
                } else 0.0
                sections.add(
                    SectionInfo(
                        name = name.ifBlank { "<unnamed>" },
                        typeName = typeName(s[1]),
                        addr = s[2],
                        offset = s[3],
                        size = size,
                        flags = flagsToString(s[5]),
                        entropy = entropy,
                    )
                )
            }
        }

        // ---- 程序头 ----
        val segments = ArrayList<SegmentInfo>()
        if (phoff > 0 && phentsize > 0) {
            for (i in 0 until minOf(phnum, 128)) {
                val base = (phoff + i.toLong() * phentsize).toInt()
                if (base < 0 || base + phentsize > r.size) break
                val pType = r.u32(base, little)
                var pFlags = 0L
                var off: Long
                var vaddr: Long
                var filesz: Long
                var memsz: Long
                if (is64) {
                    pFlags = r.u32(base + 4, little)
                    off = r.u64(base + 8, little)
                    vaddr = r.u64(base + 16, little)
                    filesz = r.u64(base + 32, little)
                    memsz = r.u64(base + 40, little)
                } else {
                    off = r.u32(base + 4, little)
                    vaddr = r.u32(base + 8, little)
                    filesz = r.u32(base + 16, little)
                    memsz = r.u32(base + 20, little)
                    pFlags = r.u32(base + 24, little)
                }
                segments.add(SegmentInfo(segTypeName(pType), off, vaddr, filesz, memsz, flagsToString(pFlags)))
            }
        }

        // ---- 动态段：DT_NEEDED / DT_SONAME ----
        val needed = ArrayList<String>()
        var soname: String? = null
        runCatching {
            val dyn = sections.firstOrNull { it.typeName == "DYNAMIC" }
            if (dyn != null) {
                // 先找 DT_STRTAB 的虚拟地址，再通过 PT_LOAD 映射成文件偏移
                val entsize = if (is64) 16 else 8
                var strtabVaddr = -1L
                var strtabSize = 0L
                val entries = ArrayList<Pair<Long, Long>>()
                var p = dyn.offset.toInt()
                val end = minOf(r.size, (dyn.offset + dyn.size).toInt())
                while (p + entsize <= end) {
                    val tag = if (is64) r.u64(p, little) else r.u32(p, little)
                    val value = if (is64) r.u64(p + 8, little) else r.u32(p + 4, little)
                    entries.add(tag to value)
                    if (tag == DT_STRTAB) strtabVaddr = value
                    if (tag == DT_STRSZ) strtabSize = value
                    if (tag == 0L) break
                    p += entsize
                }
                if (strtabVaddr > 0) {
                    val load = segments.filter { it.typeName == "PT_LOAD" }
                        .firstOrNull { strtabVaddr >= it.vaddr && strtabVaddr < it.vaddr + it.fileSize }
                    if (load != null) {
                        val base = (load.offset + (strtabVaddr - load.vaddr)).toInt()
                        for ((tag, value) in entries) {
                            if (tag == DT_NEEDED) needed.add(r.cString(base + value.toInt()))
                            if (tag == DT_SONAME) soname = r.cString(base + value.toInt())
                        }
                        if (strtabSize <= 0) notes.add("动态字符串表长度为 0，可能被裁剪")
                    }
                }
            }
        }.onFailure { notes.add("动态段解析失败：${it.message}") }

        // ---- 符号表 ----
        val exported = ArrayList<SymbolInfo>()
        val imported = ArrayList<SymbolInfo>()
        runCatching {
            val symSec = sections.firstOrNull { it.typeName == "DYNSYM" || it.typeName == "SYMTAB" }
            if (symSec != null) {
                val idx = sections.indexOf(symSec)
                val raw = sectionRaw.getOrNull(idx)
                // link 字段指向字符串表节区索引（这里用重新读取的方式更稳）
                val base = (shoff + idx.toLong() * shentsize).toInt()
                val strTabIdx = if (base >= 0 && base + shentsize <= r.size) {
                    r.u32(base + (if (is64) 40 else 24), little).toInt()
                } else -1
                val strOff = sectionRaw.getOrNull(strTabIdx)?.get(3) ?: -1L
                val step = if (is64) 24 else 16
                var p = symSec.offset.toInt()
                val end = minOf(r.size, (symSec.offset + symSec.size).toInt())
                var guard = 0
                while (p + step <= end && guard++ < 20000) {
                    val nameOff = r.u32(p, little)
                    val info = r.u8(p + (if (is64) 4 else 12))
                    val shndx = r.u16(p + (if (is64) 6 else 14), little)
                    val value = if (is64) r.u64(p + 8, little) else r.u32(p + 4, little)
                    val symSize = if (is64) r.u64(p + 16, little) else r.u32(p + 8, little)
                    if (nameOff != 0L && strOff >= 0) {
                        val name = r.cString((strOff + nameOff).toInt())
                        if (name.isNotBlank()) {
                            val bind = when ((info shr 4) and 0xF) {
                                0 -> "LOCAL"; 1 -> "GLOBAL"; 2 -> "WEAK"; else -> "OTHER"
                            }
                            val t = when (info and 0xF) {
                                1 -> "OBJECT"; 2 -> "FUNC"; 3 -> "SECTION"; 4 -> "FILE"; else -> "OTHER"
                            }
                            val sym = SymbolInfo(name, value, symSize, bind, t)
                            if (shndx == 0) imported.add(sym) else exported.add(sym)
                        }
                    }
                    p += step
                }
            }
        }.onFailure { notes.add("符号表解析失败：${it.message}") }

        val overallEntropy = ByteTools.entropy(r.raw(), 0, minOf(r.size, 2 * 1024 * 1024))
        val typeLabel = when (type.toInt()) {
            1 -> "可重定位对象(.o)"
            2 -> "可执行文件(EXEC)"
            3 -> "共享库/动态链接(DYN)"
            4 -> "核心转储(CORE)"
            else -> "type=$type"
        }
        val summary = buildString {
            append(typeLabel)
            append("，").append(machineName(machine))
            append("，节区 ${sections.size} / 段 ${segments.size}")
            append("，导出 ${exported.size} / 导入 ${imported.size}")
            if (needed.isNotEmpty()) append("，依赖 ${needed.size} 个库")
            append("，熵 %.2f".format(overallEntropy))
        }

        return BinaryReport(
            fileName = fileName,
            sizeBytes = r.size.toLong(),
            sha256 = sha256,
            format = BinaryFormat.ELF,
            arch = machineName(machine),
            bits = if (is64) 64 else 32,
            endian = if (little) "小端" else "大端",
            entryPoint = entry,
            summary = summary,
            sections = sections,
            segments = segments,
            neededLibs = needed.distinct(),
            soname = soname,
            exportedSymbols = exported,
            importedSymbols = imported,
            strings = ByteTools.asciiStrings(r.raw(), minLen = 6, limit = 400),
            overallEntropy = overallEntropy,
            notes = notes,
        )
    }
}
