package com.gcmodai.app.data.user

import com.gcmodai.ai.provider.ModelAbility
import com.gcmodai.ai.provider.Modality

/**
 * 按模型 ID 推断能力，官方通道（gcmod.online）同步模型时使用。
 *
 * 为什么需要：官方通道的 `/api/user/models` 只返回模型名字符串，不带任何能力元数据。
 * 如果同步时把 `abilities` 留空，聊天里就不会给模型挂工具（`ChatService` 只在
 * `ModelAbility.TOOL in model.abilities` 时才走工具调用），用户看到的就是「模型不支持工具」。
 *
 * 推断策略：**只认保守的白名单**。命中不了就返回空能力，宁可让用户手动去
 * 设置 → 模型 → 模型能力 里勾，也不要给一个做不到的模型误标 TOOL——
 * 误标会让请求带上 tools 字段，服务端直接报错，比没工具更难排查。
 *
 * 这个文件刻意保持为纯函数、零 Android 依赖，方便 JVM 单元测试。
 */
object ModelCapability {

    /** 支持视觉输入的模型关键词 */
    private val VISION_KEYWORDS = listOf(
        "gpt-4o", "gpt-4-turbo", "gpt-4-vision", "gpt-4.1", "gpt-5",
        "claude-3", "claude-4", "claude-sonnet", "claude-opus",
        "gemini", "gemma-3",
        "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen-omni",
        "glm-4v", "glm-4.1v", "yi-vision", "internvl", "minicpm-v",
        "llava", "pixtral", "step-1v", "step-1o",
    )

    /** 支持工具调用（function calling）的模型关键词 */
    private val TOOL_KEYWORDS = listOf(
        // DeepSeek：全系都支持工具调用（含 reasoner 的后续版本）
        "deepseek-chat", "deepseek-v3", "deepseek-v3.1", "deepseek-v3.2",
        "deepseek-r1", "deepseek-reasoner", "deepseek-coder", "deepseek",
        // OpenAI
        "gpt-3.5", "gpt-4", "gpt-5", "o1", "o3", "o4-mini",
        // Anthropic
        "claude",
        // Google
        "gemini",
        // 阿里通义
        "qwen", "qwq",
        // 智谱
        "glm-4", "glm-5", "glm-z",
        // 月之暗面 / 阶跃 / 百川 / MiniMax
        "moonshot", "kimi", "step-", "baichuan", "minimax", "abab",
        // xAI / Mistral / Meta
        "grok", "mistral", "mixtral", "llama-3", "llama-4", "llama3",
        // 其他常见国产
        "hunyuan", "ernie", "doubao", "spark", "yi-", "internlm",
        "command-r", "phi-4",
    )

    /** 支持深度推理（reasoning）的模型关键词 */
    private val REASONING_KEYWORDS = listOf(
        // DeepSeek R 系列
        "deepseek-r1", "deepseek-reasoner", "deepseek-v3.1", "deepseek-v3.2",
        // OpenAI o 系列
        "o1", "o3", "o4-mini", "gpt-5",
        // Anthropic 带思考的版本
        "claude-3-7", "claude-3.7", "claude-4", "claude-sonnet-4", "claude-opus-4",
        // 阿里
        "qwq", "qwen3", "qvq",
        // 智谱
        "glm-z1", "glm-4.5", "glm-z",
        // xAI / Google
        "grok-3", "grok-4", "gemini-2.5", "gemini-3",
        // 阶跃
        "step-r", "step-3",
    )

    /**
     * 推断模型能力。
     *
     * 只对白名单命中的模型返回能力，未知模型返回空列表。
     */
    fun inferAbilities(modelId: String): List<ModelAbility> {
        val id = modelId.lowercase()
        return buildList {
            if (TOOL_KEYWORDS.any { it in id }) add(ModelAbility.TOOL)
            if (REASONING_KEYWORDS.any { it in id }) add(ModelAbility.REASONING)
        }
    }

    /**
     * 支持视频输入的模型关键词。
     *
     * 只认明确吃视频的型号：带 `video` / 明确的多模态视频版本 / Gemini 全系
     * （Gemini 从 1.5 起就吃原生视频）。VL 系只给 IMAGE，因为 Qwen-VL 的老版本
     * 传 video_url 会直接 400，误标比不标更难排查。
     */
    private val VIDEO_KEYWORDS = listOf(
        "video", "video-llama", "videollama",
        "gemini-1.5", "gemini-2", "gemini-3", "gemini-exp",
        "qwen2.5-vl", "qwen3-vl", "qwen-vl-max", "qwen-vl-plus",
        "glm-4.5v", "glm-4v-plus", "step-1o", "step-1v",
        "internvl3", "minicpm-v-4", "kimi-vl", "molmo",
        "llava-video", "llava-ov", "mplug-owl3", "cogvlm2-video",
    )

    /** 支持音频输入的模型关键词（OpenAI `input_audio` 那套）。 */
    private val AUDIO_KEYWORDS = listOf(
        "audio", "omni", "realtime", "voice",
        "whisper", "paraformer", "sensevoice", "asr",
        "qwen2-audio", "qwen-audio", "qwen2.5-omni", "qwen3-omni",
        "glm-4-voice", "glm-realtime", "step-audio", "mimo-audio",
        "gpt-4o-audio", "gpt-4o-mini-audio",
    )

    /**
     * 推断输入模态。
     *
     * 顺序固定为 文字 → 图片 → 视频 → 音频，和 `ModelRegistry.sortModalities` 一致，
     * 免得 UI 上的能力角标顺序跳来跳去。
     */
    fun inferInputModalities(modelId: String): List<Modality> {
        val id = modelId.lowercase()
        return buildList {
            add(Modality.TEXT)
            if (VISION_KEYWORDS.any { it in id }) add(Modality.IMAGE)
            if (VIDEO_KEYWORDS.any { it in id }) add(Modality.VIDEO)
            if (AUDIO_KEYWORDS.any { it in id }) add(Modality.AUDIO)
        }
    }

    /**
     * 推断上下文长度。
     *
     * 只区分两档：能确定是大窗口的给 128k，其余给 32k 这个保守值。
     * 官方通道的模型普遍支持 128k 以上，但宁可按小的算，避免超出真实上限导致请求被拒。
     */
    fun inferContextLength(modelId: String): Int {
        val id = modelId.lowercase()
        val large = listOf(
            "deepseek", "gpt-4", "gpt-5", "claude", "gemini", "qwen",
            "glm", "moonshot", "kimi", "grok", "mistral", "llama-3", "llama-4",
            "hunyuan", "ernie", "doubao", "step-", "yi-", "minimax", "abab",
        )
        return if (large.any { it in id }) 128_000 else 32_000
    }
}
