package org.mlm.miniter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject
import org.mlm.miniter.nav.*
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.settings.ThemeMode
import org.mlm.miniter.ui.animation.forwardTransition
import org.mlm.miniter.ui.animation.popTransition
import org.mlm.miniter.ui.components.snackbar.LauncherSnackbarHost
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.ui.screens.*
import org.mlm.miniter.ui.theme.MainTheme
import org.mlm.miniter.ui.util.popBack

val LocalMessageFontSize = staticCompositionLocalOf { 16f }

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    val snackbarManager: SnackbarManager = koinInject()
    val snackbarHostState: SnackbarHostState = koinInject()
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)

    val isDark = when (settings.themeMode) {
        ThemeMode.System.ordinal -> isSystemInDarkTheme()
        ThemeMode.Dark.ordinal -> true
        ThemeMode.Light.ordinal -> false
        else -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(LocalMessageFontSize provides settings.fontSize) {
        MainTheme(darkTheme = isDark, dynamicColors = false) {
            val backStack: NavBackStack<NavKey> =
                rememberNavBackStack(navSavedStateConfiguration, Route.Editor)

            Scaffold(
                snackbarHost = {
                    LauncherSnackbarHost(hostState = snackbarHostState, manager = snackbarManager)
                }
            ) { _ ->
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator()
                    ),
                    transitionSpec = forwardTransition,
                    popTransitionSpec = popTransition,
                    predictivePopTransitionSpec = { _ -> popTransition.invoke(this) },
                    onBack = {
                        backStack.popBack()
                    },
                    entryProvider = entryProvider {
                        entry<Route.Editor> {
                            EditorScreen(
                                onOpenSettings = { backStack.add(Route.Settings) },
                                onOpenProject = { path, name, savePath, openAsProject ->
                                    backStack.add(Route.Project(path, name, savePath, openAsProject))
                                }
                            )
                        }

                        entry<Route.Settings> {
                            SettingsScreen(backStack = backStack)
                        }

                        entry<Route.Project> { key ->
                            ProjectScreen(
                                videoPath = key.path,
                                videoName = key.name,
                                backStack = backStack,
                                savePath = key.savePath,
                                openAsProject = key.openAsProject,
                            )
                        }

                        entry<Route.Export> { _ ->
                            ExportScreen(backStack = backStack)
                        }

                        entry<Route.Projects> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Projects", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                )
            }
        }
    }
}
