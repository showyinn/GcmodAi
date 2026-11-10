package com.gcmodai.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.ai.provider.TextGenerationResult
import com.gcmodai.ai.provider.TextGenerationParams
import com.gcmodai.ai.ui.StreamChunk
import com.gcmodai.ai.ui.UIMessage

interface OpenAIImpl {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>
}
