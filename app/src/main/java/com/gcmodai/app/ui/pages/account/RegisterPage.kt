package com.gcmodai.app.ui.pages.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Zap
import org.koin.compose.koinInject
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.openUrl

/**
 * 注册页。
 *
 * 只允许 QQ 邮箱：服务端 options 里 EmailDomainRestrictionEnabled=true、
 * EmailDomainWhitelist=qq.com，且 EmailVerificationEnabled=true 必须填邮箱验证码。
 * 验证码由服务端用库里配置的 SMTP（smtp.qq.com:465）发送。
 */
@Composable
fun RegisterPage() {
    val navController = LocalNavController.current
    val repository: UserRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var affCode by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    val busy by repository.busy.collectAsStateWithLifecycle()

    // 验证码倒计时
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000L)
            countdown -= 1
        }
    }

    val emailLooksValid = repository.isAllowedEmail(email)
    val canSendCode = emailLooksValid && countdown == 0 && !sending
    val canSubmit = username.isNotBlank() && password.isNotEmpty() &&
        password == confirmPassword && code.isNotBlank() && emailLooksValid && !busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册账号") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.Zap,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Text(text = "创建 GcmodAi 账号", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "注册后与官网 gcmod.online 通用，新用户赠送额度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            CardGroup(title = { Text("基本信息") }) {
                formItem(
                    headlineContent = { Text("用户名") },
                    leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; errorMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("3 个字符以上") },
                        shape = MaterialTheme.shapes.large,
                    )
                }

                formItem(
                    headlineContent = { Text("邮箱（仅支持 QQ 邮箱）") },
                    leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; errorMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("12345678@qq.com") },
                        isError = email.isNotBlank() && !emailLooksValid,
                        supportingText = if (email.isNotBlank() && !emailLooksValid) {
                            { Text("必须使用 QQ 邮箱（@qq.com）") }
                        } else null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        shape = MaterialTheme.shapes.large,
                    )
                }

                formItem(
                    headlineContent = { Text("邮箱验证码") },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                errorMessage = null
                                infoMessage = null
                                sending = true
                                scope.launch {
                                    repository.sendVerificationCode(email)
                                        .onSuccess {
                                            countdown = 60
                                            infoMessage = "验证码已发送到 $email，请查收（含垃圾箱）"
                                        }
                                        .onFailure { errorMessage = it.message ?: "验证码发送失败" }
                                    sending = false
                                }
                            },
                            enabled = canSendCode,
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(if (countdown > 0) "${countdown}s 后重发" else "发送验证码")
                            }
                        }
                    },
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; errorMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("邮件里的 6 位验证码") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }

            CardGroup(title = { Text("设置密码") }) {
                formItem(
                    headlineContent = { Text("密码") },
                    leadingContent = { CardGroupIcon(HugeIcons.Key01) },
                ) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("至少 8 位") },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.Eye,
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        shape = MaterialTheme.shapes.large,
                    )
                }

                formItem(headlineContent = { Text("确认密码") }) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("再输入一次密码") },
                        isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                        supportingText = if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                            { Text("两次输入的密码不一致") }
                        } else null,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        shape = MaterialTheme.shapes.large,
                    )
                }

                formItem(headlineContent = { Text("邀请码（选填）") }) {
                    OutlinedTextField(
                        value = affCode,
                        onValueChange = { affCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("有邀请码可以填，双方都有奖励") },
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }

            infoMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            errorMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            Button(
                onClick = {
                    errorMessage = null
                    infoMessage = null
                    scope.launch {
                        repository.register(
                            username = username,
                            password = password,
                            email = email,
                            verificationCode = code,
                            affCode = affCode,
                        ).onSuccess {
                            navController.popBackStack()
                        }.onFailure {
                            errorMessage = it.message ?: "注册失败，请重试"
                        }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("注册并登录", style = MaterialTheme.typography.titleMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { context.openUrl("https://gcmod.online/") }) {
                    Text("去官网 gcmod.online 查看详情")
                }
            }
        }
    }
}
