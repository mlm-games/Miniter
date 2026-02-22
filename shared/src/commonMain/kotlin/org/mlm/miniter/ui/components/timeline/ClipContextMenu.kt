package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.miniter.project.Clip

@Composable
fun ClipContextMenu(
    expanded: Boolean,
    clip: Clip,
    canSplit: Boolean,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetAsPlayhead: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Set playhead here") },
            leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) },
            onClick = { onSetAsPlayhead(); onDismiss() },
        )

        if (canSplit) {
            DropdownMenuItem(
                text = { Text("Split at playhead") },
                leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(18.dp)) },
                onClick = { onSplit(); onDismiss() },
                enabled = !isLocked,
            )
        }

        DropdownMenuItem(
            text = { Text("Duplicate") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) },
            onClick = { onDuplicate(); onDismiss() },
            enabled = !isLocked,
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete, null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = { onDelete(); onDismiss() },
            enabled = !isLocked,
        )
    }
}
