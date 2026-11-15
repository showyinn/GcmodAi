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
 * 为什么加固放服务器：dex2c 要 NDK+LLVM、OLLVM 是编译期 pass、DEX 重写要 apktool+smali，
 * 手机端既装不下也跑不动。App 只做「选 APK → 配参数 → 轮询进度 → 下载结果」。
 *
 * 配置项对照云镜那套思路：模式（最兼容/标准/深度）、保护类规则、自定义入口类与 so 名、
 * 签名 MD5 校验、Root / Hook / 抓包检测、多架构选择。
 *
 * 认证沿用 App 已有的用户令牌（Bearer），服务端用它打 /api/user/self 校验。
 */
class HardenClient(
    private val client: OkHttpClient,
    private val userRepository: UserRepository,
) {

    data class Options(
        /** 加固模式：safe（最兼容）/ standard（推荐）/ deep（混淆 + dex2c + 防护） */
        val mode: String = "standard",
        val stripSmaliDebug: Boolean = true,
        val stripSo: Boolean = true,
        val renameClasses: Boolean = false,
        /** 勾选要混淆（重命名）的类描述符，形如 Lcom/foo/Bar; */
        val obfuscateClasses: List<String> = emptyList(),
        /** 勾选要 dex2c 抽取到 native 的类描述符 */
        val dex2cClasses: List<String> = emptyList(),
        /** 类规则：+com.foo.* 包含 / -com.foo.Bar 排除，每行一条（服务端算成类清单） */
        val classRules: List<String> = emptyList(),
        /** 字符串加密：对命中的类加密 const-string 字面量（同样支持 +包含/-排除 规则） */
        val stringFogRules: List<String> = emptyList(),
        /** 反调试守卫：注入保护 .so（ptrace / TracerPid / Frida 等检测，命中即退出） */
        val antiDebug: Boolean = false,
        val rootCheck: Boolean = false,
        val hookCheck: Boolean = false,
        val proxyCheck: Boolean = false,
        /** 签名校验：期望的 32 位小写签名 MD5，空=不校验 */
        val sigMd5: String = "",
        /** 自定义入口类（注入 loadLibrary 的落点），空=自动找 Application */
        val customEntryClass: String = "",
        /** 自定义 so 名，空=GcmodAi（dex2c）/ GcmodGuard（守卫） */
        val customSoName: String = "",
        /** 只对守卫与 dex2c 生效的 ABI 列表，空=按 APK 现有 ABI */
        val abis: List<String> = emptyList(),
        /** 用你自己的签名证书，加固后的包才能覆盖安装（不传则用服务器证书，需卸载重装） */
        val keystore: ByteArray? = null,
        val keystorePass: String = "",
        val keyAlias: String = "",
        val keyPass: String = "",

        // ---- XopProtector 引擎（DEX 加密 + 壳 + VMP + SO 加密 + RASP）----

        /**
         * 引擎选择：`classic`（apktool 流水线）/ `xop`（XopProtector 壳）/ `both`（先 classic 再 xop）。
         *
         * 为什么允许 both：apktool 那条负责去调试信息、类重命名、StringFog、dex2c，
         * XopProtector 负责整体加壳与 DEX 加密。两者互补，先瘦身再加壳效果最好。
         */
        val engine: String = "classic",

        /**
         * 空壳策略档位：balanced / industry / aggressive / perf / max。
         *
         * 这是「多少类被抽空」的刻度：balanced 只抽 applicationId 包，
         * max 几乎全抽（跳过 android/ 和 androidx/）。抽得越多防护越强、启动越慢。
         */
        val xopProfile: String = "balanced",

        /** 允许被抽空的类前缀，形如 Lcom/foo；空=按 profile 自动 */
        val hollowPrefix: List<String> = emptyList(),

        /** PVM1 虚拟化打包的方法所属类前缀（不是解释器，只是打包后还原成 Dalvik） */
        val vmpPrefix: List<String> = emptyList(),

        /** PVM2 真 VMP：JNI 蹦床 + native 解释执行，强度最高、兼容性风险也最高 */
        val trueVmpPrefix: List<String> = emptyList(),

        /** 业务 SO 的 .text 段 RC4 加密：safe / aggressive / max */
        val protectSoMode: String = "safe",

        /** SO 加密体积预算（MB），超过就不再加密，防止大引擎把包撑爆 */
        val protectSoBudgetMb: Int = 12,

        /** 单个 SO 解压后超过这个大小就跳过（MB） */
        val protectSoMaxFileMb: Int = 8,

        /** SO 解密时机：eager=冷启动全解密（稳定）；lazy=首次 dlopen 时解密（启动快） */
        val soDecryptMode: String = "eager",

        /** 把 assets 目录加密到 protector/aenc（PAS1 + AES-GCM） */
        val encryptAssets: Boolean = false,

        /** 缩短 res/ 路径并重写 resources.arsc，增加静态分析难度 */
        val resProtect: Boolean = false,

        /** RASP 命中后的动作：report（只上报）/ exit（直接退出） */
        val raspAction: String = "report",

        /** 打渠道号（Walle 兼容），需要提供 keystore */
        val channel: String = "",
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
        /** 不可抽取的原因（服务端安全体检结论，直接展示给用户） */
        @SerialName("dex2c_reason") val dex2cReason: String = "",
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

    /** 任务列表条目（加固页「任务列表」用） */
    @Serializable
    data class JobSummary(
        val id: String = "",
        val state: String = "",
        val created: Long = 0,
        val elapsed: Long = 0,
        val size: Long? = null,
        @SerialName("logTail") val logTail: String = "",
        val downloadable: Boolean = false,
    )

    @Serializable
    private data class JobListData(val jobs: List<JobSummary> = emptyList())

    @Serializable
    private data class Envelope<T>(val success: Boolean = false, val data: T? = null, val message: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun token(): String? = userRepository.sessionFlow.value?.accessToken

    private fun optionsJson(o: Options): String = buildString {
        append("{\"mode\":\"").append(o.mode).append("\"")
        append(",\"strip_smali_debug\":").append(o.stripSmaliDebug)
        append(",\"strip_so\":").append(o.stripSo)
        append(",\"rename_classes\":").append(o.renameClasses)
        append(",\"obfuscate_classes\":").append(
            o.obfuscateClasses.joinToString(",", "[", "]") { "\"" + it + "\"" }
        )
        append(",\"dex2c_classes\":").append(
            o.dex2cClasses.joinToString(",", "[", "]") { "\"" + it + "\"" }
        )
        append(",\"class_rules\":").append(
            o.classRules.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
        )
        append(",\"string_fog_rules\":").append(
            o.stringFogRules.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
        )
        append(",\"anti_debug\":").append(o.antiDebug)
        append(",\"root_check\":").append(o.rootCheck)
        append(",\"hook_check\":").append(o.hookCheck)
        append(",\"proxy_check\":").append(o.proxyCheck)
        append(",\"sig_md5\":\"").append(o.sigMd5).append("\"")
        append(",\"custom_entry_class\":\"").append(o.customEntryClass).append("\"")
        append(",\"custom_so_name\":\"").append(o.customSoName).append("\"")
        append(",\"abis\":").append(
            o.abis.joinToString(",", "[", "]") { "\"" + it + "\"" }
        )

        // ---- XopProtector 引擎参数 ----
        // 全部小写下划线，和服务端 svc.py 的选项名保持一致；
        // 空数组/空串也照传，服务端据此判断「不启用」而不是「没收到」
        append(",\"engine\":\"").append(o.engine).append("\"")
        append(",\"xop_profile\":\"").append(o.xopProfile).append("\"")
        append(",\"hollow_prefix\":").append(
            o.hollowPrefix.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
        )
        append(",\"vmp_prefix\":").append(
            o.vmpPrefix.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
        )
        append(",\"true_vmp_prefix\":").append(
            o.trueVmpPrefix.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
        )
        append(",\"protect_so_mode\":\"").append(o.protectSoMode).append("\"")
        append(",\"protect_so_budget_mb\":").append(o.protectSoBudgetMb)
        append(",\"protect_so_max_file_mb\":").append(o.protectSoMaxFileMb)
        append(",\"so_decrypt_mode\":\"").append(o.soDecryptMode).append("\"")
        append(",\"encrypt_assets\":").append(o.encryptAssets)
        append(",\"res_protect\":").append(o.resProtect)
        append(",\"rasp_action\":\"").append(o.raspAction).append("\"")
        append(",\"channel\":\"").append(o.channel.replace("\"", "")).append("\"")
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

    /** 最近的任务列表（进行中 / 已完成都在里面，按创建时间倒序） */
    suspend fun listJobs(limit: Int = 30): Result<List<JobSummary>> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext Result.failure(IllegalStateException("请先登录账号"))
        runCatching {
            val req = Request.Builder()
                .url("$BASE_URL/api/harden/jobs?limit=$limit")
                .header("Authorization", "Bearer $t")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("查询失败 ${resp.code}")
                json.decodeFromString(Envelope.serializer(JobListData.serializer()), body).data?.jobs
                    ?: emptyList()
            }
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

        /** 架构选项：云镜里的「32位 / 64位 / 模拟器 / 实体机」映射到具体 ABI */
        val ABI_CHOICES = listOf(
            "arm64-v8a" to "64位真机",
            "armeabi-v7a" to "32位真机",
            "x86_64" to "64位模拟器",
            "x86" to "32位模拟器",
        )
    }
}
