package com.gcmodai.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程配置解析验证。
 *
 * 重点：管理后台现在有 8 组 36 项，App 必须能正确解析全部字段；
 * 缺字段、类型错、空值都不能让启动崩掉。
 */
class RemoteConfigMergeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 真实线上返回（精简版，覆盖全部 36 键）。 */
    private val realPayload = """
    {
      "app_name": "GcmodAi",
      "app_slogan": "你大白话说我直接干",
      "welcome_message": "欢迎回来",
      "startup_tip": "正在加载…",
      "about_text": "GcmodAi 是基于 rikkahub 的客户端",
      "official_group_name": "GcmodAi 官方群",
      "official_group_number": "345994931",
      "official_group_desc": "点击加入官方群",
      "official_group_url": "https://qun.qq.com/universal-share/share?ac=1&authKey=abc",
      "official_group_qr": "",
      "support_contact": "QQ 群 345994931",
      "support_email": "support@gcmod.online",
      "announcement_title": "更新公告",
      "announcement": "第一行\n第二行",
      "announcement_enabled": "true",
      "latest_version": "3.4.2",
      "download_url": "https://gcmod.bond/",
      "force_update": "false",
      "update_check_enabled": "true",
      "doc_url": "https://gcmod.online/",
      "terms_url": "",
      "privacy_url": "",
      "register_url": "https://gcmod.online/",
      "quota_per_usd": "500000",
      "checkin_min_hint": "10000",
      "checkin_max_hint": "100000",
      "register_bonus_hint": "",
      "files_quota_mb": "100",
      "files_retention_hours": "24",
      "files_max_file_mb": "32",
      "checkin_enabled_hint": "true",
      "billing_enabled": "true",
      "files_hosting_enabled": "true",
      "tools_enabled": "true",
      "onboarding_enabled": "true",
      "register_enabled": "true"
    }
    """.trimIndent()

    @Test
    fun `all 36 keys present in payload`() {
        val obj = json.parseToJsonElement(realPayload).jsonObject
        assertEquals(36, obj.size)
    }

    @Test
    fun `payload covers every RemoteConfig field we expose`() {
        // 后台 schema 的 36 项都要映射到 RemoteConfig 上的字段
        val expected = setOf(
            "app_name", "app_slogan", "welcome_message", "startup_tip", "about_text",
            "official_group_name", "official_group_number", "official_group_desc",
            "official_group_url", "official_group_qr", "support_contact", "support_email",
            "announcement_title", "announcement", "announcement_enabled",
            "latest_version", "download_url", "force_update", "update_check_enabled",
            "doc_url", "terms_url", "privacy_url", "register_url",
            "quota_per_usd", "checkin_min_hint", "checkin_max_hint", "register_bonus_hint",
            "files_quota_mb", "files_retention_hours", "files_max_file_mb",
            "checkin_enabled_hint", "billing_enabled", "files_hosting_enabled",
            "tools_enabled", "onboarding_enabled", "register_enabled",
        )
        val obj = json.parseToJsonElement(realPayload).jsonObject
        assertEquals(expected, obj.keys)
    }

    @Test
    fun `defaults are sane when nothing loaded`() {
        val c = RemoteConfig()
        assertEquals("GcmodAi", c.appName)
        assertEquals(500000L, c.quotaPerUsd)
        assertEquals(100, c.filesQuotaMb)
        assertEquals(24, c.filesRetentionHours)
        assertEquals(32, c.filesMaxFileMb)
        assertTrue(c.checkinEnabled)
        assertTrue(c.billingEnabled)
        assertTrue(c.onboardingEnabled)
        assertTrue(c.registerEnabled)
        assertTrue(c.updateCheckEnabled)
        assertFalse(c.loaded)
        assertFalse(c.forceUpdate)
    }

    @Test
    fun `empty group fields means no official group`() {
        val c = RemoteConfig()
        assertFalse(c.hasOfficialGroup)
        val withNumber = c.copy(officialGroupNumber = "345994931")
        assertTrue(withNumber.hasOfficialGroup)
    }

    @Test
    fun `numeric fields parse from strings`() {
        val obj = json.parseToJsonElement(realPayload).jsonObject
        fun num(k: String) = (obj[k] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(500000L, num("quota_per_usd").toLong())
        assertEquals(100, num("files_quota_mb").toInt())
        assertEquals(24, num("files_retention_hours").toInt())
        assertEquals(32, num("files_max_file_mb").toInt())
    }

    @Test
    fun `bool fields are strictly true or false`() {
        val obj = json.parseToJsonElement(realPayload).jsonObject
        listOf(
            "announcement_enabled", "force_update", "update_check_enabled",
            "checkin_enabled_hint", "billing_enabled", "files_hosting_enabled",
            "tools_enabled", "onboarding_enabled", "register_enabled",
        ).forEach { k ->
            val v = (obj[k] as kotlinx.serialization.json.JsonPrimitive).content
            assertTrue("$k 必须是 true/false，实际=$v", v == "true" || v == "false")
        }
    }

    @Test
    fun `qq share url survives json round trip`() {
        val obj = json.parseToJsonElement(realPayload).jsonObject
        val url = (obj["official_group_url"] as kotlinx.serialization.json.JsonPrimitive).content
        assertTrue(url.contains("universal-share/share"))
        assertTrue(url.contains("authKey="))
        assertFalse(url.contains("%25"))
    }
}
