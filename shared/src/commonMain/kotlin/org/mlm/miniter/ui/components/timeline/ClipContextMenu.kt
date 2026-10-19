package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import miniter.shared.generated.resources.Res
import miniter.shared.generated.resources.common_delete
import org.jetbrains.compose.resources.stringResource
import org.mlm.miniter.editor.model.RustAdjustmentClipKind
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind

@Composable
fun ClipContextMenu(
    expanded: Boolean,
    clip: RustClipSnapshot,
    canSplit: Boolean,
    canJoin: Boolean,
    isLocked: Boolean,
    onDismiss: () -> Unit,
    onSplit: () -> Unit,
    onJoin: () -> Unit,
    onDuplicate: () -> Unit,
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPaste: () -> Unit = {},
    canPaste: Boolean = false,
    onDelete: () -> Unit,
    onSetAsPlayhead: () -> Unit,
    onRippleDelete: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onSplitAll: () -> Unit = {},
    onCloseGap: () -> Unit = {},
    onSlipLeft: () -> Unit = {},
    onSlipRight: () -> Unit = {},
    onSlideLeft: () -> Unit = {},
    onSlideRight: () -> Unit = {},
    onRollLeft: () -> Unit = {},
    onRollRight: () -> Unit = {},
    canSlip: Boolean = false,
    canSlide: Boolean = false,
    canRoll: Boolean = false,
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
            text = { Text("Join with next") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, null, Modifier.size(18.dp)) },
            onClick = { onJoin(); onDismiss() },
            enabled = canJoin && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Duplicate") },
            leadingIcon = { Icon(Icons.Default.ControlPointDuplicate, null, Modifier.size(18.dp)) },
            onClick = { onDuplicate(); onDismiss() },
            enabled = !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) },
            onClick = { onCopy(); onDismiss() },
        )

        DropdownMenuItem(
            text = { Text("Cut") },
            leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(18.dp)) },
            onClick = { onCut(); onDismiss() },
            enabled = !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Paste at playhead") },
            leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)) },
            onClick = { onPaste(); onDismiss() },
            enabled = canPaste,
        )

        val isAdjustment = clip.kind is RustAdjustmentClipKind
        val toggleLabel = when {
            isAdjustment && clip.muted -> "Enable clip"
            isAdjustment -> "Disable clip"
            clip.muted -> "Unmute clip"
            else -> "Mute clip"
        }
        val toggleIcon = when {
            isAdjustment && clip.muted -> Icons.Default.Visibility
            isAdjustment -> Icons.Default.VisibilityOff
            clip.muted -> Icons.AutoMirrored.Filled.VolumeUp
            else -> Icons.AutoMirrored.Filled.VolumeOff
        }
        DropdownMenuItem(
            text = { Text(toggleLabel) },
            leadingIcon = { Icon(toggleIcon, null, Modifier.size(18.dp)) },
            onClick = { onToggleMute(); onDismiss() },
            enabled = !isLocked && (
                clip.kind is RustAudioClipKind ||
                    clip.kind is RustVideoClipKind ||
                    isAdjustment
                ),
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
            text = { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) },
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

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("Slip source earlier") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.NavigateBefore, null, Modifier.size(18.dp)) },
            onClick = { onSlipLeft(); onDismiss() },
            enabled = canSlip && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Slip source later") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.NavigateNext, null, Modifier.size(18.dp)) },
            onClick = { onSlipRight(); onDismiss() },
            enabled = canSlip && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Slide clip left") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)) },
            onClick = { onSlideLeft(); onDismiss() },
            enabled = canSlide && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Slide clip right") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp)) },
            onClick = { onSlideRight(); onDismiss() },
            enabled = canSlide && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Roll cut left") },
            leadingIcon = { Icon(Icons.Default.SkipPrevious, null, Modifier.size(18.dp)) },
            onClick = { onRollLeft(); onDismiss() },
            enabled = canRoll && !isLocked,
        )

        DropdownMenuItem(
            text = { Text("Roll cut right") },
            leadingIcon = { Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)) },
            onClick = { onRollRight(); onDismiss() },
            enabled = canRoll && !isLocked,
        )
    }
}
