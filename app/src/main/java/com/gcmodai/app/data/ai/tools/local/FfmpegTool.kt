package com.gcmodai.app.data.ai.tools.local

import android.util.Log
import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.files.FileFolders
import com.gcmodai.app.data.media.MediaProbeClient
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "FfmpegTool"

/** 工具名：模型看到的就是 `ffmpeg` */
const val FFMPEG_TOOL_NAME = "ffmpeg"

/** 单次最多塞给模型的帧数：太多会顶爆上下文，4 帧足够判断画面内容 */
private const val MAX_FRAMES = 6

/** 送去做解析的体积上限（手机内存有限，超大文件先提示用户） */
private const val MAX_FILE_BYTES = 256L * 1024 * 1024

/**
 * ffmpeg 工具：让 AI 直接"看懂"用户设备上的视频和音频。
 *
 * 为什么做成工具而不是一个页面：
 *  · 页面只能人点，AI 用不上；做成工具后，用户说"这个视频讲了什么"，
 *    模型会自己调 ffmpeg 拿时长/编码/分辨率，再抽 4 帧当图片看，最后回答；
 *  · 解析仍然在服务器跑（ffmpeg 7.1 + ffprobe），手机端零体积、零耗电。
 *
 * 参数：
 *   path   设备上的绝对路径（也接受 file:// 前缀），比如 /sdcard/Movies/a.mp4
 *   action probe（只看参数）| frames（只抽帧）| audio（只抽音轨）| all（全做，默认）
 *   frames 抽几帧，1~6，默认 4
 */
internal fun buildFfmpegTool(
    mediaClient: MediaProbeClient,
    filesRoot: File,
): Tool = Tool(
    name = FFMPEG_TOOL_NAME,
    description = """
        Analyze a video or audio file on this device with ffmpeg (runs on our server, zero local ffmpeg binary).
        Returns real metadata: duration, container, video codec/resolution/fps, audio codec/channels/sample rate, bitrate.
        With action=frames it extracts evenly spaced JPEG frames and returns them as images, so you can actually see
        what happens in the video. With action=audio it converts the audio track to a mono mp3 and returns its path.
        Use this whenever the user asks about a video/audio file's content, length, resolution, or format.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path to a media file on the device, e.g. /sdcard/Movies/demo.mp4 " +
                            "or file:///sdcard/Download/a.mp3"
                    )
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "probe = metadata only; frames = extract frames; audio = extract mp3; all = default")
                    put("enum", buildJsonArray {
                        add("probe")
                        add("frames")
                        add("audio")
                        add("thumbnail")
                        add("trim")
                        add("all")
                    })
                })
                put("frames", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many frames to extract (1-6, default 4). Only used when action is frames/all")
                })
                put("at", buildJsonObject {
                    put("type", "number")
                    put("description", "Timestamp in seconds for action=thumbnail (default: 10% of duration)")
                })
                put("start", buildJsonObject {
                    put("type", "number")
                    put("description", "Start time in seconds for action=trim (default 0)")
                })
                put("duration", buildJsonObject {
                    put("type", "number")
                    put("description", "Clip length in seconds for action=trim (default 10, max 120)")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { params ->
        val obj = params.jsonObject
        val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "all"
        val frameCount = (obj["frames"]?.jsonPrimitive?.intOrNull ?: 4).coerceIn(1, MAX_FRAMES)
        val atSeconds = obj["at"]?.jsonPrimitive?.doubleOrNull
        val trimStart = (obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0).coerceAtLeast(0.0)
        val trimDuration = (obj["duration"]?.jsonPrimitive?.doubleOrNull ?: 10.0)
            .coerceIn(0.5, 120.0)

        if (rawPath.isBlank()) {
            return@Tool listOf(UIMessagePart.Text("""{"error":"path is required"}"""))
        }

        val file = resolveMediaFile(rawPath, filesRoot)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    """{"error":"file not found or not readable","path":"${rawPath.replace("\"", "")}"}"""
                )
            )
        if (!file.canRead()) {
            return@Tool listOf(UIMessagePart.Text("""{"error":"permission denied","path":"${file.absolutePath}"}"""))
        }
        if (file.length() > MAX_FILE_BYTES) {
            return@Tool listOf(
                UIMessagePart.Text(
                    """{"error":"file too large","sizeBytes":${file.length()},"limitBytes":$MAX_FILE_BYTES}"""
                )
            )
        }

        val bytes = runCatching { file.readBytes() }.getOrElse { e ->
            Log.w(TAG, "read failed: ${file.absolutePath}", e)
            return@Tool listOf(UIMessagePart.Text("""{"error":"read failed: ${e.javaClass.simpleName}"}"""))
        }

        val parts = mutableListOf<UIMessagePart>()
        val wantProbe = action == "probe" || action == "all" || action == "frames"
        val wantFrames = action == "frames" || action == "all"
        val wantAudio = action == "audio" || action == "all"
        val wantThumb = action == "thumbnail" || action == "all"
        val wantTrim = action == "trim"

        val info = mediaClient.probe(bytes, file.name).getOrNull()

        val summary = buildJsonObject {
            put("file", file.name)
            put("sizeBytes", file.length())
            put("sizeText", "%.2f MB".format(file.length() / 1024f / 1024f))
            if (info != null) {
                put("kind", info.kind)
                put("container", info.container)
                put("durationSeconds", info.durationSeconds)
                put("durationText", info.durationText)
                put("bitRateKbps", info.bitRateKbps)
                put("summary", info.summary)
                info.video?.let { v ->
                    put("video", buildJsonObject {
                        put("codec", v.codec)
                        put("profile", v.profile)
                        put("width", v.width)
                        put("height", v.height)
                        put("fps", v.fps)
                        put("pixFmt", v.pixFmt)
                        put("frames", v.frameCount)
                    })
                }
                info.audio?.let { a ->
                    put("audio", buildJsonObject {
                        put("codec", a.codec)
                        put("channels", a.channels)
                        put("sampleRate", a.sampleRate)
                        put("bitRateKbps", a.bitRateKbps)
                    })
                }
            } else if (wantProbe) {
                put("probeError", "server probe failed (network or unsupported container)")
            }
        }

        parts.add(UIMessagePart.Text(summary.toString()))

        // ---- 抽帧：直接生成图片部件，模型能"看见"画面 ----
        if (wantFrames) {
            val frames = mediaClient.frames(bytes, file.name, frameCount).getOrNull()
            if (frames == null || frames.frames.isEmpty()) {
                parts.add(UIMessagePart.Text("""{"framesError":"frame extraction failed"}"""))
            } else {
                val dir = File(filesRoot, FileFolders.TOOL_OUTPUTS)
                    .resolve("ffmpeg_frames")
                    .apply { mkdirs() }
                val base = file.nameWithoutExtension.take(24)
                frames.frames.take(MAX_FRAMES).forEachIndexed { index, frame ->
                    val jpeg = runCatching {
                        android.util.Base64.decode(frame.jpegBase64, android.util.Base64.DEFAULT)
                    }.getOrNull() ?: return@forEachIndexed
                    val out = File(dir, "${base}_${(frame.atSeconds * 10).roundToInt()}_$index.jpg")
                    runCatching { out.writeBytes(jpeg) }.onSuccess {
                        parts.add(UIMessagePart.Image("file://${out.absolutePath}"))
                    }
                }
                parts.add(
                    UIMessagePart.Text(
                        """{"frames":${frames.count},"frameDir":"${dir.absolutePath}","note":"frames returned as images above"}"""
                    )
                )
            }
        }

        // ---- 抽音轨：存成 mp3，返回路径给后续处理 ----
        if (wantAudio) {
            val track = mediaClient.audio(bytes, file.name).getOrNull()
            if (track == null) {
                parts.add(UIMessagePart.Text("""{"audioError":"audio extraction failed"}"""))
            } else {
                val dir = File(filesRoot, FileFolders.TOOL_OUTPUTS)
                    .resolve("ffmpeg_audio")
                    .apply { mkdirs() }
                val out = File(dir, "${file.nameWithoutExtension.take(24)}.mp3")
                val ok = runCatching {
                    out.writeBytes(android.util.Base64.decode(track.audioBase64, android.util.Base64.DEFAULT))
                }.isSuccess
                val audioJson = if (ok) {
                    buildJsonObject {
                        put("audio", buildJsonObject {
                            put("format", track.format)
                            put("bitrate", track.bitrate)
                            put("sampleRate", track.sampleRate)
                            put("bytes", track.bytes)
                            put("path", out.absolutePath)
                        })
                    }.toString()
                } else {
                    """{"audioError":"write failed"}"""
                }
                parts.add(UIMessagePart.Text(audioJson))
            }
        }

        // ---- 封面帧：只要一张，省 token ----
        if (wantThumb) {
            val frame = mediaClient.thumbnail(bytes, file.name, atSeconds).getOrNull()
            if (frame == null) {
                parts.add(UIMessagePart.Text("""{"thumbnailError":"failed"}"""))
            } else {
                val dir = File(filesRoot, FileFolders.TOOL_OUTPUTS).resolve("ffmpeg_thumb").apply { mkdirs() }
                val out = File(dir, "${file.nameWithoutExtension.take(24)}_cover.jpg")
                val ok = runCatching {
                    out.writeBytes(android.util.Base64.decode(frame.jpegBase64, android.util.Base64.DEFAULT))
                }.isSuccess
                if (ok) parts.add(UIMessagePart.Image("file://${out.absolutePath}"))
                parts.add(UIMessagePart.Text(
                    buildJsonObject {
                        put("thumbnailAtSeconds", frame.atSeconds)
                        put("thumbnailBytes", frame.bytes)
                        put("thumbnailPath", if (ok) out.absolutePath else "")
                    }.toString()
                ))
            }
        }

        // ---- 截取片段：拿到 mp4 落盘，返回路径 ----
        if (wantTrim) {
            val clip = mediaClient.trim(bytes, file.name, trimStart, trimDuration).getOrNull()
            if (clip == null) {
                parts.add(UIMessagePart.Text("""{"trimError":"failed"}"""))
            } else {
                val dir = File(filesRoot, FileFolders.TOOL_OUTPUTS).resolve("ffmpeg_clips").apply { mkdirs() }
                val out = File(dir, "${file.nameWithoutExtension.take(20)}_${trimStart.toInt()}s.mp4")
                val ok = runCatching {
                    out.writeBytes(android.util.Base64.decode(clip.mp4Base64, android.util.Base64.DEFAULT))
                }.isSuccess
                parts.add(UIMessagePart.Text(
                    buildJsonObject {
                        put("trim", buildJsonObject {
                            put("startSeconds", clip.startSeconds)
                            put("durationSeconds", clip.durationSeconds)
                            put("mode", clip.mode)
                            put("bytes", clip.bytes)
                            put("path", if (ok) out.absolutePath else "")
                        })
                    }.toString()
                ))
            }
        }

        parts
    },
)

/**
 * 把模型给的路径解析成真实文件。
 *
 * 支持三种写法：
 *   /sdcard/xxx.mp4          —— 绝对路径（需要存储权限，Manifest 里已申请 MANAGE_EXTERNAL_STORAGE）
 *   file:///sdcard/xxx.mp4   —— URI 形式
 *   gcmod://upload/<name>    —— 指向 App 自己的聊天存储目录（用户刚传的视频）
 */
private fun resolveMediaFile(raw: String, filesRoot: File): File? {
    val path = when {
        raw.startsWith("file://") -> raw.removePrefix("file://")
        raw.startsWith("gcmod://upload/") -> {
            val name = raw.removePrefix("gcmod://upload/")
            return File(File(filesRoot, FileFolders.UPLOAD), name).takeIf { it.exists() }
        }

        else -> raw
    }
    val direct = File(path)
    if (direct.exists()) return direct
    // 再试一下 App 私有目录（模型可能只给了文件名）
    val inUpload = File(File(filesRoot, FileFolders.UPLOAD), path.substringAfterLast('/'))
    return inUpload.takeIf { it.exists() }
}
