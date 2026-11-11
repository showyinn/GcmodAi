package com.gcmodai.app.data.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二进制分析能力的契约测试。
 *
 * 这些解析器一旦把偏移读错，产出的报告会「看起来很对但全是错的」，比报错更危险。
 * 所以这里用可精确构造的字节序列，把关键字段逐项钉死。
 */
class BinaryAnalyserTest {

    // ---------------------------------------------------------------- 构造工具

    private fun ByteArray.putLE(off: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) this[off + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun elf64Aarch64(): ByteArray {
        val size = 256
        val b = ByteArray(size)
        // e_ident
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2      // 64 位
        b[5] = 1      // 小端
        b[6] = 1      // 版本
        b.putLE(16, 3, 2)          // e_type = ET_DYN
        b.putLE(18, 0xB7, 2)       // e_machine = AArch64
        b.putLE(20, 1, 4)          // e_version
        b.putLE(24, 0x1234L, 8)    // e_entry
        b.putLE(52, 64, 2)         // e_ehsize
        b.putLE(54, 56, 2)         // e_phentsize
        b.putLE(58, 64, 2)         // e_shentsize
        return b
    }

    private fun dexHeader(classCount: Int = 0): ByteArray {
        val b = ByteArray(0x70 + 64)
        'd'.code.toByte().let { b[0] = it }
        'e'.code.toByte().let { b[1] = it }
        'x'.code.toByte().let { b[2] = it }
        b[3] = '\n'.code.toByte()
        b[4] = '0'.code.toByte(); b[5] = '3'.code.toByte(); b[6] = '5'.code.toByte(); b[7] = 0
        b.putLE(32, b.size.toLong(), 4)   // file_size
        b.putLE(56, 0, 4)                 // string_ids_size
        b.putLE(64, 0, 4)                 // type_ids_size
        b.putLE(0x60, classCount.toLong(), 4) // class_defs_size
        b.putLE(0x64, 0x70, 4)            // class_defs_off
        return b
    }

    // ---------------------------------------------------------------- 格式识别

    @Test
    fun `detects elf dex zip pe`() {
        assertEquals(BinaryFormat.ELF, BinaryAnalyser.detect(ByteReader(elf64Aarch64())))
        assertEquals(BinaryFormat.DEX, BinaryAnalyser.detect(ByteReader(dexHeader())))
        assertEquals(
            BinaryFormat.ZIP,
            BinaryAnalyser.detect(ByteReader(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        )
        assertEquals(
            BinaryFormat.PE,
            BinaryAnalyser.detect(ByteReader(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)))
        )
        assertEquals(BinaryFormat.UNKNOWN, BinaryAnalyser.detect(ByteReader(ByteArray(32))))
    }

    // ---------------------------------------------------------------- ELF

    @Test
    fun `elf header fields are parsed correctly`() {
        val report = BinaryAnalyser.analyse(elf64Aarch64(), "libtest.so")
        assertEquals(BinaryFormat.ELF, report.format)
        assertEquals(64, report.bits)
        assertEquals("小端", report.endian)
        assertTrue("架构应识别为 AArch64，实际 ${report.arch}", report.arch.contains("AArch64"))
        assertEquals(0x1234L, report.entryPoint)
        assertTrue(report.summary.contains("ELF") || report.summary.contains("共享库") || report.summary.contains("DYN"))
        assertEquals(64, report.sha256.length)
    }

    @Test
    fun `elf markdown report contains key sections`() {
        val report = BinaryAnalyser.analyse(elf64Aarch64(), "libtest.so")
        val md = report.toMarkdown()
        assertTrue(md.contains("二进制分析报告"))
        assertTrue(md.contains("AArch64"))
        assertTrue(md.contains("SHA256"))
    }

    // ---------------------------------------------------------------- DEX

    @Test
    fun `dex header without classes is parsed`() {
        val report = BinaryAnalyser.analyse(dexHeader(), "classes.dex")
        assertEquals(BinaryFormat.DEX, report.format)
        assertTrue(report.summary.contains("DEX"))
        assertTrue(report.dexClasses.isEmpty())
        // 空类表应提示可能是壳
        assertTrue(report.packerHints.any { it.contains("壳") || it.contains("dex") })
    }

    // ---------------------------------------------------------------- XOR 解密

    @Test
    fun `xor key is recovered from elf magic`() {
        val key = byteArrayOf(0x12, 0x34, 0x56)
        val plain = elf64Aarch64()
        val cipher = XorDecryptor.apply(plain, key)

        val found = XorDecryptor.recoverKey(cipher)
        assertNotNull("应当能从 ELF 魔数恢复密钥", found)
        assertEquals(3, found!!.keyLength)
        assertArrayEqualsHex(key, found.key)

        val restored = XorDecryptor.apply(cipher, found.key)
        assertTrue(ByteReader(restored).matches(0, ByteReader.ELF_MAGIC))
    }

    @Test
    fun `plain file yields no xor key`() {
        assertNull("未加密的文件不应误判", XorDecryptor.recoverKey(elf64Aarch64()))
    }

    @Test
    fun `manual key parsing accepts common formats`() {
        assertArrayEqualsHex(byteArrayOf(0x1A, 0x2B), XorDecryptor.parseKey("1A2B")!!)
        assertArrayEqualsHex(byteArrayOf(0x1A, 0x2B), XorDecryptor.parseKey("0x1A 0x2B")!!)
        assertArrayEqualsHex(byteArrayOf(0x1A, 0x2B), XorDecryptor.parseKey("\\x1A\\x2B")!!)
        assertNull(XorDecryptor.parseKey("zzz"))
    }

    private fun assertArrayEqualsHex(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.joinToString("") { "%02X".format(it) }, actual.joinToString("") { "%02X".format(it) })
    }

    // ---------------------------------------------------------------- 编辑

    @Test
    fun `binary editor patches bytes and rejects length mismatch`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val patched = BinaryEditor.patchAt(data, 2, byteArrayOf(9, 8))
        assertEquals(9.toByte(), patched.data[2])
        assertEquals(8.toByte(), patched.data[3])
        assertEquals(2, patched.changedBytes)

        val text = "GCmodAI".toByteArray()
        val replaced = BinaryEditor.replaceString(text, "GCmod", "GCMOD")
        assertEquals("应命中 1 处", 1, replaced.count)
        assertEquals(5, replaced.changedBytes)
        assertEquals("GCMODAI", String(replaced.data))

        // 长度不一致必须拒绝，否则会破坏内部偏移
        var threw = false
        try {
            BinaryEditor.replaceString(text, "GC", "GCX")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("长度不一致应当抛异常", threw)
    }

    // ---------------------------------------------------------------- 反汇编

    @Test
    fun `arm64 common instructions are decoded`() {
        assertEquals("ret", Arm64Lite.decode(0xD65F03C0.toInt(), 0))
        assertEquals("nop", Arm64Lite.decode(0xD503201F.toInt(), 0))
        // bl +0x10
        val bl = 0x94000004.toInt()
        val text = Arm64Lite.decode(bl, 0x1000)
        assertTrue("bl 应带目标地址：$text", text.startsWith("bl 0x") && text.contains("1010"))

        // mov x0, #0x1
        assertEquals("mov x0, #0x1", Arm64Lite.decode(0xD2800020.toInt(), 0))
        // 未知字保留原始编码
        assertTrue(Arm64Lite.decode(0x00000000, 0).startsWith(".word"))
    }

    @Test
    fun `disassemble walks the buffer word by word`() {
        val code = byteArrayOf(
            0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(), // ret
            0x1F, 0x20, 0x03, 0xD5.toByte(),          // nop
        )
        val lines = Arm64Lite.disassemble(code, 0x2000)
        assertEquals(2, lines.size)
        assertEquals("ret", lines[0].text)
        assertEquals("nop", lines[1].text)
        assertEquals(0x2004L, lines[1].address)
    }

    // ---------------------------------------------------------------- 平台信息

    @Test
    fun `unknown binary produces honest report instead of failing`() {
        val junk = ByteArray(64) { (it * 7).toByte() }
        val report = BinaryAnalyser.analyse(junk, "mystery.bin")
        assertEquals(BinaryFormat.UNKNOWN, report.format)
        assertTrue(report.summary.contains("无法识别"))
        assertFalse(report.toMarkdown().isBlank())
    }
}
