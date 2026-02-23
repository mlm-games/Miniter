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
import org.mlm.miniter.engine.PlatformFrameGrabber
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
    val frameGrabber = remember { PlatformFrameGrabber() }

    DisposableEffect(Unit) {
        onDispose { frameGrabber.release() }
    }

    val currentClip = remember(project, playheadMs) {
        project?.timeline?.tracks
            ?.filter { it.type == TrackType.Video && !it.isMuted }
            ?.flatMap { it.clips.filterIsInstance<Clip.VideoClip>() }
            ?.find { clip -> playheadMs >= clip.startMs && playheadMs < clip.startMs + clip.durationMs }
    }

    val clipSourcePath = currentClip?.sourcePath
    val clipSpeed = currentClip?.speed ?: 1f
    val clipVolume = currentClip?.volume ?: 1f
    val clipFilters = currentClip?.filters ?: emptyList()
    val clipOpacity = currentClip?.opacity ?: 1f
    val hasFilters = clipFilters.isNotEmpty() || clipOpacity < 1f

    var fullFileDurationMs by remember { mutableLongStateOf(0L) }
    var lastLoadedPath by remember { mutableStateOf<String?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var editorIsSeeking by remember { mutableStateOf(false) }
    var scrubbedFrame by remember { mutableStateOf<ImageData?>(null) }
    var grabberReady by remember { mutableStateOf(false) }

    LaunchedEffect(clipSourcePath) {
        if (clipSourcePath != null && clipSourcePath != lastLoadedPath) {
            playerReady = false
            grabberReady = false
            scrubbedFrame = null
            fullFileDurationMs = 0L
            lastLoadedPath = clipSourcePath

            playerState.openUri(clipSourcePath)
            delay(150)
            playerState.pause()

            try {
                frameGrabber.open(clipSourcePath)
                grabberReady = true
            } catch (e: Exception) {
                e.printStackTrace()
                grabberReady = false
            }

            var attempts = 0
            while (attempts < 40) {
                val dur = playerState.metadata.duration
                if (dur != null && dur > 0L) {
                    fullFileDurationMs = dur
                    break
                }
                delay(100)
                attempts++
            }

            if (fullFileDurationMs <= 0L) {
                fullFileDurationMs = currentClip?.sourceEndMs ?: 0L
            }

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
            scrubbedFrame = null
            playerState.play()
        } else {
            playerState.pause()
        }
    }

    LaunchedEffect(playheadMs, isPlaying, grabberReady, clipFilters, clipOpacity) {
        if (isPlaying || !grabberReady) return@LaunchedEffect
        if (currentClip == null || clipSourcePath == null) return@LaunchedEffect

        val offsetInClip = playheadMs - currentClip.startMs
        val sourceTimeMs = currentClip.sourceStartMs + (offsetInClip * currentClip.speed).toLong()
        if (sourceTimeMs < 0) return@LaunchedEffect

        delay(30)

        try {
            val frame = frameGrabber.grabFrame(
                timestampMs = sourceTimeMs,
                filters = clipFilters,
                opacity = clipOpacity,
            )
            scrubbedFrame = frame
        } catch (_: Exception) {
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

        snapshotFlow { playerState.sliderPos }
            .drop(1)
            .sample(50)
            .collectLatest { sliderPos ->
                if (editorIsSeeking) return@collectLatest

                val sliderFraction = sliderPos / 1000f
                val sourceMs = (sliderFraction * fullFileDurationMs).toLong()
                val offsetFromSourceStart = sourceMs - currentClip.sourceStartMs
                val timelineMs = currentClip.startMs + (offsetFromSourceStart / currentClip.speed).toLong()
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
        when {
            !isPlaying && scrubbedFrame != null -> {
                val bitmap = remember(scrubbedFrame) { scrubbedFrame!!.toImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            clipSourcePath != null && lastLoadedPath == clipSourcePath -> {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            thumbnailFallback != null -> {
                val bitmap = remember(thumbnailFallback) { thumbnailFallback.toImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.VideoFile, null,
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

        if (currentClip != null) {
            if (clipFilters.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "${clipFilters.size} filter${if (clipFilters.size > 1) "s" else ""}",
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
                        "${clipSpeed}x",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
