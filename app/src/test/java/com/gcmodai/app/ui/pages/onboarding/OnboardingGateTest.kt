package com.gcmodai.app.ui.pages.onboarding

import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.user.ApiUser
import com.gcmodai.app.data.user.UserSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 启动闸门的决策覆盖。
 *
 * 这段逻辑判错的两种代价都很严重，所以两侧边界都要钉死：
 * - 判成「要引导」→ 配好自己渠道的老用户升级后被卡死在引导页（引导页没有返回出口）；
 * - 判成「不要引导」→ 内置 DEFAULT_PROVIDERS 让所有人都算「已配置」，引导永远不出现。
 */
class OnboardingGateTest {

    private val officialId: Uuid = Uuid.parse("7cc0f2a1-5b34-4e8d-9a20-1f6d3b8c4e57")

    private val autoModel = Model(modelId = "auto", displayName = "自动")
    private val officialModel = Model(modelId = "deepseek-chat", displayName = "deepseek-chat")

    /** 内置渠道：enabled 且有模型，但 builtIn = true */
    private fun builtInProvider() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        enabled = true,
        name = "内置渠道",
        models = listOf(autoModel),
        apiKey = "",
        baseUrl = "https://example.com/v1",
        builtIn = true,
    )

    /** 用户自己加的渠道 */
    private fun userProvider() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        enabled = true,
        name = "我的渠道",
        models = listOf(autoModel),
        apiKey = "sk-mine",
        baseUrl = "https://mine.example.com/v1",
        builtIn = false,
    )

    private fun officialProvider(enabled: Boolean = true, models: List<Model> = listOf(officialModel)) =
        ProviderSetting.OpenAI(
            id = officialId,
            enabled = enabled,
            name = "GcmodAi 官方服务",
            models = models,
            apiKey = "sk-official",
            baseUrl = "https://gcmod.online/v1",
            builtIn = false,
        )

    private fun session(tokenId: Long = 1L, key: String = "sk-abc") = UserSession(
        accessToken = "at",
        user = ApiUser(username = "tester"),
        selectedTokenId = tokenId,
        selectedTokenKey = key,
        selectedTokenGroup = "default",
    )

    private fun decide(
        completed: Boolean = false,
        session: UserSession? = null,
        settings: Settings,
        groupConfirmed: Boolean = false,
    ) = OnboardingGate.decideShowOnboarding(
        completed = completed,
        session = session,
        settings = settings,
        officialProviderId = officialId,
        groupConfirmed = groupConfirmed,
    )

    // ---------- 出口 1：已完成 ----------

    @Test
    fun `completed onboarding never shows again`() {
        assertFalse(
            decide(
                completed = true,
                session = null,
                settings = Settings(providers = listOf(officialProvider())),
            )
        )
    }

    // ---------- 出口 2：老用户保护（本次最关键的两条） ----------

    /**
     * 内置渠道不能被当成「已配置」。
     *
     * 首启就是「内置渠道 enabled 且有模型 + chatModelId == 默认模型」的状态，
     * 如果 builtIn 渠道也算数，那么所有人都算已配置，引导永远不会出现。
     */
    @Test
    fun `built-in provider must not count as already configured`() {
        val settings = Settings(
            providers = listOf(builtInProvider()),
            chatModelId = autoModel.id,
        )
        assertTrue(
            "内置渠道 + 默认模型不能算已配置，否则引导永不出现",
            decide(session = null, settings = settings),
        )
    }

    /**
     * 老用户已配好自己渠道并选好模型 → 不进引导。
     * 缺了这条，升级后的 BYOK 用户会被卡死在引导页。
     */
    @Test
    fun `user owned provider with selected model skips onboarding`() {
        val settings = Settings(
            providers = listOf(builtInProvider(), userProvider()),
            chatModelId = autoModel.id,
        )
        assertFalse(
            "已配好自己渠道的老用户不能进引导",
            decide(session = null, settings = settings),
        )
    }

    /** 但用户加的渠道若被停用，就还算没配好，应该进引导。 */
    @Test
    fun `disabled user provider still shows onboarding`() {
        val disabled = userProvider().let {
            (it as ProviderSetting.OpenAI).copy(enabled = false)
        }
        val settings = Settings(
            providers = listOf(builtInProvider(), disabled),
            chatModelId = autoModel.id,
        )
        assertTrue(decide(session = null, settings = settings))
    }

    // ---------- 三道阻塞 ----------

    @Test
    fun `not logged in shows onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()), chatModelId = officialModel.id)
        assertTrue(decide(session = null, settings = settings))
    }

    @Test
    fun `missing token shows onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()), chatModelId = officialModel.id)
        assertTrue(decide(session = session(tokenId = 0L), settings = settings, groupConfirmed = true))
        assertTrue(decide(session = session(key = ""), settings = settings, groupConfirmed = true))
    }

    @Test
    fun `unconfirmed group shows onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()), chatModelId = officialModel.id)
        assertTrue(decide(session = session(), settings = settings, groupConfirmed = false))
    }

    @Test
    fun `missing official models shows onboarding`() {
        val settings = Settings(providers = listOf(officialProvider(enabled = false)), chatModelId = officialModel.id)
        assertTrue(decide(session = session(), settings = settings, groupConfirmed = true))
    }

    @Test
    fun `unselected chat model shows onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()), chatModelId = Uuid.random())
        assertTrue(decide(session = session(), settings = settings, groupConfirmed = true))
    }

    // ---------- 全部就绪 ----------

    @Test
    fun `everything ready does not show onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()), chatModelId = officialModel.id)
        assertFalse(decide(session = session(), settings = settings, groupConfirmed = true))
    }

    // ---------- IP 维度：每个新 IP 触发一次 ----------

    private fun decideByIp(
        currentIp: String?,
        lastTriggerIp: String = "",
        session: UserSession? = null,
        settings: Settings,
        groupConfirmed: Boolean = false,
    ) = OnboardingGate.decideShowOnboarding(
        currentIp = currentIp,
        lastTriggerIp = lastTriggerIp,
        session = session,
        settings = settings,
        officialProviderId = officialId,
        groupConfirmed = groupConfirmed,
    )

    /** 全新安装：没有任何记录，拿到 IP 就应当引导。 */
    @Test
    fun `new ip with no history triggers onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()))
        assertTrue(decideByIp(currentIp = "1.1.1.1", lastTriggerIp = "", settings = settings))
    }

    /** 同一个 IP 第二次启动：不再打扰。 */
    @Test
    fun `same ip does not trigger again`() {
        val settings = Settings(providers = listOf(officialProvider()))
        assertFalse(
            decideByIp(currentIp = "1.1.1.1", lastTriggerIp = "1.1.1.1", settings = settings)
        )
    }

    /** 换了 IP：即使上次已经走完，也要重新来一次。 */
    @Test
    fun `different ip triggers onboarding even if previously completed`() {
        val settings = Settings(
            providers = listOf(officialProvider()),
            chatModelId = officialModel.id,
        )
        assertTrue(
            "换 IP 必须重新触发，不能被旧标记永久挡住",
            decideByIp(
                currentIp = "2.2.2.2",
                lastTriggerIp = "1.1.1.1",
                session = session(),
                settings = settings,
                groupConfirmed = true,
            )
        )
    }

    /** 探测失败（离线）：不能当成「换 IP」，否则离线用户会被莫名弹引导。 */
    @Test
    fun `null ip never triggers onboarding`() {
        val settings = Settings(providers = listOf(officialProvider()))
        assertFalse(decideByIp(currentIp = null, lastTriggerIp = "1.1.1.1", settings = settings))
        assertFalse(decideByIp(currentIp = "", lastTriggerIp = "1.1.1.1", settings = settings))
    }

    /** 新 IP 也不能打断已经自给自足的老用户——否则他被扣在无返回键的引导页里出不来。 */
    @Test
    fun `new ip must not trap user who already owns a provider`() {
        val settings = Settings(
            providers = listOf(userProvider(), officialProvider()),
            chatModelId = autoModel.id,
        )
        assertFalse(
            "新 IP + 已有自有渠道 + 已选模型 → 放行，否则老用户无法退出引导页",
            decideByIp(currentIp = "9.9.9.9", lastTriggerIp = "", session = session(), settings = settings)
        )
    }
}
