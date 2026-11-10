package com.gcmodai.app.data.ai.tools.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容错序列化器验证：**任何形状的输入都必须解析成功**。
 *
 * 这是「AI 用不了工作区」这类问题的最后一道防线。
 * 只要解析不失败，坏数据最多让某个开关失效，绝不会让整份设置崩掉。
 */
class LocalToolOptionToleranceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String): LocalToolOption =
        json.decodeFromString(LocalToolOption.serializer(), raw)

    private fun decodeList(raw: String): List<LocalToolOption> =
        json.decodeFromString(ListSerializer(LocalToolOption.serializer()), raw)

    // ---------------- 正常格式 ----------------

    @Test
    fun `object format works`() {
        assertEquals(
            LocalToolOption.WorkspaceServer,
            decode("""{"type":"workspace_server"}"""),
        )
    }

    @Test
    fun `serializes to object format`() {
        val out = json.encodeToString(LocalToolOption.serializer(), LocalToolOption.SshRemote)
        assertTrue("应为对象格式: $out", out.startsWith("{"))
        assertTrue(out.contains("ssh_remote"))
    }

    // ---------------- 历史坏格式也必须能吃 ----------------

    @Test
    fun `bare string works`() {
        // V6 第一版写出的就是这种，之前会抛异常
        assertEquals(
            LocalToolOption.WorkspaceServer,
            decode(""""workspace_server""""),
        )
    }

    @Test
    fun `bare string list works`() {
        val list = decodeList("""["time_info","workspace_server","ssh_remote"]""")
        assertEquals(3, list.size)
        assertTrue(list.contains(LocalToolOption.WorkspaceServer))
        assertTrue(list.contains(LocalToolOption.SshRemote))
    }

    // ---------------- 未知名字不抛异常 ----------------

    @Test
    fun `unknown object name becomes Unknown`() {
        assertEquals(
            LocalToolOption.Unknown,
            decode("""{"type":"some_future_tool"}"""),
        )
    }

    @Test
    fun `unknown bare string becomes Unknown`() {
        assertEquals(LocalToolOption.Unknown, decode(""""brand_new_tool""""))
    }

    @Test
    fun `empty string becomes Unknown`() {
        assertEquals(LocalToolOption.Unknown, decode(""""""""))
    }

    @Test
    fun `object without type becomes Unknown`() {
        assertEquals(LocalToolOption.Unknown, decode("""{"foo":"bar"}"""))
    }

    @Test
    fun `number becomes Unknown`() {
        assertEquals(LocalToolOption.Unknown, decode("123"))
    }

    @Test
    fun `null becomes Unknown`() {
        assertEquals(LocalToolOption.Unknown, decode("null"))
    }

    @Test
    fun `mixed garbage list does not throw`() {
        val list = decodeList(
            """["time_info", {"type":"workspace_server"}, 123, null, {"x":1}, "junk"]"""
        )
        // 6 个元素全部解析成功，其中有效的被识别出来
        assertEquals(6, list.size)
        assertTrue(list.contains(LocalToolOption.TimeInfo))
        assertTrue(list.contains(LocalToolOption.WorkspaceServer))
        // 无效的都变成 Unknown
        assertTrue(list.count { it == LocalToolOption.Unknown } >= 3)
    }

    // ---------------- 往返一致 ----------------

    @Test
    fun `round trip for every known option`() {
        val all = listOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.SshRemote,
            LocalToolOption.WorkspaceServer,
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Tts,
            LocalToolOption.AskUser,
            LocalToolOption.ScreenTime,
            LocalToolOption.Calendar,
            LocalToolOption.ChartDisplay,
            LocalToolOption.GcmodAiOfficial,
        )
        all.forEach { option ->
            val encoded = json.encodeToString(LocalToolOption.serializer(), option)
            assertEquals("往返失败: $encoded", option, decode(encoded))
        }
    }

    @Test
    fun `serial name mapping is consistent`() {
        assertEquals("workspace_server", LocalToolOption.WorkspaceServer.serialName())
        assertEquals("ssh_remote", LocalToolOption.SshRemote.serialName())
        assertEquals(
            LocalToolOption.WorkspaceServer,
            localToolOptionFromName("workspace_server"),
        )
        assertEquals(LocalToolOption.Unknown, localToolOptionFromName(null))
        assertEquals(LocalToolOption.Unknown, localToolOptionFromName("nope"))
    }

    // ---------------- 端到端：坏数据到可用 ----------------

    @Test
    fun `broken assistant json still loads tools`() {
        // 模拟盘上最坏情况：裸字符串 + 未知名字 + 垃圾
        val raw = """["workspace_server","ssh_remote","unknown_future","time_info"]"""
        val list = decodeList(raw)
        assertNotNull(list)
        // 至少工作区与 SSH 可用 —— 这决定了 AI 能不能写文件
        assertTrue(list.contains(LocalToolOption.WorkspaceServer))
        assertTrue(list.contains(LocalToolOption.SshRemote))
    }

    @Test
    fun `unknown is not treated as a real tool`() {
        val list = decodeList("""["workspace_server","junk_tool"]""")
        val real = list.filter { it != LocalToolOption.Unknown }
        assertEquals(1, real.size)
        assertEquals(LocalToolOption.WorkspaceServer, real[0])
        assertFalse(real.contains(LocalToolOption.Unknown))
    }
}
