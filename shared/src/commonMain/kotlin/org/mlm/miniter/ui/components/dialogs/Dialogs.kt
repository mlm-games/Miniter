package org.mlm.miniter.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
