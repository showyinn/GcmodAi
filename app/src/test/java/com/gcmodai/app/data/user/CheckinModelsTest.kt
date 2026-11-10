package com.gcmodai.app.data.user

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到模型验证。
 *
 * 核心要求：「网站签到过了 app 端还能签到」绝不允许发生。
 * 数据库端有 `UNIQUE(user_id, checkin_date)` 兜底，App 端的责任是
 * **正确反映服务端状态**，并且把「今日已签到」当成正常分支而不是错误。
 */
class CheckinModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 线上真实返回：已签到状态。 */
    private val realStatus = """
        {
          "data": {
            "enabled": true,
            "max_quota": 100000,
            "min_quota": 10000,
            "stats": {
              "checked_in_today": true,
              "checkin_count": 3,
              "records": [
                {"checkin_date": "2026-11-07", "quota_awarded": 44763},
                {"checkin_date": "2026-11-05", "quota_awarded": 25673},
                {"checkin_date": "2026-11-04", "quota_awarded": 37748}
              ],
              "total_checkins": 33,
              "total_quota": 1725186
            }
          },
          "success": true
        }
    """.trimIndent()

    /** 线上真实返回：未签到状态。 */
    private val notCheckedStatus = """
        {
          "data": {
            "enabled": true,
            "max_quota": 100000,
            "min_quota": 10000,
            "stats": {
              "checked_in_today": false,
              "checkin_count": 0,
              "records": [],
              "total_checkins": 30,
              "total_quota": 1680000
            }
          },
          "success": true
        }
    """.trimIndent()

    @Test
    fun `parses real checked-in status`() {
        val root = json.parseToJsonElement(realStatus).jsonObject
        val data = root["data"]!!.jsonObject
        val st = json.decodeFromJsonElement(CheckinStatus.serializer(), data)

        assertTrue(st.enabled)
        assertTrue(st.stats.checkedInToday)
        assertEquals(33, st.stats.totalCheckins)
        assertEquals(1725186L, st.stats.totalQuota)
        assertEquals(3, st.stats.records.size)
        assertEquals("2026-11-07", st.stats.records[0].checkinDate)
        assertEquals(44763L, st.stats.records[0].quotaAwarded)
    }

    @Test
    fun `checked in today blocks further checkin`() {
        val data = json.parseToJsonElement(realStatus).jsonObject["data"]!!.jsonObject
        val st = json.decodeFromJsonElement(CheckinStatus.serializer(), data)
        // 这是「网站签过 app 不能再签」的核心断言
        assertFalse(st.canCheckin)
    }

    @Test
    fun `not checked in allows checkin`() {
        val data = json.parseToJsonElement(notCheckedStatus).jsonObject["data"]!!.jsonObject
        val st = json.decodeFromJsonElement(CheckinStatus.serializer(), data)
        assertTrue(st.canCheckin)
    }

    @Test
    fun `disabled feature blocks checkin even when not checked in`() {
        val raw = """
            {"enabled": false, "max_quota": 100000, "min_quota": 10000,
             "stats": {"checked_in_today": false, "checkin_count": 0, "records": []}}
        """.trimIndent()
        val st = json.decodeFromString(CheckinStatus.serializer(), raw)
        assertFalse(st.canCheckin)
    }

    @Test
    fun `quota converts to usd consistently`() {
        val data = json.parseToJsonElement(realStatus).jsonObject["data"]!!.jsonObject
        val st = json.decodeFromJsonElement(CheckinStatus.serializer(), data)
        assertEquals(1725186 / 500000.0, st.stats.totalQuotaUsd, 1e-9)
        assertEquals(44763 / 500000.0, st.stats.records[0].quotaUsd, 1e-9)
        assertEquals(100000 / 500000.0, st.maxQuotaUsd, 1e-9)
        assertEquals(10000 / 500000.0, st.minQuotaUsd, 1e-9)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val raw = """{"enabled": true}"""
        val st = json.decodeFromString(CheckinStatus.serializer(), raw)
        assertTrue(st.enabled)
        assertFalse(st.stats.checkedInToday)
        assertEquals(0L, st.stats.totalQuota)
        assertTrue(st.stats.records.isEmpty())
    }

    @Test
    fun `success result carries awarded quota`() {
        val r = CheckinResult.Success(checkinDate = "2026-11-07", quotaAwarded = 44763)
        assertEquals(44763 / 500000.0, r.quotaUsd, 1e-9)
    }
}
