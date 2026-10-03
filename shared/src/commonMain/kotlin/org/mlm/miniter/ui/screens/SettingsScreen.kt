package org.mlm.miniter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation3.runtime.NavBackStack
import miniter.shared.generated.resources.Res
import miniter.shared.generated.resources.common_back
import miniter.shared.generated.resources.common_settings
import org.jetbrains.compose.resources.stringResource
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.ProvideStringResources
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.rememberSettingsStringResourceProvider
import org.mlm.miniter.ui.util.popBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backStack: NavBackStack<NavKey>,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)
    val settingsStringProvider = rememberSettingsStringResourceProvider()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_settings), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { backStack.popBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        ProvideStringResources(settingsStringProvider) {
            AutoSettingsScreen(
                schema = settingsRepository.schema,
                value = settings,
                onSet = { key, value ->
                    scope.launch {
                        settingsRepository.set(key, value)
                    }
                },
                onAction = { },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}
