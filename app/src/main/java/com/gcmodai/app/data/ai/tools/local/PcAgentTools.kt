package com.gcmodai.app.data.ai.tools.local

import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.agent.AgentResult
import com.gcmodai.app.data.agent.PcAgentRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Windows 电脑工具集（通过 GcmodAi 助手 exe 连接）。
 *
 * 与 [PcControlTools] 的分工：
 * - `pc_*`  走 SSH，适合服务器和有 SSH 的机器
 * - `wpc_*` 走助手 exe，Windows 用户双击即用，不用碰 SSH
 *
 * 安全：助手要求口令 + 拒绝破坏性命令；这里再拦一次，两边口径一致。
 */
internal object PcAgentTools {

    fun all(repo: PcAgentRepository): List<Tool> = listOf(
        listAgentsTool(repo),
        infoTool(repo),
        execTool(repo),
        listDirTool(repo),
        readFileTool(repo),
        writeFileTool(repo),
    )

    private fun listAgentsTool(repo: PcAgentRepository): Tool = Tool(
        name = "wpc_list_computers",
        description = """
            List the Windows computers connected through the GcmodAi helper exe.
            Call this first to get a computer_id, then use wpc_info / wpc_exec /
            wpc_ls / wpc_read / wpc_write on that id.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val agents = repo.agents()
            val text = if (agents.isEmpty()) {
                "No computer connected yet. Ask the user to run GcmodAi-Agent.exe on " +
                    "their Windows PC, then add the address and token in " +
                    "Settings -> 工作区与建站 -> Windows 电脑."
            } else {
                buildString {
                    appendLine("Connected computers (${agents.size}):")
                    agents.forEach { a ->
                        appendLine("  computer_id = ${a.id}")
                        appendLine("    name    = ${a.name}")
                        appendLine("    address = ${a.url}")
                    }
                    append("Use one of these computer_id values in the other wpc_* tools.")
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    private fun infoTool(repo: PcAgentRepository): Tool = Tool(
        name = "wpc_info",
        description = """
            Show system information of the connected computer: hostname, OS,
            current user, working directory, local IPs and drives.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("computer_id") {
                        put("type", "string")
                        put("description", "computer_id from wpc_list_computers")
                    }
                },
                required = listOf("computer_id"),
            )
        },
        execute = { input ->
            val id = input.jsonObject["computer_id"]?.jsonPrimitive?.content.orEmpty()
            val text = if (id.isBlank()) "Missing required parameter: computer_id" else {
                val agent = repo.agents().firstOrNull { it.id == id }
                if (agent == null) "Unknown computer_id: $id" else {
                    when (val r = repo.info(agent)) {
                        is AgentResult.Ok -> buildString {
                            appendLine("name:     ${agent.name}")
                            appendLine("hostname: ${r.value.hostname}")
                            appendLine("os:       ${r.value.os}")
                            appendLine("user:     ${r.value.user}")
                            appendLine("cwd:      ${r.value.cwd}")
                            if (r.value.ips.isNotEmpty()) {
                                appendLine("ips:      ${r.value.ips.joinToString(", ")}")
                            }
                            if (r.value.drives.isNotEmpty()) {
                                append("drives:   ${r.value.drives.joinToString(", ")}")
                            }
                        }

                        is AgentResult.Err -> "Failed: ${r.message}"
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    private fun execTool(repo: PcAgentRepository): Tool = Tool(
        name = "wpc_exec",
        description = """
            Run a Windows command (cmd.exe) on the connected computer.
            Use it to install software, manage files, run scripts, check services.
            Destructive commands (format, diskpart, shutdown, net user, bcdedit)
            are refused; ask the user to run those manually.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("computer_id") {
                        put("type", "string")
                        put("description", "computer_id from wpc_list_computers")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Windows command to run")
                    }
                    putJsonObject("cwd") {
                        put("type", "string")
                        put("description", "Working directory, e.g. C:/Users/me/project")
                    }
                },
                required = listOf("computer_id", "command"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["computer_id"]?.jsonPrimitive?.content.orEmpty()
            val command = obj["command"]?.jsonPrimitive?.content.orEmpty()
            val cwd = obj["cwd"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: computer_id"
                command.isBlank() -> "Missing required parameter: command"
                ShellGuard.isDangerous(command) -> ShellGuard.rejectionMessage(command)
                else -> {
                    val agent = repo.agents().firstOrNull { it.id == id }
                    if (agent == null) "Unknown computer_id: $id" else {
                        when (val r = repo.exec(agent, command, cwd)) {
                            is AgentResult.Ok -> buildString {
                                appendLine("exit=${r.value.exitCode}")
                                if (r.value.stdout.isNotBlank()) {
                                    appendLine("--- stdout ---")
                                    appendLine(r.value.stdout.take(12000))
                                }
                                if (r.value.stderr.isNotBlank()) {
                                    appendLine("--- stderr ---")
                                    appendLine(r.value.stderr.take(6000))
                                }
                            }.trim()

                            is AgentResult.Err -> "Command failed: ${r.message}"
                        }
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    private fun listDirTool(repo: PcAgentRepository): Tool = Tool(
        name = "wpc_ls",
        description = """
            List a directory on the computer, e.g. C:/Users/me/Desktop or D:/projects.
            Empty path means the agent's current directory.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("computer_id") {
                        put("type", "string")
                        put("description", "computer_id from wpc_list_computers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Directory path")
                    }
                },
                required = listOf("computer_id"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["computer_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = if (id.isBlank()) "Missing required parameter: computer_id" else {
                val agent = repo.agents().firstOrNull { it.id == id }
                if (agent == null) "Unknown computer_id: $id" else {
                    when (val r = repo.listDir(agent, path)) {
                        is AgentResult.Ok -> r.value
                        is AgentResult.Err -> "Failed to list: ${r.message}"
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    private fun readFileTool(repo: PcAgentRepository): Tool = Tool(
        name = "wpc_read",
        description = "Read a text file on the computer, e.g. C:/Users/me/config.json.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("computer_id") {
                        put("type", "string")
                        put("description", "computer_id from wpc_list_computers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "File path")
                    }
                },
                required = listOf("computer_id", "path"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["computer_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: computer_id"
                path.isBlank() -> "Missing required parameter: path"
                else -> {
                    val agent = repo.agents().firstOrNull { it.id == id }
                    if (agent == null) "Unknown computer_id: $id" else {
                        when (val r = repo.readFile(agent, path)) {
                            is AgentResult.Ok ->
                                "File: $path\n--- begin ---\n${r.value}\n--- end ---"

                            is AgentResult.Err -> "Failed to read: ${r.message}"
                        }
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    private fun writeFileTool(repo: PcAgentRepository): Tool = Tool(
        name = "wpc_write",
        description = """
            Create or overwrite a text file on the computer.
            Parent directories are created automatically.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("computer_id") {
                        put("type", "string")
                        put("description", "computer_id from wpc_list_computers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "File path")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Full file content")
                    }
                },
                required = listOf("computer_id", "path", "content"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val id = obj["computer_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                id.isBlank() -> "Missing required parameter: computer_id"
                path.isBlank() -> "Missing required parameter: path"
                else -> {
                    val agent = repo.agents().firstOrNull { it.id == id }
                    if (agent == null) "Unknown computer_id: $id" else {
                        when (val r = repo.writeFile(agent, path, content)) {
                            is AgentResult.Ok ->
                                "Wrote $path (${content.toByteArray().size} bytes)."

                            is AgentResult.Err -> "Failed to write: ${r.message}"
                        }
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )
}
