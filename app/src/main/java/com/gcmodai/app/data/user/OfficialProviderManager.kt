package com.gcmodai.app.data.user

import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ModelType
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * 官方模型通道（真实计费）。
 *
 * 用户在 App 内选中某个密钥后，这里把「GcmodAi 官方」供应商写入设置：
 * - baseUrl 固定 https://gcmod.online/v1，请求直接由官网 New API 网关处理；
 * - apiKey 用选中密钥的完整明文 key，官网按 token 归属用户做真实扣费；
 * - 模型列表用 /api/user/models 的可用模型（用户分组内可调的）。
 *
 * 供应商是固定 id 的单例，重复调用只更新 key/模型，不会重复创建。
 */
class OfficialProviderManager(
    private val settingsStore: SettingsStore,
    private val userRepository: UserRepository,
) {

    /** 官方供应商固定 id，用于识别与去重 */
    val officialProviderId: Uuid = Uuid.parse("7cc0f2a1-5b34-4e8d-9a20-1f6d3b8c4e57")

    /** 官方网关地址（OpenAI 兼容） */
    val officialBaseUrl: String = UserApi.BASE_URL + "/v1"

    /** 读取当前官方供应商配置（可能不存在） */
    fun currentProvider(settings: Settings): ProviderSetting.OpenAI? {
        return settings.providers.firstOrNull { it.id == officialProviderId } as? ProviderSetting.OpenAI
    }

    /**
     * 把选中密钥与模型列表同步进官方供应商配置。
     * 未选中密钥时只是禁用官方供应商，不清 key，方便切回来。
     *
     * 模型列表每次都会重建，但**必须按 modelId 合并旧条目**：
     * 用户在「设置 → 模型 → 模型能力」里手动勾的 TOOL / REASONING 以及改过的
     * 上下文长度，如果重建时丢掉，只要触发一次登录/选密钥/刷新模型就会被抹掉。
     */
    suspend fun applySelectedToken() {
        val session = userRepository.sessionFlow.value
        val apiKey = userRepository.selectedApiKey(session)
        val models = userRepository.models.value.ifEmpty {
            // 没有拉过模型列表就直接用上次缓存的供应商模型
            emptyList()
        }
        settingsStore.update { settings ->
            val existing = currentProvider(settings)
            val modelList = if (models.isEmpty()) {
                existing?.models ?: emptyList()
            } else {
                mergeOfficialModels(models, existing?.models.orEmpty())
            }
            val updated = ProviderSetting.OpenAI(
                id = officialProviderId,
                enabled = apiKey != null,
                name = OFFICIAL_PROVIDER_NAME,
                models = modelList,
                apiKey = apiKey ?: existing?.apiKey.orEmpty(),
                baseUrl = officialBaseUrl,
                builtIn = false,
            )
            val others = settings.providers.filterNot { it.id == officialProviderId }
            settings.copy(providers = others + updated)
        }
    }

    /** 拉取模型列表后同步官方供应商 */
    suspend fun syncModelsAndApply() {
        val result = userRepository.refreshTokens()
        if (result.isSuccess) applySelectedToken()
    }

    companion object {
        const val OFFICIAL_PROVIDER_NAME = "GcmodAi 官方服务"

        /**
         * 用服务端最新的模型名重建模型列表，同时保留用户已有的手动配置。
         *
         * 合并规则（全部按 `modelId` 匹配）：
         * - `id` 沿用旧值，否则每次同步都会换 id，会让「当前选中的模型」失效；
         * - `abilities` / `inputModalities` / `contextLength` 非空就保留（视为用户配置过），
         *   为空才用 [ModelCapability] 的推断结果；
         * - 服务端已下线的模型会被丢弃，新增的模型自动补上。
         *
         * 抽成纯函数是为了让单元测试能盯住「同步不丢用户配置」这条不变量——
         * 之前这里直接 `Model(modelId, displayName, type)`，一次同步就抹掉用户勾的能力。
         *
         * 已知限制：`abilities` 为空既可能是「没配置过」也可能是「用户主动取消了全部能力」，
         * 两者在数据模型上无法区分，因此白名单模型的空能力会被推断结果补回。
         * 用户若要关掉某个模型的能力，需改 `Model` 增加显式的「已配置」标记，另开任务处理。
         */
        internal fun mergeOfficialModels(
            modelIds: List<String>,
            previous: List<Model>,
        ): List<Model> {
            val old = previous.associateBy { it.modelId }
            return modelIds.map { modelId ->
                val existing = old[modelId]
                Model(
                    id = existing?.id ?: Uuid.random(),
                    modelId = modelId,
                    displayName = existing?.displayName ?: modelId,
                    type = ModelType.CHAT,
                    abilities = existing?.abilities?.takeIf { it.isNotEmpty() }
                        ?: ModelCapability.inferAbilities(modelId),
                    inputModalities = existing?.inputModalities?.takeIf { it.isNotEmpty() }
                        ?: ModelCapability.inferInputModalities(modelId),
                    contextLength = existing?.contextLength
                        ?: ModelCapability.inferContextLength(modelId),
                )
            }
        }
    }
}
