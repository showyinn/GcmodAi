package com.gcmodai.app.ui.pages.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gcmodai.app.data.agent.AgentResult
import com.gcmodai.app.data.agent.PcAgent
import com.gcmodai.app.data.agent.PcAgentRepository
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.context.LocalToaster
import com.gcmodai.app.utils.openUrl
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import org.koin.compose.koinInject

/**
 * 助手下载地址。
 *
 * 主推 zip：里面带「启动助手.bat」，它会先去掉文件的"来自网络"标记再启动，
 * 从而绕过 Windows SmartScreen 的「已保护你的电脑」拦截。
 * exe 单独下载会被拦，只作为备用。
 */
private const val AGENT_ZIP_URL = "http://38.76.219.151/GcmodAi-Agent.zip"
private const val AGENT_EXE_URL = "http://38.76.219.151/GcmodAi-Agent.exe"

/**
 * Windows 电脑管理（通过助手 exe）。
 *
 * 比 SSH 简单：电脑上双击 exe，把窗口里的地址和口令填进来即可。
 * 页面上把这几步写清楚，不用去猜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcAgentPage() {
    val repo: PcAgentRepository = koinInject()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var agents by remember { mutableStateOf(repo.agents()) }
    var editing by remember { mutableStateOf<PcAgent?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<PcAgent?>(null) }

    fun refresh() {
        agents = repo.agents()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Windows 电脑") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(HugeIcons.Add01, contentDescription = "添加电脑")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 使用说明 ----------
            item {
                CardGroup(title = { Text("三步连上你的 Windows 电脑") }) {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Download01) },
                        headlineContent = { Text("① 下载并解压助手") },
                        supportingContent = {
                            Text(
                                "下载 GcmodAi-Agent.zip（7.7MB），解压到电脑任意文件夹。\n" +
                                    "里面有三个文件：exe、启动助手.bat、使用说明.txt"
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { context.openUrl(AGENT_ZIP_URL) }) {
                                Text("下载")
                            }
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Computer) },
                        headlineContent = { Text("② 双击「启动助手.bat」") },
                        supportingContent = {
                            Text(
                                "黑窗口里会显示「地址」和「口令」，别关它。\n" +
                                    "首次运行若提示允许访问网络，必须点「允许」，否则手机连不上。\n" +
                                    "用 bat 启动不会被 Windows 拦截（它已自动解除网络标记）。"
                            )
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Add01) },
                        headlineContent = { Text("③ 点右下角 + 添加") },
                        supportingContent = {
                            Text(
                                "把窗口里的地址（形如 http://192.168.5.96:8787）和口令填进来。\n" +
                                    "手机与电脑必须连同一个 Wi-Fi。"
                            )
                        },
                    )
                }
            }

            // ---------- 已添加 ----------
            if (agents.isEmpty()) {
                item {
                    CardGroup {
                        item(
                            leadingContent = { CardGroupIcon(HugeIcons.Computer) },
                            headlineContent = { Text("还没有添加电脑") },
                            supportingContent = { Text("按上面三步操作，然后点右下角 + 添加") },
                        )
                    }
                }
            } else {
                items(agents, key = { it.id }) { agent ->
                    CardGroup {
                        item(
                            leadingContent = { CardGroupIcon(HugeIcons.Computer) },
                            headlineContent = { Text(agent.name.ifBlank { "未命名电脑" }) },
                            supportingContent = { Text(agent.url) },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        testing = agent.id
                                        scope.launch {
                                            when (val r = repo.ping(agent)) {
                                                is AgentResult.Ok ->
                                                    toaster.show("连接成功，电脑名：${r.value}")

                                                is AgentResult.Err ->
                                                    toaster.show("连接失败：${r.message}")
                                            }
                                            testing = null
                                        }
                                    },
                                    enabled = testing != agent.id,
                                ) {
                                    Text(if (testing == agent.id) "测试中…" else "测试")
                                }
                            },
                        )
                        item(
                            onClick = { editing = agent },
                            leadingContent = { CardGroupIcon(HugeIcons.Computer) },
                            headlineContent = { Text("编辑地址和口令") },
                        )
                        item(
                            onClick = { deleteTarget = agent },
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Delete01,
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            },
                            headlineContent = {
                                Text("删除这台电脑", color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }

            // ---------- SmartScreen 说明 ----------
            item {
                CardGroup(title = { Text("提示「Windows 已保护你的电脑」？") }) {
                    item(
                        headlineContent = { Text("原因") },
                        supportingContent = {
                            Text(
                                "Windows SmartScreen 会拦截「从网上下载、没有数字签名」的程序。" +
                                    "这跟病毒无关，只是我们没有购买代码签名证书。"
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("方法一：用启动助手.bat（推荐）") },
                        supportingContent = {
                            Text("启动器会先去掉文件的「来自网络」标记再运行，不会弹任何提示。")
                        },
                    )
                    item(
                        headlineContent = { Text("方法二：手动放行一次") },
                        supportingContent = {
                            Text("在蓝色提示里点「更多信息」→ 点「仍要运行」。只需一次，之后不再弹。")
                        },
                    )
                    item(
                        headlineContent = { Text("方法三：右键解除锁定") },
                        supportingContent = {
                            Text("右键 exe → 属性 → 勾选最下方「解除锁定」→ 确定，然后再运行。")
                        },
                    )
                }
            }

            // ---------- 排查 ----------
            item {
                CardGroup(title = { Text("连不上怎么办") }) {
                    item(
                        headlineContent = { Text("提示「连不上」") },
                        supportingContent = {
                            Text("1) exe 窗口是不是关了？2) 电脑休眠了？3) 地址端口抄对了吗？")
                        },
                    )
                    item(
                        headlineContent = { Text("手机与电脑不在同一 Wi-Fi") },
                        supportingContent = {
                            Text("助手只在局域网可用；要外网用请改用「SSH 远程服务器」。")
                        },
                    )
                    item(
                        headlineContent = { Text("防火墙拦截") },
                        supportingContent = {
                            Text(
                                "管理员命令行执行一次即可放行：\n" +
                                    "netsh advfirewall firewall add rule name=\"GcmodAi Agent\" " +
                                    "dir=in action=allow protocol=TCP localport=8787"
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("提示「口令不正确」") },
                        supportingContent = {
                            Text("重新看一下 exe 窗口里的口令，重新复制，注意别带空格。")
                        },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { context.openUrl(AGENT_ZIP_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Download01, contentDescription = null)
                        Text("下载助手压缩包（推荐）", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { context.openUrl(AGENT_EXE_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Download01, contentDescription = null)
                        Text("只下载 exe（可能被系统拦截）", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    val formTarget = when {
        showAdd -> PcAgent()
        editing != null -> editing
        else -> null
    }
    formTarget?.let { initial ->
        AgentEditDialog(
            initial = initial,
            onDismiss = {
                showAdd = false
                editing = null
            },
            onConfirm = { saved ->
                repo.upsert(saved)
                refresh()
                showAdd = false
                editing = null
                toaster.show("已保存：${saved.name}")
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除电脑") },
            text = { Text("确定删除「${target.name.ifBlank { target.url }}」？") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(target.id)
                    refresh()
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AgentEditDialog(
    initial: PcAgent,
    onDismiss: () -> Unit,
    onConfirm: (PcAgent) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var token by remember { mutableStateOf(initial.token) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial.name.isBlank() && initial.url.isBlank()) "添加电脑" else "编辑电脑")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("我的电脑") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("地址") },
                    placeholder = { Text("http://192.168.5.96:8787") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("口令") },
                    placeholder = { Text("exe 窗口里显示的那串") },
                    singleLine = true,
                )
                Text(
                    "地址和口令都在电脑上那个黑窗口里，照抄即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        initial.copy(
                            name = name.trim().ifBlank { "Windows 电脑" },
                            url = url.trim(),
                            token = token.trim(),
                        )
                    )
                },
                enabled = url.isNotBlank() && token.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
