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
 * V6：给已有助手补上「服务器工作区」与「SSH 远程服务器」两个本地工具。
 *
 * ## 为什么需要这个迁移
 *
 * 这两个工具是 3.6.x 新增的。只在 [com.gcmodai.app.data.model.Assistant] 里
 * 改默认值**对老用户无效**——助手配置整体序列化存在 DataStore 里，
 * 老用户盘上的 `localTools` 还是旧列表，反序列化后就没有新工具。
 *
 * ## 格式必须与 kotlinx.serialization 一致（踩过坑）
 *
 * [com.gcmodai.app.data.ai.tools.local.LocalToolOption] 是 `sealed class`，
 * 默认用**多态序列化**，每项必须写成对象：
 *
 * ```json
 * {"type":"workspace_server"}
 * ```
 *
 * 而不是裸字符串 `"workspace_server"`。写成裸字符串会让整个
 * `localTools` 反序列化抛异常，助手的工具列表整体丢失——
 * 这比"少两个工具"严重得多，所以这里严格按多态格式写入。
 *
 * ## 策略
 *
 * 只补不删：已选工具原样保留，缺的两个追加。
 * 解析失败时原样返回，绝不阻断启动。
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        prefs[SettingsStore.ASSISTANTS]?.let { json ->
            prefs[SettingsStore.ASSISTANTS] = migrateAssistantsWorkspaceTools(json)
        }

        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * 给每个助手补齐工作区相关工具。幂等，可反复执行。
 */
internal fun migrateAssistantsWorkspaceTools(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson

        var changed = false
        val migrated = JsonArray(
            root.map { element ->
                val obj = element as? JsonObject ?: return@map element
                val current = obj["localTools"] as? JsonArray

                if (current == null) {
                    // 字段缺失：补一个只含新工具的列表
                    changed = true
                    JsonObject(
                        obj + ("localTools" to JsonArray(WORKSPACE_TOOLS.map(::toolEntry)))
                    )
                } else {
                    val existing = current.mapNotNull(::toolNameOf).toSet()
                    val missing = WORKSPACE_TOOLS.filter { it !in existing }
                    if (missing.isEmpty()) {
                        obj
                    } else {
                        changed = true
                        JsonObject(
                            obj + (
                                "localTools" to JsonArray(
                                    current + missing.map(::toolEntry)
                                )
                                )
                        )
                    }
                }
            }
        )

        if (changed) JsonInstant.encodeToString(migrated) else assistantsJson
    }.getOrDefault(assistantsJson)
}

/**
 * 生成一个工具项。
 *
 * 兼容两种历史格式：
 *  - 新版：`{"type":"workspace_server"}`
 *  - 极老版本可能存的裸字符串
 * 这里统一输出新版多态格式。
 */
private fun toolEntry(name: String): JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive(name))
)

/**
 * 从一个工具项里取出标识。
 *
 * 兼容 `{"type":"x"}` 和裸字符串 `"x"` 两种写法，
 * 这样即使盘上是旧格式也能正确判断"是否已存在"，避免重复追加。
 */
private fun toolNameOf(element: kotlinx.serialization.json.JsonElement): String? {
    (element as? JsonObject)?.get("type")?.let { t ->
        runCatching { t.jsonPrimitive.contentOrNull }.getOrNull()?.let { return it }
    }
    return runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
}

/**
 * 需要自动补齐的工具标识。
 *
 * 必须与 [com.gcmodai.app.data.ai.tools.local.LocalToolOption] 的
 * `@SerialName` 完全一致，否则反序列化会失败。
 */
private val WORKSPACE_TOOLS = listOf(
    "workspace_server",
    "ssh_remote",
)
