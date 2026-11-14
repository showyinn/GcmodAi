package com.gcmodai.ai.agent

import com.gcmodai.ai.util.UpstreamHtmlException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 请求模型失败时的重试策略：最多重试 [maxRetries] 次，等待时间从 [initialDelay] 开始每次翻倍。
 *
 * 默认重试两类错误：
 * - [IOException]：连不上、超时、读到一半断开这类网络故障；
 * - [UpstreamHtmlException]：上游把 HTML 页面当响应返回（网关维护页、WAF 拦截、登录跳转）。
 *
 * 第二类是实战里最气人的一种：HTTP 状态码是 200，内容却是一张网页，
 * 客户端只看到「JSON 解析失败」就放弃。它和网络抖动一样是瞬时的，该重试。
 *
 * 上限 [maxDelay] 很重要：10 次翻倍如果不封顶会等到 8 分钟，
 * 用户只会以为 App 卡死。封顶后总计等待约 [capTotalDelay]。
 */
data class RetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration = 1.seconds,
    /** 单次等待的上限，防止指数退避把总时长拖成几分钟 */
    val maxDelay: Duration = 8.seconds,
    val retryOn: (Throwable) -> Boolean = ::isTransientFailure,
) {
    companion object {
        val NONE = RetryPolicy(maxRetries = 0)

        /** 网络自动重试：最多 3 次，间隔 1s、2s、4s */
        val NETWORK = RetryPolicy(maxRetries = 3)

        /**
         * 顽固故障重试：最多 10 次。
         *
         * 用于任务模式：用户在跑长任务，中途撞上一次网关维护页就整轮报废，
         * 代价远大于多等十几秒。10 次 × 封顶 8 秒 ≈ 最坏 40 秒，
         * 够撑过大多数网关重启与 WAF 抖动。
         */
        val PERSISTENT = RetryPolicy(maxRetries = 10)
    }
}

/**
 * 这个异常值不值得再试一次。
 *
 * 刻意**不**把任意 RuntimeException 都算进来：请求体真的写错、模型名不存在
 * 这类问题重试一万次也一样失败，只会把额度和时间一起烧掉。
 */
fun isTransientFailure(error: Throwable): Boolean {
    if (error is UpstreamHtmlException) return true
    if (error is IOException) return true
    val message = error.message?.lowercase().orEmpty()
    // 一些网关会把 5xx 包成普通异常抛出，按文案兜一下
    return "timeout" in message ||
        "temporarily unavailable" in message ||
        "bad gateway" in message ||
        "service unavailable" in message ||
        "gateway timeout" in message ||
        "connection reset" in message
}

/** 第 [retry] 次重试（共 [maxRetries] 次）将在 [delay] 后开始，[cause] 是上一次失败的原因 */
data class RetryAttempt(
    val cause: Throwable,
    val retry: Int,
    val maxRetries: Int,
    val delay: Duration,
)

/**
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (error: Throwable) {
            // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
            // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
            currentCoroutineContext().ensureActive()
            if (error is CancellationException || retries >= policy.maxRetries || !policy.retryOn(error)) {
                throw error
            }
            retries++
            val backoff = backoffFor(policy, retries)
            onRetry(RetryAttempt(error, retries, policy.maxRetries, backoff))
            delay(backoff)
        }
    }
}

/** 第 [retry] 次的等待时长：1s、2s、4s、8s…但不超过 [RetryPolicy.maxDelay] */
internal fun backoffFor(policy: RetryPolicy, retry: Int): Duration {
    val raw = policy.initialDelay * (1 shl (retry - 1).coerceIn(0, 20))
    return if (raw > policy.maxDelay) policy.maxDelay else raw
}
