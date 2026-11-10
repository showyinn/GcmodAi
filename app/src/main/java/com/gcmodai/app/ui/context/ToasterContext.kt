package com.gcmodai.app.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.gcmodai.ui.toast.ToasterState

val LocalToaster = staticCompositionLocalOf<ToasterState> { error("Not provided") }