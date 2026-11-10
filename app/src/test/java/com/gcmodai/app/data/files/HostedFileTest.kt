package com.gcmodai.app.data.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 托管模型解析与展示逻辑验证。
 *
 * 样本全部来自服务端真实返回，避免字段名猜错——这是这类集成最容易翻车的地方。
 */
class HostedFileTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** /api/files/quota 真实返回。 */
    private val realQuota = """
    {"success":true,"data":{"user":"2173151712","used_bytes":174,
     "quota_bytes":104857600,"remaining_bytes":104857426,"retention_hours":24}}
    """.trimIndent()

    /** /api/files/list 列表项真实返回。 */
    private val realItem = """
    {"id":"9f932a2cc6ff79d1","path":"demosite8888","size_bytes":1922,
     "created_at":1794065191,"expires_at":1794151591,
     "url":"/f/eb7cccde8b48b4fb/9f932a2cc6ff79d1/",
     "file_count":2,"files":["index.html","style.css"],
     "index_url":"/f/eb7cccde8b48b4fb/9f932a2cc6ff79d1/index.html"}
    """.trimIndent()

    private fun parseItem(raw: String): HostedFile =
        json.decodeFromString(HostedFile.serializer(), raw)

    private fun parseQuota(): FilesQuota {
        val data = json.parseToJsonElement(realQuota).jsonObject["data"]!!.jsonObject
        return json.decodeFromJsonElement(FilesQuota.serializer(), data)
    }

    // ---------------- 配额 ----------------

    @Test
    fun `parses real quota response`() {
        val q = parseQuota()
        assertEquals("2173151712", q.user)
        assertEquals(174L, q.usedBytes)
        assertEquals(104857600L, q.quotaBytes)
        assertEquals(104857426L, q.remainingBytes)
        assertEquals(24, q.retentionHours)
    }

    @Test
    fun `quota is exactly 100MB`() {
        assertEquals(100.0, parseQuota().totalMb, 0.001)
    }

    @Test
    fun `used fraction near zero for tiny file`() {
        val q = parseQuota()
        assertTrue(q.usedFraction < 0.001f)
        assertTrue(q.usedFraction >= 0f)
    }

    @Test
    fun `full quota shows fraction 1`() {
        val q = FilesQuota(usedBytes = 104857600L, quotaBytes = 104857600L, remainingBytes = 0)
        assertEquals(1f, q.usedFraction, 0.0001f)
        assertEquals(0.0, q.freeMb, 0.0001)
    }

    @Test
    fun `zero quota does not divide by zero`() {
        val q = FilesQuota(quotaBytes = 0, usedBytes = 0, remainingBytes = 0)
        assertEquals(0f, q.usedFraction, 0.0001f)
    }

    // ---------------- 列表项 ----------------

    @Test
    fun `parses real list item`() {
        val f = parseItem(realItem)
        assertEquals("9f932a2cc6ff79d1", f.id)
        assertEquals("demosite8888", f.path)
        assertEquals("demosite8888", f.name)
        assertEquals(1922L, f.sizeBytes)
        assertEquals(1794151591L, f.expiresAt)
        assertEquals(2, f.fileCount)
        assertEquals(listOf("index.html", "style.css"), f.files)
    }

    @Test
    fun `full url prefers index html`() {
        val f = parseItem(realItem)
        assertEquals(
            FilesRepository.BASE_URL + "/f/eb7cccde8b48b4fb/9f932a2cc6ff79d1/index.html",
            f.fullUrl,
        )
    }

    @Test
    fun `deployable site detected by index html`() {
        assertTrue(parseItem(realItem).isDeployableSite)
    }

    @Test
    fun `site without index is not deployable`() {
        val f = parseItem("""{"id":"abc","path":"raw","url":"/f/h/abc/","files":["a.txt"]}""")
        assertFalse(f.isDeployableSite)
        assertTrue(f.fullUrl.endsWith("/f/h/abc/"))
    }

    @Test
    fun `absolute url is not double prefixed`() {
        val f = HostedFile(url = "https://example.com/x/", indexUrl = "https://example.com/x/index.html")
        assertEquals("https://example.com/x/index.html", f.fullUrl)
    }

    @Test
    fun `name falls back to id when path blank`() {
        val f = parseItem("""{"id":"abc123"}""")
        assertEquals("abc123", f.name)
    }

    @Test
    fun `size label formats bytes kb mb`() {
        assertEquals("512 B", HostedFile(sizeBytes = 512).sizeLabel)
        assertEquals("1.5 KB", HostedFile(sizeBytes = 1536).sizeLabel)
        assertEquals("2.00 MB", HostedFile(sizeBytes = 2097152).sizeLabel)
    }

    @Test
    fun `remaining time decreases and floors at zero`() {
        val now = 1000L
        val f = HostedFile(expiresAt = now + 7200)
        assertEquals(7200L, f.remainingSeconds(now))
        assertTrue(f.remainingLabel(now).contains("小时"))

        val expired = HostedFile(expiresAt = now - 10)
        assertEquals(0L, expired.remainingSeconds(now))
        assertEquals("已过期", expired.remainingLabel(now))
    }

    @Test
    fun `remaining label uses minutes under an hour`() {
        val f = HostedFile(expiresAt = 1000L + 1800)
        assertTrue(f.remainingLabel(1000L).contains("分钟"))
        assertFalse(f.remainingLabel(1000L).contains("小时"))
    }

    @Test
    fun `deployable extensions cover web assets`() {
        listOf("html", "htm", "css", "js", "png", "svg", "json", "wasm").forEach {
            assertTrue("$it 应该是可部署类型", it in FilesRepository.DEPLOYABLE_EXTENSIONS)
        }
    }

    @Test
    fun `max file size is 32MB`() {
        assertEquals(32L * 1024 * 1024, FilesRepository.MAX_FILE_BYTES)
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val f = parseItem("""{"id":"x"}""")
        assertEquals("x", f.id)
        assertEquals(0L, f.sizeBytes)
        assertEquals(24, f.retentionHours)
        assertEquals(0, f.fileCount)
    }
}
