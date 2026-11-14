package com.gcmodai.app.di

import kotlinx.serialization.json.Json
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.analytics.AppAnalytics
import com.gcmodai.app.data.analytics.SafeAppAnalytics
import com.gcmodai.app.data.ai.ChatRequestFactory
import com.gcmodai.app.data.ai.tools.ChatToolFactory
import com.gcmodai.app.data.ai.tools.local.LocalTools
import com.gcmodai.app.data.event.AppEventBus
import com.gcmodai.app.service.ChatFloatingTracker
import com.gcmodai.app.service.ChatNotificationManager
import com.gcmodai.app.service.ChatService
import com.gcmodai.app.service.MediaCreationService
import com.gcmodai.app.ui.floating.ChatFloatingWindows
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import com.gcmodai.app.utils.EmojiData
import com.gcmodai.app.utils.EmojiUtils
import com.gcmodai.app.utils.JsonInstant
import com.gcmodai.app.utils.SoundEffectPlayer
import com.gcmodai.app.utils.AutoUpdateScheduler
import com.gcmodai.app.utils.HotUpdateManager
import com.gcmodai.app.utils.UpdateChecker
import com.gcmodai.app.data.user.UserApi
import com.gcmodai.app.data.remote.RemoteConfigRepository
import com.gcmodai.app.data.files.FilesRepository
import com.gcmodai.app.data.workspace.WorkspaceRepository
import com.gcmodai.app.data.agent.PcAgentRepository
import com.gcmodai.app.data.agent.PcAgentStore
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.ui.pages.files.FilesVM
import com.gcmodai.app.ui.pages.workspace.MyWorkspaceVM
import com.gcmodai.app.data.user.UserAuthStore
import com.gcmodai.app.data.user.OfficialProviderManager
import com.gcmodai.app.ui.pages.onboarding.OnboardingVM
import com.gcmodai.app.ui.pages.billing.BillingVM
import com.gcmodai.app.ui.pages.checkin.CheckinVM
import com.gcmodai.app.web.WebServerManager
import com.gcmodai.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        // 用具名参数：工作区和 SSH 两个仓库类名相同、包不同，
        // 位置参数一旦传反，工具会静默消失（?.let 直接跳过，不报错）
        LocalTools(
            context = get(),
            eventBus = get(),
            ttsManager = get(),
            settingsStore = get(),
            workspaceRepository = get<com.gcmodai.app.data.workspace.WorkspaceRepository>(),
            sshRepository = get<com.gcmodai.app.data.repository.WorkspaceRepository>(),
            pcAgentRepository = get<com.gcmodai.app.data.agent.PcAgentRepository>(),
            mediaProbeClient = get(),
        )
    }

    single {
        HotUpdateManager(
            context = get(),
            client = get(),
            appScope = get(),
        )
    }

    single {
        AutoUpdateScheduler(
            context = get(),
            updateChecker = get(),
            hotUpdateManager = get(),
            appScope = get(),
        )
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
            // 传远程配置：让后台的 latest_version / update_check_enabled 影响更新检查
            remoteConfig = get(),
        )
    }

    // 用户体系：账号沿用官网体系，数据源是服务端账号库。
    // 客户端不持有数据库凭据，全部走 HTTPS 用户接口。
    single {
        UserApi(get())
    }

    // 远程配置：管理员在网页后台改的东西在这里落地（官方群、公告、功能开关）
    single {
        val hotUpdate: com.gcmodai.app.utils.HotUpdateManager = get()
        RemoteConfigRepository(
            client = get(),
            // 远端接口拿不到时，用热更新包里下发的 config.json 顶上
            hotConfigOverride = { runCatching { hotUpdate.configOverride() }.getOrNull() },
        )
    }

    // 代码产物托管：公网可访问，替代原来的手机本地 Web 服务器
    single {
        // 提前取出实例：既避免在 lambda 里做运行期 Koin 查找，
        // 又能保证用的是会自动续期 token 的方法
        val userRepository: com.gcmodai.app.data.user.UserRepository = get()
        FilesRepository(
            client = get(),
            tokenProvider = { userRepository.freshAccessToken() },
        )
    }


    // Windows 助手客户端：通过 exe 控制 Windows 电脑，不需要配 SSH
    single { PcAgentStore(get()) }
    single { PcAgentRepository(client = get(), store = get()) }

    // 服务器工作区：每人 100MB 隔离空间，AI 可直接读写并部署网站
    single {
        // 同上：走 freshAccessToken()，token 快过期会自动用 refresh cookie 续期。
        // 之前直接读 accessToken，导致登录 15 分钟后工作区一律提示「未登录」。
        val userRepository: com.gcmodai.app.data.user.UserRepository = get()
        WorkspaceRepository(
            client = get(),
            tokenProvider = { userRepository.freshAccessToken() },
        )
    }

    single {
        MyWorkspaceVM(
            repository = get(),
            scope = get(),
        )
    }

    single {
        FilesVM(
            repository = get(),
            userRepository = get(),
            scope = get(),
        )
    }

    single {
        UserAuthStore(get())
    }

    single {
        UserRepository(
            api = get(),
            store = get(),
            appScope = get(),
        )
    }

    single {
        OfficialProviderManager(
            settingsStore = get(),
            userRepository = get(),
        )
    }

    single {
        OnboardingVM(
            context = get(),
            userRepository = get(),
            settingsStore = get(),
            officialProviderManager = get(),
            scope = get(),
        )
    }

    single {
        BillingVM(
            repository = get(),
            userApi = get(),
            scope = get(),
        )
    }

    single {
        CheckinVM(
            repository = get(),
            userApi = get(),
            scope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    // 埋点走安全包装：Firebase/GMS 不可用时降级为 no-op。
    // 直接 single { Firebase.analytics } 会让容器在创建实例时就抛异常，
    // 在缺失 Google 服务的机器上表现为启动崩溃（已复现），不要改回去。
    single<AppAnalytics> {
        SafeAppAnalytics(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    // ChatService 只在后台才用到，延迟创建
    single {
        ChatFloatingTracker(
            appScope = get(),
            lazyChatService = lazy { get() },
            settingsStore = get(),
        )
    }

    // 同上，进程启动即开始监听前后台切换
    single(createdAtStart = true) {
        ChatFloatingWindows(
            context = get(),
            appScope = get(),
            tracker = get(),
            lazyChatService = lazy { get() },
        )
    }

    single {
        ChatToolFactory(
            context = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }

    single {
        ChatRequestFactory(
            context = get(),
            providerManager = get(),
            usageRecorder = get(),
            chatToolFactory = get(),
            memoryRepository = get(),
            templateTransformer = get(),
            workspaceRepository = get(),
            mediaProbeClient = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            requestFactory = get(),
            memoryConsolidationScheduler = get(),
            translationHandler = get(),
            metaGenerator = get(),
            mcpManager = get(),
            filesManager = get(),
            folderRepository = get()
        )
    }

    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
            okHttpClient = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
