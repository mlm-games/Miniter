package org.mlm.miniter.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.path

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
fun NewProjectDialog(
    videoPath: String,
    videoName: String,
    onDismiss: () -> Unit,
    onCreate: (projectName: String, savePath: String) -> Unit,
) {
    var projectName by remember { mutableStateOf(videoName.substringBeforeLast(".")) }
    var saveFile by remember { mutableStateOf<PlatformFile?>(null) }

    // Native "Save As" dialog — returns a writable PlatformFile
    val fileSaverLauncher = rememberFileSaverLauncher(dialogSettings = FileKitDialogSettings("Save as")) { file: PlatformFile? ->
        saveFile = file
        // Update project name to match chosen file name (without extension)
        if (file != null) {
            val nameWithoutExt = file.path
                .substringAfterLast("/")
                .substringAfterLast("\\")
                .substringBeforeLast(".")
            if (nameWithoutExt.isNotBlank()) {
                projectName = nameWithoutExt
            }
        }
    }

    val savePath = saveFile?.path
        ?: (videoPath.substringBeforeLast("/") + "/$projectName.mntr")

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
                        value = saveFile?.path ?: "Same as video location",
                        onValueChange = {},
                        label = { Text("Save Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                        supportingText = { Text("Leave empty to save next to video") },
                    )
                    IconButton(
                        onClick = {
                            fileSaverLauncher.launch(
                                suggestedName = projectName.ifBlank { "project" },
                                extension = "mntr",
                            )
                        },
                    ) {
                        Icon(Icons.Default.FolderOpen, "Choose location")
                    }
                }

                Text(
                    "Will be saved as: $savePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(projectName, savePath) },
                enabled = projectName.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun SaveProjectDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (projectName: String, savePath: String) -> Unit,
) {
    var projectName by remember { mutableStateOf(defaultName) }
    var saveFile by remember { mutableStateOf<PlatformFile?>(null) }

    val fileSaverLauncher = rememberFileSaverLauncher(FileKitDialogSettings("Save")) { file: PlatformFile? ->
        saveFile = file
        if (file != null) {
            val nameWithoutExt = file.path
                .substringAfterLast("/")
                .substringAfterLast("\\")
                .substringBeforeLast(".")
            if (nameWithoutExt.isNotBlank()) {
                projectName = nameWithoutExt
            }
        }
    }

    val savePath = saveFile?.path ?: "$projectName.mntr"

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
                        value = saveFile?.path ?: "Default location",
                        onValueChange = {},
                        label = { Text("Save Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                    )
                    IconButton(
                        onClick = {
                            fileSaverLauncher.launch(
                                suggestedName = projectName.ifBlank { "project" },
                                extension = "mntr",
                            )
                        },
                    ) {
                        Icon(Icons.Default.FolderOpen, "Choose location")
                    }
                }

                Text(
                    "Will be saved as: $savePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(projectName, savePath) },
                enabled = projectName.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
