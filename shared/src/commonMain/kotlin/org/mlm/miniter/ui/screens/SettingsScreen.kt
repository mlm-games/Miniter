package org.mlm.miniter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.ui.AutoSettingsScreen
import io.github.mlmgames.settings.ui.CategoryConfig
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.Appearance
import org.mlm.miniter.settings.Editor
import org.mlm.miniter.settings.Export
import org.mlm.miniter.ui.util.popBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backStack: NavBackStack<NavKey>,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { backStack.popBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
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
            categoryConfigs = listOf(
                CategoryConfig(Appearance::class, "Appearance"),
                CategoryConfig(Editor::class, "Editor"),
                CategoryConfig(Export::class, "Export"),
            )
        )
    }
}
