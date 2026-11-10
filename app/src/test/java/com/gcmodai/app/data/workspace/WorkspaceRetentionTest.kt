package com.gcmodai.app.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区保留策略验证。
 *
 * 规则：整个工作区 24 小时无任何操作就清空。
 * 这里的测试锁住「剩余时间」「闲置比例」的计算，避免 UI 显示错乱。
 */
class WorkspaceRetentionTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** 服务端 /api/ws/quota 真实返回（带保留字段）。 */
    private val realQuota = """
    {"success":true,"data":{"user":"2173151712","used_bytes":1542,
     "quota_bytes":104857600,"remaining_bytes":104856058,"file_count":4,
     "max_files":2000,"max_file_bytes":33554432,
     "retention_hours":24,"last_activity_at":1794108874,"expires_at":1794195274}}
    """.trimIndent()

    private fun parse(raw: String): WorkspaceQuota {
        val data = json.parseToJsonElement(raw).jsonObject["data"] as JsonObject
        return json.decodeFromJsonElement(WorkspaceQuota.serializer(), data)
    }

    @Test
    fun `parses retention fields`() {
        val q = parse(realQuota)
        assertEquals(24, q.retentionHours)
        assertEquals(1794108874L, q.lastActivityAt)
        assertEquals(1794195274L, q.expiresAt)
    }

    @Test
    fun `expiry is exactly 24 hours after activity`() {
        val q = parse(realQuota)
        assertEquals(24 * 3600L, q.expiresAt - q.lastActivityAt)
    }

    @Test
    fun `remaining seconds counts down`() {
        val q = parse(realQuota)
        // 刚操作完，剩余接近满 24 小时
        assertEquals(86400L, q.remainingSeconds(q.lastActivityAt))
        // 过了 1 小时
        assertEquals(86400L - 3600, q.remainingSeconds(q.lastActivityAt + 3600))
        // 过了 12 小时
        assertEquals(43200L, q.remainingSeconds(q.lastActivityAt + 43200))
    }

    @Test
    fun `remaining seconds floors at zero after expiry`() {
        val q = parse(realQuota)
        assertEquals(0L, q.remainingSeconds(q.expiresAt + 1000))
        assertEquals(0L, q.remainingSeconds(q.expiresAt))
    }

    @Test
    fun `remaining label shows hours and minutes`() {
        val q = parse(realQuota)
        val label = q.remainingLabel(q.lastActivityAt + 3600)  // 剩 23 小时
        assertTrue("应含小时: $label", label.contains("小时"))
        assertTrue(label.contains("还剩"))
    }

    @Test
    fun `remaining label shows minutes when under an hour`() {
        val q = parse(realQuota)
        val label = q.remainingLabel(q.expiresAt - 1800)  // 剩 30 分钟
        assertTrue("应含分钟: $label", label.contains("分钟"))
        assertTrue(!label.contains("小时"))
    }

    @Test
    fun `expired shows imminent`() {
        val q = parse(realQuota)
        assertEquals("即将清空", q.remainingLabel(q.expiresAt + 1))
    }

    @Test
    fun `idle fraction grows over time`() {
        val q = parse(realQuota)
        assertEquals(0f, q.idleFraction(q.lastActivityAt), 0.001f)
        assertEquals(0.5f, q.idleFraction(q.lastActivityAt + 43200), 0.01f)
        assertEquals(1f, q.idleFraction(q.lastActivityAt + 86400), 0.01f)
    }

    @Test
    fun `idle fraction clamps at one`() {
        val q = parse(realQuota)
        assertEquals(1f, q.idleFraction(q.lastActivityAt + 999999), 0.001f)
    }

    @Test
    fun `missing retention fields fall back safely`() {
        val raw = """{"data":{"user":"u","used_bytes":0,"quota_bytes":104857600}}"""
        val q = parse(raw)
        assertEquals(24, q.retentionHours)   // 默认 24 小时
        assertEquals(0L, q.lastActivityAt)
        assertEquals(0L, q.expiresAt)
        // 没有到期信息时不该显示倒计时
        assertEquals(0L, q.remainingSeconds(1000))
    }

    @Test
    fun `zero retention does not divide by zero`() {
        val q = WorkspaceQuota(retentionHours = 0, lastActivityAt = 100, expiresAt = 200)
        assertEquals(0f, q.idleFraction(1000), 0.001f)
    }

    @Test
    fun `default quota is 100MB with 24h retention`() {
        val q = WorkspaceQuota()
        assertEquals(100.0, q.totalMb, 0.001)
        assertEquals(24, q.retentionHours)
    }
}
