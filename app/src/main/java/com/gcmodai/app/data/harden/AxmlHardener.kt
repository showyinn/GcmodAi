package com.gcmodai.app.data.harden

/**
 * AndroidManifest.xml（二进制 AXML）原位加固。
 *
 * 只做一件事：把指定布尔属性的 4 字节 typedValue **原地**改成 0。
 * 为什么必须是"原地"：AXML 的字符串池是定长的，属性值如果是字符串类型，
 * 改成别的长度就会让整张表的偏移全错；而 `debuggable` / `allowBackup` 是
 * 8 位整型/布尔类型（dataType 0x12 / 0x10 / 0x11），改 data 字段不影响任何偏移。
 *
 * 对应加固项：
 *   - `android:debuggable` → false：禁止调试器附加、禁止 run-as 读私有目录
 *   - `android:allowBackup` → false：禁掉 adb backup 拖数据
 */
object AxmlHardener {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_START_ELEMENT = 0x0102

    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    data class Result(
        val data: ByteArray,
        val changed: List<String>,
        val notes: List<String>,
    )

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toInt() and 0xFF).toLong()
        return v
    }

    private fun readStringPool(b: ByteArray, start: Int): List<String> {
        val count = u32(b, start + 8).toInt()
        val flags = u32(b, start + 16)
        val stringsStart = u32(b, start + 20).toInt()
        val utf8 = flags and 0x100L != 0L
        val out = ArrayList<String>(minOf(count, 60_000))
        for (i in 0 until minOf(count, 60_000)) {
            val off = u32(b, start + 28 + i * 4).toInt()
            val abs = start + stringsStart + off
            if (abs <= 0 || abs >= b.size) {
                out.add("")
                continue
            }
            runCatching {
                if (utf8) {
                    var p = abs
                    // 跳过 UTF-16 长度与 UTF-8 长度的 varint
                    repeat(2) {
                        val first = b[p].toInt() and 0xFF
                        p++
                        if (first and 0x80 != 0) p++
                    }
                    val lenByte = b[p].toInt() and 0xFF
                    p++
                    val len = if (lenByte and 0x80 != 0) ((lenByte and 0x7F) shl 8) or (b[p++].toInt() and 0xFF) else lenByte
                    out.add(String(b, p, len, Charsets.UTF_8))
                } else {
                    val len = u16(b, abs)
                    out.add(String(b, abs + 2, len * 2, Charsets.UTF_16LE))
                }
            }.onFailure { out.add("") }
        }
        return out
    }

    /**
     * 把 [targets] 里的布尔/整型属性值改成 0。
     *
     * @param targets 属性名（不带 `android:` 前缀也可），默认 debuggable / allowBackup
     */
    fun harden(
        data: ByteArray,
        targets: Set<String> = setOf("debuggable", "allowBackup"),
    ): Result {
        if (data.size < 8) return Result(data, emptyList(), listOf("文件太小，不是 AXML"))
        val out = data.copyOf()
        val changed = ArrayList<String>()
        val notes = ArrayList<String>()

        var strings: List<String> = emptyList()
        var pos = 8
        while (pos + 8 <= out.size) {
            val type = u16(out, pos)
            val headerSize = u16(out, pos + 2)
            val chunkSize = u32(out, pos + 4).toInt()
            if (chunkSize <= 0 || pos + chunkSize > out.size) break

            if (type == RES_STRING_POOL) {
                strings = readStringPool(out, pos)
            } else if (type == RES_XML_START_ELEMENT) {
                val ext = pos + headerSize
                val attrStart = u16(out, ext + 8)
                val attrSize = u16(out, ext + 10)
                val attrCount = u16(out, ext + 12)
                if (attrSize in 12..64 && attrCount in 1..200) {
                    val base = ext + attrStart
                    for (i in 0 until attrCount) {
                        val a = base + i * attrSize
                        if (a + 20 > out.size) break
                        val nameIdx = u32(out, a + 4).toInt()
                        val name = strings.getOrNull(nameIdx) ?: continue
                        if (name !in targets) continue
                        val dataType = out[a + 15].toInt() and 0xFF
                        if (dataType != TYPE_INT_BOOLEAN && dataType != TYPE_INT_DEC && dataType != TYPE_INT_HEX) {
                            notes.add("$name 不是布尔/整型属性（type=0x%02X），跳过".format(dataType))
                            continue
                        }
                        val oldVal = u32(out, a + 16)
                        if (oldVal == 0L) {
                            notes.add("$name 已经是 false")
                            continue
                        }
                        // 只改 4 字节 data，长度与偏移完全不变
                        for (k in 0 until 4) out[a + 16 + k] = 0
                        changed.add(name)
                        notes.add("$name：0x${java.lang.Long.toHexString(oldVal)} → 0（false）")
                    }
                }
            }
            pos += chunkSize
        }
        if (changed.isEmpty() && notes.isEmpty()) notes.add("清单里没有找到目标属性")
        return Result(out, changed, notes)
    }
}
