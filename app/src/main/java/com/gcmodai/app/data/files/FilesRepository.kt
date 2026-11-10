package com.gcmodai.app.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 代码产物托管客户端。
 *
 * 与手机本地 Web 服务器的区别（这是这次替换的核心）：
 * - 本地 Web 服务器只能同局域网访问，移动数据下不可用；
 * - 托管服务在公网，任何网络下都能访问，链接可直接分享。
 *
 * 配额规则由服务端强制（每人 100MB、保留 24 小时），客户端只做展示。
 */

@Serializable
data class HostedFile(
    val id: String = "",
    /** 目录/站点名 */
    val path: String = "",
    /** 目录访问前缀 */
    val url: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("retention_hours") val retentionHours: Int = 24,
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("files") val files: List<String> = emptyList(),
    @SerialName("index_url") val indexUrl: String = "",
) {
    val name: String get() = path.ifBlank { id }

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024 * 1024 -> "%.2f MB".format(sizeBytes / 1048576.0)
            sizeBytes >= 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }

    val fullUrl: String
        get() {
            val rel = indexUrl.ifBlank { url }
            return if (rel.startsWith("http")) rel else FilesRepository.BASE_URL + rel
        }

    val isDeployableSite: Boolean
        get() = indexUrl.endsWith("/index.html") || files.any { it == "index.html" }

    fun remainingSeconds(nowSeconds: Long): Long =
        (expiresAt - nowSeconds).coerceAtLeast(0)

    fun remainingLabel(nowSeconds: Long): String {
        val s = remainingSeconds(nowSeconds)
        if (s <= 0) return "已过期"
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }
}

@Serializable
data class FilesQuota(
    val user: String = "",
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("quota_bytes") val quotaBytes: Long = 100L * 1024 * 1024,
    @SerialName("remaining_bytes") val remainingBytes: Long = 100L * 1024 * 1024,
    @SerialName("retention_hours") val retentionHours: Int = 24,
) {
    val usedMb: Double get() = usedBytes / 1048576.0
    val totalMb: Double get() = quotaBytes / 1048576.0
    val freeMb: Double get() = remainingBytes / 1048576.0

    val usedFraction: Float
        get() = if (quotaBytes <= 0) 0f else (usedBytes.toFloat() / quotaBytes).coerceIn(0f, 1f)

    val usedLabel: String get() = "%.1f MB / %.0f MB".format(usedMb, totalMb)
    val freeLabel: String get() = "剩余 %.1f MB".format(freeMb)
}

sealed interface UploadResult {
    data class Success(val file: HostedFile) : UploadResult
    data class Rejected(val message: String) : UploadResult
    data class Failed(val message: String) : UploadResult
}

/**
 * 一次拉取的结果。
 *
 * 为什么不再用 `null` 表示失败：null 无法区分「没登录」「超时」「服务端 500」
 * 「JSON 字段不匹配」，上一版正是因此只能对用户说一句笼统的「无法获取托管信息」。
 */
sealed interface FilesFetch {
    data class Ok(val quota: FilesQuota?, val files: List<HostedFile>) : FilesFetch
    data class Error(val message: String, val detail: String? = null) : FilesFetch
}

class FilesRepository(
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    // 上传可能慢，单独放宽超时
    private val uploadClient: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // 日常接口：移动网络下首包可能慢，给足余量，避免误判为失败
    private val fastClient: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun base() = BASE_URL

    /**
     * 取 token。为空时直接给明确错误，而不是打一次必然 401 的请求。
     *
     * 这里做一次清洗：token 可能带首尾空白或换行（从剪贴板/旧存储来的），
     * 直接拼进 Authorization 头会导致服务端判为无效，表现为「已登录但拿不到数据」。
     */
    private suspend fun requireToken(): Result<String> {
        val raw = runCatching { tokenProvider() }.getOrNull()
        val t = raw?.trim()?.removePrefix("Bearer ")?.trim()
        return if (t.isNullOrBlank()) {
            Result.failure(IOException("未登录或登录状态已失效，请先登录账号"))
        } else {
            Result.success(t)
        }
    }

    /**
     * 一次拉取配额与列表。
     *
     * 两者并行请求，且互不遮蔽：配额成功就显示配额，列表失败只影响列表。
     */
    suspend fun fetch(): FilesFetch = withContext(Dispatchers.IO) {
        val tokenRes = requireToken()
        val token = tokenRes.getOrElse {
            return@withContext FilesFetch.Error(it.message ?: "未登录")
        }

        coroutineScope {
            val quotaDeferred = async { requestQuota(token) }
            val listDeferred = async { requestList(token) }
            val quotaRes = quotaDeferred.await()
            val listRes = listDeferred.await()

            val quota = quotaRes.getOrNull()
            val files = listRes.getOrNull()

            // 两个都失败才算整体失败，并把最具体的原因报出来
            if (quota == null && files == null) {
                val err = quotaRes.exceptionOrNull() ?: listRes.exceptionOrNull()
                return@coroutineScope FilesFetch.Error(
                    message = humanize(err),
                    detail = err?.toString(),
                )
            }

            FilesFetch.Ok(quota = quota, files = files ?: emptyList())
        }
    }

    private fun humanize(e: Throwable?): String = when {
        e == null -> "无法获取托管信息，请稍后重试"
        e is java.net.UnknownHostException -> "网络不可用，请检查网络连接"
        e is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
        e is javax.net.ssl.SSLException -> "网络连接不安全，请检查网络环境"
        e is IOException && e.message?.contains("未登录") == true ->
            e.message ?: "请先登录账号"
        e is kotlinx.serialization.SerializationException -> "服务端返回数据格式异常，请更新 App"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "无法获取托管信息，请稍后重试"
    }

    private fun executeJson(req: Request, client: OkHttpClient): Result<JsonObject> {
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        val o = json.parseToJsonElement(text).jsonObject
                        (o["message"] as? JsonPrimitive)?.contentOrNull
                    }.getOrNull()
                    return Result.failure(
                        IOException(
                            when (resp.code) {
                                401, 403 -> msg ?: "登录已失效，请重新登录"
                                404 -> "服务接口不存在（HTTP 404）"
                                413 -> msg ?: "文件超出大小限制"
                                in 500..599 -> msg ?: "服务端错误（HTTP ${resp.code}）"
                                else -> msg ?: "请求失败（HTTP ${resp.code}）"
                            }
                        )
                    )
                }
                val root = json.parseToJsonElement(text).jsonObject
                val ok = (root["success"] as? JsonPrimitive)?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: true
                if (!ok) {
                    val msg = (root["message"] as? JsonPrimitive)?.contentOrNull
                    return Result.failure(IOException(msg ?: "服务端返回失败"))
                }
                Result.success(root)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requestQuota(token: String): Result<FilesQuota> {
        val req = Request.Builder()
            .url("${base()}/api/files/quota")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return executeJson(req, fastClient).mapCatching { root ->
            val data = root["data"] as? JsonObject
                ?: throw IOException("配额接口返回缺少 data 字段")
            json.decodeFromJsonElement(FilesQuota.serializer(), data)
        }
    }

    private fun requestList(token: String): Result<List<HostedFile>> {
        val req = Request.Builder()
            .url("${base()}/api/files/list")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return executeJson(req, fastClient).mapCatching { root ->
            val data = root["data"] as? JsonObject
                ?: throw IOException("列表接口返回缺少 data 字段")
            val arr = data["items"] ?: return@mapCatching emptyList()
            json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(HostedFile.serializer()),
                arr,
            )
        }
    }

    /** 兼容旧调用：只要配额。 */
    suspend fun quota(): FilesQuota? = when (val r = fetch()) {
        is FilesFetch.Ok -> r.quota
        is FilesFetch.Error -> null
    }

    /** 兼容旧调用：只要列表。 */
    suspend fun list(): List<HostedFile> = when (val r = fetch()) {
        is FilesFetch.Ok -> r.files
        is FilesFetch.Error -> emptyList()
    }

    suspend fun upload(name: String, bytes: ByteArray, entry: String = ""): UploadResult =
        withContext(Dispatchers.IO) {
            val token = requireToken().getOrElse {
                return@withContext UploadResult.Failed(it.message ?: "未登录")
            }

            val safeName = name.trim().ifBlank { "file.txt" }
            if (bytes.isEmpty()) return@withContext UploadResult.Rejected("文件内容为空")
            if (bytes.size > MAX_FILE_BYTES) {
                return@withContext UploadResult.Rejected(
                    "单文件不能超过 ${MAX_FILE_BYTES / 1048576}MB"
                )
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    safeName,
                    bytes.toRequestBody("application/octet-stream".toMediaType()),
                )
                .build()

            val urlBuilder = StringBuilder("${base()}/api/files/upload")
                .append("?name=").append(java.net.URLEncoder.encode(safeName, "UTF-8"))
            if (entry.isNotBlank()) {
                urlBuilder.append("&entry=")
                    .append(java.net.URLEncoder.encode(entry.trim(), "UTF-8"))
            }

            val req = Request.Builder()
                .url(urlBuilder.toString())
                .post(body)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            runCatching {
                uploadClient.newCall(req).execute().use { resp ->
                    val text = resp.body.string()
                    val root = runCatching {
                        json.parseToJsonElement(text).jsonObject
                    }.getOrNull()

                    val success = (root?.get("success") as? JsonPrimitive)
                        ?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    val message = (root?.get("message") as? JsonPrimitive)?.contentOrNull

                    if (!success) {
                        val msg = message ?: "上传失败（HTTP ${resp.code}）"
                        return@use if (msg.contains("配额") || msg.contains("超") ||
                            msg.contains("quota") || resp.code == 413
                        ) {
                            UploadResult.Rejected(msg)
                        } else {
                            UploadResult.Failed(msg)
                        }
                    }

                    val data = root?.get("data") as? JsonObject
                    val file = data?.let {
                        runCatching {
                            json.decodeFromJsonElement(HostedFile.serializer(), it)
                        }.getOrNull()
                    }
                    if (file == null) UploadResult.Failed("服务端返回格式异常")
                    else UploadResult.Success(file)
                }
            }.getOrElse { e ->
                UploadResult.Failed(humanize(e))
            }
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val token = requireToken().getOrElse { return@withContext false }
        val req = Request.Builder()
            .url("${base()}/api/files/delete?id=${java.net.URLEncoder.encode(id, "UTF-8")}")
            .delete()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        executeJson(req, fastClient).isSuccess
    }

    companion object {
        const val BASE_URL = "http://38.76.219.151"
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        val DEPLOYABLE_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "json", "svg", "png", "jpg", "jpeg",
            "gif", "webp", "ico", "txt", "md", "xml", "wasm", "map",
        )
    }
}
