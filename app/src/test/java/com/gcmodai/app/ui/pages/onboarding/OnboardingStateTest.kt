package com.gcmodai.app.ui.pages.onboarding

import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.user.GroupInfo
import com.gcmodai.app.data.user.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 引导状态机的分支覆盖。
 *
 * `evaluate` 是纯函数，所以这里不碰 Android 依赖，直接喂状态。
 * 六个分支对应设计文档 §3.2 的短路顺序。
 */
class OnboardingStateTest {

    private val models = listOf(Model(modelId = "deepseek-chat", displayName = "deepseek-chat"))

    private fun providerWithModels(enabled: Boolean = true, models: List<Model> = this.models) =
        ProviderSetting.OpenAI(
            id = OFFICIAL_ID,
            enabled = enabled,
            name = "GcmodAi 官方服务",
            models = models,
            apiKey = "sk-test",
            baseUrl = "https://gcmod.online/v1",
        )

    private fun settings(
        providers: List<ProviderSetting> = listOf(providerWithModels()),
        chatModelId: Uuid = models.first().id,
    ) = Settings(providers = providers, chatModelId = chatModelId)

    private fun session(tokenId: Long = 1L, key: String = "sk-abc") = UserSession(
        accessToken = "at",
        user = com.gcmodai.app.data.user.ApiUser(username = "tester"),
        selectedTokenId = tokenId,
        selectedTokenKey = key,
        selectedTokenGroup = "default",
    )

    private val groups = mapOf("default" to GroupInfo(), "vip" to GroupInfo())

    // ---------- 分支 1：未登录 ----------

    @Test
    fun `no session goes to login step`() {
        val state = evaluate(
            session = null,
            settings = settings(),
            groups = groups,
            groupConfirmed = true,
            officialModels = models,
        )
        assertEquals(OnboardingStep.Login, state.step)
        assertEquals(1, state.stepIndex)
    }

    // ---------- 分支 2：无密钥 ----------

    @Test
    fun `missing token goes to token step`() {
        for (s in listOf(
            session(tokenId = 0L, key = "sk-abc"),
            session(tokenId = 1L, key = ""),
            session(tokenId = 0L, key = ""),
        )) {
            val state = evaluate(
                session = s,
                settings = settings(),
                groups = groups,
                groupConfirmed = true,
                officialModels = models,
            )
            assertEquals(OnboardingStep.Token, state.step)
            assertEquals(2, state.stepIndex)
        }
    }

    // ---------- 分支 3：分组未确认 / 未拉取 ----------

    @Test
    fun `no groups goes to group step`() {
        val state = evaluate(
            session = session(),
            settings = settings(),
            groups = emptyMap(),
            groupConfirmed = true,
            officialModels = models,
        )
        assertEquals(OnboardingStep.Group, state.step)
        assertEquals(3, state.stepIndex)
    }

    /**
     * 关键：分组判定靠一次性标记，不能靠字符串非空。
     * 空串 "" 是合法的「跟随账号」取值，用字符串判定会永远卡在第 3 步。
     */
    @Test
    fun `unconfirmed group stays on group step even with empty group value`() {
        val state = evaluate(
            session = session().copy(selectedTokenGroup = ""),
            settings = settings(),
            groups = groups,
            groupConfirmed = false,
            officialModels = models,
        )
        assertEquals("空串是合法分组值，未确认标记时必须停在第 3 步", OnboardingStep.Group, state.step)
    }

    @Test
    fun `empty group value passes once confirmed`() {
        val state = evaluate(
            session = session().copy(selectedTokenGroup = ""),
            settings = settings(),
            groups = groups,
            groupConfirmed = true,
            officialModels = models,
        )
        assertTrue(
            "确认过标记后，空分组值不应再卡住",
            state.step == OnboardingStep.Model || state.step == OnboardingStep.Done,
        )
    }

    // ---------- 分支 4：官方通道无模型 ----------

    @Test
    fun `no official models goes to model step`() {
        val state = evaluate(
            session = session(),
            settings = settings(providers = listOf(providerWithModels(enabled = false))),
            groups = groups,
            groupConfirmed = true,
            officialModels = emptyList(),
        )
        assertEquals(OnboardingStep.Model, state.step)
        assertEquals(4, state.stepIndex)
    }

    @Test
    fun `official models present but none selected goes to model step`() {
        val state = evaluate(
            session = session(),
            settings = settings(chatModelId = Uuid.random()),
            groups = groups,
            groupConfirmed = true,
            officialModels = models,
        )
        assertEquals(OnboardingStep.Model, state.step)
        assertEquals(4, state.stepIndex)
    }

    // ---------- 分支 5：全部就绪 ----------

    @Test
    fun `everything ready goes to done`() {
        val state = evaluate(
            session = session(),
            settings = settings(),
            groups = groups,
            groupConfirmed = true,
            officialModels = models,
        )
        assertEquals(OnboardingStep.Done, state.step)
        assertEquals(4, state.stepIndex)
    }

    // ---------- 幂等与回退 ----------

    @Test
    fun `evaluate is idempotent`() {
        val first = evaluate(session(), settings(), groups, true, models)
        val second = evaluate(session(), settings(), groups, true, models)
        assertEquals(first, second)
    }

    /** 退出登录后必须自动回到第 1 步，不需要额外的同步逻辑。 */
    @Test
    fun `logout rolls back to login step`() {
        val done = evaluate(session(), settings(), groups, true, models)
        assertEquals(OnboardingStep.Done, done.step)
        val afterLogout = evaluate(null, settings(), groups, true, models)
        assertEquals(OnboardingStep.Login, afterLogout.step)
    }

    /** 删掉密钥后必须自动回到第 2 步。 */
    @Test
    fun `removing token rolls back to token step`() {
        val afterDelete = evaluate(session(tokenId = 0L), settings(), groups, true, models)
        assertEquals(OnboardingStep.Token, afterDelete.step)
    }

    // ---------- 步数固定为 4 ----------

    @Test
    fun `total steps is always four`() {
        for (state in listOf(
            evaluate(null, settings(), groups, true, models),
            evaluate(session(tokenId = 0L), settings(), groups, true, models),
            evaluate(session(), settings(), emptyMap(), true, models),
            evaluate(session(), settings(), groups, true, emptyList()),
            evaluate(session(), settings(), groups, true, models),
        )) {
            assertEquals(4, state.totalSteps)
            assertTrue("步号必须在 1..4", state.stepIndex in 1..4)
        }
    }

    @Test
    fun `state carries username and groups through`() {
        val state = evaluate(session(), settings(), groups, true, models)
        assertEquals("tester", state.username)
        assertEquals(groups, state.groups)
        assertFalse(state.busy)
    }

    // ---------- 登录硬闸门：必须先登录才能继续 ----------

    @Test
    fun `not logged in is locked at step one`() {
        val state = evaluate(null, Settings(), emptyMap(), false, emptyList())
        assertEquals(OnboardingStep.Login, state.step)
        assertFalse("未登录时后续步骤必须锁死", state.canProceed)
        assertFalse("未登录不能有登录态", state.loggedIn)
    }

    @Test
    fun `logged in unlocks the next steps`() {
        val state = evaluate(session(tokenId = 0L), settings(), emptyMap(), false, emptyList())
        assertTrue("登录后必须放行到下一步", state.canProceed)
        assertTrue(state.loggedIn)
    }

    /** 登出后再求值必须重新锁死，不能沿用之前的放行状态。 */
    @Test
    fun `logging out re-locks the flow`() {
        val advanced = evaluate(session(tokenId = 0L), settings(), emptyMap(), false, emptyList())
        assertTrue(advanced.canProceed)

        val relocked = evaluate(null, settings(), groups, true, models)
        assertEquals(OnboardingStep.Login, relocked.step)
        assertFalse("登出后必须重新锁死", relocked.canProceed)
    }

    /** 未登录时 payload 里的模型/分组即便有值，也不能被当成已就绪。 */
    @Test
    fun `logged out never reports readiness`() {
        val state = evaluate(null, settings(), groups, true, models)
        assertFalse(state.canProceed)
        assertEquals(1, state.stepIndex)
    }

    private companion object {
        val OFFICIAL_ID: Uuid = Uuid.parse("7cc0f2a1-5b34-4e8d-9a20-1f6d3b8c4e57")
    }
}
