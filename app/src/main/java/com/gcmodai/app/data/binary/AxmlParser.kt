package com.gcmodai.app.data.binary

/**
 * Android 二进制 XML（AXML）解析，用于读 APK 里的 AndroidManifest.xml。
 *
 * 为什么需要它：APK 里的清单不是文本 XML，而是编译后的 chunk 结构。
 * 安卓逆向第一步就是拿到 package / 权限 / 导出组件 / debuggable，
 * 这几项直接决定「这个包能不能调、有没有对外暴露面」。
 */
object AxmlParser {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    class Attr(val name: String, val value: String, val rawType: Int)
    class Element(val name: String, val attrs: List<Attr>) {
        fun attr(name: String): Attr? = attrs.firstOrNull { it.name == name }
    }

    /** 解析出所有元素（扁平列表，含层级顺序）。 */
    fun parseElements(data: ByteArray): List<Element> {
        val r = ByteReader(data)
        if (data.size < 8) return emptyList()
        val out = ArrayList<Element>()
        var strings = ArrayList<String>()

        var pos = 8 // 跳过 XML 文件头
        while (pos + 8 <= data.size) {
            val chunkType = r.u16(pos, true)
            val headerSize = r.u16(pos + 2, true)
            val chunkSize = r.u32(pos + 4, true).toInt()
            if (chunkSize <= 0 || pos + chunkSize > data.size + 8) break

            when (chunkType) {
                RES_STRING_POOL -> strings = parseStringPool(r, pos)
                RES_XML_START_ELEMENT -> {
                    runCatching { parseStartElement(r, pos, headerSize, strings)?.let { out.add(it) } }
                }
                RES_XML_END_ELEMENT -> { /* 结构信息对本场景无用 */ }
            }
            pos += chunkSize
            if (chunkSize == 0) break
        }
        return out
    }

    private fun parseStringPool(r: ByteReader, start: Int): ArrayList<String> {
        val count = r.u32(start + 8, true).toInt()
        val flags = r.u32(start + 16, true)
        val stringsStart = r.u32(start + 20, true).toInt()
        val utf8 = flags and 0x100L != 0L
        val list = ArrayList<String>(minOf(count, 60_000))
        val offsets = IntArray(minOf(count, 60_000)) { i -> r.u32(start + 28 + i * 4, true).toInt() }
        for (off in offsets) {
            val abs = start + stringsStart + off
            if (abs <= 0 || abs >= r.size) {
                list.add("")
                continue
            }
            runCatching {
                var p = abs
                if (utf8) {
                    // UTF-8：先 1~2 字节的字符数，再 1~2 字节的字节数
                    var skip = 0
                    while (skip < 2) {
                        val b = r.u8(p)
                        p++
                        if (b and 0x80 == 0) break
                        p++ // 第二字节
                        skip++
                    }
                    val lenByte = r.u8(p); p++
                    val len = if (lenByte and 0x80 != 0) ((lenByte and 0x7F) shl 8) or r.u8(p).also { p++ } else lenByte
                    list.add(String(r.bytes(p, len), Charsets.UTF_8))
                } else {
                    val len = r.u16(p, true)
                    p += 2
                    val bytes = r.bytes(p, len * 2)
                    list.add(String(bytes, Charsets.UTF_16LE))
                }
            }.onFailure { list.add("") }
        }
        return list
    }

    private fun parseStartElement(r: ByteReader, start: Int, headerSize: Int, strings: List<String>): Element? {
        val ext = start + headerSize
        val nameIdx = r.u32(ext + 4, true).toInt()
        val attrStart = r.u16(ext + 8, true)
        val attrSize = r.u16(ext + 10, true)
        val attrCount = r.u16(ext + 12, true)
        if (attrSize <= 0 || attrCount > 200) return null

        val name = strings.getOrElse(nameIdx) { "?" }
        val attrs = ArrayList<Attr>()
        var p = ext + attrStart
        for (i in 0 until attrCount) {
            val base = p + i * attrSize
            if (base + 20 > r.size) break
            val aNameIdx = r.u32(base + 4, true).toInt()
            val rawValueIdx = r.u32(base + 8, true).toInt()
            val dataType = r.u8(base + 15)
            val data = r.u32(base + 16, true)
            val aName = strings.getOrElse(aNameIdx) { "?" }
            val value = when (dataType) {
                TYPE_STRING -> strings.getOrElse(if (rawValueIdx >= 0) rawValueIdx else data.toInt()) { "" }
                TYPE_INT_BOOLEAN -> if (data != 0L) "true" else "false"
                TYPE_INT_DEC -> data.toString()
                TYPE_INT_HEX -> "0x" + java.lang.Long.toHexString(data)
                TYPE_REFERENCE -> "@res/0x" + java.lang.Long.toHexString(data)
                else -> if (rawValueIdx >= 0) strings.getOrElse(rawValueIdx) { "" } else data.toString()
            }
            attrs.add(Attr(aName, value, dataType))
        }
        return Element(name, attrs)
    }

    /** 从元素列表里抽取关键清单信息。 */
    fun readManifest(data: ByteArray): ApkManifest? {
        val elements = parseElements(data)
        if (elements.isEmpty()) return null
        val manifest = elements.firstOrNull { it.name == "manifest" } ?: return null
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
        val app = elements.firstOrNull { it.name == "application" }
        val permissions = elements.filter { it.name == "uses-permission" || it.name == "uses-permission-sdk-23" }
            .mapNotNull { it.attr("name")?.value }
            .filter { it.isNotBlank() }

        fun intOr(value: String?, default: Int): Int = value?.toIntOrNull() ?: default

        val exported = ArrayList<String>()
        for (e in elements) {
            if (e.name !in setOf("activity", "activity-alias", "service", "receiver", "provider")) continue
            val compName = e.attr("name")?.value ?: continue
            val isExported = e.attr("exported")?.value
            // Android 12+ 要求显式声明；没写 exported 但带 intent-filter 的组件默认对外
            val hasFilter = false
            if (isExported == "true" || (isExported == null && hasFilter)) {
                exported.add("${e.name}: $compName")
            }
        }

        return ApkManifest(
            packageName = manifest.attr("package")?.value.orEmpty(),
            versionName = manifest.attr("versionName")?.value.orEmpty(),
            versionCode = manifest.attr("versionCode")?.value.orEmpty(),
            minSdk = intOr(usesSdk?.attr("minSdkVersion")?.value, 0),
            targetSdk = intOr(usesSdk?.attr("targetSdkVersion")?.value, 0),
            permissions = permissions.distinct(),
            exportedComponents = exported,
            hasDebuggable = app?.attr("debuggable")?.value == "true",
        )
    }
}
