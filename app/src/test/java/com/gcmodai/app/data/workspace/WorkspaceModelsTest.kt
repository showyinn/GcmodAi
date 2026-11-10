package com.gcmodai.app.data.workspace

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区客户端验证。
 *
 * 重点：
 * 1. 用户哈希必须与服务端算法完全一致，否则算出来的部署地址是错的
 * 2. 配额/文件树的字段名必须对上服务端真实返回
 * 3. 扫描结果的分级判断
 */
class WorkspaceModelsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // ---------------- 用户哈希 ----------------

    @Test
    fun `user hash matches server algorithm`() {
        // 服务端：hashlib.sha256(username).hexdigest()[:16]
        // 已用 Python 验证过 2173151712 -> eb7cccde8b48b4fb
        assertEquals(
            "eb7cccde8b48b4fb",
            WorkspaceRepository.sha256Hex16("2173151712"),
        )
    }

    @Test
    fun `user hash is 16 chars lowercase hex`() {
        val h = WorkspaceRepository.sha256Hex16("someone@example.com")
        assertEquals(16, h.length)
        assertTrue(h.all { it in "0123456789abcdef" })
    }

    @Test
    fun `different users get different hashes`() {
        val a = WorkspaceRepository.sha256Hex16("user1")
        val b = WorkspaceRepository.sha256Hex16("user2")
        assertTrue(a != b)
    }

    @Test
    fun `same user always same hash`() {
        assertEquals(
            WorkspaceRepository.sha256Hex16("2173151712"),
            WorkspaceRepository.sha256Hex16("2173151712"),
        )
    }

    // ---------------- 配额 ----------------

    /** 服务端 /api/ws/quota 真实返回。 */
    private val realQuota = """
    {"success":true,"data":{"user":"2173151712","used_bytes":1048576,
     "quota_bytes":104857600,"remaining_bytes":103809024,
     "file_count":7,"max_files":2000,"max_file_bytes":33554432}}
    """.trimIndent()

    @Test
    fun `parses real quota`() {
        val data = json.parseToJsonElement(realQuota)
            .let { (it as kotlinx.serialization.json.JsonObject)["data"]!! }
        val q = json.decodeFromJsonElement(
            WorkspaceQuota.serializer(),
            data as kotlinx.serialization.json.JsonObject,
        )
        assertEquals("2173151712", q.user)
        assertEquals(1048576L, q.usedBytes)
        assertEquals(104857600L, q.quotaBytes)
        assertEquals(103809024L, q.remainingBytes)
        assertEquals(7, q.fileCount)
        assertEquals(2000, q.maxFiles)
        assertEquals(33554432L, q.maxFileBytes)
    }

    @Test
    fun `quota is exactly 100MB`() {
        assertEquals(100.0, WorkspaceQuota().totalMb, 0.001)
    }

    @Test
    fun `used fraction is computed and clamped`() {
        val half = WorkspaceQuota(usedBytes = 52428800, quotaBytes = 104857600)
        assertEquals(0.5f, half.usedFraction, 0.001f)

        val over = WorkspaceQuota(usedBytes = 200000000, quotaBytes = 104857600)
        assertEquals(1f, over.usedFraction, 0.0001f)

        val zero = WorkspaceQuota(usedBytes = 0, quotaBytes = 0)
        assertEquals(0f, zero.usedFraction, 0.0001f)
    }

    @Test
    fun `quota labels are readable`() {
        val q = WorkspaceQuota(
            usedBytes = 1048576, quotaBytes = 104857600, remainingBytes = 103809024,
        )
        assertTrue(q.usedLabel.contains("MB"))
        assertTrue(q.freeLabel.contains("MB"))
    }

    // ---------------- 文件树 ----------------

    @Test
    fun `entry knows dir vs file`() {
        val dir = WorkspaceEntry(name = "src", path = "src", type = "dir")
        val file = WorkspaceEntry(name = "index.html", path = "index.html", type = "file", size = 194)
        assertTrue(dir.isDir)
        assertFalse(file.isDir)
        assertEquals("194 B", file.sizeLabel)
        assertEquals("", dir.sizeLabel)
    }

    @Test
    fun `deployable flag marks html entry`() {
        val entry = WorkspaceEntry(
            name = "index.html", path = "index.html", type = "file",
            size = 194, deployable = true,
        )
        assertTrue(entry.deployable)
    }

    @Test
    fun `size label formats correctly`() {
        assertEquals("512 B", WorkspaceEntry(size = 512).sizeLabel)
        assertEquals("1.5 KB", WorkspaceEntry(size = 1536).sizeLabel)
        assertEquals("2.00 MB", WorkspaceEntry(size = 2097152).sizeLabel)
    }

    // ---------------- 扫描报告 ----------------

    @Test
    fun `clean report`() {
        val r = ScanReport(level = "clean", blocked = false, findings = emptyList())
        assertTrue(r.isClean)
        assertFalse(r.hasWarnings)
        assertFalse(r.blocked)
    }

    @Test
    fun `warn report`() {
        val r = ScanReport(
            level = "warn",
            findings = listOf(ScanFinding(id = "eval_usage", level = "warn", message = "用了 eval")),
        )
        assertTrue(r.hasWarnings)
        assertFalse(r.isClean)
        assertFalse(r.blocked)
        assertEquals(1, r.findings.size)
    }

    @Test
    fun `blocked report`() {
        val r = ScanReport(
            level = "block",
            blocked = true,
            findings = listOf(ScanFinding(id = "php_webshell", level = "block", message = "webshell")),
        )
        assertTrue(r.blocked)
        assertFalse(r.isClean)
    }

    @Test
    fun `parses real scan payload`() {
        val raw = """{"level":"block","blocked":true,
            "findings":[{"id":"php_webshell","level":"block","message":"包含 PHP 代码执行入口"}]}"""
        val r = json.decodeFromString(ScanReport.serializer(), raw)
        assertTrue(r.blocked)
        assertEquals("php_webshell", r.findings[0].id)
    }

    @Test
    fun `file content carries warnings`() {
        val raw = """{"path":"a.html","content":"<h1>x</h1>","size":11,
            "warnings":[{"id":"eval_usage","level":"warn","message":"eval"}]}"""
        val c = json.decodeFromString(FileContent.serializer(), raw)
        assertEquals("a.html", c.path)
        assertEquals(11L, c.size)
        assertEquals(1, c.warnings.size)
    }

    @Test
    fun `max file size is 32MB`() {
        assertEquals(32L * 1024 * 1024, WorkspaceRepository.MAX_FILE_BYTES)
    }
}
