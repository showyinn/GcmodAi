package com.gcmodai.app.data.remote

/**
 * 官方群加入方式的决策逻辑。
 *
 * 抽成纯函数的原因：这里是「用户点一下到底发生什么」的全部判断，
 * 涉及链接回退、剪贴板、无可用方式三种结局，写成 Compose 里的 if 链
 * 就没法测了，而这恰恰是最容易出错的地方。
 */
sealed interface GroupJoinAction {
    /** 用浏览器/QQ 打开这个链接。 */
    data class OpenUrl(val url: String) : GroupJoinAction

    /** 没有可用链接，把群号复制到剪贴板，提示用户自己去 QQ 搜。 */
    data class CopyNumber(val number: String) : GroupJoinAction

    /** 后台什么都没配，点了不做事。 */
    data object Nothing : GroupJoinAction
}

object OfficialGroup {

    /**
     * 决定点击官方群时该做什么。
     *
     * 优先级：分享链接 > 二维码图片地址 > 复制群号。
     *
     * 为什么链接优先：QQ 的 `qun.qq.com/universal-share/share` 链接带
     * `authKey` 和 `busi_data`，点了能直接进群，比让用户复制群号再搜快得多。
     */
    fun resolve(url: String, qr: String, number: String): GroupJoinAction {
        val link = url.trim()
        if (link.isNotEmpty()) return GroupJoinAction.OpenUrl(link)

        val qrLink = qr.trim()
        if (qrLink.isNotEmpty()) return GroupJoinAction.OpenUrl(qrLink)

        val num = number.trim()
        if (num.isNotEmpty()) return GroupJoinAction.CopyNumber(num)

        return GroupJoinAction.Nothing
    }

    /**
     * 是否该显示官方群入口。
     *
     * 只要有一种加入方式就算可用；`supportContact` 不参与判断，
     * 因为客服联系方式可能是邮箱或其他渠道，不代表有群。
     */
    fun isAvailable(url: String, qr: String, number: String): Boolean =
        resolve(url, qr, number) != GroupJoinAction.Nothing

    /**
     * 副标题文案。
     *
     * 只在「没有链接、只能复制群号」时展示群号——有链接时群号是冗余信息，
     * 反而让用户不知道该点哪里。
     */
    fun subtitle(desc: String, url: String, qr: String, number: String): String {
        val action = resolve(url, qr, number)
        val parts = mutableListOf<String>()
        if (desc.isNotBlank()) parts += desc.trim()
        if (action is GroupJoinAction.CopyNumber) {
            parts += "群号 ${action.number}（点击复制）"
        }
        return parts.joinToString(" · ")
    }
}
