package com.gcmodai.app.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.gcmodai.ai.core.ReasoningLevel
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.asr.ASRProviderSetting
import com.gcmodai.mediagen.provider.MediaGenerationProviderSetting
import com.gcmodai.app.data.ai.mcp.McpServerConfig
import com.gcmodai.app.data.ai.prompts.DEFAULT_OCR_PROMPT
import com.gcmodai.app.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import com.gcmodai.app.data.ai.prompts.DEFAULT_TITLE_PROMPT
import com.gcmodai.app.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import com.gcmodai.app.data.model.Assistant
import com.gcmodai.app.data.model.Avatar
import com.gcmodai.app.data.model.Lorebook
import com.gcmodai.app.data.model.PromptInjection
import com.gcmodai.app.data.model.QuickMessage
import com.gcmodai.app.data.model.Tag
import com.gcmodai.app.data.sync.s3.S3Config
import com.gcmodai.app.ui.theme.CustomTheme
import com.gcmodai.app.ui.theme.PresetThemes
import com.gcmodai.search.SearchCommonOptions
import com.gcmodai.search.SearchServiceOptions
import com.gcmodai.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    /** 液态玻璃强度：0=轻薄 1=标准 2=浓稠 */
    val glassLevel: Int = 1,
    /** 液态玻璃壁纸风格，对应 WallpaperStyle 的序号 */
    val glassWallpaper: Int = 0,
    /** 壁纸是否缓慢流动（关掉更省电） */
    val glassAnimated: Boolean = true,
    /** DIY：圆角倍率 0.5~1.8，整站 Shapes 跟着变 */
    val cornerScale: Float = 1f,
    /** DIY：按钮尺寸 0=紧凑 1=标准 2=宽松 */
    val buttonSize: Int = 1,
    /** DIY：列表密度 0=紧凑 1=标准 2=舒适 */
    val listDensity: Int = 1,
    val developerMode: Boolean = false,
    /** 自动检查更新（默认开）：启动后静默检查一次，有新版本才提示 */
    val autoCheckUpdates: Boolean = true,
    /** 自动下载更新包（默认开）：检查到新版本后后台下好，用户只需点一下安装 */
    val autoDownloadUpdates: Boolean = true,
    /** 仅在 Wi-Fi 下下载更新（默认开，照顾流量用户） */
    val autoUpdateWifiOnly: Boolean = true,
    /** 自动应用热更新（默认开）：界面/配置类更新不需要装新 APK */
    val autoHotUpdate: Boolean = true,
    /**
     * 任务模式（默认开）：模型没真正干完就自动接着跑。
     *
     * 解决的实际问题有三个，全都见过：
     * 1. 上游断流，回复说到一半就停，界面直接进入完成态；
     * 2. max_tokens 撞顶被截断，长任务永远只写一半；
     * 3. 长任务里模型提前收工，人不盯着就得手动发「继续」。
     */
    val taskModeEnabled: Boolean = true,
    /** 任务模式最多自动续写几次；0 等于关闭（兼容只想留开关的人） */
    val taskModeMaxContinuations: Int = 8,
    /** 任务完成标记：模型输出里出现它就收工；留空则不按标记判断 */
    val taskModeDoneMarker: String = "[DONE]",
    /** 续写时发给模型的提示；留空则用内置文案 */
    val taskModeNudge: String = "",
    /** ffmpeg 工具的一次性迁移标记：老用户升级后自动补上该工具（之后用户自己关掉就不再补） */
    val ffmpegToolMigrated: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,

    @SerialName("serif")
    SERIF,

    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "gcmodai_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)
