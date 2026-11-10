package com.gcmodai.app.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.gcmodai.app.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
