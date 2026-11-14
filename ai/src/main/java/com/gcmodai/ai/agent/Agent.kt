package com.gcmodai.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.provider.Provider
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.ai.provider.TextGenerationParams
import com.gcmodai.ai.ui.UIMessage

/** 自动续写的触发条件，命中任意一个且还有剩余次数时，run 会接着往下跑而不是就此收工 */
enum class ContinuationTrigger {
    /** 这一步什么内容都没产出：断流、空回复 */
    EMPTY,

    /** 这一步被输出上限截断（length / max_tokens / incomplete） */
    TRUNCATED,

    /** 任务模式：没看到完成标记，说明模型认为还没干完 */
    UNFINISHED,

    /** 步数配额用完了，但活可能还没干完 */
    STEP_LIMIT,
}

/**
 * 断流保护 / 任务模式：模型没真正干完时自动接着跑，不让一轮对话停在一半。
 *
 * 原理是**不结束这一轮**：命中 [triggers] 时把续写提示作为一条用户消息塞进历史，
 * 再请求一次模型，产出仍然写回同一条助手消息，所以界面上的气泡是连续的、不会出现「说到一半就停」。
 * [maxContinuations] 是硬上限，防止模型反复空转烧额度。
 */
data class ContinuationPolicy(
    /** 最多自动续写几次；0 表示关闭，行为和以前完全一致 */
    val maxContinuations: Int = 0,
    val triggers: Set<ContinuationTrigger> = ContinuationPolicy.DEFAULT_TRIGGERS,
    /** 任务完成标记：输出的文本里出现任意一个就收工；为空表示不做标记判定 */
    val doneMarkers: List<String> = DEFAULT_DONE_MARKERS,
    /** 续写时发给模型的提示 */
    val nudge: String = ContinuationPolicy.DEFAULT_NUDGE,
    /** 续写次数用完还是空的，用这句话兜底，避免留下一个空气泡 */
    val emptyFallback: String = "",
) {
    val enabled: Boolean get() = maxContinuations > 0

    companion object {
        val DEFAULT_TRIGGERS: Set<ContinuationTrigger> = setOf(
            ContinuationTrigger.EMPTY,
            ContinuationTrigger.TRUNCATED,
            ContinuationTrigger.UNFINISHED,
            ContinuationTrigger.STEP_LIMIT,
        )

        /** 常见的任务完成标记，模型自己写哪一种都算数 */
        val DEFAULT_DONE_MARKERS: List<String> = listOf(
            "[DONE]",
            "【任务完成】",
            "任务完成",
            "全部完成",
        )

        const val DEFAULT_NUDGE =
            "继续。不要重复已经写过的内容，从断开的地方接着往下做，不要在任务没做完时停下；全部完成后最后单独一行输出 [DONE]。"

        // 放在最后：构造时要读上面几个默认值，写在前面会读到还没初始化的字段
        val NONE = ContinuationPolicy()
    }
}

/**
 * 一次 agent 运行的输入。
 *
 * [messages] 的最后一条是助手消息时，从它继续：先处理它上面还没执行的工具调用（比如审批之后），
 * 再请求模型；否则新建一条助手消息。一次运行只产出这一条助手消息，其余消息只读，不会被改写。
 */
data class AgentRequest(
    val provider: Provider<ProviderSetting>,
    val providerSetting: ProviderSetting,
    val messages: List<UIMessage>,
    val tools: List<Tool> = emptyList(),
    val stream: Boolean = true,
    /** 最多请求模型的次数 */
    val maxSteps: Int = 256,
    val retry: RetryPolicy = RetryPolicy.NONE,
    /** 断流保护 / 任务模式；默认关闭 */
    val continuation: ContinuationPolicy = ContinuationPolicy.NONE,
)

/** 调用方在循环各个节点上的介入点 */
interface AgentHooks {
    /**
     * 每次请求模型之前调用，返回这一步实际发出的消息和参数，系统提示词、上下文裁剪、输入转换都在这里做。
     *
     * [messages] 是历史加上本轮的助手消息；第一步还没有内容时不包含它。
     * 返回的 [StepRequest.params] 里的 tools 会被换成 [AgentRequest.tools]，声明给模型的工具和能执行的工具始终是同一份。
     */
    suspend fun prepareStep(messages: List<UIMessage>): StepRequest

    /**
     * 生成过程中每次发出更新之前调用，返回值只用于这一次 [AgentEvent.MessageUpdated]，不会写回循环内部。
     * 适合 think 标签这类要边生成边展示、等这一步结束才真正处理的转换；[newPartsFrom] 的含义同 [finishStep]。
     */
    suspend fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /**
     * 每一步生成结束、执行工具之前调用，返回值作为这一步的结果。
     *
     * 下标 [newPartsFrom] 及之后的 part 是这一步新生成的；之前的来自更早的步骤，已经处理过一次。
     */
    suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage = message
}

data class StepRequest(
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
)

sealed interface AgentEvent {
    /** 本轮助手消息的最新内容，整次运行 id 不变 */
    data class MessageUpdated(val message: UIMessage) : AgentEvent

    /** 请求失败，即将重试；之后再收到 [MessageUpdated] 说明重试的请求已经返回内容 */
    data class Retrying(val attempt: RetryAttempt) : AgentEvent

    /**
     * 断流保护 / 任务模式触发续写：[round] 是第几次（从 1 开始），[max] 是上限。
     * 界面可以据此显示「自动续写 1/3」，不必让用户以为生成已经结束了。
     */
    data class Continuing(val round: Int, val max: Int) : AgentEvent

    /** 运行结束，总是最后一个事件；出错时直接抛出异常，不会发出这个事件 */
    data class Finished(val message: UIMessage, val reason: AgentFinishReason) : AgentEvent
}

sealed interface AgentFinishReason {
    /** 模型最后一步没有再调用工具 */
    data object Completed : AgentFinishReason

    /** 有工具在等待用户审批；写入审批结果后，用这条消息再运行一次即可继续 */
    data class AwaitingApproval(val toolCallIds: Set<String>) : AgentFinishReason

    /** 达到 [AgentRequest.maxSteps]，最后一步调用的工具已经执行，但模型还没有回复 */
    data object StepLimitReached : AgentFinishReason
}

/**
 * 运行 agent 循环：请求模型 → 执行工具 → 再请求模型，直到模型不再调用工具、有工具需要审批或达到步数上限。
 *
 * 工具按调用顺序逐个执行，有任意一个需要审批时，同一批的工具都等审批后再一起执行。
 * 返回的 Flow 不切换调度器，Provider 请求和工具执行都在收集方的上下文里运行，需要时由调用方 flowOn。
 * 取消时直接抛出，最后一个 [AgentEvent.MessageUpdated] 就是取消前的状态，已经执行完的工具结果都在里面。
 */
fun runAgent(request: AgentRequest, hooks: AgentHooks): Flow<AgentEvent> = flow {
    require(request.maxSteps > 0) { "maxSteps must be positive" }
    AgentLoop(request, hooks, this).run()
}
