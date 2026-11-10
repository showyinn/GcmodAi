package com.gcmodai.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 内下载的进度与文件命名验证。
 *
 * 这次改造把系统 DownloadManager 换成了 App 自己的流式下载，
 * 进度条上的数字全靠这里的计算，算错了用户会看到"100% 但没完成"之类的怪现象。
 */
class InAppUpdateDownloadTest {

    // ---------------- 字节格式化 ----------------

    @Test
    fun `formats bytes`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("0 B", formatBytes(0))
    }

    @Test
    fun `formats kilobytes`() {
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("500 KB", formatBytes(512000))
    }

    @Test
    fun `formats megabytes with one decimal`() {
        assertEquals("1.0 MB", formatBytes(1048576))
        assertEquals("18.2 MB", formatBytes(19072000))
    }

    @Test
    fun `formats gigabytes`() {
        assertEquals("1.00 GB", formatBytes(1073741824))
    }

    // ---------------- 进度状态 ----------------

    @Test
    fun `percent from progress`() {
        assertEquals(0, downloading(0f).percent)
        assertEquals(50, downloading(0.5f).percent)
        assertEquals(100, downloading(1f).percent)
    }

    @Test
    fun `percent is minus one when total unknown`() {
        assertEquals(-1, downloading(-1f).percent)
    }

    @Test
    fun `percent clamps high side`() {
        // 浮点误差可能给出 1.0000001，不能让 UI 显示超过 100
        assertEquals(100, downloading(1.5f).percent)
        assertEquals(100, downloading(1.0000001f).percent)
    }

    @Test
    fun `negative progress means unknown`() {
        // 负值是"总大小未知"的约定，percent 返回 -1 让 UI 走不确定进度条
        assertEquals(-1, downloading(-0.5f).percent)
        assertEquals(-1, downloading(-1f).percent)
    }

    @Test
    fun `size label shows downloaded over total`() {
        val ds = UpdateDownloadState.Downloading(
            name = "x.apk",
            progress = 0.5f,
            downloadedBytes = 9 * 1048576L,
            totalBytes = 18 * 1048576L,
        )
        assertEquals("9.0 MB / 18.0 MB", ds.sizeLabel)
    }

    @Test
    fun `size label shows only downloaded when total unknown`() {
        val ds = UpdateDownloadState.Downloading(
            name = "x.apk",
            progress = -1f,
            downloadedBytes = 3 * 1048576L,
            totalBytes = 0,
        )
        assertEquals("3.0 MB", ds.sizeLabel)
    }

    @Test
    fun `speed label formatted`() {
        val ds = UpdateDownloadState.Downloading(
            name = "x.apk",
            progress = 0.1f,
            downloadedBytes = 1048576,
            totalBytes = 10485760,
            bytesPerSecond = 2 * 1048576L,
        )
        assertEquals("2.0 MB/s", ds.speedLabel)
    }

    @Test
    fun `speed label empty when unknown`() {
        assertEquals("", downloading(0.1f).speedLabel)
    }

    // ---------------- 文件名清洗 ----------------

    @Test
    fun `keeps normal apk name`() {
        assertEquals("GcmodAi-3.6.6-universal.apk", sanitizeFileName("GcmodAi-3.6.6-universal.apk"))
    }

    @Test
    fun `appends apk extension when missing`() {
        assertEquals("update.apk", sanitizeFileName("update"))
    }

    @Test
    fun `strips path separators`() {
        // 服务端若返回带目录的名字，不能让它写到别处
        assertEquals("evil.apk", sanitizeFileName("../../evil.apk"))
        assertEquals("x.apk", sanitizeFileName("/tmp/x.apk"))
    }

    @Test
    fun `replaces illegal characters`() {
        val out = sanitizeFileName("a b:c*d.apk")
        assertTrue("不应含空格", !out.contains(" "))
        assertTrue("不应含冒号", !out.contains(":"))
        assertTrue("不应含星号", !out.contains("*"))
        assertTrue(out.endsWith(".apk"))
    }

    @Test
    fun `blank name falls back`() {
        assertEquals("update.apk", sanitizeFileName(""))
        assertEquals("update.apk", sanitizeFileName("   "))
    }

    @Test
    fun `uppercase extension preserved as apk`() {
        val out = sanitizeFileName("UPDATE.APK")
        assertTrue(out.endsWith(".APK") || out.endsWith(".apk"))
    }

    // ---------------- 其它状态 ----------------

    @Test
    fun `idle and downloaded and failed are distinct`() {
        assertTrue(UpdateDownloadState.Idle != UpdateDownloadState.Downloaded("x.apk"))
        assertTrue(UpdateDownloadState.Downloaded("x.apk") != UpdateDownloadState.Failed("x.apk", "e"))
    }

    private fun downloading(progress: Float) = UpdateDownloadState.Downloading(
        name = "x.apk",
        progress = progress,
    )
}
