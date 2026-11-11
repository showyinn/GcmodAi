package com.gcmodai.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 榜单条目。富豪榜与词元榜共用一套字段，缺的字段保持默认值。 */
@Serializable
data class LeaderboardItem(
    val rank: Int = 0,
    val name: String = "",
    val display: String = "",
    /** 富豪榜：累计充值金额（元） */
    val amount: Double = 0.0,
    /** 站内余额（quota） */
    val quota: Long = 0,
    /** 词元榜：累计消耗 tokens */
    val tokens: Long = 0,
    /** 调用次数 */
    val requests: Long = 0,
)

@Serializable
data class LeaderboardData(
    val generatedAt: Long = 0,
    val topN: Int = 10,
    val basis: Map<String, String> = emptyMap(),
    val wealth: List<LeaderboardItem> = emptyList(),
    val tokens: List<LeaderboardItem> = emptyList(),
)

@Serializable
data class LeaderboardResponse(
    val success: Boolean = false,
    val data: LeaderboardData? = null,
    val message: String? = null,
)

/**
 * 排行榜数据源。
 *
 * 服务端：https://gcmod.online/api/leaderboard（gcmod-leaderboard-svc，只读汇总表，毫秒级）
 * 口径：富豪榜按累计充值金额，词元榜按 GcmodAi 供应商累计 tokens；
 *       只有在本站（供应商 GcmodAi）用过量的账号才会出现在榜单里，各榜前 10 名。
 */
class LeaderboardRepository(private val client: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetch(): LeaderboardResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/api/leaderboard")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return@withContext LeaderboardResult.Err("服务返回 ${response.code}")
                }
                val parsed = json.decodeFromString(LeaderboardResponse.serializer(), body)
                val data = parsed.data
                if (!parsed.success || data == null) {
                    LeaderboardResult.Err(parsed.message ?: "榜单数据为空")
                } else {
                    LeaderboardResult.Ok(data)
                }
            }
        }.getOrElse { LeaderboardResult.Err(it.message ?: it.toString()) }
    }

    companion object {
        const val BASE_URL = "https://gcmod.online"
    }
}

sealed interface LeaderboardResult {
    data class Ok(val data: LeaderboardData) : LeaderboardResult
    data class Err(val message: String) : LeaderboardResult
}
