package com.gcmodai.app.data.user

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用服务端真实返回的报文验证解析。
 *
 * 回归点：`auto_groups` 与 `DeletedAt` 会返回 null，早期把它们声明成非空导致
 * 密钥列表整体解析失败（表现为「无法新建密钥」「分组无法更换」）。
 */
class UserTokenModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val envelopeSerializer =
        ApiEnvelope.serializer(TokenPage.serializer())

    @Test
    fun `token list with null fields parses`() {
        val raw = """
        {"data":{"page":1,"page_size":100,"total":2,"items":[
          {"id":3295,"user_id":3576,"key":"ckgo**********5YyW","status":1,"name":"probe-b",
           "created_time":1793966298,"accessed_time":1793966298,"expired_time":-1,
           "remain_quota":100,"unlimited_quota":false,"model_limits_enabled":false,
           "model_limits":"","allow_ips":"","used_quota":0,"group":"国模0.01",
           "auto_groups":null,"cross_group_retry":false,"DeletedAt":null},
          {"id":3294,"user_id":3576,"key":"4heu**********qg1v","status":1,"name":"probe-a",
           "created_time":1793966083,"accessed_time":1793966083,"expired_time":-1,
           "remain_quota":0,"unlimited_quota":true,"model_limits_enabled":false,
           "model_limits":"","allow_ips":"","used_quota":0,"group":"",
           "auto_groups":null,"cross_group_retry":false,"DeletedAt":null}
        ]},"message":"","success":true}
        """.trimIndent()

        val envelope = json.decodeFromString(envelopeSerializer, raw)
        assertTrue(envelope.success)
        val items = requireNotNull(envelope.data).items
        assertEquals(2, items.size)
        assertEquals("国模0.01", items[0].group)
        assertTrue(items[1].unlimitedQuota)
        assertEquals("正常", items[0].statusLabel)
    }

    @Test
    fun `token create response without data parses`() {
        val raw = """{"message":"","success":true}"""
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(JsonElement.serializer()), raw
        )
        assertTrue(envelope.success)
    }

    @Test
    fun `group list parses with ratio`() {
        val raw = """
        {"data":{"default":{"desc":"用户分组","ratio":1},"vip":{"desc":"VIP分组","ratio":1},
                 "国模0.01":{"desc":"国模0.01","ratio":0.01}},
         "message":"","success":true}
        """.trimIndent()
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(
                MapSerializer(String.serializer(), GroupInfo.serializer())
            ),
            raw,
        )
        val groups = requireNotNull(envelope.data)
        assertEquals(3, groups.size)
        assertEquals(0.01, groups.getValue("国模0.01").ratio, 0.0001)
    }

    @Test
    fun `epay order builds a checkout query containing device`() {
        val raw = """
        {"data":{"device":"pc","money":"5.00","name":"TUC5",
          "notify_url":"https://gcmod.bond/api/user/epay/notify",
          "out_trade_no":"USR3579NOxDVGZR1793966687","pid":"1295",
          "return_url":"https://gcmod.online/usage-logs",
          "sign":"bb0aa4215547be5d4a9d1854ca97ceda","sign_type":"MD5","type":"alipay"},
         "message":"success","url":"https://www.pay1987.com/submit.php"}
        """.trimIndent()
        val obj = json.parseToJsonElement(raw) as JsonObject
        val order = json.decodeFromJsonElement(
            EpayOrder.serializer(), obj.getValue("data")
        )
        assertEquals("alipay", order.type)
        assertEquals("pc", order.device)
        assertEquals("1295", order.pid)
        // device 必须参与签名，缺失会被收银台判为「MD5签名校验失败」
        assertFalse(order.device.isBlank())
    }

    @Test
    fun `subscription plans parse`() {
        val raw = """
        {"data":[{"plan":{"id":5,"title":"体验","subtitle":"不限量日卡","price_amount":4,
          "currency":"USD","duration_unit":"day","duration_value":1,"custom_seconds":0,
          "enabled":true,"sort_order":0,"allow_balance_pay":false,
          "allow_wallet_overflow":true,"stripe_price_id":"","creem_product_id":"",
          "waffo_pancake_product_id":"","max_purchase_per_user":5,"upgrade_group":"",
          "downgrade_group":"","total_amount":500000000,"quota_reset_period":"never",
          "quota_reset_custom_seconds":0,"created_at":1793271181,"updated_at":1793273611}}],
         "message":"","success":true}
        """.trimIndent()
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(ListSerializer(SubscriptionPlanWrapper.serializer())), raw
        )
        val plans = requireNotNull(envelope.data).map { it.plan }
        assertEquals(1, plans.size)
        assertEquals("体验", plans[0].title)
        assertEquals(4.0, plans[0].priceAmount, 0.0001)
        assertEquals("1天", plans[0].durationLabel)
        assertEquals("不改变分组", plans[0].groupLabel)
    }

    @Test
    fun `vip plan reports its group`() {
        val plan = SubscriptionPlan(
            title = "高级套餐", priceAmount = 20.0,
            durationUnit = "month", durationValue = 1, upgradeGroup = "vip",
        )
        assertEquals("1个月", plan.durationLabel)
        assertEquals("VIP 分组", plan.groupLabel)
    }

    /**
     * 回归：钱包充值的 amount 必须是整数。
     *
     * 服务端按 int 解析，发 `10.0` / `10.5` 会直接返回 `{"data":"参数错误"}`，
     * 这正是上一版充值一直报参数错误的原因。
     */
    @Test
    fun `pay order body serializes amount as integer`() {
        val body = UserApi.buildPayOrderBody(amount = 10L, paymentMethod = "alipay")
        assertEquals("""{"amount":10,"payment_method":"alipay"}""", body)
        assertFalse(body.contains("10.0"))
        assertFalse(body.contains("."))
    }

    @Test
    fun `pay order body keeps payment_method and channel`() {
        val wx = UserApi.buildPayOrderBody(amount = 5L, paymentMethod = "wxpay")
        assertEquals("""{"amount":5,"payment_method":"wxpay"}""", wx)
        // 字段名必须是 payment_method，写成 payment_type 会被服务端拒绝
        assertTrue(wx.contains("payment_method"))
        assertFalse(wx.contains("payment_type"))
    }
}
