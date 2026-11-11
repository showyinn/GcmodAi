package com.gcmodai.app.data.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GcmodAi 用户体系的数据模型。
 *
 * 数据源是官网的账号接口，其背后即账号服务的用户数据。
 * App 不直连数据库，凭据不下发到客户端。
 */

/** New API 通用响应外壳 */
@Serializable
data class ApiEnvelope<T>(
    val success: Boolean = false,
    val message: String = "",
    val data: T? = null,
)

/** 登录/刷新成功后返回的载荷 */
@Serializable
data class LoginPayload(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("access_expires_at") val accessExpiresAt: Long = 0L,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val user: ApiUser = ApiUser(),
)

/** 用户资料，字段与 users 表一一对应 */
@Serializable
data class ApiUser(
    val id: Long = 0L,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    val email: String = "",
    val quota: Long = 0L,
    @SerialName("used_quota") val usedQuota: Long = 0L,
    @SerialName("request_count") val requestCount: Long = 0L,
    @SerialName("group") val groupName: String = "default",
    val role: Long = 1L,
    val status: Long = 1L,
    @SerialName("aff_code") val affCode: String = "",
    @SerialName("aff_count") val affCount: Long = 0L,
    @SerialName("inviter_id") val inviterId: Long = 0L,
) {
    /** status: 1 正常, 2 封禁 */
    val isBanned: Boolean get() = status != 1L

    /** role: 1 普通用户, 10 管理员, 100 超级管理员 */
    val isAdmin: Boolean get() = role >= 10L

    /** 优先展示昵称，其次用户名 */
    val displayLabel: String get() = displayName.ifBlank { username }

    /** 剩余额度（美元）。New API 默认 500000 quota = $1 */
    val quotaUsd: Double get() = quota / 500000.0

    /** 已用额度（美元） */
    val usedUsd: Double get() = usedQuota / 500000.0

    /** 累计额度（美元） */
    val totalUsd: Double get() = (quota + usedQuota) / 500000.0

    /** 剩余额度百分比 0f..1f，用于进度展示 */
    val remainingRatio: Float
        get() {
            val total = quota + usedQuota
            if (total <= 0L) return 0f
            return (quota.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/** 本地保存的登录会话 */
@Serializable
data class UserSession(
    val accessToken: String = "",
    /** access token 过期时间，Unix 秒 */
    val expiresAt: Long = 0L,
    /** 刷新用的 cookie 值（new_api_refresh），约 30 天有效 */
    val refreshCookie: String = "",
    val user: ApiUser = ApiUser(),
    /** 用户在 App 内选中用于调用模型的密钥 id（tokens.id） */
    val selectedTokenId: Long = 0L,
    /** 选中密钥的完整明文 key（sk- 前缀不含） */
    val selectedTokenKey: String = "",
    /** 选中密钥的分组 */
    val selectedTokenGroup: String = "",
    /** 选中密钥的显示名 */
    val selectedTokenName: String = "",
) {
    /** token 是否即将过期（预留 2 分钟余量） */
    fun isAccessTokenStale(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        accessToken.isBlank() || expiresAt <= 0L || nowSeconds >= expiresAt - 120L

    fun withToken(payload: LoginPayload): UserSession = copy(
        accessToken = payload.accessToken.ifBlank { accessToken },
        expiresAt = if (payload.accessExpiresAt > 0L) payload.accessExpiresAt else expiresAt,
        user = if (payload.user.id > 0L) payload.user else user,
    )
}

/** 网络层返回：登录/刷新同时要带回刷新 cookie */
data class AuthResult(
    val payload: LoginPayload,
    val refreshCookie: String,
)

/** 登录态 */
sealed interface UserAuthState {
    data object Loading : UserAuthState
    data object LoggedOut : UserAuthState
    data class LoggedIn(val session: UserSession) : UserAuthState
}

/** 用户接口异常，message 可直接展示给用户 */
class UserApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 额度展示。New API 以 500000 quota = $1 计价，这里统一按美元展示，
 * 与账号页保持一致，避免各处用不同汇率算出不同的数字。
 */
fun formatQuotaUsd(usd: Double): String =
    String.format(java.util.Locale.US, "$%,.2f", usd)
