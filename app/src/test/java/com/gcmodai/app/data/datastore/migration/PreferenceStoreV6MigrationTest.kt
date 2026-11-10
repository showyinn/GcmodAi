package com.gcmodai.app.data.datastore.migration

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.gcmodai.app.data.ai.tools.local.LocalToolOption
import com.gcmodai.app.data.datastore.SettingsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V6 迁移验证。
 *
 * 除了「能补上工具」，这里还要锁住一个曾经写错的地方：
 * **必须用多态格式 `{"type":"x"}`，不能是裸字符串 `"x"`**。
 * 写成裸字符串会让整个 localTools 解析抛异常，工具集体丢失。
 */
class PreferenceStoreV6MigrationTest {

    private val migration = PreferenceStoreV6Migration()
    private val json = Json { ignoreUnknownKeys = true }

    /** 模拟助手真实存储格式（多态对象）。 */
    private fun assistantJson(tools: List<String>): String =
        tools.joinToString(prefix = "[", postfix = "]") { """{"type":"$it"}""" }
            .let { """[{"id":"a1","name":"\u52a9\u624b","localTools":$it}]""" }

    // ---------------- 触发条件 ----------------

    @Test
    fun `migrates when version missing`() = runTest {
        assertTrue(migration.shouldMigrate(mutablePreferencesOf()))
    }

    @Test
    fun `migrates when version below 6`() = runTest {
        assertTrue(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 5)))
    }

    @Test
    fun `skips when version already 6`() = runTest {
        assertFalse(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 6)))
    }

    // ---------------- 格式正确性（关键） ----------------

    @Test
    fun `writes polymorphic object format not bare string`() {
        val raw = assistantJson(listOf("time_info"))
        val out = migrateAssistantsWorkspaceTools(raw)

        // 必须是对象形式
        assertTrue("应写多态对象", out.contains("""{"type":"workspace_server"}"""))
        assertTrue("应写多态对象", out.contains("""{"type":"ssh_remote"}"""))

        // 不能出现"裸字符串元素"：即数组里直接是 "xxx" 而不是对象。
        // 注意不能简单用 contains 判断子串——对象里本来就含同样的文字，
        // 所以这里真实反序列化一次，能解析成功才说明格式对。
        val root = Json.parseToJsonElement(out) as JsonArray
        val tools = (root[0] as JsonObject)["localTools"] as JsonArray
        val parsed = json.decodeFromJsonElement(
            ListSerializer(LocalToolOption.serializer()),
            tools,
        )
        assertTrue(
            "反序列化后应含新工具",
            parsed.contains(LocalToolOption.WorkspaceServer),
        )
    }

    @Test
    fun `migrated json actually deserializes`() {
        val raw = assistantJson(listOf("time_info", "gcmodai_official"))
        val out = migrateAssistantsWorkspaceTools(raw)

        // 提取 localTools 并真实反序列化 —— 这是最有说服力的验证：
        // 只有能被序列化器接受的 JSON 才算格式正确
        val root = Json.parseToJsonElement(out) as JsonArray
        val first = root[0] as JsonObject
        val tools = first["localTools"] as JsonArray

        val parsed = json.decodeFromJsonElement(
            ListSerializer(LocalToolOption.serializer()),
            tools,
        )

        assertEquals(4, parsed.size)
        assertTrue(parsed.contains(LocalToolOption.WorkspaceServer))
        assertTrue(parsed.contains(LocalToolOption.SshRemote))
        assertTrue(parsed.contains(LocalToolOption.TimeInfo))
        assertTrue(parsed.contains(LocalToolOption.GcmodAiOfficial))
    }

    @Test
    fun `handles legacy bare string entries`() {
        // 盘上可能是极老格式：裸字符串
        val raw = """[{"id":"a1","localTools":["time_info"]}]"""
        val out = migrateAssistantsWorkspaceTools(raw)
        assertTrue(out.contains("""{"type":"workspace_server"}"""))
        assertTrue(out.contains("""{"type":"ssh_remote"}"""))
    }

    @Test
    fun `bare string existing tool is not duplicated`() {
        // 已存在的裸字符串也要被识别，避免重复追加
        val raw = """[{"id":"a1","localTools":["workspace_server"]}]"""
        val out = migrateAssistantsWorkspaceTools(raw)
        assertEquals(1, Regex("workspace_server").findAll(out).count())
        // 但仍要补上缺的 ssh_remote
        assertTrue(out.contains("ssh_remote"))
    }

    // ---------------- 迁移效果 ----------------

    @Test
    fun `adds both tools to old assistant`() {
        val out = migrateAssistantsWorkspaceTools(assistantJson(listOf("time_info")))
        assertTrue(out.contains("workspace_server"))
        assertTrue(out.contains("ssh_remote"))
        assertTrue(out.contains("time_info"))
    }

    @Test
    fun `bumps version to 6`() = runTest {
        val out = migration.migrate(mutablePreferencesOf(SettingsStore.VERSION to 5))
        assertEquals(6, out[SettingsStore.VERSION])
    }

    @Test
    fun `does not duplicate existing tools`() {
        val out = migrateAssistantsWorkspaceTools(
            assistantJson(listOf("workspace_server", "ssh_remote"))
        )
        assertEquals(1, Regex("workspace_server").findAll(out).count())
        assertEquals(1, Regex("ssh_remote").findAll(out).count())
    }

    @Test
    fun `adds field when localTools missing`() {
        val raw = """[{"id":"a1","name":"老助手"}]"""
        val out = migrateAssistantsWorkspaceTools(raw)
        assertTrue(out.contains("localTools"))
        assertTrue(out.contains("workspace_server"))
        assertTrue(out.contains("ssh_remote"))
        assertTrue(out.contains("老助手"))
    }

    @Test
    fun `handles multiple assistants`() {
        val raw = """[
            {"id":"a1","localTools":[{"type":"time_info"}]},
            {"id":"a2","localTools":[{"type":"workspace_server"}]},
            {"id":"a3"}
        ]"""
        val out = migrateAssistantsWorkspaceTools(raw)
        assertEquals(3, Regex("workspace_server").findAll(out).count())
        assertEquals(3, Regex("ssh_remote").findAll(out).count())
    }

    @Test
    fun `invalid json left untouched`() = runTest {
        val broken = "definitely not json"
        val prefs = mutablePreferencesOf(
            SettingsStore.VERSION to 5,
            SettingsStore.ASSISTANTS to broken,
        )
        val out = migration.migrate(prefs)
        assertEquals(broken, out[SettingsStore.ASSISTANTS])
        // 版本仍要推进，避免每次启动重复尝试
        assertEquals(6, out[SettingsStore.VERSION])
    }

    @Test
    fun `other preferences preserved`() = runTest {
        val otherKey = stringPreferencesKey("other")
        val intKey = intPreferencesKey("num")
        val prefs = mutablePreferencesOf(
            SettingsStore.VERSION to 5,
            otherKey to "keep me",
            intKey to 42,
            SettingsStore.ASSISTANTS to assistantJson(listOf("time_info")),
        )
        val out = migration.migrate(prefs)
        assertEquals("keep me", out[otherKey])
        assertEquals(42, out[intKey])
    }

    @Test
    fun `no assistants key is safe`() = runTest {
        val out = migration.migrate(mutablePreferencesOf(SettingsStore.VERSION to 5))
        assertEquals(6, out[SettingsStore.VERSION])
        assertEquals(null, out[SettingsStore.ASSISTANTS])
    }

    @Test
    fun `is idempotent`() {
        val once = migrateAssistantsWorkspaceTools(assistantJson(listOf("time_info")))
        val twice = migrateAssistantsWorkspaceTools(once)
        assertEquals(once, twice)
    }

    @Test
    fun `empty array safe`() {
        val out = migrateAssistantsWorkspaceTools("[]")
        assertEquals("[]", out)
    }

    @Test
    fun `object without id still migrated`() {
        val raw = """[{"localTools":[]}]"""
        val out = migrateAssistantsWorkspaceTools(raw)
        assertNotNull(out)
        assertTrue(out.contains("workspace_server"))
    }
}
