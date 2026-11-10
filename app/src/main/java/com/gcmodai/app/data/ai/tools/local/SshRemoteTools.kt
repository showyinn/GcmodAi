package com.gcmodai.app.data.ai.tools.local

import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.repository.WorkspaceRepository
import com.gcmodai.workspace.WorkspaceStorageArea
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * SSH 远程服务器工具集：让 AI 直接在用户自己的服务器上建站。
 *
 * 与 [WorkspaceTools] 的区别：
 * - WorkspaceTools 操作 GcmodAi 托管的 100MB 工作区（我们提供的空间）
 * - 这组工具操作用户**自己的**服务器（他填的 host/账号），走 SSH/SFTP
 *
 * 安全边界：
 * - 只操作用户已配置并保存的 SSH 工作区，无法凭空连任意主机
 * - 主机指纹由用户首次连接时确认，之后变化会拒绝（防中间人）
 * - 凭据用 Android Keystore 加密存储，工具读不到明文
 * - 命令执行受工作区 root 限制
 */
internal object SshRemoteTools {

    fun all(repo: WorkspaceRepository): List<Tool> = listOf(
        listSshServersTool(repo),
        listRemoteFilesTool(repo),
        readRemoteFileTool(repo),
        writeRemoteFileTool(repo),
        runRemoteCommandTool(repo),
        deployRemoteSiteTool(repo),
    )

    /* ------------------------------ 列出已配置服务器 ------------------------------ */

    private fun listSshServersTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ssh_list_servers",
        description = """
            List the SSH servers the user has already configured in GcmodAi.
            Call this first: it tells you which remote servers are available and
            what their workspace directories are. You can only operate on servers
            from this list; you cannot connect to arbitrary hosts.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { })
        },
        execute = {
            val list = runCatching { repo.listSshWorkspacesNow() }.getOrElse { emptyList() }
            val text = if (list.isEmpty()) {
                "No SSH server configured yet. Ask the user to add one in " +
                    "Settings -> Extensions -> Workspace -> SSH, then retry."
            } else {
                buildString {
                    appendLine("Configured SSH servers (${list.size}):")
                    list.forEach { w ->
                        appendLine("  id=${w.id}")
                        appendLine("    name=${w.name}")
                        appendLine("    address=${w.address}")
                        appendLine("    remoteDir=${w.remoteDir}")
                    }
                    append("Use the id as the workspace_id parameter in other ssh_* tools.")
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 列远程文件 ------------------------------ */

    private fun listRemoteFilesTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ssh_list_files",
        description = """
            List files in a directory on the user's SSH server.
            workspace_id comes from ssh_list_servers. path is relative to the
            server's configured workspace directory; leave empty for the root.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "Workspace id from ssh_list_servers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative directory path, empty for root")
                    }
                },
                required = listOf("workspace_id"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = if (wsId.isBlank()) {
                "Missing required parameter: workspace_id"
            } else {
                runCatching {
                    val entries = repo.listFiles(wsId, WorkspaceStorageArea.FILES, path)
                    if (entries.isEmpty()) {
                        "Directory '${path.ifBlank { "/" }}' is empty."
                    } else {
                        buildString {
                            appendLine("Directory '${path.ifBlank { "/" }}' has ${entries.size} entries:")
                            entries.forEach { e ->
                                if (e.isDirectory) appendLine("  [dir]  ${e.name}/")
                                else appendLine("  [file] ${e.name}  ${e.sizeBytes} bytes")
                            }
                        }.trim()
                    }
                }.getOrElse { "Failed to list: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 读远程文件 ------------------------------ */

    private fun readRemoteFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ssh_read_file",
        description = """
            Read a text file from the user's SSH server.
            Always read before overwriting so you keep the existing content.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "Workspace id from ssh_list_servers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "File path relative to the workspace directory")
                    }
                },
                required = listOf("workspace_id", "path"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                wsId.isBlank() -> "Missing required parameter: workspace_id"
                path.isBlank() -> "Missing required parameter: path"
                else -> runCatching {
                    val content = repo.readText(wsId, path)
                    "File: $path\n--- begin ---\n$content\n--- end ---"
                }.getOrElse { "Failed to read: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 写远程文件 ------------------------------ */

    private fun writeRemoteFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ssh_write_file",
        description = """
            Write a file on the user's SSH server. This is how you deploy a website
            to their own server: write index.html, style.css, app.js and assets.
            The file lands in the server's configured workspace directory and is
            immediately served by whatever web server the user runs there.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "Workspace id from ssh_list_servers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Target file path relative to workspace dir, e.g. site/index.html")
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
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                wsId.isBlank() -> "Missing required parameter: workspace_id"
                path.isBlank() -> "Missing required parameter: path"
                else -> runCatching {
                    val entry = repo.writeText(wsId, path, content, overwrite = true)
                    "Wrote ${entry.name} (${content.toByteArray().size} bytes) on the remote server."
                }.getOrElse { "Failed to write: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 执行命令 ------------------------------ */

    private fun runRemoteCommandTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ssh_run_command",
        description = """
            Run a shell command on the user's SSH server inside the workspace
            directory. Use it to create directories, move files, or restart a
            static server. Do not run destructive commands (rm -rf /, mkfs,
            user management, credential changes) - ask the user instead.
            Returns stdout, stderr and exit code.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "Workspace id from ssh_list_servers")
                    }
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "Shell command to execute")
                    }
                },
                required = listOf("workspace_id", "command"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val cmd = obj["command"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                wsId.isBlank() -> "Missing required parameter: workspace_id"
                cmd.isBlank() -> "Missing required parameter: command"
                isDangerousCommand(cmd) ->
                    "Refused: this command looks destructive. Ask the user to run it manually."
                else -> runCatching {
                    val r = repo.executeCommand(wsId, cmd)
                    buildString {
                        appendLine("exit=${r.exitCode}")
                        if (r.stdout.isNotBlank()) {
                            appendLine("--- stdout ---")
                            appendLine(r.stdout.take(8000))
                        }
                        if (r.stderr.isNotBlank()) {
                            appendLine("--- stderr ---")
                            appendLine(r.stderr.take(4000))
                        }
                    }.trim()
                }.getOrElse { "Command failed: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 部署到远程 ------------------------------ */

    private fun deployRemoteSiteTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ssh_deploy_site",
        description = """
            Prepare a website deployment on the user's SSH server: verify index.html
            exists in the target directory and return the ready-to-use URL info.
            The actual HTTP serving depends on the user's own web server (nginx,
            apache, caddy); this tool confirms the files are in place.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspace_id") {
                        put("type", "string")
                        put("description", "Workspace id from ssh_list_servers")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Directory containing index.html, empty for workspace root")
                    }
                },
                required = listOf("workspace_id"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val text = if (wsId.isBlank()) {
                "Missing required parameter: workspace_id"
            } else {
                runCatching {
                    val entries = repo.listFiles(wsId, WorkspaceStorageArea.FILES, path)
                    val hasIndex = entries.any {
                        !it.isDirectory && it.name.equals("index.html", ignoreCase = true)
                    }
                    if (!hasIndex) {
                        "No index.html in '${path.ifBlank { "/" }}'. " +
                            "Write one with ssh_write_file first."
                    } else {
                        val server = repo.listSshWorkspacesNow().firstOrNull { it.id == wsId }
                        buildString {
                            appendLine("Deployment ready.")
                            appendLine("Server: ${server?.address ?: wsId}")
                            appendLine("Directory: ${server?.remoteDir ?: "/"}/${path.trim('/')}")
                            appendLine("Files: ${entries.size}")
                            appendLine()
                            append("The site is served by the web server already running on that host. ")
                            append("If it is not reachable, check nginx/apache root points to this directory.")
                        }
                    }
                }.getOrElse { "Deploy check failed: ${it.message}" }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 危险命令拦截 ------------------------------ */

    /**
     * 明显破坏性的命令一律不让 AI 直接跑。
     *
     * 这些操作一旦误执行无法回滚，交给用户手动确认更稳妥。
     */
    private fun isDangerousCommand(cmd: String): Boolean = ShellGuard.isDangerous(cmd)
}
