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
    onPlayPause: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onAddText: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause"
                )
            }

            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text("Split at playhead (S)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onSplit) {
                    Icon(Icons.Default.ContentCut, "Split")
                }
            }

            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text("Delete selected clip (Del)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete")
                }
            }

            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text("Add text overlay (T)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onAddText) {
                    Icon(Icons.Default.TextFields, "Add Text")
                }
            }

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onZoomOut) {
                Icon(Icons.Default.ZoomOut, "Zoom Out")
            }
            IconButton(onClick = onZoomIn) {
                Icon(Icons.Default.ZoomIn, "Zoom In")
            }
        }
    }
}