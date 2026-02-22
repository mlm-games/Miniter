package org.mlm.miniter.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}

@Composable
fun ShortcutHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keyboard Shortcuts") },
        text = {
            val shortcuts = listOf(
                "Space" to "Play / Pause",
                "S" to "Split clip at playhead",
                "D" to "Duplicate selected clip",
                "T" to "Add text overlay",
                "Delete" to "Delete selected clip",
                "Ctrl+S" to "Save project",
                "Ctrl+Z" to "Undo",
                "Ctrl+Shift+Z" to "Redo",
                "← / →" to "Move playhead 1s",
                "Shift+← / →" to "Move playhead 5s",
                "Home" to "Go to start",
                "+ / −" to "Zoom in / out",
                "Escape" to "Deselect",
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shortcuts.size) { i ->
                    val (key, desc) = shortcuts[i]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            key,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(0.4f)
                        )
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
fun NewProjectDialog(
    videoPath: String,
    videoName: String,
    onDismiss: () -> Unit,
    onCreate: (projectName: String, savePath: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var projectName by remember { mutableStateOf(videoName.substringBeforeLast(".")) }
    var saveDirectory by remember { mutableStateOf<String?>(null) }
    var isPickingDirectory by remember { mutableStateOf(false) }

    val suggestedFileName = "$projectName.mntr"
    val fullSavePath = saveDirectory?.let { "$it/$suggestedFileName" }
        ?: (videoPath.substringBeforeLast("/") + "/" + suggestedFileName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = saveDirectory ?: "Same as video location",
                        onValueChange = {},
                        label = { Text("Save Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                        supportingText = { Text("Leave empty to save next to video") },
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                isPickingDirectory = true
                                val dir = FileKit.openDirectoryPicker(title = "Select Project Location")
                                saveDirectory = dir?.path
                                isPickingDirectory = false
                            }
                        },
                        enabled = !isPickingDirectory,
                    ) {
                        Icon(Icons.Default.FolderOpen, "Choose location")
                    }
                }

                Text(
                    "File will be saved as: $fullSavePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(projectName, fullSavePath) },
                enabled = projectName.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SaveProjectDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (projectName: String, savePath: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var projectName by remember { mutableStateOf(defaultName) }
    var saveDirectory by remember { mutableStateOf<String?>(null) }
    var isPickingDirectory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = saveDirectory ?: "Default location",
                        onValueChange = {},
                        label = { Text("Save Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                isPickingDirectory = true
                                val dir = FileKit.openDirectoryPicker(title = "Select Project Location")
                                saveDirectory = dir?.path
                                isPickingDirectory = false
                            }
                        },
                        enabled = !isPickingDirectory,
                    ) {
                        Icon(Icons.Default.FolderOpen, "Choose location")
                    }
                }

                val suggestedFileName = "$projectName.mntr"
                Text(
                    "File will be saved as: ${suggestedFileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fileName = "$projectName.mntr"
                    val path = saveDirectory?.let { "$it/$fileName" } ?: fileName
                    onSave(projectName, path)
                },
                enabled = projectName.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
