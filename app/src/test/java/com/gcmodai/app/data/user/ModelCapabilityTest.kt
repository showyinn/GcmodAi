package com.gcmodai.app.data.user

import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ModelAbility
import com.gcmodai.ai.provider.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方通道模型能力推断的回归测试。
 *
 * 背景：官方通道 `/api/user/models` 只回模型名，同步时若把 abilities 留空，
 * 聊天里就不会挂工具（ChatService 只在 ModelAbility.TOOL in abilities 时才走工具调用）。
 */
class ModelCapabilityTest {

    @Test
    fun `deepseek models get tool ability`() {
        for (id in listOf(
            "deepseek-chat", "deepseek-v3", "deepseek-v3.1", "deepseek-v3.2",
            "deepseek-r1", "deepseek-reasoner",
        )) {
            assertTrue("$id 应支持工具调用", ModelAbility.TOOL in ModelCapability.inferAbilities(id))
        }
    }

    @Test
    fun `reasoning models get reasoning ability`() {
        for (id in listOf("deepseek-r1", "deepseek-reasoner", "o1", "o3-mini", "qwq-32b", "glm-z1-air")) {
            assertTrue("$id 应有推理能力", ModelAbility.REASONING in ModelCapability.inferAbilities(id))
        }
    }

    @Test
    fun `mainstream chat models get tool ability`() {
        val cases = listOf(
            "gpt-4o", "gpt-4.1-mini", "gpt-5", "claude-sonnet-4",
            "gemini-2.5-pro", "qwen-max", "glm-4-plus", "moonshot-v1-8k",
            "grok-3", "mistral-large", "llama-3.3-70b",
        )
        for (id in cases) {
            assertTrue("$id 应支持工具调用", ModelAbility.TOOL in ModelCapability.inferAbilities(id))
        }
    }

    /** 未知模型不能瞎标能力：误标会让请求带上 tools 字段，服务端直接报错。 */
    @Test
    fun `unknown models get no abilities`() {
        for (id in listOf("some-random-model", "text-embedding-3-small", "whisper-1", "")) {
            assertEquals("$id 不应被标任何能力", emptyList<ModelAbility>(), ModelCapability.inferAbilities(id))
        }
    }

    @Test
    fun `vision models get image modality`() {
        for (id in listOf("gpt-4o", "claude-3-5-sonnet", "gemini-2.5-flash", "qwen-vl-max")) {
            assertTrue("$id 应支持图片输入", Modality.IMAGE in ModelCapability.inferInputModalities(id))
        }
    }

    @Test
    fun `text only models stay text`() {
        for (id in listOf("deepseek-chat", "o3-mini", "glm-z1-air", "some-random-model")) {
            assertEquals(
                "$id 应只有文本输入",
                listOf(Modality.TEXT),
                ModelCapability.inferInputModalities(id),
            )
        }
    }

    @Test
    fun `context length is conservative`() {
        assertEquals(128_000, ModelCapability.inferContextLength("deepseek-chat"))
        assertEquals(128_000, ModelCapability.inferContextLength("gpt-4o"))
        assertEquals(32_000, ModelCapability.inferContextLength("some-random-model"))
    }

    @Test
    fun `inference is case insensitive`() {
        assertEquals(
            ModelCapability.inferAbilities("deepseek-chat"),
            ModelCapability.inferAbilities("DeepSeek-Chat"),
        )
    }

    // ---------- 官方通道同步时的模型合并 ----------

    /**
     * 核心回归：同步模型列表不能抹掉用户手动勾的工具能力。
     *
     * 曾经的 bug 是同步时直接用 `Model(modelId, displayName, type)` 重建，
     * abilities 回到空，用户勾了 TOOL 只要触发一次登录/选密钥就被清掉。
     */
    @Test
    fun `sync keeps user enabled tool ability`() {
        val custom = Model(modelId = "some-random-model", displayName = "随机模型")
            .copy(abilities = listOf(ModelAbility.TOOL))
        val merged = OfficialProviderManager.mergeOfficialModels(listOf("some-random-model"), listOf(custom))
        assertTrue("用户给非白名单模型手动开了工具，同步后必须保留", ModelAbility.TOOL in merged.first().abilities)
    }

    /**
     * 已知限制：`abilities` 为空既可能是「没配置过」也可能是「用户主动取消了」，
     * 数据模型上无法区分，所以同步会把白名单模型的推断能力补回来。
     * 这里把这个行为固定下来，避免以后有人误以为是 bug 又去改坏它。
     */
    @Test
    fun `sync refills inferred ability for whitelisted model with empty abilities`() {
        val untouched = Model(modelId = "deepseek-chat", displayName = "deepseek-chat")
        val merged = OfficialProviderManager.mergeOfficialModels(listOf("deepseek-chat"), listOf(untouched))
        assertTrue(
            "白名单模型的空能力视为未配置，同步时补上推断结果",
            ModelAbility.TOOL in merged.first().abilities,
        )
    }

    @Test
    fun `sync preserves model id so selection survives`() {
        val previous = listOf(Model(modelId = "deepseek-chat", displayName = "deepseek-chat"))
        val first = OfficialProviderManager.mergeOfficialModels(listOf("deepseek-chat"), previous)
        val second = OfficialProviderManager.mergeOfficialModels(listOf("deepseek-chat"), first)
        assertEquals(
            "重复同步必须保持同一个 id，否则当前选中的模型会失效",
            first.first().id,
            second.first().id,
        )
    }

    @Test
    fun `sync applies inference for brand new models`() {
        val merged = OfficialProviderManager.mergeOfficialModels(listOf("deepseek-v3", "brand-new-xyz"), emptyList())
        assertEquals(2, merged.size)
        assertTrue(ModelAbility.TOOL in merged[0].abilities)
        assertEquals("未知新模型不应被标能力", emptyList<ModelAbility>(), merged[1].abilities)
        assertEquals("未知新模型也应带上推断的上下文长度", 32_000, merged[1].contextLength)
    }

    @Test
    fun `sync drops removed models and adds new ones`() {
        val previous = listOf(
            Model(modelId = "old-model", displayName = "old-model"),
            Model(modelId = "deepseek-chat", displayName = "deepseek-chat"),
        )
        val merged = OfficialProviderManager.mergeOfficialModels(
            listOf("deepseek-chat", "new-model"),
            previous,
        )
        assertEquals(listOf("deepseek-chat", "new-model"), merged.map { it.modelId })
    }

    @Test
    fun `sync preserves user edited display name and context length`() {
        val custom = Model(modelId = "deepseek-chat", displayName = "我的主力模型")
            .copy(contextLength = 65_536)
        val merged = OfficialProviderManager.mergeOfficialModels(listOf("deepseek-chat"), listOf(custom))
        assertEquals("我的主力模型", merged.first().displayName)
        assertEquals(65_536, merged.first().contextLength)
    }

    /**
     * 顺序变化边界：合并必须以 modelId 为键，不能用下标对齐。
     *
     * 用下标会张冠李戴——A 的能力贴到 B 身上，用户勾的 TOOL 跑到别的模型去。
     */
    @Test
    fun `sync matches by modelId not by index when order changes`() {
        val previous = listOf(
            Model(modelId = "deepseek-chat", displayName = "A").copy(abilities = listOf(ModelAbility.TOOL)),
            Model(modelId = "some-random-model", displayName = "B").copy(
                abilities = listOf(ModelAbility.REASONING),
                contextLength = 8_888,
            ),
        )
        // 服务端把顺序反过来了
        val merged = OfficialProviderManager.mergeOfficialModels(
            listOf("some-random-model", "deepseek-chat"),
            previous,
        )
        val byId = merged.associateBy { it.modelId }

        assertEquals("顺序变化后名字必须跟着 modelId 走", "B", byId.getValue("some-random-model").displayName)
        assertEquals("顺序变化后名字必须跟着 modelId 走", "A", byId.getValue("deepseek-chat").displayName)
        assertEquals(8_888, byId.getValue("some-random-model").contextLength)
        assertTrue(ModelAbility.TOOL in byId.getValue("deepseek-chat").abilities)
        assertTrue(ModelAbility.REASONING in byId.getValue("some-random-model").abilities)
    }

    /**
     * 新增模型边界：新模型必须拿全新 id，不能继承旧模型的 id。
     *
     * 否则旧 id 可能正是「当前选中的模型」，等于选中项被静默换人。
     */
    @Test
    fun `sync gives brand new models a fresh id`() {
        val previous = listOf(
            Model(modelId = "deepseek-chat", displayName = "deepseek-chat"),
        )
        val existingIds = previous.map { it.id }.toSet()
        val merged = OfficialProviderManager.mergeOfficialModels(
            listOf("deepseek-chat", "brand-new-model"),
            previous,
        )
        val newModel = merged.first { it.modelId == "brand-new-model" }
        assertTrue("新增模型不能复用任何既有 id", newModel.id !in existingIds)
        assertTrue("既有模型的 id 必须保持不变", merged.first { it.modelId == "deepseek-chat" }.id in existingIds)
    }

    /**
     * 已知取舍：用户在官方 provider 上主动取消全部能力后，下一次同步会被推断值补回来
     * （因为空列表无法区分「没配过」和「主动取消」）。这是取舍不是 bug，固定住行为。
     */
    @Test
    fun `sync refills abilities when user cleared them all`() {
        val cleared = Model(modelId = "deepseek-chat", displayName = "deepseek-chat")
            .copy(abilities = emptyList())
        val merged = OfficialProviderManager.mergeOfficialModels(listOf("deepseek-chat"), listOf(cleared))
        assertTrue(
            "白名单模型清空能力后同步会补回推断值（已知取舍）",
            ModelAbility.TOOL in merged.first().abilities,
        )
    }
}
