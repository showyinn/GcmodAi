package com.gcmodai.app.ui.pages.setting

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gcmodai.ui.toast.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.ListView
import me.rerere.hugeicons.stroke.Ruler
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.PaintBrush01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Tick01
import com.gcmodai.app.R
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.components.ui.CardGroup
import com.gcmodai.app.ui.design.ButtonSize
import com.gcmodai.app.ui.design.ListDensity
import com.gcmodai.app.ui.design.Md3Panel
import com.gcmodai.app.ui.components.ui.ConnectedChoiceLabel
import com.gcmodai.app.ui.components.ui.ConnectedMultiChoiceButtons
import com.gcmodai.app.ui.components.ui.ItemAction
import com.gcmodai.app.ui.components.ui.ItemActionMenu
import com.gcmodai.app.ui.components.ui.switchItem
import com.gcmodai.app.ui.context.LocalToaster
import com.gcmodai.app.ui.pages.setting.components.PresetThemeButtonGroup
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.ui.theme.CustomTheme
import com.gcmodai.app.ui.theme.ThemeVariants
import com.gcmodai.app.ui.theme.LocalDarkMode
import com.gcmodai.app.utils.plus
import com.gcmodai.ui.components.GcmodAiConfirmDialog
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private val themeJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val clipboardManager = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val exportSuccessMsg = stringResource(R.string.setting_theme_page_export_success)
    val importSuccessMsg = stringResource(R.string.setting_theme_page_import_success)

    var showEditSheet by remember { mutableStateOf(false) }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var deletingTheme by remember { mutableStateOf<CustomTheme?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_theme_setting)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.dynamicColor) {
                item("dynamicColorHint") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.setting_theme_page_dynamic_color_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!settings.dynamicColor) {
                item("presetThemes") {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_theme_page_preset_themes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp)
                        )
                        Md3Panel(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            PresetThemeButtonGroup(
                                themeId = settings.themeId,
                                modifier = Modifier.fillMaxWidth(),
                                onChangeTheme = {
                                    vm.updateSettings { latest -> latest.copy(themeId = it) }
                                }
                            )
                        }
                    }
                }

            // ---- DIY：圆角 / 按钮尺寸 / 列表密度 ----
            // 放在 dynamicColor 判断之外：这三个是"界面形状"的偏好，
            // 和「是否使用系统动态取色」无关，任何时候都该能调。
            item("uiDiy") {
                CardGroup(
                    title = { Text("界面 DIY") },
                ) {
                    formItem(
                        headlineContent = { Text("圆角") },
                        supportingContent = {
                            Text(
                                "当前 %.0f%%：卡片、弹窗、输入框、按钮一起缩放"
                                    .format(settings.cornerScale * 100)
                            )
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("利落", style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = settings.cornerScale,
                                onValueChange = { v ->
                                    vm.updateSettings { it.copy(cornerScale = v) }
                                },
                                    valueRange = 0.5f..1.8f,
                                    // 0.05 一档，手感够细又不会滑到停不下来
                                    steps = 25,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                )
                                Text("软糯", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        formItem(
                            headlineContent = { Text("按钮尺寸") },
                            supportingContent = {
                                Text(
                                    when (settings.buttonSize) {
                                        0 -> "紧凑：高度 36dp，信息密度高"
                                        2 -> "宽松：高度 52dp，单手更好点"
                                        else -> "标准：高度 44dp，符合 Material 建议"
                                    }
                                )
                            },
                        ) {
                            ConnectedMultiChoiceButtons(
                                options = ButtonSize.entries.indices.toList(),
                                selected = listOf(
                                    settings.buttonSize.coerceIn(0, ButtonSize.entries.lastIndex)
                                ),
                                onToggle = { size, _ ->
                                    vm.updateSettings { it.copy(buttonSize = size) }
                                },
                            ) { size, selected ->
                                ConnectedChoiceLabel(
                                    text = ButtonSize.entries[size].label,
                                    selected = selected,
                                    icon = HugeIcons.Ruler,
                                )
                            }
                        }
                        formItem(
                            headlineContent = { Text("列表密度") },
                            supportingContent = {
                                Text(
                                    when (settings.listDensity) {
                                        0 -> "紧凑：行高最小，一屏能看更多"
                                        2 -> "舒适：行距放大，适合大屏"
                                        else -> "标准：默认节奏"
                                    }
                                )
                            },
                        ) {
                            ConnectedMultiChoiceButtons(
                                options = ListDensity.entries.indices.toList(),
                                selected = listOf(
                                    settings.listDensity.coerceIn(0, ListDensity.entries.lastIndex)
                                ),
                                onToggle = { d, _ ->
                                    vm.updateSettings { it.copy(listDensity = d) }
                                },
                            ) { d, selected ->
                                ConnectedChoiceLabel(
                                    text = ListDensity.entries[d].label,
                                    selected = selected,
                                    icon = HugeIcons.ListView,
                                )
                            }
                        }
                    }
                }
            }

            item("customThemesHeader") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_theme_page_custom_themes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { showImportDialog = true }
                            ) {
                                Icon(HugeIcons.FileImport, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.setting_theme_page_import_theme))
                            }
                            FilledTonalButton(
                                onClick = {
                                    editingTheme = null
                                    showEditSheet = true
                                }
                            ) {
                                Icon(HugeIcons.PlusSign, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.setting_theme_page_add_theme))
                            }
                        }
                    }
                }

                if (settings.customThemes.isEmpty()) {
                    item("emptyCustomThemes") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.setting_theme_page_no_custom_themes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(settings.customThemes, key = { it.id }) { theme ->
                    CustomThemeItem(
                        theme = theme,
                        isSelected = settings.themeId == theme.id,
                        onSelect = {
                            vm.updateSettings { it.copy(themeId = theme.id) }
                        },
                        onExport = {
                            val json = themeJson.encodeToString(theme)
                            scope.launch {
                                clipboardManager.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("theme", json))
                                )
                            }
                            toaster.show(exportSuccessMsg, type = ToastType.Success)
                        },
                        onEdit = {
                            editingTheme = theme
                            showEditSheet = true
                        },
                        onDelete = {
                            deletingTheme = theme
                        }
                    )
                }
            }
    }

    if (showEditSheet) {
        CustomThemeEditSheet(
            theme = editingTheme,
            onDismiss = { showEditSheet = false },
            onSave = { theme ->
                val newThemes = if (editingTheme != null) {
                    settings.customThemes.map { if (it.id == theme.id) theme else it }
                } else {
                    settings.customThemes + theme
                }
                vm.updateSettings {
                    it.copy(
                        customThemes = newThemes,
                        themeId = theme.id
                    )
                }
                showEditSheet = false
            }
        )
    }

    if (showImportDialog) {
        ImportThemeDialog(
            onDismiss = { showImportDialog = false },
            onImport = { theme ->
                val importedTheme = theme.copy(id = Uuid.random().toString())
                vm.updateSettings {
                    it.copy(
                        customThemes = it.customThemes + importedTheme,
                        themeId = importedTheme.id
                    )
                }
                showImportDialog = false
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }
        )
    }

    GcmodAiConfirmDialog(
        show = deletingTheme != null,
        title = stringResource(R.string.setting_theme_page_delete_theme_title),
        confirmText = stringResource(android.R.string.ok),
        dismissText = stringResource(android.R.string.cancel),
        onConfirm = {
            deletingTheme?.let { theme ->
                val newThemes = settings.customThemes.filter { it.id != theme.id }
                val newThemeId = if (settings.themeId == theme.id) "sakura" else settings.themeId
                vm.updateSettings { it.copy(customThemes = newThemes, themeId = newThemeId) }
            }
            deletingTheme = null
        },
        onDismiss = { deletingTheme = null },
        text = {
            Text(stringResource(R.string.setting_theme_page_delete_theme_message))
        }
    )
}


@Composable
private fun CustomThemeItem(
    theme: CustomTheme,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.generateColorScheme(darkMode)

    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelect() },
        headlineContent = { Text(theme.name.ifEmpty { "Unnamed" }) },
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(40.dp)
                ) {
                    drawRect(color = scheme.primaryContainer, size = size)
                    drawRect(
                        color = scheme.secondaryContainer,
                        size = size,
                        topLeft = Offset(x = size.width / 2, y = 0f)
                    )
                    drawRect(
                        color = scheme.tertiaryContainer,
                        size = size,
                        topLeft = Offset(x = size.width / 2, y = size.height / 2)
                    )
                    drawCircle(
                        color = scheme.primary,
                        radius = if (isSelected) 10.dp.toPx() else 6.dp.toPx(),
                        center = Offset(x = size.width / 2, y = size.height / 2)
                    )
                }
                if (isSelected) {
                    Icon(
                        HugeIcons.Tick01,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        trailingContent = {
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.edit),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onEdit,
                    ),
                    ItemAction(
                        text = stringResource(R.string.export_title),
                        icon = HugeIcons.Share03,
                        onClick = onExport,
                    ),
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        },
        colors = CustomColors.listItemColors,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomThemeEditSheet(
    theme: CustomTheme?,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var currentTheme by remember {
        mutableStateOf(theme ?: CustomTheme())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = if (theme == null) stringResource(R.string.setting_theme_page_create_theme)
                else stringResource(R.string.setting_theme_page_edit_theme),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = currentTheme.name,
                    onValueChange = { currentTheme = currentTheme.copy(name = it) },
                    label = { Text(stringResource(R.string.setting_theme_page_theme_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_primary_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColorPickerRow(
                    color = Color(currentTheme.primaryColorArgb.toInt()),
                    onColorChange = {
                        currentTheme = currentTheme.copy(primaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_secondary_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColorPickerRow(
                    color = if (currentTheme.secondaryColorArgb != null) {
                        Color(currentTheme.secondaryColorArgb!!.toInt())
                    } else {
                        Color(currentTheme.generateColorScheme(false).secondary.toArgb())
                    },
                    onColorChange = {
                        currentTheme = currentTheme.copy(secondaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_tertiary_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColorPickerRow(
                    color = if (currentTheme.tertiaryColorArgb != null) {
                        Color(currentTheme.tertiaryColorArgb!!.toInt())
                    } else {
                        Color(currentTheme.generateColorScheme(false).tertiary.toArgb())
                    },
                    onColorChange = {
                        currentTheme = currentTheme.copy(tertiaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                )

                // 配色风格：同一颗种子色，换风格后整套调色板完全不同
                Text("配色风格", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "决定主色/副色怎么派生：柔和最耐看，鲜艳更抓眼，极简灰适合长时间阅读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeVariants.fastForEach { (id, label) ->
                        val selected = currentTheme.variant == id
                        FilterChip(
                            selected = selected,
                            onClick = { currentTheme = currentTheme.copy(variant = id) },
                            label = { Text(label) },
                        )
                    }
                }

                ThemePreview(currentTheme)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(currentTheme) },
                    enabled = currentTheme.name.isNotBlank()
                ) {
                    Text(stringResource(R.string.setting_theme_page_save))
                }
            }
        }
    }
}

@Composable
private fun ImportThemeDialog(
    onDismiss: () -> Unit,
    onImport: (CustomTheme) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_theme_page_import_theme)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        errorMessage = null
                    },
                    label = { Text("JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val theme = themeJson.decodeFromString<CustomTheme>(jsonText)
                        onImport(theme)
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                },
                enabled = jsonText.isNotBlank()
            ) {
                Text(stringResource(R.string.setting_theme_page_import_theme))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun ColorPickerRow(
    color: Color,
    onColorChange: (Color) -> Unit,
) {
    val hsl = remember(color) {
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    }
    var hue by remember(color) { mutableFloatStateOf(hsl[0]) }
    var saturation by remember(color) { mutableFloatStateOf(hsl[1]) }
    var lightness by remember(color) { mutableFloatStateOf(hsl[2]) }
    var hslCode by remember(color) { mutableStateOf(formatHslCode(hsl[0], hsl[1], hsl[2])) }
    var hslCodeError by remember(color) { mutableStateOf(false) }

    fun updateColor(newHue: Float, newSaturation: Float, newLightness: Float) {
        hue = newHue
        saturation = newSaturation
        lightness = newLightness
        hslCode = formatHslCode(newHue, newSaturation, newLightness)
        hslCodeError = false
        onColorChange(Color(ColorUtils.HSLToColor(floatArrayOf(newHue, newSaturation, newLightness))))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            ) {
                drawCircle(color = color)
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("H", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = hue,
                        onValueChange = {
                            updateColor(it, saturation, lightness)
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = saturation,
                        onValueChange = {
                            updateColor(hue, it, lightness)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("L", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = lightness,
                        onValueChange = {
                            updateColor(hue, saturation, it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        OutlinedTextField(
            value = hslCode,
            onValueChange = { value ->
                hslCode = value
                val parsedHsl = parseHslCode(value)
                hslCodeError = parsedHsl == null
                if (parsedHsl != null) {
                    hue = parsedHsl[0]
                    saturation = parsedHsl[1]
                    lightness = parsedHsl[2]
                    onColorChange(Color(ColorUtils.HSLToColor(parsedHsl)))
                }
            },
            label = { Text("HSL") },
            placeholder = { Text("hsl(267 36% 48%)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = hslCodeError,
            supportingText = if (hslCodeError) {
                { Text("Use hsl(267 36% 48%)") }
            } else {
                null
            },
        )
    }
}

private val hslNumberRegex = Regex("""[-+]?\d*\.?\d+""")

private fun parseHslCode(value: String): FloatArray? {
    val values = buildList {
        for (match in hslNumberRegex.findAll(value)) {
            add(match.value.toFloatOrNull() ?: return null)
            if (size == 3) break
        }
    }

    if (values.size != 3) return null

    val hue = values[0].coerceIn(0f, 360f)
    val saturation = parseHslPercentOrFraction(values[1]) ?: return null
    val lightness = parseHslPercentOrFraction(values[2]) ?: return null

    return floatArrayOf(hue, saturation, lightness)
}

private fun parseHslPercentOrFraction(value: Float): Float? {
    if (!value.isFinite()) return null
    return if (value > 1f) {
        (value / 100f).coerceIn(0f, 1f)
    } else {
        value.coerceIn(0f, 1f)
    }
}

private fun formatHslCode(hue: Float, saturation: Float, lightness: Float): String {
    return "hsl(${hue.roundToInt()} ${(saturation * 100).roundToInt()}% ${(lightness * 100).roundToInt()}%)"
}

@Composable
private fun ThemePreview(theme: CustomTheme) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.generateColorScheme(darkMode)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_theme_page_preview),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ColorSwatch(scheme.primary, "P")
            ColorSwatch(scheme.secondary, "S")
            ColorSwatch(scheme.tertiary, "T")
            ColorSwatch(scheme.primaryContainer, "PC")
            ColorSwatch(scheme.secondaryContainer, "SC")
            ColorSwatch(scheme.surface, "Sf")
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
        ) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
