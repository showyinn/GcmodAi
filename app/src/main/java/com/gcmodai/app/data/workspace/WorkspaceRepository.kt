package com.gcmodai.app.data.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 服务器工作区客户端。
 *
 * 每个用户一个 100MB 隔离工作区，AI 写代码直接落到服务器上，
 * 并能把工作区里的静态页面部署成公网可访问的站点。
 *
 * 隔离由服务端强制（路径校验、用户哈希目录、配额），客户端只负责调用。
 */

@Serializable
data class WorkspaceEntry(
    val name: String = "",
    val path: String = "",
    val type: String = "file",
    val size: Long = 0,
    val mtime: Long = 0,
    val ext: String = "",
    val deployable: Boolean = false,
) {
    val isDir: Boolean get() = type == "dir"

    val sizeLabel: String
        get() = when {
            isDir -> ""
            size >= 1024 * 1024 -> "%.2f MB".format(size / 1048576.0)
            size >= 1024 -> "%.1f KB".format(size / 1024.0)
            else -> "$size B"
        }
}

@Serializable
data class WorkspaceQuota(
    val user: String = "",
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("quota_bytes") val quotaBytes: Long = 100L * 1024 * 1024,
    @SerialName("remaining_bytes") val remainingBytes: Long = 100L * 1024 * 1024,
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("max_files") val maxFiles: Int = 2000,
    @SerialName("max_file_bytes") val maxFileBytes: Long = 32L * 1024 * 1024,
    /** 闲置多少小时后清空工作区 */
    @SerialName("retention_hours") val retentionHours: Int = 24,
    /** 最后一次操作时间（Unix 秒） */
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
    /** 到期时间（Unix 秒） */
    @SerialName("expires_at") val expiresAt: Long = 0,
) {
    val usedMb: Double get() = usedBytes / 1048576.0
    val totalMb: Double get() = quotaBytes / 1048576.0
    val freeMb: Double get() = remainingBytes / 1048576.0

    val usedFraction: Float
        get() = if (quotaBytes <= 0) 0f else (usedBytes.toFloat() / quotaBytes).coerceIn(0f, 1f)

    val usedLabel: String get() = "%.1f MB / %.0f MB".format(usedMb, totalMb)
    val freeLabel: String get() = "剩余 %.1f MB".format(freeMb)

    /** 距离清空还有多少秒。已过期或未知返回 0。 */
    fun remainingSeconds(nowSeconds: Long): Long =
        if (expiresAt <= 0) 0 else (expiresAt - nowSeconds).coerceAtLeast(0)

    /** 例如「还剩 23 小时 12 分」 */
    fun remainingLabel(nowSeconds: Long): String {
        val s = remainingSeconds(nowSeconds)
        if (s <= 0) return "即将清空"
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "还剩 ${h} 小时 ${m} 分" else "还剩 ${m} 分钟"
    }

    /** 闲置比例 0f..1f，用于进度条（越满越接近清空） */
    fun idleFraction(nowSeconds: Long): Float {
        if (retentionHours <= 0 || lastActivityAt <= 0) return 0f
        val total = retentionHours * 3600L
        val idle = (nowSeconds - lastActivityAt).coerceAtLeast(0)
        return (idle.toFloat() / total).coerceIn(0f, 1f)
    }
}

/** 扫描发现的问题。 */
@Serializable
data class ScanFinding(
    val id: String = "",
    val level: String = "warn",
    val message: String = "",
    val snippet: String = "",
)

@Serializable
data class ScanReport(
    val level: String = "clean",
    val blocked: Boolean = false,
    val findings: List<ScanFinding> = emptyList(),
) {
    val isClean: Boolean get() = level == "clean"
    val hasWarnings: Boolean get() = level == "warn"
}

@Serializable
data class FileContent(
    val path: String = "",
    val content: String = "",
    val size: Long = 0,
    val warnings: List<ScanFinding> = emptyList(),
)

sealed interface WorkspaceResult<out T> {
    data class Ok<T>(val value: T) : WorkspaceResult<T>
    data class Err(val message: String, val detail: String? = null) : WorkspaceResult<Nothing>
}

class WorkspaceRepository(
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val fast: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // AI 一次可能写较大的文件
    private val upload: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun token(): String? =
        runCatching { tokenProvider() }.getOrNull()?.trim()?.removePrefix("Bearer ")?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun humanize(e: Throwable?): String = when {
        e == null -> "操作失败，请稍后重试"
        e is java.net.UnknownHostException -> "网络不可用，请检查网络连接"
        e is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
        e is javax.net.ssl.SSLException -> "网络连接不安全，请检查网络环境"
        e is IOException && e.message?.contains("未登录") == true -> e.message ?: "请先登录"
        e is kotlinx.serialization.SerializationException -> "服务端返回数据格式异常"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
    }

    private fun execute(req: Request, c: OkHttpClient): Result<JsonObject> = try {
        c.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)
                        ?.contentOrNull
                }.getOrNull()
                Result.failure(
                    IOException(
                        when (resp.code) {
                            401, 403 -> msg ?: "登录已失效，请重新登录"
                            413 -> msg ?: "超出配额或文件过大"
                            in 500..599 -> msg ?: "服务端错误（HTTP ${resp.code}）"
                            else -> msg ?: "请求失败（HTTP ${resp.code}）"
                        }
                    )
                )
            } else {
                val root = json.parseToJsonElement(text).jsonObject
                val ok = (root["success"] as? JsonPrimitive)?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: true
                if (!ok) {
                    Result.failure(
                        IOException(
                            (root["message"] as? JsonPrimitive)?.contentOrNull ?: "服务端返回失败"
                        )
                    )
                } else {
                    Result.success(root)
                }
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun data(root: JsonObject): JsonObject =
        root["data"] as? JsonObject ?: JsonObject(emptyMap())

    // ---------------- 配额 ----------------

    suspend fun quota(): WorkspaceResult<WorkspaceQuota> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
        val req = Request.Builder()
            .url("$BASE_URL/api/ws/quota")
            .get()
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/json")
            .build()
        execute(req, fast).fold(
            onSuccess = { root ->
                runCatching {
                    json.decodeFromJsonElement(WorkspaceQuota.serializer(), data(root))
                }.fold(
                    onSuccess = { WorkspaceResult.Ok(it) },
                    onFailure = { WorkspaceResult.Err("配额数据解析失败", it.toString()) },
                )
            },
            onFailure = { WorkspaceResult.Err(humanize(it), it.toString()) },
        )
    }

    // ---------------- 文件树 ----------------

    suspend fun tree(path: String = ""): WorkspaceResult<List<WorkspaceEntry>> =
        withContext(Dispatchers.IO) {
            val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
            val url = StringBuilder("$BASE_URL/api/ws/tree")
            if (path.isNotBlank()) url.append("?path=").append(enc(path))
            val req = Request.Builder()
                .url(url.toString())
                .get()
                .header("Authorization", "Bearer $t")
                .header("Accept", "application/json")
                .build()
            execute(req, fast).fold(
                onSuccess = { root ->
                    val arr = data(root)["entries"] as? JsonArray ?: JsonArray(emptyList())
                    runCatching {
                        json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                WorkspaceEntry.serializer()
                            ),
                            arr,
                        )
                    }.fold(
                        onSuccess = { WorkspaceResult.Ok(it) },
                        onFailure = { WorkspaceResult.Err("文件列表解析失败", it.toString()) },
                    )
                },
                onFailure = { WorkspaceResult.Err(humanize(it), it.toString()) },
            )
        }

    // ---------------- 读文件 ----------------

    suspend fun read(path: String): WorkspaceResult<FileContent> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
        val req = Request.Builder()
            .url("$BASE_URL/api/ws/read?path=${enc(path)}")
            .get()
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/json")
            .build()
        execute(req, fast).fold(
            onSuccess = { root ->
                runCatching {
                    json.decodeFromJsonElement(FileContent.serializer(), data(root))
                }.fold(
                    onSuccess = { WorkspaceResult.Ok(it) },
                    onFailure = { WorkspaceResult.Err("文件内容解析失败", it.toString()) },
                )
            },
            onFailure = { WorkspaceResult.Err(humanize(it), it.toString()) },
        )
    }

    // ---------------- 写文件 ----------------

    /**
     * 写入文件。服务端会做危险内容扫描，被拦截时返回 Err 并附带原因。
     */
    suspend fun write(
        path: String,
        content: String,
        charset: java.nio.charset.Charset = Charsets.UTF_8,
    ): WorkspaceResult<ScanReport> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
        val bytes = content.toByteArray(charset)
        if (bytes.size > MAX_FILE_BYTES) {
            return@withContext WorkspaceResult.Err(
                "单文件不能超过 ${MAX_FILE_BYTES / 1048576}MB"
            )
        }
        val req = Request.Builder()
            .url("$BASE_URL/api/ws/write?path=${enc(path)}")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/json")
            .build()
        execute(req, upload).fold(
            onSuccess = { root ->
                val scan = runCatching {
                    json.decodeFromJsonElement(
                        ScanReport.serializer(),
                        data(root)["scan"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                }.getOrDefault(ScanReport())
                WorkspaceResult.Ok(scan)
            },
            onFailure = { WorkspaceResult.Err(humanize(it), it.toString()) },
        )
    }

    // ---------------- 删除 ----------------

    suspend fun delete(path: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
        val req = Request.Builder()
            .url("$BASE_URL/api/ws/delete?path=${enc(path)}")
            .delete()
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/json")
            .build()
        execute(req, fast).fold(
            onSuccess = { WorkspaceResult.Ok(Unit) },
            onFailure = { WorkspaceResult.Err(humanize(it), it.toString()) },
        )
    }

    // ---------------- 建目录 ----------------

    suspend fun mkdir(path: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
        val req = Request.Builder()
            .url("$BASE_URL/api/ws/mkdir?path=${enc(path)}")
            .post("".toRequestBody(null))
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/json")
            .build()
        execute(req, fast).fold(
            onSuccess = { WorkspaceResult.Ok(Unit) },
            onFailure = { WorkspaceResult.Err(humanize(it), it.toString()) },
        )
    }

    // ---------------- 部署 ----------------

    /**
     * 部署：确认工作区里有可访问的入口文件，返回公网地址。
     *
     * 服务端没有"部署"这个动作——工作区本身就是可访问的静态目录。
     * 这里只是把入口地址算出来给用户。
     */
    suspend fun deploy(sitePath: String = ""): WorkspaceResult<String> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext WorkspaceResult.Err("未登录，请先登录账号")
        // 先确认配额接口能拿到用户名（顺带验证登录态）
        val q = quota()
        if (q is WorkspaceResult.Err) return@withContext q

        val entries = tree(sitePath)
        if (entries is WorkspaceResult.Err) return@withContext entries
        val list = (entries as WorkspaceResult.Ok).value

        val hasIndex = list.any { !it.isDir && it.name.equals("index.html", ignoreCase = true) }
        if (!hasIndex) {
            return@withContext WorkspaceResult.Err(
                "该目录没有 index.html，无法作为网站入口。请先创建 index.html。"
            )
        }

        val user = (q as WorkspaceResult.Ok).value.user
        val hash = sha256Hex16(user)
        val suffix = if (sitePath.isBlank()) "" else "/" + sitePath.trim('/')
        WorkspaceResult.Ok("$BASE_URL/w/$hash$suffix/")
    }

    companion object {
        const val BASE_URL = "https://gcmod.bond"
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /** 与服务端 user_hash 完全一致：sha256(username) 前 16 位。 */
        fun sha256Hex16(username: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(username.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
