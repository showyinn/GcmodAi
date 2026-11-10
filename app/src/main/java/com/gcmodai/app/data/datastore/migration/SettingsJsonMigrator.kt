package com.gcmodai.app.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.gcmodai.app.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [com.gcmodai.app.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching {
                            JsonInstant.parseToJsonElement(
                                JsonInstant.encodeToString(it)
                            ) as? JsonArray
                        }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            // V4: 移除 assistants 的 allowConversationPromptInjection 开关
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsConversationInjections(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V5: 提供商的余额查询 balanceOption 改成自定义额度查询 quotaQuery
            (root["providers"] as? JsonArray)?.let { providers ->
                root["providers"] = JsonArray(providers.map(::migrateProviderQuotaQuery))
            }

            // V6: 给已有助手补上工作区与 SSH 远程建站工具
            //
            // 为什么需要这步：这两个工具是后加的，只改 Assistant 的默认值
            // 对老用户无效——他们数据库里存的 localTools 还是旧列表，
            // 结果就是「AI 明明该能读写工作区，却调不到任何 ws_/ssh_ 工具」。
            // 这里在加载时统一补齐，用户无需手动去开关里勾选。
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsWorkspaceTools(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }

    /**
     * V6: 确保每个助手都启用 [WORKSPACE_TOOLS] 里的工具。
     *
     * 只做"补齐"，不删用户已选的其他工具；已经有的不重复添加。
     * 解析失败时原样返回，绝不让迁移阻断启动。
     */
    internal fun migrateAssistantsWorkspaceTools(assistantsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
                ?: return@runCatching assistantsJson

            var changed = false
            val migrated = JsonArray(root.map { element ->
                val obj = element as? JsonObject
                if (obj == null) {
                    element
                } else {
                    val current = obj["localTools"] as? JsonArray
                    if (current == null) {
                        // 字段缺失：补一个含新工具的列表
                        changed = true
                        JsonObject(
                            obj + ("localTools" to JsonArray(
                                WORKSPACE_TOOLS.map { JsonPrimitive(it) }
                            ))
                        )
                    } else {
                        val existing = current.mapNotNull {
                            runCatching { it.jsonPrimitive.content }.getOrNull()
                        }.toSet()
                        val missing = WORKSPACE_TOOLS.filter { it !in existing }
                        if (missing.isEmpty()) {
                            obj
                        } else {
                            changed = true
                            JsonObject(
                                obj + ("localTools" to JsonArray(
                                    current + missing.map { JsonPrimitive(it) }
                                ))
                            )
                        }
                    }
                }
            })

            if (changed) JsonInstant.encodeToString(migrated) else assistantsJson
        }.getOrDefault(assistantsJson)
    }

    /**
     * 需要自动补齐的工具标识。
     *
     * 取值必须与 [com.gcmodai.app.data.ai.tools.local.LocalToolOption] 的
     * @SerialName 完全一致，否则反序列化会失败。
     */
    private val WORKSPACE_TOOLS = listOf(
        "workspace_server",
        "ssh_remote",
    )
}
