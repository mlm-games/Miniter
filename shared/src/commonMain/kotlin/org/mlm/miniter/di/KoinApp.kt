package org.mlm.miniter.di

import androidx.compose.runtime.Composable
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.mlm.miniter.settings.AppSettings

@Composable
fun KoinApp(
    settingsRepository: SettingsRepository<AppSettings>,
    content: @Composable () -> Unit
) {
    KoinApplication(
        application = {
            modules(appModules(settingsRepository))
        },
        content = content
    )
}

fun initKoin(
    settingsRepository: SettingsRepository<AppSettings>,
    additionalModules: List<Module> = emptyList()
) {
    startKoin {
        modules(appModules(settingsRepository) + additionalModules)
    }
}

fun stopKoinApp() {
    stopKoin()
}
