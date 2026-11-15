package com.gcmodai.ai.agent

/**
 * 判断这一轮回复是不是**真的已经完成**。
 *
 * 为什么需要它：3.9.35–3.9.38 的 `UNFINISHED` 判据只有一条——
 * 「输出里有没有 `[DONE]` 之类的完成标记」。模型自然收尾时（写「以上就是全部内容」
 * 「如需进一步调整请告诉我」）根本不会输出标记，于是被判定成没干完，
 * 一路续写到轮数上限。用户看到的现象就是：**明明写完了还在继续**。
 *
 * 这个判定器不依赖模型配合，靠三个客观信号：
 *
 * 1. **显式标记**：模型真的按提示输出了 `[DONE]`，最确定的信号；
 * 2. **收束语**：结尾出现「以上就是」「总结一下」「如需…请告诉我」这类
 *    人类写作里天然收尾的句子，说明这一段已经讲完了；
 * 3. **没有进展**：和上一轮相比没有新增可见内容，或者新内容和已写高度重复，
 *    说明续写没有带来任何价值，继续下去只是烧额度。
 *
 * 三者任意命中即视为完成。全部不命中才允许续写。
 */
object CompletionDetector {

    /**
     * 收束语：出现在**结尾附近**才算数。
     *
     * 为什么限定结尾：正文中间出现「总结一下」是引出下一段，不是收尾；
     * 只看最后若干字符才符合人类写作习惯。
     */
    private val CLOSING_PHRASES = listOf(
        "以上就是", "以上是全部", "上面就是", "这就是全部",
        "总结一下", "总结如下", "最后总结", "一句话总结",
        "希望对你有帮助", "希望这对你有帮助", "希望有帮助",
        "如需", "如果需要", "若有需要", "有问题随时", "随时告诉我",
        "还有别的需要", "还需要什么", "可以继续问我", "欢迎继续提问",
        "完成。", "已完成。", "到此为止", "就先到这里", "先写到这里",
        "here's the complete", "that's everything", "let me know if",
        "feel free to ask", "hope this helps",
    )

    /**
     * 结尾取样长度：只在这些字符内找收束语。
     *
     * 取 90 而不是几百：实测 220 会把正文中段那句「总结一下前面两步」
     * 也纳进采样，于是「承上启下」被当成「收尾」，长任务被提前判完成。
     * 90 个字符大致是最后两句，正好覆盖人类写作的真实收尾位置。
     */
    private const val TAIL_LENGTH = 90

    /**
     * 这一轮是否已经完成。
     *
     * @param text 整条助手消息的可见正文（不含推理）
     * @param markers 用户配置的完成标记
     * @param previousText 上一轮结束时的正文，用于判断有没有新增进展；首次生成传 null
     */
    fun isComplete(
        text: String,
        markers: List<String>,
        previousText: String? = null,
    ): Boolean {
        val body = text.trim()

        // 一个字都没有：不是「完成」，是没开始，交给 EMPTY 那条去处理
        if (body.isEmpty()) return false

        // 1) 显式标记
        if (markers.any { it.isNotBlank() && it in body }) return true

        // 2) 这一轮没有任何新增内容 —— 续写等于原地踏步，没理由继续
        if (previousText != null && body == previousText.trim()) return true

        // 3) 结尾收束语
        if (hasClosingPhrase(body)) return true

        // 4) 新增内容与已有内容高度重复
        if (previousText != null) {
            val added = body.removePrefix(previousText.trim())
            if (added.isNotBlank() && isMostlyRepetition(added, previousText)) return true
        }

        return false
    }

    private fun hasClosingPhrase(body: String): Boolean {
        // 只看结尾：正文中间的「总结一下」是承上启下，不是收尾
        val tail = body.takeLast(TAIL_LENGTH).lowercase()
        return CLOSING_PHRASES.any { it.lowercase() in tail }
    }

    /**
     * 新增的那段是不是主要在重复前面已经写过的内容。
     *
     * 判据用「新增内容里有多少比例的短句在旧内容里出现过」。
     * 阈值 0.8 是保守取值：宁可多续一轮，也不要把正常的补充说明打断。
     */
    private fun isMostlyRepetition(added: String, previous: String): Boolean {
        val sentences = added
            .split('\n', '。', '！', '？', '；', '.', '!', '?', ';')
            .map { it.trim() }
            .filter { it.length >= 8 }
        if (sentences.size < 3) return false

        val old = previous.trim()
        val repeated = sentences.count { old.contains(it) }
        return repeated.toDouble() / sentences.size >= 0.8
    }
}
