package com.gcmodai.app.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import com.gcmodai.ai.core.ReasoningLevel
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.asr.ASRProviderSetting
import com.gcmodai.mediagen.provider.MediaGenerationProviderSetting
import com.gcmodai.app.data.ai.mcp.McpServerConfig
import com.gcmodai.app.data.model.Assistant
import com.gcmodai.app.data.model.Lorebook
import com.gcmodai.app.data.model.PromptInjection
import com.gcmodai.app.data.model.QuickMessage
import com.gcmodai.app.data.model.Tag
import com.gcmodai.app.data.sync.s3.S3Config
import com.gcmodai.app.ui.theme.CustomTheme
import com.gcmodai.search.SearchCommonOptions
import com.gcmodai.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsFieldsTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every settings property has exactly one field`() {
        val properties = Settings.serializer().descriptor.elementNames.sorted()

        assertEquals(properties, SETTINGS_FIELDS.map { it.property.name }.sorted())
    }

    @Test
    fun `settings survive a round trip through preferences`() {
        // 样本里和默认值相同的字段测不出读写是否对得上
        val defaults = emptyPreferences().toSettings()
        assertEquals(
            emptyList<String>(),
            SETTINGS_FIELDS.filterNot { it.changed(defaults, SAMPLE) }.map { it.property.name },
        )

        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }

        assertEquals(SAMPLE, preferences.toSettings())
    }

    // 这里的 key 名和取值就是用户盘上的格式，改动它们等于丢掉已有的设置
    @Test
    fun `stored format stays compatible with existing data`() {
        val stored = mutablePreferencesOf().apply { putSettings(SAMPLE) }.asMap().mapKeys { it.key.name }

        assertEquals(STORED_KEYS, stored.keys)
        assertEquals(false, stored["dynamic_color"])
        assertEquals(9090, stored["web_server_port"])
        assertEquals(1.5f, stored["default_tts_playback_speed"])
        assertEquals("title", stored["title_prompt"])
        assertEquals("00000000-0000-0000-0000-000000000001", stored["chat_model"])
        assertEquals("LOW", stored["fast_model_reasoning_level"])
        assertEquals("""[{"id":"00000000-0000-0000-0000-000000000007","name":"tag"}]""", stored["assistant_tags"])
        assertEquals("""{"resultSize":3}""", stored["search_common"])
    }

    @Test
    fun `missing keys read as first launch values`() {
        val settings = emptyPreferences().toSettings()

        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.chatModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.fastModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.translateModeId)
        assertEquals(DEFAULT_MODE_INJECTIONS, settings.modeInjections)
        assertEquals(DEFAULT_ASSISTANT_ID, settings.assistantId)
        assertEquals(DEFAULT_SYSTEM_TTS_ID, settings.selectedTTSProviderId)
        assertEquals(null, settings.selectedASRProviderId)
        assertEquals(null, settings.memoryModelId)
        assertEquals(true, settings.dynamicColor)
        assertEquals(8080, settings.webServerPort)
    }

    @Test
    fun `unknown reasoning level reads as auto`() {
        val preferences = mutablePreferencesOf(SettingsStore.FAST_MODEL_REASONING_LEVEL to "REMOVED_LEVEL")

        assertEquals(ReasoningLevel.AUTO, preferences.toSettings().fastModelReasoningLevel)
    }

    @Test
    fun `clearing the asr provider removes its key`() {
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }

        preferences.putSettings(SAMPLE.copy(selectedASRProviderId = null), persisted = SAMPLE)

        assertFalse(SettingsStore.SELECTED_ASR_PROVIDER in preferences)
        assertEquals(null, preferences.toSettings().selectedASRProviderId)
    }

    @Test
    fun `only changed fields are written`() {
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }
        // 盘上的值故意和已落盘的那份不一致：没变的字段如果也被重写，它就会变回去
        preferences[SettingsStore.TITLE_PROMPT] = "on disk"

        preferences.putSettings(SAMPLE.copy(themeId = "changed"), persisted = SAMPLE)

        assertEquals("changed", preferences[SettingsStore.THEME_ID])
        assertEquals("on disk", preferences[SettingsStore.TITLE_PROMPT])
    }
}

private fun id(n: Int) = Uuid.parse("00000000-0000-0000-0000-%012d".format(n))

// 每个字段都取了和默认值不同的值
private val SAMPLE = Settings(
    dynamicColor = false,
    glassLevel = 2,
    glassWallpaper = 3,
    glassAnimated = false,
    cornerScale = 1.3f,
    buttonSize = 0,
    listDensity = 2,
    themeId = "custom",
    customThemes = listOf(CustomTheme(id = "theme", name = "Theme")),
    developerMode = true,
    ffmpegToolMigrated = true,
    // 自动更新开关：默认全开，样本里取反以便验证读写
    autoCheckUpdates = false,
    autoDownloadUpdates = false,
    autoUpdateWifiOnly = false,
    autoHotUpdate = false,
    // 任务模式：默认全开，样本里取反以便验证读写
    taskModeEnabled = false,
    taskModeStrict = true,
    taskModeMaxContinuations = 12,
    taskModeDoneMarker = "@@DONE@@",
    taskModeNudge = "go on",
    displaySetting = DisplaySetting(userNickname = "me"),
    networkSetting = NetworkSetting(userAgent = "agent"),
    favoriteModels = listOf(id(30)),
    chatModelId = id(1),
    fastModelId = id(2),
    fastModelReasoningLevel = ReasoningLevel.LOW,
    memoryModelId = id(6),
    memoryModelReasoningLevel = ReasoningLevel.HIGH,
    imageGenerationModelId = id(3),
    enableAutoTitle = false,
    titlePrompt = "title",
    translateModeId = id(4),
    translatePrompt = "translate",
    translateThinkingBudget = 1024,
    translateTargetLanguage = "ja",
    enableSuggestion = false,
    suggestionPrompt = "suggestion",
    ocrModelId = id(5),
    ocrPrompt = "ocr",
    assistantId = id(20),
    providers = listOf(ProviderSetting.OpenAI(id = id(31), name = "Provider")),
    assistants = listOf(Assistant(id = id(20), name = "Assistant")),
    assistantTags = listOf(Tag(id = id(7), name = "tag")),
    searchServices = listOf(
        SearchServiceOptions.BingLocalOptions(id = id(21)),
        SearchServiceOptions.BingLocalOptions(id = id(22)),
    ),
    searchCommonOptions = SearchCommonOptions(resultSize = 3),
    searchServiceSelected = 1,
    mcpServers = listOf(McpServerConfig.SseTransportServer(id = id(8), url = "https://example.com/sse")),
    webDavConfig = WebDavConfig(url = "https://dav.example.com"),
    s3Config = S3Config(bucket = "backup"),
    uploadS3Config = S3Config(bucket = "upload"),
    mediaGenerationProviders = listOf(MediaGenerationProviderSetting.OpenAI(id = id(9))),
    ttsProviders = DEFAULT_TTS_PROVIDERS.take(1),
    selectedTTSProviderId = id(10),
    defaultTTSPlaybackSpeed = 1.5f,
    asrProviders = listOf(ASRProviderSetting.OpenAIRealtime(id = id(11))),
    selectedASRProviderId = id(11),
    modeInjections = listOf(PromptInjection.ModeInjection(id = id(14), name = "mode")),
    lorebooks = listOf(Lorebook(id = id(12), name = "lorebook")),
    quickMessages = listOf(QuickMessage(id = id(13), title = "hi")),
    webServerEnabled = true,
    webServerPort = 9090,
    webServerJwtEnabled = true,
    webServerAccessPassword = "secret",
    webServerLocalhostOnly = true,
    backupReminderConfig = BackupReminderConfig(enabled = true),
    sponsorAlertDismissedAt = 7,
)

private val STORED_KEYS = setOf(
    "dynamic_color",
    "theme_id",
    "custom_themes",
    "glass_level",
    "glass_wallpaper",
    "glass_animated",
    "ui_corner_scale",
    "ui_button_size",
    "ui_list_density",
    "display_setting",
    "network_setting",
    "developer_mode",
    "ffmpeg_tool_migrated",
    "auto_check_updates",
    "auto_download_updates",
    "auto_update_wifi_only",
    "auto_hot_update",
    "task_mode_enabled",
    "task_mode_strict",
    "task_mode_max_continuations",
    "task_mode_done_marker",
    "task_mode_nudge",
    "favorite_models",
    "chat_model",
    "fast_model",
    "fast_model_reasoning_level",
    "memory_model",
    "memory_model_reasoning_level",
    "translate_model",
    "enable_suggestion",
    "enable_auto_title",
    "image_generation_model",
    "title_prompt",
    "translation_prompt",
    "translate_thinking_budget",
    "translate_target_language",
    "suggestion_prompt",
    "ocr_model",
    "ocr_prompt",
    "providers",
    "select_assistant",
    "assistants",
    "assistant_tags",
    "search_services",
    "search_common",
    "search_selected",
    "mcp_servers",
    "webdav_config",
    "s3_config",
    "upload_s3_config",
    "media_generation_providers",
    "tts_providers",
    "selected_tts_provider",
    "default_tts_playback_speed",
    "asr_providers",
    "selected_asr_provider",
    "web_server_enabled",
    "web_server_port",
    "web_server_jwt_enabled",
    "web_server_access_password",
    "web_server_localhost_only",
    "mode_injections",
    "lorebooks",
    "quick_messages",
    "backup_reminder_config",
    "sponsor_alert_dismissed_at",
)
