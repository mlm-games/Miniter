package org.mlm.miniter.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import org.koin.compose.koinInject
import org.mlm.miniter.ui.components.snackbar.SnackbarManager

@Composable
fun EditorScreen(
    onOpenSettings: () -> Unit,
    onOpenProject: (String, String) -> Unit,
) {
    val snackbarManager: SnackbarManager = koinInject()

    val videoPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("mp4", "webm", "mov", "mkv", "avi"))
    ) { file: PlatformFile? ->
        file?.let {
            onOpenProject(it.path, it.name)
        }
    }

    val projectPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("mntr"))
    ) { file: PlatformFile? ->
        file?.let {
            onOpenProject(it.path, it.name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miniter", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Icon(
                    Icons.Default.VideoFile,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    "Welcome to Miniter",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "A simple minimal video editor",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { videoPicker.launch() },
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Video File")
                }

                OutlinedButton(
                    onClick = { projectPicker.launch() },
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Project (.mntr)")
                }
            }
        }
    }
}
