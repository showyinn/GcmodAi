package com.gcmodai.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.gcmodai.ai.core.MessageRole
import com.gcmodai.ai.core.sum
import com.gcmodai.ai.provider.TextGenerationParams
import com.gcmodai.ai.ui.StreamChunk
import com.gcmodai.ai.ui.StreamChunkHandler
import com.gcmodai.ai.ui.ToolApprovalState
import com.gcmodai.ai.ui.UIMessage
import com.gcmodai.ai.ui.UIMessagePart
import com.gcmodai.ai.ui.finishReasoning
import com.gcmodai.ai.util.json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// 输出为空的工具会被当成没执行过，之后每一步都会再执行一次，所以换成占位文本
private const val EMPTY_TOOL_OUTPUT = "(no output)"

private class StreamChunkHandlingException(override val cause: Throwable) : RuntimeException(cause)

internal class AgentLoop(
    private val request: AgentRequest,
    private val hooks: AgentHooks,
    private val events: FlowCollector<AgentEvent>,
) {
    private val history: List<UIMessage>

    // 本轮的助手消息；新的一轮在第一次请求模型时才创建
    private var message: UIMessage?

    // 续写提示：这一步要不要紧跟在当前助手消息后面发给模型（只发一次，发完就清）
    private val extra = mutableListOf<UIMessage>()

    // 最近一次请求的结束原因，用来判断是不是被输出上限截断
    private var lastFinishReason: String? = null

    // 本步开始前已有的 part 数：判断「这一步什么都没产出」要看这一步新加的 part，
    // 否则前面步骤写过的内容会让空回复看起来像有内容
    private var stepPartsFrom = 0

    // 上一步结束时的可见正文：用来判断续写有没有带来新增内容，
    // 没进展就不要继续（这是「写完了还在续」的兜底闸门之一）
    private var previousStepText: String? = null

    init {
        val last = request.messages.lastOrNull()
        if (last?.role == MessageRole.ASSISTANT) {
            history = request.messages.dropLast(1)
            message = last
        } else {
            history = request.messages
            message = null
        }
    }

    suspend fun run() {
        var steps = 0
        var continuations = 0
        while (true) {
            val current = message
            if (current != null && current.getTools().any { !it.isExecuted }) {
                val pending = requestApprovals(current)
                if (pending.isNotEmpty()) return finish(AgentFinishReason.AwaitingApproval(pending))
                executeTools()
            } else if (current != null && steps > 0 && extra.isEmpty()) {
                // 模型这一步没再调工具，看起来要收工了：先看是真空了、被截断了，还是任务没做完。
                // extra 不为空说明刚决定续写，直接去请求模型，别在这里再判一次同样的条件
                val nudge = nextNudge(
                    current = current,
                    triggers = setOf(
                        ContinuationTrigger.EMPTY,
                        ContinuationTrigger.TRUNCATED,
                        ContinuationTrigger.UNFINISHED,
                    ),
                    used = continuations,
                )
                if (nudge == null) return finish(AgentFinishReason.Completed)
                continuations++
                continueWith(nudge, continuations)
            }
            if (steps >= request.maxSteps) {
                // 步数配额用完但活可能还没干完：续写可以突破这个上限，只是同样受次数限制。
                // 续写时给一份新的步数配额，否则下一轮会立刻又撞上这里，一次都请求不出去
                val nudge = nextNudge(
                    current = message,
                    triggers = setOf(ContinuationTrigger.STEP_LIMIT),
                    used = continuations,
                )
                if (nudge == null) return finish(AgentFinishReason.StepLimitReached)
                continuations++
                continueWith(nudge, continuations)
                steps = 0
            }
            generateStep()
            steps++
        }
    }

    // 续写：把提示排在这条助手消息后面发给模型，产出仍然写回同一条消息，界面上不会断成两截
    private suspend fun continueWith(nudge: String, round: Int) {
        extra += UIMessage.user(nudge)
        events.emit(AgentEvent.Continuing(round, request.continuation.maxContinuations))
    }

    /**
     * 命中续写条件时返回要发出去的提示，否则返回 null。
     *
     * 只看「调用方想知道」和「用户已启用」两个集合的交集：不加交集的话，
     * 用户只开了「被截断就续写」，也会被没开启的「任务没做完」判定命中。
     */
    private fun nextNudge(
        current: UIMessage?,
        triggers: Set<ContinuationTrigger>,
        used: Int,
    ): String? {
        val policy = request.continuation
        if (!policy.enabled || used >= policy.maxContinuations) return null
        val active = triggers.filter { it in policy.triggers }
        if (active.isEmpty()) return null
        if (active.none { matches(it, current, policy) }) return null
        return policy.nudge
    }

    private fun matches(
        trigger: ContinuationTrigger,
        current: UIMessage?,
        policy: ContinuationPolicy,
    ): Boolean = when (trigger) {
        // 这一步什么都没产出：断流、空包、被上游吃掉
        ContinuationTrigger.EMPTY -> !hasVisibleContent(current?.parts?.drop(stepPartsFrom))
        // 被输出上限截断，后面肯定还有内容
        ContinuationTrigger.TRUNCATED -> isTruncated(lastFinishReason)
        // 任务没做完。判据不能只看完成标记：模型自然收尾时不会输出 [DONE]，
        // 只看标记会导致「明明写完了还在继续」。交给 CompletionDetector 综合判断。
        ContinuationTrigger.UNFINISHED -> !CompletionDetector.isComplete(
            text = current?.toText().orEmpty(),
            markers = policy.doneMarkers,
            previousText = previousStepText,
        )
        ContinuationTrigger.STEP_LIMIT -> true
    }

    // 新的工具调用按定义判断是否需要审批，需要的标为 Pending；返回仍在等待审批的调用
    private suspend fun requestApprovals(current: UIMessage): Set<String> {
        val updated = current.copy(
            parts = current.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted &&
                    part.approvalState == ToolApprovalState.Auto &&
                    findTool(part.toolName)?.needsApproval?.invoke(part.inputAsJson()) == true
                ) {
                    part.copy(approvalState = ToolApprovalState.Pending)
                } else {
                    part
                }
            }
        )
        if (updated != current) update(updated)
        return updated.getTools().filter { it.isPending }.mapTo(LinkedHashSet()) { it.toolCallId }
    }

    private suspend fun generateStep() {
        val current = message
        // 续写提示排在这条助手消息后面：只影响这一步的请求，不写进消息内容，界面上不会多出一条用户气泡
        val pending = extra.toList()
        extra.clear()
        val prepared = when {
            current == null -> history
            else -> history + current
        } + pending
        val step = hooks.prepareStep(prepared)
        val params = step.params.copy(tools = request.tools)
        val base = current ?: UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = params.model.id,
        )
        val generated = if (request.stream) {
            stream(step.messages, params, base)
        } else {
            generate(step.messages, params, base)
        }
        stepPartsFrom = base.parts.size
        // 记下这一步之前的正文，供下一次完成判定比较「有没有新增进展」。
        // 必须在 update 之前取，此时 message 还是上一步的结果
        previousStepText = current?.toText()
        update(hooks.finishStep(generated, newPartsFrom = base.parts.size).copy(finishedAt = now()))
    }

    private suspend fun stream(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 多轮工具调用共用同一条助手消息，消息上已有的 usage 和耗时来自之前的步骤。
        // 本步从清空的统计开始（merge 只适合同一次请求内的快照），再与之前的步骤累加。
        val stepStart = base.copy(usage = null, generationTimeMs = null)
        return try {
            withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
                // 每次重试都从本步开始时的消息重新合并，不会把重试的响应接在已经展示的半截回复后面；
                // 各次尝试共用同一个消息 id，调用方直接覆盖即可。
                val handler = StreamChunkHandler(params.model)
                var attempt = stepStart
                var firstChunk: TimeMark? = null
                lastFinishReason = null
                request.provider.streamText(request.providerSetting, messages, params).collect { chunk ->
                    try {
                        // 断流保护要知道这一步到底是被限长截断，还是模型自己说完了
                        if (chunk is StreamChunk.Finish) lastFinishReason = chunk.finishReason
                        val mark = firstChunk ?: TimeSource.Monotonic.markNow().also { firstChunk = it }
                        attempt = handler.handle(listOf(attempt), chunk).single()
                        preview(attempt.withStepStats(base, mark.elapsedNow()), newPartsFrom = base.parts.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 合并失败或下游处理失败都不是网络故障，不能重放模型请求
                        throw StreamChunkHandlingException(e)
                    }
                }
                attempt.withStepStats(base, firstChunk?.elapsedNow() ?: Duration.ZERO)
            }
        } catch (e: StreamChunkHandlingException) {
            throw e.cause
        }
    }

    private suspend fun generate(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        base: UIMessage,
    ): UIMessage {
        // 非流式拿不到首 token 时间，只能统计最后一次尝试的整个请求耗时
        var requestMark = TimeSource.Monotonic.markNow()
        val result = withRetry(request.retry, onRetry = { events.emit(AgentEvent.Retrying(it)) }) {
            requestMark = TimeSource.Monotonic.markNow()
            request.provider.generateText(request.providerSetting, messages, params)
        }
        lastFinishReason = result.finishReason
        val requestTime = requestMark.elapsedNow()
        // 和流式一样只追加 part，不并进之前步骤的文本或推理里，否则 newPartsFrom 之后就不全是这一步的内容了。
        // 一次请求只有一份 usage；没有返回时保持为空，累加时沿用之前步骤的统计
        val generated = base.copy(
            parts = base.parts + result.message.parts,
            annotations = result.message.annotations.ifEmpty { base.annotations },
            modelId = params.model.id,
            usage = result.usage,
        ).finishReasoning().withStepStats(base, requestTime)
        preview(generated, newPartsFrom = base.parts.size)
        return generated
    }

    // 按调用顺序逐个执行，每执行完一个就更新一次，中途取消时已经执行的结果不会丢
    private suspend fun executeTools() {
        val tools = checkNotNull(message).getTools().filter { !it.isExecuted }
        for (tool in tools) {
            // 只给真正运行的工具计时；开始时先写一次，界面才能在执行中实时显示耗时
            val runs = tool.approvalState == ToolApprovalState.Auto || tool.approvalState == ToolApprovalState.Approved
            if (runs) updateTool(tool.toolCallId) { it.copy(startedAt = Clock.System.now(), finishedAt = null) }
            val output = execute(tool)
            updateTool(tool.toolCallId) {
                it.copy(output = output, finishedAt = if (runs) Clock.System.now() else it.finishedAt)
            }
        }
    }

    private suspend fun updateTool(toolCallId: String, transform: (UIMessagePart.Tool) -> UIMessagePart.Tool) {
        val current = checkNotNull(message)
        update(
            current.copy(
                parts = current.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) transform(part) else part
                }
            )
        )
    }

    private suspend fun execute(tool: UIMessagePart.Tool): List<UIMessagePart> =
        when (val state = tool.approvalState) {
            is ToolApprovalState.Denied -> errorOutput(
                "Tool execution denied by user. Reason: ${state.reason.ifBlank { "No reason provided" }}"
            )

            is ToolApprovalState.Answered -> listOf(UIMessagePart.Text(state.answer))
            ToolApprovalState.Auto, ToolApprovalState.Approved -> runTool(tool)
            ToolApprovalState.Pending -> error("Tool ${tool.toolCallId} is still waiting for approval")
        }

    private suspend fun runTool(tool: UIMessagePart.Tool): List<UIMessagePart> = try {
        val definition = findTool(tool.toolName) ?: error("Tool ${tool.toolName} not found")
        val args = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
            .getOrElse { error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}") }
        definition.execute(args).ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }
    } catch (e: CancellationException) {
        // 取消必须向上传播，否则停止生成会被误报为工具执行错误
        throw e
    } catch (e: Throwable) {
        errorOutput("[${e.javaClass.name}] ${e.message}\n${e.stackTraceToString()}")
    }

    private fun findTool(name: String) = request.tools.find { it.name == name }

    private suspend fun update(updated: UIMessage) {
        message = updated
        events.emit(AgentEvent.MessageUpdated(updated))
    }

    // 生成中途的内容只发给调用方，这一步结束前循环内部一直基于原始内容合并
    private suspend fun preview(generating: UIMessage, newPartsFrom: Int) {
        events.emit(AgentEvent.MessageUpdated(hooks.previewStep(generating, newPartsFrom)))
    }

    private suspend fun finish(reason: AgentFinishReason) {
        val final = message ?: error("agent finished without an assistant message")
        // 续写用完了还是空的：写一句兜底话，别在会话里留下一个点不开的空气泡。
        // 等审批时不算空（工具调用就是这一步的产出），不能塞文本进去
        val fallback = request.continuation.emptyFallback
        val patched = if (reason !is AgentFinishReason.AwaitingApproval &&
            fallback.isNotBlank() && !hasVisibleContent(final.parts)
        ) {
            final.copy(parts = final.parts + UIMessagePart.Text(fallback)).also { update(it) }
        } else {
            final
        }
        events.emit(AgentEvent.Finished(patched, reason))
    }
}

private fun errorOutput(error: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject { put("error", error) }.toString()))

/**
 * 这一步是不是被输出上限截断的。
 *
 * 各家 provider 的写法不统一（OpenAI 的 length、OpenAI Responses 的 max_output_tokens、
 * Anthropic 的 max_tokens、Gemini 的 MAX_TOKENS、以及不完整状态），这里统一按小写子串认。
 */
private fun isTruncated(finishReason: String?): Boolean {
    val reason = finishReason?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    return "length" in reason || "max_token" in reason || "incomplete" in reason
}

// 只有文本、推理这类看得见的内容才算数：纯工具调用、空 part 同样要续写
private fun hasVisibleContent(parts: List<UIMessagePart>?): Boolean {
    if (parts == null) return false
    return parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> false
        }
    }
}

// 把本步的 usage 和生成耗时累加到之前步骤的统计上；接收者只带本步的 usage
private fun UIMessage.withStepStats(stepBase: UIMessage, stepTime: Duration): UIMessage = copy(
    usage = stepBase.usage.sum(usage),
    generationTimeMs = (stepBase.generationTimeMs ?: 0L) + stepTime.inWholeMilliseconds,
    // 上下文占用只看最后一次请求，usage 是多步累计值；本步没返回 usage 时沿用之前步骤的
    lastUsage = usage ?: stepBase.lastUsage,
    stepCount = (stepBase.stepCount ?: 0) + 1,
)

private fun now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
