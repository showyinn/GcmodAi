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
