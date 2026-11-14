package com.gcmodai.app.ui.pages.tutorial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Search01
import com.gcmodai.app.R
import com.gcmodai.app.data.tutorial.Tutorial
import com.gcmodai.app.data.tutorial.TutorialChapter
import com.gcmodai.app.data.tutorial.TutorialEntry
import com.gcmodai.app.data.tutorial.TutorialRepository
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.components.ui.CardGroupIcon
import com.gcmodai.app.ui.theme.CustomColors

/** 教程页的状态：内容 + 搜索词，纯展示。 */
data class TutorialUiState(
    val loading: Boolean = true,
    val tutorial: Tutorial? = null,
    val query: String = "",
) {
    /** 按搜索词过滤后的章节；搜索词为空时原样返回 */
    val visibleChapters: List<TutorialChapter>
        get() {
            val source = tutorial ?: return emptyList()
            val q = query.trim()
            if (q.isEmpty()) return source.chapters
            return source.chapters.mapNotNull { chapter ->
                val hitChapter = chapter.title.contains(q, true) || chapter.summary.contains(q, true)
                val entries = chapter.entries.filter { entry ->
                    entry.title.contains(q, true) ||
                        entry.body.contains(q, true) ||
                        entry.where.contains(q, true) ||
                        entry.tip.contains(q, true)
                }
                when {
                    // 章节标题命中：整章都显示，用户是想看这一整块
                    hitChapter -> chapter
                    entries.isNotEmpty() -> chapter.copy(entries = entries)
                    else -> null
                }
            }
        }

    val isEmptyResult: Boolean
        get() = !loading && query.isNotBlank() && visibleChapters.isEmpty()
}

/** 只负责读教程内容，不掺任何业务。 */
class TutorialVM(
    private val repository: TutorialRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TutorialUiState())
    val state: StateFlow<TutorialUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val tutorial = repository.load()
            _state.value = _state.value.copy(loading = false, tutorial = tutorial)
        }
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }
}

/**
 * 新手教程页。
 *
 * 内容来自热更新可替换的 tutorial.json，所以「教了什么」和「App 有什么功能」能一起迭代，
 * 不必为了改一句说明发一次 APK。
 */
@Composable
fun TutorialPage(vm: TutorialVM) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }

    // 首次进入时把第一章默认展开，否则整页只有标题、看起来像空的
    LaunchedEffect(state.tutorial?.version) {
        val first = state.tutorial?.chapters?.firstOrNull()?.id
        if (first != null && expanded.isEmpty()) expanded = setOf(first)
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.tutorial?.title ?: stringResource(R.string.tutorial_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.tutorial_search_hint)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                )
            }

            state.tutorial?.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                item {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (state.isEmptyResult) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text(stringResource(R.string.tutorial_no_result)) },
                            supportingContent = { Text(stringResource(R.string.tutorial_no_result_hint)) },
                            leadingContent = { CardGroupIcon(HugeIcons.Search01) },
                        )
                    }
                }
            }

            items(state.visibleChapters, key = { it.id }) { chapter ->
                val open = chapter.id in expanded || state.query.isNotBlank()
                ChapterCard(
                    chapter = chapter,
                    open = open,
                    onToggle = {
                        expanded = if (chapter.id in expanded) {
                            expanded - chapter.id
                        } else {
                            expanded + chapter.id
                        }
                    },
                )
            }

            item {
                TextButton(onClick = vm::reload, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tutorial_reload))
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: TutorialChapter,
    open: Boolean,
    onToggle: () -> Unit,
) {
    CardGroup {
        item(
            onClick = onToggle,
            headlineContent = { Text(chapter.title, fontWeight = FontWeight.SemiBold) },
            supportingContent = chapter.summary.takeIf { it.isNotBlank() }?.let { summary ->
                { Text(summary) }
            },
            leadingContent = { CardGroupIcon(HugeIcons.Book02) },
            trailingContent = { Chevron(open) },
        )
    }
    AnimatedVisibility(visible = open) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chapter.entries.forEach { entry -> EntryCard(entry) }
        }
    }
}

@Composable
private fun EntryCard(entry: TutorialEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (entry.badge.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = entry.badge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (entry.where.isNotBlank()) {
                Text(
                    text = entry.where,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = entry.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.tip.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = entry.tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 折叠箭头：展开时翻转 180°。 */
@Composable
private fun Chevron(open: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        label = "chapterArrow",
    )
    Icon(
        imageVector = HugeIcons.ArrowDown01,
        contentDescription = null,
        modifier = Modifier
            .size(18.dp)
            .rotate(rotation),
    )
}
