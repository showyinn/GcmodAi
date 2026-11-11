package com.gcmodai.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringUtilsTest {

    @Test
    fun `extract chinese double quotes`() {
        assertEquals(listOf("你好"), "他说“你好”".extractQuotedContent())
    }

    @Test
    fun `extract chinese single quotes`() {
        assertEquals(listOf("世界"), "标题是‘世界’".extractQuotedContent())
    }

    @Test
    fun `extract english double quotes`() {
        assertEquals(listOf("hello"), "he said \"hello\"".extractQuotedContent())
    }

    @Test
    fun `extract english single quotes`() {
        assertEquals(listOf("world"), "title is 'world'".extractQuotedContent())
    }

    @Test
    fun `extract corner brackets`() {
        assertEquals(listOf("你好"), "他说「你好」".extractQuotedContent())
    }

    @Test
    fun `extract white corner brackets`() {
        assertEquals(listOf("世界"), "标题是『世界』".extractQuotedContent())
    }

    @Test
    fun `extract multiple quotes`() {
        assertEquals(
            listOf("你好", "世界"),
            "“你好” 和 ‘世界’".extractQuotedContent(),
        )
    }

    @Test
    fun `blank content is ignored`() {
        assertTrue("“” \"\" '  '".extractQuotedContent().isEmpty())
    }

    @Test
    fun `no quotes returns empty`() {
        assertTrue("没有任何引号".extractQuotedContent().isEmpty())
    }

    @Test
    fun `extract as text joins with separator`() {
        assertEquals("你好\n世界", "“你好”‘世界’".extractQuotedContentAsText())
    }

    @Test
    fun `extract as text returns null when empty`() {
        assertNull("没有引号".extractQuotedContentAsText())
    }

    @Test
    fun `remove english brackets`() {
        assertEquals("你好世界", "你好(旁白)世界".removeBracketedContent())
    }

    @Test
    fun `remove chinese brackets`() {
        assertEquals("你好世界", "你好（旁白）世界".removeBracketedContent())
    }

    @Test
    fun `remove multiple brackets`() {
        assertEquals("你好世界", "你好(注释)世界（备注）".removeBracketedContent())
    }

    @Test
    fun `remove brackets keeps outside text trimmed`() {
        assertEquals("你好", "(旁白) 你好 ".removeBracketedContent())
    }

    @Test
    fun `remove brackets does not cross bracket boundaries`() {
        assertEquals("ac", "a(b)c".removeBracketedContent())
    }

    @Test
    fun `remove brackets returns null when all removed`() {
        assertNull("(全是旁白)".removeBracketedContent())
    }

    @Test
    fun `remove brackets returns null for blank result`() {
        assertNull("（旁白） ".removeBracketedContent())
    }

    @Test
    fun `no brackets returns original text`() {
        assertEquals("没有括号", "没有括号".removeBracketedContent())
    }

    // ------------------------------------------------------------------
    // Base64 容错：这几条对应线上闪退事故，不能删
    // ------------------------------------------------------------------

    @Test
    fun `base64 decode falls back to raw text when input is not base64`() {
        val markdown = "# 二进制分析报告\n- 大小：207 KB\n- 格式：ELF"
        assertEquals("非 Base64 输入必须原样返回，而不是抛异常", markdown, markdown.base64Decode())
    }

    @Test
    fun `base64 decode still decodes valid payload`() {
        val original = "hello 二进制"
        assertEquals(original, original.base64Encode().base64Decode())
    }

    @Test
    fun `base64 decode tolerates whitespace and empty input`() {
        val encoded = "测试".base64Encode()
        assertEquals("测试", "  $encoded\n".base64Decode())
        assertEquals("", "".base64Decode())
    }

    @Test
    fun `strict decode returns null for invalid input`() {
        // 注意长度必须是 4 的倍数，否则 kotlin.io.encoding.Base64 视为非法
        assertEquals("abc", "YWJj".base64DecodeOrNull())
        assertNull("非法输入严格模式下返回 null", "# not base64".base64DecodeOrNull())
        assertNull("长度不对也应判非法", "abc".base64DecodeOrNull())
    }

    @Test
    fun `url encode decode round trip`() {
        val text = "a b+c/中文"
        assertEquals(text, text.urlEncode().urlDecode())
    }
}
