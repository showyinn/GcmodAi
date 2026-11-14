package com.gcmodai.app.data.tutorial

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 教程内容自检。
 *
 * 为什么不 mock 一份 JSON 来测：这段内容会通过热更新发到线上，坏掉的表现是
 * 用户看到空教程页 —— 没有任何崩溃、没有任何日志，静默失效。
 * 所以这里直接读仓库里真实的 assets/tutorial.json，
 * 结构不对、必填字段漏了、id 重复，构建期就挂掉，不会等用户发现。
 */
class TutorialContentTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun realTutorialFile(): File {
        // 单元测试的运行目录是模块根（app/），按相对路径找真实资产
        val candidates = listOf(
            File("src/main/assets/tutorial.json"),
            File("app/src/main/assets/tutorial.json"),
            File("../app/src/main/assets/tutorial.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 tutorial.json，实际工作目录=${File("").absolutePath}")
    }

    private fun loadReal(): Tutorial =
        json.decodeFromString(Tutorial.serializer(), realTutorialFile().readText())

    @Test
    fun `the shipped tutorial parses`() {
        val tutorial = loadReal()

        assertTrue("必须有版本号，热更新靠它判断新旧", tutorial.version.isNotBlank())
        assertTrue("至少要有开局那章", tutorial.chapters.isNotEmpty())
    }

    @Test
    fun `chapter and entry ids are unique and non blank`() {
        val tutorial = loadReal()

        val ids = tutorial.chapters.map { it.id }
        assertEquals("章节 id 不能重复", ids.size, ids.distinct().size)
        assertTrue("章节 id 不能为空", ids.all { it.isNotBlank() })

        tutorial.chapters.forEach { chapter ->
            assertTrue("${chapter.id} 标题不能为空", chapter.title.isNotBlank())
            assertTrue("${chapter.id} 至少要有一条内容", chapter.entries.isNotEmpty())
            chapter.entries.forEach { entry ->
                assertTrue("${chapter.id} 里有条目标题为空", entry.title.isNotBlank())
                assertTrue("${chapter.id} 里有条目正文为空", entry.body.isNotBlank())
            }
        }
    }

    @Test
    fun `every feature area we ship is covered`() {
        val text = loadReal().chapters.joinToString("\n") { chapter ->
            chapter.title + "\n" + chapter.entries.joinToString("\n") { it.title + it.body + it.where + it.tip }
        }

        // 这些是产品里真实存在、用户会找不到的功能，教程里漏掉任何一个都算内容缺口
        val mustMention = listOf(
            "任务模式", "续写", "工作区", "SSH", "ffmpeg", "APK 加固", "SO 分析",
            "MCP", "搜索", "记忆", "提示词注入", "世界书", "备份", "签到",
            "账单", "密钥", "分组", "热更新",
        )
        val missing = mustMention.filterNot { text.contains(it, ignoreCase = true) }
        assertTrue("教程缺少这些功能的说明: $missing", missing.isEmpty())
    }

    @Test
    fun `the task mode entry points at the chat panel, not the settings page`() {
        val tutorial = loadReal()
        val entry = tutorial.chapters
            .flatMap { it.entries }
            .first { it.title.contains("任务模式") }

        // 开关的主入口搬到了聊天页 + 号面板，教程里必须跟着改，否则用户按说明找不到
        assertTrue("任务模式的 where 应该指向 + 号面板: ${entry.where}", entry.where.contains("+ 号"))
        assertFalse("不该再写「设置 → 模型」作为主入口: ${entry.where}", entry.where.contains("设置 → 模型"))
    }

    @Test
    fun `fallback content is always usable`() {
        val fallback = BuiltinTutorial.FALLBACK

        assertTrue(fallback.chapters.isNotEmpty())
        assertTrue(fallback.chapters.all { it.entries.isNotEmpty() })
    }
}
