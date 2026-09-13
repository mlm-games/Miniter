package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind

@Composable
fun ClipContextMenu(
    expanded: Boolean,
    clip: RustClipSnapshot,
    canSplit: Boolean,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetAsPlayhead: () -> Unit,
    onRippleDelete: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onSplitAll: () -> Unit = {},
    onCloseGap: () -> Unit = {},
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

        DropdownMenuItem(
            text = { Text("Split at playhead") },
            leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(18.dp)) },
            onClick = { onSplit(); onDismiss() },
            enabled = canSplit && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Duplicate") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) },
            onClick = { onDuplicate(); onDismiss() },
            enabled = !isLocked,
        )

        DropdownMenuItem(
            text = { Text(if (clip.muted) "Unmute clip" else "Mute clip") },
            leadingIcon = { Icon(if (clip.muted) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null, Modifier.size(18.dp)) },
            onClick = { onToggleMute(); onDismiss() },
            // Muting only makes sense for clips that can carry audio.
            enabled = !isLocked && (clip.kind is RustAudioClipKind || clip.kind is RustVideoClipKind),
        )

        DropdownMenuItem(
            text = { Text("Split all at playhead") },
            leadingIcon = { Icon(Icons.Default.Splitscreen, null, Modifier.size(18.dp)) },
            onClick = { onSplitAll(); onDismiss() },
            enabled = !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Close gap on track") },
            leadingIcon = { Icon(Icons.Default.Compress, null, Modifier.size(18.dp)) },
            onClick = { onCloseGap(); onDismiss() },
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

        DropdownMenuItem(
            text = { Text("Ripple delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Default.DeleteSweep, null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = { onRippleDelete(); onDismiss() },
            enabled = !isLocked,
        )
    }
}
