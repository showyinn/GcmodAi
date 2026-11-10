package com.gcmodai.app.data.user

import com.gcmodai.common.http.await
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GcmodAi 用户接口客户端。
 *
 * 直连 gcmod.online 的 New API 用户接口（HTTPS），账号体系与官网完全一致：
 * 用户在官网注册的账号可直接登录 App，不需要另建一套账号。
 *
 * 接口要点（已实测）：
 * - POST /api/user/login           {username,password} → access_token(JWT, 约15分钟) + new_api_refresh cookie(约30天) + 用户资料
 * - POST /api/user/auth/refresh    Cookie: new_api_refresh=... → 新的 access_token
 * - GET  /api/user/self            Authorization: Bearer <token> → 用户资料
 * - 服务端有 Origin 白名单校验，所有请求都必须带 Origin/Referer，否则 403 AUTH_ORIGIN_FORBIDDEN
 */
class UserApi(private val client: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        // 服务端会把 auto_groups / DeletedAt 等字段返回成 null，开启后按默认值处理，不再抛异常
        coerceInputValues = true
        explicitNulls = false
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun login(username: String, password: String): AuthResult {
        val body = buildString {
            append("{\"username\":")
            append(Json.encodeToString(String.serializer(), username))
            append(",\"password\":")
            append(Json.encodeToString(String.serializer(), password))
            append("}")
        }
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/login")
                .post(body.toRequestBody(jsonMediaType))
                .build()
        ).await()

        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "登录请求失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(ApiEnvelope.serializer(LoginPayload.serializer()), text)
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "用户名或密码错误" })
        }
        return AuthResult(
            payload = envelope.data,
            refreshCookie = extractRefreshCookie(response.headers.values("Set-Cookie")),
        )
    }

    /** 用刷新 cookie 换新的 access token */
    suspend fun refresh(refreshCookie: String): AuthResult {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/auth/refresh")
                .post("{}".toRequestBody(jsonMediaType))
                .addHeader("Cookie", "new_api_refresh=$refreshCookie")
                .build()
        ).await()

        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "登录状态已失效，请重新登录")
        }
        val envelope = json.decodeFromString(ApiEnvelope.serializer(LoginPayload.serializer()), text)
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "登录状态已失效，请重新登录" })
        }
        val newCookie = extractRefreshCookie(response.headers.values("Set-Cookie"))
        return AuthResult(
            payload = envelope.data,
            refreshCookie = newCookie.ifBlank { refreshCookie },
        )
    }

    /**
     * 发送邮箱验证码。
     *
     * 发信由服务端完成——SMTP 主机/端口/账号/授权码都配置在 new-api 库的 options 表里
     * （SMTPServer=smtp.qq.com、SMTPPort=465、SMTPSSLEnabled=true、EmailDomainWhitelist=qq.com），
     * 客户端不持有任何 SMTP 凭据。
     */
    suspend fun sendVerificationCode(email: String) {
        val encoded = java.net.URLEncoder.encode(email, "UTF-8")
        val response = client.newCall(
            baseRequest("$BASE_URL/api/verification?email=$encoded").get().build()
        ).await()

        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "验证码发送失败（HTTP ${response.code}）")
        }
        // 该接口即使失败也用 200 + success=false 返回，必须看 success
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.json.JsonElement.serializer()), text)
        }.getOrElse { throw UserApiException("服务器响应格式异常，请稍后重试", it) }
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "验证码发送失败" })
        }
    }

    /**
     * 注册账号。服务端要求邮箱验证码（EmailVerificationEnabled），
     * 且邮箱域名受白名单限制（EmailDomainWhitelist=qq.com），即只允许 QQ 邮箱。
     */
    suspend fun register(
        username: String,
        password: String,
        email: String,
        verificationCode: String,
        affCode: String = "",
    ) {
        val payload = buildString {
            append("{\"username\":")
            append(Json.encodeToString(String.serializer(), username))
            append(",\"password\":")
            append(Json.encodeToString(String.serializer(), password))
            append(",\"email\":")
            append(Json.encodeToString(String.serializer(), email))
            append(",\"verification_code\":")
            append(Json.encodeToString(String.serializer(), verificationCode))
            if (affCode.isNotBlank()) {
                append(",\"aff_code\":")
                append(Json.encodeToString(String.serializer(), affCode))
            }
            append("}")
        }
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/register")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
        ).await()

        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "注册失败（HTTP ${response.code}）")
        }
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.json.JsonElement.serializer()), text)
        }.getOrElse { throw UserApiException("服务器响应格式异常，请稍后重试", it) }
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "注册失败" })
        }
    }

    /** 拉取当前用户资料 */
    suspend fun self(accessToken: String): ApiUser {        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/self")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()

        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取用户信息失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(ApiEnvelope.serializer(ApiUser.serializer()), text)
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "获取用户信息失败" })
        }
        return envelope.data
    }

    private fun baseRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Origin", ORIGIN)
        .addHeader("Referer", "$ORIGIN/")
        .addHeader("Accept", "application/json")
        .addHeader("User-Agent", USER_AGENT)

    // ==================== 签到 ====================

    /**
     * 查询签到状态。
     *
     * 一切以服务端为准：数据库有 `UNIQUE(user_id, checkin_date)` 唯一约束，
     * 网站和 App 共用同一张表，所以这里返回的 `checkedInToday` 是唯一真相。
     * 本地不做任何日期推断，避免时区差异导致误判。
     */
    suspend fun getCheckinStatus(accessToken: String): CheckinStatus {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/checkin")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取签到状态失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(CheckinStatus.serializer()), text
        )
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "获取签到状态失败" })
        }
        return envelope.data
    }

    /**
     * 执行签到。
     *
     * 注意：服务端对「今日已签到」返回 HTTP 200 但 `success=false`，
     * 这是正常业务分支，要映射成 [CheckinResult.AlreadyCheckedIn] 而不是抛异常。
     * 若把它当异常抛出，UI 会把「今天已经签过了」渲染成红色错误提示。
     */
    suspend fun checkin(accessToken: String): CheckinResult {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/checkin")
                .post("{}".toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()

        if (!response.isSuccessful) {
            val msg = parseErrorMessage(text) ?: "签到失败（HTTP ${response.code}）"
            return if (msg.contains("已签到")) CheckinResult.AlreadyCheckedIn
            else CheckinResult.Failed(msg)
        }

        // 成功分支：{"data":{"checkin_date":"...","quota_awarded":44763},"success":true}
        // 已签到分支：{"message":"今日已签到","success":false}
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return CheckinResult.Failed("服务器响应格式异常")

        val success = (root["success"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (!success) {
            return when {
                message.contains("已签到") -> CheckinResult.AlreadyCheckedIn
                message.contains("关闭") || message.contains("未开启") -> CheckinResult.Disabled
                else -> CheckinResult.Failed(message.ifBlank { "签到失败" })
            }
        }

        val data = root["data"] as? JsonObject
            ?: return CheckinResult.Failed("签到成功但未返回数据")
        val date = (data["checkin_date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val awarded = (data["quota_awarded"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        return CheckinResult.Success(checkinDate = date, quotaAwarded = awarded)
    }

    // ==================== 账单明细（计费日志）====================

    /**
     * 拉取计费日志。
     *
     * 网关 `/api/log/self` 把充值、消费、登录等全记在同一张表，靠 `type` 区分，
     * 所以这里不按 type 过滤，交给 UI 的汇总逻辑筛 `type == 2` 的消费记录。
     * 默认按时间倒序，最新的在最上面。
     */
    suspend fun listBillingLogs(
        accessToken: String,
        page: Int = 1,
        pageSize: Int = 20,
        type: Long? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        modelName: String? = null,
    ): BillingLogPage {
        val query = buildString {
            append("$BASE_URL/api/log/self?p=").append(page)
            append("&page_size=").append(pageSize)
            // type=0 在 new-api 里表示"全部"，显式传 0 比省略更稳
            append("&type=").append(type ?: 0L)
            if (startTimestamp != null) append("&start_timestamp=").append(startTimestamp)
            if (endTimestamp != null) append("&end_timestamp=").append(endTimestamp)
            if (!modelName.isNullOrBlank()) {
                append("&model_name=").append(
                    java.net.URLEncoder.encode(modelName, "UTF-8")
                )
            }
        }
        val response = client.newCall(
            baseRequest(query)
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取账单明细失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(BillingLogPage.serializer()), text
        )
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "获取账单明细失败" })
        }
        return envelope.data
    }

    // ==================== 令牌（API 密钥）====================
    /** 令牌列表（key 是打码的） */
    suspend fun listTokens(accessToken: String, page: Int = 1, size: Int = 100): TokenPage {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/token/?p=$page&size=$size")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取密钥列表失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(ApiEnvelope.serializer(TokenPage.serializer()), text)
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "获取密钥列表失败" })
        }
        return envelope.data
    }

    /** 创建令牌。成功后用 [fetchTokenKeys] 取完整 key。 */
    suspend fun createToken(accessToken: String, request: CreateTokenRequest) {
        val body = json.encodeToString(CreateTokenRequest.serializer(), request)
        val response = client.newCall(
            baseRequest("$BASE_URL/api/token/")
                .post(body.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "创建密钥失败（HTTP ${response.code}）")
        }
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.json.JsonElement.serializer()), text)
        }.getOrElse { throw UserApiException("服务器响应格式异常，请稍后重试", it) }
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "创建密钥失败" })
        }
    }

    /** 删除令牌 */
    suspend fun deleteToken(accessToken: String, tokenId: Long) {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/token/$tokenId")
                .delete()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "删除密钥失败（HTTP ${response.code}）")
        }
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.json.JsonElement.serializer()), text)
        }.getOrElse { throw UserApiException("服务器响应格式异常，请稍后重试", it) }
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "删除密钥失败" })
        }
    }

    /**
     * 修改令牌（换分组 / 改名 / 改额度）。
     *
     * 必须 PUT **完整对象**：只传 id+group 会把 name 清空（已实测），
     * 所以这里把列表里的原始字段一起回传，只覆盖要改的部分。
     */
    suspend fun updateToken(accessToken: String, token: ApiToken, newGroup: String, newName: String = token.name) {
        val body = buildString {
            append("{\"id\":").append(token.id).append(',')
            append("\"name\":").append(Json.encodeToString(String.serializer(), newName)).append(',')
            append("\"remain_quota\":").append(token.remainQuota).append(',')
            append("\"expired_time\":").append(token.expiredTime).append(',')
            append("\"unlimited_quota\":").append(token.unlimitedQuota).append(',')
            append("\"model_limits_enabled\":").append(token.modelLimitsEnabled).append(',')
            append("\"model_limits\":").append(Json.encodeToString(String.serializer(), token.modelLimits)).append(',')
            append("\"allow_ips\":").append(Json.encodeToString(String.serializer(), token.allowIps)).append(',')
            append("\"group\":").append(Json.encodeToString(String.serializer(), newGroup))
            append('}')
        }
        val response = client.newCall(
            baseRequest("$BASE_URL/api/token/")
                .put(body.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "修改密钥失败（HTTP ${response.code}）")
        }
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.json.JsonElement.serializer()), text)
        }.getOrElse { throw UserApiException("服务器响应格式异常，请稍后重试", it) }
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "修改密钥失败" })
        }
    }


    /**
     * 取完整明文 key（官网前端同款接口）。
     * 返回 tokenId → 完整 key。
     */
    suspend fun fetchTokenKeys(accessToken: String, tokenIds: List<Long>): Map<Long, String> {
        val idsJson = tokenIds.joinToString(",") { it.toString() }
        val body = "{\"ids\":[$idsJson]}"
        val response = client.newCall(
            baseRequest("$BASE_URL/api/token/batch/keys")
                .post(body.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取密钥失败（HTTP ${response.code}）")
        }
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw UserApiException("服务器响应格式异常，请稍后重试")
        if ((obj["success"] as? JsonPrimitive)?.contentOrNull == "false") {
            val msg = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: "获取密钥失败"
            throw UserApiException(msg)
        }
        val data = obj["data"] as? JsonObject
        val keys = data?.get("keys") as? JsonObject
            ?: throw UserApiException("获取密钥失败：响应缺少 keys 字段")
        return keys.entries.associate { (k, v) ->
            (k.toLongOrNull() ?: -1L) to ((v as? JsonPrimitive)?.contentOrNull ?: "")
        }
    }

    // ==================== 分组 / 模型 / 价格 ====================

    /** 当前用户可选的分组：name → desc/ratio */
    suspend fun availableGroups(accessToken: String): Map<String, GroupInfo> {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/self/groups")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取分组失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(MapSerializer(String.serializer(), GroupInfo.serializer())),
            text,
        )
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "获取分组失败" })
        }
        return envelope.data
    }

    /** 当前用户可用的模型 id 列表 */
    suspend fun userModels(accessToken: String): List<String> {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/models")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取模型列表失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(ListSerializer(String.serializer())),
            text,
        )
        if (!envelope.success || envelope.data == null) {
            throw UserApiException(envelope.message.ifBlank { "获取模型列表失败" })
        }
        return envelope.data
    }

    /** 全站模型价格（含倍率与可用分组） */
    suspend fun pricing(accessToken: String): List<PricingEntry> {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/pricing")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) return emptyList()
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(ListSerializer(PricingEntry.serializer())), text)
        }.getOrNull() ?: return emptyList()
        return envelope.data.orEmpty()
    }

    // ==================== 充值 ====================

    /** 兑换码充值 */
    suspend fun redeemCode(accessToken: String, code: String): String {
        val body = "{\"key\":" + Json.encodeToString(String.serializer(), code) + "}"
        val response = client.newCall(
            baseRequest("$BASE_URL/api/user/topup")
                .post(body.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "兑换失败（HTTP ${response.code}）")
        }
        val envelope = runCatching {
            json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.json.JsonElement.serializer()), text)
        }.getOrElse { throw UserApiException("服务器响应格式异常，请稍后重试", it) }
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "兑换失败，请检查兑换码" })
        }
        return envelope.message
    }

    /**
     * 创建钱包充值订单，返回可直接打开的收银台 URL。
     *
     * 两个已实测的坑：
     * 1. 字段名必须是 **payment_method**（不是 payment_type），取值只有 `alipay` / `wxpay`；
     * 2. amount 必须是**整数**——服务端按 int 解析，发 `10.0` / `10.5` / `"10"` 都会得到
     *    `{"data":"参数错误","message":"error"}`，所以这里只接受整元金额。
     */
    suspend fun createPayOrder(accessToken: String, amount: Long, paymentMethod: String): String {
        return requestEpayCheckout(
            "$BASE_URL/api/user/pay",
            accessToken,
            buildPayOrderBody(amount, paymentMethod),
            "创建充值订单失败",
        )
    }

    /** 站点可用的在线支付渠道（本部署实测为 支付宝 alipay / 微信 wxpay） */
    fun supportedPayMethods(): List<PayMethod> = listOf(
        PayMethod(name = "支付宝", type = "alipay"),
        PayMethod(name = "微信", type = "wxpay"),
    )

    /** 订阅套餐列表（购买后按 plan.upgradeGroup 解锁分组） */
    suspend fun subscriptionPlans(accessToken: String): List<SubscriptionPlan> {
        val response = client.newCall(
            baseRequest("$BASE_URL/api/subscription/plans")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "获取套餐失败（HTTP ${response.code}）")
        }
        val envelope = json.decodeFromString(
            ApiEnvelope.serializer(ListSerializer(SubscriptionPlanWrapper.serializer())),
            text,
        )
        if (!envelope.success) {
            throw UserApiException(envelope.message.ifBlank { "获取套餐失败" })
        }
        return envelope.data.orEmpty().map { it.plan }.filter { it.enabled }
    }

    /** 购买订阅套餐，返回收银台 URL */
    suspend fun purchasePlan(accessToken: String, planId: Long, paymentMethod: String): String {
        val body = buildString {
            append("{\"plan_id\":")
            append(planId.toString())
            append(",\"payment_method\":")
            append(Json.encodeToString(String.serializer(), paymentMethod))
            append("}")
        }
        return requestEpayCheckout(
            "$BASE_URL/api/subscription/epay/pay", accessToken, body, "创建套餐订单失败"
        )
    }

    /**
     * 易支付统一下单：服务端返回 `{data:{...表单字段}, url:"https://.../submit.php"}`，
     * 这里把表单字段拼成查询串，拼出可被浏览器直接打开的收银台地址。
     */
    private suspend fun requestEpayCheckout(
        url: String,
        accessToken: String,
        body: String,
        errorPrefix: String,
    ): String {
        val response = client.newCall(
            baseRequest(url)
                .post(body.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw UserApiException(parseErrorMessage(text) ?: "$errorPrefix（HTTP ${response.code}）")
        }
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw UserApiException("服务器响应格式异常，请稍后重试")

        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val dataElement = obj["data"]
        // 失败时 data 是错误文案字符串（如「支付方式不存在」）
        if (dataElement is JsonPrimitive) {
            val hint = dataElement.contentOrNull.orEmpty()
            throw UserApiException(hint.ifBlank { message.ifBlank { errorPrefix } })
        }
        val order = runCatching {
            json.decodeFromJsonElement(EpayOrder.serializer(), dataElement!!)
        }.getOrElse { throw UserApiException(message.ifBlank { errorPrefix }, it) }

        val submitUrl = (obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (submitUrl.isBlank()) throw UserApiException(errorPrefix)
        if (order.outTradeNo.isBlank() || order.sign.isBlank()) {
            throw UserApiException(message.ifBlank { errorPrefix })
        }

        val query = listOf(
            "pid" to order.pid,
            "type" to order.type,
            "out_trade_no" to order.outTradeNo,
            "notify_url" to order.notifyUrl,
            "return_url" to order.returnUrl,
            "name" to order.name,
            "money" to order.money,
            // device 也参与签名：漏掉它收银台会直接报「MD5签名校验失败」（已实测）
            "device" to order.device.ifBlank { "pc" },
            "sign" to order.sign,
            "sign_type" to order.signType,
        ).joinToString("&") { (k, v) -> "$k=" + urlEncode(v) }
        return "$submitUrl?$query"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")



    /** 从 Set-Cookie 头里取出 new_api_refresh 的值 */
    private fun extractRefreshCookie(setCookies: List<String>): String {
        return setCookies.firstOrNull { it.startsWith("$REFRESH_COOKIE_NAME=") }
            ?.substringAfter("$REFRESH_COOKIE_NAME=")
            ?.substringBefore(";")
            ?.trim()
            .orEmpty()
    }

    /** New API 的错误体有两种形态：{message:...} 或 {error:{message:...}} */
    private fun parseErrorMessage(text: String): String? {
        if (text.isBlank()) return null
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        val direct = (obj["message"] as? JsonPrimitive)?.contentOrNull
        if (!direct.isNullOrBlank()) return direct
        val nested = obj["error"] as? JsonObject
        return (nested?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val BASE_URL = "https://gcmod.online"
        const val ORIGIN = BASE_URL
        const val REFRESH_COOKIE_NAME = "new_api_refresh"
        const val USER_AGENT = "GcmodAi-App"

        /**
         * 拼钱包充值请求体。
         *
         * `amount` 必须序列化成整数：服务端按 int 解析，写成 `10.0` 会直接返回「参数错误」。
         * 单独抽出来是为了让单元测试能盯住这个格式。
         */
        internal fun buildPayOrderBody(amount: Long, paymentMethod: String): String =
            "{\"amount\":$amount,\"payment_method\":" +
                Json.encodeToString(String.serializer(), paymentMethod) + "}"
    }
}
