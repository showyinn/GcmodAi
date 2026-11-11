package com.gcmodai.app.data.binary

/**
 * 大小端可切换的字节读取器。
 *
 * 逆向场景里同一份文件里常出现混合端序（例如 ELF 头是大端标志位、但数据段按目标架构写），
 * 所以这里把端序做成显式参数，调用方每次自己声明，不允许隐式默认。
 */
class ByteReader(private val data: ByteArray) {

    val size: Int get() = data.size

    fun u8(off: Int): Int {
        require(off in 0 until size) { "越界读取 u8@$off (size=$size)" }
        return data[off].toInt() and 0xFF
    }

    fun u16(off: Int, little: Boolean): Int {
        val a = u8(off)
        val b = u8(off + 1)
        return if (little) (b shl 8) or a else (a shl 8) or b
    }

    fun u32(off: Int, little: Boolean): Long {
        var v = 0L
        if (little) {
            for (i in 3 downTo 0) v = (v shl 8) or u8(off + i).toLong()
        } else {
            for (i in 0..3) v = (v shl 8) or u8(off + i).toLong()
        }
        return v and 0xFFFFFFFFL
    }

    fun u64(off: Int, little: Boolean): Long {
        var v = 0L
        if (little) {
            for (i in 7 downTo 0) v = (v shl 8) or u8(off + i).toLong()
        } else {
            for (i in 0..7) v = (v shl 8) or u8(off + i).toLong()
        }
        return v
    }

    /** 读 offset 处的一段原始字节（自动裁剪到文件尾，不抛异常）。 */
    fun bytes(off: Int, len: Int): ByteArray {
        if (off < 0 || off >= size || len <= 0) return ByteArray(0)
        val end = minOf(size, off + len)
        return data.copyOfRange(off, end)
    }

    fun ascii(off: Int, len: Int): String =
        bytes(off, len).map { b -> if (b.toInt() in 32..126) b.toInt().toChar() else '.' }.joinToString("")

    /** 以 0 结尾的 C 字符串。 */
    fun cString(off: Int, max: Int = 512): String {
        val sb = StringBuilder()
        var i = off
        val end = minOf(size, off + max)
        while (i < end) {
            val c = u8(i)
            if (c == 0) break
            sb.append(if (c in 32..126) c.toChar() else '.')
            i++
        }
        return sb.toString()
    }

    fun matches(off: Int, magic: ByteArray): Boolean {
        if (off < 0 || off + magic.size > size) return false
        for (i in magic.indices) if (data[off + i] != magic[i]) return false
        return true
    }

    fun raw(): ByteArray = data

    companion object {
        val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\003\004
        val PE_MAGIC = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())
    }
}

/** 通用小工具：熵、可打印串提取、十六进制格式化。 */
object ByteTools {

    /** 香农熵（0~8）。打包/加密段的熵通常 > 7.2。 */
    fun entropy(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Double {
        if (length <= 0) return 0.0
        val counts = IntArray(256)
        val end = minOf(data.size, offset + length)
        for (i in offset until end) counts[data[i].toInt() and 0xFF]++
        val total = (end - offset).toDouble()
        if (total <= 0) return 0.0
        var h = 0.0
        for (c in counts) {
            if (c == 0) continue
            val p = c / total
            h -= p * (Math.log(p) / Math.log(2.0))
        }
        return h
    }

    /** 提取可打印 ASCII 串；minLen 默认 6，结果去重并限制条数。 */
    fun asciiStrings(data: ByteArray, minLen: Int = 6, limit: Int = 600): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLen) {
                    val s = sb.toString()
                    if (seen.add(s)) out.add(s)
                    if (out.size >= limit) return out
                }
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen) {
            val s = sb.toString()
            if (seen.add(s)) out.add(s)
        }
        return out
    }

    fun hex(b: Int): String = "%02X".format(b and 0xFF)

    fun hex(data: ByteArray, maxLen: Int = 64): String =
        data.take(maxLen).joinToString(" ") { hex(it.toInt()) } + if (data.size > maxLen) " …" else ""

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
