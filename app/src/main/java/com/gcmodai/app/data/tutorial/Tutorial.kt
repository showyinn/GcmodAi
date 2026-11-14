package com.gcmodai.app.data.tutorial

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 新手教程的内容模型。
 *
 * 为什么做成数据而不是写死在 Composable 里：
 * 教程讲的是「这个按钮在哪、能干什么」，跟着功能迭代走，但功能变化频率远高于 APK 发版频率。
 * 做成可热更新的 JSON 后，改一句说明、加一个功能条目都不用发版；
 * 内置这份 JSON 只是兜底，保证没网、没下过热更新包时教程依然完整。
 */
@Serializable
data class Tutorial(
    /** 内容版本，客户端只用来判断要不要提示「教程有更新」 */
    val version: String = "1",
    val title: String = "新手教程",
    val subtitle: String = "",
    val chapters: List<TutorialChapter> = emptyList(),
)

@Serializable
data class TutorialChapter(
    val id: String,
    val title: String,
    val summary: String = "",
    /** 章节图标名，映射到 [TutorialIcons]，认不出就用默认图标 */
    val icon: String = "",
    val entries: List<TutorialEntry> = emptyList(),
)

@Serializable
data class TutorialEntry(
    val title: String,
    val body: String,
    /** 在哪能找到这个功能，比如「+ 号 → 任务模式」 */
    val where: String = "",
    /** 关键提示，界面上用高亮块显示；空则不显示 */
    val tip: String = "",
    /** 是否是本次新增/重点，界面上加一个「新」标记 */
    val badge: String = "",
)

/**
 * 教程内容的加载与缓存。
 *
 * 读取顺序：热更新包里的 tutorial.json > APK 内置 assets/tutorial.json > 代码里的最小兜底。
 * 三层都不可用时也不能让 App 崩：教程页显示一句「教程加载失败，点重试」即可，
 * 聊天、模型、工具这些主功能一律不受影响。
 */
class TutorialRepository(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Tutorial"
        private const val ASSET_NAME = "tutorial.json"
        /** 热更新包会把文件放在 hotui/<版本>/tutorial.json */
        private const val HOT_DIR = "hotui"
        private const val HOT_FILE = "tutorial.json"
        private const val PREF = "tutorial"
        private const val KEY_VERSION = "version"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 上次读到的教程版本，用于在设置页显示「教程内容 vN」 */
    fun loadedVersion(): String = prefs.getString(KEY_VERSION, null) ?: ""

    /**
     * 读出当前生效的教程。
     *
     * 每次都从磁盘读：文件很小（几十 KB），但能让用户在设置里点「重新加载教程」后立刻看到新内容，
     * 不必杀掉进程。解析失败时逐层降级，不会抛给调用方。
     */
    suspend fun load(): Tutorial = withContext(Dispatchers.IO) {
        val fromHot = readHot()
        if (fromHot != null) {
            prefs.edit().putString(KEY_VERSION, fromHot.version).apply()
            return@withContext fromHot
        }
        val fromAsset = readAsset()
        if (fromAsset != null) {
            prefs.edit().putString(KEY_VERSION, fromAsset.version).apply()
            return@withContext fromAsset
        }
        Log.w(TAG, "no tutorial content available, using builtin fallback")
        BuiltinTutorial.FALLBACK
    }

    private fun readHot(): Tutorial? {
        val root = File(context.filesDir, HOT_DIR)
        if (!root.isDirectory) return null
        // 热更新目录按版本命名，取最新修改的那个，避免读到上次遗留的旧目录
        val dir = root.listFiles()
            ?.filter { it.isDirectory && File(it, HOT_FILE).isFile }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return parse(File(dir, HOT_FILE))
    }

    private fun readAsset(): Tutorial? = runCatching {
        context.assets.open(ASSET_NAME).use { stream ->
            parse(stream.readBytes().decodeToString())
        }
    }.onFailure { Log.w(TAG, "read asset tutorial failed", it) }.getOrNull()

    private fun parse(file: File): Tutorial? = runCatching {
        parse(file.readText())
    }.onFailure { Log.w(TAG, "parse ${file.name} failed", it) }.getOrNull()

    private fun parse(text: String): Tutorial? = runCatching {
        json.decodeFromString(Tutorial.serializer(), text)
    }.onFailure { Log.w(TAG, "parse tutorial json failed", it) }.getOrNull()
}
