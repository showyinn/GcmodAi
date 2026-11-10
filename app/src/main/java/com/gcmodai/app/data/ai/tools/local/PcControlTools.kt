package com.gcmodai.app.data.ai.tools.local

import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.repository.WorkspaceRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream

/**
 * 电脑控制工具集：让 AI 在用户**整台电脑**上干活。
 *
 * ## 和 SSH 远程建站的区别
 *
 * - `ssh_*`：只在工作区目录（remoteDir）里读写，安全但受限
 * - `pc_*` ：可以操作任意绝对路径、在任意目录执行命令、查系统信息
 *
 * 也就是从"AI 只能往我的网站目录写文件"升级成
 * "AI 能帮我在电脑上装机、配环境、跑脚本、改配置"。
 *
 * ## 安全边界（重要）
 *
 * 1. 只能操作**用户已配置并保存**的 SSH 机器，无法凭空连任意主机
 * 2. 主机指纹首次确认后锁定，指纹变化会拒绝连接（防中间人）
 * 3. 破坏性命令由 [ShellGuard] 拦截（删根、格式化、关机、改账号……）
 * 4. 凭据在 Android Keystore 里加密，工具拿不到明文
 */
internal object PcControlTools {

    fun all(repo: WorkspaceRepository): List<Tool> = listOf(
        listComputersTool(repo),
        systemInfoTool(repo),
        execTool(repo),
        listDirTool(repo),
        readFileTool(repo),
        writeFileTool(repo),
    )

    /* ------------------------------ 列出可控制的电脑 ------------------------------ */

    private fun listComputersTool(repo: WorkspaceRepository): Tool = Tool(
        name = "pc_list_computers",
        description = """
            List the computers the user has connected (via SSH) and that you are
            allowed to control. Call this first to get a workspace_id, then use
            pc_system_info / pc_exec / pc_read_file / pc_write_file on that id.
            You can only operate on machines from this list.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { })
        },
        execute = {
            val machines = runCatching { repo.listSshWorkspacesNow() }.getOrElse { emptyList() }
            val text = if (machines.isEmpty()) {
                "No computer connected yet. Ask the user to add one in " +
                    "Settings -> 工作区与建站 -> SSH 远程服务器, then retry."
            } else {
                buildString {
                    appendLine("Connected computers (${machines.size}):")
                    machines.forEach { m ->
                        appendLine("  workspace_id = ${m.id}")
                        appendLine("    name       = ${m.name}")
                        appendLine("    address    = ${m.address}")
                        appendLine("    homeDir    = ${m.remoteDir}")
                    }
                    append("Use one of these workspace_id values in the other pc_* tools.")
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 系统信息 ------------------------------ */

    private fun systemInfoTool(repo: WorkspaceRepository): Tool = Tool(
        name = "pc_system_info",
        description = """
            Show basic system information of the controlled computer: OS, kernel,
            hostname, current user, disk usage, memory. Use this before installing
            anything so you know what you are working with.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "workspace_id from pc_list_computers")
                    }
                },
                required = listOf("workspace_id"),
            )
        },
        execute = { input ->
            val id = input.jsonObject["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val text = if (id.isBlank()) {
                "Missing required parameter: workspace_id"
            } else {
                // 全用只读命令，跨 Linux/macOS 基本通用；Windows 下会自然报错，模型可据此判断
                val command = listOf(
                    "echo '--- os ---'", "uname -a",
                    "echo '--- host ---'", "hostname",
                    "echo '--- whoami ---'", "whoami; id 2>/dev/null",
                    "echo '--- pwd ---'", "pwd",
                    "echo '--- disk ---'", "df -h 2>/dev/null | head -20",
                    "echo '--- mem ---'", "free -h 2>/dev/null || vm_stat 2>/dev/null | head -10",
                    "echo '--- cpu ---'", "nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null",
                ).joinToString("\n")
                runCatching {
                    val r = repo.executeCommand(id, command)
                    buildString {
                        if (r.stdout.isNotBlank()) appendLine(r.stdout.take(8000))
                        if (r.stderr.isNotBlank()) {
                            appendLine("--- stderr ---")
                            appendLine(r.stderr.take(2000))
                        }
                        appendLine("exit=${r.exitCode}")
                    }.trim()
                }.getOrElse { "Failed to read system info: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 执行命令 ------------------------------ */

    private fun execTool(repo: WorkspaceRepository): Tool = Tool(
        name = "pc_exec",
        description = """
            Run a shell command on the user's computer, in any directory.
            Use it to install packages, configure services, build projects, run
            scripts — anything you would do in a terminal on that machine.
            Destructive commands (rm -rf /, mkfs, shutdown, userdel …) are refused;
            ask the user to run those manually.
            Prefer cwd to change directory instead of chaining `cd`.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "workspace_id from pc_list_computers")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Shell command to run")
                    }
                    putJsonObject("cwd") {
                        put("type", "string")
                        put("description", "Absolute directory to run in. Empty = workspace home dir")
                    }
                },
                required = listOf("workspace_id", "command"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val command = obj["command"]?.jsonPrimitive?.content.orEmpty()
            val cwd = obj["cwd"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: workspace_id"
                command.isBlank() -> "Missing required parameter: command"
                ShellGuard.isDangerous(command) -> ShellGuard.rejectionMessage(command)
                else -> runCatching {
                    val r = repo.executeCommand(id, command, cwd)
                    buildString {
                        appendLine("exit=${r.exitCode}")
                        if (r.timedOut) appendLine("(timed out)")
                        if (r.stdout.isNotBlank()) {
                            appendLine("--- stdout ---")
                            appendLine(r.stdout.take(12000))
                        }
                        if (r.stderr.isNotBlank()) {
                            appendLine("--- stderr ---")
                            appendLine(r.stderr.take(6000))
                        }
                    }.trim()
                }.getOrElse { "Command failed: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 列目录（绝对路径） ------------------------------ */

    private fun listDirTool(repo: WorkspaceRepository): Tool = Tool(
        name = "pc_list_dir",
        description = """
            List a directory on the user's computer by absolute path,
            e.g. /var/www, C:/Users/name/Documents, ~/projects.
            Unlike ssh_list_files this is not limited to the workspace folder.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "workspace_id from pc_list_computers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Absolute directory path. Empty = home dir")
                    }
                },
                required = listOf("workspace_id"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: workspace_id"
                else -> {
                    // ls -la 在 Linux/macOS 通用；路径为空就列当前工作目录
                    val target = if (path.isBlank()) "." else path
                    val command = "ls -la " + shellQuote(target)
                    runCatching {
                        val r = repo.executeCommand(id, command)
                        if (r.exitCode != 0 && r.stderr.isNotBlank()) {
                            "Failed to list '$target': ${r.stderr.take(1500)}"
                        } else {
                            r.stdout.take(12000)
                        }
                    }.getOrElse { "Failed to list: ${it.message}" }
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 读文件（绝对路径） ------------------------------ */

    private fun readFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "pc_read_file",
        description = """
            Read a text file on the user's computer by absolute path.
            Use it to inspect configs, logs or source files anywhere on the machine.
            Very large files are truncated.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "workspace_id from pc_list_computers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Absolute file path")
                    }
                },
                required = listOf("workspace_id", "path"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: workspace_id"
                path.isBlank() -> "Missing required parameter: path"
                else -> runCatching {
                    val size = runCatching { repo.absoluteFileSize(id, path) }.getOrDefault(0L)
                    if (size > MAX_READ_BYTES) {
                        return@runCatching "File is $size bytes, too large to inline. " +
                            "Use pc_exec with `head -200 $path` or `tail -200 $path` instead."
                    }
                    val buffer = ByteArrayOutputStream()
                    repo.exportAbsoluteFile(id, path, buffer)
                    val content = buffer.toString(Charsets.UTF_8.name())
                    "File: $path ($size bytes)\n--- begin ---\n$content\n--- end ---"
                }.getOrElse { "Failed to read: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 写文件（绝对路径） ------------------------------ */

    private fun writeFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "pc_write_file",
        description = """
            Create or overwrite a text file on the user's computer by absolute path.
            Use it to write configs, scripts or source files anywhere on the machine.
            Parent directories must exist — use pc_exec `mkdir -p` first if needed.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "workspace_id from pc_list_computers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Absolute file path")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Full file content")
                    }
                },
                required = listOf("workspace_id", "path", "content"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: workspace_id"
                path.isBlank() -> "Missing required parameter: path"
                else -> runCatching {
                    val entry = repo.writeAbsoluteText(id, path, content, overwrite = true)
                    "Wrote ${entry.name} on the computer (${content.toByteArray().size} bytes)."
                }.getOrElse { "Failed to write: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /** 单次内联读取上限：太大时让模型改用 head/tail，避免撑爆上下文。 */
    private const val MAX_READ_BYTES = 512 * 1024L

    /**
     * 给 shell 参数加引号。
     *
     * 路径里有空格或特殊字符时不加引号会被拆成多个参数，
     * 轻则列不出目录，重则误操作别的路径。
     */
    private fun shellQuote(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "''"
        // 只含安全字符时不加引号，保持命令可读
        if (s.all { it.isLetterOrDigit() || it in "/._-~:@%+=" }) return s
        return "'" + s.replace("'", "'\"'\"'") + "'"
    }
}
