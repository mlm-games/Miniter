package org.mlm.miniter.di

import androidx.compose.material3.SnackbarHostState
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.ui.components.snackbar.SnackbarManager

val coreModule = module {
    single { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    single { SnackbarHostState() }
    single { SnackbarManager() }
}

fun appModules(settingsRepository: SettingsRepository<AppSettings>) = listOf(
    module { single { settingsRepository } },
    coreModule
)
