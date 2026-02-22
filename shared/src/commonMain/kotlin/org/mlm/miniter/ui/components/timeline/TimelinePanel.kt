package org.mlm.miniter.ui.components.timeline

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.miniter.project.*

private val TRACK_HEIGHT = 56.dp
private val TRACK_HEADER_WIDTH = 130.dp
private val RULER_HEIGHT = 32.dp
private val TRIM_HANDLE_WIDTH = 8.dp
private val PLAYHEAD_HEAD_SIZE = 10.dp
private val ADD_TRACK_ROW_HEIGHT = 40.dp
private const val MIN_CLIP_WIDTH_DP = 8f
private const val MIN_TIMELINE_DURATION_MS = 30_000L

@Composable
fun TimelinePanel(
    project: MinterProject?,
    playheadMs: Long,
    zoomLevel: Float,
    isPlaying: Boolean = false,
    selectedClipId: String?,
    onPlayheadChange: (Long) -> Unit,
    onClipSelected: (String?) -> Unit,
    onClipMoved: (clipId: String, newStartMs: Long) -> Unit,
    onClipTrimStart: (clipId: String, deltaMs: Long) -> Unit,
    onClipTrimEnd: (clipId: String, deltaMs: Long) -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onAddTrack: (TrackType) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onSnapPosition: (Long, String?) -> Long,
) {
    if (project == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No project loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val tracks = project.timeline.tracks
    val dpPerMs = zoomLevel * 0.1f
    val density = LocalDensity.current

    val timelineDurationMs = maxOf(
        project.timeline.durationMs,
        MIN_TIMELINE_DURATION_MS,
        tracks.flatMap { it.clips }.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
    ) + 5000

    val totalContentWidthDp = (timelineDurationMs * dpPerMs).dp

    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(playheadMs, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        val playheadPx = with(density) { (playheadMs * dpPerMs).dp.toPx() }
        val viewportPx = horizontalScrollState.viewportSize.toFloat()
        val scrollPx = horizontalScrollState.value.toFloat()
        val margin = viewportPx * 0.2f

        if (playheadPx > scrollPx + viewportPx - margin) {
            horizontalScrollState.scrollTo((playheadPx - viewportPx * 0.3f).toInt().coerceAtLeast(0))
        } else if (playheadPx < scrollPx + margin * 0.5f) {
            horizontalScrollState.scrollTo((playheadPx - margin).toInt().coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TimelineRuler(
            timelineDurationMs = timelineDurationMs,
            dpPerMs = dpPerMs,
            playheadMs = playheadMs,
            scrollState = horizontalScrollState,
            onTap = { tapDp ->
                val ms = (tapDp / dpPerMs).toLong().coerceAtLeast(0)
                onPlayheadChange(ms)
            },
        )

        HorizontalDivider(thickness = 0.5.dp)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                tracks.forEach { track ->
                    TrackRow(
                        track = track,
                        dpPerMs = dpPerMs,
                        totalContentWidthDp = totalContentWidthDp,
                        playheadMs = playheadMs,
                        selectedClipId = selectedClipId,
                        scrollState = horizontalScrollState,
                        canRemove = !(track.type == TrackType.Video && tracks.count { it.type == TrackType.Video } <= 1),
                        onClipSelected = onClipSelected,
                        onClipMoved = onClipMoved,
                        onClipTrimStart = onClipTrimStart,
                        onClipTrimEnd = onClipTrimEnd,
                        onToggleMute = { onToggleMute(track.id) },
                        onToggleLock = { onToggleLock(track.id) },
                        onRemoveTrack = { onRemoveTrack(track.id) },
                        onSnapPosition = onSnapPosition,
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }

                AddTrackRow(onAddTrack = onAddTrack)
            }
        }
    }
}

@Composable
private fun TimelineRuler(
    timelineDurationMs: Long,
    dpPerMs: Float,
    playheadMs: Long,
    scrollState: ScrollState,
    onTap: (tapDp: Float) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val rulerTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rulerLineColor = MaterialTheme.colorScheme.outlineVariant
    val playheadColor = MaterialTheme.colorScheme.error
    val density = LocalDensity.current

    Row(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        Box(
            modifier = Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text("Time", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp))
        }

        VerticalDivider(thickness = 0.5.dp)

        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .horizontalScroll(scrollState)
                .pointerInput(dpPerMs) {
                    detectTapGestures { offset -> onTap(offset.x / density.density) }
                }
        ) {
            val totalWidthDp = (timelineDurationMs * dpPerMs).dp

            Canvas(modifier = Modifier.width(totalWidthDp).fillMaxHeight()) {
                val heightPx = size.height

                val majorIntervalMs = when {
                    dpPerMs >= 0.5f -> 1_000L
                    dpPerMs >= 0.2f -> 2_000L
                    dpPerMs >= 0.1f -> 5_000L
                    dpPerMs >= 0.05f -> 10_000L
                    else -> 30_000L
                }
                val minorTicksPerMajor = 5
                val minorIntervalMs = majorIntervalMs / minorTicksPerMajor

                var ms = 0L
                while (ms <= timelineDurationMs) {
                    val x = ms * dpPerMs * density.density
                    val isMajor = ms % majorIntervalMs == 0L

                    if (isMajor) {
                        drawLine(rulerLineColor, Offset(x, heightPx * 0.4f), Offset(x, heightPx), 1.5f)
                        val label = formatRulerTime(ms)
                        val textResult = textMeasurer.measure(label, style = TextStyle(fontSize = 10.sp, color = rulerTextColor))
                        drawText(textResult, topLeft = Offset(x + 3f, 2f))
                    } else {
                        drawLine(rulerLineColor.copy(alpha = 0.4f), Offset(x, heightPx * 0.7f), Offset(x, heightPx), 0.5f)
                    }
                    ms += minorIntervalMs
                }

                val phX = playheadMs * dpPerMs * density.density
                val headSize = PLAYHEAD_HEAD_SIZE.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(phX - headSize / 2, 0f)
                    lineTo(phX + headSize / 2, 0f)
                    lineTo(phX, headSize)
                    close()
                }
                drawPath(path, playheadColor)
                drawLine(playheadColor, Offset(phX, headSize), Offset(phX, heightPx), 2f)
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    dpPerMs: Float,
    totalContentWidthDp: Dp,
    playheadMs: Long,
    selectedClipId: String?,
    scrollState: ScrollState,
    canRemove: Boolean,
    onClipSelected: (String?) -> Unit,
    onClipMoved: (String, Long) -> Unit,
    onClipTrimStart: (String, Long) -> Unit,
    onClipTrimEnd: (String, Long) -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onRemoveTrack: () -> Unit,
    onSnapPosition: (Long, String?) -> Long,
) {
    val density = LocalDensity.current
    val playheadColor = MaterialTheme.colorScheme.error

    Row(modifier = Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
        TrackHeader(
            track = track,
            canRemove = canRemove,
            onToggleMute = onToggleMute,
            onToggleLock = onToggleLock,
            onRemoveTrack = onRemoveTrack,
            modifier = Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight(),
        )

        VerticalDivider(thickness = 0.5.dp)

        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .horizontalScroll(scrollState)
                .pointerInput(dpPerMs) {
                    detectTapGestures {
                        onClipSelected(null)
                    }
                }
        ) {
            Box(modifier = Modifier.width(totalContentWidthDp).fillMaxHeight()) {
                track.clips.forEach { clip ->
                    val leftDp = (clip.startMs * dpPerMs).dp
                    val widthDp = (clip.durationMs * dpPerMs).dp.coerceAtLeast(MIN_CLIP_WIDTH_DP.dp)
                    val isSelected = clip.id == selectedClipId
                    val isLocked = track.isLocked

                    ClipBlock(
                        clip = clip,
                        trackType = track.type,
                        widthDp = widthDp,
                        isSelected = isSelected,
                        isLocked = isLocked,
                        isMuted = track.isMuted,
                        dpPerMs = dpPerMs,
                        modifier = Modifier.offset(x = leftDp).fillMaxHeight().padding(vertical = 2.dp),
                        onTap = { onClipSelected(clip.id) },
                        onMoved = { deltaMs ->
                            if (!isLocked) {
                                val raw = (clip.startMs + deltaMs).coerceAtLeast(0)
                                val snapped = onSnapPosition(raw, clip.id)
                                onClipMoved(clip.id, snapped)
                            }
                        },
                        onTrimStart = { deltaMs -> if (!isLocked) onClipTrimStart(clip.id, deltaMs) },
                        onTrimEnd = { deltaMs -> if (!isLocked) onClipTrimEnd(clip.id, deltaMs) },
                    )
                }

                val phDp = (playheadMs * dpPerMs).dp
                Box(
                    modifier = Modifier.offset(x = phDp - 0.5.dp).width(1.5.dp).fillMaxHeight()
                        .background(playheadColor)
                )
            }
        }
    }
}

@Composable
private fun TrackHeader(
    track: Track,
    canRemove: Boolean,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onRemoveTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (track.type) {
                    TrackType.Video -> Icons.Default.Videocam
                    TrackType.Audio -> Icons.Default.Audiotrack
                    TrackType.Text -> Icons.Default.TextFields
                },
                contentDescription = null, modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(3.dp))
            Text(track.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleMute, modifier = Modifier.size(18.dp)) {
                Icon(if (track.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute", modifier = Modifier.size(12.dp),
                    tint = if (track.isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(18.dp)) {
                Icon(if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock", modifier = Modifier.size(12.dp),
                    tint = if (track.isLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canRemove) {
                IconButton(onClick = onRemoveTrack, modifier = Modifier.size(18.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove track", modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun ClipBlock(
    clip: Clip,
    trackType: TrackType,
    widthDp: Dp,
    isSelected: Boolean,
    isLocked: Boolean,
    isMuted: Boolean,
    dpPerMs: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onMoved: (deltaMs: Long) -> Unit,
    onTrimStart: (deltaMs: Long) -> Unit,
    onTrimEnd: (deltaMs: Long) -> Unit,
) {
    val density = LocalDensity.current

    val bgColor = when (trackType) {
        TrackType.Video -> MaterialTheme.colorScheme.primaryContainer
        TrackType.Audio -> MaterialTheme.colorScheme.secondaryContainer
        TrackType.Text -> MaterialTheme.colorScheme.tertiaryContainer
    }.let { if (isMuted) it.copy(alpha = 0.5f) else it }

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp

    val clipLabel = when (clip) {
        is Clip.VideoClip -> clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")
        is Clip.AudioClip -> clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")
        is Clip.TextClip -> "T: ${clip.text}"
    }

    val onContainerColor = when (trackType) {
        TrackType.Video -> MaterialTheme.colorScheme.onPrimaryContainer
        TrackType.Audio -> MaterialTheme.colorScheme.onSecondaryContainer
        TrackType.Text -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    val trimHandleColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    Box(modifier = modifier.width(widthDp)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = TRIM_HANDLE_WIDTH)
                .clip(RoundedCornerShape(6.dp)).border(borderWidth, borderColor, RoundedCornerShape(6.dp))
                .pointerInput(clip.id) { detectTapGestures { onTap() } }
                .pointerInput(clip.id, dpPerMs, isLocked) {
                    if (isLocked) return@pointerInput
                    var accumulatedDx = 0f
                    detectDragGestures(onDragStart = { accumulatedDx = 0f }, onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDx += dragAmount.x
                        val deltaMs = (accumulatedDx / density.density / dpPerMs).toLong()
                        if (deltaMs != 0L) { onMoved(deltaMs); accumulatedDx = 0f }
                    })
                },
            color = bgColor, shape = RoundedCornerShape(6.dp),
            tonalElevation = if (isSelected) 4.dp else 1.dp,
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (clip is Clip.VideoClip && clip.speed != 1f) {
                    Text("${clip.speed}x", style = MaterialTheme.typography.labelSmall,
                        color = onContainerColor.copy(alpha = 0.7f), fontSize = 8.sp)
                    Spacer(Modifier.width(2.dp))
                }
                Text(text = clipLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = onContainerColor, modifier = Modifier.weight(1f))
                if (clip is Clip.VideoClip && clip.filters.isNotEmpty()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${clip.filters.size}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onTertiary)
                        }
                    }
                }
                if (isMuted) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null, modifier = Modifier.size(10.dp),
                        tint = onContainerColor.copy(alpha = 0.5f))
                }
            }
        }

        if (!isLocked && clip is Clip.VideoClip) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart).width(TRIM_HANDLE_WIDTH).fillMaxHeight()
                    .padding(vertical = 4.dp).clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(trimHandleColor).pointerInput(clip.id, dpPerMs) {
                        var accDx = 0f
                        detectDragGestures(onDragStart = { accDx = 0f }, onDrag = { change, dragAmount ->
                            change.consume()
                            accDx += dragAmount.x
                            val deltaMs = (accDx / density.density / dpPerMs).toLong()
                            if (deltaMs != 0L) { onTrimStart(deltaMs); accDx = 0f }
                        })
                    },
            )
        }

        if (!isLocked && clip is Clip.VideoClip) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).width(TRIM_HANDLE_WIDTH).fillMaxHeight()
                    .padding(vertical = 4.dp).clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(trimHandleColor).pointerInput(clip.id, dpPerMs) {
                        var accDx = 0f
                        detectDragGestures(onDragStart = { accDx = 0f }, onDrag = { change, dragAmount ->
                            change.consume()
                            accDx += dragAmount.x
                            val deltaMs = (accDx / density.density / dpPerMs).toLong()
                            if (deltaMs != 0L) { onTrimEnd(deltaMs); accDx = 0f }
                        })
                    },
            )
        }
    }
}

@Composable
private fun AddTrackRow(onAddTrack: (TrackType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxWidth().height(ADD_TRACK_ROW_HEIGHT),
        color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Track", style = MaterialTheme.typography.labelMedium)
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Video Track") }, leadingIcon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(TrackType.Video); expanded = false })
                    DropdownMenuItem(text = { Text("Audio Track") }, leadingIcon = { Icon(Icons.Default.Audiotrack, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(TrackType.Audio); expanded = false })
                    DropdownMenuItem(text = { Text("Text Track") }, leadingIcon = { Icon(Icons.Default.TextFields, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(TrackType.Text); expanded = false })
                }
            }
        }
    }
}

private fun formatRulerTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "%d:%02d.%d".format(min, sec, tenths) else "%d.%ds".format(sec, tenths)
}
