package com.gcmodai.ai.agent

import com.gcmodai.ai.util.UpstreamHtmlException
import com.gcmodai.ai.util.parseJsonOrThrow
import com.gcmodai.ai.util.requireJsonLike
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * 上游把 HTML 当 JSON 返回时的重试行为。
 *
 * 这个场景值得单独钉死，因为它的表象极具误导性：
 * 界面报的是「JSON 解析失败」，看起来像客户端 bug，
 * 实际是网关维护页/WAF 拦截页——本该重试的瞬时故障，却被当成致命错误直接终止。
 */
class UpstreamHtmlRetryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `an html page is reported as an upstream error, not a parse crash`() {
        val html = """
            <!DOCTYPE html><html class="no-js" lang="en"><head>
            <meta charset="utf-8"><title>Just a moment...</title></head>
            <body>Checking your browser before accessing.</body></html>
        """.trimIndent()

        val error = runCatching { json.parseJsonOrThrow(html) }.exceptionOrNull()

        assertTrue("应识别为上游网页错误，实际: $error", error is UpstreamHtmlException)
        assertTrue("错误里要带上原文片段方便排查", error?.message?.contains("DOCTYPE") == true)
    }

    @Test
    fun `a truncated json body is also treated as transient`() {
        val error = runCatching { json.parseJsonOrThrow("""{"id":"a","choices":[""") }.exceptionOrNull()

        assertTrue("截断的响应体同样是瞬时故障", error is UpstreamHtmlException)
    }

    @Test
    fun `valid json passes through untouched`() {
        val body = """{"id":"chatcmpl-1","choices":[{"message":{"content":"hi"}}]}"""

        val parsed = json.parseJsonOrThrow(body)

        val id = (parsed as kotlinx.serialization.json.JsonObject)["id"]
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals("chatcmpl-1", id)
    }

    @Test
    fun `plain text and empty bodies are rejected with a readable message`() {
        // "Not Found" 不含 HTML 标记，会走到 JSON 解析失败那条分支
        assertTrue(runCatching { json.parseJsonOrThrow("Not Found") }.exceptionOrNull() is UpstreamHtmlException)
        assertTrue(runCatching { json.parseJsonOrThrow("") }.exceptionOrNull() is UpstreamHtmlException)
    }

    // ---- 重试策略 ----

    @Test
    fun `html errors are retryable and io errors stay retryable`() {
        assertTrue(isTransientFailure(UpstreamHtmlException("<html>")))
        assertTrue(isTransientFailure(IOException("connection reset")))
    }

    @Test
    fun `programming errors are not retryable`() {
        // 请求体真的写错、模型名不存在，重试只会烧钱
        assertFalse(isTransientFailure(IllegalArgumentException("bad request")))
        assertFalse(isTransientFailure(NoSuchElementException("choices is null")))
    }

    @Test
    fun `gateway style messages are treated as transient`() {
        assertTrue(isTransientFailure(RuntimeException("Failed to get response: 502 Bad Gateway")))
        assertTrue(isTransientFailure(RuntimeException("Service Unavailable")))
        assertTrue(isTransientFailure(RuntimeException("upstream timeout")))
    }

    @Test
    fun `persistent policy retries ten times`() {
        assertEquals(10, RetryPolicy.PERSISTENT.maxRetries)
        assertEquals(3, RetryPolicy.NETWORK.maxRetries)
        assertEquals(0, RetryPolicy.NONE.maxRetries)
    }

    @Test
    fun `backoff is capped so ten retries cannot hang for minutes`() {
        // 不封顶的话第 10 次要等 512 秒，用户只会以为 App 卡死
        assertEquals(1.seconds, backoffFor(RetryPolicy.PERSISTENT, 1))
        assertEquals(2.seconds, backoffFor(RetryPolicy.PERSISTENT, 2))
        assertEquals(4.seconds, backoffFor(RetryPolicy.PERSISTENT, 3))
        assertEquals(8.seconds, backoffFor(RetryPolicy.PERSISTENT, 4))
        assertEquals(8.seconds, backoffFor(RetryPolicy.PERSISTENT, 10))
    }

    @Test
    fun `an html error causes exactly ten attempts before giving up`() = runTest {
        var attempts = 0
        val html = "<!DOCTYPE html><html class=\"no-js\"><body>maintenance</body></html>"

        val error = runCatching {
            withRetry(RetryPolicy.PERSISTENT) {
                attempts++
                json.parseJsonOrThrow(html)
            }
        }.exceptionOrNull()

        assertTrue(error is UpstreamHtmlException)
        // 首次 + 10 次重试 = 11 次
        assertEquals(11, attempts)
    }

    @Test
    fun `a transient failure followed by success recovers`() = runTest {
        var attempts = 0
        val result = withRetry(RetryPolicy.PERSISTENT) {
            attempts++
            if (attempts < 3) throw UpstreamHtmlException("<!DOCTYPE html>")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retrying reports each attempt so the ui can show progress`() = runTest {
        val seen = mutableListOf<Int>()
        runCatching {
            withRetry(RetryPolicy.PERSISTENT, onRetry = { seen += it.retry }) {
                throw UpstreamHtmlException("<html>")
            }
        }

        assertEquals((1..10).toList(), seen)
    }
}
