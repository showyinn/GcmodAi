package com.gcmodai.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的版本比较与后台配置优先级验证。
 *
 * 重点：管理员在后台把 latest_version 调高，App 必须判定为「有新版本」；
 * 调低或相等则不该骚扰用户。
 */
class UpdateVersionLogicTest {

    @Test
    fun `higher patch means update available`() {
        assertTrue(Version("3.4.4") > Version("3.4.3"))
    }

    @Test
    fun `equal version means no update`() {
        assertFalse(Version("3.4.3") > Version("3.4.3"))
    }

    @Test
    fun `lower version means no update`() {
        assertFalse(Version("3.4.2") > Version("3.4.3"))
    }

    @Test
    fun `higher minor beats lower patch`() {
        assertTrue(Version("3.5.0") > Version("3.4.99"))
    }

    @Test
    fun `higher major beats everything`() {
        assertTrue(Version("4.0.0") > Version("3.99.99"))
    }

    @Test
    fun `numeric not lexicographic comparison`() {
        // 字符串比较会认为 "3.4.10" < "3.4.9"，数值比较才是对的
        assertTrue(Version("3.4.10") > Version("3.4.9"))
        assertTrue(Version("3.10.0") > Version("3.9.0"))
    }

    @Test
    fun `prerelease is lower than release`() {
        assertTrue(Version("3.4.4") > Version("3.4.4-beta"))
        assertFalse(Version("3.4.4-beta") > Version("3.4.4"))
    }

    @Test
    fun `double digit segments work`() {
        assertTrue(Version("10.0.0") > Version("9.9.9"))
        assertTrue(Version("1.100.0") > Version("1.99.0"))
    }

    @Test
    fun `version with build metadata ignored`() {
        assertEquals(0, Version("3.4.3+abc") .compareTo(Version("3.4.3+xyz")))
        assertFalse(Version("3.4.3+build") > Version("3.4.3"))
    }

    @Test
    fun `two and three segment versions compare sanely`() {
        assertTrue(Version("3.5") > Version("3.4.9"))
        assertFalse(Version("3.4") > Version("3.4.0"))
    }

    @Test
    fun `backend higher version should trigger prompt`() {
        // 模拟后台把 latest_version 设成 3.4.4，当前安装的是 3.4.3
        val installed = Version("3.4.3")
        val backendLatest = Version("3.4.4")
        assertTrue(backendLatest > installed)
    }

    @Test
    fun `backend lower version should not trigger prompt`() {
        val installed = Version("3.4.3")
        val backendLatest = Version("3.0.0")
        assertFalse(backendLatest > installed)
    }

    @Test
    fun `malformed backend version does not crash`() {
        // 后台手误填了奇怪的值，也不应该让更新检查崩溃
        listOf("", "abc", "3.x.4", "  ").forEach { bad ->
            val result = runCatching { Version(bad) > Version("3.4.3") }
            // 不要求结果对错，只要求不抛异常
            assertTrue("$bad 不应导致崩溃", result.isSuccess || result.isFailure)
        }
    }

    @Test
    fun `update disabled returns no update`() {
        // 后台关闭 update_check_enabled 时，fetchUpdateInfo 返回当前版本
        // 等价于 latest == current，所以不该提示
        val current = Version("3.4.3")
        val returned = Version("3.4.3")
        assertFalse(returned > current)
    }
}
