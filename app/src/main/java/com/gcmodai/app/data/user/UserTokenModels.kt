package com.gcmodai.app.data.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 令牌（API 密钥）。
 *
 * 注意：服务端 `auto_groups` 与 `DeletedAt` 会返回 **null**，所以这里必须声明成可空，
 * 否则 kotlinx 反序列化会抛异常，导致整个密钥列表加载失败。
 */
@Serializable
data class ApiToken(
    val id: Long = 0L,
    val name: String = "",
    val key: String = "",
    val status: Int = 1,
    @SerialName("remain_quota") val remainQuota: Long = 0L,
    @SerialName("used_quota") val usedQuota: Long = 0L,
    @SerialName("unlimited_quota") val unlimitedQuota: Boolean = false,
    @SerialName("expired_time") val expiredTime: Long = -1L,
    @SerialName("created_time") val createdTime: Long = 0L,
    @SerialName("accessed_time") val accessedTime: Long = 0L,
    val group: String = "",
    @SerialName("model_limits_enabled") val modelLimitsEnabled: Boolean = false,
    @SerialName("model_limits") val modelLimits: String = "",
    @SerialName("allow_ips") val allowIps: String = "",
    @SerialName("auto_groups") val autoGroups: String? = null,
    @SerialName("cross_group_retry") val crossGroupRetry: Boolean = false,
) {
    val isExpired: Boolean
        get() = expiredTime in 1..(System.currentTimeMillis() / 1000)

    val isDisabled: Boolean
        get() = status != 1

    val statusLabel: String
        get() = when {
            isDisabled -> "已禁用"
            isExpired -> "已过期"
            else -> "正常"
        }
}

/** 令牌分页数据 */
@Serializable
data class TokenPage(
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 10,
    val total: Int = 0,
    val items: List<ApiToken> = emptyList(),
)

/** 可用分组：name → 描述与倍率 */
@Serializable
data class GroupInfo(
    val desc: String = "",
    val ratio: Double = 1.0,
)

/** /api/pricing 里的模型价格条目 */
@Serializable
data class PricingEntry(
    @SerialName("model_name") val modelName: String = "",
    @SerialName("quota_type") val quotaType: Int = 0,
    @SerialName("model_ratio") val modelRatio: Double = 0.0,
    @SerialName("model_price") val modelPrice: Double = 0.0,
    @SerialName("completion_ratio") val completionRatio: Double = 0.0,
    @SerialName("enable_groups") val enableGroups: List<String> = emptyList(),
)

/** 创建令牌请求体 */
@Serializable
data class CreateTokenRequest(
    val name: String,
    @SerialName("remain_quota") val remainQuota: Long = 0L,
    @SerialName("expired_time") val expiredTime: Long = -1L,
    @SerialName("unlimited_quota") val unlimitedQuota: Boolean = true,
    @SerialName("model_limits_enabled") val modelLimitsEnabled: Boolean = false,
    @SerialName("model_limits") val modelLimits: String = "",
    @SerialName("allow_ips") val allowIps: String = "",
    val group: String = "",
)

/** 充值方式（站点 PayMethods 配置） */
@Serializable
data class PayMethod(
    val name: String = "",
    val type: String = "",
    val icon: String = "",
)

/**
 * 订阅套餐。
 *
 * 站点的真实在线支付走"订阅套餐"（钱包充值也走同一套易支付网关），
 * `upgradeGroup` 就是购买后生效的分组（例如 vip）。
 */
@Serializable
data class SubscriptionPlan(
    val id: Long = 0L,
    val title: String = "",
    val subtitle: String = "",
    @SerialName("price_amount") val priceAmount: Double = 0.0,
    val currency: String = "USD",
    @SerialName("duration_unit") val durationUnit: String = "month",
    @SerialName("duration_value") val durationValue: Long = 0L,
    @SerialName("total_amount") val totalAmount: Long = 0L,
    @SerialName("upgrade_group") val upgradeGroup: String = "",
    @SerialName("downgrade_group") val downgradeGroup: String = "",
    @SerialName("allow_balance_pay") val allowBalancePay: Boolean = false,
    val enabled: Boolean = true,
    @SerialName("max_purchase_per_user") val maxPurchasePerUser: Int = 0,
) {
    /** 有效期文案，例如「1 天」「1 个月」 */
    val durationLabel: String
        get() {
            val unit = when (durationUnit) {
                "day" -> "天"
                "month" -> "个月"
                "year" -> "年"
                "hour" -> "小时"
                else -> durationUnit
            }
            return "$durationValue$unit"
        }

    /** 购买后解锁的分组描述 */
    val groupLabel: String
        get() = when (upgradeGroup) {
            "" -> "不改变分组"
            "vip" -> "VIP 分组"
            else -> upgradeGroup
        }
}

/** /api/subscription/plans 的条目包装 */
@Serializable
data class SubscriptionPlanWrapper(
    val plan: SubscriptionPlan = SubscriptionPlan(),
)

/** 易支付下单结果：把 data 里的表单字段拼成收银台 URL */
@Serializable
data class EpayOrder(
    val device: String = "pc",
    val money: String = "",
    val name: String = "",
    @SerialName("notify_url") val notifyUrl: String = "",
    @SerialName("out_trade_no") val outTradeNo: String = "",
    val pid: String = "",
    @SerialName("return_url") val returnUrl: String = "",
    val sign: String = "",
    @SerialName("sign_type") val signType: String = "MD5",
    val type: String = "",
)
