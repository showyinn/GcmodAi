package com.gcmodai.ai.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「写完了还在续写」的回归测试。
 *
 * 这个 bug 的真实形态：模型正常收尾（写「以上就是全部内容」），
 * 但没有输出 `[DONE]`，旧判据只看标记 → 判定成没干完 → 一直续写到轮数上限。
 * 用户看到的就是「任务已经完成了它确还在继续」。
 */
class CompletionDetectorTest {

    private val markers = ContinuationPolicy.DEFAULT_DONE_MARKERS

    // ---- 信号一：显式标记 ----

    @Test
    fun `an explicit done marker counts as complete`() {
        assertTrue(CompletionDetector.isComplete("全部改完了。\n[DONE]", markers))
        assertTrue(CompletionDetector.isComplete("收工【任务完成】", markers))
    }

    @Test
    fun `a custom marker also counts`() {
        assertTrue(
            CompletionDetector.isComplete(
                "done here @@END@@",
                markers = listOf("@@END@@"),
            )
        )
    }

    // ---- 信号二：结尾收束语（这次 bug 的主因） ----

    @Test
    fun `a natural closing sentence counts as complete without any marker`() {
        val body = """
            我把三个页面的落地页都写完了，配色统一成暖色，导航固定在顶部。
            首页是产品介绍，第二页是定价，第三页是联系方式。
            以上就是全部内容，如需调整配色直接跟我说。
        """.trimIndent()

        assertTrue("自然收尾不该被当成没干完", CompletionDetector.isComplete(body, markers))
    }

    @Test
    fun `short closing phrases at the tail are recognised`() {
        assertTrue(CompletionDetector.isComplete("改好了，希望对你有帮助。", markers))
        assertTrue(CompletionDetector.isComplete("总结一下：三点都改完了。", markers))
        assertTrue(CompletionDetector.isComplete("有问题随时告诉我。", markers))
        assertTrue(CompletionDetector.isComplete("That's everything, let me know if you need more.", markers))
    }

    @Test
    fun `a mid-text transition is not mistaken for a closing`() {
        // 正文中间的「总结一下」是承上启下，后面还有大量内容，不能判成完成
        val body = "总结一下前面两步：先建目录，再装依赖。" +
            "接下来要做的是把构建脚本写完，然后跑一次完整构建，" +
            "确认产物落到 dist 目录，最后检查 index.html 是否存在并且引用的资源路径都是相对的。" +
            "这一步做完我们再回头看整体的目录结构是否合理。"

        assertFalse(CompletionDetector.isComplete(body, markers))
    }

    // ---- 信号三：没有新增进展 ----

    @Test
    fun `the same text as last round counts as complete`() {
        val text = "这是一段没有变化的内容，续写之后一个字都没多。"

        assertTrue(
            "续写没有任何新增，继续下去只是烧钱",
            CompletionDetector.isComplete(text, markers, previousText = text),
        )
    }

    @Test
    fun `genuinely new content keeps the loop going`() {
        val previous = "第一步：建目录。"
        val current = previous + "\n第二步：写构建脚本。\n第三步：跑构建。"

        assertFalse(
            "确实有新增内容，应该继续",
            CompletionDetector.isComplete(current, markers, previousText = previous),
        )
    }

    // ---- 信号四：高重复 ----

    @Test
    fun `a mostly repetitive continuation stops the loop`() {
        val previous = "第一段：说明为什么要先建目录。\n第二段：说明构建脚本怎么写。\n第三段：说明产物落在哪。\n"
        // 新增的部分几乎全是旧句子
        val added = listOf(
            "说明为什么要先建目录",
            "说明构建脚本怎么写",
            "说明产物落在哪",
            "说明为什么要先建目录",
        ).joinToString("\n")

        assertTrue(
            "新增内容高度重复，等于没有进展",
            CompletionDetector.isComplete(previous + added, markers, previousText = previous),
        )
    }

    @Test
    fun `two repeated sentences out of many is not enough to stop`() {
        val previous = "旧内容一。\n旧内容二。\n"
        val added = listOf(
            "旧内容一",
            "这是全新的第四步说明，内容完全不同且足够长",
            "这是全新的第五步说明，同样和前面无重叠",
            "这是全新的第六步说明，继续展开细节",
        ).joinToString("\n")

        assertFalse(CompletionDetector.isComplete(previous + added, markers, previousText = previous))
    }

    // ---- 边界 ----

    @Test
    fun `an empty body is not complete`() {
        // 空正文交给 EMPTY 触发器处理，这里不能返回「已完成」，
        // 否则断流场景会被误判成正常收工
        assertFalse(CompletionDetector.isComplete("", markers))
        assertFalse(CompletionDetector.isComplete("   \n  ", markers))
    }

    @Test
    fun `no markers configured still detects completion by closing phrases`() {
        // 用户把标记清空时，不能退化成「永远没干完」
        assertTrue(CompletionDetector.isComplete("以上就是全部内容。", emptyList()))
        assertFalse(CompletionDetector.isComplete("接下来还有第三步和第四步没做，先继续往下写构建脚本。", emptyList()))
    }

    @Test
    fun `unfinished mid-work text does not look complete`() {
        val body = "现在开始写第二个页面，先搭骨架，再补样式，" +
            "然后处理响应式断点，最后接入导航。"

        assertFalse(CompletionDetector.isComplete(body, markers))
    }
}
