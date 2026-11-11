package com.gcmodai.app.data.binary

/**
 * 已知明文攻击：破解单字节/多字节循环 XOR 加密。
 *
 * 原理：ELF/SO/PE/DEX/ZIP 都有固定魔数。若整段（或头部）是循环 XOR 加密的，
 * 那么 key[i] = cipher[i] XOR magic[i % magicLen]，再把 key 回代验证后续若干字节，
 * 全部吻合即为命中。这样不需要暴力遍历 256^n。
 */
object XorDecryptor {

    /** 常见魔数与它们的语义，命中后直接告诉用户「解密出来是什么」。 */
    private val KNOWN_MAGICS: List<Pair<String, ByteArray>> = listOf(
        "ELF 动态库/可执行" to ByteReader.ELF_MAGIC,
        "PE 可执行" to ByteReader.PE_MAGIC,
        "DEX 字节码" to ByteReader.DEX_MAGIC,
        "ZIP/APK 归档" to ByteReader.ZIP_MAGIC,
        "PNG 图片" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        "GZIP 压缩" to byteArrayOf(0x1F, 0x8B.toByte()),
        "SQLite 数据库" to "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII),
    )

    data class XorKey(val key: ByteArray, val magic: String, val keyLength: Int) {
        val hex: String get() = key.joinToString("") { "%02X".format(it) }
        fun asText(): String = key.joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c in 32..126) c.toChar().toString() else "\\x%02x".format(c)
        }
    }

    /**
     * 尝试从 [data] 头部恢复循环 XOR 密钥。
     *
     * 约束说明（很重要）：用已知明文只能解出「密钥长度 ≤ 魔数长度」的情况
     * （ELF 4 字节、MZ 2 字节、dex 4 字节、PK 4 字节），因为超出魔数长度的密钥位
     * 没有已知明文可对照。更长的密钥请用手动密钥输入。
     *
     * 防误报：只对魔数是不够的（任意两字节都能「解」成 MZ），所以每种格式都有
     * 结构化后置校验，见 [looksValid]。
     */
    fun recoverKey(data: ByteArray, maxKeyLen: Int = 32, verifyBytes: Int = 256): XorKey? {
        if (data.size < 16) return null
        for ((label, magic) in KNOWN_MAGICS) {
            val limit = minOf(maxKeyLen, magic.size, data.size / 4)
            for (keyLen in 1..limit) {
                val key = ByteArray(keyLen) { i -> (data[i].toInt() xor magic[i % magic.size].toInt()).toByte() }
                if (key.all { it == 0.toByte() }) continue     // 全 0 = 根本没加密
                if (looksValid(data, key, label)) return XorKey(key, label, keyLen)
            }
        }
        return null
    }

    /** 用 key 解出前 [n] 字节明文。 */
    private fun head(data: ByteArray, key: ByteArray, n: Int): IntArray =
        IntArray(minOf(n, data.size)) { i -> (data[i].toInt() xor key[i % key.size].toInt()) and 0xFF }

    private fun ascii(h: IntArray, from: Int, len: Int): String {
        if (from + len > h.size) return ""
        return (from until from + len).map { h[it].toChar() }.joinToString("")
    }

    private fun u32le(h: IntArray, off: Int): Long {
        if (off + 4 > h.size) return -1
        return (h[off].toLong()) or (h[off + 1].toLong() shl 8) or
            (h[off + 2].toLong() shl 16) or (h[off + 3].toLong() shl 24)
    }

    private fun u16le(h: IntArray, off: Int): Int {
        if (off + 2 > h.size) return -1
        return h[off] or (h[off + 1] shl 8)
    }

    /**
     * 结构化校验：解出来的明文必须像一个真的该种文件。
     * 这一步把「魔数巧合」全部挡掉（例如未加密的 ELF 不该被认成 MZ）。
     */
    private fun looksValid(data: ByteArray, key: ByteArray, label: String): Boolean {
        val h = head(data, key, 128)
        fun startsWith(vararg bytes: Int): Boolean {
            if (h.size < bytes.size) return false
            for (i in bytes.indices) if (h[i] != bytes[i]) return false
            return true
        }
        return when {
            label.startsWith("ELF") -> {
                if (!startsWith(0x7F, 0x45, 0x4C, 0x46)) return false
                if (h.size < 20) return false
                if (h[4] !in 1..2) return false          // EI_CLASS
                if (h[5] !in 1..2) return false          // EI_DATA
                val eType = u16le(h, 16)
                val eMachine = u16le(h, 18)
                eType in 1..4 && eMachine in setOf(0x03, 0x08, 0x28, 0x3E, 0xB7, 0xF3)
            }

            label.startsWith("PE") -> {
                if (!startsWith(0x4D, 0x5A)) return false
                val peOff = u32le(h, 0x3C)
                // e_lfanew 必须落在文件内，且偏移处确实是 "PE\0\0"
                if (peOff <= 0 || peOff > data.size - 4) return false
                val full = head(data, key, minOf(data.size, peOff.toInt() + 4))
                ascii(full, peOff.toInt(), 4) == "PE\u0000\u0000"
            }

            label.startsWith("DEX") -> {
                if (!startsWith(0x64, 0x65, 0x78, 0x0A)) return false
                // 版本号必须是 "0xx"
                val v = ascii(h, 4, 3)
                v.length == 3 && v[0] == '0' && v.drop(1).all { it.isDigit() }
            }

            label.startsWith("ZIP") -> {
                if (!startsWith(0x50, 0x4B, 0x03, 0x04)) return false
                // 本地文件头里文件名/扩展字段长度必须合理
                val nameLen = u16le(h, 26)
                val extraLen = u16le(h, 28)
                nameLen in 1..1024 && extraLen in 0..4096
            }

            label.startsWith("PNG") -> {
                // 注意：密钥长度等于魔数长度时，明文必然等于魔数（自我印证），
                // 所以必须再往后看一段真实结构，否则会误报。
                startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) && ascii(h, 12, 4) == "IHDR"
            }

            label.startsWith("GZIP") -> {
                if (!startsWith(0x1F, 0x8B, 0x08)) return false
                // 第 4 字节是 flags，保留位 0xE0 必须为 0
                h.size > 3 && (h[3] and 0xE0) == 0
            }

            label.startsWith("SQLite") -> {
                if (ascii(h, 0, 15) != "SQLite format 3") return false
                // 魔数后紧跟页大小（大端，必须是 512..65536 的 2 的幂），再后两个字节是版本号 1/2
                if (h.size < 20) return false
                val pageSize = (h[16] shl 8) or h[17]
                val valid = pageSize == 65536 || (pageSize in 512..32768 && (pageSize and (pageSize - 1)) == 0)
                valid && h[18] in 1..2 && h[19] in 1..2
            }

            else -> false
        }
    }

    /** 用 key 回代验证：前 [len] 个字节必须全部等于 magic 的循环展开。 */
    fun verify(data: ByteArray, key: ByteArray, magic: ByteArray, len: Int): Boolean {
        if (key.isEmpty()) return false
        for (i in 0 until minOf(len, data.size)) {
            val plain = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            if (plain != magic[i % magic.size]) return false
        }
        return true
    }

    /** 对整个数组做循环 XOR（加密与解密是同一操作）。 */
    fun apply(data: ByteArray, key: ByteArray, startOffset: Int = 0): ByteArray {
        if (key.isEmpty()) return data
        val out = data.copyOf()
        for (i in startOffset until out.size) {
            out[i] = (out[i].toInt() xor key[(i - startOffset) % key.size].toInt()).toByte()
        }
        return out
    }

    /** 解析用户手输的 key：支持 "0x12 34 AB"、"1234AB"、"\\x12\\x34" 等写法。 */
    fun parseKey(text: String): ByteArray? {
        val cleaned = text.replace("0x", " ", ignoreCase = true)
            .replace("\\x", " ", ignoreCase = true)
            .replace(",", " ")
            .trim()
        if (cleaned.isEmpty()) return null
        // 纯十六进制字符串（无分隔符）
        if (!cleaned.contains(" ") && cleaned.length % 2 == 0 && cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return runCatching {
                ByteArray(cleaned.length / 2) { i ->
                    cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val bytes = ArrayList<Byte>()
        for (p in parts) {
            val v = p.toIntOrNull(16) ?: return null
            if (v !in 0..255) return null
            bytes.add(v.toByte())
        }
        return if (bytes.isEmpty()) null else bytes.toByteArray()
    }

    // ---------------------------------------------------------------------
    // 节区级探测
    // ---------------------------------------------------------------------

    data class SingleByteResult(
        val key: Int,
        val entropyBefore: Double,
        val entropyAfter: Double,
        val hint: String,
    ) {
        val keyHex: String get() = "%02X".format(key)
    }

    /** 解密后能认出来的容器/文件头，用来判断「这个 key 是不是对的」。 */
    private fun containerHint(head: ByteArray): String? {
        fun startsWith(vararg bs: Int): Boolean {
            if (head.size < bs.size) return false
            for (i in bs.indices) if ((head[i].toInt() and 0xFF) != bs[i]) return false
            return true
        }
        return when {
            startsWith(0x1F, 0x8B) -> "GZIP 压缩数据"
            startsWith(0x78, 0x01) || startsWith(0x78, 0x9C) || startsWith(0x78, 0xDA) -> "ZLIB 压缩数据"
            startsWith(0x5D, 0x00, 0x00) -> "LZMA 压缩数据"
            startsWith(0x7F, 0x45, 0x4C, 0x46) -> "ELF 文件"
            startsWith(0x50, 0x4B, 0x03, 0x04) -> "ZIP 归档"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "PNG 图片"
            startsWith(0x64, 0x65, 0x78, 0x0A) -> "DEX 字节码"
            else -> null
        }
    }

    /**
     * 单字节 XOR 暴力：找出让「熵明显下降」或「解出已知容器头」的那个 key。
     *
     * 为什么需要它：很多加固/混淆只对某个节区（例如 .rodata）做单字节 XOR，
     * 整文件魔数还是好的——这时整文件已知明文攻击找不到 key，必须按节区来试。
     * 判据用「熵下降 + 能认出容器头」，避免把随机噪声当成成功。
     */
    fun bruteForceSingleByte(section: ByteArray, sampleSize: Int = 200_000): SingleByteResult? {
        if (section.size < 64) return null
        val sample = if (section.size > sampleSize) section.copyOf(sampleSize) else section
        val before = ByteTools.entropy(sample)
        // 熵本来就不高说明没加密，不必试
        if (before < 6.5) return null

        var best: SingleByteResult? = null
        for (k in 1..255) {
            val decoded = ByteArray(minOf(sample.size, 4096)) { i -> (sample[i].toInt() xor k).toByte() }
            val hint = containerHint(decoded)
            val afterEntropyFull = ByteTools.entropy(ByteArray(sample.size) { i -> (sample[i].toInt() xor k).toByte() })
            val candidate = SingleByteResult(
                key = k,
                entropyBefore = before,
                entropyAfter = afterEntropyFull,
                hint = hint ?: "",
            )
            if (hint != null) return candidate          // 解出已知容器头：直接判定成功
            // 否则仅在熵显著下降时保留最优解（下降 0.8 以上才算有信号）
            if (before - afterEntropyFull >= 0.8) {
                if (best == null || afterEntropyFull < best.entropyAfter) best = candidate
            }
        }
        return best
    }
}

/**
 * 二进制编辑：按偏移改写字节、替换字符串、另存为新文件。
 *
 * 所有写操作都返回新数组，不原地改原件——原件要么来自用户的外部文件，
 * 要么是上一步解密的产物，误改会让整个分析链条失去参照。
 */
object BinaryEditor {

    data class PatchResult(
        val data: ByteArray,
        /** 实际被改写的字节数 */
        val changedBytes: Int,
        /** 命中/写入的次数（字节写入算 1 次，字符串替换算命中的处数） */
        val count: Int,
        val description: String,
    )

    fun patchAt(data: ByteArray, offset: Int, replacement: ByteArray): PatchResult {
        require(offset >= 0 && offset < data.size) { "偏移越界：$offset（文件 ${data.size} 字节）" }
        val out = data.copyOf()
        val len = minOf(replacement.size, out.size - offset)
        System.arraycopy(replacement, 0, out, offset, len)
        return PatchResult(out, len, 1, "在 0x${java.lang.Integer.toHexString(offset)} 写入 $len 字节")
    }

    /** 替换全部出现的字符串（限长保护，避免把整个文件扫爆）。 */
    fun replaceString(
        data: ByteArray,
        find: String,
        replace: String,
        limit: Int = 10_000,
    ): PatchResult {
        require(find.isNotEmpty()) { "要查找的内容不能为空" }
        require(find.toByteArray().size == replace.toByteArray().size) {
            "替换前后长度必须一致（${find.toByteArray().size} vs ${replace.toByteArray().size}），" +
                "否则会破坏 ELF 内部偏移"
        }
        val src = find.toByteArray(Charsets.UTF_8)
        val dst = replace.toByteArray(Charsets.UTF_8)
        val out = data.copyOf()
        var count = 0
        var i = 0
        val end = minOf(out.size - src.size, limit)
        while (i <= end) {
            var hit = true
            for (j in src.indices) if (out[i + j] != src[j]) { hit = false; break }
            if (hit) {
                System.arraycopy(dst, 0, out, i, dst.size)
                count++
                i += src.size
            } else {
                i++
            }
        }
        return PatchResult(out, count * dst.size, count, "替换 \"$find\" → \"$replace\" 共 $count 处")
    }
}
