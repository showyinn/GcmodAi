package com.gcmodai.app.data.harden

import android.util.Log
import com.gcmodai.app.data.user.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 服务器端加固客户端。
 *
 * 为什么加固放服务器：dex2c 要 NDK+LLVM、Ollvm 是编译期 pass、DEX 重写要 apktool+smali，
 * 手机端既装不下也跑不动。App 只做「上传 APK → 轮询进度 → 下载加固结果」。
 *
 * 认证沿用 App 已有的用户令牌（Bearer），服务端用它打 /api/user/self 校验。
 */
class HardenClient(
    private val client: OkHttpClient,
    private val userRepository: UserRepository,
) {

    data class Options(
        val stripSmaliDebug: Boolean = true,
        val stripSo: Boolean = true,
        val renameClasses: Boolean = false,
        /** 勾选要混淆（重命名）的类描述符，形如 Lcom/foo/Bar; */
        val obfuscateClasses: List<String> = emptyList(),
        /** 勾选要 dex2c 抽取到 native 的类描述符 */
        val dex2cClasses: List<String> = emptyList(),
        /** 反调试守卫：注入 libGcmodGuard.so（ptrace / TracerPid / Frida 三重检测，命中即退出） */
        val antiDebug: Boolean = false,
        /** 用你自己的签名证书，加固后的包才能覆盖安装（不传则用服务器证书，需卸载重装） */
        val keystore: ByteArray? = null,
        val keystorePass: String = "",
        val keyAlias: String = "",
        val keyPass: String = "",
    )

    @Serializable
    data class JobRef(@SerialName("id") val id: String = "", val state: String = "")

    /** 扫描出的一个类：descriptor 用于提交，obfuscatable / dex2cOk 决定勾选框能否点。 */
    @Serializable
    data class DexClass(
        val descriptor: String = "",
        val name: String = "",
        val methods: Int = 0,
        val size: Long = 0,
        val obfuscatable: Boolean = true,
        @SerialName("dex2c_ok") val dex2cOk: Boolean = true,
    )

    @Serializable
    data class JobStatus(
        val id: String = "",
        val state: String = "",
        val size: Long? = null,
        val elapsed: Long = 0,
        val log: List<String> = emptyList(),
        val classes: List<DexClass> = emptyList(),
    )

    @Serializable
    private data class Envelope<T>(val success: Boolean = false, val data: T? = null, val message: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun token(): String? = userRepository.sessionFlow.value?.accessToken

    private fun optionsJson(o: Options): String = buildString {
        append("{\"strip_smali_debug\":").append(o.stripSmaliDebug)
        append(",\"strip_so\":").append(o.stripSo)
        append(",\"rename_classes\":").append(o.renameClasses)
        append(",\"obfuscate_classes\":").append(
            o.obfuscateClasses.joinToString(",", "[", "]") { "\"" + it + "\"" }
        )
        append(",\"dex2c_classes\":").append(
            o.dex2cClasses.joinToString(",", "[", "]") { "\"" + it + "\"" }
        )
        append(",\"anti_debug\":").append(o.antiDebug)
        append('}')
    }

    suspend fun submit(apk: ByteArray, options: Options): Result<String> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext Result.failure(IllegalStateException("请先登录账号"))
        runCatching {
            val builder = Request.Builder()
                .url("$BASE_URL/api/harden/jobs")
                .post(apk.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", "Bearer $t")
                .header("X-Options", optionsJson(options))
            options.keystore?.let { ks ->
                builder.header("X-Keystore-Base64", android.util.Base64.encodeToString(ks, android.util.Base64.NO_WRAP))
                builder.header("X-Keystore-Pass", options.keystorePass)
                builder.header("X-Key-Alias", options.keyAlias)
                builder.header("X-Key-Pass", options.keyPass)
            }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("提交失败 ${resp.code}：${body.take(160)}")
                val env = json.decodeFromString(Envelope.serializer(JobRef.serializer()), body)
                val id = env.data?.id
                if (env.success && !id.isNullOrBlank()) id else error(env.message ?: "提交失败")
            }
        }
    }

    /**
     * 扫描 APK 里可勾选的类。
     *
     * 服务端零留存：扫描任务结束后即删除反编译产物，所以这里只拿类清单；
     * 真正加固时需要再上传一次同一个 APK（文件本来就在本地）。
     */
    suspend fun scan(apk: ByteArray): Result<List<DexClass>> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext Result.failure(IllegalStateException("请先登录账号"))
        runCatching {
            val req = Request.Builder()
                .url("$BASE_URL/api/harden/scan")
                .post(apk.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", "Bearer " + t)
                .build()
            val body = client.newCall(req).execute().use { it.body.string() }
            val jobId = json.decodeFromString(Envelope.serializer(JobRef.serializer()), body).data?.id
                ?: error("扫描提交失败")
            var attempt = 0
            while (attempt++ < 150) {
                val st = status(jobId).getOrThrow()
                if (st.classes.isNotEmpty()) return@runCatching st.classes
                if (st.state == "failed") error("扫描失败：${st.log.lastOrNull() ?: ""}")
                delay(2500)
            }
            error("扫描超时，请稍后重试")
        }
    }

    suspend fun status(jobId: String): Result<JobStatus> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext Result.failure(IllegalStateException("请先登录账号"))
        runCatching {
            val req = Request.Builder()
                .url("$BASE_URL/api/harden/jobs/$jobId")
                .header("Authorization", "Bearer $t")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("查询失败 ${resp.code}")
                json.decodeFromString(Envelope.serializer(JobStatus.serializer()), body).data
                    ?: error("返回为空")
            }
        }
    }

    suspend fun download(jobId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext Result.failure(IllegalStateException("请先登录账号"))
        runCatching {
            val req = Request.Builder()
                .url("$BASE_URL/api/harden/jobs/$jobId/result")
                .header("Authorization", "Bearer $t")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败 ${resp.code}")
                resp.body.bytes()
            }
        }.onFailure { Log.w("HardenClient", "download failed: ${it.message}") }
    }

    companion object {
        /** 加固服务与下载站同机，直接走 gcmod.bond（不经过 CDN） */
        const val BASE_URL = "https://gcmod.bond"
    }
}
