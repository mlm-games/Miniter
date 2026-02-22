package org.mlm.miniter.ui.components.toolbar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mlm.miniter.ui.util.formatTimestamp

@Composable
fun PlaybackControls(
    playheadMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekToStart: () -> Unit,
    onSeekToEnd: () -> Unit,
    onSeekRelative: (Long) -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            if (durationMs > 0) {
                Slider(
                    value = playheadMs.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTimestamp(playheadMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onSeekToStart,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Start", Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { onSeekRelative(-5000) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Replay5, "Back 5s", Modifier.size(20.dp))
                    }

                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    IconButton(
                        onClick = { onSeekRelative(5000) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Forward5, "Forward 5s", Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = onSeekToEnd,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, "End", Modifier.size(20.dp))
                    }
                }

                Text(
                    formatTimestamp(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
