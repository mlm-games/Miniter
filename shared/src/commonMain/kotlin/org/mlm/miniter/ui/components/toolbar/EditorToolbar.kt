package org.mlm.miniter.ui.components.toolbar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EditorToolbar(
    isPlaying: Boolean,
    hasSelection: Boolean,
    canSplit: Boolean,
    onPlayPause: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onAddText: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomFit: () -> Unit,
    zoomPercent: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play")
            }

            VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant)

            ToolbarIconButton(icon = Icons.Default.ContentCut, label = "Split (S)", enabled = canSplit, onClick = onSplit)
            ToolbarIconButton(icon = Icons.Default.Delete, label = "Delete (Del)", enabled = hasSelection, onClick = onDelete)
            ToolbarIconButton(icon = Icons.Default.ContentCopy, label = "Duplicate (D)", enabled = hasSelection, onClick = onDuplicate)

            VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant)

            ToolbarIconButton(icon = Icons.Default.TextFields, label = "Add Text (T)", enabled = true, onClick = onAddText)

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onZoomOut, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ZoomOut, "Zoom Out", modifier = Modifier.size(20.dp))
            }

            Text("$zoomPercent%", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 36.dp))

            IconButton(onClick = onZoomIn, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ZoomIn, "Zoom In", modifier = Modifier.size(20.dp))
            }

            IconButton(onClick = onZoomFit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.FitScreen, "Fit to View", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
    }
}
