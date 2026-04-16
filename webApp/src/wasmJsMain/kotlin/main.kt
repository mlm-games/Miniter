package org.mlm.miniter

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.mlm.miniter.di.KoinApp
import org.mlm.miniter.platform.SettingsProvider

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val settingsRepo = SettingsProvider.get()
    ComposeViewport {
        KoinApp(settingsRepo) {
            App()
        }
    }
}
