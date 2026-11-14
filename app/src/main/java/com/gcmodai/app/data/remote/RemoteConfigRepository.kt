package com.gcmodai.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 远程 App 配置。
 *
 * 管理员在网页后台改的东西（官方群、公告、功能开关、版本号…）在这里落地。
 *
 * 设计原则：
 * - **永不阻塞启动**：拉取失败就用内置默认值，App 照常能用；
 * - **短超时**：3 秒拿不到就放弃，用户不该为了一次配置拉取多等；
 * - **公开接口**：不需要登录也能读，所以登录页就能显示官方群。
 */
data class RemoteConfig(
    val appName: String = "GcmodAi",
    val appSlogan: String = "你大白话说我直接干",
    val officialGroupName: String = "GcmodAi 官方群",
    val officialGroupDesc: String = "点击加入，获取最新模型与使用技巧",
    val officialGroupNumber: String = "",
    val officialGroupUrl: String = "",
    val officialGroupQr: String = "",
    val announcement: String = "",
    val announcementTitle: String = "公告",
    val announcementEnabled: Boolean = false,
    val latestVersion: String = "",
    val downloadUrl: String = "https://gcmod.bond/",
    val forceUpdate: Boolean = false,
    val updateCheckEnabled: Boolean = true,
    val supportContact: String = "",
    val supportEmail: String = "",
    // App 全局设置
    val welcomeMessage: String = "",
    val startupTip: String = "",
    val aboutText: String = "",
    // 链接
    val docUrl: String = "",
    val termsUrl: String = "",
    val privacyUrl: String = "",
    val registerUrl: String = "",
    // 计费口径
    val quotaPerUsd: Long = 500000L,
    val checkinMinHint: String = "",
    val checkinMaxHint: String = "",
    val registerBonusHint: String = "",
    // 代码托管限额（展示口径）
    val filesQuotaMb: Int = 100,
    val filesRetentionHours: Int = 24,
    val filesMaxFileMb: Int = 32,
    /** 功能开关：管理员可以随时关掉某个入口 */
    val checkinEnabled: Boolean = true,
    val billingEnabled: Boolean = true,
    val filesHostingEnabled: Boolean = true,
    val toolsEnabled: Boolean = true,
    val onboardingEnabled: Boolean = true,
    val registerEnabled: Boolean = true,
    /** 是否成功从服务端拿到过配置（false 表示当前是内置默认值） */
    val loaded: Boolean = false,
) {
    /** 官方群是否有可用的加入方式。 */
    val hasOfficialGroup: Boolean
        get() = officialGroupUrl.isNotBlank() ||
                officialGroupNumber.isNotBlank() ||
                officialGroupQr.isNotBlank()
}

class RemoteConfigRepository(
    private val client: OkHttpClient,
    /**
     * 热更新包里的配置覆盖（HotUpdateManager.configOverride）。
     *
     * 为什么需要这一层：远端接口偶发不可用（改版、迁移、被墙）时，
     * 用户手机里上一次热更新下来的 config.json 还能顶上，
     * 至少不会退回到「群号空了、公告没了」的出厂默认值。
     */
    private val hotConfigOverride: (() -> Map<String, String>?)? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val _config = MutableStateFlow(RemoteConfig())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()

    // 配置读的是本站 nginx 反代，正常几十毫秒；3 秒足够，超时就不等了
    private val fastClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取配置。失败时静默保留当前值（首次即内置默认值）。
     *
     * 不抛异常：配置拉取失败绝不该让 App 启动失败。
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/config/public")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val result = runCatching {
            fastClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body.string()
                if (text.isBlank()) return@use null
                val root = json.parseToJsonElement(text) as? JsonObject ?: return@use null
                val success = (root["success"] as? JsonPrimitive)?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: false
                if (!success) return@use null
                root["data"] as? JsonObject
            }
        }.getOrNull()

        val data = result
        if (data == null) {
            // 远端拿不到：退到热更新包里的 config.json（键名与 /api/config/public 一致）
            val hot = runCatching { hotConfigOverride?.invoke() }.getOrNull()
            if (!hot.isNullOrEmpty()) {
                val obj = JsonObject(hot.mapValues { JsonPrimitive(it.value) })
                _config.value = _config.value.mergeFrom(obj).copy(loaded = false)
                return@withContext true
            }
            return@withContext false
        }
        _config.value = _config.value.mergeFrom(data).copy(loaded = true)
        true
    }

    /** 把服务端返回的字符串键值对合并进当前配置。缺失的键保留旧值。 */
    internal fun RemoteConfig.mergeFrom(data: JsonObject): RemoteConfig {
        fun str(key: String, fallback: String): String =
            (data[key] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: fallback

        fun boolean(key: String, fallback: Boolean): Boolean =
            (data[key] as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull()
                ?: fallback

        fun int(key: String, fallback: Int): Int =
            (data[key] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.toIntOrNull()
                ?: fallback

        fun long(key: String, fallback: Long): Long =
            (data[key] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.toLongOrNull()
                ?: fallback

        // 允许管理员把某些文本项清空（比如取消二维码），所以这两项不用 isNotBlank 过滤
        fun rawStr(key: String, fallback: String): String =
            (data[key] as? JsonPrimitive)?.contentOrNull ?: fallback

        return copy(
            appName = str("app_name", appName),
            appSlogan = rawStr("app_slogan", appSlogan),
            officialGroupName = str("official_group_name", officialGroupName),
            officialGroupDesc = rawStr("official_group_desc", officialGroupDesc),
            officialGroupNumber = rawStr("official_group_number", officialGroupNumber),
            officialGroupUrl = rawStr("official_group_url", officialGroupUrl),
            officialGroupQr = rawStr("official_group_qr", officialGroupQr),
            announcement = rawStr("announcement", announcement),
            announcementTitle = str("announcement_title", announcementTitle),
            announcementEnabled = boolean("announcement_enabled", announcementEnabled),
            latestVersion = str("latest_version", latestVersion),
            downloadUrl = str("download_url", downloadUrl),
            forceUpdate = boolean("force_update", forceUpdate),
            updateCheckEnabled = boolean("update_check_enabled", updateCheckEnabled),
            supportContact = rawStr("support_contact", supportContact),
            supportEmail = rawStr("support_email", supportEmail),
            welcomeMessage = rawStr("welcome_message", welcomeMessage),
            startupTip = rawStr("startup_tip", startupTip),
            aboutText = rawStr("about_text", aboutText),
            docUrl = rawStr("doc_url", docUrl),
            termsUrl = rawStr("terms_url", termsUrl),
            privacyUrl = rawStr("privacy_url", privacyUrl),
            registerUrl = rawStr("register_url", registerUrl),
            quotaPerUsd = long("quota_per_usd", quotaPerUsd),
            checkinMinHint = rawStr("checkin_min_hint", checkinMinHint),
            checkinMaxHint = rawStr("checkin_max_hint", checkinMaxHint),
            registerBonusHint = rawStr("register_bonus_hint", registerBonusHint),
            filesQuotaMb = int("files_quota_mb", filesQuotaMb),
            filesRetentionHours = int("files_retention_hours", filesRetentionHours),
            filesMaxFileMb = int("files_max_file_mb", filesMaxFileMb),
            checkinEnabled = boolean("checkin_enabled_hint", checkinEnabled),
            billingEnabled = boolean("billing_enabled", billingEnabled),
            filesHostingEnabled = boolean("files_hosting_enabled", filesHostingEnabled),
            toolsEnabled = boolean("tools_enabled", toolsEnabled),
            onboardingEnabled = boolean("onboarding_enabled", onboardingEnabled),
            registerEnabled = boolean("register_enabled", registerEnabled),
        )
    }

    companion object {
        /** 配置服务部署在下载站同一台机器上，走它的 nginx。 */
        const val BASE_URL = "https://gcmod.bond"
        private const val USER_AGENT = "GcmodAi/RemoteConfig"
    }
}
