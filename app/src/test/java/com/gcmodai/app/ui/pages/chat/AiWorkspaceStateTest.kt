package com.gcmodai.app.ui.pages.chat

import com.gcmodai.app.data.ai.tools.local.LocalToolOption
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * AI 工作区选择推导验证。
 *
 * 规则：外层三选一（Cloud / Ssh / None），Ssh 模式下子服务器可多选。
 * 界面显示的状态必须与实际生效的工具严格一致——否则用户以为在用云端，
 * 代码实际写到了别处。
 */
class AiWorkspaceStateTest {

    private fun assistant(
        name: String = "默认助手",
        tools: List<LocalToolOption> = emptyList(),
        workspaceId: Uuid? = null,
        sshIds: List<Uuid> = emptyList(),
    ) = Assistant(
        id = Uuid.random(),
        name = name,
        localTools = tools,
        workspaceId = workspaceId,
        sshWorkspaceIds = sshIds,
    )

    private fun settingsWith(vararg all: Assistant, current: Assistant? = null): Settings {
        val list = all.toList()
        return Settings(
            assistantId = (current ?: list.first()).id,
            assistants = list,
        )
    }

    // ---------------- 外层三选一 ----------------

    @Test
    fun `cloud mode when WorkspaceServer enabled`() {
        val a = assistant(tools = listOf(LocalToolOption.WorkspaceServer))
        val state = aiWorkspaceStateOf(settingsWith(a))
        assertEquals(AiWorkspaceMode.Cloud, state.mode)
        assertTrue(state.enabled)
    }

    @Test
    fun `none mode when no workspace tool`() {
        val a = assistant(tools = listOf(LocalToolOption.TimeInfo))
        val state = aiWorkspaceStateOf(settingsWith(a))
        assertEquals(AiWorkspaceMode.None, state.mode)
        assertFalse(state.enabled)
    }

    @Test
    fun `none mode when assistant missing`() {
        val a = assistant()
        val settings = Settings(assistantId = Uuid.random(), assistants = listOf(a))
        assertEquals(AiWorkspaceMode.None, aiWorkspaceStateOf(settings).mode)
    }

    // ---------------- SSH 模式 ----------------

    @Test
    fun `ssh mode when SshRemote tool enabled`() {
        val a = assistant(tools = listOf(LocalToolOption.SshRemote))
        assertEquals(AiWorkspaceMode.Ssh, aiWorkspaceStateOf(settingsWith(a)).mode)
    }

    @Test
    fun `ssh mode wins over cloud tool when both present`() {
        // 异常数据（两个都开着）：以 SSH 为准，避免"以为云端其实远程"
        val a = assistant(
            tools = listOf(LocalToolOption.WorkspaceServer, LocalToolOption.SshRemote),
        )
        assertEquals(AiWorkspaceMode.Ssh, aiWorkspaceStateOf(settingsWith(a)).mode)
    }

    @Test
    fun `ssh mode via bound workspaceId`() {
        val target = assistant(name = "机房A")
        val current = assistant(workspaceId = target.id)
        val state = aiWorkspaceStateOf(settingsWith(current, target, current = current))
        assertEquals(AiWorkspaceMode.Ssh, state.mode)
        assertEquals(listOf(target.id.toString()), state.sshIds)
        assertEquals(listOf("机房A"), state.sshNames)
    }

    // ---------------- 子服务器多选 ----------------

    @Test
    fun `multi selected ssh servers are preserved`() {
        val a1 = assistant(name = "机房A")
        val a2 = assistant(name = "机房B")
        val current = assistant(
            tools = listOf(LocalToolOption.SshRemote),
            sshIds = listOf(a1.id, a2.id),
        )
        val state = aiWorkspaceStateOf(settingsWith(current, a1, a2, current = current))
        assertEquals(AiWorkspaceMode.Ssh, state.mode)
        assertEquals(2, state.sshIds.size)
        assertEquals(listOf("机房A", "机房B"), state.sshNames)
    }

    @Test
    fun `removed server shows placeholder name`() {
        val ghost = Uuid.random()
        val current = assistant(
            tools = listOf(LocalToolOption.SshRemote),
            sshIds = listOf(ghost),
        )
        val state = aiWorkspaceStateOf(settingsWith(current))
        assertEquals(listOf("已移除的服务器"), state.sshNames)
    }

    // ---------------- 摘要文案 ----------------

    @Test
    fun `labels are human readable`() {
        assertEquals("不使用工作区", AiWorkspaceState(mode = AiWorkspaceMode.None).label)
        assertTrue(
            AiWorkspaceState(mode = AiWorkspaceMode.Cloud).label.contains("100MB")
        )
    }

    @Test
    fun `ssh label single vs multi`() {
        val single = AiWorkspaceState(
            mode = AiWorkspaceMode.Ssh,
            sshIds = listOf("1"),
            sshNames = listOf("机房A"),
        )
        assertEquals("机房A", single.label)

        val multi = AiWorkspaceState(
            mode = AiWorkspaceMode.Ssh,
            sshIds = listOf("1", "2"),
            sshNames = listOf("机房A", "机房B"),
        )
        assertTrue(multi.label.contains("2 台"))
    }

    @Test
    fun `ssh mode with no servers selected warns in label`() {
        val empty = AiWorkspaceState(mode = AiWorkspaceMode.Ssh)
        assertTrue(empty.label.contains("未选择"))
    }

    @Test
    fun `enabled reflects mode`() {
        assertFalse(AiWorkspaceState(mode = AiWorkspaceMode.None).enabled)
        assertTrue(AiWorkspaceState(mode = AiWorkspaceMode.Cloud).enabled)
        assertTrue(AiWorkspaceState(mode = AiWorkspaceMode.Ssh).enabled)
    }
}
