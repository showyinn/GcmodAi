package com.gcmodai.app.ui.pages.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.components.ui.CardGroupValue
import com.gcmodai.app.ui.context.LocalNavController
import com.gcmodai.app.Screen
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.ServerStack01

/**
 * 工作区与建站：把两条入口合成一个页面。
 *
 * ## 为什么要合并
 *
 * 「我的工作区」和「SSH 远程服务器」在设置页原本是两行并排，
 * 但它们是同一件事的两种存储位置——AI 到底把代码写到哪儿。
 * 分成两条让人误以为是两个独立功能，还要先判断该点哪个。
 *
 * 现在设置页只有一个入口，进来后再按「用我们的空间」还是
 * 「用你自己的服务器」二选一，选择成本降到最低。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceHubPage() {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("工作区与建站") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup(title = { Text("选一个位置存放代码") }) {
                    item(
                        onClick = { navController.navigate(Screen.MyWorkspace) },
                        leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                        headlineContent = { Text("我的工作区") },
                        supportingContent = {
                            Text("每人 100MB 独立空间，开箱即用，AI 写的代码直接存这里并一键上线")
                        },
                        trailingContent = { CardGroupValue(text = "推荐") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Workspaces) },
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                        headlineContent = { Text("SSH 远程服务器") },
                        supportingContent = {
                            Text("连接你自己的服务器，AI 直接在你的机器上写文件并建站")
                        },
                    )
                    // Windows 用户走这条：双击 exe 即可，不用碰 SSH
                    item(
                        onClick = { navController.navigate(Screen.PcAgents) },
                        leadingContent = { CardGroupIcon(HugeIcons.Computer) },
                        headlineContent = { Text("Windows 电脑") },
                        supportingContent = {
                            Text("在电脑上双击助手 exe，把地址和口令填进来就能控制它")
                        },
                        trailingContent = { CardGroupValue(text = "最简单") },
                    )
                }
            }

            item {
                CardGroup(title = { Text("两者区别") }) {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                        headlineContent = { Text("我的工作区") },
                        supportingContent = {
                            Text("不用配置，登录就能用；容量 100MB；闲置 24 小时自动清空")
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                        headlineContent = { Text("SSH 远程服务器") },
                        supportingContent = {
                            Text("需要填主机与账号；容量看你自己的磁盘；适合长期运行")
                        },
                    )
                }
            }

            item {
                Text(
                    text = "AI 会在你选的位置上写文件，并给出可访问的网址。两个位置可以同时存在，互不影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
