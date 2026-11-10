package com.gcmodai.app.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.PencilEdit01
import com.gcmodai.app.Screen
import com.gcmodai.app.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import com.gcmodai.app.R
import com.gcmodai.app.ui.components.ai.ExtensionEmptyState
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.ItemAction
import com.gcmodai.app.ui.components.ui.ItemActionMenu
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.plus
import com.gcmodai.ui.components.GcmodAiConfirmDialog
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import com.gcmodai.app.data.ai.tools.local.LocalToolOption
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    // 点击列表项弹出的操作弹窗（不再需要长按探索隐藏菜单）
    var actionTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }

    val settingsStore: com.gcmodai.app.data.datastore.SettingsStore = org.koin.compose.koinInject()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val toaster = com.gcmodai.app.ui.context.LocalToaster.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.workspace_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTypeSheet = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_page_empty),
                        description = stringResource(R.string.workspace_page_empty_desc),
                        icon = HugeIcons.FolderCode,
                    )
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceItem(
                    workspace = workspace,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    // 点击进详情，长按弹操作窗：
                    // Surface 的 onClick 只认单击，长按必须用 combinedClickable 才生效。
                    // 之前把弹窗挂在单击上，用户点右侧三点看到的是旧菜单，就以为"没效果"。
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                    onLongPress = { actionTarget = workspace },
                )
            }
        }
    }

    // ---------- 点击工作区后的操作弹窗 ----------
    actionTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            CardGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                title = { Text(target.name) },
            ) {
                item(
                    onClick = {
                        actionTarget = null
                        navController.navigate(Screen.WorkspaceDetail(target.id))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                    headlineContent = { Text("打开详情") },
                    supportingContent = { Text("浏览文件、开终端、改配置") },
                )
                item(
                    onClick = {
                        actionTarget = null
                        val targetId = target.id
                        coroutineScope.launch {
                            settingsStore.update { current ->
                                current.copy(
                                    assistants = current.assistants.map { assistant ->
                                        if (assistant.id != current.assistantId) assistant else {
                                            val tools = assistant.localTools
                                                .filter {
                                                    it != LocalToolOption.WorkspaceServer &&
                                                        it != LocalToolOption.SshRemote
                                                }
                                                .toMutableList()
                                            val parsed = runCatching { Uuid.parse(targetId) }.getOrNull()
                                            if (target.isSsh()) {
                                                // SSH：开远程工具并绑定，供 pc_* / ssh_* 使用
                                                tools.add(LocalToolOption.SshRemote)
                                                assistant.copy(
                                                    localTools = tools,
                                                    workspaceId = parsed,
                                                    sshWorkspaceIds = listOfNotNull(parsed),
                                                )
                                            } else {
                                                // 本地：绑定给 workspace_* 工具
                                                assistant.copy(
                                                    localTools = tools,
                                                    workspaceId = parsed,
                                                    sshWorkspaceIds = emptyList(),
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            toaster.show("已设为 AI 工作区：${'$'}{target.name}")
                        }
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
                    headlineContent = { Text("设为 AI 工作区") },
                    supportingContent = {
                        Text(
                            if (target.isSsh()) "让 AI 可以控制这台服务器"
                            else "让 AI 使用这个本地工作区"
                        )
                    },
                )
                item(
                    onClick = {
                        actionTarget = null
                        editTarget = target
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.PencilEdit01) },
                    headlineContent = { Text(stringResource(R.string.common_rename)) },
                )
                item(
                    onClick = {
                        actionTarget = null
                        deleteTarget = target
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Delete01) },
                    headlineContent = {
                        Text(
                            stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }

    if (showTypeSheet) {
        ModalBottomSheet(onDismissRequest = { showTypeSheet = false }) {
            CardGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                title = { Text(stringResource(R.string.workspace_page_create)) },
            ) {
                item(
                    onClick = {
                        showTypeSheet = false
                        showAddDialog = true
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.FolderCode) },
                    supportingContent = { Text(stringResource(R.string.workspace_page_type_local_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_page_type_local)) },
                )
                item(
                    onClick = {
                        showTypeSheet = false
                        navController.navigate(Screen.WorkspaceSshEditor())
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
                    supportingContent = { Text(stringResource(R.string.workspace_page_type_ssh_desc)) },
                    headlineContent = { Text(stringResource(R.string.workspace_page_type_ssh)) },
                )
            }
        }
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                vm.create(name)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            onDismiss = { editTarget = null },
            onConfirm = { name ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    GcmodAiConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WorkspaceItem(
    workspace: WorkspaceEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(if (workspace.isSsh()) HugeIcons.CloudServer else HugeIcons.FolderCode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 末尾加提示：不写出来没人会去试长按
                    text = workspace.statusLabel() + " · 长按管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.common_rename),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onRename,
                    ),
                    ItemAction(
                        text = stringResource(R.string.common_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_page_name)) },
                singleLine = true,
                isError = isDuplicate,
                supportingText = if (isDuplicate) {
                    { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = name.isNotBlank() && !isDuplicate,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
