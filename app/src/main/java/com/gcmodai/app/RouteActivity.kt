package com.gcmodai.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okio.Path.Companion.toOkioPath
import com.gcmodai.ui.toast.Toaster
import com.gcmodai.ui.toast.rememberToasterState
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.gcmodai.app.data.datastore.SettingsStore
import com.gcmodai.app.data.db.DatabaseMigrationTracker
import com.gcmodai.app.data.db.MigrationState
import com.gcmodai.app.data.event.AppEvent
import com.gcmodai.app.data.event.AppEventBus
import com.gcmodai.app.data.repository.MediaCreationRepository
import com.gcmodai.app.service.MediaCreationForegroundService
import com.gcmodai.app.ui.activity.SafeModeActivity
import com.gcmodai.app.ui.components.ui.TTSController
import com.gcmodai.app.ui.context.LocalASRState
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.context.LocalSettings
import com.gcmodai.app.ui.context.LocalSharedTransitionScope
import com.gcmodai.app.ui.context.LocalTTSState
import com.gcmodai.app.ui.context.LocalToaster
import com.gcmodai.app.ui.context.Navigator
import com.gcmodai.app.ui.hooks.readBooleanPreference
import com.gcmodai.app.ui.hooks.readStringPreference
import com.gcmodai.app.ui.hooks.rememberCustomAsrState
import com.gcmodai.app.ui.hooks.rememberCustomTtsState
import com.gcmodai.app.ui.pages.assistant.AssistantPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantBasicPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantDetailPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantExtensionsPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantLocalToolPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantMcpPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantMemoryPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantPromptPage
import com.gcmodai.app.ui.pages.assistant.detail.AssistantRequestPage
import com.gcmodai.app.ui.pages.backup.BackupPage
import com.gcmodai.app.ui.pages.chat.ChatPage
import com.gcmodai.app.ui.pages.debug.DebugPage
import com.gcmodai.app.ui.pages.extensions.ExtensionsPage
import com.gcmodai.app.ui.pages.extensions.PromptPage
import com.gcmodai.app.ui.pages.extensions.QuickMessagesPage
import com.gcmodai.app.ui.pages.extensions.skills.SkillDetailPage
import com.gcmodai.app.ui.pages.memory.MemoryFilePage
import com.gcmodai.app.ui.pages.extensions.skills.SkillsPage
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspacePage
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceDetailPage
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceSshEditorPage
import com.gcmodai.app.ui.pages.extensions.workspace.WorkspaceTerminalPage
import com.gcmodai.workspace.WorkspaceStorageArea
import com.gcmodai.app.ui.pages.favorite.FavoritePage
import com.gcmodai.app.ui.pages.history.HistoryPage
import com.gcmodai.app.ui.pages.imggen.ImageGenPage
import com.gcmodai.app.ui.pages.log.LogPage
import com.gcmodai.app.ui.pages.mediacreation.MediaCreationPage
import com.gcmodai.app.ui.pages.mediacreation.MediaCreationSessionsPage
import com.gcmodai.app.ui.pages.search.SearchPage
import com.gcmodai.app.data.user.OfficialProviderManager
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.ui.pages.account.AccountPage
import com.gcmodai.app.ui.pages.billing.BillingPage
import com.gcmodai.app.ui.pages.checkin.CheckinPage
import com.gcmodai.app.ui.pages.leaderboard.LeaderboardPage
import com.gcmodai.app.ui.pages.binary.BinaryToolPage
import com.gcmodai.app.ui.pages.harden.HardenPage
import com.gcmodai.app.ui.pages.files.FilesPage
import com.gcmodai.app.ui.pages.workspace.MyWorkspacePage
import com.gcmodai.app.ui.pages.workspace.WorkspaceHubPage
import com.gcmodai.app.ui.pages.agent.PcAgentPage
import com.gcmodai.app.data.remote.RemoteConfigRepository
import com.gcmodai.app.ui.components.announcement.GlobalAnnouncementHost
import com.gcmodai.app.ui.pages.account.LoginPage
import com.gcmodai.app.ui.pages.account.RegisterPage
import com.gcmodai.app.ui.pages.account.TokenPage
import com.gcmodai.app.ui.pages.account.TopupPage
import com.gcmodai.app.ui.pages.onboarding.IpProbe
import com.gcmodai.app.ui.pages.onboarding.OnboardingGate
import com.gcmodai.app.ui.pages.onboarding.OnboardingPage
import com.gcmodai.app.ui.pages.setting.SettingAboutPage
import com.gcmodai.app.ui.pages.setting.SettingPreferencesPage
import com.gcmodai.app.ui.pages.setting.SettingPreferencesThemePage
import com.gcmodai.app.ui.pages.setting.SettingPreferencesNotificationPage
import com.gcmodai.app.ui.pages.setting.SettingPreferencesGeneralPage
import com.gcmodai.app.ui.pages.setting.SettingPreferencesNetworkPage
import com.gcmodai.app.ui.pages.setting.SettingPreferencesUIPage
import com.gcmodai.app.ui.pages.setting.SettingThemePage
import com.gcmodai.app.ui.pages.setting.SettingFilesPage
import com.gcmodai.app.ui.pages.setting.SettingMcpDetailPage
import com.gcmodai.app.ui.pages.setting.SettingMcpPage
import com.gcmodai.app.ui.pages.setting.SettingModelPage
import com.gcmodai.app.ui.pages.setting.SettingPage
import com.gcmodai.app.ui.pages.setting.SettingProviderDetailPage
import com.gcmodai.app.ui.pages.setting.SettingProviderPage
import com.gcmodai.app.ui.pages.setting.SettingSearchDetailPage
import com.gcmodai.app.ui.pages.setting.SettingSearchPage
import com.gcmodai.app.ui.pages.setting.SettingMediaPage
import com.gcmodai.app.ui.pages.setting.SettingMediaProviderDetailPage
import com.gcmodai.app.ui.pages.setting.SettingSpeechPage
import com.gcmodai.app.ui.pages.setting.SettingWebPage
import com.gcmodai.app.ui.pages.share.handler.ShareHandlerPage
import com.gcmodai.app.ui.pages.stats.StatsPage
import com.gcmodai.app.ui.pages.translator.TranslatorPage
import com.gcmodai.app.ui.pages.webview.WebViewPage
import com.gcmodai.app.ui.theme.GcmodAiTheme
import com.gcmodai.app.utils.CrashHandler
import com.gcmodai.app.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "com.gcmodai.app.action.TRANSLATE"
private const val ACTION_MEDIA_CREATION = "com.gcmodai.app.action.MEDIA_CREATION"
private const val EXTRA_CONVERSATION_ID = "conversationId"

/** 打开指定会话的聊天页 */
fun conversationIntent(context: Context, conversationId: String): Intent =
    Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val mediaCreationRepository by inject<MediaCreationRepository>()
    private val userRepository by inject<UserRepository>()
    private val officialProviderManager by inject<OfficialProviderManager>()

    private var navStack: MutableList<NavKey>? = null
    private val pendingIntents = ArrayDeque<Intent>()

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            GcmodAiTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        // 图片缓存给死上限：内存 20% 堆、磁盘 160MB。
                        // 默认值在长会话（大量头像/截图/图表）里会一路吃到 OOM 边缘，
                        // 这里主动收口，超出部分交给 LRU 淘汰。
                        .memoryCache {
                            MemoryCache.Builder()
                                .maxSizePercent(context, 0.20)
                                .build()
                        }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                                .maxSizeBytes(160L * 1024 * 1024)
                                .build()
                        }
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val backStack = navStack ?: run {
            // Compose 尚未创建导航栈，待就绪后处理。
            pendingIntents.addLast(intent)
            return
        }
        val destination = when (intent.action) {
            ACTION_TRANSLATE -> Screen.Translator
            ACTION_MEDIA_CREATION -> {
                openNewMediaCreation(backStack)
                null
            }
            Intent.ACTION_SEND -> Screen.ShareHandler(
                text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
                streamUri = intent.getStringExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
            )
            else -> mediaCreationDestination(intent)
                ?: intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { Screen.Chat(it) }
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    // 快捷方式没有指定会话，打开一个空会话
    private fun openNewMediaCreation(backStack: MutableList<NavKey>) {
        lifecycleScope.launch {
            val destination = Screen.MediaCreation(mediaCreationRepository.newSession().id.toString())
            if (backStack.lastOrNull() != destination) backStack.add(destination)
        }
    }

    // 媒体创作的通知带着会话 ID，点击后直接打开那个会话
    private fun mediaCreationDestination(intent: Intent): Screen? {
        val sessionId = intent.getStringExtra(MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID) ?: return null
        return runCatching { Uuid.parse(sessionId) }.getOrNull()?.let { Screen.MediaCreation(it.toString()) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        // 远程配置：管理员在网页后台改的官方群、公告、功能开关在这里落地。
        // 拉取失败就用内置默认值，绝不阻塞启动。
        val remoteConfigRepo = koinInject<RemoteConfigRepository>()
        val remoteConfig by remoteConfigRepo.config.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            remoteConfigRepo.refresh()
        }
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        // 冷启动且没有外部 Intent 时，判断要不要进引导页。
        // 分享/翻译/会话跳转等外部 Intent 会压到引导页之上（handleIntent 未改动），
        // 这是预期行为：那些 Intent 代表用户带着明确目的进来，不该被教程拦住。
        val hasExternalIntent = intent.action in setOf(
            ACTION_TRANSLATE,
            ACTION_MEDIA_CREATION,
            Intent.ACTION_SEND,
            Intent.ACTION_PROCESS_TEXT,
        ) || intent.getStringExtra(EXTRA_CONVERSATION_ID) != null ||
            intent.getStringExtra(MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID) != null

        // 「新 IP 触发一次引导」需要先问服务端当前出口 IP，这是一次网络请求。
        // rememberNavBackStack 只在首帧取值，探测结果回来后再改 startScreen 是无效的，
        // 所以这里必须先等探测落定再建导航栈——期间显示与启动画面一致的占位，不出现白屏。
        // /api/ip 是本站 nginx 直接回显，正常几十毫秒；超时也只影响「这次要不要引导」。
        var resolvedIp by remember { mutableStateOf<String?>(null) }
        // 未登录时不需要等 IP：登录门禁在最前面，IP 探测结果根本没机会被用到。
        // 跳过探测可以让「首次打开 App」立刻落到登录页，而不是先白屏 3 秒。
        val sessionAtStart = userRepository.sessionFlow.value
        var ipSettled by remember { mutableStateOf(hasExternalIntent || sessionAtStart == null) }
        LaunchedEffect(Unit) {
            if (!hasExternalIntent && sessionAtStart != null) {
                resolvedIp = IpProbe.fetch()
                ipSettled = true
            }
        }

        // 探测未落定时不建导航栈：rememberNavBackStack 只在首帧取值，
        // 若先按「不引导」建栈、等 IP 回来再改 startScreen 是无效的。
        // 这里用占位 + 条件建栈：占位与主题背景同色，最长 3 秒，失败也会落地。
        if (!ipSettled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
            return
        }

        // 登录门禁：产品要求「每个用户进入 app 前都要进行登录注册」。
        // 这条优先级最高，连外部 Intent（分享/打开会话）也不能绕过——
        // 没有账号就没有密钥、没有额度，进去也只能看到一堆报错。
        val needsLogin = userRepository.sessionFlow.value == null

        val startScreen = if (needsLogin) {
            Screen.Onboarding
        } else if (
            !hasExternalIntent && OnboardingGate.shouldShowOnboarding(
                context = this,
                session = userRepository.sessionFlow.value,
                settings = settings,
                officialProviderManager = officialProviderManager,
                currentIp = resolvedIp,
            )
        ) {
            Screen.Onboarding
        } else {
            Screen.Chat(
                id = if (readBooleanPreference("create_new_conversation_on_start", true)) {
                    Uuid.random().toString()
                } else {
                    readStringPreference(
                        "lastConversationId",
                        Uuid.random().toString()
                    ) ?: Uuid.random().toString()
                }
            )
        }

        val backStack = rememberNavBackStack(startScreen)
        SideEffect {
            navStack = backStack
            while (pendingIntents.isNotEmpty()) {
                handleIntent(pendingIntents.removeFirst())
            }
        }

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                TTSController()
                // 全局公告：挂在导航容器外层，进 app 落在任何页面都能弹出，
                // 点「知道了」立刻消失，不会残留在某个页面的组合树里继续遮挡。
                GlobalAnnouncementHost(config = remoteConfig)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        // 同一帧内连续两次返回时 NavDisplay 的返回状态还没更新，会多回调一次，不加判断会把栈弹空
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) }
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.MemoryFile> { key ->
                                MemoryFilePage(memoryId = key.memoryId, path = key.path)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.MediaCreationSessions> {
                                MediaCreationSessionsPage()
                            }

                            // 从会话列表展开（容器变换），页面本身只做淡入淡出，避免和平移打架
                            entry<Screen.MediaCreation>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.predictivePopTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                MediaCreationPage(key.id)
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferences> {
                                SettingPreferencesPage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.Account> {
                                AccountPage()
                            }

                            entry<Screen.Billing> {
                                BillingPage()
                            }

                            entry<Screen.Checkin> {
                                CheckinPage()
                            }

                            entry<Screen.Leaderboard> {
                                LeaderboardPage()
                            }

                            entry<Screen.BinaryTool> {
                                BinaryToolPage()
                            }

                            entry<Screen.Harden> {
                                HardenPage()
                            }

                            entry<Screen.Files> {
                                FilesPage(vm = koinViewModel())
                            }

                            entry<Screen.MyWorkspace> {
                                MyWorkspacePage(vm = koinViewModel())
                            }

                            entry<Screen.WorkspaceHub> {
                                WorkspaceHubPage()
                            }

                            entry<Screen.PcAgents> {
                                PcAgentPage()
                            }

                            entry<Screen.Onboarding> {
                                OnboardingPage()
                            }

                            entry<Screen.Login> {
                                LoginPage()
                            }

                            entry<Screen.Register> {
                                RegisterPage()
                            }

                            entry<Screen.Tokens> {
                                TokenPage()
                            }

                            entry<Screen.Topup> {
                                TopupPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMedia> {
                                SettingMediaPage()
                            }

                            entry<Screen.SettingMediaProviderDetail> { key ->
                                SettingMediaProviderDetailPage(id = Uuid.parse(key.providerId))
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingMcpDetail> { key ->
                                SettingMcpDetailPage(id = Uuid.parse(key.serverId))
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceSshEditor> { key ->
                                WorkspaceSshEditorPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }
                        }
                    )
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Toaster(
                        state = toastState,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    /** 记忆库 [memoryId] 里的一个文件，[path] 为 null 时新建 */
    @Serializable
    data class MemoryFile(val memoryId: String, val path: String? = null) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    /** 我的账号（用户中心） */
    @Serializable
    data object Account : Screen

    /** 账单明细（每次模型调用的真实扣费记录） */
    @Serializable
    data object Billing : Screen

    /** 每日签到 */
    @Serializable
    data object Checkin : Screen

    /** 排行榜：富豪榜（累计充值）+ 词元榜（GcmodAi 供应商 tokens 消耗） */
    @Serializable
    data object Leaderboard : Screen

    /** APK 加固（服务器端 apktool + apksigner 流水线） */
    @Serializable
    data object Harden : Screen

    /** 二进制逆向工作台：SO/二进制/DEX/APK 的解密、解析、反汇编与 AI 交接 */
    @Serializable
    data object BinaryTool : Screen

    /** 代码产物托管（公网可访问，替代原 Web 服务器） */
    @Serializable
    data object Files : Screen

    /** 我的工作区：每人 100MB 隔离空间，AI 写代码并部署网站 */
    @Serializable
    data object MyWorkspace : Screen

    /** 工作区与建站：云工作区 / SSH 远程服务器的合并入口 */
    @Serializable
    data object WorkspaceHub : Screen

    /** Windows 电脑：通过助手 exe 连接 */
    @Serializable
    data object PcAgents : Screen

    /**
     * 新手引导（强引导）。
     *
     * 只在用户还没走完引导、且是冷启动无外部 Intent 时作为起始页；
     * 走完后写 onboarding_completed 标记，之后冷启动直接进聊天页。
     */
    @Serializable
    data object Onboarding : Screen

    /** 登录 */
    @Serializable
    data object Login : Screen

    /** 注册 */
    @Serializable
    data object Register : Screen

    /** API 密钥管理 */
    @Serializable
    data object Tokens : Screen

    /** 充值 */
    @Serializable
    data object Topup : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data object MediaCreationSessions : Screen

    @Serializable
    data class MediaCreation(val id: String) : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMedia : Screen

    @Serializable
    data class SettingMediaProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data class SettingMcpDetail(val serverId: String) : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    /** [id] 为 null 时新建 */
    @Serializable
    data class WorkspaceSshEditor(val id: String? = null) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}
