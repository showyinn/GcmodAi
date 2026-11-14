package com.gcmodai.app.data.tutorial

/**
 * 代码里的最后兜底。
 *
 * 只在「热更新包没带 tutorial.json」且「APK 内置 assets 也读不出来」时才会用到 ——
 * 正常情况下用户永远看到的是 assets 里那份完整教程。
 * 这里刻意保持极小：它存在的意义是让教程页永远有内容可显示，
 * 而不是替代完整教程。
 */
object BuiltinTutorial {
    val FALLBACK = Tutorial(
        version = "fallback",
        title = "新手指南",
        subtitle = "教程内容暂时读不出来，先看这几条通用的。",
        chapters = listOf(
            TutorialChapter(
                id = "fallback",
                title = "先做这三件事",
                summary = "任何情况下都需要的基础配置。",
                icon = "rocket",
                entries = listOf(
                    TutorialEntry(
                        title = "登录账号",
                        where = "设置 → 我的账号",
                        body = "账号与额度来自 gcmod.online。没登录时大部分功能会置灰。",
                    ),
                    TutorialEntry(
                        title = "创建并选中 API 密钥",
                        where = "设置 → 我的账号 → 密钥",
                        body = "密钥是计费凭据，创建后自动选中。",
                    ),
                    TutorialEntry(
                        title = "选一个聊天模型",
                        where = "设置 → 模型设置",
                        body = "模型列表由官网同步，点一个即选中；不确定就选带「自动」的。",
                    ),
                ),
            )
        ),
    )
}
