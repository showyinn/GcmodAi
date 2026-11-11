package com.gcmodai.app.data.harden

import com.gcmodai.app.data.binary.ByteReader
import com.gcmodai.app.data.binary.ByteTools

/**
 * SO 加固（二进制级）。
 *
 * 做的三件事，都只改「元数据/无用数据」，不碰任何会被执行或链接的字节：
 *   1. 剥离 `.symtab` / `.strtab` / `.debug_*` / `.comment`：这些只对静态分析有用，
 *      删掉后 IDA/Ghidra 看不到函数名与调试信息，但你自己的程序运行完全不受影响；
 *   2. 节名等长随机化：`.text` 变成 `.aB3xQ` 之类，分析者少一个好锚点；
 *      **必须等长**，因为节名偏移写在 sh_name 里，变长会让整张表错位；
 *   3. 清零 `.comment` 内容（编译器版本、构建路径常在这里泄露）。
 *
 * 明确不做（也不该在 App 里做）：控制流平坦化 / 指令替换 —— 那是编译期 LLVM pass，
 * 二进制事后修改极易改坏 ARM64 指令编码，属于"看起来加固了、实际跑不起来"。
 */
object SoHardener {

    private val STRIP_SECTIONS = setOf(
        ".symtab", ".strtab", ".comment",
        ".debug_info", ".debug_abbrev", ".debug_line", ".debug_str",
        ".debug_ranges", ".debug_loc", ".debug_frame", ".debug_aranges",
        ".note.gnu.build-id",
    )

    /** 不随机化的节名：这些被动态链接器/工具按名字依赖，改了没必要也有风险。 */
    private val KEEP_NAMES = setOf("", ".shstrtab", ".interp", ".dynamic", ".dynsym", ".dynstr")

    data class Result(
        val data: ByteArray,
        val strippedSections: List<String>,
        val renamedSections: Int,
        val wipedComment: Boolean,
        val notes: List<String>,
    )

    /**
     * @param strip 是否剥离符号/调试节
     * @param randomizeNames 是否随机化节名（等长）
     * @param wipeComment 是否清零 .comment
     */
    fun harden(
        data: ByteArray,
        strip: Boolean = true,
        randomizeNames: Boolean = true,
        wipeComment: Boolean = true,
    ): Result {
        val r = ByteReader(data)
        val notes = ArrayList<String>()
        if (!r.matches(0, ByteReader.ELF_MAGIC)) {
            return Result(data, emptyList(), 0, false, listOf("不是 ELF 文件，跳过 SO 加固"))
        }
        val is64 = r.u8(4) == 2
        val little = r.u8(5) == 1
        if (!little) {
            return Result(data, emptyList(), 0, false, listOf("大端 ELF 暂不处理（Android 上不存在）"))
        }

        val shoff = if (is64) r.u64(40, true) else r.u32(32, true)
        val shentsize = if (is64) r.u16(58, true) else r.u16(46, true)
        val shnum = if (is64) r.u16(60, true) else r.u16(48, true)
        val shstrndx = if (is64) r.u16(62, true) else r.u16(50, true)
        if (shoff <= 0 || shentsize <= 0 || shnum <= 0) {
            return Result(data, emptyList(), 0, false, listOf("没有节表（可能已被剥离），跳过"))
        }

        val out = data.copyOf()
        val shHeaders = ArrayList<Int>()   // 每个节头的文件偏移
        for (i in 0 until shnum) {
            val off = (shoff + i.toLong() * shentsize).toInt()
            if (off < 0 || off + shentsize > out.size) break
            shHeaders.add(off)
        }
        fun nameOf(headerOff: Int): String {
            val nameOff = ByteReader(out).u32(headerOff, true).toInt()
            val strSec = shHeaders.getOrNull(shstrndx) ?: return ""
            val strOff = ByteReader(out).u64(strSec + (if (is64) 24 else 16), true).toInt()
            return ByteReader(out).cString(strOff + nameOff)
        }
        fun fieldOff(headerOff: Int, isType: Boolean): Int = headerOff + if (isType) 4 else 0
        fun secOffset(headerOff: Int): Long =
            ByteReader(out).u64(headerOff + (if (is64) 24 else 16), true)
        fun secSize(headerOff: Int): Long =
            ByteReader(out).u64(headerOff + (if (is64) 32 else 20), true)

        val stripped = ArrayList<String>()
        var wiped = false

        // ---- 1. 剥离 + 清 comment ----
        for (headerOff in shHeaders) {
            val name = nameOf(headerOff)
            if (name.isBlank()) continue
            val isComment = name == ".comment"
            if (strip && name in STRIP_SECTIONS) {
                // 清空内容再抹掉节头：内容清零避免被字符串扫描找回来
                val off = secOffset(headerOff).toInt()
                val size = secSize(headerOff).toInt()
                if (off in 0 until out.size && size in 1..(out.size - off)) {
                    java.util.Arrays.fill(out, off, off + size, 0)
                }
                // 节头标记为 NULL 类型、大小/偏移归零
                for (i in 0 until shentsize) out[headerOff + i] = 0
                stripped.add(name)
                if (isComment) wiped = true
            }
        }

        // ---- 2. 节名等长随机化 ----
        var renamed = 0
        if (randomizeNames) {
            val strSec = shHeaders.getOrNull(shstrndx)
            if (strSec != null) {
                val strOff = secOffset(strSec).toInt()
                val strSize = secSize(strSec).toInt()
                if (strOff in 0 until out.size && strSize in 1..(out.size - strOff)) {
                    // 收集每个节名在字符串表里的区间
                    for (headerOff in shHeaders) {
                        val nameOff = ByteReader(out).u32(headerOff, true).toInt()
                        if (nameOff <= 0 || nameOff >= strSize) continue
                        val abs = strOff + nameOff
                        // ByteArray 没有带起点的 indexOf，手写扫描（上限到字符串表末尾）
                        var end = -1
                        var k = abs
                        val limit = minOf(out.size, strOff + strSize)
                        while (k < limit) {
                            if (out[k] == 0.toByte()) {
                                end = k
                                break
                            }
                            k++
                        }
                        if (end < 0) continue
                        val len = end - abs
                        if (len < 3) continue
                        val current = String(out, abs, len, Charsets.US_ASCII)
                        if (current in KEEP_NAMES) continue
                        // 等长随机名：首字符用 '.'，其余可打印字母数字，避免破坏解析
                        val sb = StringBuilder(len)
                        sb.append('.')
                        var seed = current.hashCode().toLong() xor 0x5DEECE66DL
                        for (i in 1 until len) {
                            seed = (seed * 6364136223846793005L + 1442695040888963407L)
                            val c = ('a'.code + ((seed ushr 33) % 26).toInt())
                            sb.append(c.toChar())
                        }
                        val bytes = sb.toString().toByteArray(Charsets.US_ASCII)
                        System.arraycopy(bytes, 0, out, abs, len)
                        renamed++
                    }
                }
            }
        }

        if (stripped.isNotEmpty()) notes.add("已剥离：${stripped.joinToString("、")}")
        if (renamed > 0) notes.add("节名随机化：$renamed 个（等长替换，不改变偏移）")
        if (wiped) notes.add(".comment 已清零（编译器版本/构建路径不再泄露）")
        notes.add(
            "加固后体积 ${ByteTools.humanSize(data.size.toLong())} → " +
                ByteTools.humanSize(out.size.toLong()) + "（节内容清零不改变文件大小）"
        )
        return Result(out, stripped, renamed, wiped, notes)
    }
}
