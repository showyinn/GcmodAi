package com.gcmodai.app.data.user

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单明细的核心逻辑验证。
 *
 * 重点不是「能不能解析 JSON」，而是**金额算得对不对**：
 * 用户最不能接受的就是账单少算一笔、或多把「登录记录」当成扣费。
 */
class BillingModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 网关真实返回的一条消费记录（字段名照抄线上）。 */
    private val realConsumeJson = """
        {
          "id": 1,
          "user_id": 1,
          "created_at": 1794026751,
          "type": 2,
          "content": "模型测试",
          "username": "2173151712",
          "token_name": "默认密钥",
          "model_name": "glm-5.3",
          "quota": 9268,
          "prompt_tokens": 525,
          "completion_tokens": 512,
          "use_time": 3400,
          "is_stream": true,
          "channel": 1,
          "channel_name": "智谱",
          "token_id": 3,
          "group": "vip",
          "ip": "42.226.10.246",
          "request_id": "202611070446460435872698268d9d6eQoLYwIe",
          "other": "{}"
        }
    """.trimIndent()

    private val realLoginJson = """
        {
          "id": 2,
          "user_id": 1,
          "created_at": 1794026806,
          "type": 7,
          "content": "Logged in successfully via password",
          "username": "2173151712",
          "token_name": "",
          "model_name": "",
          "quota": 0,
          "prompt_tokens": 0,
          "completion_tokens": 0,
          "use_time": 0,
          "is_stream": false,
          "channel": 0,
          "channel_name": "",
          "token_id": 0,
          "group": "",
          "ip": "42.226.10.246",
          "request_id": "202611070446460435872698268",
          "other": "{}"
        }
    """.trimIndent()

    private fun consume(
        id: Long,
        quota: Long,
        model: String = "glm-5.3",
        prompt: Long = 100,
        completion: Long = 50,
        createdAt: Long = 1794026000,
    ) = BillingLog(
        id = id,
        createdAt = createdAt,
        type = BillingLog.TYPE_CONSUME,
        modelName = model,
        quota = quota,
        promptTokens = prompt,
        completionTokens = completion,
        group = "vip",
    )

    // ---------- 解析 ----------

    @Test
    fun `parses real gateway consume record`() {
        val log = json.decodeFromString(BillingLog.serializer(), realConsumeJson)
        assertEquals("glm-5.3", log.modelName)
        assertEquals(9268L, log.quota)
        assertEquals(525L, log.promptTokens)
        assertEquals(512L, log.completionTokens)
        assertEquals(1037L, log.totalTokens)
        assertEquals("vip", log.group)
        assertEquals("默认密钥", log.tokenName)
        assertTrue(log.isStream)
    }

    @Test
    fun `parses login record and marks it as not a charge`() {
        val log = json.decodeFromString(BillingLog.serializer(), realLoginJson)
        assertEquals(BillingLog.TYPE_LOGIN, log.type)
        assertEquals("登录", log.typeLabel)
        // 登录不扣费，绝不能算进消费
        assertFalse(log.isCharge)
        assertEquals(0L, log.quota)
    }

    @Test
    fun `quota converts to usd using the same 500000 ratio as account page`() {
        val log = consume(1, 500000)
        assertEquals(1.0, log.quotaUsd, 1e-9)
        val half = consume(2, 250000)
        assertEquals(0.5, half.quotaUsd, 1e-9)
        val tiny = consume(3, 9268)
        assertEquals(9268 / 500000.0, tiny.quotaUsd, 1e-12)
    }

    @Test
    fun `type label covers all known log types`() {
        val expect = mapOf(
            BillingLog.TYPE_TOPUP to "充值",
            BillingLog.TYPE_CONSUME to "消费",
            BillingLog.TYPE_MANAGE to "管理",
            BillingLog.TYPE_SYSTEM to "系统",
            BillingLog.TYPE_TEST to "测试",
            BillingLog.TYPE_LOGIN to "登录",
        )
        for ((type, label) in expect) {
            assertEquals(label, BillingLog(type = type).typeLabel)
        }
    }

    // ---------- 汇总 ----------

    @Test
    fun `summary only counts consume records`() {
        val logs = listOf(
            consume(1, quota = 1000),
            consume(2, quota = 2000),
            BillingLog(id = 3, type = BillingLog.TYPE_LOGIN, quota = 0),
            BillingLog(id = 4, type = BillingLog.TYPE_TOPUP, quota = 99999),
        )
        val s = BillingSummary.from(logs, nowSeconds = 1794026000)
        // 登录和充值都不应计入消费
        assertEquals(2L, s.totalRequests)
        assertEquals(3000L, s.totalSpentQuota)
    }

    @Test
    fun `summary aggregates tokens across consume records`() {
        val logs = listOf(
            consume(1, quota = 100, prompt = 10, completion = 20),
            consume(2, quota = 200, prompt = 30, completion = 40),
        )
        val s = BillingSummary.from(logs, nowSeconds = 1794026000)
        assertEquals(40L, s.totalPromptTokens)
        assertEquals(60L, s.totalCompletionTokens)
        assertEquals(100L, s.totalTokens)
    }

    @Test
    fun `summary groups by model sorted by spend descending`() {
        val logs = listOf(
            consume(1, quota = 100, model = "cheap-model"),
            consume(2, quota = 900, model = "expensive-model"),
            consume(3, quota = 500, model = "mid-model"),
        )
        val s = BillingSummary.from(logs, nowSeconds = 1794026000)
        assertEquals(3, s.byModel.size)
        assertEquals("expensive-model", s.byModel[0].model)
        assertEquals(900L, s.byModel[0].spentQuota)
        assertEquals("mid-model", s.byModel[1].model)
        assertEquals("cheap-model", s.byModel[2].model)
    }

    @Test
    fun `summary separates today from earlier records`() {
        // 现在取一个整天的起点，便于断言
        val now = 1794067200L // 2026-11-07 00:00:00 UTC 附近的整点
        val todayStart = now - (now % 86400L)
        val logs = listOf(
            consume(1, quota = 1000, createdAt = todayStart + 100),      // 今天
            consume(2, quota = 2000, createdAt = todayStart + 200),      // 今天
            consume(3, quota = 5000, createdAt = todayStart - 86400),    // 昨天
        )
        val s = BillingSummary.from(logs, nowSeconds = now)
        assertEquals(3000L, s.todaySpentQuota)
        assertEquals(2L, s.todayRequests)
        assertEquals(8000L, s.totalSpentQuota)
        assertEquals(3L, s.totalRequests)
    }

    @Test
    fun `summary handles empty input`() {
        val s = BillingSummary.from(emptyList(), nowSeconds = 1794026000)
        assertEquals(0L, s.totalRequests)
        assertEquals(0L, s.totalSpentQuota)
        assertEquals(0.0, s.totalSpentUsd, 1e-12)
        assertTrue(s.byModel.isEmpty())
    }

    @Test
    fun `summary ignores consume records without model name in per-model breakdown`() {
        val logs = listOf(
            consume(1, quota = 100, model = ""),
            consume(2, quota = 200, model = "glm-5.3"),
        )
        val s = BillingSummary.from(logs, nowSeconds = 1794026000)
        // 总额仍然统计全部消费
        assertEquals(300L, s.totalSpentQuota)
        // 但按模型分组只保留有模型名的
        assertEquals(1, s.byModel.size)
        assertEquals("glm-5.3", s.byModel[0].model)
    }

    @Test
    fun `summary usd conversion matches quota ratio`() {
        val logs = listOf(consume(1, quota = 250000))
        val s = BillingSummary.from(logs, nowSeconds = 1794026000)
        assertEquals(0.5, s.totalSpentUsd, 1e-9)
    }

    // ---------- 分页 ----------

    @Test
    fun `log page reports has more correctly`() {
        val more = BillingLogPage(items = List(20) { consume(it.toLong(), 1) }, page = 1, pageSize = 20, total = 100)
        assertTrue(more.hasMore)

        val last = BillingLogPage(items = List(20) { consume(it.toLong(), 1) }, page = 5, pageSize = 20, total = 100)
        assertFalse(last.hasMore)

        val partial = BillingLogPage(items = List(7) { consume(it.toLong(), 1) }, page = 5, pageSize = 20, total = 87)
        assertFalse(partial.hasMore)
    }

    // ---------- 时间格式 ----------

    @Test
    fun `time label formats epoch seconds`() {
        val log = BillingLog(createdAt = 1794026751)
        // 只要不是占位符，且格式长度正确即可（受本地时区影响，不硬编码具体时刻）
        assertTrue(log.timeLabel.length == 14)
        assertTrue(log.timeLabel.contains(":"))
        assertEquals(10, log.dateLabel.length)
    }

    @Test
    fun `zero timestamp yields placeholder`() {
        val log = BillingLog(createdAt = 0)
        assertEquals("-", log.timeLabel)
        assertEquals("-", log.dateLabel)
    }
}
