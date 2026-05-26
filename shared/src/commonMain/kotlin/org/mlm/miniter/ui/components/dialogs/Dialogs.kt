package org.mlm.miniter.ui.components.dialogs

import androidx.compose.foundation.layout.*
import org.mlm.miniter.platform.openSaveFileDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.launch
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.PlatformFileSystem

private fun defaultProjectSavePath(projectName: String): String {
    val appDir = PlatformFileSystem.getAppDataDirectory("miniter")
    return PlatformFileSystem.combinePath(appDir, "$projectName.mntr")
}

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
    mediaPath: String,
    mediaName: String,
    onDismiss: () -> Unit,
    onCreate: (projectName: String, savePath: String) -> Unit,
) {
    var projectName by remember { mutableStateOf(mediaName.substringBeforeLast(".")) }
    var saveFile by remember { mutableStateOf<PlatformFile?>(null) }
    val scope = rememberCoroutineScope()

    val savePath = saveFile?.platformPath() ?: defaultProjectSavePath(projectName.ifBlank { "project" })

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
                        value = saveFile?.platformPath() ?: "Default: app storage",
                        onValueChange = {},
                        label = { Text("Save Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                        supportingText = { Text("Choose a location to override") },
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val file = openSaveFileDialog(
                                    suggestedName = projectName.ifBlank { "project" },
                                    extension = "mntr",
                                )
                                saveFile = file
                                if (file != null) {
                                    val nameWithoutExt = file.platformPath()
                                        .substringAfterLast("/")
                                        .substringAfterLast("\\")
                                        .substringBeforeLast(".")
                                    if (nameWithoutExt.isNotBlank()) {
                                        projectName = nameWithoutExt
                                    }
                                }
                            }
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
    val scope = rememberCoroutineScope()

    val savePath = saveFile?.platformPath() ?: defaultProjectSavePath(projectName.ifBlank { "project" })

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
                        value = saveFile?.platformPath() ?: "Default location",
                        onValueChange = {},
                        label = { Text("Save Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val file = openSaveFileDialog(
                                    suggestedName = projectName.ifBlank { "project" },
                                    extension = "mntr",
                                )
                                saveFile = file
                                if (file != null) {
                                    val nameWithoutExt = file.platformPath()
                                        .substringAfterLast("/")
                                        .substringAfterLast("\\")
                                        .substringBeforeLast(".")
                                    if (nameWithoutExt.isNotBlank()) {
                                        projectName = nameWithoutExt
                                    }
                                }
                            }
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
