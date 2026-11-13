package com.gcmodai.app.data.media

import android.util.Log
import com.gcmodai.app.data.user.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 服务器端 ffmpeg 工具箱的客户端。
 *
 * 为什么不在 App 里塞 ffmpeg：一个 ABI 的静态 ffmpeg 就是 30–60MB，三个 ABI 直接翻倍，
 * 而我们刚为体积砍过 4%。服务器上已经装好 ffmpeg 7.1，解析/抽帧/转码都在那边做，
 * 手机端只负责上传字节 + 收 JSON，体积零增长。
 *
 * 服务端零留存：临时文件落 /tmp 后立刻删除，返回体里没有原始媒体。
 */
class MediaProbeClient(
    private val client: OkHttpClient,
    private val userRepository: UserRepository,
) {

    @Serializable
    data class MediaInfo(
        val kind: String = "",              // video / audio / unknown
        val container: String = "",
        val durationSeconds: Double = 0.0,
        val durationText: String = "",
        val sizeBytes: Long = 0,
        val bitRateKbps: Int = 0,
        val streamCount: Int = 0,
        val video: VideoInfo? = null,
        val audio: AudioInfo? = null,
        val summary: String = "",
    ) {
        /** 给模型看的一行摘要（中文，避免模型还要自己翻译字段名）。 */
        fun toPromptText(fileName: String): String = buildString {
            append("附件「").append(fileName).append("」：")
            if (summary.isNotBlank()) append(summary)
            if (durationText.isNotBlank()) append("，时长 ").append(durationText)
            video?.let { v ->
                append("，画面 ").append(v.width).append("x").append(v.height)
                if (v.fps > 0) append(" @ ").append(v.fps).append("fps")
            }
            audio?.let { a ->
                append("，声音 ").append(a.channels).append(" 声道 ").append(a.sampleRate).append("Hz")
            }
        }
    }

    @Serializable
    data class VideoInfo(
        val codec: String = "",
        val profile: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        @SerialName("pixFmt") val pixFmt: String = "",
        val frameCount: Int = 0,
        val bitRateKbps: Int = 0,
    )

    @Serializable
    data class AudioInfo(
        val codec: String = "",
        val channels: Int = 0,
        val sampleRate: Int = 0,
        val bitRateKbps: Int = 0,
    )

    @Serializable
    data class Frame(
        val atSeconds: Double = 0.0,
        val jpegBase64: String = "",
        val bytes: Long = 0,
    )

    @Serializable
    data class Frames(
        val count: Int = 0,
        val durationSeconds: Double = 0.0,
        val frames: List<Frame> = emptyList(),
    )

    @Serializable
    data class AudioTrack(
        val format: String = "",
        val bitrate: String = "",
        val channels: Int = 0,
        val sampleRate: Int = 0,
        val bytes: Long = 0,
        val audioBase64: String = "",
    )

    @Serializable
    data class TrimmedClip(
        val startSeconds: Double = 0.0,
        val durationSeconds: Double = 0.0,
        val totalSeconds: Double = 0.0,
        /** copy = 无损快速切（关键帧对齐）；reencode = 重编码（切点精确） */
        val mode: String = "",
        val bytes: Long = 0,
        val mp4Base64: String = "",
    )

    @Serializable
    private data class Envelope<T>(
        val success: Boolean = false,
        val data: T? = null,
        val message: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun token(): String? = userRepository.sessionFlow.value?.accessToken

    suspend fun probe(bytes: ByteArray, fileName: String): Result<MediaInfo> =
        call("/api/media/probe", bytes, fileName, emptyMap(), MediaInfo.serializer())

    /** 按时间轴等距抽帧，给「只吃图片」的模型当替代输入。 */
    suspend fun frames(
        bytes: ByteArray,
        fileName: String,
        count: Int = 4,
    ): Result<Frames> = call(
        "/api/media/frames", bytes, fileName, mapOf("X-Frames" to count.toString()),
        Frames.serializer(),
    )

    /** 抽出单声道 16k mp3 音轨，给只吃音频的模型。 */
    suspend fun audio(bytes: ByteArray, fileName: String): Result<AudioTrack> =
        call("/api/media/audio", bytes, fileName, emptyMap(), AudioTrack.serializer())

    /** 取某一时刻的单帧（封面/关键画面），at 为空时服务端取 10% 位置 */
    suspend fun thumbnail(
        bytes: ByteArray,
        fileName: String,
        atSeconds: Double? = null,
    ): Result<Frame> = call(
        "/api/media/thumbnail", bytes, fileName,
        buildMap { atSeconds?.let { put("X-At", it.toString()) } },
        Frame.serializer(),
    )

    /** 按时间区间截取一段视频，返回 mp4（base64） */
    suspend fun trim(
        bytes: ByteArray,
        fileName: String,
        startSeconds: Double = 0.0,
        durationSeconds: Double = 10.0,
    ): Result<TrimmedClip> = call(
        "/api/media/trim", bytes, fileName,
        mapOf(
            "X-Start" to startSeconds.toString(),
            "X-Duration" to durationSeconds.toString(),
        ),
        TrimmedClip.serializer(),
    )

    private suspend fun <T> call(
        path: String,
        bytes: ByteArray,
        fileName: String,
        extraHeaders: Map<String, String>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext Result.failure(IllegalStateException("请先登录账号"))
        runCatching {
            val builder = Request.Builder()
                .url("$BASE_URL$path")
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", "Bearer $t")
                .header("X-Filename", fileName)
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body.string()
                val env = runCatching {
                    json.decodeFromString(Envelope.serializer(serializer), body)
                }.getOrElse { error("解析失败 ${resp.code}：${body.take(160)}") }
                if (!resp.isSuccessful || !env.success) {
                    error(env.message ?: "请求失败 ${resp.code}")
                }
                env.data ?: error("返回为空")
            }
        }.onFailure { Log.w(TAG, "$path 失败：${it.message}") }
    }

    companion object {
        private const val TAG = "MediaProbeClient"

        /** 与加固服务同机，直接走 gcmod.bond */
        const val BASE_URL = "https://gcmod.bond"
    }
}
