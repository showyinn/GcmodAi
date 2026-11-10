package com.gcmodai.app.ui.pages.extensions.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import com.gcmodai.app.R
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.workspace.ssh.SshAuthType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.ByteArrayOutputStream
import java.io.InputStream
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.Wifi01
import me.rerere.hugeicons.stroke.Bluetooth
import com.gcmodai.app.ui.components.ui.CardGroupValue

private const val MAX_PRIVATE_KEY_BYTES = 64 * 1024

@Composable
fun WorkspaceSshEditorPage(id: String?) {
    val navController = LocalNavController.current
    val vm: WorkspaceSshEditorVM = koinViewModel(key = "ssh-editor-$id", parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 测试连接和保存针对开始时的表单快照, 期间锁定表单
    val editable = !state.busy

    LaunchedEffect(state.saved) {
        if (state.saved) navController.popBackStack()
    }

    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readLimited(MAX_PRIVATE_KEY_BYTES) }
        }.getOrNull()
        if (text != null) vm.update { it.copy(privateKey = text.trim()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) stringResource(R.string.workspace_ssh_editor_title_new) else stringResource(R.string.workspace_ssh_editor_title_edit)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::save, enabled = !state.busy) {
                        Icon(HugeIcons.Tick02, contentDescription = stringResource(R.string.workspace_ssh_editor_save))
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 小白说明放在最前面：先看懂再填，比填错再报错省事
            item {
                SshSetupGuide(
                    onFillExample = {
                        // 一键填入示例值：让用户先看到"正确格式长什么样"，
                        // 再照着改成自己的真实信息
                        vm.update { draft ->
                            draft.copy(
                                host = "192.168.1.10",
                                port = "22",
                                username = "ubuntu",
                                remoteDir = "/home/ubuntu/projects",
                            )
                        }
                    },
                )
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.workspace_ssh_editor_basic)) }) {
                    formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_name)) }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.name,
                            onValueChange = { value -> vm.update { it.copy(name = value) } },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.workspace_ssh_editor_name_placeholder)) },
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.workspace_ssh_editor_connection)) }) {
                    formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_host)) }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.host,
                            onValueChange = { value -> vm.update { it.copy(host = value.trim()) } },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.workspace_ssh_editor_host_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_port)) }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.port,
                            onValueChange = { value ->
                                vm.update { it.copy(port = value.filter(Char::isDigit).take(5)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_username)) }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.username,
                            onValueChange = { value -> vm.update { it.copy(username = value.trim()) } },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.workspace_ssh_editor_auth)) }) {
                    formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_auth_method)) }) {
                        val options = listOf(
                            SshAuthType.PASSWORD to stringResource(R.string.workspace_ssh_editor_password),
                            SshAuthType.PRIVATE_KEY to stringResource(R.string.workspace_ssh_editor_private_key),
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (type, label) ->
                                SegmentedButton(
                                    enabled = editable,
                                    selected = state.authType == type,
                                    onClick = { vm.update { it.copy(authType = type) } },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                    when (state.authType) {
                        SshAuthType.PASSWORD -> formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_password)) }) {
                            SecretTextField(
                                enabled = editable,
                                value = state.password,
                                onValueChange = { value -> vm.update { it.copy(password = value) } },
                                placeholder = if (state.hasSavedPassword) stringResource(R.string.workspace_ssh_editor_saved_keep) else null,
                            )
                        }

                        SshAuthType.PRIVATE_KEY -> {
                            formItem(
                                headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_private_key)) },
                                supportingContent = { Text(stringResource(R.string.workspace_ssh_editor_private_key_desc)) },
                            ) {
                                OutlinedTextField(
                                    enabled = editable,
                                    value = state.privateKey,
                                    onValueChange = { value -> vm.update { it.copy(privateKey = value) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            if (state.hasSavedPrivateKey) {
                                                stringResource(R.string.workspace_ssh_editor_saved_keep)
                                            } else {
                                                "-----BEGIN OPENSSH PRIVATE KEY-----"
                                            }
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    minLines = 3,
                                    maxLines = 8,
                                )
                                OutlinedButton(
                                    enabled = editable,
                                    onClick = { privateKeyPicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.workspace_ssh_editor_import_key))
                                }
                            }
                            formItem(headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_passphrase)) }) {
                                SecretTextField(
                                    enabled = editable,
                                    value = state.passphrase,
                                    onValueChange = { value -> vm.update { it.copy(passphrase = value) } },
                                    placeholder = stringResource(R.string.workspace_ssh_editor_passphrase_placeholder),
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.workspace_ssh_editor_working_dir)) }) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_remote_dir)) },
                        supportingContent = { Text(stringResource(R.string.workspace_ssh_editor_remote_dir_desc)) },
                    ) {
                        OutlinedTextField(
                            enabled = editable,
                            value = state.remoteDir,
                            onValueChange = { value -> vm.update { it.copy(remoteDir = value.trim()) } },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("/home/user/project") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.workspace_ssh_editor_security)) }) {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.FingerPrint) },
                        headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_host_key)) },
                        supportingContent = {
                            val hostKey = state.hostKey
                            if (hostKey == null) {
                                Text(stringResource(R.string.workspace_ssh_editor_host_key_unconfirmed))
                            } else {
                                Text(
                                    text = "${hostKey.type}\n${hostKey.fingerprint}",
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                    )
                }
            }

            item {
                ProbeSection(
                    state = state,
                    onTest = vm::testConnection,
                )
            }
        }
    }

    state.pendingHostKey?.let { pending ->
        HostKeyDialog(
            pending = pending,
            address = "${pending.draft.host}:${pending.draft.port}",
            onTrust = vm::trustHostKey,
            onDismiss = vm::dismissHostKey,
        )
    }

    state.error?.let { error ->
        val message = when (error) {
            is SshEditorError.Resource -> stringResource(error.id)
            is SshEditorError.Message -> error.text
        }
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(
                    if (state.canSaveAnyway) {
                        stringResource(R.string.workspace_ssh_editor_connect_failed)
                    } else {
                        stringResource(R.string.workspace_ssh_editor_error)
                    }
                ) },
            text = {
                Text(
                    if (state.canSaveAnyway) stringResource(R.string.workspace_ssh_editor_save_anyway_message, message) else message
                )
            },
            confirmButton = {
                if (state.canSaveAnyway) {
                    TextButton(onClick = vm::saveAnyway) { Text(stringResource(R.string.workspace_ssh_editor_save_anyway)) }
                } else {
                    TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.confirm)) }
                }
            },
            dismissButton = if (state.canSaveAnyway) {
                { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.cancel)) } }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    enabled: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )
}

@Composable
private fun ProbeSection(
    state: WorkspaceSshEditorState,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = onTest,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.testing) {
                LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
            } else {
                Icon(HugeIcons.Connect, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                if (state.testing) {
                    stringResource(R.string.workspace_ssh_editor_connecting)
                } else {
                    stringResource(R.string.workspace_ssh_editor_test)
                }
            )
        }
        state.probeResult?.let { result ->
            CardGroup(title = { Text(stringResource(R.string.workspace_ssh_editor_connected)) }) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.CloudServer) },
                    headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_shell)) },
                    supportingContent = { Text(result.shell) },
                )
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                    headlineContent = { Text(stringResource(R.string.workspace_ssh_editor_working_dir)) },
                    supportingContent = {
                        Text(
                            when {
                                state.remoteDir.isBlank() || state.remoteDir == result.homeDir ->
                                    stringResource(R.string.workspace_ssh_editor_home_dir, result.homeDir)
                                result.remoteDirExists -> state.remoteDir
                                else -> stringResource(R.string.workspace_ssh_editor_dir_missing, state.remoteDir)
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun HostKeyDialog(
    pending: PendingHostKey,
    address: String,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = pending.expected != null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HugeIcons.Key01, contentDescription = null) },
        title = { Text(
                if (changed) {
                    stringResource(R.string.workspace_ssh_editor_host_key_changed)
                } else {
                    stringResource(R.string.workspace_ssh_editor_host_key_confirm)
                }
            ) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (changed) {
                        stringResource(R.string.workspace_ssh_editor_host_key_changed_message, address)
                    } else {
                        val keyFile = pending.actual.type.removePrefix("ssh-").substringBefore('-')
                        stringResource(
                            R.string.workspace_ssh_editor_host_key_first_message,
                            address,
                            "ssh-keygen -lf /etc/ssh/ssh_host_${keyFile}_key.pub",
                        )
                    }
                )
                Text(
                    text = "${pending.actual.type}\n${pending.actual.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            if (changed) {
                Button(
                    onClick = onTrust,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.workspace_ssh_editor_trust_new_key)) }
            } else {
                TextButton(onClick = onTrust) { Text(stringResource(R.string.workspace_ssh_editor_trust_continue)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// 私钥文件很小, 超过上限的多半是选错了文件, 不整个读进内存
private fun InputStream.readLimited(limit: Int): String? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.size() + read > limit) return null
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

/**
 * 第一次配置 SSH 的详细说明。
 *
 * 写这么细是有原因的：SSH 的四个字段（主机/端口/用户名/目录）对没接触过
 * 服务器的人完全是黑话，随便填一个就报"连接被拒"，用户根本不知道错在哪。
 * 这里把每项从哪来、常见值、出错怎么排查全部写清楚。
 */
@Composable
private fun SshSetupGuide(onFillExample: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SSH 配置完整教程",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "五个空怎么填、去哪找、报什么错怎么修，全在这。看完能独立连上任何一台服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起教程" else "展开完整教程")
                }
                TextButton(onClick = onFillExample) {
                    Text("一键填入示例")
                }
            }

            if (!expanded) return@Column

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                GuideSection(
                    title = "〇、五个空分别是什么（先看懂再填）",
                    lines = listOf(
                        "名称：随便起，只给你自己看。示例：家里的电脑 / 阿里云服务器",
                        "主机：那台机器的地址，填 IP 或域名。示例：192.168.1.10",
                        "端口：SSH 服务端口，默认 22。改过的填实际值。示例：22",
                        "用户名：登录那台机器的账号。示例：ubuntu / root / admin",
                        "工作目录：AI 读写文件的根目录，必须绝对路径。示例：/home/ubuntu/projects",
                        "认证方式：有密码选密码；只有 .pem 文件选私钥。",
                    ),
                )

                GuideSection(
                    title = "一、怎么查这台机器的 IP",
                    lines = listOf(
                        "Windows：按 Win+R 输入 cmd 回车，敲 ipconfig，看「IPv4 地址」，形如 192.168.1.10",
                        "macOS：打开终端，敲 ifconfig | grep inet，找 192.168.x.x 那行",
                        "Linux：终端敲 ip addr 或 hostname -I",
                        "云服务器：登录服务商控制台，在实例列表里找「公网 IP」",
                        "注意：127.0.0.1 和 localhost 指的是手机自己，不能填。",
                    ),
                )

                GuideSection(
                    title = "二、端口填什么",
                    lines = listOf(
                        "99% 的情况就是 22，不用改。",
                        "确认命令（在服务器上跑）：sudo ss -lntp | grep ssh",
                        "输出里冒号后面的数字就是端口，例如 0.0.0.0:22 表示 22。",
                        "如果运维改过端口（比如 2222），这里就填 2222。",
                    ),
                )

                GuideSection(
                    title = "三、用户名怎么确定",
                    lines = listOf(
                        "在服务器上敲 whoami，输出就是用户名。",
                        "阿里云/腾讯云 Ubuntu 镜像默认是 ubuntu；CentOS 默认是 root。",
                        "自己装的 Windows：填开机登录的账户名，cmd 里敲 whoami 可看。",
                        "不确定就先试 root。",
                    ),
                )

                GuideSection(
                    title = "四、密码还是私钥",
                    lines = listOf(
                        "有密码 → 选「密码」，直接填服务器登录密码（不是网站后台密码）。",
                        "买服务器时下载过 .pem 文件 → 选「私钥」，用记事本打开，把全部内容复制进来。",
                        "私钥内容长这样：-----BEGIN OPENSSH PRIVATE KEY----- 开头，-----END ...----- 结尾，中间不能漏行。",
                        "如果私钥是 .ppk 格式（PuTTY 的），需要先转换：puttygen 里 Export 成 OpenSSH 格式。",
                        "私钥文件在 Linux/Mac 上要先改权限，否则会报「too open」：chmod 600 你的私钥.pem",
                    ),
                )

                GuideSection(
                    title = "五、工作目录填什么（必须 / 开头）",
                    lines = listOf(
                        "root 用户：/root",
                        "ubuntu 用户：/home/ubuntu",
                        "做网站：/var/www/html（nginx/apache 默认站点目录）",
                        "想放项目：/home/ubuntu/projects（不存在也没关系，AI 会自动建）",
                        "Windows 电脑：C:/Users/你的用户名（用正斜杠，别用反斜杠）",
                        "不确定就先填 / ，连上之后再改。",
                    ),
                )

                GuideSection(
                    title = "六、先在服务器上开启 SSH 服务",
                    lines = listOf(
                        "Ubuntu / Debian：sudo systemctl enable --now ssh",
                        "CentOS / RHEL：sudo systemctl enable --now sshd",
                        "Windows 10/11：设置 → 应用 → 可选功能 → 添加「OpenSSH 服务器」，再管理员 PowerShell 运行：Start-Service sshd; Set-Service sshd -StartupType Automatic",
                        "Windows 还要放行防火墙：New-NetFirewallRule -Name sshd -DisplayName OpenSSH -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22",
                        "云服务器：除了开服务，还要在控制台「安全组」放行 TCP 22，否则外面连不进来。",
                        "验证是否开好（在另一台电脑上）：ssh -p 22 用户名@IP，能提示输入密码就说明通了。",
                    ),
                )

                // ---------- 完整示范 ----------
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "完整示范：假设你有一台家用电脑",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Text(
                            text = "情况：Windows 电脑，IP 是 192.168.1.10，登录账户叫 ubuntu，" +
                                "想让它把项目放在 D 盘 projects 文件夹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "那么五个空这样填：\n" +
                                "  名称：    家里的电脑\n" +
                                "  主机：    192.168.1.10\n" +
                                "  端口：    22\n" +
                                "  用户名：  ubuntu\n" +
                                "  工作目录：D:/projects\n" +
                                "  认证方式：密码（填开机密码）\n\n" +
                                "填完点右上角对勾保存，然后在 AI 工作区里勾选这台电脑，" +
                                "对 AI 说「看看我的项目目录」就能验证。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = onFillExample) { Text("一键填入这套示例值") }
                    }
                }

                GuideSection(
                    title = "七、报错对照表（照这个查最快）",
                    lines = listOf(
                        "Connection refused：SSH 服务没开，或端口不是 22。",
                        "Connection timed out：IP 写错、不在同一网络，或安全组/防火墙没放行。",
                        "Permission denied：用户名错，或密码/私钥不对。",
                        "Host key verification failed / 指纹变化：服务器重装过，删掉这条重新添加。",
                        "No such file or directory：工作目录不存在，改成一个存在的路径。",
                        "Bad permissions / too open：私钥权限太宽，chmod 600 私钥文件。",
                        "Connection closed by remote host：对方封了这个 IP，或账号被限制。",
                        "一直卡在「测试中」：网络不通，先用手机浏览器访问 http://IP 看能不能通。",
                    ),
                )

                GuideSection(
                    title = "八、安全建议",
                    lines = listOf(
                        "不要用 root 长期开放密码登录，建议新建普通用户再 sudo。",
                        "更安全的方式是用私钥登录，并在服务器上关闭密码登录。",
                        "公网服务器建议改默认端口 22，能挡掉大量扫描。",
                        "这些凭据在本机是用 Android Keystore 加密存的，不会明文落盘。",
                    ),
                )
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        lines.forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
