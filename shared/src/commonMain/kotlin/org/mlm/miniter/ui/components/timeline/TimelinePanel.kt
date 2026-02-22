package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mlm.miniter.project.*

private val TRACK_HEIGHT = 56.dp
private val TRACK_HEADER_WIDTH = 120.dp
private val RULER_HEIGHT = 28.dp

@Composable
fun TimelinePanel(
    project: MinterProject?,
    playheadMs: Long,
    zoomLevel: Float,
    selectedClipId: String?,
    onPlayheadChange: (Long) -> Unit,
    onClipSelected: (String?) -> Unit,
    onClipMoved: (String, Long) -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleLock: (String) -> Unit,
) {
    if (project == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No project loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val tracks = project.timeline.tracks
    val scrollState = rememberScrollState()
    val pxPerMs = zoomLevel * 0.1f

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
            Spacer(Modifier.width(TRACK_HEADER_WIDTH))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
                    .pointerInput(pxPerMs) {
                        detectTapGestures { offset ->
                            val ms = (offset.x / pxPerMs).toLong()
                            onPlayheadChange(ms)
                        }
                    }
                    .drawBehind {
                        val intervalMs = (1000 / pxPerMs).toLong().coerceAtLeast(500)
                        val totalWidth = size.width
                        var ms = 0L
                        while (ms * pxPerMs < totalWidth) {
                            val x = ms * pxPerMs
                            drawLine(
                                Color.Gray.copy(alpha = 0.5f),
                                start = Offset(x, size.height * 0.6f),
                                end = Offset(x, size.height),
                                strokeWidth = 1f
                            )
                            ms += intervalMs
                        }

                        val phX = playheadMs * pxPerMs
                        drawLine(
                            Color.Red,
                            start = Offset(phX, 0f),
                            end = Offset(phX, size.height),
                            strokeWidth = 2f
                        )
                    }
            )
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            for (track in tracks) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TRACK_HEIGHT)
                ) {
                    TrackHeader(
                        track = track,
                        onToggleMute = { onToggleMute(track.id) },
                        onToggleLock = { onToggleLock(track.id) },
                        modifier = Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight()
                    )

                    VerticalDivider()

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(scrollState)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(2.dp)
                                .offset(x = (playheadMs * pxPerMs).dp)
                                .background(Color.Red)
                        )

                        for (clip in track.clips) {
                            val leftOffset = (clip.startMs * pxPerMs).dp
                            val clipWidth = (clip.durationMs * pxPerMs).dp.coerceAtLeast(4.dp)
                            val isSelected = clip.id == selectedClipId

                            ClipBlock(
                                clip = clip,
                                trackType = track.type,
                                width = clipWidth,
                                isSelected = isSelected,
                                modifier = Modifier
                                    .offset(x = leftOffset)
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp)
                                    .pointerInput(clip.id) {
                                        detectTapGestures {
                                            onClipSelected(clip.id)
                                        }
                                    }
                                    .pointerInput(clip.id, pxPerMs) {
                                        var accumulatedDx = 0f
                                        detectDragGestures { _, dragAmount ->
                                            if (!track.isLocked) {
                                                accumulatedDx += dragAmount.x
                                                val newStart = (clip.startMs + (accumulatedDx / pxPerMs).toLong())
                                                    .coerceAtLeast(0)
                                                onClipMoved(clip.id, newStart)
                                            }
                                        }
                                    }
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TrackHeader(
    track: Track,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (track.type) {
                    TrackType.Video -> Icons.Default.Videocam
                    TrackType.Audio -> Icons.Default.Audiotrack
                    TrackType.Text -> Icons.Default.TextFields
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                track.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleMute, modifier = Modifier.size(20.dp)) {
                Icon(
                    if (track.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute",
                    modifier = Modifier.size(14.dp),
                    tint = if (track.isMuted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(20.dp)) {
                Icon(
                    if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock",
                    modifier = Modifier.size(14.dp),
                    tint = if (track.isLocked) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ClipBlock(
    clip: Clip,
    trackType: TrackType,
    width: Dp,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = when (trackType) {
        TrackType.Video -> MaterialTheme.colorScheme.primaryContainer
        TrackType.Audio -> MaterialTheme.colorScheme.secondaryContainer
        TrackType.Text -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else Color.Transparent

    Surface(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp)
            ),
        color = color,
        shape = RoundedCornerShape(6.dp),
        tonalElevation = if (isSelected) 4.dp else 1.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = when (clip) {
                    is Clip.VideoClip -> clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")
                    is Clip.AudioClip -> clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")
                    is Clip.TextClip -> clip.text
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when (trackType) {
                    TrackType.Video -> MaterialTheme.colorScheme.onPrimaryContainer
                    TrackType.Audio -> MaterialTheme.colorScheme.onSecondaryContainer
                    TrackType.Text -> MaterialTheme.colorScheme.onTertiaryContainer
                }
            )
        }
    }
}