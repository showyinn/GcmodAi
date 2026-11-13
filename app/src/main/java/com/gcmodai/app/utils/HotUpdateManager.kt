package com.gcmodai.app.utils

import android.content.Context
import android.util.Log
import com.gcmodai.app.AppScope
import com.gcmodai.app.BuildConfig
import com.gcmodai.common.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 热更新（HotUI）：不发新版 APK 也能换界面。
 *
 * 能这么干的原因：这个 App 自带一个内嵌 Web 服务器（Ktor），Web 界面是纯静态资源。
 * 只要把新的静态资源下载到 App 私有目录，让内嵌服务器优先从那里读，
 * 界面就换新了 —— 不需要经过安装器，也就没有"下载 APK → 点安装 → 重启"那套流程。
 *
 * 安全边界（很重要）：
 *  · 只解压到 filesDir/hotui/<version>/ 下，并且逐条校验 zip 内路径，
 *    拒绝 ".."、绝对路径、反斜杠逃逸 —— 否则一个恶意 zip 可以覆盖 App 任意文件；
 *  · 下载完必须校验 sha256，不一致直接丢弃；
 *  · 先解压到临时目录，成功后再原子 rename 并更新指针文件；
 *    任何一步失败都保留旧版本，界面不会变成白屏。
 */
class HotUpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "HotUpdate"
        const val MANIFEST_URL = "https://gcmod.bond/hotui.json"
        private const val PREF = "hotui"
        private const val KEY_VERSION = "version"
        private const val KEY_APPLIED_AT = "applied_at"
        private const val DIR_ROOT = "hotui"
        private const val MAX_ZIP_BYTES = 32L * 1024 * 1024
    }

    @Serializable
    data class Manifest(
        val version: String = "",
        val url: String = "",
        val mirror: String = "",
        val sha256: String = "",
        val sizeBytes: Long = 0,
        val note: String = "",
        val publishedAt: String = "",
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Downloading(val progress: Int) : State
        data class Applied(val version: String) : State
        data class Failed(val message: String) : State
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前生效的热更新目录；没有热更新时返回 null（走 APK 内打包的资源） */
    fun activeDir(): File? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val dir = File(rootDir(), version)
        return dir.takeIf { it.isDirectory && File(it, "index.html").isFile }
    }

    fun activeVersion(): String = prefs.getString(KEY_VERSION, null) ?: "内置版本"

    private fun rootDir() = File(context.filesDir, DIR_ROOT)

    /**
     * 检查并（可选）应用热更新。
     *
     * @param autoApply true = 有新版本就静默下载并切换；false = 只检查，返回是否有新版
     * @return 是否有新版本可用
     */
    fun checkAndApply(autoApply: Boolean, onNewVersion: (String) -> Unit = {}) {
        if (_state.value is State.Downloading) return
        appScope.launch(Dispatchers.IO) {
            _state.value = State.Checking
            val manifest = runCatching { fetchManifest() }.getOrElse { e ->
                Log.w(TAG, "fetch manifest failed", e)
                _state.value = State.Failed("获取热更新清单失败：${e.message}")
                return@launch
            }
            val current = prefs.getString(KEY_VERSION, null)
            if (manifest.version.isBlank() || manifest.version == current) {
                _state.value = State.UpToDate(manifest.version.ifBlank { "内置版本" })
                return@launch
            }
            onNewVersion(manifest.version)
            if (!autoApply) {
                _state.value = State.UpToDate(manifest.version)
                return@launch
            }
            apply(manifest)
        }
    }

    private suspend fun fetchManifest(): Manifest {
        val req = Request.Builder()
            .url(MANIFEST_URL)
            .get()
            .addHeader("User-Agent", "GcmodAi ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
            .build()
        client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return json.decodeFromString(Manifest.serializer(), resp.body.string())
        }
    }

    private suspend fun apply(manifest: Manifest) {
        val url = manifest.url.ifBlank { manifest.mirror }
        if (url.isBlank()) {
            _state.value = State.Failed("清单里没有下载地址")
            return
        }
        _state.value = State.Downloading(0)
        val bytes = withContext(Dispatchers.IO) {
            runCatching { download(url) }.getOrNull()
        }
        if (bytes == null) {
            _state.value = State.Failed("下载失败，请稍后重试")
            return
        }

        val digest = sha256(bytes)
        if (manifest.sha256.isNotBlank() && !digest.equals(manifest.sha256, ignoreCase = true)) {
            _state.value = State.Failed("校验失败：sha256 不一致")
            return
        }

        val ok = withContext(Dispatchers.IO) { runCatching { unpack(bytes, manifest.version) }.isSuccess }
        if (ok) {
            prefs.edit()
                .putString(KEY_VERSION, manifest.version)
                .putLong(KEY_APPLIED_AT, System.currentTimeMillis())
                .apply()
            _state.value = State.Applied(manifest.version)
            Log.i(TAG, "hot update applied: ${manifest.version}")
        } else {
            _state.value = State.Failed("解压失败")
        }
    }

    private fun download(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", "GcmodAi ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val total = resp.body.contentLength()
            val out = java.io.ByteArrayOutputStream()
            resp.body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var sum = 0L
                while (input.read(buf).also { read = it } > 0) {
                    sum += read
                    if (sum > MAX_ZIP_BYTES) error("包体超过 ${MAX_ZIP_BYTES / 1048576}MB")
                    out.write(buf, 0, read)
                    if (total > 0) {
                        _state.value = State.Downloading(((sum * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            out.toByteArray()
        }
    }

    /**
     * 解压到 hotui/<version>，逐条校验路径。
     *
     * 防 Zip Slip：任何解析后落到目标目录之外的条目都直接抛错，整包作废。
     */
    private fun unpack(bytes: ByteArray, version: String) {
        val target = File(rootDir(), version)
        val tmp = File(rootDir(), "$version.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val canonicalTmp = tmp.canonicalFile

        ZipInputStream(bytes.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.contains("..")) error("非法路径：$name")
                if (name.startsWith("/")) error("非法绝对路径：$name")
                val out = File(tmp, name)
                if (!out.canonicalPath.startsWith(canonicalTmp.path + File.separator) &&
                    out.canonicalPath != canonicalTmp.path
                ) {
                    error("路径逃逸：$name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { fos -> zin.copyTo(fos) }
                }
                entry = zin.nextEntry
            }
        }

        // 原子切换：删旧目录 -> 临时目录改名
        target.deleteRecursively()
        if (!tmp.renameTo(target)) error("目录切换失败")
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** 回退到内置版本（出问题时用） */
    fun revert() {
        prefs.edit().remove(KEY_VERSION).apply()
        _state.value = State.UpToDate("内置版本")
    }
}
