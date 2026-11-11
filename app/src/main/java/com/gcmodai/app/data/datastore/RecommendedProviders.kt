package com.gcmodai.app.data.datastore

import com.gcmodai.ai.provider.ProviderSetting

/**
 * 推荐供应商列表（供应商设置页右上角推荐 Sheet 的数据源）。
 *
 * 产品口径（2026-11-11 起）：模型供应商只保留 **GcmodAi 官方渠道** 与 **OpenAI**，
 * 所以这里不再推荐任何第三方中转站——列表为空时，[com.gcmodai.app.ui.pages.setting.SettingProviderPage]
 * 里的推荐入口整个不渲染。
 *
 * 需要恢复某个推荐位时，直接把条目补回 listOf 即可（结构同 [DEFAULT_PROVIDERS]）。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = emptyList()
