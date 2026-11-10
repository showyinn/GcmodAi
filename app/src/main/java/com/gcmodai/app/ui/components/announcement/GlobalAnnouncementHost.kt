package com.gcmodai.app.ui.components.announcement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcmodai.app.data.remote.RemoteConfig
import com.gcmodai.app.ui.hooks.readStringPreference
import com.gcmodai.app.ui.hooks.writeStringPreference
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Notification01

/**
 * 全局公告宿主。
 *
 * 挂在导航容器**外层**，所以：
 * - 进 app 落在哪个页面都能弹出（不再依赖用户翻到账户页）；
 * - 点「知道了」立刻消失，不会残留在某个页面的组合树里继续遮挡。
 *
 * 去重策略见 [AnnouncementGate]：按公告正文签名记「读过」，
 * 管理员改了正文就重新弹一次，没改就永远不再打扰。
 */
@Composable
fun GlobalAnnouncementHost(config: RemoteConfig) {
    val context = LocalContext.current

    // 每次进入 app 读一次已读签名（配置刷新后也重读，避免内存里过期）
    var lastRead by remember(config) {
        mutableStateOf(context.readStringPreference(PREF_KEY_LAST_READ))
    }
    // 用户点了「知道了」立刻收起，不等配置重新拉取
    var dismissedThisSession by remember(config) { mutableStateOf(false) }

    val shouldShow = !dismissedThisSession && AnnouncementGate.shouldShow(config, lastRead)

    // 配置一到位就滚到顶部，避免长公告从上一次的位置继续
    val scrollState = rememberScrollState()
    LaunchedEffect(shouldShow) {
        if (shouldShow) scrollState.scrollTo(0)
    }

    if (!shouldShow) return

    AlertDialog(
        onDismissRequest = {
            dismissedThisSession = true
            // 点外面关掉也算读过，否则下次进来又弹，用户会觉得「关不掉」
            val sig = AnnouncementGate.signature(config)
            context.writeStringPreference(PREF_KEY_LAST_READ, sig)
            lastRead = sig
        },
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = HugeIcons.Notification01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        },
        title = {
            Text(
                text = if (config.appName.isNotBlank()) config.appName else "公告",
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
            )
        },
        text = {
            // 公告可能很长，给个上限并允许滚动，避免把整屏吃满
            Column(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = config.announcement,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(end = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dismissedThisSession = true
                    val sig = AnnouncementGate.signature(config)
                    context.writeStringPreference(PREF_KEY_LAST_READ, sig)
                    lastRead = sig
                }
            ) {
                Text("知道了", fontWeight = FontWeight.Medium)
            }
        },
    )
}

/** 已读公告签名。单独放一个文件级常量，便于测试与其他地方引用。 */
const val PREF_KEY_LAST_READ = "announcement_last_read_signature"
