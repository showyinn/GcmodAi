package com.gcmodai.app.data.ai.tools.local

import android.content.Context
import com.gcmodai.ai.core.Tool
import com.gcmodai.app.data.datastore.SettingsStore
import com.gcmodai.app.data.event.AppEventBus
import com.gcmodai.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    /** 服务器工作区仓库：供 AI 直接读写用户 100MB 工作区并部署网站 */
    private val workspaceRepository: com.gcmodai.app.data.workspace.WorkspaceRepository? = null,
    /** SSH 工作区仓库：供 AI 在用户自己的服务器上建站（与上面同名但不同类） */
    private val sshRepository: com.gcmodai.app.data.repository.WorkspaceRepository? = null,
    /** Windows 助手（exe）客户端：不配 SSH 也能控制 Windows 电脑 */
    private val pcAgentRepository: com.gcmodai.app.data.agent.PcAgentRepository? = null,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        // 过滤掉无法识别的占位项（旧版本或未来版本写入的工具名）
        val enabled = options.filter { it != LocalToolOption.Unknown }.toSet()
        val tools = mutableListOf<Tool>()
        if (enabled.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (enabled.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (enabled.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (enabled.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (enabled.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (enabled.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (enabled.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (enabled.contains(LocalToolOption.ChartDisplay)) {
            tools.add(chartDisplayTool)
        }
        if (enabled.contains(LocalToolOption.GcmodAiOfficial)) {
            tools.addAll(GcmodAiTools.all())
        }
        if (enabled.contains(LocalToolOption.WorkspaceServer)) {
            workspaceRepository?.let { tools.addAll(WorkspaceTools.all(it)) }
        }
        if (enabled.contains(LocalToolOption.SshRemote)) {
            sshRepository?.let { tools.addAll(SshRemoteTools.all(it)) }
        }
        if (enabled.contains(LocalToolOption.PcControl)) {
            // 电脑控制有两条通道：
            //   1) SSH（pc_*）：服务器、有 SSH 的机器
            //   2) Windows 助手（wpc_*）：小白向，双击小程序即可，不用配 SSH
            sshRepository?.let { tools.addAll(PcControlTools.all(it)) }
            // Windows 助手走 wpc_* 工具，和上面 SSH 的 pc_* 并列
            pcAgentRepository?.let { tools.addAll(PcAgentTools.all(it)) }
        }
        return tools
    }
}
