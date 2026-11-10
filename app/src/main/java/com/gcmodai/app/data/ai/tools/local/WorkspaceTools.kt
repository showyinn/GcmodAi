package com.gcmodai.app.data.ai.tools.local

import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.workspace.WorkspaceRepository
import com.gcmodai.app.data.workspace.WorkspaceResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 工作区工具集：让 AI 直接在服务器工作区里建站。
 *
 * 这一组工具把「AI 写代码」和「代码真正落地到服务器」连起来：
 * AI 调 ws_write_file 写文件 → 文件立刻出现在用户工作区
 * → ws_deploy_site 给出公网地址，任何网络都能打开。
 *
 * 所有隔离、100MB 配额、危险代码拦截都在服务端强制，工具只是通道，
 * 所以即使模型被诱导也越不出自己的目录。
 */
internal object WorkspaceTools {

    fun all(repo: WorkspaceRepository): List<Tool> = listOf(
        listFilesTool(repo),
        readFileTool(repo),
        writeFileTool(repo),
        deleteFileTool(repo),
        deploySiteTool(repo),
        workspaceInfoTool(repo),
    )

    /* ------------------------------ 列目录 ------------------------------ */

    private fun listFilesTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ws_list_files",
        description = """
            List files in the user's server workspace. The workspace is a private
            100MB space per user; web pages written there can be deployed as a real
            public website. Omit path to list the root directory.
            Use this before writing files to see what already exists.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative directory path. Leave empty for root, e.g. src or site/assets")
                    }
                },
            )
        },
        execute = { input ->
            val path = input.jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            val text = when (val r = repo.tree(path)) {
                is WorkspaceResult.Ok -> {
                    if (r.value.isEmpty()) {
                        "Directory '${path.ifBlank { "/" }}' is empty. Use ws_write_file to create files."
                    } else {
                        buildString {
                            appendLine("Directory '${path.ifBlank { "/" }}' has ${r.value.size} entries:")
                            r.value.forEach { e ->
                                if (e.isDir) appendLine("  [dir]  ${e.path}/")
                                else appendLine("  [file] ${e.path}  ${e.sizeLabel}")
                            }
                        }.trim()
                    }
                }

                is WorkspaceResult.Err -> "Failed to list: ${r.message}"
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 读文件 ------------------------------ */

    private fun readFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ws_read_file",
        description = """
            Read the full content of a file in the workspace.
            Always read a file before modifying it so you keep the existing structure.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative file path, e.g. index.html")
                    }
                },
                required = listOf("path"),
            )
        },
        execute = { input ->
            val path = input.jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            val text = if (path.isBlank()) {
                "Missing required parameter: path"
            } else {
                when (val r = repo.read(path)) {
                    is WorkspaceResult.Ok -> buildString {
                        appendLine("File: ${r.value.path} (${r.value.size} bytes)")
                        if (r.value.warnings.isNotEmpty()) {
                            appendLine("Scan warnings:")
                            r.value.warnings.forEach { appendLine("  - ${it.message}") }
                        }
                        appendLine("--- begin ---")
                        append(r.value.content)
                        appendLine()
                        append("--- end ---")
                    }

                    is WorkspaceResult.Err -> "Failed to read: ${r.message}"
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 写文件 ------------------------------ */

    private fun writeFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ws_write_file",
        description = """
            Create or overwrite a file in the workspace. This is the main tool for
            building a website: write index.html, style.css, app.js and any assets.
            Files appear immediately in the user's Workspace screen and can be
            deployed to a public URL. The server scans content and rejects
            webshells, crypto miners and phishing forms.
            To build a multi-page site, call this once per file with matching
            relative paths so links between files keep working.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative file path, e.g. index.html or assets/app.js")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Full file content")
                    }
                },
                required = listOf("path", "content"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
            val text = when {
                path.isBlank() -> "Missing required parameter: path"
                else -> when (val r = repo.write(path, content)) {
                    is WorkspaceResult.Ok -> buildString {
                        append("Wrote $path (${content.toByteArray().size} bytes).")
                        if (r.value.blocked) {
                            append(" BLOCKED by security scan.")
                        }
                        if (r.value.findings.isNotEmpty()) {
                            appendLine()
                            appendLine("Scan notes:")
                            r.value.findings.forEach { appendLine("  - ${it.message}") }
                        }
                    }

                    is WorkspaceResult.Err -> "Failed to write: ${r.message}"
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 删除 ------------------------------ */

    private fun deleteFileTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ws_delete_file",
        description = "Delete a file or directory in the workspace. This cannot be undone.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Relative path to delete")
                    }
                },
                required = listOf("path"),
            )
        },
        execute = { input ->
            val path = input.jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            val text = if (path.isBlank()) {
                "Missing required parameter: path"
            } else {
                when (val r = repo.delete(path)) {
                    is WorkspaceResult.Ok -> "Deleted $path"
                    is WorkspaceResult.Err -> "Failed to delete: ${r.message}"
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 部署 ------------------------------ */

    private fun deploySiteTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ws_deploy_site",
        description = """
            Deploy the workspace as a public website and return a shareable URL.
            The directory must contain index.html. After deploying, anyone can open
            the link on any network, including mobile data.
            Call this after writing the site files so the user gets a working link.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Directory to deploy. Leave empty for workspace root")
                    }
                },
            )
        },
        execute = { input ->
            val path = input.jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            val text = when (val r = repo.deploy(path)) {
                is WorkspaceResult.Ok ->
                    "Deployed successfully. Public URL: ${r.value}\nAnyone can open this link."

                is WorkspaceResult.Err -> "Deploy failed: ${r.message}"
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    /* ------------------------------ 工作区信息 ------------------------------ */

    private fun workspaceInfoTool(repo: WorkspaceRepository): Tool = Tool(
        name = "ws_info",
        description = """
            Show workspace quota usage: used space, remaining space and file limits.
            Use it when the user asks how much space is left or when a write failed
            due to quota.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { })
        },
        execute = {
            val text = when (val r = repo.quota()) {
                is WorkspaceResult.Ok -> {
                    val q = r.value
                    buildString {
                        appendLine("Workspace user: ${q.user}")
                        appendLine("Used: ${q.usedLabel} (${q.fileCount} files)")
                        appendLine(q.freeLabel)
                        appendLine("Max single file: ${q.maxFileBytes / 1048576} MB")
                        append("Max file count: ${q.maxFiles}")
                    }
                }

                is WorkspaceResult.Err -> "Failed to get workspace info: ${r.message}"
            }
            listOf(UIMessagePart.Text(text))
        },
    )
}
