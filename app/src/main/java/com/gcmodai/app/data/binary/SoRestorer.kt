package com.gcmodai.app.data.binary

/**
 * SO 还原引擎。
 *
 * 对应「Android SO 逆向」文章里的手工流程，这里做成静态自动化：
 *   1. .init_array / DT_INIT：解密动作通常发生在 JNI_OnLoad 之前的初始化函数里
 *   2. JNI 动态注册表：在可写数据段里找 (方法名指针, 签名指针, 实现函数指针) 三元组，
 *      直接得到「Java 方法 → 函数偏移」，不用再手工跟 RegisterNatives
 *   3. 密钥识别：在常量区找「递增序列」这类典型 XOR key（文章样本的 key 就是 0x00..0xFF 循环）
 *   4. 逐字节 XOR 还原：对指定 vaddr 范围应用 key，并用熵变化/指令识别率判断是否解对
 *
 * 全部只读文件、不做任何执行，也不联网。
 */
object SoRestorer {

    /** vaddr → 文件偏移（按 PT_LOAD 段映射；映射不到返回 null）。 */
    fun vaddrToOffset(report: BinaryReport, vaddr: Long): Long? {
        for (seg in report.segments) {
            if (seg.typeName != "PT_LOAD") continue
            if (vaddr >= seg.vaddr && vaddr < seg.vaddr + seg.fileSize) {
                return seg.offset + (vaddr - seg.vaddr)
            }
        }
        return null
    }

    private fun inExecSegment(report: BinaryReport, vaddr: Long): Boolean =
        report.segments.any {
            it.typeName == "PT_LOAD" && it.flags.contains("X") &&
                vaddr >= it.vaddr && vaddr < it.vaddr + it.memSize
        }

    private fun inReadableSegment(report: BinaryReport, vaddr: Long): Boolean =
        report.segments.any {
            it.typeName == "PT_LOAD" && vaddr >= it.vaddr && vaddr < it.vaddr + it.memSize
        }

    // ---------------------------------------------------------------- JNI 注册表

    /**
     * 扫描可写数据段里的 JNINativeMethod 三元组。
     *
     * 判定条件（宁严勿滥，避免把普通数据误报成注册表）：
     *   - 名/签名指针落在可读段内，且指向合法 C 字符串
     *   - 签名以 '(' 开头、含 ')'
     *   - 实现函数指针落在可执行段内
     */
    fun scanJniRegistrations(data: ByteArray, report: BinaryReport): List<JniRegistration> {
        if (report.format != BinaryFormat.ELF) return emptyList()
        val r = ByteReader(data)
        val ptr = if (report.bits == 64) 8 else 4
        val little = report.endian == "小端"
        val out = ArrayList<JniRegistration>()
        val seen = HashSet<Long>()

        fun readPtr(off: Long): Long? {
            val o = off.toInt()
            if (o < 0 || o + ptr > data.size) return null
            return if (ptr == 8) r.u64(o, little) else r.u32(o, little)
        }

        fun cStringAt(vaddr: Long, max: Int = 96): String? {
            val off = vaddrToOffset(report, vaddr) ?: return null
            if (off < 0 || off >= data.size) return null
            val sb = StringBuilder()
            var p = off.toInt()
            val end = minOf(data.size, p + max)
            while (p < end) {
                val c = data[p].toInt() and 0xFF
                if (c == 0) break
                if (c < 0x20 || c > 0x7E) return null       // 含不可打印字符 → 不是字符串
                sb.append(c.toChar())
                p++
            }
            return sb.toString().ifBlank { null }
        }

        for (seg in report.segments) {
            if (seg.typeName != "PT_LOAD" || !seg.flags.contains("W")) continue
            var off = seg.offset
            val end = minOf(seg.offset + seg.fileSize, data.size.toLong())
            // 指针数组按 ptr 对齐
            off -= off % ptr
            var guard = 0
            while (off + ptr * 3 <= end && guard++ < 200_000) {
                val nameV = readPtr(off) ?: break
                val sigV = readPtr(off + ptr) ?: break
                val fnV = readPtr(off + ptr * 2) ?: break
                if (nameV != 0L && sigV != 0L && fnV != 0L &&
                    inReadableSegment(report, nameV) && inReadableSegment(report, sigV) &&
                    inExecSegment(report, fnV) && seen.add(fnV)
                ) {
                    val name = cStringAt(nameV)
                    val sig = cStringAt(sigV)
                    if (name != null && sig != null && sig.startsWith("(") && sig.contains(")")) {
                        out.add(
                            JniRegistration(
                                javaName = name,
                                signature = sig,
                                fnVaddr = fnV,
                                fnOffset = vaddrToOffset(report, fnV),
                                nameVaddr = nameV,
                            )
                        )
                        if (out.size >= 256) return out
                    }
                }
                off += ptr
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 密钥识别

    /**
     * 在常量/数据区识别典型 XOR 密钥。
     *
     * 两类最值得报：
     *   - 递增序列 0x00,0x01,…,0xFF 循环（文章样本用的正是它，直接逐字节异或即可解密函数体）
     *   - 长度 ≥ 32 的常量重复序列（单字节或多字节固定 key）
     */
    fun findKeyCandidates(data: ByteArray, report: BinaryReport): List<KeyCandidate> {
        if (report.format != BinaryFormat.ELF) return emptyList()
        val out = ArrayList<KeyCandidate>()
        val seen = HashSet<String>()

        for (sec in report.sections) {
            if (sec.typeName != "PROGBITS" || sec.size < 32) continue
            if (sec.name == ".text") continue
            val off = sec.offset.toInt()
            if (off <= 0 || off >= data.size) continue
            val end = minOf(data.size, (sec.offset + sec.size).toInt())

            // 递增序列（step=1，长度 ≥ 32）
            var i = off
            while (i + 32 <= end) {
                var len = 0
                while (i + len + 1 < end &&
                    ((data[i + len + 1].toInt() - data[i + len].toInt()) and 0xFF) == 1
                ) {
                    len++
                    if (len >= 512) break
                }
                if (len >= 31) {
                    val kLen = minOf(len + 1, 256)
                    val key = data.copyOfRange(i, i + kLen)
                    val hex = key.joinToString("") { "%02X".format(it) }
                    if (seen.add(hex)) {
                        out.add(
                            KeyCandidate(
                                key = key,
                                source = "${sec.name} @0x${java.lang.Long.toHexString(sec.addr + (i - off))}",
                                note = "递增序列（长 $kLen），典型逐字节 XOR 密钥",
                                vaddr = sec.addr + (i - off),
                            )
                        )
                    }
                    i += len
                }
                i++
            }

            // 常量重复序列（同一字节连续 ≥ 32）
            i = off
            while (i + 32 <= end) {
                var len = 0
                while (i + len + 1 < end && data[i + len + 1] == data[i]) {
                    len++
                    if (len >= 256) break
                }
                if (len >= 31) {
                    val key = byteArrayOf(data[i])
                    val hex = key.joinToString("") { "%02X".format(it) }
                    if (seen.add(hex)) {
                        out.add(
                            KeyCandidate(
                                key = key,
                                source = "${sec.name} @0x${java.lang.Long.toHexString(sec.addr + (i - off))}",
                                note = "单字节常量重复（长 ${len + 1}），可能被当作填充或单字节 key",
                                vaddr = sec.addr + (i - off),
                            )
                        )
                    }
                    i += len
                }
                i++
            }
            if (out.size >= 24) break
        }
        return out
    }

    // ---------------------------------------------------------------- 还原

    data class RestoreResult(
        val data: ByteArray,
        val entropyBefore: Double,
        val entropyAfter: Double,
        val looksRestored: Boolean,
        val detail: String,
    )

    /**
     * 逐字节 XOR 还原指定范围。
     *
     * @param vaddr 目标起始虚拟地址（函数或节区都可）
     * @param size  长度
     * @param key   密钥（循环使用）
     */
    fun restoreXor(
        data: ByteArray,
        report: BinaryReport,
        vaddr: Long,
        size: Long,
        key: ByteArray,
    ): RestoreResult {
        require(key.isNotEmpty()) { "密钥不能为空" }
        val off = vaddrToOffset(report, vaddr)
            ?: return RestoreResult(data, 0.0, 0.0, false, "该地址不在任何 PT_LOAD 段内，无法映射到文件偏移")
        val start = off.toInt()
        val len = minOf(size, (data.size - start).toLong()).toInt()
        if (start < 0 || len <= 0) {
            return RestoreResult(data, 0.0, 0.0, false, "范围非法（起始 $start，长度 $len）")
        }
        val before = ByteTools.entropy(data, start, len)
        val out = data.copyOf()
        for (i in 0 until len) {
            out[start + i] = (out[start + i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val after = ByteTools.entropy(out, start, len)
        // 判断是否"像被解开了"：熵明显下降，或解密后能识别出常见指令
        val sample = out.copyOfRange(start, minOf(start + 256, out.size))
        val looksRestored = after < before - 0.4 || Arm64Lite.disassemble(sample, vaddr, 32)
            .count { !it.text.startsWith(".word") } >= 24
        return RestoreResult(
            data = out,
            entropyBefore = before,
            entropyAfter = after,
            looksRestored = looksRestored,
            detail = "0x${java.lang.Long.toHexString(vaddr)} 起 $len 字节：熵 %.2f → %.2f%s".format(
                before, after, if (looksRestored) "（指令可读，判定已还原）" else "（仍不可读，可能不是这把 key）"
            ),
        )
    }

    /** 汇总一份「还原建议」，交给 UI 与 AI 报告共用。 */
    fun analyze(data: ByteArray, report: BinaryReport): List<String> {
        val notes = ArrayList<String>()
        if (report.format != BinaryFormat.ELF) return notes
        if (report.initArray.isNotEmpty()) {
            notes.add(
                ".init_array 有 ${report.initArray.size} 个初始化函数（先于 JNI_OnLoad 执行）：" +
                    report.initArray.take(6).joinToString("、") { "0x" + java.lang.Long.toHexString(it) } +
                    "；解密逻辑通常就在这里"
            )
        }
        report.dtInit?.let { notes.add("DT_INIT = 0x${java.lang.Long.toHexString(it)}（初始化入口）") }
        val jni = scanJniRegistrations(data, report)
        if (jni.isNotEmpty()) {
            notes.add(
                "静态还原出 ${jni.size} 条 JNI 动态注册：" +
                    jni.take(5).joinToString("；") { "${it.javaName}${it.signature} → 0x${java.lang.Long.toHexString(it.fnVaddr)}" }
            )
        }
        val keys = findKeyCandidates(data, report)
        if (keys.isNotEmpty()) {
            notes.add("发现 ${keys.size} 个疑似 XOR 密钥，最可疑：${keys.first().source}（${keys.first().note}）")
        }
        if (report.sections.any { it.name == ".bss" && it.size > 64 }) {
            notes.add(".bss 里有未初始化数据：若密钥保存在 .bss，静态分析拿不到，需要运行期 dump（本工具不做动态执行）")
        }
        return notes
    }
}
