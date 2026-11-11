package com.gcmodai.app.data.harden

import com.gcmodai.app.data.binary.ByteReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SO 加固的契约测试。
 *
 * 加固改的是节表与节内容：改错一位就可能让 so 加载失败，
 * 所以用构造的最小 ELF 把「剥离 + 等长改名」的边界钉死。
 */
class SoHardenerTest {

    /**
     * 构造一个最小 ELF64：
     *   段表：1 个 PT_LOAD（整个文件）
     *   节区：NULL / .text / .comment / .shstrtab
     */
    private fun buildElf(): ByteArray {
        val textData = "AAAA".toByteArray()
        val commentData = "GCC: (Debian 12) 12.2.0".toByteArray()
        val names = "\u0000.text\u0000.comment\u0000.shstrtab\u0000".toByteArray()

        val ehSize = 64
        val shoff = 0x200
        val shentsize = 64
        val shnum = 4
        val textOff = 0x300
        val commentOff = 0x320
        val namesOff = 0x340
        val total = namesOff + names.size + 16
        val b = ByteArray(total)

        // ELF header
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2; b[5] = 1; b[6] = 1
        put16(b, 16, 3)                 // e_type = DYN
        put16(b, 18, 0xB7)              // e_machine = AArch64
        put64(b, 40, shoff.toLong())    // e_shoff
        put16(b, 58, shentsize)         // e_shentsize
        put16(b, 60, shnum)             // e_shnum
        put16(b, 62, 3)                 // e_shstrndx
        // 注意：e_ehsize 是 offset 52 处的 u16；这里**不能**用 put64 写 8 字节，
        // 否则会把 54..59 的 phentsize/phnum/shentsize 一起覆盖成 0（踩过这个坑）

        // 数据
        System.arraycopy(textData, 0, b, textOff, textData.size)
        System.arraycopy(commentData, 0, b, commentOff, commentData.size)
        System.arraycopy(names, 0, b, namesOff, names.size)

        // 节头：NULL
        // .text
        writeSection(b, shoff + shentsize, nameOff = 1, type = 1, addr = 0x1000, off = textOff.toLong(), size = textData.size.toLong())
        // .comment
        writeSection(b, shoff + shentsize * 2, nameOff = 7, type = 1, addr = 0, off = commentOff.toLong(), size = commentData.size.toLong())
        // .shstrtab
        writeSection(b, shoff + shentsize * 3, nameOff = 16, type = 3, addr = 0, off = namesOff.toLong(), size = names.size.toLong())
        return b
    }

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun writeSection(b: ByteArray, base: Int, nameOff: Int, type: Int, addr: Long, off: Long, size: Long) {
        for (i in 0 until 64) b[base + i] = 0
        put16(b, base + 0, nameOff)     // sh_name（低 16 位足够）
        put16(b, base + 4, type)        // sh_type
        put64(b, base + 16, addr)       // sh_addr
        put64(b, base + 24, off)        // sh_offset
        put64(b, base + 32, size)       // sh_size
    }

    @Test
    fun `strips comment section and wipes its content`() {
        val elf = buildElf()
        val before = String(elf, 0x320, 22, Charsets.US_ASCII)
        assertTrue("构造样本应包含编译器注释：$before", before.contains("GCC"))

        val r = SoHardener.harden(elf, strip = true, randomizeNames = false, wipeComment = true)
        val after = String(r.data, 0x320, 22, Charsets.US_ASCII)
        assertTrue("注释内容应被清零", after.all { it == '\u0000' })
        assertTrue("应报告剥离了 .comment：${r.strippedSections}", r.strippedSections.any { it.contains("comment") })
        assertEquals("文件大小不变", elf.size, r.data.size)
    }

    @Test
    fun `randomized section names keep offsets valid`() {
        val elf = buildElf()
        val r = SoHardener.harden(elf, strip = false, randomizeNames = true, wipeComment = false)
        assertTrue("应有节名被改名：${r.renamedSections}", r.renamedSections >= 1)
        // 节表仍然可解析、节数不变
        val reader = ByteReader(r.data)
        assertEquals(4, reader.u16(60, true))
        // shstrtab 名字长度不变（等长替换）
        assertEquals(elf.size, r.data.size)
    }

    @Test
    fun `non elf input is rejected softly`() {
        val junk = ByteArray(64) { 0x41 }
        val r = SoHardener.harden(junk)
        assertTrue("应说明不是 ELF 并原样返回", r.notes.any { it.contains("不是 ELF") })
        assertEquals(64, r.data.size)
    }
}
