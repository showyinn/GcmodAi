package com.gcmodai.app.data.ai

import com.gcmodai.ai.agent.ContinuationPolicy
import com.gcmodai.ai.agent.ContinuationTrigger
import com.gcmodai.ai.agent.RetryPolicy
import com.gcmodai.app.data.datastore.NetworkSetting
import com.gcmodai.app.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 设置里的任务模式开关 → agent 循环用的续写策略，只做映射，不做别的判断
class TaskModePolicyTest {
    @Test
    fun `switching task mode off disables continuation entirely`() {
        val policy = Settings(taskModeEnabled = false, taskModeMaxContinuations = 8).continuationPolicy()

        assertEquals(ContinuationPolicy.NONE, policy)
        assertFalse(policy.enabled)
    }

    @Test
    fun `enabled task mode watches every trigger in strict mode`() {
        val policy = Settings(
            taskModeEnabled = true,
            taskModeStrict = true,
            taskModeMaxContinuations = 5,
        ).continuationPolicy()

        assertTrue(policy.enabled)
        assertEquals(5, policy.maxContinuations)
        assertEquals(ContinuationPolicy.DEFAULT_TRIGGERS, policy.triggers)
    }

    // 默认不盯「有没有完成标记」：否则普通聊天也会被一直续写，白烧额度
    @Test
    fun `non-strict task mode only watches the objective failures`() {
        val policy = Settings(
            taskModeEnabled = true,
            taskModeStrict = false,
            taskModeMaxContinuations = 5,
        ).continuationPolicy()

        assertTrue(ContinuationTrigger.EMPTY in policy.triggers)
        assertTrue(ContinuationTrigger.TRUNCATED in policy.triggers)
        assertTrue(ContinuationTrigger.STEP_LIMIT in policy.triggers)
        assertFalse(ContinuationTrigger.UNFINISHED in policy.triggers)
    }

    @Test
    fun `strict task mode also waits for the done marker`() {
        val policy = Settings(
            taskModeEnabled = true,
            taskModeStrict = true,
            taskModeMaxContinuations = 5,
        ).continuationPolicy()

        assertTrue(ContinuationTrigger.UNFINISHED in policy.triggers)
        assertTrue(ContinuationTrigger.EMPTY in policy.triggers)
    }

    @Test
    fun `a custom marker is kept next to the built-in ones`() {
        val policy = Settings(taskModeDoneMarker = "@@END@@").continuationPolicy()

        assertTrue("@@END@@" in policy.doneMarkers)
        assertTrue("[DONE]" in policy.doneMarkers)
    }

    @Test
    fun `an empty marker falls back to the built-in markers`() {
        val policy = Settings(taskModeDoneMarker = "   ").continuationPolicy()

        assertEquals(ContinuationPolicy.DEFAULT_DONE_MARKERS, policy.doneMarkers)
    }

    @Test
    fun `an empty nudge falls back to the built-in text`() {
        val policy = Settings(taskModeNudge = "").continuationPolicy()

        assertEquals(ContinuationPolicy.DEFAULT_NUDGE, policy.nudge)
    }

    @Test
    fun `a custom nudge is used as written`() {
        val policy = Settings(taskModeNudge = " 接着写 ").continuationPolicy()

        assertEquals("接着写", policy.nudge)
    }

    @Test
    fun `zero rounds still counts as switched off`() {
        val policy = Settings(taskModeEnabled = true, taskModeMaxContinuations = 0).continuationPolicy()

        assertFalse(policy.enabled)
        assertTrue(ContinuationTrigger.EMPTY in policy.triggers)
    }

    // ---- 重试策略：任务模式开得狠一点 ----

    @Test
    fun `task mode raises the request retry budget to ten`() {
        val settings = Settings(
            taskModeEnabled = true,
            networkSetting = NetworkSetting(enableAutoRetry = true),
        )

        // 长任务撞一次网关维护页就整轮报废，代价远大于多等十几秒
        assertEquals(RetryPolicy.PERSISTENT, settings.retryPolicy())
        assertEquals(10, settings.retryPolicy().maxRetries)
    }

    @Test
    fun `without task mode the retry budget stays at three`() {
        val settings = Settings(
            taskModeEnabled = false,
            networkSetting = NetworkSetting(enableAutoRetry = true),
        )

        assertEquals(RetryPolicy.NETWORK, settings.retryPolicy())
        assertEquals(3, settings.retryPolicy().maxRetries)
    }

    @Test
    fun `turning auto retry off disables retries entirely`() {
        val settings = Settings(
            taskModeEnabled = true,
            networkSetting = NetworkSetting(enableAutoRetry = false),
        )

        // 用户明确关了自动重试，任务模式也不能偷偷重试
        assertEquals(RetryPolicy.NONE, settings.retryPolicy())
        assertEquals(0, settings.retryPolicy().maxRetries)
    }
}
