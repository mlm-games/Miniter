package org.mlm.miniter

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.vinceglb.filekit.FileKit
import org.mlm.miniter.datastore.createDataStore
import org.mlm.miniter.di.KoinApp
import org.mlm.miniter.platform.SettingsProvider
import org.mlm.miniter.settings.AppSettings

fun main() = application {
    FileKit.init(appId = "org.mlm.miniter")

    val settingsRepo = remember { SettingsProvider.get() }


    KoinApp(settingsRepo) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Miniter",
        ) {
            App()
        }
    }
}
