package com.gcmodai.app.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import com.gcmodai.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GcmodAi 官方工具集的真实执行验证。
 *
 * 这些测试**真的调用**每个工具的 execute 并检查返回 JSON 里的数值，
 * 而不是只看工具是否注册成功——工具调用「看起来接上了但算错」是更难发现的问题。
 */
class GcmodAiToolsTest {

    private val tools = GcmodAiTools.all().associateBy { it.name }

    private fun run(name: String, payload: JsonObject): JsonObject = runBlocking {
        val tool = tools[name]
        assertNotNull("工具 $name 必须存在", tool)
        val parts = tool!!.execute(payload)
        val text = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()
        assertNotNull("$name 必须返回文本结果", text)
        Json.parseToJsonElement(text!!.text).jsonObject
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

    // ---------- 工具数量 ----------

    @Test
    fun `exactly eight official tools are registered`() {
        assertEquals(
            setOf(
                "calc_expression", "text_process", "date_calc", "hash_text",
                "json_format", "unit_convert", "url_codec", "base64_codec",
            ),
            tools.keys,
        )
    }

    @Test
    fun `every tool has a non-empty description so the model knows when to use it`() {
        for ((name, tool) in tools) {
            assertTrue("$name 的 description 不能为空", tool.description.isNotBlank())
            assertNotNull("$name 必须提供 parameters schema", tool.parameters())
        }
    }

    // ---------- 1. 计算器 ----------

    @Test
    fun `calculator handles precedence and parentheses`() {
        val r = run("calc_expression", buildJsonObject {
            put("expression", "(128*0.85)+36/2")
        })
        assertTrue(r.bool("ok"))
        assertEquals("126.8", r.str("result"))
    }

    @Test
    fun `calculator handles power right associativity`() {
        val r = run("calc_expression", buildJsonObject { put("expression", "2^3^2") })
        assertTrue(r.bool("ok"))
        // 右结合：2^(3^2) = 2^9 = 512
        assertEquals("512", r.str("result"))
    }

    @Test
    fun `calculator supports functions`() {
        val r = run("calc_expression", buildJsonObject { put("expression", "sqrt(144)+max(3,9)") })
        assertTrue(r.bool("ok"))
        assertEquals("21", r.str("result"))
    }

    @Test
    fun `calculator reports division by zero instead of crashing`() {
        val r = run("calc_expression", buildJsonObject { put("expression", "5/0") })
        assertTrue(!r.bool("ok"))
        assertTrue(r.str("error").isNotBlank())
    }

    @Test
    fun `calculator normalizes full-width symbols`() {
        val r = run("calc_expression", buildJsonObject { put("expression", "（3+4）×2") })
        assertTrue(r.bool("ok"))
        assertEquals("14", r.str("result"))
    }

    // ---------- 2. 文本 ----------

    @Test
    fun `text stats counts chars words and lines`() {
        val r = run("text_process", buildJsonObject {
            put("text", "hello world\nfoo bar")
            put("operation", "stats")
        })
        assertEquals("19", r.str("chars"))
        assertEquals("4", r.str("words"))
        assertEquals("2", r.str("lines"))
    }

    @Test
    fun `text replace reports occurrence count`() {
        val r = run("text_process", buildJsonObject {
            put("text", "a-b-c-d")
            put("operation", "replace")
            put("search", "-")
            put("replacement", "+")
        })
        assertEquals("a+b+c+d", r.str("result"))
        assertEquals("3", r.str("occurrences"))
    }

    @Test
    fun `text dedupe removes duplicate lines`() {
        val r = run("text_process", buildJsonObject {
            put("text", "a\nb\na\nc\nb")
            put("operation", "dedupe_lines")
        })
        // "a","b","a","c","b" 共 5 行，去重剩 3 行，因此删掉 2 行
        assertEquals("2", r.str("removed"))
        assertEquals("a\nb\nc", r.str("result"))
    }

    // ---------- 3. 日期 ----------

    @Test
    fun `date diff computes days between two dates`() {
        val r = run("date_calc", buildJsonObject {
            put("operation", "diff")
            put("start_date", "2026-01-01")
            put("end_date", "2026-03-01")
        })
        assertEquals("59", r.str("days"))
    }

    @Test
    fun `date add handles month arithmetic`() {
        val r = run("date_calc", buildJsonObject {
            put("operation", "add")
            put("start_date", "2026-01-31")
            put("months", 1)
        })
        // 1 月 31 日加一个月会被夹到 2 月末，这是 java.time 的既有语义
        assertEquals("2026-02-28", r.str("result_date"))
    }

    @Test
    fun `date calc rejects malformed dates`() {
        val r = run("date_calc", buildJsonObject {
            put("operation", "diff")
            put("start_date", "2026/01/01")
            put("end_date", "2026-02-01")
        })
        assertTrue(r.str("error").isNotBlank())
    }

    // ---------- 4. 哈希 ----------

    @Test
    fun `hash produces the known sha256 of abc`() {
        val r = run("hash_text", buildJsonObject {
            put("text", "abc")
            put("algorithm", "sha256")
        })
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            r.str("hash"),
        )
        assertEquals("3", r.str("input_bytes"))
    }

    @Test
    fun `hash default is sha256 and md5 also works`() {
        val d = run("hash_text", buildJsonObject { put("text", "abc") })
        assertEquals("SHA-256", d.str("algorithm"))
        val m = run("hash_text", buildJsonObject {
            put("text", "abc")
            put("algorithm", "md5")
        })
        assertEquals("900150983cd24fb0d6963f7d28e17f72", m.str("hash"))
    }

    // ---------- 5. JSON ----------

    @Test
    fun `json format validates and pretty prints`() {
        val r = run("json_format", buildJsonObject { put("json", """{"a":1,"b":[1,2]}""") })
        assertTrue(r.bool("ok"))
        assertEquals("object", r.str("type"))
        assertTrue(r.str("formatted").contains("\n"))
    }

    @Test
    fun `json format reports invalid input`() {
        val r = run("json_format", buildJsonObject { put("json", "{a:1}") })
        assertTrue(!r.bool("ok"))
        assertTrue(r.str("error").isNotBlank())
    }

    // ---------- 6. 单位换算 ----------

    @Test
    fun `unit convert km to mile`() {
        val r = run("unit_convert", buildJsonObject {
            put("value", 100)
            put("from", "km")
            put("to", "mile")
        })
        assertTrue(r.bool("ok"))
        assertEquals("62.137119", r.str("result"))
    }

    @Test
    fun `unit convert celsius to fahrenheit`() {
        val r = run("unit_convert", buildJsonObject {
            put("value", 100)
            put("from", "c")
            put("to", "f")
        })
        assertEquals("212", r.str("result"))
    }

    @Test
    fun `unit convert gb to mb`() {
        val r = run("unit_convert", buildJsonObject {
            put("value", 1)
            put("from", "gb")
            put("to", "mb")
        })
        assertEquals("1024", r.str("result"))
    }

    @Test
    fun `unit convert rejects cross category`() {
        val r = run("unit_convert", buildJsonObject {
            put("value", 1)
            put("from", "km")
            put("to", "kg")
        })
        assertTrue(!r.bool("ok"))
    }

    // ---------- 7/8. 编解码 ----------

    @Test
    fun `url codec round trips`() {
        val enc = run("url_codec", buildJsonObject {
            put("text", "你好 world&x=1")
            put("operation", "encode")
        })
        assertTrue(enc.bool("ok"))
        val dec = run("url_codec", buildJsonObject {
            put("text", enc.str("result"))
            put("operation", "decode")
        })
        assertEquals("你好 world&x=1", dec.str("result"))
    }

    @Test
    fun `base64 round trips utf8`() {
        val enc = run("base64_codec", buildJsonObject {
            put("text", "GcmodAi 测试")
            put("operation", "encode")
        })
        assertTrue(enc.bool("ok"))
        val dec = run("base64_codec", buildJsonObject {
            put("text", enc.str("result"))
            put("operation", "decode")
        })
        assertEquals("GcmodAi 测试", dec.str("result"))
    }

    @Test
    fun `base64 decode reports invalid input`() {
        val r = run("base64_codec", buildJsonObject {
            put("text", "!!!not-base64!!!")
            put("operation", "decode")
        })
        assertTrue(!r.bool("ok"))
    }
}
