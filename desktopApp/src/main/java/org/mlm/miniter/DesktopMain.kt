package org.mlm.miniter

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.vinceglb.filekit.FileKit
import org.mlm.miniter.di.KoinApp
import org.mlm.miniter.platform.PendingMediaOpens
import org.mlm.miniter.platform.SettingsProvider
import org.mlm.miniter.platform.pendingMediaFromCommandLineArgs

fun main(args: Array<String>) = application {
    FileKit.init(appId = "org.mlm.miniter")

    val settingsRepo = remember { SettingsProvider.get() }

    val openedMedia = pendingMediaFromCommandLineArgs(args)
    if (openedMedia.isNotEmpty()) {
        PendingMediaOpens.submit(openedMedia)
    }

    KoinApp(settingsRepo) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Miniter",
        ) {
            App()
        }
    }
}
