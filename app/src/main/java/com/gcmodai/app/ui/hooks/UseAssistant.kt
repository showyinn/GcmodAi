package com.gcmodai.app.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.data.datastore.getCurrentAssistant
import com.gcmodai.app.data.model.Assistant

@Composable
fun rememberAssistantState(
    settings: Settings,
    onSelectAssistant: (Assistant) -> Unit
): AssistantState {
    return remember(settings, onSelectAssistant) {
        AssistantState(settings, onSelectAssistant)
    }
}

class AssistantState(
    private val settings: Settings,
    private val onSelectAssistant: (Assistant) -> Unit
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant()
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        onSelectAssistant(assistant)
    }
}
