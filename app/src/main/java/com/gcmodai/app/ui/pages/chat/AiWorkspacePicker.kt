package com.gcmodai.app.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcmodai.app.data.repository.WorkspaceRepository
import com.gcmodai.app.data.repository.WorkspaceSummary
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.ServerStack01
import org.koin.compose.koinInject

/** 外层三选一：云端 / SSH / 不使用。 */
enum class AiWorkspaceMode {
    Cloud,
    Ssh,
    None,
}

/**
 * AI 的工作区配置。
 *
 * ## 结构
 *
 * 外层是**三选一**（云端 / SSH / 不使用），选 SSH 之后
 * 再在子列表里**多选**要启用的服务器。
 *
 * 为什么这样分两层：用户先要决定"用我们的还是用自己的"，
 * 这是一次互斥的选择；而自己有服务器时，勾几台是另一回事，
 * 所以子列表做成多选。
 */
data class AiWorkspaceState(
    val mode: AiWorkspaceMode = AiWorkspaceMode.Cloud,
    val sshIds: List<String> = emptyList(),
    val sshNames: List<String> = emptyList(),
    /** 选中的本地工作区（单选）。本地工作区供 workspace_* 工具用，不能被 SSH 控制 */
    val localId: String? = null,
    val localName: String? = null,
) {
    val enabled: Boolean get() = mode != AiWorkspaceMode.None

    /** 抽屉里显示的一句话摘要。 */
    val label: String
        get() = when (mode) {
            AiWorkspaceMode.Cloud -> "我的工作区（100MB）"
            AiWorkspaceMode.None -> "不使用工作区"
            AiWorkspaceMode.Ssh -> {
                val parts = buildList {
                    if (sshIds.size == 1) add(sshNames.firstOrNull() ?: "SSH 服务器")
                    else if (sshIds.size > 1) add("${sshNames.firstOrNull() ?: "SSH"} 等 ${sshIds.size} 台")
                    localName?.let { add("本地 $it") }
                }
                parts.joinToString(" + ").ifBlank { "SSH 远程服务器（未选择）" }
            }
        }
}

/**
 * 抽屉里的入口行：显示当前 AI 工作区，点开选择。
 *
 * 外观与抽屉其它条目一致（圆形图标底 + 主副标题 + 右侧箭头）。
 */
@Composable
fun AiWorkspaceRow(
    state: AiWorkspaceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (state.enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Icon(
                    imageVector = when (state.mode) {
                        AiWorkspaceMode.Cloud -> HugeIcons.CloudUpload
                        AiWorkspaceMode.Ssh -> HugeIcons.ServerStack01
                        AiWorkspaceMode.None -> HugeIcons.Folder01
                    },
                    contentDescription = null,
                    tint = if (state.enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(8.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 工作区",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 选择 AI 工作区：三选一 + SSH 子服务器多选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWorkspaceSheet(
    state: AiWorkspaceState,
    onSelect: (AiWorkspaceState) -> Unit,
    onDismiss: () -> Unit,
    /** 新建 SSH 服务器。由调用方先关弹层再跳转，避免关闭动画吞掉导航 */
    onAddSsh: () -> Unit,
) {
    val sshRepository: WorkspaceRepository = koinInject()
    // 列出全部工作区：SSH 的可多选，本地的单独提示。
    // 用 listWorkspacesNow 而不是 listSshWorkspacesNow——后者会把本地工作区
    // 和解析失败的条目过滤掉，导致"明明有服务器却选不到"。
    var allWorkspaces by remember { mutableStateOf<List<WorkspaceSummary>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allWorkspaces = runCatching { sshRepository.listWorkspacesNow() }
            .onFailure { loadFailed = true }
            .getOrDefault(emptyList())
    }

    val sshServers = allWorkspaces.filter { it.isSsh }
    val localWorkspaces = allWorkspaces.filter { !it.isSsh }

    // 用 LazyColumn 承载内容：ModalBottomSheet 会把上下拖拽当成"展开/收起"，
    // 内层 Column + verticalScroll 拿不到手势，看起来就是"滑不动"。
    // LazyColumn 与弹层通过嵌套滚动协作，才能正常滚动。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 8.dp,
                bottom = 40.dp,
            ),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "AI 在哪个工作区运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "先选一种方式。选「SSH 远程服务器」后，可以再勾选要启用的服务器（可多选）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------- 外层三选一 ----------
            item {
                CardGroup(title = { Text("运行位置") }) {
                    item(
                        onClick = { onSelect(state.copy(mode = AiWorkspaceMode.Cloud)) },
                        leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                        headlineContent = { Text("我的工作区") },
                        supportingContent = { Text("100MB 独立空间，开箱即用") },
                        trailingContent = {
                            if (state.mode == AiWorkspaceMode.Cloud) {
                                CardGroupValue(text = "已选")
                            }
                        },
                    )
                    item(
                        onClick = { onSelect(state.copy(mode = AiWorkspaceMode.Ssh)) },
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                        headlineContent = { Text("SSH 远程服务器") },
                        supportingContent = { Text("用你自己的服务器，可勾多台") },
                        trailingContent = {
                            if (state.mode == AiWorkspaceMode.Ssh) {
                                CardGroupValue(text = "已选")
                            }
                        },
                    )
                    item(
                        onClick = { onSelect(state.copy(mode = AiWorkspaceMode.None)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                        headlineContent = { Text("不使用工作区") },
                        supportingContent = { Text("AI 只读不写，不碰任何文件") },
                        trailingContent = {
                            if (state.mode == AiWorkspaceMode.None) {
                                CardGroupValue(text = "已选")
                            }
                        },
                    )
                }
            }

            // ---------- SSH 子列表 ----------
            if (state.mode == AiWorkspaceMode.Ssh) {
                item {
                    CardGroup(title = { Text("选择服务器（可多选）") }) {
                        item(
                            onClick = { onAddSsh() },
                            leadingContent = { CardGroupIcon(HugeIcons.Add01) },
                            headlineContent = { Text("添加 SSH 服务器") },
                            supportingContent = { Text("点这里新建一台，填好主机和账号就能用") },
                        )

                        if (sshServers.isEmpty()) {
                            item(
                                leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                                headlineContent = { Text("还没有 SSH 服务器") },
                                supportingContent = {
                                    Text(
                                        when {
                                            loadFailed -> "读取失败，去「设置 - 工作区与建站」检查"
                                            localWorkspaces.isNotEmpty() ->
                                                "你现有的是本地工作区（${localWorkspaces.joinToString("、") { it.name }}），" +
                                                    "它只能在本机用；要远程控制请点上面新建 SSH 服务器"
                                            else -> "点上面「添加 SSH 服务器」新建一台"
                                        }
                                    )
                                },
                            )
                        } else {
                            sshServers.forEach { server ->
                                val selected = server.id in state.sshIds
                                item(
                                    onClick = {
                                        if (selected) {
                                            val idx = state.sshIds.indexOf(server.id)
                                            onSelect(
                                                state.copy(
                                                    sshIds = state.sshIds - server.id,
                                                    sshNames = state.sshNames
                                                        .filterIndexed { i, _ -> i != idx },
                                                )
                                            )
                                        } else {
                                            onSelect(
                                                state.copy(
                                                    sshIds = state.sshIds + server.id,
                                                    sshNames = state.sshNames + server.name,
                                                )
                                            )
                                        }
                                    },
                                    leadingContent = {
                                        if (selected) CardGroupIcon(HugeIcons.CheckmarkCircle02)
                                        else CardGroupIcon(HugeIcons.ServerStack01)
                                    },
                                    headlineContent = { Text(server.name) },
                                    supportingContent = {
                                        Text(
                                            if (server.configBroken) {
                                                "配置损坏，请到「设置 - 工作区与建站」重新编辑"
                                            } else {
                                                server.address
                                            }
                                        )
                                    },
                                    trailingContent = {
                                        if (selected) CardGroupValue(text = "已勾选")
                                    },
                                )
                            }
                        }
                    }
                }

                // 本地工作区单独一块，保证一定可见、可点
                if (localWorkspaces.isNotEmpty()) {
                    item {
                        CardGroup(title = { Text("本地工作区") }) {
                            item(
                                headlineContent = { Text("说明") },
                                supportingContent = {
                                    Text("只能在本机用，供本地文件工具；不能被 SSH 远程控制")
                                },
                            )
                            localWorkspaces.forEach { ws ->
                                val selected = state.localId == ws.id
                                item(
                                    onClick = {
                                        onSelect(
                                            if (selected) {
                                                state.copy(localId = null, localName = null)
                                            } else {
                                                state.copy(localId = ws.id, localName = ws.name)
                                            }
                                        )
                                    },
                                    leadingContent = {
                                        if (selected) CardGroupIcon(HugeIcons.CheckmarkCircle02)
                                        else CardGroupIcon(HugeIcons.Folder01)
                                    },
                                    headlineContent = { Text(ws.name) },
                                    supportingContent = { Text("本地") },
                                    trailingContent = {
                                        if (selected) CardGroupValue(text = "已勾选")
                                    },
                                )
                            }
                        }
                    }
                }

                if (sshServers.isNotEmpty() && state.sshIds.isEmpty() && state.localId == null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.ServerStack01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "还没勾选服务器，AI 目前没有可写的远程目录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
