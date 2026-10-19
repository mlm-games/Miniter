package org.mlm.miniter.ui.components.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.mlm.miniter.project.RecoveryInfo

@Composable
fun RecoveryDialog(
    recovery: RecoveryInfo,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
) {
    val name = recovery.projectName?.takeIf { it.isNotBlank() } ?: "Untitled project"
    val origin = recovery.projectPath?.takeIf { it.isNotBlank() }?.let { " (last saved to $it)" } ?: ""
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Recover unsaved work?") },
        text = {
            Text(
                "Miniter did not shut down cleanly. A recovery copy of \"$name\"$origin is available. " +
                    "Restoring opens it as an unsaved document; your project file is not modified until you save."
            )
        },
        confirmButton = {
            TextButton(onClick = onRestore) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text("Discard") }
        },
    )
}
