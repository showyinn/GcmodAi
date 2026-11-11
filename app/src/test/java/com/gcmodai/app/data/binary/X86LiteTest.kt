package com.gcmodai.app.data.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * x86-64 轻量反汇编与节区级解密探测的契约。
 *
 * 反汇编器最怕的两件事：认错指令长度导致整体错位、以及遇到不认识就崩。
 * 所以这里既验具体指令，也验「步长必须连续」。
 */
class X86LiteTest {

    private fun decode(vararg bytes: Int, pc: Long = 0x1000): X86Lite.Decoded =
        X86Lite.decodeOne(ByteArray(bytes.size) { bytes[it].toByte() }, 0, pc)

    @Test
    fun `decodes common prologue and epilogue`() {
        assertEquals("push rbp", decode(0x55).text)
        assertEquals("mov rbp, rsp", decode(0x48, 0x89, 0xE5).text)
        assertEquals("pop rbp", decode(0x5D).text)
        assertEquals("ret", decode(0xC3).text)
        assertEquals("leave", decode(0xC9).text)
        assertEquals("nop", decode(0x90).text)
        assertEquals("int3", decode(0xCC).text)
    }

    @Test
    fun `decodes call jmp and conditional jumps with targets`() {
        val call = decode(0xE8, 0x10, 0x00, 0x00, 0x00, pc = 0x1000)
        assertEquals("call 0x1015", call.text)
        assertEquals(5, call.length)

        val jmpShort = decode(0xEB, 0x0E, pc = 0x2000)
        assertEquals("jmp 0x2010", jmpShort.text)

        val jeShort = decode(0x74, 0x05, pc = 0x3000)
        assertEquals("je 0x3007", jeShort.text)
    }

    @Test
    fun `decodes rip relative mov`() {
        // 48 8B 05 10 00 00 00 -> mov rax, [rip+0x10]（mod=0, rm=5 → disp32）
        val d = decode(0x48, 0x8B, 0x05, 0x10, 0x00, 0x00, 0x00)
        assertTrue("rip 相对寻址应解出内存操作数：${d.text}", d.text.startsWith("mov rax, [0x"))
        assertEquals(7, d.length)
    }

    @Test
    fun `decodes endbr64 and mov immediate`() {
        assertEquals("endbr64", decode(0xF3, 0x0F, 0x1E, 0xFA).text)
        // 48 C7 C0 01 00 00 00 是 mov rax, imm32（group 扩展），这里验证 0xB8 形式
        val mov32 = decode(0xB8, 0x2A, 0x00, 0x00, 0x00)
        assertEquals("mov eax, 0x2a", mov32.text)
        val mov64 = decode(0x48, 0xB8, 1, 2, 3, 4, 5, 6, 7, 8)
        assertTrue("64 位立即数 mov 长度应为 10：${mov64.length}", mov64.length == 10)
    }

    @Test
    fun `disassembly steps stay in sync`() {
        // push rbp / mov rbp,rsp / ret
        val code = byteArrayOf(0x55, 0x48, 0x89.toByte(), 0xE5.toByte(), 0xC3.toByte())
        val lines = X86Lite.disassemble(code, 0x4000)
        assertEquals(3, lines.size)
        assertEquals("push rbp", lines[0].text)
        assertEquals("mov rbp, rsp", lines[1].text)
        assertEquals("ret", lines[2].text)
        // 地址必须严格递增且连续（不错位）
        assertEquals(0x4000L, lines[0].address)
        assertEquals(0x4001L, lines[1].address)
        assertEquals(0x4004L, lines[2].address)
    }

    @Test
    fun `unknown bytes are preserved without crashing`() {
        val code = ByteArray(16) { 0x0F.toByte() }   // 0F 是转义前缀，未实现的具体指令应回退
        val lines = X86Lite.disassemble(code, 0)
        assertTrue("必须有输出", lines.isNotEmpty())
        assertTrue("未识别时应保留原始字节：${lines.first().text}", lines.first().text.startsWith(".byte"))
    }

    // ---------------------------------------------------------------- 节区探测

    @Test
    fun `single byte xor brute force finds gzip key`() {
        // 造一段高熵数据，把前两字节改成「用 0x5A 加密过的 gzip 头」
        val random = Random(42)
        val data = ByteArray(4096) { random.nextInt(256).toByte() }
        val key = 0x5A
        data[0] = (0x1F xor key).toByte()
        data[1] = (0x8B xor key).toByte()

        val result = XorDecryptor.bruteForceSingleByte(data)
        assertNotNull("应当命中密钥", result)
        assertEquals(key, result!!.key)
        assertTrue("应识别出 GZIP：${result.hint}", result.hint.contains("GZIP"))
    }

    @Test
    fun `brute force ignores low entropy data`() {
        val zeros = ByteArray(4096) { 0 }
        assertNull("低熵数据不该报出密钥", XorDecryptor.bruteForceSingleByte(zeros))
    }

    @Test
    fun `brute force needs minimum size`() {
        assertNull(XorDecryptor.bruteForceSingleByte(ByteArray(16) { it.toByte() }))
    }
}
