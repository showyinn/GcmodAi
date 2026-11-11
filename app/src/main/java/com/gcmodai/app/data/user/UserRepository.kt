package com.gcmodai.app.data.user

import com.gcmodai.app.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 用户登录与资料的业务入口。
 *
 * 设计取舍：
 * - access token 只有约 15 分钟，过期前用 refresh cookie 自动续期，用户无感；
 * - 资料每次进入用户页都会重新拉取，保证额度/分组是服务端的最新值；
 * - 登录失败直接把服务端 message 抛给 UI 展示（中文文案由服务端返回）。
 */
class UserRepository(
    private val api: UserApi,
    private val store: UserAuthStore,
    appScope: AppScope,
) {
    /** 当前会话，null 表示未登录 */
    val sessionFlow: StateFlow<UserSession?> = store.sessionFlow
        .stateIn(appScope, SharingStarted.Eagerly, null)

    val authState: StateFlow<UserAuthState> = store.sessionFlow
        .map { session -> if (session == null) UserAuthState.LoggedOut else UserAuthState.LoggedIn(session) }
        .stateIn(appScope, SharingStarted.Eagerly, UserAuthState.Loading)

    /** 登录中/刷新中标记，避免重复提交 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun login(username: String, password: String): Result<ApiUser> {
        val name = username.trim()
        if (name.isBlank()) return Result.failure(UserApiException("请输入用户名"))
        if (password.isEmpty()) return Result.failure(UserApiException("请输入密码"))
        if (_busy.value) return Result.failure(UserApiException("正在登录，请稍候"))
        _busy.value = true
        return try {
            val result = api.login(name, password)
            val session = UserSession(
                accessToken = result.payload.accessToken,
                expiresAt = result.payload.accessExpiresAt,
                refreshCookie = result.refreshCookie,
                user = result.payload.user,
            )
            store.save(session)
            Result.success(result.payload.user)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    suspend fun logout() {
        runCatching { store.clear() }
    }

    /**
     * 发送邮箱验证码。服务端按统一配置发信，只允许 QQ 邮箱。
     */
    suspend fun sendVerificationCode(email: String): Result<Unit> {
        val mail = email.trim()
        if (!isAllowedEmail(mail)) {
            return Result.failure(UserApiException("目前仅支持 QQ 邮箱注册（@qq.com）"))
        }
        return try {
            api.sendVerificationCode(mail)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 注册并自动登录。
     */
    suspend fun register(
        username: String,
        password: String,
        email: String,
        verificationCode: String,
        affCode: String = "",
    ): Result<ApiUser> {
        val name = username.trim()
        val mail = email.trim()
        val code = verificationCode.trim()
        if (name.isBlank()) return Result.failure(UserApiException("请输入用户名"))
        if (name.length < 3) return Result.failure(UserApiException("用户名至少 3 个字符"))
        if (password.length < 8) return Result.failure(UserApiException("密码至少 8 位"))
        if (!isAllowedEmail(mail)) return Result.failure(UserApiException("目前仅支持 QQ 邮箱注册（@qq.com）"))
        if (code.isBlank()) return Result.failure(UserApiException("请填写邮箱验证码"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            api.register(name, password, mail, code, affCode.trim())
            // 注册成功后直接登录，省掉一次手动输入
            val result = api.login(name, password)
            store.save(
                UserSession(
                    accessToken = result.payload.accessToken,
                    expiresAt = result.payload.accessExpiresAt,
                    refreshCookie = result.refreshCookie,
                    user = result.payload.user,
                )
            )
            Result.success(result.payload.user)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** 邮箱是否在白名单内（服务端 EmailDomainWhitelist=qq.com，这里先做本地校验，少打一次网络） */
    fun isAllowedEmail(email: String): Boolean {
        val lower = email.trim().lowercase()
        return Regex("^[a-z0-9][a-z0-9._-]*@qq\\.com$").matches(lower)
    }

    /** 拉取最新资料；token 过期会先自动刷新 */
    suspend fun refreshProfile(): Result<ApiUser> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (_busy.value) return Result.failure(UserApiException("正在刷新，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            val user = api.self(fresh.accessToken)
            store.save(fresh.copy(user = user))
            Result.success(user)
        } catch (e: Exception) {
            // 刷新失败且是鉴权问题：清掉本地会话，让用户重新登录
            if (e is UserApiException && isAuthFailure(e.message)) {
                runCatching { store.clear() }
            }
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /**
     * 取一个**当前可用**的 access token，必要时自动续期。
     *
     * 为什么必须有这个方法：
     * access token 只有约 15 分钟有效期，而工作区、代码托管这类请求如果
     * 直接读 `sessionFlow.value.accessToken`，登录过一会儿就会拿到过期 token，
     * 服务端一律返回 401，界面表现为「明明登录了却提示未登录」。
     *
     * 返回 null 表示确实没有会话（未登录），调用方据此给"请先登录"的提示。
     */
    suspend fun freshAccessToken(): String? {
        val session = sessionFlow.value ?: return null
        return try {
            ensureFreshToken(session).accessToken.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 续期失败：如果是鉴权问题就清会话，让界面回到未登录态，
            // 而不是反复用一个必然失败的 token
            if (e is UserApiException && isAuthFailure(e.message)) {
                runCatching { store.clear() }
                null
            } else {
                // 网络抖动等临时问题：沿用旧 token 再试一次，别让用户被迫重登
                session.accessToken.takeIf { it.isNotBlank() }
            }
        }
    }

    /** token 快过期时用 refresh cookie 续期 */
    private suspend fun ensureFreshToken(session: UserSession): UserSession {
        if (!session.isAccessTokenStale()) return session
        if (session.refreshCookie.isBlank()) throw UserApiException("登录状态已失效，请重新登录")
        val refreshed = api.refresh(session.refreshCookie)
        val updated = session.copy(
            accessToken = refreshed.payload.accessToken,
            expiresAt = refreshed.payload.accessExpiresAt,
            refreshCookie = refreshed.refreshCookie.ifBlank { session.refreshCookie },
            user = if (refreshed.payload.user.id > 0L) refreshed.payload.user else session.user,
        )
        store.save(updated)
        return updated
    }

    private fun isAuthFailure(message: String?): Boolean {
        val text = message.orEmpty()
        return text.contains("登录", ignoreCase = true) ||
            text.contains("Unauthorized", ignoreCase = true) ||
            text.contains("invalid access token", ignoreCase = true) ||
            text.contains("失效", ignoreCase = true)
    }

    // ==================== 令牌（API 密钥）/ 分组 / 模型 / 充值 ====================

    private val _tokens = MutableStateFlow<List<ApiToken>>(emptyList())
    /** 密钥列表（key 打码；完整 key 只在选中和创建时取回） */
    val tokens: StateFlow<List<ApiToken>> = _tokens.asStateFlow()

    private val _groups = MutableStateFlow<Map<String, GroupInfo>>(emptyMap())
    /** 可选分组：name → 描述/倍率 */
    val groups: StateFlow<Map<String, GroupInfo>> = _groups.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    /** 官网可用模型 id */
    val models: StateFlow<List<String>> = _models.asStateFlow()

    /** 拉取密钥列表并同步分组/模型 */
    suspend fun refreshTokens(): Result<List<ApiToken>> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (_busy.value) return Result.failure(UserApiException("正在同步，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            // 分组和模型各自独立加载：任一接口失败都不该拖垮密钥列表
            runCatching { _groups.value = api.availableGroups(fresh.accessToken) }
            runCatching { _models.value = api.userModels(fresh.accessToken) }
            val page = api.listTokens(fresh.accessToken)
            _tokens.value = page.items
            Result.success(page.items)
        } catch (e: Exception) {
            if (e is UserApiException && isAuthFailure(e.message)) {
                runCatching { store.clear() }
            }
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** 只刷新分组（新建密钥弹窗用，失败不影响其它功能） */
    suspend fun refreshGroups(): Map<String, GroupInfo> {
        val session = sessionFlow.value ?: return _groups.value
        return try {
            val fresh = ensureFreshToken(session)
            api.availableGroups(fresh.accessToken).also { _groups.value = it }
        } catch (e: Exception) {
            _groups.value
        }
    }

    /** 更换某个密钥的分组（PUT 完整对象，避免把 name 清空） */
    suspend fun changeTokenGroup(token: ApiToken, newGroup: String): Result<Unit> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            api.updateToken(fresh.accessToken, token, newGroup)
            val page = api.listTokens(fresh.accessToken)
            _tokens.value = page.items
            // 改的正好是当前选中的密钥，同步更新本地记录的分组
            if (fresh.selectedTokenId == token.id) {
                store.save(fresh.copy(selectedTokenGroup = newGroup))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** 订阅套餐列表 */
    suspend fun subscriptionPlans(): Result<List<SubscriptionPlan>> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        return try {
            val fresh = ensureFreshToken(session)
            Result.success(api.subscriptionPlans(fresh.accessToken))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 购买订阅套餐（真实在线支付） */
    suspend fun purchasePlan(planId: Long, paymentMethod: String): Result<String> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            Result.success(api.purchasePlan(fresh.accessToken, planId, paymentMethod))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** 创建密钥；创建成功后刷新列表并自动选中它 */
    suspend fun createToken(name: String, group: String, unlimitedQuota: Boolean, remainQuota: Long): Result<ApiToken?> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (name.isBlank()) return Result.failure(UserApiException("请输入密钥名称"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            api.createToken(
                fresh.accessToken,
                CreateTokenRequest(
                    name = name,
                    group = group,
                    unlimitedQuota = unlimitedQuota,
                    remainQuota = if (unlimitedQuota) 0L else remainQuota,
                ),
            )
            val page = api.listTokens(fresh.accessToken)
            _tokens.value = page.items
            val created = page.items
                .filter { it.name == name }
                .maxByOrNull { it.id }
            Result.success(created)
        } catch (e: Exception) {
            if (e is UserApiException && isAuthFailure(e.message)) {
                runCatching { store.clear() }
            }
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** 删除密钥；如果删的是当前选中的，清除选中状态 */
    suspend fun deleteToken(tokenId: Long): Result<Unit> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            api.deleteToken(fresh.accessToken, tokenId)
            if (fresh.selectedTokenId == tokenId) {
                store.save(
                    fresh.copy(selectedTokenId = 0L, selectedTokenKey = "", selectedTokenGroup = "", selectedTokenName = "")
                )
            }
            _tokens.value = _tokens.value.filterNot { it.id == tokenId }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is UserApiException && isAuthFailure(e.message)) {
                runCatching { store.clear() }
            }
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /**
     * 选中某个密钥用于模型调用：取回完整明文 key 并持久化。
     * 之后 [applyOfficialProvider] 会把它写入官方供应商配置，模型调用即真实扣费。
     */
    suspend fun selectToken(tokenId: Long): Result<UserSession> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            val keys = api.fetchTokenKeys(fresh.accessToken, listOf(tokenId))
            val fullKey = keys[tokenId]
            if (fullKey.isNullOrBlank()) throw UserApiException("获取密钥内容失败")
            val token = _tokens.value.firstOrNull { it.id == tokenId }
            val updated = fresh.copy(
                selectedTokenId = tokenId,
                selectedTokenKey = fullKey,
                selectedTokenGroup = token?.group ?: fresh.selectedTokenGroup,
                selectedTokenName = token?.name ?: "",
            )
            store.save(updated)
            Result.success(updated)
        } catch (e: Exception) {
            if (e is UserApiException && isAuthFailure(e.message)) {
                runCatching { store.clear() }
            }
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** 当前选中的完整密钥（sk- 前缀），未选中返回 null */
    fun selectedApiKey(session: UserSession?): String? {
        if (session?.selectedTokenKey.isNullOrBlank()) return null
        return "sk-" + session!!.selectedTokenKey
    }

    /** 兑换码充值 */
    suspend fun redeemCode(code: String): Result<String> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (code.isBlank()) return Result.failure(UserApiException("请输入兑换码"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            Result.success(api.redeemCode(fresh.accessToken, code.trim()))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /**
     * 在线充值：创建订单并返回收银台 URL。
     *
     * 字段名是 payment_method（不是 payment_type），取值 alipay / wxpay；
     * 金额必须是整数，服务端发浮点会回「参数错误」。
     */
    suspend fun createPayOrder(amount: Long, paymentMethod: String): Result<String> {
        val session = sessionFlow.value ?: return Result.failure(UserApiException("尚未登录"))
        if (amount < 1) return Result.failure(UserApiException("充值金额至少为 1"))
        if (_busy.value) return Result.failure(UserApiException("正在提交，请稍候"))
        _busy.value = true
        return try {
            val fresh = ensureFreshToken(session)
            Result.success(api.createPayOrder(fresh.accessToken, amount, paymentMethod))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }
}
