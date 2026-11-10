package com.gcmodai.app.data.ai

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import com.gcmodai.ai.core.TokenUsage
import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.Provider
import com.gcmodai.ai.provider.ProviderSetting
import com.gcmodai.ai.provider.TextGenerationParams
import com.gcmodai.ai.provider.TextGenerationResult
import com.gcmodai.ai.ui.StreamChunk
import com.gcmodai.ai.ui.UIMessage
import com.gcmodai.app.data.db.dao.DayCount
import com.gcmodai.app.data.db.dao.ModelUsage
import com.gcmodai.app.data.db.dao.SourceUsage
import com.gcmodai.app.data.db.dao.UsageBucket
import com.gcmodai.app.data.db.dao.UsageRecordDAO
import com.gcmodai.app.data.db.dao.UsageTotals
import com.gcmodai.app.data.db.entity.UsageRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class UsageRecorderTest {
    private val dao = FakeUsageRecordDAO()
    private val setting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "gpt-test", displayName = "Test")
    private val params = TextGenerationParams(model = model)
    private val tag = UsageTag(UsageSource.CHAT, assistantId = Uuid.random(), conversationId = Uuid.random())

    private fun TestScope.recorder() = UsageRecorder(dao, this)

    @Test
    fun `generateText records the usage with its tag`() = runTest {
        val provider = FakeProvider(result = TokenUsage(promptTokens = 10, completionTokens = 2, cachedTokens = 4))

        recorder().wrap(provider, tag).generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        val record = dao.records.single()
        assertEquals(UsageSource.CHAT.name, record.source)
        assertEquals(setting.id.toString(), record.providerId)
        assertEquals(model.id.toString(), record.modelId)
        assertEquals("gpt-test", record.modelName)
        assertEquals(tag.assistantId.toString(), record.assistantId)
        assertEquals(tag.conversationId.toString(), record.conversationId)
        assertEquals(listOf(10, 2, 4), listOf(record.promptTokens, record.completionTokens, record.cachedTokens))
    }

    @Test
    fun `stream records the merged usage once`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10)))
                emit(StreamChunk.TextDelta("text", "hi"))
                emit(StreamChunk.Usage(TokenUsage(completionTokens = 5, cachedTokens = 3)))
            }
        )

        val chunks = recorder().wrap(provider, tag).streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(3, chunks.size)
        val record = dao.records.single()
        assertEquals(listOf(10, 5, 3), listOf(record.promptTokens, record.completionTokens, record.cachedTokens))
    }

    @Test
    fun `stream cancelled midway still records usage received so far`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10)))
                emit(StreamChunk.TextDelta("text", "hi"))
                awaitCancellation()
            }
        )

        recorder().wrap(provider, tag).streamText(setting, emptyList(), params).take(2).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(10, dao.records.single().promptTokens)
    }

    @Test
    fun `requests without usage are not recorded`() = runTest {
        val recorder = recorder()
        recorder.wrap(FakeProvider(result = null), tag).generateText(setting, emptyList(), params)
        recorder.wrap(FakeProvider(chunks = flow { emit(StreamChunk.TextDelta("text", "hi")) }), tag)
            .streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertTrue(dao.records.isEmpty())
    }
}

private class FakeUsageRecordDAO : UsageRecordDAO {
    val records = mutableListOf<UsageRecordEntity>()

    override suspend fun insert(record: UsageRecordEntity) {
        records += record
    }

    override suspend fun getTotals(since: Long) = UsageTotals()

    override suspend fun getTotalsByBucket(format: String, since: Long) = emptyList<UsageBucket>()

    override suspend fun getTotalsByModel(since: Long) = emptyList<ModelUsage>()

    override suspend fun getRequestCountPerDay(source: String, since: Long) = emptyList<DayCount>()

    override suspend fun getTotalsBySource(since: Long) = emptyList<SourceUsage>()
}

private class FakeProvider(
    private val result: TokenUsage? = null,
    private val chunks: Flow<StreamChunk> = flow {},
) : Provider<ProviderSetting.OpenAI> {
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ) = TextGenerationResult(
        id = "result",
        model = params.model.modelId,
        message = UIMessage.assistant(""),
        usage = result
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = chunks
}
