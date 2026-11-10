package com.gcmodai.app.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.gcmodai.app.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

