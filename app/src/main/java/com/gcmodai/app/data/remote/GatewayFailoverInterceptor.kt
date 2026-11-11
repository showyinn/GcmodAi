package com.gcmodai.app.data.remote

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 主域名连不上时自动切到备用入口。
 *
 * 背景（2026-11-11 线上）：`gcmod.online` 走第三方 CDN，实测不同网络会解析到不同边缘节点，
 * 有用户手机拿到的节点 443 直接连不通（20 秒超时），表现为「创建密钥失败 / 模型调不通」。
 * 我们在自有源站 `gcmod.bond` 上加了一层同接口反代，这里让客户端在失败时自动切过去。
 *
 * 设计要点：
 * - **只改写域名，不改路径与请求体**：备用入口提供的是同一套接口（nginx 侧按前缀反代）。
 * - **失败后进入冷却期**：避免每个请求都先等一次超时。冷却期内直接走备用域名，
 *   冷却结束再探一次主域名，恢复后自动切回。
 * - **只在 IOException（连接/读取失败）时切换**：HTTP 4xx/5xx 是业务响应，
 *   不该触发切换，否则会把「token 无效」误判成「网络故障」。
 */
class GatewayFailoverInterceptor(
    private val primaryHost: String = PRIMARY_HOST,
    private val fallbackHost: String = FALLBACK_HOST,
    private val cooldownMillis: Long = 5 * 60 * 1000L,
) : Interceptor {

    @Volatile
    private var primaryDownUntil: Long = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.equals(primaryHost, ignoreCase = true)) {
            return chain.proceed(request)
        }

        val now = System.currentTimeMillis()
        if (now < primaryDownUntil) {
            // 冷却期：直接走备用，失败再把状态清掉交给下一次探测
            return try {
                chain.proceed(switchToFallback(request))
            } catch (e: IOException) {
                primaryDownUntil = 0L
                throw e
            }
        }

        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            Log.w(TAG, "主域名 ${request.url.host} 不可达，切备用 $fallbackHost：${e.javaClass.simpleName}: ${e.message}")
            primaryDownUntil = System.currentTimeMillis() + cooldownMillis
            try {
                chain.proceed(switchToFallback(request))
            } catch (fallbackError: IOException) {
                // 两个入口都失败：把原始异常抛出去，保留真实原因
                throw e
            }
        }
    }

    private fun switchToFallback(request: Request): Request =
        request.newBuilder()
            .url(request.url.newBuilder().host(fallbackHost).build())
            .build()

    companion object {
        private const val TAG = "GatewayFailover"
        const val PRIMARY_HOST = "gcmod.online"
        const val FALLBACK_HOST = "gcmod.bond"
    }
}
