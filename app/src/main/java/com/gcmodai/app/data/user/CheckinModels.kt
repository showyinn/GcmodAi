package com.gcmodai.app.data.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 签到相关模型。
 *
 * 关键设计：**签到状态完全以服务端为准**。
 * 数据库 `checkins` 表上有 `UNIQUE KEY (user_id, checkin_date)`，
 * 所以「网站签过了 app 还能签」这种事在数据库层面就不可能发生——
 * 第二次插入会被唯一约束直接拒绝，服务端返回 `今日已签到`。
 *
 * 因此 App 端**绝不能**自己用本地日期做判断，否则会出现设备时区差异导致的
 * 「本地以为没签、点了报错」或「本地以为签过、其实能签」两种错误。
 * 一切状态都问 `GET /api/user/checkin`。
 */
@Serializable
data class CheckinStats(
    @SerialName("checked_in_today") val checkedInToday: Boolean = false,
    @SerialName("checkin_count") val checkinCount: Int = 0,
    val records: List<CheckinRecord> = emptyList(),
    @SerialName("total_checkins") val totalCheckins: Int = 0,
    @SerialName("total_quota") val totalQuota: Long = 0L,
) {
    val totalQuotaUsd: Double get() = totalQuota / 500000.0
}

@Serializable
data class CheckinRecord(
    @SerialName("checkin_date") val checkinDate: String = "",
    @SerialName("quota_awarded") val quotaAwarded: Long = 0L,
) {
    val quotaUsd: Double get() = quotaAwarded / 500000.0
}

/** `GET /api/user/checkin` 的 data 部分。 */
@Serializable
data class CheckinStatus(
    val enabled: Boolean = false,
    @SerialName("max_quota") val maxQuota: Long = 100000L,
    @SerialName("min_quota") val minQuota: Long = 10000L,
    val stats: CheckinStats = CheckinStats(),
) {
    val maxQuotaUsd: Double get() = maxQuota / 500000.0
    val minQuotaUsd: Double get() = minQuota / 500000.0
    val canCheckin: Boolean get() = enabled && !stats.checkedInToday
}

/**
 * 签到结果。
 *
 * 服务端对「已签到」返回的是 `success=false` + `message="今日已签到"`，
 * 这**不是异常**，是正常业务分支，所以要单独建模而不是抛异常，
 * 否则 UI 会把「今天已经签过了」显示成红色错误，体验很差。
 */
sealed class CheckinResult {
    /** 签到成功，[quotaAwarded] 是本次获得的额度。 */
    data class Success(
        val checkinDate: String,
        val quotaAwarded: Long,
    ) : CheckinResult() {
        val quotaUsd: Double get() = quotaAwarded / 500000.0
    }

    /** 今天已经签过了（网站或 app 任一处签的）。 */
    data object AlreadyCheckedIn : CheckinResult()

    /** 签到功能被管理员关闭。 */
    data object Disabled : CheckinResult()

    /** 其他失败，带上服务端原文便于排查。 */
    data class Failed(val message: String) : CheckinResult()
}
