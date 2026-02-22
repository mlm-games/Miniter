package org.mlm.miniter.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import org.koin.compose.koinInject
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.nav.Route
import org.mlm.miniter.project.Clip
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.ui.components.dialogs.ConfirmDialog
import org.mlm.miniter.ui.components.dialogs.SaveProjectDialog
import org.mlm.miniter.ui.components.dialogs.ShortcutHelpDialog
import org.mlm.miniter.ui.components.properties.PropertiesPanel
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.ui.components.timeline.TimelinePanel
import org.mlm.miniter.ui.components.toolbar.EditorToolbar
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectUiState
import org.mlm.miniter.viewmodel.ProjectViewModel

@Composable
fun ProjectScreen(
    videoPath: String,
    videoName: String,
    backStack: NavBackStack<NavKey>,
    projectViewModel: ProjectViewModel = koinInject(),
    savePath: String? = null,
) {
    val snackbarManager: SnackbarManager = koinInject()
    val uiState by projectViewModel.state.collectAsState()
    val playerState = rememberVideoPlayerState()

    LaunchedEffect(videoPath) {
        if (uiState.project == null) {
            if (videoPath.endsWith(".mntr")) {
                projectViewModel.loadProject(videoPath)
            } else {
                projectViewModel.newProject(videoName, videoPath, savePath)
            }
        }
    }

    LaunchedEffect(videoPath) {
        if (!videoPath.endsWith(".mntr")) {
            playerState.openUri(videoPath, InitialPlayerState.PAUSE)
        }
    }

    LaunchedEffect(playerState, uiState.isPlaying) {
        while (uiState.isPlaying) {
            projectViewModel.onPlayerPositionChanged(playerState.sliderPos)
            delay(50)
        }
    }

    LaunchedEffect(playerState.isPlaying) {
        if (!playerState.isPlaying && uiState.isPlaying) {
            projectViewModel.onPlayerCompleted()
        }
    }

    val currentSpeed = projectViewModel.getCurrentClipSpeed()
    LaunchedEffect(currentSpeed) {
        playerState.playbackSpeed = currentSpeed.coerceIn(0.5f, 2.0f)
    }

    val currentVolume = projectViewModel.getCurrentClipVolume()
    LaunchedEffect(currentVolume) {
        playerState.volume = currentVolume.coerceIn(0f, 1f)
    }

    var showShortcutHelp by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showShortcutHelp) {
        ShortcutHelpDialog(onDismiss = { showShortcutHelp = false })
    }

    if (showSaveAsDialog) {
        SaveProjectDialog(
            defaultName = uiState.project?.name ?: videoName,
            onDismiss = { showSaveAsDialog = false },
            onSave = { projectName, path ->
                showSaveAsDialog = false
                projectViewModel.renameProject(projectName)
                projectViewModel.saveProject(path)
                snackbarManager.show("Project saved")
            },
        )
    }

    if (showUnsavedDialog) {
        ConfirmDialog(
            title = "Unsaved Changes",
            message = "You have unsaved changes. Are you sure you want to leave?",
            confirmText = "Leave",
            dismissText = "Stay",
            onConfirm = {
                showUnsavedDialog = false
                playerState.pause()
                projectViewModel.setPlaying(false)
                backStack.popBack()
            },
            onDismiss = { showUnsavedDialog = false },
        )
    }

    val togglePlayPause: () -> Unit = {
        val nowPlaying = !uiState.isPlaying
        projectViewModel.setPlaying(nowPlaying)
        if (nowPlaying) playerState.play() else playerState.pause()
    }

    val seekToMs: (Long) -> Unit = { ms ->
        projectViewModel.setPlayhead(ms)
        val totalMs = uiState.project?.timeline?.durationMs ?: 1L
        val sliderPos = (ms.toFloat() / totalMs * 1000f).coerceIn(0f, 1000f)
        playerState.seekTo(sliderPos)
    }

    val seekForward5s: () -> Unit = {
        val newMs = (uiState.playheadMs + 5000).coerceAtMost(
            uiState.project?.timeline?.durationMs ?: Long.MAX_VALUE
        )
        seekToMs(newMs)
    }

    val seekBackward5s: () -> Unit = {
        val newMs = (uiState.playheadMs - 5000).coerceAtLeast(0)
        seekToMs(newMs)
    }

    val selectedClip = uiState.project?.timeline?.tracks
        ?.flatMap { it.clips }
        ?.find { it.id == uiState.selectedClipId }

    val canSplit = selectedClip != null &&
            selectedClip is Clip.VideoClip &&
            uiState.playheadMs > selectedClip.startMs &&
            uiState.playheadMs < selectedClip.startMs + selectedClip.durationMs

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            uiState.project?.name ?: videoName,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (uiState.isDirty) {
                            Text(
                                "Unsaved changes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isDirty) {
                            showUnsavedDialog = true
                        } else {
                            playerState.pause()
                            projectViewModel.setPlaying(false)
                            backStack.popBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val files = FileKit.openFilePicker(
                                type = FileKitType.File(
                                    extensions = listOf(
                                        "mp4", "mkv", "mov", "webm", "avi",
                                        "mp3", "wav", "m4a", "aac", "ogg", "flac"
                                    )
                                ),
                                mode = FileKitMode.Multiple(maxItems = 50),
                                title = "Import media"
                            )
                            val list = when (files) {
                                null -> emptyList()
                                is List<*> -> files.filterIsInstance<PlatformFile>()
                                else -> emptyList()
                            }
                            if (list.isNotEmpty()) {
                                projectViewModel.importMediaFiles(list)
                            }
                        }
                    }) {
                        Icon(Icons.Default.Add, "Import media")
                    }
                    IconButton(
                        onClick = { projectViewModel.undo() },
                        enabled = uiState.canUndo,
                    ) {
                        Icon(Icons.Default.Undo, "Undo")
                    }
                    IconButton(
                        onClick = { projectViewModel.redo() },
                        enabled = uiState.canRedo,
                    ) {
                        Icon(Icons.Default.Redo, "Redo")
                    }
                    IconButton(
                        onClick = {
                            val path = uiState.projectPath
                            if (path != null) {
                                projectViewModel.saveProject(path)
                                snackbarManager.show("Project saved")
                            } else {
                                showSaveAsDialog = true
                            }
                        },
                        enabled = uiState.isDirty,
                    ) {
                        Icon(Icons.Default.Save, "Save Project")
                    }
                    IconButton(onClick = {
                        showSaveAsDialog = true
                    }) {
                        Icon(Icons.Default.SaveAs, "Save As")
                    }
                    IconButton(onClick = {
                        playerState.pause()
                        projectViewModel.setPlaying(false)
                        val projectPath = uiState.projectPath ?: videoPath
                        backStack.add(Route.Export(projectPath))
                    }) {
                        Icon(Icons.Default.FileDownload, "Export")
                    }
                    IconButton(onClick = { showShortcutHelp = true }) {
                        Icon(Icons.Default.HelpOutline, "Shortcuts")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Spacebar -> { togglePlayPause(); true }
                        event.key == Key.S && !event.isCtrlPressed -> {
                            if (canSplit) {
                                uiState.selectedClipId?.let {
                                    projectViewModel.splitClipAtPlayhead(it)
                                }
                            }
                            true
                        }
                        event.key == Key.Delete || event.key == Key.Backspace -> {
                            uiState.selectedClipId?.let {
                                projectViewModel.removeClip(it)
                                projectViewModel.selectClip(null)
                            }
                            true
                        }
                        event.key == Key.D && !event.isCtrlPressed -> {
                            uiState.selectedClipId?.let {
                                projectViewModel.duplicateClip(it)
                            }
                            true
                        }
                        event.key == Key.T && !event.isCtrlPressed -> {
                            projectViewModel.addTextClip("text-0", "New Text", uiState.playheadMs)
                            true
                        }
                        event.key == Key.S && event.isCtrlPressed -> {
                            val path = uiState.projectPath
                            if (path != null) {
                                projectViewModel.saveProject(path)
                                snackbarManager.show("Project saved")
                            } else {
                                showSaveAsDialog = true
                            }
                            true
                        }
                        event.key == Key.Z && event.isCtrlPressed && !event.isShiftPressed -> {
                            projectViewModel.undo(); true
                        }
                        event.key == Key.Z && event.isCtrlPressed && event.isShiftPressed -> {
                            projectViewModel.redo(); true
                        }
                        event.key == Key.Slash && event.isShiftPressed -> {
                            showShortcutHelp = true; true
                        }
                        event.key == Key.DirectionLeft -> {
                            val step = if (event.isShiftPressed) 5000L else 1000L
                            seekToMs((uiState.playheadMs - step).coerceAtLeast(0))
                            true
                        }
                        event.key == Key.DirectionRight -> {
                            val step = if (event.isShiftPressed) 5000L else 1000L
                            val maxMs = uiState.project?.timeline?.durationMs ?: Long.MAX_VALUE
                            seekToMs((uiState.playheadMs + step).coerceAtMost(maxMs))
                            true
                        }
                        event.key == Key.Home -> { seekToMs(0); true }
                        event.key == Key.Period -> { // End key - using period for now
                            seekToMs(uiState.project?.timeline?.durationMs ?: 0L); true
                        }
                        event.key == Key.Plus || event.key == Key.Equals -> {
                            projectViewModel.setZoom(uiState.zoomLevel * 1.5f); true
                        }
                        event.key == Key.Minus -> {
                            projectViewModel.setZoom(uiState.zoomLevel / 1.5f); true
                        }
                        event.key == Key.Escape -> { projectViewModel.selectClip(null); true }
                        else -> false
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    VideoPreviewPanel(
                        playerState = playerState,
                        isPlaying = uiState.isPlaying,
                        playheadMs = uiState.playheadMs,
                        durationMs = uiState.project?.timeline?.durationMs ?: 0L,
                        visibleTextClips = projectViewModel.getVisibleTextClips(),
                        currentFilters = getCurrentClipFilters(uiState),
                        onPlayPause = togglePlayPause,
                        onSeekToStart = { seekToMs(0) },
                        onSeekForward5s = seekForward5s,
                        onSeekBackward5s = seekBackward5s,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    PropertiesPanel(
                        project = uiState.project,
                        selectedClipId = uiState.selectedClipId,
                        onAddFilter = { clipId, filter ->
                            projectViewModel.addFilter(clipId, filter)
                        },
                        onRemoveFilter = { clipId, index ->
                            projectViewModel.removeFilter(clipId, index)
                        },
                        onUpdateFilterParams = { clipId, filterIndex, params ->
                            projectViewModel.updateFilterParams(clipId, filterIndex, params)
                        },
                        onSetSpeed = { clipId, speed ->
                            projectViewModel.setClipSpeed(clipId, speed)
                        },
                        onSetVolume = { clipId, volume ->
                            projectViewModel.setClipVolume(clipId, volume)
                        },
                        onSetTransition = { clipId, transition ->
                            projectViewModel.setTransition(clipId, transition)
                        },
                        onUpdateText = { clipId, text ->
                            projectViewModel.updateTextClip(clipId, text)
                        },
                        onUpdateTextStyle = { clipId, fontSize, color ->
                            projectViewModel.updateTextClipStyle(
                                clipId,
                                fontSizeSp = fontSize,
                                colorHex = color,
                                backgroundColorHex = null,
                                positionX = null,
                                positionY = null,
                            )
                        },
                        onSetOpacity = { clipId, opacity ->
                            projectViewModel.setClipOpacity(clipId, opacity)
                        },
                        onSetTextDuration = { clipId, durationMs ->
                            projectViewModel.setTextClipDuration(clipId, durationMs)
                        },
                    )
                }
            }

            EditorToolbar(
                isPlaying = uiState.isPlaying,
                hasSelection = uiState.selectedClipId != null,
                canSplit = canSplit,
                onPlayPause = togglePlayPause,
                onSplit = {
                    uiState.selectedClipId?.let {
                        projectViewModel.splitClipAtPlayhead(it)
                    }
                },
                onDelete = {
                    uiState.selectedClipId?.let {
                        projectViewModel.removeClip(it)
                        projectViewModel.selectClip(null)
                    }
                },
                onDuplicate = {
                    uiState.selectedClipId?.let {
                        projectViewModel.duplicateClip(it)
                    }
                },
                onAddText = {
                    projectViewModel.addTextClip(
                        trackId = "text-0",
                        text = "New Text",
                        startMs = uiState.playheadMs,
                    )
                },
                onZoomIn = { projectViewModel.setZoom(uiState.zoomLevel * 1.5f) },
                onZoomOut = { projectViewModel.setZoom(uiState.zoomLevel / 1.5f) },
                onZoomFit = {
                    val duration = uiState.project?.timeline?.durationMs ?: 30_000L
                    val targetDp = 480f
                    val newZoom = (targetDp / (duration * 0.1f)).coerceIn(0.1f, 10f)
                    projectViewModel.setZoom(newZoom)
                },
                zoomPercent = (uiState.zoomLevel * 100).toInt(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.thumbnails.isNotEmpty()) {
                ThumbnailStrip(
                    thumbnails = uiState.thumbnails,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .background(MaterialTheme.colorScheme.surfaceDim),
            ) {
                TimelinePanel(
                    project = uiState.project,
                    playheadMs = uiState.playheadMs,
                    zoomLevel = uiState.zoomLevel,
                    isPlaying = uiState.isPlaying,
                    selectedClipId = uiState.selectedClipId,
                    snapIndicatorMs = uiState.snapIndicatorMs,
                    onPlayheadChange = { ms -> seekToMs(ms) },
                    onClipSelected = { projectViewModel.selectClip(it) },
                    onBeginEdit = { projectViewModel.beginContinuousEdit() },
                    onClipMoveAbsolute = { clipId, absMs ->
                        projectViewModel.moveClipAbsolute(clipId, absMs)
                    },
                    onClipMoveToTrack = { clipId, from, to ->
                        projectViewModel.moveClipToTrack(clipId, from, to)
                    },
                    onCommitEdit = { projectViewModel.commitContinuousEdit() },
                    onCancelEdit = { projectViewModel.cancelContinuousEdit() },
                    onClipTrimStartAbsolute = { clipId, newStart ->
                        projectViewModel.trimClipStartAbsolute(clipId, newStart)
                    },
                    onClipTrimEndAbsolute = { clipId, newEnd ->
                        projectViewModel.trimClipEndAbsolute(clipId, newEnd)
                    },
                    onToggleMute = { projectViewModel.toggleTrackMute(it) },
                    onToggleLock = { projectViewModel.toggleTrackLock(it) },
                    onAddTrack = { type -> projectViewModel.addTrack(type) },
                    onRemoveTrack = { trackId -> projectViewModel.removeTrack(trackId) },
                    onSnapPosition = { ms, excludeId ->
                        projectViewModel.snapPosition(ms, excludeId)
                    },
                    onClearSnap = { projectViewModel.clearSnapIndicator() },
                    onSplitClip = { clipId ->
                        projectViewModel.splitClipAtPlayhead(clipId)
                    },
                    onDuplicateClip = { clipId ->
                        projectViewModel.duplicateClip(clipId)
                    },
                    onDeleteClip = { clipId ->
                        projectViewModel.removeClip(clipId)
                        projectViewModel.selectClip(null)
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoPreviewPanel(
    playerState: VideoPlayerState,
    isPlaying: Boolean,
    playheadMs: Long,
    durationMs: Long,
    visibleTextClips: List<Clip.TextClip>,
    currentFilters: List<FilterType>,
    onPlayPause: () -> Unit,
    onSeekToStart: () -> Unit,
    onSeekForward5s: () -> Unit,
    onSeekBackward5s: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize(),
            )

            visibleTextClips.forEach { textClip ->
                TextOverlay(textClip)
            }

            if (currentFilters.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        text = "${currentFilters.size} filter${if (currentFilters.size > 1) "s" else ""} (preview at export)",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Slider(
            value = playerState.sliderPos,
            onValueChange = { newPos ->
                playerState.sliderPos = newPos
                playerState.userDragging = true
            },
            onValueChangeFinished = {
                playerState.userDragging = false
                playerState.seekTo(playerState.sliderPos)
            },
            valueRange = 0f..1000f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSeekToStart) {
                Icon(Icons.Default.SkipPrevious, "Rewind to start")
            }
            IconButton(onClick = onSeekBackward5s) {
                Icon(Icons.Default.Replay5, "Back 5s")
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onSeekForward5s) {
                Icon(Icons.Default.Forward5, "Forward 5s")
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = "${formatTime(playheadMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoxScope.TextOverlay(textClip: Clip.TextClip) {
    val bgColor = textClip.backgroundColorHex?.let { parseColor(it) }
    val textColor = parseColor(textClip.colorHex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        Text(
            text = textClip.text,
            color = textColor,
            fontSize = textClip.fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(
                    when {
                        textClip.positionY < 0.33f -> {
                            when {
                                textClip.positionX < 0.33f -> Alignment.TopStart
                                textClip.positionX > 0.66f -> Alignment.TopEnd
                                else -> Alignment.TopCenter
                            }
                        }
                        textClip.positionY > 0.66f -> {
                            when {
                                textClip.positionX < 0.33f -> Alignment.BottomStart
                                textClip.positionX > 0.66f -> Alignment.BottomEnd
                                else -> Alignment.BottomCenter
                            }
                        }
                        else -> {
                            when {
                                textClip.positionX < 0.33f -> Alignment.CenterStart
                                textClip.positionX > 0.66f -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }
                        }
                    }
                )
                .then(
                    if (bgColor != null) {
                        Modifier.background(
                            bgColor.copy(alpha = 0.6f),
                            MaterialTheme.shapes.extraSmall,
                        ).padding(horizontal = 8.dp, vertical = 4.dp)
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

@Composable
private fun ThumbnailStrip(
    thumbnails: List<ImageData>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(thumbnails) { thumb ->
            val imageBitmap = remember(thumb) { thumb.toImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun getCurrentClipFilters(uiState: ProjectUiState): List<FilterType> {
    val project = uiState.project ?: return emptyList()
    val playhead = uiState.playheadMs
    return project.timeline.tracks
        .flatMap { it.clips }
        .filterIsInstance<Clip.VideoClip>()
        .firstOrNull { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
        ?.filters
        ?.map { it.type }
        ?: emptyList()
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

private fun parseColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> "FF$cleaned"
            8 -> cleaned
            else -> "FFFFFFFF"
        }
        Color(argb.toLong(16))
    } catch (_: Exception) {
        Color.White
    }
}
