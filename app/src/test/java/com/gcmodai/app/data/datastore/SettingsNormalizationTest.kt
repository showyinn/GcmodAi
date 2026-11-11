package com.gcmodai.app.data.datastore

import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 内置渠道下线的清理契约：升级后用户的设置里只应剩下 OpenAI 与 GcmodAi 官方渠道。
 *
 * 这段逻辑判错的代价是可见的——用户升级后仍看到十几个空 key 的旧渠道（真实发生过），
 * 所以两种历史形态都要钉死：builtIn 标记还在的、以及标记已经被写成 false 的。
 */
class SettingsNormalizationTest {

    private val officialId: Uuid = Uuid.parse("7cc0f2a1-5b34-4e8d-9a20-1f6d3b8c4e57")
    private val aiHubMixId: Uuid = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953")
    private val geminiId: Uuid = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d")

    private fun ld(name: String, id: Uuid, builtIn: Boolean, key: String = "") =
        ProviderSetting.OpenAI(
            id = id,
            enabled = true,
            name = name,
            models = listOf(Model(modelId = "$name-model", displayName = name)),
            apiKey = key,
            baseUrl = "https://$name.example/v1",
            builtIn = builtIn,
        )

    @Test
    fun `retired built-in providers are dropped when flag is still true`() {
        val settings = Settings(
            providers = listOf(
                ld("AiHubMix", aiHubMixId, builtIn = true),
                ld("Gemini", geminiId, builtIn = true),
            )
        ).normalized()

        val names = settings.providers.map { it.name }
        assertFalse("已下线渠道必须被清掉", "AiHubMix" in names)
        assertFalse("已下线渠道必须被清掉", "Gemini" in names)
        assertTrue("OpenAI 必须保留", "OpenAI" in names)
        assertEquals("清理后只应剩 OpenAI", 1, settings.providers.size)
    }

    @Test
    fun `retired providers are dropped even if the builtIn flag was overwritten`() {
        // 某些升级路径会把 builtIn 写回 false，靠标记判断会漏掉，必须靠 id 兜底
        val settings = Settings(
            providers = listOf(
                ld("AiHubMix", aiHubMixId, builtIn = false, key = "sk-old"),
                ld("Gemini", geminiId, builtIn = false),
            )
        ).normalized()

        val names = settings.providers.map { it.name }
        assertFalse("标记丢了也必须按 id 清掉", "AiHubMix" in names)
        assertFalse("标记丢了也必须按 id 清掉", "Gemini" in names)
    }

    @Test
    fun `official channel and user-owned channels survive`() {
        val userOwn = ld("我的中转", Uuid.random(), builtIn = false, key = "sk-mine")
        val official = ProviderSetting.OpenAI(
            id = officialId,
            enabled = true,
            name = "GcmodAi 官方服务",
            models = listOf(Model(modelId = "auto", displayName = "自动")),
            apiKey = "sk-official",
            baseUrl = "https://gcmod.online/v1",
            builtIn = false,
        )
        val settings = Settings(
            providers = listOf(userOwn, official, ld("MIMO", Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"), builtIn = true))
        ).normalized()

        val ids = settings.providers.map { it.id }
        assertTrue("用户自建渠道不能被误删", userOwn.id in ids)
        assertTrue("GcmodAi 官方渠道不能被误删", officialId in ids)
        assertTrue("OpenAI 必须保留", settings.providers.any { it.name == "OpenAI" })
        assertFalse("MIMO 已下线", "MIMO" in settings.providers.map { it.name })
    }
}
