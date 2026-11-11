package com.gcmodai.app.data.datastore

import com.gcmodai.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置供应商清单的契约。
 *
 * 2026-11-11 起产品口径变化：模型供应商只保留 **GcmodAi 官方渠道** 与 **OpenAI**。
 * GcmodAi 不由这份清单提供（登录后由 OfficialProviderManager 注入），
 * 所以这里只剩 OpenAI 一条。这个用例把「只剩一条」钉死，
 * 防止后续有人顺手把第三方中转站加回来。
 */
class DefaultProvidersTest {
    @Test
    fun `built-in providers contain only openai`() {
        assertEquals("内置供应商应当只剩 OpenAI 一条", 1, DEFAULT_PROVIDERS.size)

        val provider = DEFAULT_PROVIDERS.single()
        assertEquals("OpenAI", provider.name)
        assertTrue("内置渠道必须标记 builtIn", provider.builtIn)
        assertEquals("https://api.openai.com/v1", provider.baseUrl)
        assertEquals("", provider.apiKey)
    }

    @Test
    fun `retired built-in providers are gone`() {
        val retired = listOf(
            "Gemini", "AiHubMix", "APIMart", "硅基流动", "DeepSeek", "月之暗面",
            "OpenRouter", "Vercel AI Gateway", "阿里云百炼", "火山引擎", "智谱AI开放平台",
            "阶跃星辰", "302.AI", "腾讯Hunyuan", "xAI", "随想AI网关", "MaruCode", "MiniMax", "MIMO",
        )
        val names = DEFAULT_PROVIDERS.map { it.name }.toSet()
        val stillThere = retired.filter { it in names }
        assertEquals("这些渠道已按产品要求下线，不应再出现在内置清单里", emptyList<String>(), stillThere)
        assertTrue(
            "推荐清单也必须清空，避免把下线渠道再推荐回来",
            RECOMMENDED_PROVIDERS.isEmpty(),
        )
    }
}
