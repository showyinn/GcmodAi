package com.gcmodai.app.data.ai.tools.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LocalToolOption 序列化格式验证。
 *
 * 关键事实：它是 `sealed class`，默认走**多态序列化**，
 * 每项必须写成 `{"type":"xxx"}`，而不是裸字符串 `"xxx"`。
 *
 * 这个格式搞错会让整个 localTools 列表解析失败、工具集体消失，
 * 是导致「AI 读不了工作区」的根因之一，所以必须锁死。
 */
class LocalToolOptionSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(raw: String): LocalToolOption =
        json.decodeFromString(LocalToolOption.serializer(), raw)

    private fun decodeList(raw: String): List<LocalToolOption> =
        json.decodeFromString(ListSerializer(LocalToolOption.serializer()), raw)

    // ---------------- 正确格式 ----------------

    @Test
    fun `workspace_server deserializes from polymorphic object`() {
        assertEquals(
            LocalToolOption.WorkspaceServer,
            decode("""{"type":"workspace_server"}"""),
        )
    }

    @Test
    fun `ssh_remote deserializes from polymorphic object`() {
        assertEquals(
            LocalToolOption.SshRemote,
            decode("""{"type":"ssh_remote"}"""),
        )
    }

    @Test
    fun `serialization round trips to object format`() {
        val back = json.encodeToString(
            LocalToolOption.serializer(),
            LocalToolOption.WorkspaceServer,
        )
        assertTrue("应序列化为含 type 的对象: $back", back.contains("workspace_server"))
        assertTrue(back.startsWith("{"))
        assertEquals(LocalToolOption.WorkspaceServer, decode(back))
    }

    // ---------------- 错误格式必须被拒绝 ----------------

    @Test
    fun `bare string is tolerated`() {
        // 容错序列化器上线后，裸字符串不再抛异常，而是正常识别。
        // 这样历史坏数据不会拖垮整份设置。
        assertEquals(
            LocalToolOption.WorkspaceServer,
            decode(""""workspace_server""""),
        )
    }

    @Test
    fun `bare string in list is tolerated`() {
        val list = decodeList("""["time_info","workspace_server"]""")
        assertEquals(2, list.size)
        assertTrue(list.contains(LocalToolOption.WorkspaceServer))
    }

    // ---------------- 列表场景 ----------------

    @Test
    fun `full list deserializes`() {
        val list = decodeList(
            """[{"type":"time_info"},{"type":"gcmodai_official"},{"type":"workspace_server"},{"type":"ssh_remote"}]"""
        )
        assertEquals(4, list.size)
        assertTrue(list.contains(LocalToolOption.WorkspaceServer))
        assertTrue(list.contains(LocalToolOption.SshRemote))
    }

    @Test
    fun `legacy list without new tools still parses`() {
        val list = decodeList("""[{"type":"time_info"},{"type":"gcmodai_official"}]""")
        assertEquals(2, list.size)
        assertFalse(list.contains(LocalToolOption.WorkspaceServer))
    }

    @Test
    fun `empty list parses`() {
        assertEquals(0, decodeList("[]").size)
    }

    // ---------------- 默认值 ----------------

    @Test
    fun `default assistant includes workspace and ssh tools`() {
        val defaults = com.gcmodai.app.data.model.Assistant().localTools
        assertTrue(
            "新助手默认应含 WorkspaceServer",
            defaults.contains(LocalToolOption.WorkspaceServer),
        )
        assertTrue(
            "新助手默认应含 SshRemote",
            defaults.contains(LocalToolOption.SshRemote),
        )
    }

    // ---------------- 标识稳定性 ----------------

    @Test
    fun `all known serial names are stable`() {
        val known = listOf(
            "workspace_server" to LocalToolOption.WorkspaceServer,
            "ssh_remote" to LocalToolOption.SshRemote,
            "pc_control" to LocalToolOption.PcControl,
            "time_info" to LocalToolOption.TimeInfo,
            "gcmodai_official" to LocalToolOption.GcmodAiOfficial,
            "clipboard" to LocalToolOption.Clipboard,
            "ask_user" to LocalToolOption.AskUser,
            "screen_time" to LocalToolOption.ScreenTime,
            "calendar" to LocalToolOption.Calendar,
        )
        known.forEach { (name, option) ->
            assertEquals(
                "标识 $name 必须保持稳定",
                option,
                decode("""{"type":"$name"}"""),
            )
        }
    }
}
