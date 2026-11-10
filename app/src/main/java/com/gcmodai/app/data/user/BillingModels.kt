package com.gcmodai.app.data.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 计费日志（账单明细）。
 *
 * 数据源是网关的 `/api/log/self`，new-api 的通用日志表。它把三种事情记在同一张表里，
 * 用 [type] 区分，所以账单页必须按 type 分类展示，否则「登录成功」会和「模型扣费」
 * 混在一起，用户会以为登录也花钱：
 *
 *  - type = 1 充值
 *  - type = 2 消费（真正的模型调用扣费，我们最关心的）
 *  - type = 3 管理
 *  - type = 4 系统
 *  - type = 5 测试
 *  - type = 7 登录（quota 恒为 0，绝不能计入支出）
 */
@Serializable
data class BillingLog(
    val id: Long = 0L,
    @SerialName("user_id") val userId: Long = 0L,
    @SerialName("created_at") val createdAt: Long = 0L,
    val type: Long = 0L,
    val content: String = "",
    val username: String = "",
    @SerialName("token_name") val tokenName: String = "",
    @SerialName("model_name") val modelName: String = "",
    val quota: Long = 0L,
    @SerialName("prompt_tokens") val promptTokens: Long = 0L,
    @SerialName("completion_tokens") val completionTokens: Long = 0L,
    @SerialName("use_time") val useTime: Long = 0L,
    @SerialName("is_stream") val isStream: Boolean = false,
    val channel: Long = 0L,
    @SerialName("channel_name") val channelName: String = "",
    @SerialName("token_id") val tokenId: Long = 0L,
    val group: String = "",
    val ip: String = "",
    @SerialName("request_id") val requestId: String = "",
    val other: String = "",
) {
    /** New API 默认 500000 quota = 1 美元，与 [ApiUser.quotaUsd] 保持同一口径。 */
    val quotaUsd: Double get() = quota / 500000.0

    /** 扣费金额（人民币展示用，1 美元按 7.2 折算，仅用于让用户有直观感受）。 */
    val quotaCny: Double get() = quotaUsd * 7.2

    val totalTokens: Long get() = promptTokens + completionTokens

    /** 是否是一次真实的模型调用扣费。 */
    val isCharge: Boolean get() = type == TYPE_CONSUME

    val typeLabel: String
        get() = when (type) {
            TYPE_TOPUP -> "充值"
            TYPE_CONSUME -> "消费"
            TYPE_MANAGE -> "管理"
            TYPE_SYSTEM -> "系统"
            TYPE_TEST -> "测试"
            TYPE_LOGIN -> "登录"
            else -> "其他"
        }

    /** 秒级时间戳 → "MM-dd HH:mm:ss"。 */
    val timeLabel: String
        get() = if (createdAt <= 0L) "-" else formatTime(createdAt)

    val dateLabel: String
        get() = if (createdAt <= 0L) "-" else formatDate(createdAt)

    /** 从 other 字段里挖出耗时等补充信息，挖不到就返回空串。 */
    val detailExtra: String
        get() {
            if (other.isBlank()) return ""
            val sb = StringBuilder()
            if (useTime > 0) sb.append("耗时 ${(useTime / 1000.0).let { String.format(Locale.US, "%.2f", it) }}s")
            if (isStream) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append("流式")
            }
            if (channelName.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append("渠道 $channelName")
            }
            return sb.toString()
        }

    companion object {
        const val TYPE_TOPUP = 1L
        const val TYPE_CONSUME = 2L
        const val TYPE_MANAGE = 3L
        const val TYPE_SYSTEM = 4L
        const val TYPE_TEST = 5L
        const val TYPE_LOGIN = 7L

        private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        internal fun formatTime(epochSeconds: Long): String =
            timeFmt.format(Date(epochSeconds * 1000))

        internal fun formatDate(epochSeconds: Long): String =
            dateFmt.format(Date(epochSeconds * 1000))
    }
}

/** `/api/log/self` 的分页外壳。 */
@Serializable
data class BillingLogPage(
    val items: List<BillingLog> = emptyList(),
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    val total: Long = 0L,
) {
    val hasMore: Boolean get() = page * pageSize < total
}

/**
 * 账单汇总：把一堆日志归拢成用户能一眼看懂的结论。
 *
 * 为什么在客户端算而不是要求后端提供：日志接口只给原始条目，
 * 用户最常问的「今天花了多少」「哪个模型最烧钱」必须自己聚合。
 */
data class BillingSummary(
    val totalRequests: Long = 0L,
    val totalSpentQuota: Long = 0L,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val byModel: List<ModelSpend> = emptyList(),
    val todaySpentQuota: Long = 0L,
    val todayRequests: Long = 0L,
    val todayPromptTokens: Long = 0L,
    val todayCompletionTokens: Long = 0L,
) {
    val totalTokens: Long get() = totalPromptTokens + totalCompletionTokens
    val totalSpentUsd: Double get() = totalSpentQuota / 500000.0
    val todaySpentUsd: Double get() = todaySpentQuota / 500000.0

    companion object {
        /** 从日志列表聚合出汇总。[nowSeconds] 用于判定「今天」，便于测试注入。 */
        fun from(
            logs: List<BillingLog>,
            nowSeconds: Long = System.currentTimeMillis() / 1000,
        ): BillingSummary {
            val charges = logs.filter { it.isCharge }
            val todayStart = nowSeconds - (nowSeconds % 86400L)
            val today = charges.filter { it.createdAt >= todayStart }

            val grouped = charges
                .filter { it.modelName.isNotBlank() }
                .groupBy { it.modelName }
                .map { (model, list) ->
                    ModelSpend(
                        model = model,
                        requests = list.size.toLong(),
                        spentQuota = list.sumOf { it.quota },
                        promptTokens = list.sumOf { it.promptTokens },
                        completionTokens = list.sumOf { it.completionTokens },
                    )
                }
                .sortedByDescending { it.spentQuota }

            return BillingSummary(
                totalRequests = charges.size.toLong(),
                totalSpentQuota = charges.sumOf { it.quota },
                totalPromptTokens = charges.sumOf { it.promptTokens },
                totalCompletionTokens = charges.sumOf { it.completionTokens },
                byModel = grouped,
                todaySpentQuota = today.sumOf { it.quota },
                todayRequests = today.size.toLong(),
                todayPromptTokens = today.sumOf { it.promptTokens },
                todayCompletionTokens = today.sumOf { it.completionTokens },
            )
        }
    }
}

/** 单个模型的消耗。 */
data class ModelSpend(
    val model: String,
    val requests: Long,
    val spentQuota: Long,
    val promptTokens: Long,
    val completionTokens: Long,
) {
    val spentUsd: Double get() = spentQuota / 500000.0
    val totalTokens: Long get() = promptTokens + completionTokens
}
