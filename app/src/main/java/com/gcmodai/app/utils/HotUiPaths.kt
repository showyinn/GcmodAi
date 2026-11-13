package com.gcmodai.app.utils

import android.content.Context
import java.io.File

/**
 * 热更新目录的读取工具。
 *
 * 单独抽出来是为了让 WebServerManager / WebServerService 这类底层组件
 * 不必依赖 HotUpdateManager（它有 AppScope、OkHttp 等一堆依赖），
 * 只读一个已经落盘的目录，构造上不会出问题。
 */
object HotUiPaths {
    private const val PREF = "hotui"
    private const val KEY_VERSION = "version"

    fun activeDir(context: Context): File? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val dir = File(File(context.filesDir, "hotui"), version)
        return dir.takeIf { it.isDirectory && File(it, "index.html").isFile }
    }

    fun activeVersion(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_VERSION, null)
}
