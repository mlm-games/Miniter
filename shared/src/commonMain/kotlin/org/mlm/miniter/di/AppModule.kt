package org.mlm.miniter.di

import androidx.compose.material3.SnackbarHostState
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.mlm.miniter.engine.VideoExporter
import org.mlm.miniter.project.ProjectRepository
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.viewmodel.ProjectViewModel

val coreModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
    single { SnackbarHostState() }
    single { SnackbarManager() }
    single { ProjectRepository(get()) }
    single { VideoExporter() }
    factory { ProjectViewModel(get()) }
}

fun appModules(settingsRepository: SettingsRepository<AppSettings>) = listOf(
    module { single { settingsRepository } },
    coreModule
)
