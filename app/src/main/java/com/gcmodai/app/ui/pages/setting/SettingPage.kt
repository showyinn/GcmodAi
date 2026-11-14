package com.gcmodai.app.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.BatteryCharging02
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import com.gcmodai.app.R
import com.gcmodai.app.Screen
import com.gcmodai.app.data.datastore.isNotConfigured
import com.gcmodai.app.data.files.FilesManager
import com.gcmodai.app.data.user.UserRepository
import com.gcmodai.app.data.user.formatQuotaUsd
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.switchItem
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.ConnectedChoiceButtons
import com.gcmodai.app.ui.components.ui.ConnectedChoiceLabel
import com.gcmodai.app.ui.components.ui.permission.LocalNetworkPermissionCard
import com.gcmodai.app.ui.components.ui.permission.rememberLocalNetworkPermissionState
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.ui.hooks.rememberColorMode
import com.gcmodai.app.ui.theme.ColorMode
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.BackgroundKeepAlive
import com.gcmodai.app.utils.openUrl
import com.gcmodai.app.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val userRepository: UserRepository = koinInject()
    val userSession by userRepository.sessionFlow.collectAsStateWithLifecycle()
    val localNetworkPermission = rememberLocalNetworkPermissionState()

    // 赞助提示与赞助页已移除（产品要求：删除赞助功能）

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(
                        onClick = { navController.navigate(Screen.SettingProvider) }
                    )
                }
            }

            if (localNetworkPermission?.allRequiredPermissionsGranted == false) {
                item("localNetworkPermission") {
                    LocalNetworkPermissionCard(permissionState = localNetworkPermission)
                }
            }

            item("accountSettings") {
                val account = userSession?.user
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text("账号") },
                ) {
                    item(
                        onClick = {
                            navController.navigate(
                                if (account == null) Screen.Login else Screen.Account
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.UserCircle,
                                containerColor = containerColor,
                                contentColor = contentColor,
                            )
                        },
                        headlineContent = {
                            Text(if (account == null) "登录账号" else account.displayLabel)
                        },
                        supportingContent = {
                            Text(
                                if (account == null) {
                                    "使用 gcmod.online 账号登录，注册后可查看额度"
                                } else {
                                    "@${account.username} · 余额 ${formatQuotaUsd(account.quotaUsd)}"
                                }
                            )
                        },
                        trailingContent = if (account != null) {
                            { CardGroupValue(text = account.groupName) }
                        } else null,
                    )
                }
            }

            item("generalSettings") {
                var colorMode by rememberColorMode()
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    formItem {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CardGroupIcon(colorMode.icon(), containerColor = containerColor, contentColor = contentColor)
                            Text(stringResource(R.string.setting_page_color_mode))
                        }
                        ConnectedChoiceButtons(
                            options = ColorMode.entries,
                            selected = colorMode,
                            onSelect = {
                                colorMode = it
                                navController.navigate(Screen.Setting) {
                                    popUpTo(Screen.Setting) {
                                        inclusive = true
                                    }
                                }
                            },
                        ) { mode, selected ->
                            ConnectedChoiceLabel(
                                text = when (mode) {
                                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                },
                                selected = selected,
                                icon = mode.icon(),
                            )
                        }
                    }
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferences) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { CardGroupIcon(HugeIcons.LookTop, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { CardGroupIcon(HugeIcons.Package, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                    // 合并入口：云工作区与 SSH 是「代码存哪儿」的两种选择，
                    // 并列成两行会让人以为是两个独立功能，所以收成一个页面。
                    item(
                        onClick = { navController.navigate(Screen.WorkspaceHub) },
                        leadingContent = { CardGroupIcon(HugeIcons.CloudUpload, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text("100MB 云工作区 与 SSH 远程服务器，AI 直接写代码并建站") },
                        headlineContent = { Text("工作区与建站") },
                    )
                }
            }

            item("modelServices") {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { CardGroupIcon(HugeIcons.AiMagic, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { CardGroupIcon(HugeIcons.Brain02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { CardGroupIcon(HugeIcons.GlobalSearch, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { CardGroupIcon(HugeIcons.Megaphone01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMedia) },
                        leadingContent = { CardGroupIcon(HugeIcons.Image02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_media_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_media)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { CardGroupIcon(HugeIcons.McpServer, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    )
                }
            }

            item("autoUpdate") {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text("自动更新") },
                ) {
                    switchItem(
                        checked = settings.autoCheckUpdates,
                        onCheckedChange = { checked -> vm.updateSettings { s -> s.copy(autoCheckUpdates = checked) } },
                        headlineContent = { Text("自动检查更新") },
                        supportingContent = { Text("启动后静默检查，每 6 小时最多一次") },
                    )
                    switchItem(
                        checked = settings.autoDownloadUpdates,
                        onCheckedChange = { checked -> vm.updateSettings { s -> s.copy(autoDownloadUpdates = checked) } },
                        headlineContent = { Text("自动下载新版本") },
                        supportingContent = { Text("后台下好并通知你点一下安装，不用自己去下载") },
                    )
                    switchItem(
                        checked = settings.autoUpdateWifiOnly,
                        onCheckedChange = { checked -> vm.updateSettings { s -> s.copy(autoUpdateWifiOnly = checked) } },
                        headlineContent = { Text("仅在 Wi-Fi 下下载") },
                        supportingContent = { Text("关掉后蜂窝网络也会下载，注意流量") },
                    )
                    switchItem(
                        checked = settings.autoHotUpdate,
                        onCheckedChange = { checked -> vm.updateSettings { s -> s.copy(autoHotUpdate = checked) } },
                        headlineContent = { Text("自动应用热更新") },
                        supportingContent = { Text("界面/配置类更新直接生效，不需要安装新 APK") },
                    )
                }
            }

            item("backgroundRunning") {
                val context = LocalContext.current
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                // 每次进入设置页重新读一次白名单状态：用户可能刚从系统页面回来
                val ignoringBattery = remember { mutableStateOf(BackgroundKeepAlive.isIgnoringBatteryOptimizations(context)) }
                LaunchedEffect(Unit) {
                    ignoringBattery.value = BackgroundKeepAlive.isIgnoringBatteryOptimizations(context)
                }
                val vendor = BackgroundKeepAlive.vendorAutoStartHint()
                CardGroup(
                    title = { Text("后台运行") },
                ) {
                    item(
                        onClick = { BackgroundKeepAlive.requestIgnoreBatteryOptimizations(context) },
                        leadingContent = { CardGroupIcon(HugeIcons.BatteryCharging02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = {
                            Text(
                                if (ignoringBattery.value) {
                                    "已加入白名单：锁屏后生成、工作区、内嵌网页都会继续跑"
                                } else {
                                    "未加入白名单：点这里把 GcmodAi 设为「不受电池优化限制」"
                                }
                            )
                        },
                        headlineContent = { Text("电池优化白名单") },
                    )
                    item(
                        onClick = { BackgroundKeepAlive.openAutoStartSettings(context) },
                        leadingContent = { CardGroupIcon(HugeIcons.Refresh, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = {
                            Text(
                                if (vendor != null) {
                                    "检测到 $vendor：把「自启动」「后台运行」都打开，锁屏后才不会被清掉"
                                } else {
                                    "打开系统的自启动 / 后台管理页面"
                                }
                            )
                        },
                        headlineContent = { Text("自启动与后台管理") },
                    )
                }
            }

            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_data_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { CardGroupIcon(HugeIcons.Database02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { CardGroupIcon(HugeIcons.ImageUpload, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.calculating))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                }
            }

            item("aboutSettings") {
                val context = LocalContext.current
                val shareText = stringResource(R.string.setting_page_share_text)
                val share = stringResource(R.string.setting_page_share)
                val noShareApp = stringResource(R.string.setting_page_no_share_app)
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_about)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAbout) },
                        leadingContent = { CardGroupIcon(HugeIcons.Clapping01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_about_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_about)) },
                    )
                    // 站内教程：内容跟着热更新走，不依赖网页是否可达
                    item(
                        onClick = { navController.navigate(Screen.Tutorial) },
                        leadingContent = { CardGroupIcon(HugeIcons.Book02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.tutorial_entry_desc)) },
                        headlineContent = { Text(stringResource(R.string.tutorial_entry)) },
                    )
                    item(
                        onClick = {
                            val docUrl = if (java.util.Locale.getDefault().language == "zh") {
                                "https://gcmod.online/"
                            } else {
                                "https://gcmod.online/"
                            }
                            context.openUrl(docUrl)
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Book01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_documentation_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_documentation)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        leadingContent = { CardGroupIcon(HugeIcons.Bookshelf01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_request_logs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                    )
                    item(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, shareText)
                            try {
                                context.startActivity(Intent.createChooser(intent, share))
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, noShareApp, Toast.LENGTH_SHORT).show()
                            }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Share04, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_share_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_share)) },
                    )
                }
            }
        }
    }
}

private fun ColorMode.icon(): ImageVector = when (this) {
    ColorMode.SYSTEM -> HugeIcons.SmartPhone01
    ColorMode.LIGHT -> HugeIcons.Sun01
    ColorMode.DARK -> HugeIcons.Moon02
}

@Composable
private fun ProviderConfigWarningCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.error, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Alert01,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_config_api_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_config_api_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
