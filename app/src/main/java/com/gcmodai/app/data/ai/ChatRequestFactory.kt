package com.gcmodai.app.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import com.gcmodai.ai.agent.AgentHooks
import com.gcmodai.ai.agent.AgentRequest
import com.gcmodai.ai.agent.ContinuationPolicy
import com.gcmodai.ai.agent.ContinuationTrigger
import com.gcmodai.ai.agent.RetryPolicy
import com.gcmodai.ai.agent.StepRequest
import com.gcmodai.ai.agent.withRetry
import com.gcmodai.ai.core.ReasoningLevel
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ProviderManager
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.ai.provider.TextGenerationParams
import com.gcmodai.ai.ui.UIMessage
import com.gcmodai.ai.ui.limitContext
import com.gcmodai.app.R
import com.gcmodai.app.data.ai.prompts.buildMemoryPrompt
import com.gcmodai.app.data.ai.tools.ChatToolFactory
import com.gcmodai.app.data.ai.transformers.Base64ImageToLocalFileTransformer
import com.gcmodai.app.data.ai.transformers.DocumentAsPromptTransformer
import com.gcmodai.app.data.ai.transformers.InputMessageTransformer
import com.gcmodai.app.data.ai.transformers.OcrTransformer
import com.gcmodai.app.data.ai.transformers.OutputMessageTransformer
import com.gcmodai.app.data.ai.transformers.PlaceholderTransformer
import com.gcmodai.app.data.ai.transformers.PromptInjectionTransformer
import com.gcmodai.app.data.ai.transformers.RegexOutputTransformer
import com.gcmodai.app.data.ai.transformers.TemplateTransformer
import com.gcmodai.app.data.ai.transformers.ThinkTagTransformer
import com.gcmodai.app.data.ai.transformers.TimeReminderTransformer
import com.gcmodai.app.data.ai.transformers.WorkspaceReminderTransformer
import com.gcmodai.app.data.ai.transformers.onGenerationFinish
import com.gcmodai.app.data.ai.transformers.transformThinkTags
import com.gcmodai.app.data.ai.transformers.transforms
import com.gcmodai.app.data.ai.transformers.visualTransforms
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.findProvider
import com.gcmodai.app.data.model.Assistant
import com.gcmodai.app.data.model.Conversation
import com.gcmodai.app.data.model.MemoryFile
import com.gcmodai.app.data.model.getAssistantOf
import com.gcmodai.app.data.model.getChatModelOf
import com.gcmodai.app.data.ai.transformers.MediaCapabilityTransformer
import com.gcmodai.app.data.media.MediaProbeClient
import com.gcmodai.app.data.repository.MemoryRepository
import com.gcmodai.app.data.repository.WorkspaceRepository
import com.gcmodai.app.data.repository.memoryId
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同，
 * 压缩请求的前缀才能和上一轮对话一致，命中提示词缓存。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
)

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
    mediaProbeClient: MediaProbeClient,
) {
    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        // 放在 OCR 之后、模板之前：视频/音频要在这里决定「原样发」还是「抽帧/摘要降级」，
        // 太晚就会被 DocumentAsPrompt 之类先吃掉，太早又拿不到最终模型能力。
        MediaCapabilityTransformer(mediaProbeClient),
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
    )

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(settings: Settings, conversation: Conversation): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
            ),
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
    ): Pair<AgentRequest, AgentHooks> {
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
            // 任务模式 / 断流保护：模型没干完就自动续写，开关在设置-模型页
            continuation = turn.settings.continuationPolicy(),
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                return buildStep(turn, messages, processingStatus)
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }
        }
        return request to hooks
    }

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、工具定义、参数和 sessionId 都相同），只在末尾追加压缩指令，
     * 前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留：它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     */
    suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        val (requestMessages, params) = buildStep(turn, messages)
        // 压缩指令不经过输入转换器，模板转换器会把其中的花括号当成模板语法
        val result = withRetry(
            policy = turn.settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            ).generateText(
                providerSetting = turn.providerSetting,
                messages = requestMessages + UIMessage.user(compressPrompt),
                // 助手的输出上限是给聊天回复定的，结构化摘要写不完会被截断；max_tokens 不属于请求前缀，改了不影响缓存
                params = params.copy(maxTokens = null),
            )
        }
        val summary = result.message.compressionSummaryOrNull()
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        return summary.ifBlank { error("Failed to generate compressed summary") }
    }

    /** 一步请求实际发出的消息和参数；正常对话和压缩共用，二者的请求前缀必须一致才能命中提示词缓存 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): StepRequest {
        val assistant = turn.assistant
        val requestMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                        turn.conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(turn.memories))
                }
                // 工具prompt
                turn.tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(turn.model, messages))
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        )
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        return StepRequest(requestMessages, params)
    }
}

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容也要在这里剥掉，否则会存进摘要。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

/**
 * 本轮的请求重试策略。
 *
 * 任务模式开着的时候用 [RetryPolicy.PERSISTENT]（10 次）：
 * 长任务最怕的不是多等十几秒，而是跑了一半撞上一次网关维护页就整轮报废。
 * 关掉任务模式时回到普通的 3 次，避免省钱党觉得它在死磕。
 *
 * 注意这两个开关是**协作**关系：自动重试管「同一步请求再试一次」，
 * 任务模式管「这一步已经生成了内容但被截断」。关掉自动重试不等于关掉续写，
 * 但会让「拿到网页而不是 JSON」这类瞬时故障直接冒到用户面前。
 */
internal fun Settings.retryPolicy(): RetryPolicy = when {
    !networkSetting.enableAutoRetry -> RetryPolicy.NONE
    taskModeEnabled -> RetryPolicy.PERSISTENT
    else -> RetryPolicy.NETWORK
}

/**
 * 把设置里的任务模式开关翻译成 agent 循环能用的策略。
 *
 * 这里刻意只做映射、不带默认值判断：开关和次数都在 [Settings] 里，
 * 循环本身不读设置，测试可以只构造策略。
 */
internal fun Settings.continuationPolicy(): ContinuationPolicy {
    if (!taskModeEnabled) return ContinuationPolicy.NONE
    val marker = taskModeDoneMarker.trim()
    val markers = if (marker.isEmpty()) {
        // 用户把标记删空：退回内置那几种，别让「任务没干完」的判定失效
        ContinuationPolicy.DEFAULT_DONE_MARKERS
    } else {
        (listOf(marker) + ContinuationPolicy.DEFAULT_DONE_MARKERS).distinct()
    }
    return ContinuationPolicy(
        maxContinuations = taskModeMaxContinuations,
        // 严格模式才盯「有没有完成标记」；否则只管客观上没说完的三种情况。
        // 不加这个区分的话，普通闲聊也会被一直续写到模型写出标记，白烧额度
        triggers = if (taskModeStrict) {
            ContinuationPolicy.DEFAULT_TRIGGERS
        } else {
            ContinuationPolicy.DEFAULT_TRIGGERS - ContinuationTrigger.UNFINISHED
        },
        doneMarkers = markers,
        nudge = taskModeNudge.trim().ifEmpty { ContinuationPolicy.DEFAULT_NUDGE },
        // 续写到上限还是空的：给一句兜底，别留下一个点不开的空气泡
        emptyFallback = "（模型这次没有返回内容，点重试再来一次。）",
    )
}
