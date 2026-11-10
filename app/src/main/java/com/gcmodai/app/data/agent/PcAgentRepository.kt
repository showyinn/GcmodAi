package com.gcmodai.app.data.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * 一台通过「GcmodAi Windows 助手（exe）」连接的电脑。
 *
 * 为什么用 exe 而不是 SSH：普通用户配 SSH 要开服务、放行防火墙、
 * 查 IP、填账号密码，一步错就"连接被拒绝"，完全不知道从哪查。
 * 助手是双击即用的单个 exe，把窗口里的地址和口令填进来就行。
 */
@Serializable
data class PcAgent(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    /** 形如 http://192.168.5.96:8787 */
    val url: String = "",
    /** 助手启动时打印的口令 */
    val token: String = "",
)

/** 助手返回的执行结果。 */
data class AgentExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long = 0,
)

/** 助手返回的系统信息。 */
data class AgentInfo(
    val hostname: String = "",
    val os: String = "",
    val user: String = "",
    val cwd: String = "",
    val ips: List<String> = emptyList(),
    val drives: List<String> = emptyList(),
)

sealed interface AgentResult<out T> {
    data class Ok<T>(val value: T) : AgentResult<T>
    data class Err(val message: String, val detail: String? = null) : AgentResult<Nothing>
}

/** 助手配置的本地存储：单独一个文件，清设置不会误删。 */
class PcAgentStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs
        get() = context.applicationContext
            .getSharedPreferences("gcmodai_pc_agents", Context.MODE_PRIVATE)

    fun load(): List<PcAgent> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PcAgent.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun save(agents: List<PcAgent>) {
        prefs.edit()
            .putString(KEY, json.encodeToString(ListSerializer(PcAgent.serializer()), agents))
            .apply()
    }

    private companion object {
        const val KEY = "agents"
    }
}

/**
 * 与 Windows 助手通信。
 *
 * 协议都是 JSON：
 *   GET  /ping            探活（免口令）
 *   GET  /info            系统信息（含 IP、盘符）
 *   GET  /ls?path=        列目录
 *   GET  /read?path=      读文件
 *   POST /exec            执行命令
 *   POST /write           写文件
 */
class PcAgentRepository(
    client: OkHttpClient,
    private val store: PcAgentStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 装软件、编译可能跑很久，给足超时
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun agents(): List<PcAgent> = store.load()

    fun upsert(agent: PcAgent) {
        val list = agents().toMutableList()
        val idx = list.indexOfFirst { it.id == agent.id }
        if (idx >= 0) list[idx] = agent else list.add(agent)
        store.save(list)
    }

    fun delete(id: String) {
        store.save(agents().filterNot { it.id == id })
    }

    private fun humanize(e: Throwable?): String = when {
        e == null -> "操作失败"
        e is java.net.UnknownHostException -> "找不到这台电脑：地址写错了，或不在同一 Wi-Fi"
        e is java.net.SocketTimeoutException -> "连接超时：助手可能已关闭，或防火墙拦了"
        e is java.net.ConnectException -> "连不上：助手没在运行、端口写错，或被防火墙拦截"
        e is javax.net.ssl.SSLException -> "网络连接不安全"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "操作失败"
    }

    private fun buildRequest(agent: PcAgent, path: String, post: JsonObject?): Request {
        val base = agent.url.trim().trimEnd('/')
        val builder = Request.Builder()
            .url(base + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${agent.token.trim()}")
        if (post == null) builder.get() else
            builder.post(post.toString().toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    private fun call(
        agent: PcAgent,
        path: String,
        post: JsonObject? = null,
    ): AgentResult<JsonObject> = try {
        http.newCall(buildRequest(agent, path, post)).execute().use { resp ->
            val text = resp.body.string()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val success = (root?.get("success") as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            if (!resp.isSuccessful || !success) {
                AgentResult.Err(
                    (root?.get("message") as? JsonPrimitive)?.contentOrNull
                        ?: "请求失败（HTTP ${resp.code}）"
                )
            } else {
                AgentResult.Ok(root?.get("data") as? JsonObject ?: JsonObject(emptyMap()))
            }
        }
    } catch (e: Exception) {
        AgentResult.Err(humanize(e), e.toString())
    }

    /** 探活：不需要口令，用来先确认地址通不通。 */
    suspend fun ping(agent: PcAgent): AgentResult<String> = withContext(Dispatchers.IO) {
        // ping 不校验口令，这里随便给个非空值即可
        val probe = agent.copy(token = agent.token.ifBlank { "x" })
        when (val r = call(probe, "/ping")) {
            is AgentResult.Ok ->
                AgentResult.Ok((r.value["host"] as? JsonPrimitive)?.contentOrNull ?: "未知主机")

            is AgentResult.Err -> r
        }
    }

    suspend fun info(agent: PcAgent): AgentResult<AgentInfo> = withContext(Dispatchers.IO) {
        when (val r = call(agent, "/info")) {
            is AgentResult.Ok -> {
                fun str(k: String) = (r.value[k] as? JsonPrimitive)?.contentOrNull ?: ""
                fun arr(k: String) = (r.value[k] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                AgentResult.Ok(
                    AgentInfo(
                        hostname = str("hostname"),
                        os = str("os"),
                        user = str("user"),
                        cwd = str("cwd"),
                        ips = arr("ips"),
                        drives = arr("drives"),
                    )
                )
            }

            is AgentResult.Err -> r
        }
    }

    suspend fun exec(
        agent: PcAgent,
        command: String,
        cwd: String = "",
    ): AgentResult<AgentExecResult> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("command", command)
            if (cwd.isNotBlank()) put("cwd", cwd)
        }
        when (val r = call(agent, "/exec", body)) {
            is AgentResult.Ok -> {
                fun int(k: String) = (r.value[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
                fun str(k: String) = (r.value[k] as? JsonPrimitive)?.contentOrNull ?: ""
                AgentResult.Ok(
                    AgentExecResult(
                        exitCode = int("exitCode"),
                        stdout = str("stdout"),
                        stderr = str("stderr"),
                        durationMs = (r.value["durationMs"] as? JsonPrimitive)
                            ?.contentOrNull?.toLongOrNull() ?: 0,
                    )
                )
            }

            is AgentResult.Err -> r
        }
    }

    suspend fun listDir(agent: PcAgent, path: String): AgentResult<String> =
        withContext(Dispatchers.IO) {
            val q = if (path.isBlank()) "" else
                "?path=" + java.net.URLEncoder.encode(path, "UTF-8")
            when (val r = call(agent, "/ls$q")) {
                is AgentResult.Ok -> {
                    val arr = r.value["items"] as? kotlinx.serialization.json.JsonArray
                        ?: return@withContext AgentResult.Err("返回格式异常")
                    val lines = arr.mapNotNull { item ->
                        val o = item as? JsonObject ?: return@mapNotNull null
                        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
                        val dir = (o["dir"] as? JsonPrimitive)?.contentOrNull
                            ?.toBooleanStrictOrNull() ?: false
                        val size = (o["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
                        if (dir) "  [目录] ${str("name")}" else "  [文件] ${str("name")}  $size 字节"
                    }
                    AgentResult.Ok(
                        "目录：${path.ifBlank { "(当前目录)" }}  共 ${arr.size} 项\n" +
                            lines.joinToString("\n")
                    )
                }

                is AgentResult.Err -> r
            }
        }

    suspend fun readFile(agent: PcAgent, path: String): AgentResult<String> =
        withContext(Dispatchers.IO) {
            val q = "?path=" + java.net.URLEncoder.encode(path, "UTF-8")
            when (val r = call(agent, "/read$q")) {
                is AgentResult.Ok ->
                    AgentResult.Ok((r.value["content"] as? JsonPrimitive)?.contentOrNull ?: "")

                is AgentResult.Err -> r
            }
        }

    suspend fun writeFile(
        agent: PcAgent,
        path: String,
        content: String,
    ): AgentResult<Unit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("path", path)
            put("content", content)
        }
        when (val r = call(agent, "/write", body)) {
            is AgentResult.Ok -> AgentResult.Ok(Unit)
            is AgentResult.Err -> r
        }
    }
}
