package com.gcmodai.app.data.datastore

import com.gcmodai.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

/**
 * 内置供应商清单。
 *
 * 产品口径（2026-11-11 起）：**只保留 OpenAI 与 GcmodAi 两个供应商**。
 * - GcmodAi 不在这里出现：它是登录后由 [com.gcmodai.app.data.user.OfficialProviderManager]
 *   按官方渠道 id 注入的（baseUrl = https://gcmod.online/v1），随账号的令牌与分组一起变，
 *   写死在这里反而会和登录态打架。
 * - OpenAI 保留为「用户自带 key」的通用入口，也是自定义渠道的默认协议。
 *
 * 想再加回某个内置渠道：把条目补回下面的 listOf 即可，[SettingsNormalization] 会自动
 * 给老用户补上；反过来，从这里删掉的 builtIn 渠道会在下次读盘时被清理掉。
 */
val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true,
    ),
)

/**
 * 已经下线的内置渠道 id（2026-11-11 起）。
 *
 * 为什么要单独列 id，而不是只靠 `builtIn = true` 判断：
 * 老版本装过的机器里，这些渠道是被 DEFAULT_PROVIDERS 补进来的，但它们在某些升级路径下
 * 可能已经被写过一轮（builtIn 标记被覆盖成 false），只按标记清理会漏。按 id 清理是确定的：
 * 这些 id 只属于内置渠道，绝不会和用户自建的渠道撞号。
 *
 * 注意：清理只作用于这里列出的 id。用户自己添加的渠道、GcmodAi 官方渠道一律不动。
 */
internal val RETIRED_PROVIDER_IDS: Set<Uuid> = setOf(
    Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"), // Gemini
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81"), // APIMart
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), // 硅基流动
    Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"), // DeepSeek
    Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"), // 月之暗面
    Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"), // OpenRouter
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"), // Vercel AI Gateway
    Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"), // 阿里云百炼
    Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"), // 火山引擎
    Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"), // 智谱AI开放平台
    Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"), // 阶跃星辰
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"), // 302.AI
    Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"), // 腾讯 Hunyuan
    Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"), // xAI
    Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"), // 随想AI网关
    Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"), // MaruCode
    Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"), // MiniMax
    Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"), // MIMO
)
