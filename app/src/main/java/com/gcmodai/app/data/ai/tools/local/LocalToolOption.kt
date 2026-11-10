package com.gcmodai.app.data.ai.tools.local

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 本地工具开关。
 *
 * ## 为什么自己写序列化器
 *
 * 这是 `sealed class`，kotlinx 默认用**多态格式**：每项必须写成
 * `{"type":"workspace_server"}`。一旦盘上出现裸字符串 `"workspace_server"`，
 * 反序列化就抛 `JsonDecodingException`——而设置加载失败时上层会
 * `Runtime.getRuntime().halt(1)` 直接杀进程，或者整个助手的工具列表集体丢失。
 *
 * 历史上就因为这个格式问题，出现「AI 明明该能写工作区却调不到工具」。
 * 现在换成容错序列化器：
 *
 *  - `{"type":"x"}`  → 正常解析（当前格式）
 *  - `"x"`           → 也接受（历史坏数据）
 *  - 不认识的名字     → [Unknown]，不抛异常
 *
 * 这样**解析永远不会失败**，坏数据最多是"少一个工具"，绝不会拖垮整份设置。
 */
@Serializable(with = LocalToolOptionSerializer::class)
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    /** 电脑控制：AI 在用户整台电脑上执行命令、读写任意路径、配环境 */
    @Serializable
    @SerialName("pc_control")
    data object PcControl : LocalToolOption()

    /** SSH 远程服务器：AI 在用户自己的服务器上建站 */
    @Serializable
    @SerialName("ssh_remote")
    data object SshRemote : LocalToolOption()

    /** 服务器工作区：AI 写代码直接落到 100MB 隔离工作区并部署成网站 */
    @Serializable
    @SerialName("workspace_server")
    data object WorkspaceServer : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    /**
     * GcmodAi 官方工具集（计算、文本、日期、哈希、JSON、单位换算、URL/Base64 编解码）。
     *
     * 作为一个整体开关存在：它们全是零网络、零隐私的纯计算工具，
     * 拆成八个独立开关只会让用户在设置页里困惑。
     */
    @Serializable
    @SerialName("gcmodai_official")
    data object GcmodAiOfficial : LocalToolOption()

    /**
     * 无法识别的工具名。
     *
     * 存在的唯一目的：让反序列化永不失败。旧版本写入的、或未来版本新增的
     * 工具名，解析成它之后在 [LocalTools.getTools] 里自然被忽略。
     */
    @Serializable
    @SerialName("unknown")
    data object Unknown : LocalToolOption()
}

/** 标识 ↔ 实例的唯一映射表，序列化与反序列化共用，避免两处写错不一致。 */
private val SERIAL_NAMES: Map<String, LocalToolOption> = listOf(
    "javascript_engine" to LocalToolOption.JavascriptEngine,
    "ssh_remote" to LocalToolOption.SshRemote,
    "pc_control" to LocalToolOption.PcControl,
    "workspace_server" to LocalToolOption.WorkspaceServer,
    "time_info" to LocalToolOption.TimeInfo,
    "clipboard" to LocalToolOption.Clipboard,
    "tts" to LocalToolOption.Tts,
    "ask_user" to LocalToolOption.AskUser,
    "screen_time" to LocalToolOption.ScreenTime,
    "calendar" to LocalToolOption.Calendar,
    "chart_display" to LocalToolOption.ChartDisplay,
    "gcmodai_official" to LocalToolOption.GcmodAiOfficial,
    "unknown" to LocalToolOption.Unknown,
).toMap()

private val NAMES_BY_OPTION: Map<LocalToolOption, String> =
    SERIAL_NAMES.entries.associate { (name, option) -> option to name }

/** 取实例对应的标识；未登记的实例退化为 "unknown"。 */
internal fun LocalToolOption.serialName(): String = NAMES_BY_OPTION[this] ?: "unknown"

/** 标识转实例；空或未登记一律给 [LocalToolOption.Unknown]，绝不抛异常。 */
internal fun localToolOptionFromName(name: String?): LocalToolOption =
    SERIAL_NAMES[name] ?: LocalToolOption.Unknown

/**
 * 容错序列化器。
 *
 * 写出统一用多态对象 `{"type":"x"}`，与既有数据保持一致；
 * 读入同时接受对象和裸字符串。
 */
object LocalToolOptionSerializer : KSerializer<LocalToolOption> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("LocalToolOption") {
            element<String>("type")
        }

    override fun serialize(encoder: Encoder, value: LocalToolOption) {
        val name = value.serialName()
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(buildJsonObject { put("type", name) })
        } else {
            encoder.encodeString(name)
        }
    }

    override fun deserialize(decoder: Decoder): LocalToolOption {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder == null) {
            // 非 JSON 格式：按裸字符串处理
            val raw = runCatching { decoder.decodeString() }.getOrNull()
            return localToolOptionFromName(raw)
        }

        val element = runCatching { jsonDecoder.decodeJsonElement() }.getOrNull()
        val name = when (element) {
            is JsonObject -> runCatching {
                element["type"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()

            is JsonPrimitive -> element.contentOrNull
            else -> null
        }
        return localToolOptionFromName(name)
    }
}
