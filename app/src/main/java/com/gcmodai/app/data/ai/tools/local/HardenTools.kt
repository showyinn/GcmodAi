package com.gcmodai.app.data.ai.tools.local

import android.content.Context
import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.harden.ApkProfiler
import com.gcmodai.app.data.harden.HardenAdvisor
import com.gcmodai.app.data.harden.HardenClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * AI 可直接调用的加固工具。
 *
 * 设计原则：**AI 负责判断与解释，用户负责拍板。**
 *
 * 三个工具分工：
 * 1. `harden_analyze_apk` —— 读 APK 特征，给推荐方案 + 理由 + 代价 + 三档备选。**不提交任务**。
 * 2. `harden_run` —— 按用户确认后的参数真正提交，返回任务 ID。
 * 3. `harden_status` —— 查任务进度或列最近任务。
 *
 * 为什么把分析与执行拆开：加固不可逆（要重签），让 AI 直接提交是危险的。
 * 先分析、讲理由、等用户确认，再执行。
 */
object HardenTools {

    fun all(context: Context, client: HardenClient): List<Tool> = listOf(
        analyzeTool(client),
        runTool(context, client),
        statusTool(client),
    )

    // ---------------------------------------------------------------- 分析

    private fun analyzeTool(client: HardenClient): Tool = Tool(
        name = "harden_analyze_apk",
        description = buildString {
            appendLine("分析一个 APK 并给出加固方案建议。")
            appendLine()
            appendLine("**使用时机**：用户提到「加固」「加壳」「防破解」「保护 APK」「混淆我的包」时，先调这个工具。")
            appendLine()
            appendLine("返回：APK 客观特征（体积 / DEX 数 / SO 数 / ABI / 包名）、推荐方案与逐条理由、")
            appendLine("预计代价、三档备选（最保守 / 均衡 / 最强）。")
            appendLine()
            appendLine("**重要**：本工具只分析，不提交任务。拿到结果后你必须：")
            appendLine("1. 把推荐方案和理由用大白话讲给用户")
            appendLine("2. 说明预计耗时和代价")
            appendLine("3. 问用户选哪一档（或是否接受推荐）")
            appendLine("4. 用户明确确认后才调 harden_run")
            appendLine()
            appendLine("apk_path 必须是设备上真实存在的 .apk 文件绝对路径。")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("apk_path", buildJsonObject {
                        put("type", "string")
                        put("description", "APK 文件的绝对路径，例如 /storage/emulated/0/Download/app.apk")
                    })
                },
                required = listOf("apk_path"),
            )
        },
        execute = { args ->
            val file = requireApk(args.string("apk_path"))
            val profile = ApkProfiler.profile(file.readBytes())
            val rec = HardenAdvisor.recommend(profile)
            val text = HardenAdvisor.renderForAi(profile, rec)

            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine(text)
                        appendLine()
                        appendLine("【下一步】把上面的推荐方案讲给用户，说明理由和代价，")
                        appendLine("问用户选「最保守 / 均衡（推荐）/ 最强」哪一档。")
                        appendLine("用户确认后再调 harden_run，不要在确认前直接提交。")
                    }
                )
            )
        },
    )

    // ---------------------------------------------------------------- 执行

    private fun runTool(context: Context, client: HardenClient): Tool = Tool(
        name = "harden_run",
        description = buildString {
            appendLine("提交 APK 加固任务。")
            appendLine("**必须先在 harden_analyze_apk 之后、且用户明确确认方案后才调用。**")
            appendLine()
            appendLine("除 apk_path 外都可省略，省略时用推荐默认值：")
            appendLine("- engine: classic（只瘦身）/ xop（只加壳）/ both（瘦身+加壳，推荐）")
            appendLine("- xop_profile: balanced / industry / aggressive / perf / max")
            appendLine("- protect_so_mode: off / safe / aggressive / max")
            appendLine("- string_fog: off / app / app_plus / all_but_sdk / custom")
            appendLine("- string_fog_rules: 当 string_fog=custom 时生效，每行一条，+包含 / -排除")
            appendLine("- anti_debug / root_check / hook_check / proxy_check: true 或 false")
            appendLine("- rasp_action: report（只上报）/ exit（检测到即退出）")
            appendLine()
            appendLine("返回任务 ID。之后用 harden_status 查进度。")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("apk_path", buildJsonObject {
                        put("type", "string")
                        put("description", "APK 绝对路径")
                    })
                    put("engine", buildJsonObject {
                        put("type", "string")
                        put("description", "classic | xop | both")
                    })
                    put("xop_profile", buildJsonObject {
                        put("type", "string")
                        put("description", "balanced | industry | aggressive | perf | max")
                    })
                    put("protect_so_mode", buildJsonObject {
                        put("type", "string")
                        put("description", "off | safe | aggressive | max")
                    })
                    put("string_fog", buildJsonObject {
                        put("type", "string")
                        put("description", "off | app | app_plus | all_but_sdk | custom")
                    })
                    put("string_fog_rules", buildJsonObject {
                        put("type", "string")
                        put("description", "string_fog=custom 时的规则，换行分隔")
                    })
                    put("anti_debug", buildJsonObject { put("type", "boolean") })
                    put("root_check", buildJsonObject { put("type", "boolean") })
                    put("hook_check", buildJsonObject { put("type", "boolean") })
                    put("proxy_check", buildJsonObject { put("type", "boolean") })
                    put("rasp_action", buildJsonObject {
                        put("type", "string")
                        put("description", "report | exit")
                    })
                },
                required = listOf("apk_path"),
            )
        },
        execute = { args ->
            val file = requireApk(args.string("apk_path"))
            val profile = ApkProfiler.profile(file.readBytes())
            val rec = HardenAdvisor.recommend(profile)

            val engine = args.string("engine").ifBlank { rec.engine }
            val xopProfile = args.string("xop_profile").ifBlank { rec.xopProfile }
            val soMode = args.string("protect_so_mode").ifBlank { rec.protectSoMode }
            val fogKey = args.string("string_fog").ifBlank { rec.stringFogPreset }
            val customRules = args.string("string_fog_rules")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }

            val fogLines = when (fogKey) {
                "off" -> emptyList()
                "app" -> listOf("+com.gcmodai.app.*")
                "app_plus" -> listOf(
                    "+com.gcmodai.app.*",
                    "+com.gcmodai.ai.*",
                    "+com.gcmodai.workspace.*",
                    "+com.gcmodai.search.*",
                )
                "all_but_sdk" -> listOf(
                    "+*",
                    "-com.google.*",
                    "-androidx.*",
                    "-kotlin.*",
                    "-kotlinx.*",
                    "-okhttp3.*",
                    "-okio.*",
                    "-org.jetbrains.*",
                )
                "custom" -> customRules
                else -> listOf("+com.gcmodai.app.*")
            }

            val options = HardenClient.Options(
                mode = if (engine == "classic") "deep" else "standard",
                stripSmaliDebug = true,
                stripSo = true,
                renameClasses = false,
                antiDebug = args.flag("anti_debug"),
                rootCheck = args.flag("root_check"),
                hookCheck = args.flag("hook_check"),
                proxyCheck = args.flag("proxy_check"),
                stringFogRules = fogLines,
                abis = profile.abis,
                engine = engine,
                xopProfile = xopProfile,
                protectSoMode = soMode,
                soDecryptMode = rec.soDecryptMode,
                encryptAssets = rec.encryptAssets,
                resProtect = rec.resProtect,
                raspAction = args.string("rasp_action").ifBlank { rec.raspAction },
            )

            val bytes = file.readBytes()
            val jobId = client.submit(bytes, options).getOrElse {
                error("提交失败：${it.message}")
            }

            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine("已提交加固任务")
                        appendLine("- 任务 ID：$jobId")
                        appendLine("- 方案：engine=$engine / 空壳=$xopProfile / SO=$soMode / 字符串加密=$fogKey")
                        appendLine("- 文件：${file.name}（${file.length() / 1048576} MB）")
                        appendLine()
                        appendLine("接下来用 harden_status 查进度。完成后结果在「存储管理 → 加固包」。")
                    }
                )
            )
        },
    )

    // ---------------------------------------------------------------- 查状态

    private fun statusTool(client: HardenClient): Tool = Tool(
        name = "harden_status",
        description = buildString {
            appendLine("查询加固任务进度，或列出最近的任务。")
            appendLine()
            appendLine("- 传 job_id：查这一个任务的详细状态与最近日志")
            appendLine("- 不传：列出最近 20 个任务")
            appendLine()
            appendLine("状态：queued 排队 / running 处理中 / done 已完成 / error 失败。")
            appendLine("state=done 时告诉用户去「存储管理 → 加固包」取件。")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_id", buildJsonObject {
                        put("type", "string")
                        put("description", "任务 ID，省略则列出最近任务")
                    })
                },
            )
        },
        execute = { args ->
            val id = args.string("job_id")
            if (id.isBlank()) {
                val jobs = client.listJobs().getOrElse { error("查询失败：${it.message}") }
                if (jobs.isEmpty()) {
                    listOf(UIMessagePart.Text("还没有加固任务。"))
                } else {
                    listOf(
                        UIMessagePart.Text(
                            buildString {
                                appendLine("最近 ${jobs.size} 个任务：")
                                jobs.take(20).forEach { j ->
                                    val size = j.size?.let { " · ${it / 1024} KB" } ?: ""
                                    val el = if (j.elapsed > 0) " · ${j.elapsed}s" else ""
                                    appendLine("- ${j.id.take(8)} · ${stateCn(j.state)}$size$el")
                                }
                            }
                        )
                    )
                }
            } else {
                val st = client.status(id).getOrElse { error("查询失败：${it.message}") }
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("任务 ${id.take(8)}")
                            appendLine("- 状态：${stateCn(st.state)}")
                            if (st.elapsed > 0) appendLine("- 已用：${st.elapsed}s")
                            st.size?.let { appendLine("- 结果大小：${it / 1024} KB") }
                            if (st.log.isNotEmpty()) {
                                appendLine()
                                appendLine("最近日志：")
                                st.log.takeLast(12).forEach { appendLine("  $it") }
                            }
                            if (st.state == "done") {
                                appendLine()
                                appendLine("已完成。结果在「存储管理 → 加固包」，可以直接安装测试。")
                            }
                        }
                    )
                )
            }
        },
    )

    // ---- 小工具 ----

    private fun requireApk(path: String): File {
        if (path.isBlank()) error("缺少 apk_path")
        val file = File(path)
        if (!file.isFile) error("找不到文件：$path")
        if (!path.endsWith(".apk", ignoreCase = true)) error("不是 .apk 文件：$path")
        if (file.length() > 240L * 1024 * 1024) {
            error("文件超过 240MB 上限：${file.length() / 1048576} MB")
        }
        return file
    }

    private fun stateCn(state: String): String = when (state) {
        "queued" -> "排队中"
        "running" -> "处理中"
        "done" -> "已完成"
        "error", "failed" -> "失败"
        else -> state
    }

    private fun JsonElement.obj(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun JsonElement.string(key: String): String =
        runCatching { obj()?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun JsonElement.flag(key: String): Boolean =
        runCatching { obj()?.get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
}
