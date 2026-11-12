package com.gcmodai.app.data.ai.transformers

import android.util.Log
import androidx.compose.ui.util.fastForEach
import com.gcmodai.ai.provider.Modality
import com.gcmodai.ai.ui.UIMessage
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.app.data.media.MediaProbeClient
import okio.ByteString.Companion.encodeUtf8
import java.io.File

/**
 * 让「不支持视频/音频」的模型也能看懂用户发的视频和音频。
 *
 * 现实的坑：绝大多数 OpenAI 兼容网关根本不认 `video_url` / `input_audio`，
 * 直接把视频二进制塞过去就是 400，甚至被当成超长文本把上下文顶爆。
 * 但用户就是想问「这个视频里讲了啥」。
 *
 * 处理策略（三档降级）：
 *  1. 模型支持 VIDEO → 原样发视频（payload 由 ChatCompletionsAPI 负责）。
 *  2. 模型不吃视频但吃图片 → 服务器 ffmpeg 等距抽 4 帧，换成图片 + 一句时长/分辨率说明。
 *  3. 什么都不支持 → 换成「附件摘要」文字（时长、编码、分辨率、声道），至少不丢信息。
 *
 * 音频同理：支持 AUDIO 原样发；否则给文字摘要。
 *
 * 全部基于服务器 ffmpeg（App 不背 30MB+ 的体积），失败就退回纯文字摘要，绝不阻断发送。
 */
class MediaCapabilityTransformer(
    private val mediaClient: MediaProbeClient,
    private val frameCount: Int = 4,
) : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val supportVideo = Modality.VIDEO in ctx.model.inputModalities
        val supportAudio = Modality.AUDIO in ctx.model.inputModalities
        val supportImage = Modality.IMAGE in ctx.model.inputModalities

        // 全都支持就不用动，省一次上传
        if (supportVideo && supportAudio) return messages

        return messages.map { message ->
            if (message.parts.none { it is UIMessagePart.Video || it is UIMessagePart.Audio }) {
                return@map message
            }
            val newParts = mutableListOf<UIMessagePart>()
            message.parts.fastForEach { part ->
                when (part) {
                    is UIMessagePart.Video -> {
                        if (supportVideo) {
                            newParts.add(part)
                        } else {
                            newParts.addAll(videoFallback(part, supportImage))
                        }
                    }

                    is UIMessagePart.Audio -> {
                        if (supportAudio) {
                            newParts.add(part)
                        } else {
                            newParts.addAll(audioFallback(part))
                        }
                    }

                    else -> newParts.add(part)
                }
            }
            message.copy(parts = newParts)
        }
    }

    private suspend fun videoFallback(
        part: UIMessagePart.Video,
        supportImage: Boolean,
    ): List<UIMessagePart> {
        val file = localFileOf(part.url) ?: return listOf(
            UIMessagePart.Text("[视频] ${part.url}（本地文件已不可用，无法解析）")
        )
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return listOf(UIMessagePart.Text("[视频] ${file.name}（读取失败）"))

        val info = mediaClient.probe(bytes, file.name).getOrNull()

        if (supportImage) {
            val frames = mediaClient.frames(bytes, file.name, frameCount).getOrNull()
            if (frames != null && frames.frames.isNotEmpty()) {
                val text = buildString {
                    append("（视频已自动抽帧转成图片）")
                    if (info != null) append(info.toPromptText(file.name))
                }
                return buildList {
                    add(UIMessagePart.Text(text))
                    frames.frames.forEach { frame ->
                        add(UIMessagePart.Image("data:image/jpeg;base64," + frame.jpegBase64))
                    }
                }
            }
        }

        // 连抽帧都不行：至少把元信息说清楚，别让模型瞎猜
        return listOf(UIMessagePart.Text(
            if (info != null) {
                "（当前模型不支持视频输入）" + info.toPromptText(file.name)
            } else {
                "[视频] ${file.name}（${bytes.size} 字节，当前模型不支持视频输入）"
            }
        ))
    }

    private suspend fun audioFallback(part: UIMessagePart.Audio): List<UIMessagePart> {
        val file = localFileOf(part.url) ?: return listOf(
            UIMessagePart.Text("[音频] ${part.url}（本地文件已不可用，无法解析）")
        )
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return listOf(UIMessagePart.Text("[音频] ${file.name}（读取失败）"))

        val info = mediaClient.probe(bytes, file.name).getOrNull()
        return listOf(UIMessagePart.Text(
            if (info != null) {
                "（当前模型不支持音频输入）" + info.toPromptText(file.name)
            } else {
                "[音频] ${file.name}（${bytes.size} 字节，当前模型不支持音频输入）"
            }
        ))
    }

    /** `file:///…` 或绝对路径都能拿到 File；http 链接返回 null（不下载远端资源）。 */
    private fun localFileOf(url: String): File? {
        if (url.startsWith("http", ignoreCase = true) || url.startsWith("data:")) return null
        val path = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            else -> url
        }
        return File(path).takeIf { it.exists() }
    }
}
