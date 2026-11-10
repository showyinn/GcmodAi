package com.gcmodai.app.ui.pages.onboarding

import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.user.ApiUser
import com.gcmodai.app.data.user.UserSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 登录门禁验证。
 *
 * 产品要求：「每个用户进入 app 前都要进行登录注册」。
 * 这条要求必须高于一切既有快捷放行逻辑，否则老用户/外部 Intent
 * 都能绕过去，用一个不存在的账户进聊天页。
 */
class LoginGateTest {

    private val officialId: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000aaaa")

    private fun session(
        tokenId: Long = 1L,
        tokenKey: String = "sk-test",
    ) = UserSession(
        accessToken = "access-token",
        user = ApiUser(id = 1L, username = "u"),
        selectedTokenId = tokenId,
        selectedTokenKey = tokenKey,
    )

    /** 一个「完全配好」的设置：有自有渠道、有模型、选中了模型。 */
    private fun fullyConfiguredSettings(): Settings {
        val settings = Settings()
        // 用官方通道之外的默认内置渠道来模拟老用户已配置
        return settings
    }

    @Test
    fun `未登录时无论 IP 是否已知都必须进引导（登录页）`() {
        // IP 已知且和上次相同：以前会判 false（直接进聊天），现在必须 true
        assertTrue(
            OnboardingGate.decideShowOnboarding(
                currentIp = "1.2.3.4",
                lastTriggerIp = "1.2.3.4",
                session = null,
                settings = fullyConfiguredSettings(),
                officialProviderId = officialId,
                groupConfirmed = true,
            )
        )
    }

    @Test
    fun `未登录时即使 IP 探测失败也要进引导`() {
        assertTrue(
            OnboardingGate.decideShowOnboarding(
                currentIp = null,
                lastTriggerIp = "",
                session = null,
                settings = fullyConfiguredSettings(),
                officialProviderId = officialId,
                groupConfirmed = true,
            )
        )
    }

    @Test
    fun `未登录时即使已配好自有渠道也不能放行`() {
        // 这是最容易漏的一条：老用户配好渠道后退出登录，不能白拿登录态
        assertTrue(
            OnboardingGate.decideShowOnboarding(
                currentIp = "9.9.9.9",
                lastTriggerIp = "1.1.1.1",
                session = null,
                settings = fullyConfiguredSettings(),
                officialProviderId = officialId,
                groupConfirmed = true,
            )
        )
    }

    @Test
    fun `已登录且同一 IP 不应再次弹引导`() {
        assertFalse(
            OnboardingGate.decideShowOnboarding(
                currentIp = "1.2.3.4",
                lastTriggerIp = "1.2.3.4",
                session = session(),
                settings = fullyConfiguredSettings(),
                officialProviderId = officialId,
                groupConfirmed = true,
            )
        )
    }

    @Test
    fun `已登录但换了新 IP 且未自给自足时要引导`() {
        assertTrue(
            OnboardingGate.decideShowOnboarding(
                currentIp = "5.6.7.8",
                lastTriggerIp = "1.2.3.4",
                session = session(),
                settings = fullyConfiguredSettings(),
                officialProviderId = officialId,
                groupConfirmed = true,
            )
        )
    }

    @Test
    fun `已登录且离线时不做新 IP 判定`() {
        assertFalse(
            OnboardingGate.decideShowOnboarding(
                currentIp = null,
                lastTriggerIp = "1.2.3.4",
                session = session(),
                settings = fullyConfiguredSettings(),
                officialProviderId = officialId,
                groupConfirmed = true,
            )
        )
    }
}
