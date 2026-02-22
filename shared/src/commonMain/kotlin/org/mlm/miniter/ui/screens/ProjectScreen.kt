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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile
import org.koin.compose.koinInject
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.nav.Route
import org.mlm.miniter.ui.components.properties.PropertiesPanel
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.ui.components.timeline.TimelinePanel
import org.mlm.miniter.ui.components.toolbar.EditorToolbar
import org.mlm.miniter.ui.util.popBack
import org.mlm.miniter.viewmodel.ProjectViewModel
import java.io.File

@Composable
fun ProjectScreen(
    videoPath: String,
    videoName: String,
    backStack: NavBackStack<NavKey>,
    projectViewModel: ProjectViewModel = koinInject(),
) {
    val snackbarManager: SnackbarManager = koinInject()
    val uiState by projectViewModel.state.collectAsState()
    val playerState = rememberVideoPlayerState()

    LaunchedEffect(videoPath) {
        if (uiState.project == null) {
            if (videoPath.endsWith(".mntr")) {
                projectViewModel.loadProject(videoPath)
            } else {
                projectViewModel.newProject(videoName, videoPath)
            }
        }
    }

    LaunchedEffect(videoPath) {
        if (!videoPath.endsWith(".mntr")) {
            playerState.openFile(PlatformFile(File(videoPath)))
        }
    }

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
                    IconButton(onClick = { backStack.popBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val path = uiState.projectPath
                                ?: "${videoPath.substringBeforeLast(".")}.mntr"
                            projectViewModel.saveProject(path)
                            snackbarManager.show("Project saved")
                        },
                        enabled = uiState.isDirty,
                    ) {
                        Icon(Icons.Default.Save, "Save Project")
                    }

                    IconButton(onClick = {
                        val projectPath = uiState.projectPath ?: videoPath
                        backStack.add(Route.Export(projectPath))
                    }) {
                        Icon(Icons.Default.FileDownload, "Export")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                        onPlayPause = {
                            val nowPlaying = !uiState.isPlaying
                            projectViewModel.setPlaying(nowPlaying)
                            if (nowPlaying) playerState.play() else playerState.pause()
                        },
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
                        onSetSpeed = { clipId, speed ->
                            projectViewModel.setClipSpeed(clipId, speed)
                        },
                        onSetTransition = { clipId, transition ->
                            projectViewModel.setTransition(clipId, transition)
                        },
                    )
                }
            }

            EditorToolbar(
                isPlaying = uiState.isPlaying,
                onPlayPause = {
                    val nowPlaying = !uiState.isPlaying
                    projectViewModel.setPlaying(nowPlaying)
                    if (nowPlaying) playerState.play() else playerState.pause()
                },
                onSplit = {
                    uiState.selectedClipId?.let { projectViewModel.splitClipAtPlayhead(it) }
                },
                onDelete = {
                    uiState.selectedClipId?.let {
                        projectViewModel.removeClip(it)
                        projectViewModel.selectClip(null)
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
                    selectedClipId = uiState.selectedClipId,
                    onPlayheadChange = { projectViewModel.setPlayhead(it) },
                    onClipSelected = { projectViewModel.selectClip(it) },
                    onClipMoved = { clipId, newStart ->
                        projectViewModel.moveClip(clipId, newStart)
                    },
                    onToggleMute = { projectViewModel.toggleTrackMute(it) },
                    onToggleLock = { projectViewModel.toggleTrackLock(it) },
                )
            }
        }
    }
}

@Composable
private fun VideoPreviewPanel(
    playerState: io.github.kdroidfilter.composemediaplayer.VideoPlayerState,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
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
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playerState.seekTo(0f) }) {
                Icon(Icons.Default.SkipPrevious, "Rewind to start")
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { /* TODO: seek forward 5s */ }) {
                Icon(Icons.Default.Forward5, "Forward 5s")
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = "${playerState.positionText} / ${playerState.durationText}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThumbnailStrip(
    thumbnails: List<org.mlm.miniter.engine.ImageData>,
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
