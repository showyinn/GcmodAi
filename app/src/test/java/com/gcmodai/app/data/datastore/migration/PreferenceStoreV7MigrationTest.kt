package com.gcmodai.app.data.datastore.migration

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.gcmodai.app.data.ai.tools.local.LocalToolOption
import com.gcmodai.app.data.datastore.SettingsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V7 迁移验证：修复 V6 写坏的裸字符串，并保证解析永不失败。
 */
class PreferenceStoreV7MigrationTest {

    private val migration = PreferenceStoreV7Migration()
    private val json = Json { ignoreUnknownKeys = true }

    private fun toolsOf(assistantsJson: String): List<LocalToolOption> {
        val root = Json.parseToJsonElement(assistantsJson) as JsonArray
        val first = root[0] as JsonObject
        val tools = first["localTools"] as JsonArray
        return json.decodeFromJsonElement(ListSerializer(LocalToolOption.serializer()), tools)
    }

    // ---------------- 触发条件 ----------------

    @Test
    fun `migrates when version is 6`() = runTest {
        // 关键：V6 已经把版本升到 6，V7 必须仍然执行才能修数据
        assertTrue(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 6)))
    }

    @Test
    fun `migrates when version is 5`() = runTest {
        assertTrue(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 5)))
    }

    @Test
    fun `skips when version is 7`() = runTest {
        assertFalse(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 7)))
    }

    // ---------------- 修复坏格式（核心） ----------------

    @Test
    fun `fixes bare string entries written by broken V6`() {
        // 这是 V6 第一版写出的坏数据
        val broken = """[{"id":"a1","localTools":["workspace_server","ssh_remote"]}]"""
        val out = normalizeAssistantsLocalTools(broken)

        // 必须变成对象格式
        assertTrue(out.contains("""{"type":"workspace_server"}"""))
        assertTrue(out.contains("""{"type":"ssh_remote"}"""))
    }

    @Test
    fun `broken data becomes parseable after repair`() {
        val broken = """[{"id":"a1","localTools":["time_info","workspace_server"]}]"""

        // 修复前：裸字符串无法解析（记录这个事实）
        val before = runCatching {
            val root = Json.parseToJsonElement(broken) as JsonArray
            val tools = (root[0] as JsonObject)["localTools"] as JsonArray
            json.decodeFromJsonElement(ListSerializer(LocalToolOption.serializer()), tools)
        }
        // 注意：现在 LocalToolOption 有容错序列化器，所以其实能解析
        // 这里只断言修复后一定可解析
        val out = normalizeAssistantsLocalTools(broken)
        val parsed = toolsOf(out)
        assertTrue(parsed.contains(LocalToolOption.TimeInfo))
        assertTrue(parsed.contains(LocalToolOption.WorkspaceServer))
        assertTrue(parsed.contains(LocalToolOption.SshRemote))
    }

    @Test
    fun `repair is idempotent`() {
        val broken = """[{"id":"a1","localTools":["workspace_server"]}]"""
        val once = normalizeAssistantsLocalTools(broken)
        val twice = normalizeAssistantsLocalTools(once)
        assertEquals(once, twice)
    }

    @Test
    fun `already correct data unchanged`() {
        val good = """[{"id":"a1","localTools":[{"type":"time_info"},{"type":"workspace_server"},{"type":"ssh_remote"}]}]"""
        val out = normalizeAssistantsLocalTools(good)
        assertEquals(good, out)
    }

    // ---------------- 补齐工具 ----------------

    @Test
    fun `adds missing tools`() {
        val raw = """[{"id":"a1","localTools":[{"type":"time_info"}]}]"""
        val parsed = toolsOf(normalizeAssistantsLocalTools(raw))
        assertTrue(parsed.contains(LocalToolOption.WorkspaceServer))
        assertTrue(parsed.contains(LocalToolOption.SshRemote))
        assertTrue(parsed.contains(LocalToolOption.TimeInfo))
    }

    @Test
    fun `adds field when localTools missing`() {
        val raw = """[{"id":"a1","name":"老助手"}]"""
        val parsed = toolsOf(normalizeAssistantsLocalTools(raw))
        assertTrue(parsed.contains(LocalToolOption.WorkspaceServer))
        assertTrue(parsed.contains(LocalToolOption.SshRemote))
    }

    @Test
    fun `does not duplicate`() {
        val raw = """[{"id":"a1","localTools":[{"type":"workspace_server"},{"type":"ssh_remote"}]}]"""
        val out = normalizeAssistantsLocalTools(raw)
        assertEquals(1, Regex("workspace_server").findAll(out).count())
        assertEquals(1, Regex("ssh_remote").findAll(out).count())
    }

    @Test
    fun `drops garbage entries`() {
        val raw = """[{"id":"a1","localTools":[{"type":"time_info"},123,{"foo":"bar"},null]}]"""
        val parsed = toolsOf(normalizeAssistantsLocalTools(raw))
        assertTrue(parsed.contains(LocalToolOption.TimeInfo))
        assertTrue(parsed.contains(LocalToolOption.WorkspaceServer))
    }

    // ---------------- 边界 ----------------

    @Test
    fun `invalid json untouched`() = runTest {
        val broken = "not json"
        val prefs = mutablePreferencesOf(
            SettingsStore.VERSION to 6,
            SettingsStore.ASSISTANTS to broken,
        )
        val out = migration.migrate(prefs)
        assertEquals(broken, out[SettingsStore.ASSISTANTS])
        assertEquals(7, out[SettingsStore.VERSION])
    }

    @Test
    fun `bumps version to 7`() = runTest {
        val out = migration.migrate(mutablePreferencesOf(SettingsStore.VERSION to 6))
        assertEquals(7, out[SettingsStore.VERSION])
    }

    @Test
    fun `handles multiple assistants`() {
        val raw = """[
            {"id":"a1","localTools":["time_info"]},
            {"id":"a2","localTools":[{"type":"workspace_server"}]},
            {"id":"a3"}
        ]"""
        val out = normalizeAssistantsLocalTools(raw)
        assertEquals(3, Regex("workspace_server").findAll(out).count())
        assertEquals(3, Regex("ssh_remote").findAll(out).count())
    }

    @Test
    fun `empty array safe`() {
        assertEquals("[]", normalizeAssistantsLocalTools("[]"))
    }

    @Test
    fun `no assistants key safe`() = runTest {
        val out = migration.migrate(mutablePreferencesOf(SettingsStore.VERSION to 6))
        assertEquals(7, out[SettingsStore.VERSION])
        assertEquals(null, out[SettingsStore.ASSISTANTS])
    }
}
