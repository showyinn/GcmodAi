package com.gcmodai.app.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import com.gcmodai.ai.provider.Model
import com.gcmodai.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BinaryCode
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Voice
import com.gcmodai.app.R
import com.gcmodai.app.Screen
import com.gcmodai.app.data.ai.mcp.McpManager
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.findProvider
import com.gcmodai.app.data.db.entity.WorkspaceEntity
import com.gcmodai.app.data.model.Assistant
import com.gcmodai.app.data.model.Conversation
import com.gcmodai.app.data.model.lastContextTokens
import com.gcmodai.app.data.repository.WorkspaceRepository
import com.gcmodai.app.ui.components.ui.ExtensionSelector
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.context.LocalSettings
import com.gcmodai.app.ui.hooks.ChatInputState
import com.gcmodai.app.utils.formatNumber
import com.gcmodai.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject

// 顺序决定切换方向：往后是进入下一级，往前是返回
private enum class FilesPickerPage {
    MAIN,
    MCP,
    EXTENSIONS,
    SEARCH,
    SEARCH_PROVIDER,
    TASK_MODE,
}

private val FilesPickerPage.parent: FilesPickerPage
    get() = when (this) {
        FilesPickerPage.SEARCH_PROVIDER -> FilesPickerPage.SEARCH
        else -> FilesPickerPage.MAIN
    }

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    // 会话视角下的助手和模型：会话开始后以会话上固定的配置为准
    assistant: Assistant,
    chatModel: Model?,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateWorkspaceCwd: (String?) -> Unit,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSketch: () -> Unit,
    onStartVoiceMode: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val showCwd = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    var showCwdSheet by remember { mutableStateOf(false) }

    var page by remember { mutableStateOf(FilesPickerPage.MAIN) }
    // 在子页面时，返回键回到上一级而不是关闭 sheet
    BackHandler(enabled = page != FilesPickerPage.MAIN) {
        page = page.parent
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState > initialState) {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "FilesPickerPage"
    ) { currentPage ->
        when (currentPage) {
            FilesPickerPage.MCP -> McpPickerPage(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
            )

            FilesPickerPage.EXTENSIONS -> ExtensionPickerPage(
                assistant = assistant,
                settings = settings,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
                onDismissAll = onDismiss,
            )

            FilesPickerPage.SEARCH -> SearchPickerPage(
                enableSearch = assistant.enableWebSearch,
                settings = settings,
                model = chatModel,
                onUpdateSearchMode = onUpdateSearchMode,
                onSelectProvider = { page = FilesPickerPage.SEARCH_PROVIDER },
                onBack = { page = FilesPickerPage.MAIN },
                onDismissAll = onDismiss,
            )

            FilesPickerPage.SEARCH_PROVIDER -> SearchProviderPicker(
                settings = settings,
                onUpdateSearchService = { index ->
                    onUpdateSearchService(index)
                    page = FilesPickerPage.SEARCH
                },
                onBack = { page = FilesPickerPage.SEARCH },
            )

            FilesPickerPage.TASK_MODE -> TaskModePickerPage(
                settings = settings,
                onUpdateSettings = onUpdateSettings,
                onBack = { page = FilesPickerPage.MAIN },
            )

            // 矮屏上放不下时可以滚动，避免底部的压缩对话等入口被挤出屏幕
            FilesPickerPage.MAIN -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AttachmentGrid(
                    actions = buildList {
                        add(AttachmentAction(HugeIcons.Camera01, R.string.take_picture, onTakePic))
                        add(AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage))
                        add(AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch))
                        // [video-audio-20261111] 视频与音频入口对所有渠道开放。
                        // 上游原来只在 Gemini 渠道下显示（Gemini 原生吃视频/音频），
                        // 导致 GcmodAi 官方渠道与 OpenAI 渠道的用户完全看不到这两个入口。
                        // 附件类型按 MIME 分别包成 Video/Audio 部件，由网关决定是否接受。
                        add(AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo))
                        add(AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio))
                        add(AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFile))
                        // [so-entry-20261111] SO/二进制分析的快捷入口：
                        // 附件面板是用户最常打开的地方，把分析工具放这里比藏进「我的账号」里更好找。
                        add(
                            AttachmentAction(HugeIcons.BinaryCode, R.string.binary_tool_entry) {
                                onDismiss()
                                navController.navigate(Screen.BinaryTool)
                            }
                        )
                        // [harden-entry-20261111] APK 加固快捷入口：聊天里点附件就能进
                        add(
                            AttachmentAction(HugeIcons.Shield01, R.string.harden_entry) {
                                onDismiss()
                                navController.navigate(Screen.Harden)
                            }
                        )
                        onStartVoiceMode?.let { start ->
                            add(AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start))
                        }
                    }
                )

                // 任务模式快捷条：这是它的主入口。
                // 用户发现「模型停了一半」的时刻就在这个面板里，能当场开关比退回设置页翻菜单有用得多。
                // 设置-模型页那份保留为同步读写的镜像，两边改的是同一个字段。
                TaskModeQuickBar(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    onOpenDetail = { page = FilesPickerPage.TASK_MODE },
                )

                // 分段列表：各项按显示条件依次占位，首尾项才有大圆角
                val showWorkspace = workspaces.isNotEmpty()
                val showMcp = settings.mcpServers.isNotEmpty()
                // 限制了上下文消息数就是滚动上下文，旧消息会自动移出，不需要压缩
                val showCompress = assistant.contextMessageLimit <= 0
                val itemCount = 2 + listOf(showWorkspace, showWorkspace && showCwd, showMcp, showCompress).count { it }
                var itemIndex = 0
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    SearchPickerListItem(
                        enableSearch = assistant.enableWebSearch,
                        settings = settings,
                        model = chatModel,
                        shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                        onClick = { page = FilesPickerPage.SEARCH },
                    )

                    if (showWorkspace) {
                        WorkspacePickerListItem(
                            assistant = assistant,
                            workspaces = workspaces,
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            onUpdateAssistant = onUpdateAssistant,
                            onNavigateToDetail = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceDetail(id))
                            },
                            onNavigateToTerminal = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceTerminal(id))
                            },
                            onNavigateToManage = {
                                onDismiss()
                                navController.navigate(Screen.Workspaces)
                            },
                        )

                        // Workspace CWD
                        if (showCwd) {
                            SegmentedListItem(
                                onClick = { showCwdSheet = true },
                                shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                                leadingContent = {
                                    Icon(
                                        imageVector = HugeIcons.Folder01,
                                        contentDescription = stringResource(R.string.workspace_cwd_select_directory),
                                    )
                                },
                                trailingContent = {
                                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                                },
                            ) {
                                Text(
                                    text = conversation.workspaceCwd ?: boundWorkspace?.workspaceDir().orEmpty(),
                                    // 路径的末尾更有辨识度，放不下时省略开头
                                    maxLines = 1,
                                    overflow = TextOverflow.StartEllipsis,
                                )
                            }
                        }
                    }

                    if (showMcp) {
                        McpPickerListItem(
                            assistant = assistant,
                            servers = settings.mcpServers,
                            mcpManager = mcpManager,
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            onClick = { page = FilesPickerPage.MCP },
                        )
                    }

                    // Extensions (Quick Messages + Prompt Injections + Skills)
                    val activeCount =
                        assistant.quickMessageIds.size +
                            assistant.modeInjectionIds.size +
                            assistant.lorebookIds.size +
                            assistant.enabledSkills.size
                    SegmentedListItem(
                        onClick = { page = FilesPickerPage.EXTENSIONS },
                        shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Package,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            if (activeCount > 0) {
                                CountBadge(count = activeCount)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.assistant_page_tab_extensions))
                    }

                    // Compress History Button
                    if (showCompress) {
                        SegmentedListItem(
                            onClick = { onShowCompressDialogChange(true) },
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Package01,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                val contextTokens = remember(conversation.messageNodes) {
                                    conversation.lastContextTokens()
                                }
                                val contextLength = chatModel?.contextLength?.takeIf { it > 0 }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (conversation.messageNodes.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (contextTokens != null && contextLength != null) {
                                            Text(
                                                text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (contextTokens != null && contextLength != null) {
                                        ContextUsageRing(fraction = contextTokens.toFloat() / contextLength)
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_compress_context))
                        }
                    }
                }
            }
        }
    }

    if (showCwdSheet && boundWorkspace != null) {
        WorkspaceCwdPickerSheet(
            workspaceId = boundWorkspace.id,
            workspaceDir = boundWorkspace.workspaceDir(),
            currentCwd = conversation.workspaceCwd,
            onSelectCwd = { newCwd ->
                onUpdateWorkspaceCwd(newCwd)
            },
            onDismiss = { showCwdSheet = false },
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = onCompressContext)
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    shapes: ListItemShapes,
    onUpdateAssistant: (Assistant) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    SegmentedListItem(
        onClick = { showSheet = true },
        shapes = shapes,
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = null,
            )
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.supportsTerminal()) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.assistant_page_workspace))
    }

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    // 会话的工作目录随工作区一并重置
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

// 扩展选择页，作为子页面嵌在加号 sheet 里
@Composable
private fun ExtensionPickerPage(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.assistant_page_tab_extensions),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        ExtensionSelector(
            assistant = assistant,
            settings = settings,
            onUpdate = onUpdateAssistant,
            modifier = Modifier.weight(1f),
            onNavigateToQuickMessages = {
                onDismissAll()
                navController.navigate(Screen.QuickMessages)
            },
            onNavigateToPrompts = {
                onDismissAll()
                navController.navigate(Screen.Prompts)
            },
            onNavigateToSkills = {
                onDismissAll()
                navController.navigate(Screen.Skills)
            })

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
)

/**
 * 附件入口网格。
 *
 * 关键点：**所有格子尺寸必须完全一致**。
 * 旧实现按 `(size+1)/2` 均分成两行，9 个入口会变成"上行 5 个 + 下行 4 个"，
 * 每行都用 weight(1f) 平分宽度 —— 于是上行更窄、下行更宽，视觉上参差不齐。
 *
 * 现在固定列数（4 列），格子宽高由容器宽度算出来，最后一行不足列数时用等宽透明占位补齐，
 * 这样 4 个入口是 4 个同尺寸格子、8 个入口正好两行，永远不会一大一小，也不会留下空洞。
 */
@Composable
private fun AttachmentGrid(
    actions: List<AttachmentAction>,
    modifier: Modifier = Modifier,
) {
    // 4 列：8 个入口正好两行、末行不留空洞。
    // 之前是 5 列，8 项排成 5+3，右下角一片空白，而且每格只有 48dp 宽，
    // 中文标签（"上传文件""APK 加固"）被 autoSize 压到 10sp 才放得下。
    val columns = 4
    val spacing = 10.dp
    val tileHeight = 96.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 用容器宽度精确算格子宽：宽度一致 → 高度再固定 → 所有格子完全同尺寸
        val tileWidth = ((maxWidth - spacing * (columns - 1)) / columns).coerceAtLeast(48.dp)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            actions.chunked(columns).fastForEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.fastForEach { action ->
                        AttachmentTile(
                            action = action,
                            modifier = Modifier
                                .width(tileWidth)
                                .height(tileHeight),
                        )
                    }
                    // 补齐末行空位，保证每格宽度与上一行完全对齐
                    repeat(columns - row.size) {
                        Spacer(Modifier.width(tileWidth).height(tileHeight))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    // 统一成 tonal Surface：比 FilledTonalButton 更容易控制等宽等高，
    // 也不用担心按钮的最小尺寸/内边距把格子撑变形。
    Surface(
        onClick = action.onClick,
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(action.label),
                // 四列时每格有 ~78dp，中文标签正常字号放得下；仍留给 autoSize 兜底窄屏
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 13.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// 列表项尾部的已启用数量
@Composable
internal fun CountBadge(count: Int) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(count.toString())
    }
}

// 上下文使用量进度环，接近上限时变色提醒压缩
@Composable
private fun ContextUsageRing(fraction: Float) {
    val color = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    CircularProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.size(24.dp),
        color = color,
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Preview(showBackground = true)
@Composable
private fun AttachmentGridPreview() {
    AttachmentGrid(
        actions = listOf(
            AttachmentAction(HugeIcons.Camera01, R.string.take_picture) {},
            AttachmentAction(HugeIcons.Image02, R.string.photo) {},
            AttachmentAction(HugeIcons.Video01, R.string.video) {},
            AttachmentAction(HugeIcons.MusicNote03, R.string.audio) {},
            AttachmentAction(HugeIcons.Files02, R.string.upload_file) {},
        ),
        modifier = Modifier.padding(16.dp),
    )
}
