package com.gcmodai.app.data.binary

/** 一条反汇编结果。 */
data class DisasmLine(
    val address: Long,
    val word: Int,
    val text: String,
)

/**
 * AArch64（ARM64）轻量反汇编。
 *
 * 定位：把 SO 里最常见的指令还原成人能读、模型能推理的形式（函数骨架、调用目标、
 * 常量加载、栈帧布局），不做完整指令集覆盖，也不做控制流重建。
 * 未识别的字统一输出 `.word 0xXXXXXXXX`，并保持偏移对齐——这样 AI 依然能看到
 * 常量池与数据跳转，不会因为「不认识」而丢信息。
 */
object Arm64Lite {

    private fun signExtend(value: Long, bits: Int): Long {
        val shift = 64 - bits
        return (value shl shift) shr shift
    }

    private fun reg(n: Int): String = if (n == 31) "sp" else "x$n"

    private fun wreg(n: Int): String = if (n == 31) "wsp" else "w$n"

    fun disassemble(code: ByteArray, baseAddress: Long, maxLines: Int = 400): List<DisasmLine> {
        val out = ArrayList<DisasmLine>()
        var addr = baseAddress
        var i = 0
        while (i + 4 <= code.size && out.size < maxLines) {
            val w = (code[i].toInt() and 0xFF) or
                ((code[i + 1].toInt() and 0xFF) shl 8) or
                ((code[i + 2].toInt() and 0xFF) shl 16) or
                ((code[i + 3].toInt() and 0xFF) shl 24)
            out.add(DisasmLine(addr, w, decode(w, addr)))
            addr += 4
            i += 4
        }
        return out
    }

    /** 解码单条指令。 */
    fun decode(w: Int, pc: Long): String {
        val u = w.toLong() and 0xFFFFFFFFL

        // RET / BR / BLR / ERET / NOP / SVC
        when (u) {
            0xD503201FL -> return "nop"
            0xD65F03C0L -> return "ret"
        }
        if ((u and 0xFFFFFC1FL) == 0xD65F0000L) return "ret ${reg(((u shr 5) and 0x1F).toInt())}"
        if ((u and 0xFFFFFC1FL) == 0xD61F0000L) return "br ${reg(((u shr 5) and 0x1F).toInt())}"
        if ((u and 0xFFFFFC1FL) == 0xD63F0000L) return "blr ${reg(((u shr 5) and 0x1F).toInt())}"
        if ((u and 0xFFE0001FL) == 0xD4000001L) return "svc #${(u shr 5) and 0xFFFF}"

        // B / BL
        if ((u and 0x7C000000L) == 0x14000000L) {
            val imm = signExtend(u and 0x03FFFFFFL, 26) shl 2
            val target = pc + imm
            val op = if ((u shr 31) != 0L) "bl" else "b"
            return "$op 0x${java.lang.Long.toHexString(target)}"
        }
        // B.cond
        if ((u and 0xFF000010L) == 0x54000000L) {
            val imm = signExtend((u shr 5) and 0x7FFFFL, 19) shl 2
            val cond = (u and 0xFL).toInt()
            val names = listOf("eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc",
                "hi", "ls", "ge", "lt", "gt", "le", "al", "nv")
            return "b.${names[cond]} 0x${java.lang.Long.toHexString(pc + imm)}"
        }
        // CBZ / CBNZ
        if ((u and 0x7E000000L) == 0x34000000L) {
            val imm = signExtend((u shr 5) and 0x7FFFFL, 19) shl 2
            val rt = ((u shr 0) and 0x1F).toInt()
            val is64 = (u shr 31) != 0L
            val op = if ((u shr 24) and 1L != 0L) "cbnz" else "cbz"
            val r = if (is64) reg(rt) else wreg(rt)
            return "$op $r, 0x${java.lang.Long.toHexString(pc + imm)}"
        }
        // TBZ / TBNZ
        if ((u and 0x7E000000L) == 0x36000000L) {
            val bit = (((u shr 31) and 1L) shl 5) or ((u shr 19) and 0x1FL)
            val imm = signExtend((u shr 5) and 0x3FFFL, 14) shl 2
            val rt = (u and 0x1F).toInt()
            val op = if ((u shr 24) and 1L != 0L) "tbnz" else "tbz"
            return "$op ${wreg(rt)}, #$bit, 0x${java.lang.Long.toHexString(pc + imm)}"
        }
        // ADRP
        if ((u and 0x9F000000L) == 0x90000000L) {
            val rd = (u and 0x1F).toInt()
            val immlo = (u shr 29) and 0x3L
            val immhi = (u shr 5) and 0x7FFFFL
            val imm = signExtend((immhi shl 2) or immlo, 21) shl 12
            val page = (pc and -4096L) + imm   // 清低 12 位得到页基址
            return "adrp ${reg(rd)}, 0x${java.lang.Long.toHexString(page)}"
        }
        // ADR
        if ((u and 0x9F000000L) == 0x10000000L) {
            val rd = (u and 0x1F).toInt()
            val immlo = (u shr 29) and 0x3L
            val immhi = (u shr 5) and 0x7FFFFL
            val imm = signExtend((immhi shl 2) or immlo, 21)
            return "adr ${reg(rd)}, 0x${java.lang.Long.toHexString(pc + imm)}"
        }
        // ADD/SUB 立即数
        if ((u and 0x1F000000L) == 0x11000000L) {
            val sf = (u shr 31) != 0L
            val sub = (u shr 30) and 1L != 0L
            val shift = (u shr 22) and 0x3L
            val imm = (u shr 10) and 0xFFFL
            val rn = ((u shr 5) and 0x1F).toInt()
            val rd = (u and 0x1F).toInt()
            val v = if (shift == 1L) imm shl 12 else imm
            return "${if (sub) "sub" else "add"} ${if (sf) reg(rd) else wreg(rd)}, " +
                "${if (sf) reg(rn) else wreg(rn)}, #$v"
        }
        // MOVZ / MOVN / MOVK
        val movTop = u and 0x7F800000L
        if (movTop == 0x52800000L || movTop == 0x12800000L || movTop == 0x72800000L) {
            val sf = (u shr 31) != 0L
            val hw = ((u shr 21) and 0x3L).toInt()
            val imm = (u shr 5) and 0xFFFFL
            val rd = (u and 0x1F).toInt()
            val op = when (movTop) {
                0x52800000L -> "movz"
                0x12800000L -> "movn"
                else -> "movk"
            }
            val shifted = imm shl (hw * 16)
            val r = if (sf) reg(rd) else wreg(rd)
            return if (op == "movz" && hw == 0) "mov $r, #0x${java.lang.Long.toHexString(shifted)}"
            else "$op $r, #0x${java.lang.Long.toHexString(imm)}, lsl #${hw * 16}"
        }
        // LDR/STR（无符号偏移）
        if ((u and 0x3B000000L) == 0x39000000L) {
            val size = ((u shr 30) and 0x3L).toInt()
            val load = (u shr 22) and 1L != 0L
            val rt = (u and 0x1F).toInt()
            val rn = ((u shr 5) and 0x1F).toInt()
            val imm = (u shr 10) and 0xFFFL
            val scale = 1L shl size
            val suffix = when (size) { 0 -> "b"; 1 -> "h"; 2 -> "w"; else -> "x" }
            val rtName = when (size) { 0 -> "w$rt"; 1 -> "w$rt"; 2 -> "w$rt"; else -> "x$rt" }
            return "${if (load) "ldr" else "str"} $rtName, [${reg(rn)}, #${imm * scale}]$suffix".replace("]x", "]")
        }
        // LDP/STP（最常见的栈帧指令）
        if ((u and 0x3A000000L) == 0x28000000L) {
            val is64 = (u shr 31) != 0L
            val load = (u shr 22) and 1L != 0L
            val rt = (u and 0x1F).toInt()
            val rt2 = ((u shr 10) and 0x1F).toInt()
            val rn = ((u shr 5) and 0x1F).toInt()
            val imm = signExtend((u shr 15) and 0x7FL, 7) shl if (is64) 3 else 2
            val r = if (is64) "x" else "w"
            return "${if (load) "ldp" else "stp"} $r$rt, $r$rt2, [${reg(rn)}, #$imm]"
        }
        // ADD/SUB 寄存器
        if ((u and 0x1F200000L) == 0x0B000000L) {
            val sf = (u shr 31) != 0L
            val sub = (u shr 30) and 1L != 0L
            val rd = (u and 0x1F).toInt()
            val rn = ((u shr 5) and 0x1F).toInt()
            val rm = ((u shr 16) and 0x1F).toInt()
            val r = if (sf) "x" else "w"
            return "${if (sub) "sub" else "add"} $r$rd, $r$rn, $r$rm"
        }
        // MOV 寄存器（ORR Xd, XZR, Xm）
        if ((u and 0x7FE0FFE0L) == 0x2A0003E0L) {
            val sf = (u shr 31) != 0L
            val rd = (u and 0x1F).toInt()
            val rm = ((u shr 16) and 0x1F).toInt()
            val r = if (sf) "x" else "w"
            return "mov $r$rd, $r$rm"
        }
        // LDR 字面量（常用于加载常量池地址）
        if ((u and 0x3B000000L) == 0x18000000L) {
            val rt = (u and 0x1F).toInt()
            val imm = signExtend((u shr 5) and 0x7FFFFL, 19) shl 2
            val is64 = (u shr 30) and 1L != 0L
            return "ldr ${if (is64) "x$rt" else "w$rt"}, 0x${java.lang.Long.toHexString(pc + imm)}"
        }
        return ".word 0x%08X".format(w)
    }

    /** 粗略的函数骨架：从入口起，遇到 ret 或新的导出符号地址就切一段。 */
    fun functionSkeleton(
        code: ByteArray,
        baseAddress: Long,
        knownStarts: List<Long> = emptyList(),
        maxFunctions: Int = 40,
    ): List<Pair<Long, List<DisasmLine>>> {
        val lines = disassemble(code, baseAddress, maxLines = 4000)
        val starts = HashSet<Long>()
        starts.add(baseAddress)
        knownStarts.forEach { s -> if (s >= baseAddress) starts.add(s) }
        lines.forEach { l ->
            // 调用目标落在本段内，也视为函数起点
            val m = Regex("^(bl|b) 0x([0-9a-f]+)$").find(l.text)
            if (m != null) {
                val t = m.groupValues[2].toLongOrNull(16)
                if (t != null && t >= baseAddress && t < baseAddress + code.size) starts.add(t)
            }
        }
        val sorted = starts.sorted()
        val out = ArrayList<Pair<Long, List<DisasmLine>>>()
        for ((idx, start) in sorted.withIndex()) {
            if (out.size >= maxFunctions) break
            val end = if (idx + 1 < sorted.size) sorted[idx + 1] else baseAddress + code.size
            val body = lines.filter { it.address >= start && it.address < end }
            if (body.isNotEmpty()) out.add(start to body)
        }
        return out
    }
}
