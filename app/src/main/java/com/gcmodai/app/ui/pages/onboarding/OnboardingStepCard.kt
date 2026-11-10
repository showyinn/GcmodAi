package com.gcmodai.app.ui.pages.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupScope
import com.gcmodai.app.ui.components.ui.CardGroupValue

/**
 * 新手引导里的一步。
 *
 * 一屏只有**一个** [CardGroup]：步骤标题走 `CardGroup` 的 title 槽，
 * 内容直接就是该分组的 `item` / `formItem`，因此不会出现「卡中卡」的双层容器。
 *
 * 这也正是 AGENTS.md 要求的写法——分段组，而不是卡片里再包卡片。
 * 调用方在 [content] 里直接写 `item(...)`；非条目的任意内容（说明条、按钮、
 * 分组选择块）用 `formItem { ... }` 承载。
 *
 * 注意 [content] **不加** `@Composable`：`CardGroup` 的 content 只是收集条目、
 * 在组合之外调用（CardGroup.kt:327-330），所以它本身不是可组合函数，
 * 可组合内容要写在 `item` 的各个 content 槽或 `formItem` 的 content 里。
 *
 * @param index 第几步，从 1 开始
 * @param total 总步数
 * @param title 这一步的名字，拼进「第 N/M 步：xxx」
 * @param done 已完成时标题行右侧显示「已完成」徽标
 * @param enabled 未轮到这一步时整块弱化；条目本身的可点性由调用方按需控制
 */
@Composable
fun OnboardingStepCard(
    index: Int,
    total: Int,
    title: String,
    done: Boolean,
    enabled: Boolean,
    content: CardGroupScope.() -> Unit,
) {
    // 弱化用 alpha 而不是换色：tonal 容器在浅色/深色主题下的对比度都由主题保证，
    // 自己指定颜色容易在深色模式下失真
    val contentAlpha = if (enabled) 1f else 0.38f

    CardGroup(
        modifier = Modifier.graphicsLayer { this.alpha = contentAlpha },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "第 $index/$total 步：$title",
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    CardGroupValue(text = "已完成")
                }
            }
        },
        content = content,
    )
}

/** 「正在同步…」这类加载行，照 TopupPage 的写法 */
@Composable
fun OnboardingLoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 说明条 / 错误条，样式照 TokenPage.kt:150-163 与 :285-300 */
@Composable
fun OnboardingNotice(
    text: String,
    error: Boolean = false,
) {
    Surface(
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** 空态：图标 + 标题 + 说明，样式照 TokenPage.kt:165-194（不含主按钮，按钮由调用方给） */
@Composable
fun OnboardingEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
