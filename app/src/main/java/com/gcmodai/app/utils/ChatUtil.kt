package com.gcmodai.app.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gcmodai.ai.ui.UIMessage
import com.gcmodai.app.Screen
import com.gcmodai.app.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

private val ALLOWED_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
    "application/json", "application/javascript", "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/epub+zip",
    // [upload-fix-20261111] 安装包与压缩包原来全被挡在白名单外，用户选完直接被拒。
    // 这些是逆向/排查场景的常规输入（APK 拆包、SO 提取、日志归档），必须放行。
    "application/vnd.android.package-archive",
    "application/zip", "application/x-zip-compressed",
    "application/x-7z-compressed",
    "application/x-rar-compressed", "application/vnd.rar",
    "application/gzip", "application/x-gzip",
    "application/x-tar", "application/x-bzip2", "application/x-xz",
    "application/zstd", "application/java-archive",
    // 很多机型把 apk/zip/7z 报成 octet-stream，不放行等于白名单失效
    "application/octet-stream"
)

private val ALLOWED_FILE_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "js", "jsx", "mjs", "cjs",
    "html", "css", "vue", "svelte", "xml", "agc",
    "py", "rb", "lua", "sql", "java", "kt", "ts", "tsx",
    "dart", "php", "swift", "go",
    "bat", "cmd", "ps1", "psm1", "sh", "bash", "zsh", "fish",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "rs", "cs", "markdown", "mdx",
    "toml", "ini", "env", "gradle", "kts", "properties",
    "proto", "graphql", "gql", "yml", "yaml",
    // 安装包
    "apk", "apks", "apkm", "xapk", "aab", "obb", "jar", "dex",
    // 原生库与可执行文件（二进制分析工作台的输入）
    "so", "bin", "exe", "dll", "msi", "dylib", "elf", "img", "iso",
    // 压缩包
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "cab", "lz", "z"
)

/**
 * 是否允许作为聊天附件上传。
 *
 * 判定顺序：已知 MIME → 扩展名 → **兜底放行**。
 * 兜底放行是刻意的：文件是用户自己从系统选择器里挑的，模型只读内容不执行，
 * 拦下来只会让用户看到「不支持的文件类型」却不知道为什么（apk/zip/7z 就是这么被挡的）。
 */
fun isAllowedFileType(fileName: String, mime: String): Boolean {
    if (mime in ALLOWED_MIME_TYPES || mime.startsWith("text/") || mime.startsWith("image/") ||
        mime.startsWith("video/") || mime.startsWith("audio/")
    ) return true
    // 其余一律放行：文件是用户自己从系统选择器里挑的，模型只读内容、不执行。
    // 历史版本在这里对未知类型返回 false，结果就是 apk/zip/7z 选完被静默拒绝
    // （只弹一句"不支持的文件类型"），这就是本次要修的问题本身。
    return true
}

/** 是否属于"明确支持"的扩展名清单（用于 UI 文案与单元测试，不参与拦截判定）。 */
fun isKnownFileExtension(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in ALLOWED_FILE_EXTENSIONS
