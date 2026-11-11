package com.gcmodai.app.data.binary

/**
 * DEX 结构解析：类 / 方法 / 字段 / 字符串。
 *
 * 只读结构，不执行、不优化。产出的东西是「这个包里有哪些类、哪些方法、方法签名长什么样」，
 * 这正是 AI 判断「这段代码在干什么」所需的骨架；指令级（smali）反编译留给后续专项，
 * 因为完整 Dalvik 指令还原体量远超一个页面能承载的交互范围。
 */
object DexParser {

    private class Cursor(val r: ByteReader) {
        fun uleb(at: Int): Pair<Long, Int> {
            var pos = at
            var result = 0L
            var shift = 0
            while (pos < r.size) {
                val b = r.u8(pos)
                pos++
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) break
            }
            return result to pos
        }
    }

    fun accessFlags(flags: Long, isMethod: Boolean): String {
        val parts = ArrayList<String>()
        val table = linkedMapOf(
            0x1L to "public", 0x2L to "private", 0x4L to "protected", 0x8L to "static",
            0x10L to "final", 0x20L to "synchronized", 0x100L to "native", 0x200L to "interface",
            0x400L to "abstract", 0x800L to "strictfp", 0x1000L to "synthetic",
            0x2000L to "annotation", 0x4000L to "enum", 0x10000L to "constructor",
            0x20000L to "declared_synchronized",
        )
        for ((bit, name) in table) {
            if (flags and bit != 0L) {
                if (!isMethod && (name == "synchronized" || name == "constructor" || name == "declared_synchronized")) continue
                parts.add(name)
            }
        }
        return parts.joinToString(" ").ifBlank { "none" }
    }

    fun isDex(r: ByteReader): Boolean = r.matches(0, ByteReader.DEX_MAGIC) && r.size > 0x70

    fun dexVersion(r: ByteReader): String = if (isDex(r)) r.ascii(4, 3) else "?"

    fun parse(r: ByteReader): Pair<List<DexClassInfo>, List<String>> {
        val empty = emptyList<DexClassInfo>() to emptyList<String>()
        if (!isDex(r)) return empty
        val cur = Cursor(r)

        val stringIdsSize = r.u32(0x38, true).toInt()
        val stringIdsOff = r.u32(0x3C, true).toInt()
        val typeIdsSize = r.u32(0x40, true).toInt()
        val typeIdsOff = r.u32(0x44, true).toInt()
        val protoIdsOff = r.u32(0x4C, true).toInt()
        val fieldIdsOff = r.u32(0x54, true).toInt()
        val methodIdsSize = r.u32(0x58, true).toInt()
        val methodIdsOff = r.u32(0x5C, true).toInt()
        val classDefsSize = r.u32(0x60, true).toInt()
        val classDefsOff = r.u32(0x64, true).toInt()

        // ---- 字符串表 ----
        val strings = ArrayList<String>(minOf(stringIdsSize, 200_000))
        for (i in 0 until minOf(stringIdsSize, 200_000)) {
            val off = r.u32(stringIdsOff + i * 4, true).toInt()
            if (off <= 0 || off >= r.size) {
                strings.add("")
                continue
            }
            val (_, dataStart) = cur.uleb(off)          // utf16 长度前置 uleb
            val bytes = ArrayList<Byte>()
            var p = dataStart
            while (p < r.size && r.u8(p) != 0) {
                bytes.add(r.u8(p).toByte())
                p++
            }
            strings.add(String(bytes.toByteArray(), Charsets.UTF_8))
        }

        fun typeName(idx: Int): String {
            if (idx < 0 || idx >= typeIdsSize) return "?"
            val sIdx = r.u32(typeIdsOff + idx * 4, true).toInt()
            return strings.getOrElse(sIdx) { "?" }
        }

        fun methodRef(idx: Int): String {
            if (idx < 0 || idx >= methodIdsSize) return "?"
            val base = methodIdsOff + idx * 8
            val classIdx = r.u16(base, true)
            val protoIdx = r.u16(base + 2, true)
            val nameIdx = r.u32(base + 4, true).toInt()
            val name = strings.getOrElse(nameIdx) { "?" }
            val protoBase = protoIdsOff + protoIdx * 12
            val retIdx = r.u32(protoBase + 4, true).toInt()
            return "${typeName(classIdx)}->$name():${typeName(retIdx)}"
        }

        fun fieldRef(idx: Int): String {
            val base = fieldIdsOff + idx * 8
            val classIdx = r.u16(base, true)
            val typeIdx = r.u16(base + 2, true)
            val nameIdx = r.u32(base + 4, true).toInt()
            return "${typeName(classIdx)}->${strings.getOrElse(nameIdx) { "?" }}:${typeName(typeIdx)}"
        }

        // ---- 类 ----
        val classes = ArrayList<DexClassInfo>()
        for (i in 0 until minOf(classDefsSize, 20_000)) {
            val base = classDefsOff + i * 32
            if (base + 32 > r.size) break
            val classIdx = r.u32(base, true).toInt()
            val access = r.u32(base + 4, true)
            val classDataOff = r.u32(base + 24, true).toInt()
            val descriptor = typeName(classIdx)
            val methods = ArrayList<String>()
            val fields = ArrayList<String>()
            if (classDataOff > 0 && classDataOff < r.size) {
                var p = classDataOff
                val (sf, p1) = cur.uleb(p); p = p1
                val (inf, p2) = cur.uleb(p); p = p2
                val (dm, p3) = cur.uleb(p); p = p3
                val (vm, p4) = cur.uleb(p); p = p4
                // 字段
                var fieldIdx = 0
                repeat((sf + inf).toInt().coerceAtMost(4096)) {
                    val (diff, np1) = cur.uleb(p); p = np1
                    val (_, np2) = cur.uleb(p); p = np2
                    fieldIdx += diff.toInt()
                    fields.add(fieldRef(fieldIdx))
                }
                // 方法
                var methodIdx = 0
                repeat((dm + vm).toInt().coerceAtMost(8192)) {
                    val (diff, np1) = cur.uleb(p); p = np1
                    val (acc, np2) = cur.uleb(p); p = np2
                    val (codeOff, np3) = cur.uleb(p); p = np3
                    p = np3
                    methodIdx += diff.toInt()
                    val flags = accessFlags(acc, isMethod = true)
                    val body = if (codeOff > 0) "" else " [无字节码]"
                    methods.add("$flags ${methodRef(methodIdx)}$body")
                }
            }
            classes.add(
                DexClassInfo(
                    descriptor = descriptor,
                    accessFlags = accessFlags(access, isMethod = false),
                    methods = methods,
                    fields = fields,
                )
            )
        }
        return classes to strings
    }
}
