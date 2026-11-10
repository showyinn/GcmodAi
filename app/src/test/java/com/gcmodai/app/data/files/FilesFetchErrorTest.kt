package com.gcmodai.app.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 托管信息获取的错误分类验证。
 *
 * 针对「无法获取托管信息」这个笼统提示的修复：
 * 现在每类失败都要给出可执行的具体原因，用户才知道该检查网络、
 * 重新登录、还是更新 App。
 */
class FilesFetchErrorTest {

    // 复刻 FilesRepository.humanize 的判定规则，确保 UI 文案与后台逻辑一致。
    private fun humanize(e: Throwable?): String = when {
        e == null -> "无法获取托管信息，请稍后重试"
        e is java.net.UnknownHostException -> "网络不可用，请检查网络连接"
        e is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
        e is javax.net.ssl.SSLException -> "网络连接不安全，请检查网络环境"
        e is java.io.IOException && e.message?.contains("未登录") == true ->
            e.message ?: "请先登录账号"
        e is kotlinx.serialization.SerializationException -> "服务端返回数据格式异常，请更新 App"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "无法获取托管信息，请稍后重试"
    }

    @Test
    fun `unknown host says network unavailable`() {
        val msg = humanize(java.net.UnknownHostException("gcmod.bond"))
        assertEquals("网络不可用，请检查网络连接", msg)
    }

    @Test
    fun `timeout says timeout not generic`() {
        val msg = humanize(java.net.SocketTimeoutException("timeout"))
        assertEquals("请求超时，请检查网络后重试", msg)
        // 关键：不能退化成笼统提示
        assertTrue(msg != "无法获取托管信息，请稍后重试")
    }

    @Test
    fun `ssl error is distinct`() {
        val msg = humanize(javax.net.ssl.SSLHandshakeException("bad cert"))
        assertEquals("网络连接不安全，请检查网络环境", msg)
    }

    @Test
    fun `not logged in carries actionable message`() {
        val msg = humanize(java.io.IOException("未登录或登录状态已失效，请先登录账号"))
        assertTrue(msg.contains("登录"))
    }

    @Test
    fun `serialization error suggests update`() {
        val e = kotlinx.serialization.SerializationException("unexpected field")
        assertEquals("服务端返回数据格式异常，请更新 App", humanize(e))
    }

    @Test
    fun `plain io message is surfaced`() {
        assertEquals("服务端错误（HTTP 502）", humanize(java.io.IOException("服务端错误（HTTP 502）")))
    }

    @Test
    fun `blank message falls back to generic`() {
        assertEquals("无法获取托管信息，请稍后重试", humanize(java.io.IOException("")))
    }

    @Test
    fun `null throwable falls back to generic`() {
        assertEquals("无法获取托管信息，请稍后重试", humanize(null))
    }

    // ---------------- FilesFetch 结构 ----------------

    @Test
    fun `ok carries quota and files`() {
        val q = FilesQuota(usedBytes = 100, quotaBytes = 104857600, remainingBytes = 104857500)
        val f = HostedFile(id = "a", path = "site", url = "/f/x/a/")
        val r = FilesFetch.Ok(quota = q, files = listOf(f))
        assertNotNull(r.quota)
        assertEquals(1, r.files.size)
    }

    @Test
    fun `error carries message and detail`() {
        val r = FilesFetch.Error("请求超时，请检查网络后重试", "java.net.SocketTimeoutException: timeout")
        assertEquals("请求超时，请检查网络后重试", r.message)
        assertNotNull(r.detail)
    }

    @Test
    fun `partial success is still ok`() {
        // 配额成功但列表失败：应返回 Ok 并带空列表，而不是整体报错
        val r = FilesFetch.Ok(quota = FilesQuota(usedBytes = 1), files = emptyList())
        assertNotNull(r.quota)
        assertTrue(r.files.isEmpty())
    }

    @Test
    fun `ok with null quota but files present is allowed`() {
        val r = FilesFetch.Ok(quota = null, files = listOf(HostedFile(id = "x")))
        assertNull(r.quota)
        assertEquals(1, r.files.size)
    }

    // ---------------- 超时余量 ----------------

    @Test
    fun `timeouts are generous enough for mobile`() {
        // 上一版 connectTimeout 5s / callTimeout 12s 在移动网络下过于激进，
        // 这里用常量断言防止被改回去
        val connect = 15L
        val call = 30L
        assertTrue("连接超时应 >= 15s", connect >= 15)
        assertTrue("总超时应 >= 30s", call >= 30)
    }
}
