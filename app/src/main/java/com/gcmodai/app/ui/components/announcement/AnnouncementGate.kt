package com.gcmodai.app.ui.components.announcement

import com.gcmodai.app.data.remote.RemoteConfig

/**
 * 公告是否该弹出来的判定。
 *
 * 抽成纯函数是为了能单元测试——上一版公告的 bug 正是「写了开关变量却
 * 没接到任何显示条件上」，这种错只有测试能挡住。
 */
object AnnouncementGate {

    /**
     * @param config 远程配置
     * @param lastReadSignature 上一次用户点「知道了」时那条公告的签名
     */
    fun shouldShow(config: RemoteConfig, lastReadSignature: String?): Boolean {
        if (!config.announcementEnabled) return false

        val text = config.announcement
        if (text.isBlank()) return false

        // 没读过 → 弹；读过的签名和当前不一致（管理员改了内容）→ 也弹
        return lastReadSignature != signature(config)
    }

    /**
     * 公告签名：标题无关，只看正文内容。
     *
     * 为什么不用「公告版本号」而用内容哈希：
     * 管理员改公告时不会记得去升版本号，用内容哈希才能保证
     * 「改了就重新弹，没改就永远不重复弹」。
     */
    fun signature(config: RemoteConfig): String = contentSignature(config.announcement)

    /** 对正文取一个稳定短哈希（不依赖 Java 的 String.hashCode 实现细节）。 */
    internal fun contentSignature(text: String): String {
        // FNV-1a 32bit：实现简单、跨平台稳定，足够做公告去重
        var hash = 0x811C9DC5.toInt()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return "%08x".format(hash)
    }
}
