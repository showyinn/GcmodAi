package com.gcmodai.app.data.binary

/**
 * x86-64 轻量反汇编（AT&T 之外的 Intel 记法）。
 *
 * 为什么必须有它：Android 的 .so 是 ARM64，但用户手里的 SO/二进制很多是 **x86-64**
 * （PC 上拉起分析的 PHP 扩展、Windows DLL、模拟器里的库）。之前只实现 AArch64 时，
 * 这些文件的反汇编只会吐 `.word`，等于不可用。
 *
 * 覆盖范围：最常见的 mov/lea/add/sub/cmp/test/xor/and/or、push/pop、call/jmp/jcc、
 * ret/nop/leave/endbr64，含 REX 前缀与 ModRM/SIB 寻址。
 * 未识别指令输出 `.byte 0xNN`，并保持步长正确——这是反汇编器的底线：
 * **宁可少认，不能错位**，一旦错位后面全部是噪音。
 */
object X86Lite {

    private val REG64 = arrayOf("rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi")
    private val REG32 = arrayOf("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi")

    data class Decoded(val text: String, val length: Int)

    private fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    private fun reg(name: Int, rex: Int, size64: Boolean, defaultBase: Int = 0): String {
        val idx = name + if (rex and 0x01 != 0) 8 else 0
        if (idx < 8) return if (size64) REG64[idx] else REG32[idx]
        // r8-r15
        val n = idx - 8
        return (if (size64) "r$n" else "r${n}d").let { if (defaultBase > 0) it else it }
    }

    private class ModRm(val text: String, val next: Int, val rmIndex: Int, val isMemory: Boolean)

    private fun readModRm(b: ByteArray, pos: Int, rex: Int, size64: Boolean): ModRm {
        if (pos >= b.size) return ModRm("?", pos + 1, 0, false)
        val modrm = b[pos].toInt() and 0xFF
        val mod = modrm shr 6
        val rm = modrm and 7
        var p = pos + 1
        if (mod == 3) {
            return ModRm(reg(rm, rex, size64), p, rm, false)
        }
        // 内存形式
        var base = ""
        if (rm == 4) {
            // SIB
            if (p >= b.size) return ModRm("[?]", p, 0, true)
            val sib = b[p].toInt() and 0xFF
            p++
            val scale = 1 shl (sib shr 6)
            val index = (sib shr 3) and 7
            val bs = sib and 7
            val baseName = if (bs == 5 && mod == 0) null else reg(bs, rex, true)
            val idxName = if (index == 4) null else reg(index, rex, true)
            base = baseName ?: ""
            if (idxName != null) {
                base += (if (base.isEmpty()) "" else " + ") + idxName + (if (scale > 1) "*$scale" else "")
            }
            if (baseName == null) {
                // mod=0, base=5 → disp32
                if (p + 4 <= b.size) {
                    val disp = le32(b, p)
                    base = hex(disp)
                    p += 4
                }
            }
        } else if (rm == 5 && mod == 0) {
            if (p + 4 <= b.size) {
                base = hex(le32(b, p)); p += 4
            }
        } else {
            base = reg(rm, rex, true)
        }
        when (mod) {
            1 -> {
                if (p < b.size) {
                    val disp = b[p].toInt()
                    p++
                    base += (if (disp >= 0) " + " else " - ") + hex(kotlin.math.abs(disp).toLong())
                }
            }
            2 -> {
                if (p + 4 <= b.size) {
                    val disp = le32(b, p); p += 4
                    base += (if (disp >= 0) " + " else " - ") + hex(kotlin.math.abs(disp))
                }
            }
        }
        return ModRm("[$base]", p, rm, true)
    }

    private fun le32(b: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or ((b.getOrElse(pos + i) { 0 }.toInt() and 0xFF).toLong())
        return v
    }

    private fun le64(b: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or ((b.getOrElse(pos + i) { 0 }.toInt() and 0xFF).toLong())
        return v
    }

    private fun rel32(b: ByteArray, pos: Int, pc: Long, insnLen: Int): String {
        val v = le32(b, pos)
        val signed = if (v > 0x7FFFFFFFL) v - 0x100000000L else v
        return hex(pc + insnLen + signed)
    }

    fun decodeOne(b: ByteArray, start: Int, pc: Long): Decoded {
        var i = start
        var rex = 0
        var opSize16 = false
        var prefixLen = 0
        // 前缀
        loop@ while (i < b.size && prefixLen < 8) {
            when (b[i].toInt() and 0xFF) {
                0x66 -> { opSize16 = true; i++; prefixLen++ }
                0x67, 0xF0, 0xF2, 0xF3 -> { i++; prefixLen++ }
                in 0x40..0x4F -> { rex = b[i].toInt() and 0xFF; i++; prefixLen++ }
                else -> break@loop
            }
        }
        if (i >= b.size) return Decoded(".byte 0x00", 1)

        val op = b[i].toInt() and 0xFF
        val size64 = rex and 0x08 != 0
        fun len(from: Int) = from - start + 1

        // endbr64（F3 0F 1E FA）在前缀阶段被吃掉 F3，这里再判断
        if (prefixLen > 0 && op == 0x0F && i + 2 < b.size && (b[i + 1].toInt() and 0xFF) == 0x1E &&
            (b[i + 2].toInt() and 0xFF) == 0xFA
        ) {
            return Decoded("endbr64", len(i + 2))
        }

        when {
            op == 0xC3 -> return Decoded("ret", (i - start) + 1)
            op == 0xC9 -> return Decoded("leave", (i - start) + 1)
            op == 0x90 -> return Decoded("nop", (i - start) + 1)
            op == 0xCC -> return Decoded("int3", (i - start) + 1)
            op in 0x50..0x57 -> return Decoded("push ${reg(op - 0x50, rex, true)}", (i - start) + 1)
            op in 0x58..0x5F -> return Decoded("pop ${reg(op - 0x58, rex, true)}", (i - start) + 1)
            op == 0x68 -> return Decoded(
                "push ${hex(le32(b, i + 1).let { if (it > 0x7FFFFFFFL) it - 0x100000000L else it })}",
                (i - start) + 5,
            )
            op == 0x6A -> return Decoded("push 0x%X".format(b.getOrElse(i + 1) { 0 }.toInt() and 0xFF), (i - start) + 2)
            op == 0xE8 -> return Decoded("call ${rel32(b, i + 1, pc, 5)}", (i - start) + 5)
            op == 0xE9 -> return Decoded("jmp ${rel32(b, i + 1, pc, 5)}", (i - start) + 5)
            op == 0xEB -> {
                val d = b.getOrElse(i + 1) { 0 }.toInt().toByte().toInt()
                return Decoded("jmp ${hex(pc + 2 + d)}", (i - start) + 2)
            }
            op in 0x70..0x7F -> {
                val cond = arrayOf("jo", "jno", "jb", "jae", "je", "jne", "jbe", "ja",
                    "js", "jns", "jp", "jnp", "jl", "jge", "jle", "jg")[op - 0x70]
                val d = b.getOrElse(i + 1) { 0 }.toInt().toByte().toInt()
                return Decoded("$cond ${hex(pc + 2 + d)}", (i - start) + 2)
            }
            // mov r32/r64, imm —— 长度必须把 REX/0x66 前缀算进去，否则反汇编会整体错位
            op in 0xB8..0xBF -> {
                if (size64) {
                    return Decoded("mov ${reg(op - 0xB8, rex, true)}, ${hex(le64(b, i + 1))}", (i - start) + 9)
                }
                if (opSize16) {
                    return Decoded("mov ${reg(op - 0xB8, rex, false)}, 0x%X".format(le32(b, i + 1) and 0xFFFF), (i - start) + 3)
                }
                return Decoded("mov ${reg(op - 0xB8, rex, false)}, ${hex(le32(b, i + 1))}", (i - start) + 5)
            }
            // mov r/m, r 与 mov r, r/m
            op == 0x89 || op == 0x8B -> {
                val modrm = readModRm(b, i + 1, rex, size64)
                val r = reg((b[i + 1].toInt() shr 3) and 7, rex, size64)
                return if (op == 0x89) {
                    Decoded("mov ${modrm.text}, $r", modrm.next - start)
                } else {
                    Decoded("mov $r, ${modrm.text}", modrm.next - start)
                }
            }
            op == 0x8D -> {
                val modrm = readModRm(b, i + 1, rex, size64)
                val r = reg((b[i + 1].toInt() shr 3) and 7, rex, size64)
                return Decoded("lea $r, ${modrm.text}", modrm.next - start)
            }
            // add/sub/xor/and/or/cmp/test 的 r/m, r 形式
            op == 0x01 || op == 0x29 || op == 0x31 || op == 0x21 || op == 0x09 || op == 0x39 || op == 0x85 -> {
                val name = when (op) {
                    0x01 -> "add"; 0x29 -> "sub"; 0x31 -> "xor"; 0x21 -> "and"
                    0x09 -> "or"; 0x39 -> "cmp"; else -> "test"
                }
                val modrm = readModRm(b, i + 1, rex, size64)
                val r = reg((b[i + 1].toInt() shr 3) and 7, rex, size64)
                return Decoded("$name ${modrm.text}, $r", modrm.next - start)
            }
            // 立即数形式：0x81/0x83 group1，0x3D cmp eax, imm32
            op == 0x83 || op == 0x81 -> {
                val modrmByte = b.getOrElse(i + 1) { 0 }.toInt() and 0xFF
                val ext = (modrmByte shr 3) and 7
                val name = arrayOf("add", "or", "adc", "sbb", "and", "sub", "xor", "cmp")[ext]
                val modrm = readModRm(b, i + 1, rex, size64)
                return if (op == 0x83) {
                    val imm = b.getOrElse(modrm.next) { 0 }.toInt().toByte().toInt()
                    Decoded("$name ${modrm.text}, ${hex(imm.toLong() and 0xFFFFFFFFL)}", modrm.next - start + 1)
                } else {
                    Decoded("$name ${modrm.text}, ${hex(le32(b, modrm.next))}", modrm.next - start + 4)
                }
            }
            op == 0x3D -> return Decoded("cmp eax, ${hex(le32(b, i + 1))}", (i - start) + 5)
        }

        return Decoded(".byte 0x%02X".format(op), 1)
    }

    fun disassemble(code: ByteArray, baseAddress: Long, maxLines: Int = 400): List<DisasmLine> {
        val out = ArrayList<DisasmLine>()
        var i = 0
        var addr = baseAddress
        var guard = 0
        while (i < code.size && out.size < maxLines && guard++ < maxLines * 16) {
            val d = decodeOne(code, i, addr)
            val step = d.length.coerceAtLeast(1)
            var word = 0
            for (k in 0 until minOf(4, step)) {
                word = word or ((code.getOrElse(i + k) { 0 }.toInt() and 0xFF) shl (8 * k))
            }
            out.add(DisasmLine(addr, word, d.text))
            i += step
            addr += step
        }
        return out
    }
}
