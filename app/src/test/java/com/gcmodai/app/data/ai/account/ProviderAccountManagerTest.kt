package com.gcmodai.app.data.ai.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.gcmodai.ai.provider.OAuthCredential
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderAccountManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    /**
     * 宿主环境限制，不是逻辑缺陷：本组用例都靠 DataStore 落盘，
     * Windows 上「.tmp → 正式文件」原子改名会因句柄占用失败，Linux / macOS / 真机正常。
     */
    @Before
    fun skipOnWindowsHost() {
        assumeTrue(
            "DataStore 落盘用例在 Windows 宿主上不可复现",
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
        )
    }

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appScope = AppScope()

    // 刷新请求到达令牌端点后卡住，直到测试放行
    private val requestArrived = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requestArrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
                        .toResponseBody("application/json".toMediaType())
                )
                .build()
        }
        .build()

    @After
    fun tearDown() = runBlocking {
        appScope.coroutineContext.job.cancelAndJoin()
        storeScope.coroutineContext.job.cancelAndJoin()
    }

    private val provider = ProviderSetting.ChatGPT(
        credential = OAuthCredential(
            accessToken = "old-access",
            refreshToken = "old-refresh",
            expiresAt = 1L, // 早已过期，一取凭据就会刷新
            accountId = "account",
        ),
    )

    private suspend fun createStore(): SettingsStore {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(temp.root, "settings.preferences_pb") },
            storeScope,
        )
        store.awaitLoaded()
        store.update { it.copy(providers = it.providers + provider) }
        return store
    }

    private fun SettingsStore.credential(): OAuthCredential? =
        (settingsFlow.value.providers.find { it.id == provider.id } as ProviderSetting.ChatGPT).credential

    @Test
    fun `refresh that already started is saved even if the caller is cancelled`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)

        val caller = launch(Dispatchers.Default) { manager.getCredential(provider, rejectedAccessToken = null) }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })
        // 服务端已经收到刷新请求（旧 refresh token 作废），这时调用方被取消，比如停止生成
        caller.cancelAndJoin()
        release.countDown()

        val saved = withTimeout(10_000) {
            store.settingsFlow
                .map { settings ->
                    (settings.providers.find { it.id == provider.id } as ProviderSetting.ChatGPT).credential
                }
                .first { it?.refreshToken == "new-refresh" }
        }
        assertEquals("new-access", saved?.accessToken)
        assertEquals("account", saved?.accountId)
    }

    @Test
    fun `refresh result is dropped if the user signs out meanwhile`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)

        val caller = async(Dispatchers.Default) {
            runCatching { manager.getCredential(provider, rejectedAccessToken = null) }
        }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })
        manager.logout(provider.id)
        release.countDown()

        assertTrue(caller.await().isFailure)
        assertNull(store.credential())
    }
}
