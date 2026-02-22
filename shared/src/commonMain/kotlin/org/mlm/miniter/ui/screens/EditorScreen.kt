package org.mlm.miniter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import org.koin.compose.koinInject
import org.mlm.miniter.project.RecentProject
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.viewmodel.EditorViewModel

@Composable
fun EditorScreen(
    onOpenSettings: () -> Unit,
    onOpenProject: (String, String) -> Unit,
    editorViewModel: EditorViewModel = koinInject(),
) {
    val snackbarManager: SnackbarManager = koinInject()
    val recentProjects by editorViewModel.recentProjects.collectAsState()

    val videoPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("mp4", "webm", "mov", "mkv", "avi")),
    ) { file: PlatformFile? ->
        file?.let { onOpenProject(it.path, it.name) }
    }

    val projectPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("mntr")),
    ) { file: PlatformFile? ->
        file?.let { onOpenProject(it.path, it.name) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miniter", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(32.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Miniter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("A minimal video editor", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { videoPicker.launch() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Video")
                }

                OutlinedButton(onClick = { projectPicker.launch() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Project")
                }
            }

            Spacer(Modifier.height(32.dp))

            if (recentProjects.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recent Projects", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editorViewModel.clearRecents() }) {
                        Text("Clear All", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recentProjects, key = { it.path }) { recent ->
                        RecentProjectItem(
                            recent = recent,
                            onClick = { onOpenProject(recent.path, recent.name) },
                            onRemove = { editorViewModel.removeRecent(recent.path) },
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No recent projects", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentProjectItem(recent: RecentProject, onClick: () -> Unit, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (recent.path.endsWith(".mntr")) Icons.Default.Description else Icons.Default.VideoFile,
                contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(recent.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(recent.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
