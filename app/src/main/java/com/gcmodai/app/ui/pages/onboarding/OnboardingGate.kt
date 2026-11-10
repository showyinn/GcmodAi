package com.gcmodai.app.ui.pages.onboarding

import android.content.Context
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.findModelById
import com.gcmodai.app.data.user.OfficialProviderManager
import com.gcmodai.app.data.user.UserSession
import kotlin.uuid.Uuid

/**
 * 冷启动时判定要不要进新手引导。
 *
 * 使用位置：RouteActivity 计算 `startScreen` 的那一处。
 *
 * ## 触发模型（产品要求）
 *
 * 「换了一个新 IP，就触发一次引导任务」——按 **IP 维度**一次性触发：
 * - 同一个出口 IP 只引导一次，之后正常进聊天页；
 * - 换了 IP 就再引导一次（即使上次已经走完）；
 * - 拿不到 IP（离线 / 探测失败）时**不**触发，避免离线用户莫名被弹引导。
 *
 * 因此 [decideShowOnboarding] 的第一优先级是 IP 判定，
 * 而旧的 `onboarding_completed` 标记降级为「本 IP 已经引导过」的冗余备份，
 * 不再作为永久性放行条件——否则用户永远不可能在换 IP 后再次看到引导。
 *
 * ## 为什么还要保留「老用户放行」
 *
 * 引导页没有返回出口（顶栏无返回键，`popBackStack` 又要求栈深 > 1）。
 * 如果一个已经配好自己渠道的老用户在换网时被弹引导，他会**无法退出**。
 * 所以即便 IP 是新的，只要认定他已是「自给自足的用户」，仍然直接放行到聊天页。
 * 这条保护与 IP 判定互不冲突：新用户不会被它误伤，老用户不会被它扣死。
 */
object OnboardingGate {

    /**
     * 是否需要展示引导页。
     *
     * @param session 当前登录会话，null 表示未登录
     * @param settings 当前设置快照
     * @param officialProviderId 官方通道的固定 id
     * @param currentIp 当前出口 IP，null 表示探测失败/离线
     * @param groupConfirmed 用户是否确认过分组（一次性标记），默认现读
     */
    fun shouldShowOnboarding(
        context: Context,
        session: UserSession?,
        settings: Settings,
        officialProviderId: Uuid,
        currentIp: String?,
        groupConfirmed: Boolean = OnboardingPrefs.isGroupConfirmed(context),
    ): Boolean = decideShowOnboarding(
        currentIp = currentIp,
        lastTriggerIp = OnboardingIpGate.lastTriggerIp(context),
        session = session,
        settings = settings,
        officialProviderId = officialProviderId,
        groupConfirmed = groupConfirmed,
    )

    /** 同上，直接传 [OfficialProviderManager] 省得调用方拼 id。 */
    fun shouldShowOnboarding(
        context: Context,
        session: UserSession?,
        settings: Settings,
        officialProviderManager: OfficialProviderManager,
        currentIp: String?,
    ): Boolean = shouldShowOnboarding(
        context = context,
        session = session,
        settings = settings,
        officialProviderId = officialProviderManager.officialProviderId,
        currentIp = currentIp,
    )

    /**
     * 纯判定：给定全部输入，是否该进引导页。无 IO、无副作用、可反复调用。
     *
     * 短路顺序：
     * 1. 拿不到 IP → 不引导（离线不是「换 IP」）
     * 2. 这个 IP 已经触发过 → 不引导（每个 IP 一次）
     * 3. 老用户已经配好自己渠道且选好模型 → 不引导（否则会被扣死在无返回键的引导页）
     * 4. 其余情况一律引导
     *
     * 第 3 条的 `hasUserOwnedProvider` 要求 `!builtIn` **且**不是官方通道：
     * - 内置 `DEFAULT_PROVIDERS` 本身就 enabled 且有模型，算进来会让所有人「已配置」；
     * - 官方通道也传了 `builtIn = false`（见 OfficialProviderManager），必须按 id 排除，
     *   否则只配了官方通道但尚未登录的用户会被误放行。
     */
    internal fun decideShowOnboarding(
        currentIp: String?,
        lastTriggerIp: String,
        session: UserSession?,
        settings: Settings,
        officialProviderId: Uuid,
        groupConfirmed: Boolean,
    ): Boolean {
        // 0. 未登录一律进引导（第一屏就是登录/注册）。
        //    产品要求「每个用户进入 app 前都要进行登录注册」，
        //    这条必须排在最前面，否则「老用户放行」会让未登录用户绕过去，
        //    直接进聊天页用一个不存在的账户。
        if (session == null) return true

        // 1. 探测不到 IP：不把它当成「新 IP」
        if (currentIp.isNullOrBlank()) return false

        // 2. 这个 IP 已经引导过了
        if (currentIp == lastTriggerIp) return false

        // 3. 已配好自有渠道并选好模型的老用户，不打断
        val selectedModel = settings.findModelById(settings.chatModelId)
        val hasUserOwnedProvider = settings.providers.any {
            !it.builtIn && it.id != officialProviderId && it.enabled && it.models.isNotEmpty()
        }
        if (selectedModel != null && hasUserOwnedProvider) return false

        // 4. 新 IP + 尚未自给自足 → 引导
        return true
    }

    /**
     * 兼容旧签名的判定（不带 IP），供单元测试与「纯本地状态」场景使用。
     *
     * 语义为：**不考虑 IP**，只按会话/密钥/分组/模型的完成度判断。
     * 线上路径一律走带 `currentIp` 的重载。
     */
    internal fun decideShowOnboarding(
        completed: Boolean,
        session: UserSession?,
        settings: Settings,
        officialProviderId: Uuid,
        groupConfirmed: Boolean,
    ): Boolean {
        // 1. 已经明确走完引导
        if (completed) return false

        // 2. 老用户已经配好自己渠道
        val selectedModel = settings.findModelById(settings.chatModelId)
        val hasUserOwnedProvider = settings.providers.any {
            !it.builtIn && it.id != officialProviderId && it.enabled && it.models.isNotEmpty()
        }
        if (selectedModel != null && hasUserOwnedProvider) return false

        // 3. 未登录也要引导（第一步就是登录）
        if (session == null) return true

        // 4. 没有可用密钥
        if (session.selectedTokenId == 0L || session.selectedTokenKey.isBlank()) return true

        // 5. 分组还没确认
        if (!groupConfirmed) return true

        // 6. 官方通道没有可用模型
        val official = settings.providers.firstOrNull { it.id == officialProviderId }
        if (official == null || !official.enabled || official.models.isEmpty()) return true

        // 7. 还没选聊天模型
        if (selectedModel == null) return true

        return false
    }
}
