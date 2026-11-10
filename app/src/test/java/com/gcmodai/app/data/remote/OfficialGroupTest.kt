package com.gcmodai.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方群加入方式决策验证。
 *
 * 线上配置：唯一群号 345994931，唯一分享链接（QQ universal-share）。
 */
class OfficialGroupTest {

    private val realUrl =
        "https://qun.qq.com/universal-share/share?ac=1" +
            "&authKey=2NZI7SsAI6ZE2MG2Bi0NJCYybqXSH0S3T2RfqMHUqC69jQJrbkZwXPIm2%2FrUq%2B7P" +
            "&busi_data=eyJncm91cENvZGUiOiIzNDU5OTQ5MzEiLCJ0b2tlbiI6InJRMXc5T2pRU3RMUWZmVDlTREtZ" +
            "bXlaWGFMeUdZaFh3THpCZ0VMNGl4b3ZWME0rZEFMRUVxa3RXM3M1cTlTeUUiLCJ1aW4iOiIxMTIyMDE4" +
            "NDQifQ%3D%3D" +
            "&data=hXmcXy4Iafw_ZghFQ-f-gMDwSkC65Kn7tCMwVTKqcXKySJLsZV_6qOPZPf1vqG8GW1IV3bGH5Hu" +
            "CmDgRJMxl0w&svctype=4&tempid=h5_group_info"

    private val number = "345994931"

    @Test
    fun `link takes priority over number`() {
        // 有链接就必须走链接，因为点一下能直接进群
        val action = OfficialGroup.resolve(realUrl, "", number)
        assertTrue(action is GroupJoinAction.OpenUrl)
        assertEquals(realUrl, (action as GroupJoinAction.OpenUrl).url)
    }

    @Test
    fun `real qq link is preserved byte for byte`() {
        // 链接里的 %2F %3D 是 QQ 服务端生成的，绝不能被重新编码
        val action = OfficialGroup.resolve(realUrl, "", number) as GroupJoinAction.OpenUrl
        assertEquals(realUrl, action.url)
        assertTrue(action.url.contains("%2F"))
        assertTrue(action.url.contains("%3D"))
        assertFalse(action.url.contains("%25"))
    }

    @Test
    fun `falls back to copying number when no link`() {
        val action = OfficialGroup.resolve("", "", number)
        assertEquals(GroupJoinAction.CopyNumber(number), action)
    }

    @Test
    fun `qr used when link missing`() {
        val action = OfficialGroup.resolve("", "https://example.com/qr.png", number)
        assertTrue(action is GroupJoinAction.OpenUrl)
        assertEquals("https://example.com/qr.png", (action as GroupJoinAction.OpenUrl).url)
    }

    @Test
    fun `nothing configured yields Nothing`() {
        assertEquals(GroupJoinAction.Nothing, OfficialGroup.resolve("", "", ""))
    }

    @Test
    fun `blank whitespace treated as empty`() {
        assertEquals(GroupJoinAction.Nothing, OfficialGroup.resolve("  ", " ", "   "))
        val action = OfficialGroup.resolve("   ", "", " $number ")
        assertEquals(GroupJoinAction.CopyNumber(number), action)
    }

    @Test
    fun `availability matches resolve`() {
        assertTrue(OfficialGroup.isAvailable(realUrl, "", number))
        assertTrue(OfficialGroup.isAvailable("", "", number))
        assertFalse(OfficialGroup.isAvailable("", "", ""))
    }

    @Test
    fun `subtitle hides number when link exists`() {
        // 有链接时再显示群号是噪音，用户会犹豫该点哪
        val sub = OfficialGroup.subtitle("点击加入官方群", realUrl, "", number)
        assertEquals("点击加入官方群", sub)
        assertFalse(sub.contains(number))
    }

    @Test
    fun `subtitle shows copy hint when only number`() {
        val sub = OfficialGroup.subtitle("官方交流", "", "", number)
        assertEquals("官方交流 · 群号 $number（点击复制）", sub)
    }

    @Test
    fun `subtitle with empty desc still usable`() {
        val sub = OfficialGroup.subtitle("", "", "", number)
        assertEquals("群号 $number（点击复制）", sub)
    }

    @Test
    fun `only one group is expressible`() {
        // 本结构只承载一个群：一个 url、一个 qr、一个 number，没有列表
        val a = OfficialGroup.resolve(realUrl, "", number)
        val b = OfficialGroup.resolve(realUrl, "", number)
        assertEquals(a, b)
    }
}
