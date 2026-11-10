package com.gcmodai.app.ui.pages.onboarding

import android.content.Context
import com.gcmodai.app.ui.hooks.readStringPreference
import com.gcmodai.app.ui.hooks.writeStringPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 出口 IP 探测。
 *
 * 端点由本站 nginx 直接回显 `$remote_addr`（见 /etc/nginx/conf.d/gcmodai.conf 的
 * `location = /api/ip`），不采信客户端伪造的 X-Forwarded-For，因此结果可信。
 *
 * 为什么需要它：产品要求「换了一个新 IP 就触发一次新手引导」。
 * 纯靠本地状态判断做不到这件事——换个网络、换个 Wi-Fi、重装、甚至连运营商重新分配
 * 地址都会是「新 IP」，所以必须向服务端问「你现在看到的我是谁」。
 */
internal object IpProbe {
    private const val URL = "https://gcmod.bond/api/ip"

    /** 3 秒足够，且绝不能拖慢启动：失败一律当作「拿不到 IP」，不阻塞引导判定。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 返回出口 IP；任何异常/超时/脏数据都返回 null，由调用方走保守分支。 */
    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(URL).header("Cache-Control", "no-cache").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string().orEmpty()
                json.parseToJsonElement(text)
                    .jsonObject["ip"]
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}

/**
 * 「新 IP 触发一次引导」的判定与记录。
 *
 * 语义严格按产品要求：**每个 IP 只触发一次**。
 * - 记的是「上一次触发引导时的 IP」，不是「上一次启动的 IP」；
 * - 同一 IP 再次启动不再打扰；
 * - 换了 IP 就重新来一次（哪怕引导上次已经完成）。
 *
 * 持久化用独立 key，绝不放 UserSession（登出会把 session store 清空，
 * 那样一登出就又开始引导，变成死循环）。
 */
object OnboardingIpGate {
    /** 上一次「触发引导」时记录的出口 IP */
    private const val KEY_LAST_TRIGGER_IP = "onboarding_last_trigger_ip"

    fun lastTriggerIp(context: Context): String =
        context.readStringPreference(KEY_LAST_TRIGGER_IP, "").orEmpty()

    fun setLastTriggerIp(context: Context, ip: String) =
        context.writeStringPreference(KEY_LAST_TRIGGER_IP, ip)

    /**
     * 当前 IP 是否需要触发引导。
     *
     * [currentIp] 为 null（探测失败 / 无网络）时返回 false：
     * 网络异常不是「换了新 IP」，此时弹引导会让离线用户莫名其妙，
     * 而且真正的新用户本来就会被会话/密钥判定拦下来。
     */
    fun shouldTrigger(context: Context, currentIp: String?): Boolean {
        if (currentIp.isNullOrBlank()) return false
        return currentIp != lastTriggerIp(context)
    }

    /** 立即把当前 IP 记为「已触发过」，防止同一次启动重复弹。 */
    fun markTriggered(context: Context, currentIp: String?) {
        if (currentIp.isNullOrBlank()) return
        setLastTriggerIp(context, currentIp)
    }
}
