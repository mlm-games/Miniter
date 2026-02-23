package org.mlm.miniter.ui.components.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.sample
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.project.*

@Composable
fun EditorVideoPreview(
    project: MinterProject?,
    playheadMs: Long,
    isPlaying: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onPlayheadChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailFallback: ImageData? = null,
) {
    val playerState = rememberVideoPlayerState()

    val currentClip = remember(project, playheadMs) {
        project?.timeline?.tracks
            ?.filter { it.type == TrackType.Video && !it.isMuted }
            ?.flatMap { it.clips.filterIsInstance<Clip.VideoClip>() }
            ?.find { clip -> playheadMs >= clip.startMs && playheadMs < clip.startMs + clip.durationMs }
    }

    val clipSourcePath = currentClip?.sourcePath
    val clipSpeed = currentClip?.speed ?: 1f
    val clipVolume = currentClip?.volume ?: 1f

    val fullFileDurationMs = remember(currentClip) {
        currentClip?.sourceEndMs ?: 0L
    }

    var lastLoadedPath by remember { mutableStateOf<String?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var editorIsSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(clipSourcePath) {
        if (clipSourcePath != null && clipSourcePath != lastLoadedPath) {
            playerReady = false
            lastLoadedPath = clipSourcePath

            playerState.openUri(clipSourcePath)
            delay(150)
            playerState.pause()

            playerReady = true
        }
    }

    LaunchedEffect(clipSpeed) {
        playerState.playbackSpeed = clipSpeed.coerceIn(0.25f, 4f)
    }

    LaunchedEffect(clipVolume) {
        playerState.volume = clipVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(isPlaying, playerReady) {
        if (!playerReady) return@LaunchedEffect
        if (isPlaying) {
            playerState.play()
        } else {
            playerState.pause()
        }
    }

    LaunchedEffect(playheadMs, isPlaying, playerReady, fullFileDurationMs) {
        if (isPlaying || !playerReady) return@LaunchedEffect
        if (currentClip == null || fullFileDurationMs <= 0L) return@LaunchedEffect

        val offsetInClip = playheadMs - currentClip.startMs
        val sourceTimeMs = currentClip.sourceStartMs + (offsetInClip * currentClip.speed).toLong()

        if (sourceTimeMs < 0 || sourceTimeMs > fullFileDurationMs) return@LaunchedEffect

        val sliderTarget = (sourceTimeMs.toFloat() / fullFileDurationMs * 1000f)
            .coerceIn(0f, 1000f)
        if (sliderTarget.isNaN() || sliderTarget.isInfinite()) return@LaunchedEffect

        editorIsSeeking = true

        playerState.sliderPos = sliderTarget
        playerState.userDragging = true
        delay(50)
        playerState.userDragging = false
        playerState.seekTo(playerState.sliderPos)
        delay(100)

        editorIsSeeking = false
    }

    LaunchedEffect(isPlaying, currentClip?.id, playerReady, fullFileDurationMs) {
        if (!isPlaying || currentClip == null || !playerReady) return@LaunchedEffect
        if (fullFileDurationMs <= 0L) return@LaunchedEffect

        val offsetInClip = playheadMs - currentClip.startMs
        val sourceTimeMs = currentClip.sourceStartMs + (offsetInClip * currentClip.speed).toLong()
        val sliderTarget = (sourceTimeMs.toFloat() / fullFileDurationMs * 1000f)
            .coerceIn(0f, 1000f)

        if (!sliderTarget.isNaN() && !sliderTarget.isInfinite()) {
            playerState.sliderPos = sliderTarget
            playerState.userDragging = true
            delay(30)
            playerState.userDragging = false
            playerState.seekTo(playerState.sliderPos)
            delay(80)
        }

        playerState.play()
    }

    LaunchedEffect(isPlaying, currentClip?.id, playerReady, fullFileDurationMs) {
        if (!isPlaying || currentClip == null || !playerReady) return@LaunchedEffect
        if (fullFileDurationMs <= 0L) return@LaunchedEffect

        snapshotFlow { playerState.sliderPos }
            .drop(1)
            .sample(50)
            .collectLatest { sliderPos ->
                if (editorIsSeeking) return@collectLatest

                val sliderFraction = sliderPos / 1000f
                val sourceMs = (sliderFraction * fullFileDurationMs).toLong()

                val offsetFromSourceStart = sourceMs - currentClip.sourceStartMs
                val timelineMs = currentClip.startMs +
                    (offsetFromSourceStart / currentClip.speed).toLong()

                val clipEndMs = currentClip.startMs + currentClip.durationMs

                if (timelineMs >= clipEndMs - 50) {
                    onPlayheadChange(clipEndMs)
                    onPlayingChange(false)
                    playerState.pause()
                    return@collectLatest
                }

                if (timelineMs >= currentClip.startMs) {
                    onPlayheadChange(timelineMs)
                }
            }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (clipSourcePath != null && lastLoadedPath == clipSourcePath) {
            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize(),
            )

            val filters = currentClip?.filters ?: emptyList()
            if (filters.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "${filters.size} filter${if (filters.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (clipSpeed != 1f) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "${clipSpeed}x",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        } else if (thumbnailFallback != null) {
            val bitmap = remember(thumbnailFallback) { thumbnailFallback.toImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = "Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.VideoFile,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.White.copy(alpha = 0.3f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No video at playhead",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }
}
