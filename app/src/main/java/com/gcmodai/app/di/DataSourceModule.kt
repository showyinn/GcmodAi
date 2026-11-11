package com.gcmodai.app.di

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.gcmodai.ai.provider.ProviderManager
import com.gcmodai.common.http.AcceptLanguageBuilder
import com.gcmodai.mediagen.provider.MediaGenerationManager
import com.gcmodai.app.AppScope
import com.gcmodai.app.BuildConfig
import com.gcmodai.app.data.ai.AIRequestInterceptor
import com.gcmodai.app.data.ai.ConversationMetaGenerator
import com.gcmodai.app.data.ai.MemoryConsolidationScheduler
import com.gcmodai.app.data.ai.MemoryConsolidator
import com.gcmodai.app.data.ai.RequestLoggingInterceptor
import com.gcmodai.app.data.ai.TranslationHandler
import com.gcmodai.app.data.ai.UsageRecorder
import com.gcmodai.app.data.ai.account.ProviderAccountManager
import com.gcmodai.app.data.ai.mcp.McpManager
import com.gcmodai.app.data.ai.transformers.AssistantTemplateLoader
import com.gcmodai.app.data.ai.transformers.TemplateTransformer
import com.gcmodai.app.data.datastore.SettingsStore
import com.gcmodai.app.data.db.AppDatabase
import com.gcmodai.app.data.db.AppDatabaseFactory
import com.gcmodai.app.data.db.fts.MessageFtsManager
import com.gcmodai.app.data.remote.ModelsDevUpdater
import com.gcmodai.app.data.remote.RemoteFileStore
import com.gcmodai.app.data.remote.GatewayFailoverInterceptor
import com.gcmodai.app.data.remote.GcmodAiAPI
import com.gcmodai.app.data.remote.LeaderboardRepository
import com.gcmodai.app.data.remote.SettingsProxyAuthenticator
import com.gcmodai.app.data.remote.SettingsProxySelector
import com.gcmodai.app.data.remote.SettingsSocks5Authenticator
import com.gcmodai.app.data.remote.SponsorAPI
import com.gcmodai.app.data.sync.BackupManager
import com.gcmodai.app.data.sync.S3Sync
import com.gcmodai.app.data.sync.webdav.WebDavSync
import com.gcmodai.app.service.memoryDreamKeepAlive
import com.gcmodai.search.SearchService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        val engine = PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
        // 消息模板按助手 ID 缓存，内容取自设置，设置变了就让已编译的模板失效
        val settingsStore = get<SettingsStore>()
        get<AppScope>().launch {
            settingsStore.settingsFlow.collect { engine.templateCache.invalidateAll() }
        }
        engine
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().memoryConsolidationDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().mediaCreationDao()
    }

    single {
        get<AppDatabase>().translationHistoryDao()
    }

    single {
        get<AppDatabase>().usageRecordDao()
    }

    single {
        UsageRecorder(dao = get(), scope = get<AppScope>())
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        MemoryConsolidator(
            providerManager = get(),
            usageRecorder = get(),
            memoryRepository = get(),
            conversationRepo = get(),
            consolidationDao = get(),
            settingsStore = get(),
        )
    }

    single {
        MemoryConsolidationScheduler(
            scope = get<AppScope>(),
            worker = get<MemoryConsolidator>(),
            keepAlive = memoryDreamKeepAlive(get<Context>()),
            isAppVisible = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        )
    }

    single {
        TranslationHandler(providerManager = get(), usageRecorder = get())
    }

    single {
        ConversationMetaGenerator(context = get(), providerManager = get(), usageRecorder = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "GcmodAi-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            // [failover-20261111] 主域名（CDN）连不上时自动切到自有源站备用入口。
            // 放在最后：前面的鉴权/UA 拦截器已经改好请求，切换时不会丢头。
            .addInterceptor(GatewayFailoverInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
        client.also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ModelsDevUpdater(context = get(), client = get())
    }

    single {
        ProviderAccountManager(
            settingsStore = get(),
            appScope = get(),
            client = get(),
        )
    }

    single {
        ProviderManager(client = get(), context = get(), accountCredentials = get<ProviderAccountManager>())
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single {
        RemoteFileStore(
            settingsStore = get(),
            httpClient = get(),
            scope = get<AppScope>()
        )
    }

    single {
        MediaGenerationManager(client = get())
    }

    // 排行榜：https://gcmod.online/api/leaderboard（富豪榜 / 词元榜，各前 10 名）
    single {
        LeaderboardRepository(client = get())
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.gcmodai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<GcmodAiAPI> {
        get<Retrofit>().create(GcmodAiAPI::class.java)
    }
}
