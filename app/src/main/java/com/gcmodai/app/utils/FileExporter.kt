package com.gcmodai.app.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把应用私有目录里的文件导出到手机「下载/GcmodAi」。
 *
 * 加固包、抽出来的视频帧、mp3 音轨都在 App 沙盒里，用户拿不到；
 * 想安装/发给别人就必须先落到公共下载目录。
 *
 * Android 10 起走 MediaStore（不需要存储权限），更低版本直接写公共目录
 * （那条路径要求 WRITE_EXTERNAL_STORAGE，Manifest 里已按 maxSdkVersion=28 声明）。
 */
object FileExporter {

    fun exportToDownloads(context: Context, source: File, displayName: String): Boolean {
        if (!source.exists()) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(displayName))
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/GcmodAi"
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "GcmodAi",
                )
                dir.mkdirs()
                source.copyTo(File(dir, displayName), overwrite = true)
                true
            }
        }.getOrDefault(false)
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}
