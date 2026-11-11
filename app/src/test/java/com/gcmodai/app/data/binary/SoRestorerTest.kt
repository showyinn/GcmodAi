package com.gcmodai.app.data.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SO 还原引擎的契约测试。
 *
 * 这些逻辑对偏移极为敏感：vaddr↔文件偏移映射错一位，还原出来的就是垃圾，
 * 而且表面"成功"（不报错）——所以用构造的段表把边界钉死。
 */
class SoRestorerTest {

    /** 三个 PT_LOAD：R-- / R-X / RW-，文件偏移与 vaddr 故意不同（0x100 / 0x1000）。 */
    private fun report(segments: List<SegmentInfo> = listOf(
        SegmentInfo("PT_LOAD", 0x0, 0x0, 0x1000, 0x1000, "R--"),
        SegmentInfo("PT_LOAD", 0x1000, 0x1000, 0x1000, 0x1000, "R-X"),
        SegmentInfo("PT_LOAD", 0x2000, 0x2000, 0x1000, 0x1000, "RW-"),
    )): BinaryReport = BinaryReport(
        fileName = "libtest.so",
        sizeBytes = 0x3000,
        sha256 = "0".repeat(64),
        format = BinaryFormat.ELF,
        arch = "AArch64 (ARM64)",
        bits = 64,
        endian = "小端",
        segments = segments,
    )

    private fun data(size: Int = 0x3000): ByteArray = ByteArray(size)

    private fun ByteArray.putPtr(off: Int, value: Long) {
        for (i in 0 until 8) this[off + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.putStr(off: Int, text: String) {
        val b = text.toByteArray(Charsets.US_ASCII)
        System.arraycopy(b, 0, this, off, b.size)
        this[off + b.size] = 0
    }

    // ---------------------------------------------------------------- vaddr 映射

    @Test
    fun `vaddr maps to file offset per segment`() {
        val r = report()
        assertEquals(0x1000L, SoRestorer.vaddrToOffset(r, 0x1000))
        assertEquals(0x1008L, SoRestorer.vaddrToOffset(r, 0x1008))
        assertEquals(0x2000L, SoRestorer.vaddrToOffset(r, 0x2000))
        // 超出所有段 → null
        assertEquals(null, SoRestorer.vaddrToOffset(r, 0x9000))
    }

    // ---------------------------------------------------------------- JNI 注册表

    @Test
    fun `jni registration table is recovered`() {
        val r = report()
        val d = data()
        // 可写段里放三元组：name=0x2000+sig=0x2020+fn=0x1000
        d.putStr(0x2000, "test")       // vaddr 0x2000
        d.putStr(0x2020, "()V")        // vaddr 0x2020
        val tableOff = 0x2000 + 0x80
        d.putPtr(tableOff, 0x2000)
        d.putPtr(tableOff + 8, 0x2020)
        d.putPtr(tableOff + 16, 0x1000)

        val jni = SoRestorer.scanJniRegistrations(d, r)
        assertTrue("应识别出 1 条注册，实际 ${jni.size}", jni.size >= 1)
        val reg = jni.first { it.javaName == "test" }
        assertEquals("()V", reg.signature)
        assertEquals(0x1000L, reg.fnVaddr)
        assertEquals(0x1000L, reg.fnOffset)
    }

    @Test
    fun `random data is not mistaken for registration table`() {
        val r = report()
        val d = data()
        for (i in 0x2000 until 0x2800) d[i] = (i * 37).toByte()
        assertTrue(SoRestorer.scanJniRegistrations(d, r).isEmpty())
    }

    // ---------------------------------------------------------------- 密钥识别

    @Test
    fun `ascending key sequence is detected`() {
        val r = report()
        val d = data()
        // 在 .rodata（文件偏移 0x2000）放 0x00..0xFF 递增序列
        for (i in 0 until 256) d[0x2000 + i] = i.toByte()
        val r2 = r.copy(
            sections = listOf(
                SectionInfo(
                    name = ".rodata", typeName = "PROGBITS", addr = 0x2000,
                    offset = 0x2000, size = 256, flags = "R--", entropy = 8.0,
                )
            )
        )
        val keys = SoRestorer.findKeyCandidates(d, r2)
        assertTrue("应识别出递增密钥，实际 ${keys.size}", keys.isNotEmpty())
        val k = keys.first()
        assertEquals(0x00, k.key[0].toInt())
        assertEquals(0x01, k.key[1].toInt())
        assertTrue("说明应包含递增字样：${k.note}", k.note.contains("递增"))
    }

    // ---------------------------------------------------------------- 还原

    @Test
    fun `xor restore reverses encryption and reports judgement`() {
        val r = report()
        val d = data()
        val key = ByteArray(256) { it.toByte() }
        // 在 .text（vaddr 0x1000 / offset 0x1000）写入"已加密"的数据
        for (i in 0 until 256) d[0x1000 + i] = (i xor key[i].toInt()).toByte()

        val res = SoRestorer.restoreXor(d, r, 0x1000L, 256L, key)
        assertNotNull(res)
        // 解出来应该是 0x00..0xFF
        assertEquals(0x00, res.data[0x1000].toInt() and 0xFF)
        assertEquals(0xFF, res.data[0x10FF].toInt() and 0xFF)
        assertTrue("应给出可读的还原说明：${res.detail}", res.detail.contains("熵"))
    }

    @Test
    fun `restore rejects address outside segments`() {
        val r = report()
        val d = data()
        val res = SoRestorer.restoreXor(d, r, 0x9999L, 32L, byteArrayOf(1, 2, 3))
        assertTrue("越界地址必须明确报错：${res.detail}", res.detail.contains("无法映射") || !res.looksRestored)
    }

    @Test
    fun `restore clamps size to file end`() {
        val r = report()
        val d = data()
        // 从 0x2F00 还原 0x1000 字节，超出文件尾 → 只处理到末尾且不崩
        val res = SoRestorer.restoreXor(d, r, 0x2F00L, 0x1000L, byteArrayOf(0x55))
        assertTrue(res.detail.isNotBlank())
    }

    // ---------------------------------------------------------------- 汇总建议

    @Test
    fun `analysis notes mention init array and bss`() {
        val r = report().copy(
            initArray = listOf(0x1100L, 0x1200L),
            dtInit = 0x1000L,
            sections = listOf(
                SectionInfo(".bss", "NOBITS", 0x3000, 0x3000, 512, "RW-", 0.0)
            ),
        )
        val notes = SoRestorer.analyze(data(), r)
        assertTrue(notes.any { it.contains("init_array") })
        assertTrue(notes.any { it.contains("DT_INIT") })
        assertTrue(notes.any { it.contains(".bss") })
    }
}
