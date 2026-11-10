package com.gcmodai.app.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.gcmodai.app.data.datastore.SettingsStore
import com.gcmodai.app.utils.JsonInstant

/**
 * V7：修复 V6 曾经写坏的工具列表，并确保工作区/SSH 工具存在。
 *
 * ## 为什么需要单独一个 V7
 *
 * V6 的第一版实现把工具项写成了裸字符串 `"workspace_server"`，
 * 而正确格式是多态对象 `{"type":"workspace_server"}`。
 * 那次迁移执行后 `data_version` 已经是 6，所以**修好的 V6 不会再跑**
 * （`shouldMigrate` 看到版本 6 就跳过），坏数据永远留在盘上。
 *
 * 因此必须新增 V7：无视之前写过什么，把 `localTools` 全部规范化一遍。
 *
 * ## 规范化规则
 *
 * - 裸字符串 `"x"`            → `{"type":"x"}`
 * - 对象 `{"type":"x"}`       → 原样保留
 * - 其他奇怪形状              → 丢弃（宁可少一个开关，也不能让解析失败）
 * - 缺少 workspace_server / ssh_remote → 追加
 */
class PreferenceStoreV7Migration : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 7
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.ASSISTANTS]?.let { json ->
            prefs[SettingsStore.ASSISTANTS] = normalizeAssistantsLocalTools(json)
        }

        prefs[SettingsStore.VERSION] = 7
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 把每个助手的 `localTools` 规范化成多态对象数组，并补齐工作区工具。
 *
 * 幂等：规范化后的数据再跑一次不会有任何变化。
 */
internal fun normalizeAssistantsLocalTools(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson

        var changed = false

        val migrated = JsonArray(
            root.map { element ->
                val obj = element as? JsonObject ?: return@map element

                val raw = obj["localTools"] as? JsonArray

                // 逐个规范化；无法识别的形状直接丢弃
                val normalized = mutableListOf<JsonObject>()
                val seen = mutableSetOf<String>()

                raw?.forEach { item ->
                    val name = extractToolName(item) ?: return@forEach
                    if (name.isBlank() || name == "unknown") return@forEach
                    if (!seen.add(name)) return@forEach
                    normalized.add(toolObject(name))
                    // 原样是对象且形状正确 → 不算改动
                    if (item !is JsonObject) changed = true
                }

                if (raw == null) changed = true
                if (raw != null && normalized.size != raw.size) changed = true

                // 补齐工作区相关工具
                WORKSPACE_TOOLS.forEach { name ->
                    if (seen.add(name)) {
                        normalized.add(toolObject(name))
                        changed = true
                    }
                }

                JsonObject(obj + ("localTools" to JsonArray(normalized)))
            }
        )

        if (changed) JsonInstant.encodeToString(migrated) else assistantsJson
    }.getOrDefault(assistantsJson)
}

/**
 * 从任意形状的工具项里取名字。
 *
 * 兼容 `{"type":"x"}` 与裸字符串 `"x"`，其余返回 null。
 */
private fun extractToolName(element: kotlinx.serialization.json.JsonElement): String? {
    return when (element) {
        is JsonObject -> runCatching {
            element["type"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        is JsonPrimitive -> element.contentOrNull
        else -> null
    }
}

private fun toolObject(name: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(name)))

/**
 * 需要保证存在的工具。
 *
 * 必须与 [com.gcmodai.app.data.ai.tools.local.LocalToolOption] 的标识一致。
 */
private val WORKSPACE_TOOLS = listOf(
    "workspace_server",
    "ssh_remote",
)
