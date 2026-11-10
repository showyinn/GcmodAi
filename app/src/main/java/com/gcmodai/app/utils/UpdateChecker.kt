package com.gcmodai.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.gcmodai.common.http.await
import com.gcmodai.app.AppScope
import com.gcmodai.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val API_URL = "http://38.76.219.151/updates.json"

/**
 * 下载页地址。
 *
 * App 内下载失败时（比如历史上的 404：updates.json 指向了已删除的旧包），
 * 给用户一个兜底出口，不用被困在"下载失败"里。
 */
const val UPDATE_DOWNLOAD_PAGE = "http://38.76.219.151/"
private const val APK_MIME = "application/vnd.android.package-archive"

/** 更新包在 App 私有缓存下的子目录名 */
private const val UPDATE_DIR = "updates"

/**
 * 清洗下载文件名。
 *
 * 服务端返回的名字可能带路径分隔符或非法字符，
 * 直接拿去 new File 会写错位置甚至失败。
 */
internal fun sanitizeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    // 全空白或清洗后只剩分隔符（如 "___"）都视为无效名，
    // 否则会得到一个人类不认识的随机文件名
    val hasMeaning = cleaned.any { it.isLetterOrDigit() }
    val name = if (!hasMeaning) "update.apk" else cleaned
    return if (name.endsWith(".apk", ignoreCase = true)) name else "$name.apk"
}

/** 在线更新的下载/安装状态，供 UI 展示进度与安装入口 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    /**
     * 正在下载。
     *
     * progress 取值 0f..1f；总大小未知时为 -1f。
     * 同时带上字节数，UI 可以显示 "12.3 / 18.2 MB"，
     * 这比只有一个百分比更让人放心。
     */
    data class Downloading(
        val name: String,
        val progress: Float,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        /** 下载速度，字节/秒。未知时为 0 */
        val bytesPerSecond: Long = 0,
    ) : UpdateDownloadState {
        val percent: Int
            get() = if (progress < 0f) -1 else (progress * 100).toInt().coerceIn(0, 100)

        val sizeLabel: String
            get() = if (totalBytes > 0) {
                "%s / %s".format(formatBytes(downloadedBytes), formatBytes(totalBytes))
            } else {
                formatBytes(downloadedBytes)
            }

        val speedLabel: String
            get() = if (bytesPerSecond > 0) "%s/s".format(formatBytes(bytesPerSecond)) else ""
    }

    /** 已下载完成，等待用户确认安装（或已自动拉起安装器） */
    data class Downloaded(val name: String) : UpdateDownloadState

    data class Failed(val name: String, val message: String) : UpdateDownloadState
}

/** 把字节数格式化成易读文本。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
    /**
     * 远程配置来源。
     *
     * 有了它，管理员在后台改 `latest_version` 就能立刻让 App 提示更新，
     * 不用等我重新发一版 updates.json。
     */
    private val remoteConfig: com.gcmodai.app.data.remote.RemoteConfigRepository? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _updateState = MutableStateFlow<UiState<UpdateInfo>>(UiState.Loading)
    val updateState: StateFlow<UiState<UpdateInfo>> = _updateState.asStateFlow()

    private var lastCheckAt = 0L
    private var checkJob: Job? = null

    /**
     * 检查更新（默认 30 分钟内不重复请求）。
     * 应用启动、抽屉展开以及抽屉里的小时级轮询都会调用它，因此发布新版本后
     * 用户下次打开应用即可看到提示，无需手动点"检查更新"。
     */
    fun refreshIfStale(minIntervalMs: Long = 30 * 60 * 1_000L) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < minIntervalMs) return
        lastCheckAt = now
        if (checkJob?.isActive == true) return
        checkJob = appScope.launch(Dispatchers.IO) {
            _updateState.value = UiState.Loading
            _updateState.value = try {
                UiState.Success(fetchUpdateInfo())
            } catch (e: Exception) {
                UiState.Error(Exception("Failed to fetch update info", e))
            }
        }
    }

    private suspend fun fetchUpdateInfo(): UpdateInfo {
        // 先问后台配置：管理员可能直接指定了最新版本号与下载地址。
        // 这样改版本不需要重新发布 updates.json，后台保存即生效。
        val cfg = remoteConfig?.config?.value
        if (cfg != null && !cfg.updateCheckEnabled) {
            // 管理员关掉了更新检查：返回当前版本，等价于「没有新版本」
            return UpdateInfo(
                version = BuildConfig.VERSION_NAME,
                publishedAt = "",
                changelog = "",
                downloads = emptyList(),
            )
        }

        val fromJson = runCatching { fetchFromJson() }.getOrNull()

        // 后台配置了更高版本时以它为准，并把下载地址拼成一条下载项
        if (cfg != null && cfg.latestVersion.isNotBlank()) {
            val cfgNewer = runCatching {
                Version(cfg.latestVersion) > Version(fromJson?.version ?: BuildConfig.VERSION_NAME)
            }.getOrDefault(false)

            if (cfgNewer) {
                val downloads = if (cfg.downloadUrl.isNotBlank()) {
                    listOf(
                        UpdateDownload(
                            name = "GcmodAi-${cfg.latestVersion}-universal.apk",
                            url = cfg.downloadUrl,
                            size = "",
                        )
                    )
                } else {
                    fromJson?.downloads ?: emptyList()
                }
                return UpdateInfo(
                    version = cfg.latestVersion,
                    publishedAt = fromJson?.publishedAt ?: "",
                    changelog = fromJson?.changelog ?: "",
                    downloads = downloads,
                )
            }
        }

        return fromJson ?: throw Exception("Failed to fetch update info")
    }

    private suspend fun fetchFromJson(): UpdateInfo {
        val response = client.newCall(
            Request.Builder()
                .url(API_URL)
                .get()
                .addHeader(
                    "User-Agent",
                    "GcmodAi ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                )
                .build()
        ).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch update info")
        }
        return json.decodeFromString<UpdateInfo>(response.body.string())
    }

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    /** 当前下载任务的落盘位置，安装时用 */
    private var activeApkFile: File? = null
    private var activeDownloadName: String? = null
    private var downloadJob: Job? = null

    /**
     * 下载更新包。
     *
     * 为什么不用系统的 DownloadManager：
     * 那套会把下载丢到通知栏，进度在系统 UI 里，App 内看不到，
     * 用户容易以为没反应。这里改成用 OkHttp 在 App 内流式下载到
     * 私有缓存目录，进度通过 [downloadState] 实时上报，UI 画自己的进度条。
     *
     * 下载完成后自动拉起系统安装器；未授予「安装未知应用」权限时先去设置页。
     */
    fun downloadUpdate(context: Context, download: UpdateDownload) {
        val appContext = context.applicationContext

        if (_downloadState.value is UpdateDownloadState.Downloading) {
            Toast.makeText(appContext, "已有下载任务在进行", Toast.LENGTH_SHORT).show()
            return
        }

        activeDownloadName = download.name
        _downloadState.value = UpdateDownloadState.Downloading(
            name = download.name,
            progress = 0f,
        )

        downloadJob = appScope.launch(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, UPDATE_DIR).apply { mkdirs() }
            // 先清掉旧的同名包，避免磁盘残留与"用的是上一版"的错觉
            runCatching {
                dir.listFiles()?.forEach { if (it.isFile) it.delete() }
            }
            val target = File(dir, sanitizeFileName(download.name))

            try {
                val request = Request.Builder()
                    .url(download.url)
                    .get()
                    .addHeader("Accept", "*/*")
                    .addHeader(
                        "User-Agent",
                        "GcmodAi ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("下载失败（HTTP ${response.code}）")
                    }
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: 0L

                    body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastEmit = 0L
                            val startedAt = System.currentTimeMillis()

                            while (true) {
                                if (!isActive) throw CancellationException("cancelled")
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                // 每 120ms 上报一次，太频繁会让 Compose 重组压力大
                                if (now - lastEmit >= 120) {
                                    lastEmit = now
                                    val elapsed = ((now - startedAt) / 1000.0).coerceAtLeast(0.001)
                                    val speed = (downloaded / elapsed).toLong()
                                    val progress =
                                        if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                        else -1f
                                    _downloadState.value = UpdateDownloadState.Downloading(
                                        name = download.name,
                                        progress = progress,
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        bytesPerSecond = speed,
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }

                val length = target.length()
                if (length <= 0L) throw IOException("下载的文件为空")

                activeApkFile = target
                _downloadState.value = UpdateDownloadState.Downloaded(download.name)
                withContext(Dispatchers.Main) {
                    installDownloadedApk(appContext)
                }
            } catch (e: CancellationException) {
                runCatching { target.delete() }
                _downloadState.value = UpdateDownloadState.Idle
                throw e
            } catch (e: Exception) {
                runCatching { target.delete() }
                _downloadState.value = UpdateDownloadState.Failed(
                    download.name,
                    e.message ?: "下载失败",
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "下载失败：${e.message ?: ""}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    /** 取消正在进行的下载。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        activeApkFile?.let { runCatching { it.delete() } }
        activeApkFile = null
        _downloadState.value = UpdateDownloadState.Idle
    }

    /** 安装已下载完成的更新包（可重复调用，例如用户在卡片上点"安装"） */
    fun installDownloadedApk(context: Context) {
        val appContext = context.applicationContext
        val file = activeApkFile
        if (file == null || !file.isFile) {
            _downloadState.value = UpdateDownloadState.Failed(
                activeDownloadName ?: "update",
                "安装包不存在，请重新下载",
            )
            return
        }

        // 私有目录里的文件必须通过 FileProvider 交给安装器，
        // 直接传 file:// 在 Android 7+ 会抛 FileUriExposedException。
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
        }.getOrElse { e ->
            _downloadState.value = UpdateDownloadState.Failed(
                activeDownloadName ?: "update",
                "无法准备安装包：${e.message ?: ""}",
            )
            return
        }

        startInstaller(appContext, uri)
    }

    private fun startInstaller(context: Context, apkUri: Uri) {
        // Android 8+ 需要「安装未知应用」权限；未授予时先去设置页
        if (!runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)) {
            Toast.makeText(
                context,
                context.getString(com.gcmodai.app.R.string.update_card_install_permission),
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri()
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure { ex ->
            _downloadState.value = UpdateDownloadState.Failed(
                activeDownloadName ?: "update",
                ex.message ?: "无法启动安装器"
            )
        }
    }

    fun dismissDownloadState() {
        _downloadState.value = UpdateDownloadState.Idle
        activeApkFile = null
        activeDownloadName = null
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
