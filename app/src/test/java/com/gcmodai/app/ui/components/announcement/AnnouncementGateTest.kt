package com.gcmodai.app.ui.components.announcement

import com.gcmodai.app.data.remote.RemoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公告显示逻辑验证。
 *
 * 这组测试直接针对上一版的三个 bug：
 * 1. 点「知道了」没效果（开关变量没接到显示条件上）
 * 2. 一直存在遮挡其他页面
 * 3. 进 app 不显示
 */
class AnnouncementGateTest {

    private fun config(
        announcement: String = "欢迎使用 GcmodAi",
        enabled: Boolean = true,
    ) = RemoteConfig(
        announcement = announcement,
        announcementEnabled = enabled,
    )

    @Test
    fun `enabled announcement with no read record should show`() {
        // 首次进 app 必须弹出（bug 3）
        assertTrue(AnnouncementGate.shouldShow(config(), lastReadSignature = null))
    }

    @Test
    fun `after user taps known the same announcement must not show again`() {
        // 这是 bug 1 的核心：点了「知道了」之后必须不再弹
        val c = config()
        val sig = AnnouncementGate.signature(c)
        assertFalse(AnnouncementGate.shouldShow(c, lastReadSignature = sig))
    }

    @Test
    fun `disabled announcement never shows`() {
        val c = config(enabled = false)
        assertFalse(AnnouncementGate.shouldShow(c, lastReadSignature = null))
        // 即使有内容也不弹
        assertFalse(
            AnnouncementGate.shouldShow(c.copy(announcement = "随便写点"), null)
        )
    }

    @Test
    fun `blank announcement never shows`() {
        assertFalse(AnnouncementGate.shouldShow(config(announcement = ""), null))
        assertFalse(AnnouncementGate.shouldShow(config(announcement = "   "), null))
    }

    @Test
    fun `admin edits content should show again`() {
        // 管理员改公告 = 新信息，应该重新弹一次
        val v1 = config(announcement = "公告第一版")
        val v2 = config(announcement = "公告第二版：新增签到功能")
        val sigV1 = AnnouncementGate.signature(v1)

        assertFalse(AnnouncementGate.shouldShow(v1, sigV1))
        assertTrue(AnnouncementGate.shouldShow(v2, sigV1))
    }

    @Test
    fun `signature is stable for identical content`() {
        val a = AnnouncementGate.signature(config(announcement = "同样的一句话"))
        val b = AnnouncementGate.signature(config(announcement = "同样的一句话"))
        assertEquals(a, b)
    }

    @Test
    fun `signature differs for different content`() {
        val a = AnnouncementGate.contentSignature("内容 A")
        val b = AnnouncementGate.contentSignature("内容 B")
        assertNotEquals(a, b)
    }

    @Test
    fun `signature handles chinese and newlines`() {
        val text = "欢迎使用 GcmodAi 3.4.0\n新增每日签到、账单明细。\n官方群：1017968390"
        val sig = AnnouncementGate.contentSignature(text)
        assertTrue(sig.isNotBlank())
        assertEquals(8, sig.length)
        // 同一段中文多次计算结果一致
        assertEquals(sig, AnnouncementGate.contentSignature(text))
    }

    @Test
    fun `signature is not affected by unrelated config fields`() {
        // 只改官方群不该让公告重新弹
        val base = config(announcement = "固定公告")
        val changed = base.copy(
            officialGroupName = "换了群名",
            appName = "换了名字",
            supportContact = "换了客服",
        )
        assertEquals(
            AnnouncementGate.signature(base),
            AnnouncementGate.signature(changed),
        )
    }

    @Test
    fun `empty signature never equals a real one`() {
        val real = AnnouncementGate.signature(config(announcement = "有内容"))
        assertNotEquals("", real)
    }
}
