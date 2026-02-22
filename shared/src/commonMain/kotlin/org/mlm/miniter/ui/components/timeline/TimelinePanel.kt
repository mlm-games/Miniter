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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
    snapIndicatorMs: Long?,
    onPlayheadChange: (Long) -> Unit,
    onClipSelected: (String?) -> Unit,
    onBeginEdit: () -> Unit,
    onClipMoveAbsolute: (clipId: String, absoluteStartMs: Long) -> Unit,
    onClipMoveToTrack: (clipId: String, fromTrackId: String, toTrackId: String) -> Unit,
    onCommitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onClipTrimStartAbsolute: (clipId: String, newStartMs: Long) -> Unit,
    onClipTrimEndAbsolute: (clipId: String, newEndMs: Long) -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onAddTrack: (TrackType) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onSnapPosition: (Long, String?) -> Long,
    onClearSnap: () -> Unit = {},
    onSplitClip: (String) -> Unit = {},
    onDuplicateClip: (String) -> Unit = {},
    onDeleteClip: (String) -> Unit = {},
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
        tracks.flatMap { it.clips }.maxOfOrNull { it.startMs + it.durationMs } ?: 0L,
    ) + 5000L
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

    val playheadColor = MaterialTheme.colorScheme.error
    val snapLineColor = Color.Cyan

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

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            tracks.forEach { track ->
                TrackRow(
                    track = track,
                    allTracks = tracks,
                    dpPerMs = dpPerMs,
                    totalContentWidthDp = totalContentWidthDp,
                    playheadMs = playheadMs,
                    selectedClipId = selectedClipId,
                    snapIndicatorMs = snapIndicatorMs,
                    scrollState = horizontalScrollState,
                    canRemove = !(track.type == TrackType.Video &&
                            tracks.count { it.type == TrackType.Video } <= 1),
                    playheadColor = playheadColor,
                    snapLineColor = snapLineColor,
                    onClipSelected = onClipSelected,
                    onBeginEdit = onBeginEdit,
                    onClipMoveAbsolute = onClipMoveAbsolute,
                    onClipMoveToTrack = onClipMoveToTrack,
                    onCommitEdit = onCommitEdit,
                    onCancelEdit = onCancelEdit,
                    onClipTrimStartAbsolute = onClipTrimStartAbsolute,
                    onClipTrimEndAbsolute = onClipTrimEndAbsolute,
                    onToggleMute = { onToggleMute(track.id) },
                    onToggleLock = { onToggleLock(track.id) },
                    onRemoveTrack = { onRemoveTrack(track.id) },
                    onSnapPosition = onSnapPosition,
                    onClearSnap = onClearSnap,
                    onPlayheadChange = onPlayheadChange,
                    onSplitClip = onSplitClip,
                    onDuplicateClip = onDuplicateClip,
                    onDeleteClip = onDeleteClip,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            AddTrackRow(onAddTrack = onAddTrack)
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
            Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text("Time", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp))
        }
        VerticalDivider(thickness = 0.5.dp)
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .horizontalScroll(scrollState)
                .pointerInput(dpPerMs) {
                    detectTapGestures { offset -> onTap(offset.x / density.density) }
                }
        ) {
            Canvas(Modifier.width((timelineDurationMs * dpPerMs).dp).fillMaxHeight()) {
                val h = size.height
                val majorMs = when {
                    dpPerMs >= 0.5f -> 1_000L; dpPerMs >= 0.2f -> 2_000L
                    dpPerMs >= 0.1f -> 5_000L; dpPerMs >= 0.05f -> 10_000L
                    else -> 30_000L
                }
                val minorMs = majorMs / 5
                var ms = 0L
                while (ms <= timelineDurationMs) {
                    val x = ms * dpPerMs * density.density
                    if (ms % majorMs == 0L) {
                        drawLine(rulerLineColor, Offset(x, h * 0.4f), Offset(x, h), 1.5f)
                        val result = textMeasurer.measure(
                            formatRulerTime(ms),
                            TextStyle(fontSize = 10.sp, color = rulerTextColor)
                        )
                        drawText(result, topLeft = Offset(x + 3f, 2f))
                    } else {
                        drawLine(rulerLineColor.copy(0.4f), Offset(x, h * 0.7f), Offset(x, h), 0.5f)
                    }
                    ms += minorMs
                }
                val phX = playheadMs * dpPerMs * density.density
                val hs = PLAYHEAD_HEAD_SIZE.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(phX - hs / 2, 0f); lineTo(phX + hs / 2, 0f); lineTo(phX, hs); close()
                }
                drawPath(path, playheadColor)
                drawLine(playheadColor, Offset(phX, hs), Offset(phX, h), 2f)
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    allTracks: List<Track>,
    dpPerMs: Float,
    totalContentWidthDp: Dp,
    playheadMs: Long,
    selectedClipId: String?,
    snapIndicatorMs: Long?,
    scrollState: ScrollState,
    canRemove: Boolean,
    playheadColor: Color,
    snapLineColor: Color,
    onClipSelected: (String?) -> Unit,
    onBeginEdit: () -> Unit,
    onClipMoveAbsolute: (String, Long) -> Unit,
    onClipMoveToTrack: (String, String, String) -> Unit,
    onCommitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onClipTrimStartAbsolute: (String, Long) -> Unit,
    onClipTrimEndAbsolute: (String, Long) -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onRemoveTrack: () -> Unit,
    onSnapPosition: (Long, String?) -> Long,
    onClearSnap: () -> Unit,
    onPlayheadChange: (Long) -> Unit,
    onSplitClip: (String) -> Unit,
    onDuplicateClip: (String) -> Unit,
    onDeleteClip: (String) -> Unit,
) {
    val density = LocalDensity.current

    Row(modifier = Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
        TrackHeader(track, canRemove, onToggleMute, onToggleLock, onRemoveTrack,
            Modifier.width(TRACK_HEADER_WIDTH).fillMaxHeight())
        VerticalDivider(thickness = 0.5.dp)

        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .horizontalScroll(scrollState)
                .pointerInput(dpPerMs) {
                    detectTapGestures { onClipSelected(null) }
                }
        ) {
            Box(Modifier.width(totalContentWidthDp).fillMaxHeight()) {
                val sameTypeUnlocked = allTracks
                    .filter { it.type == track.type && !it.isLocked }
                    .map { it.id }
                val sameTypeIndex = sameTypeUnlocked.indexOf(track.id)

                track.clips.forEach { clip ->
                    val leftDp = (clip.startMs * dpPerMs).dp
                    val widthDp = (clip.durationMs * dpPerMs).dp.coerceAtLeast(MIN_CLIP_WIDTH_DP.dp)
                    val isSelected = clip.id == selectedClipId

                    ClipBlock(
                        clip = clip,
                        trackType = track.type,
                        widthDp = widthDp,
                        isSelected = isSelected,
                        isLocked = track.isLocked,
                        isMuted = track.isMuted,
                        dpPerMs = dpPerMs,
                        playheadMs = playheadMs,
                        modifier = Modifier.offset(x = leftDp).fillMaxHeight().padding(vertical = 2.dp),
                        onTap = { onClipSelected(clip.id) },
                        onBeginEdit = onBeginEdit,
                        onDragAbsolute = { absoluteMs ->
                            val snapped = onSnapPosition(absoluteMs, clip.id)
                            onClipMoveAbsolute(clip.id, snapped)
                        },
                        onDragEnd = { onCommitEdit(); onClearSnap() },
                        onDragCancel = { onCancelEdit(); onClearSnap() },
                        onTrimStartAbsolute = { newStartMs ->
                            onClipTrimStartAbsolute(clip.id, newStartMs)
                        },
                        onTrimEndAbsolute = { newEndMs ->
                            onClipTrimEndAbsolute(clip.id, newEndMs)
                        },
                        onTrimEnd = { onCommitEdit() },
                        onTrimCancel = { onCancelEdit() },
                        onSetPlayhead = { onPlayheadChange(clip.startMs) },
                        onSplit = { onSplitClip(clip.id) },
                        onDuplicate = { onDuplicateClip(clip.id) },
                        onDelete = { onDeleteClip(clip.id) },
                        trackId = track.id,
                        sameTypeIndex = sameTypeIndex,
                        sameTypeTrackIds = sameTypeUnlocked,
                        onMoveToTrack = { toTrackId ->
                            onClipMoveToTrack(clip.id, track.id, toTrackId)
                        },
                    )
                }

                Box(
                    Modifier.offset(x = (playheadMs * dpPerMs).dp - 0.5.dp)
                        .width(1.5.dp).fillMaxHeight().background(playheadColor)
                )

                if (snapIndicatorMs != null) {
                    val snapDp = (snapIndicatorMs * dpPerMs).dp
                    Canvas(
                        Modifier.offset(x = snapDp - 0.5.dp).width(1.dp).fillMaxHeight()
                    ) {
                        drawLine(
                            snapLineColor, Offset(0f, 0f), Offset(0f, size.height),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                        )
                    }
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
    playheadMs: Long,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onBeginEdit: () -> Unit,
    onDragAbsolute: (absoluteStartMs: Long) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTrimStartAbsolute: (newStartMs: Long) -> Unit,
    onTrimEndAbsolute: (newEndMs: Long) -> Unit,
    onTrimEnd: () -> Unit,
    onTrimCancel: () -> Unit,
    onSetPlayhead: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    trackId: String,
    sameTypeIndex: Int,
    sameTypeTrackIds: List<String>,
    onMoveToTrack: (toTrackId: String) -> Unit,
) {
    val density = LocalDensity.current

    val currentStartMs by rememberUpdatedState(clip.startMs)
    val currentDurationMs by rememberUpdatedState(clip.durationMs)
    val currentTrackId by rememberUpdatedState(trackId)
    val currentSameTypeIndex by rememberUpdatedState(sameTypeIndex)
    val currentSameTypeTrackIds by rememberUpdatedState(sameTypeTrackIds)
    val currentOnMoveToTrack by rememberUpdatedState(onMoveToTrack)

    val bgColor = when (trackType) {
        TrackType.Video -> MaterialTheme.colorScheme.primaryContainer
        TrackType.Audio -> MaterialTheme.colorScheme.secondaryContainer
        TrackType.Text -> MaterialTheme.colorScheme.tertiaryContainer
    }.let { if (isMuted) it.copy(alpha = 0.5f) else it }

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val trimHandleColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    val onContainerColor = when (trackType) {
        TrackType.Video -> MaterialTheme.colorScheme.onPrimaryContainer
        TrackType.Audio -> MaterialTheme.colorScheme.onSecondaryContainer
        TrackType.Text -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    val clipLabel = when (clip) {
        is Clip.VideoClip -> clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")
        is Clip.AudioClip -> clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")
        is Clip.TextClip -> "T: ${clip.text}"
    }

    val canSplit = clip is Clip.VideoClip &&
            playheadMs > clip.startMs &&
            playheadMs < clip.startMs + clip.durationMs

    var showContextMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier.width(widthDp)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (!isLocked && clip is Clip.VideoClip) TRIM_HANDLE_WIDTH else 0.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(if (isSelected) 2.dp else 0.dp, borderColor, RoundedCornerShape(6.dp))
                .pointerInput(clip.id) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { showContextMenu = true },
                    )
                }
                .pointerInput(clip.id, dpPerMs, isLocked) {
                    if (isLocked) return@pointerInput
                    var dragStartMs = 0L
                    var totalDragPxX = 0f
                    var totalDragPxY = 0f

                    val trackHeightPx = with(density) { TRACK_HEIGHT.toPx() }

                    detectDragGestures(
                        onDragStart = {
                            dragStartMs = currentStartMs
                            totalDragPxX = 0f
                            totalDragPxY = 0f
                            onBeginEdit()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragPxX += dragAmount.x
                            totalDragPxY += dragAmount.y

                            val totalDeltaMs = (totalDragPxX / density.density / dpPerMs).toLong()
                            onDragAbsolute((dragStartMs + totalDeltaMs).coerceAtLeast(0))
                        },
                        onDragEnd = {
                            if (currentSameTypeTrackIds.isEmpty()) {
                                onDragEnd()
                                return@detectDragGestures
                            }
                            val shift = (totalDragPxY / trackHeightPx).toInt()
                            val targetIndex = (currentSameTypeIndex + shift).coerceIn(0, currentSameTypeTrackIds.lastIndex)
                            val targetTrackId = currentSameTypeTrackIds.getOrNull(targetIndex)

                            if (targetTrackId != null && targetTrackId != currentTrackId) {
                                currentOnMoveToTrack(targetTrackId)
                            }

                            onDragEnd()
                        },
                        onDragCancel = onDragCancel,
                    )
                },
            color = bgColor,
            shape = RoundedCornerShape(6.dp),
            tonalElevation = if (isSelected) 4.dp else 1.dp,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (clip is Clip.VideoClip && clip.speed != 1f) {
                    Text("${clip.speed}x", style = MaterialTheme.typography.labelSmall,
                        color = onContainerColor.copy(alpha = 0.7f), fontSize = 8.sp)
                    Spacer(Modifier.width(2.dp))
                }
                Text(clipLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = onContainerColor,
                    modifier = Modifier.weight(1f))
                if (clip is Clip.VideoClip && clip.filters.isNotEmpty()) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${clip.filters.size}", fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onTertiary)
                        }
                    }
                }
                if (isMuted) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, null,
                        Modifier.size(10.dp), tint = onContainerColor.copy(0.5f))
                }
            }
        }

        if (!isLocked && clip is Clip.VideoClip) {
            Box(
                Modifier.align(Alignment.CenterStart).width(TRIM_HANDLE_WIDTH).fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(trimHandleColor)
                    .pointerInput(clip.id, dpPerMs) {
                        var trimStartMs = 0L
                        var totalTrimPx = 0f
                        detectDragGestures(
                            onDragStart = {
                                trimStartMs = currentStartMs
                                totalTrimPx = 0f
                                onBeginEdit()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalTrimPx += dragAmount.x
                                val deltaMsFromStart = (totalTrimPx / density.density / dpPerMs).toLong()
                                onTrimStartAbsolute(trimStartMs + deltaMsFromStart)
                            },
                            onDragEnd = onTrimEnd,
                            onDragCancel = onTrimCancel,
                        )
                    },
            )
        }

        if (!isLocked && clip is Clip.VideoClip) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(TRIM_HANDLE_WIDTH).fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(trimHandleColor)
                    .pointerInput(clip.id, dpPerMs) {
                        var trimEndMs = 0L
                        var totalTrimPx = 0f
                        detectDragGestures(
                            onDragStart = {
                                trimEndMs = currentStartMs + currentDurationMs
                                totalTrimPx = 0f
                                onBeginEdit()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalTrimPx += dragAmount.x
                                val deltaMsFromStart = (totalTrimPx / density.density / dpPerMs).toLong()
                                onTrimEndAbsolute(trimEndMs + deltaMsFromStart)
                            },
                            onDragEnd = onTrimEnd,
                            onDragCancel = onTrimCancel,
                        )
                    },
            )
        }

        ClipContextMenu(
            expanded = showContextMenu,
            clip = clip,
            canSplit = canSplit,
            isLocked = isLocked,
            onDismiss = { showContextMenu = false },
            onSplit = onSplit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onSetAsPlayhead = onSetPlayhead,
        )
    }
}

@Composable
private fun TrackHeader(
    track: Track, canRemove: Boolean,
    onToggleMute: () -> Unit, onToggleLock: () -> Unit, onRemoveTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (track.type) { TrackType.Video -> Icons.Default.Videocam
                    TrackType.Audio -> Icons.Default.Audiotrack; TrackType.Text -> Icons.Default.TextFields },
                null, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(3.dp))
            Text(track.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleMute, modifier = Modifier.size(18.dp)) {
                Icon(if (track.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                    "Mute", Modifier.size(12.dp),
                    if (track.isMuted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(18.dp)) {
                Icon(if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    "Lock", Modifier.size(12.dp),
                    if (track.isLocked) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canRemove) {
                IconButton(onClick = onRemoveTrack, modifier = Modifier.size(18.dp)) {
                    Icon(Icons.Default.Close, "Remove", Modifier.size(12.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                }
            }
        }
    }
}

@Composable
private fun AddTrackRow(onAddTrack: (TrackType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().height(ADD_TRACK_ROW_HEIGHT),
        color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Track", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Video Track") },
                        leadingIcon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(TrackType.Video); expanded = false })
                    DropdownMenuItem(text = { Text("Audio Track") },
                        leadingIcon = { Icon(Icons.Default.Audiotrack, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(TrackType.Audio); expanded = false })
                    DropdownMenuItem(text = { Text("Text Track") },
                        leadingIcon = { Icon(Icons.Default.TextFields, null, Modifier.size(18.dp)) },
                        onClick = { onAddTrack(TrackType.Text); expanded = false })
                }
            }
        }
    }
}

private fun formatRulerTime(ms: Long): String {
    val totalSec = ms / 1000; val min = totalSec / 60; val sec = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (min > 0) "%d:%02d.%d".format(min, sec, tenths) else "%d.%ds".format(sec, tenths)
}
