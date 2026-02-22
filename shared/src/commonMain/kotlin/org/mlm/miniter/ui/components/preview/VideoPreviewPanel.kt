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
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    val currentClip = remember(project, playheadMs) {
        project?.timeline?.tracks
            ?.filter { it.type == TrackType.Video && !it.isMuted }
            ?.flatMap { it.clips.filterIsInstance<Clip.VideoClip>() }
            ?.find { clip -> playheadMs >= clip.startMs && playheadMs < clip.startMs + clip.durationMs }
    }

    val clipSourcePath = currentClip?.sourcePath
    val clipSpeed = currentClip?.speed ?: 1f
    val clipVolume = currentClip?.volume ?: 1f

    val clipSourceTimeMs = remember(currentClip, playheadMs) {
        currentClip?.let { clip ->
            val offsetInClip = playheadMs - clip.startMs
            clip.sourceStartMs + (offsetInClip * clip.speed).toLong()
        } ?: 0L
    }

    var lastLoadedPath by remember { mutableStateOf<String?>(null) }
    var isSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(clipSourcePath) {
        if (clipSourcePath != null && clipSourcePath != lastLoadedPath) {
            playerState.openUri(clipSourcePath, InitialPlayerState.PAUSE)
            lastLoadedPath = clipSourcePath
        }
    }

    LaunchedEffect(clipSpeed) {
        playerState.playbackSpeed = clipSpeed.coerceIn(0.25f, 4f)
    }

    LaunchedEffect(clipVolume) {
        playerState.volume = clipVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            playerState.play()
        } else {
            playerState.pause()
        }
    }

    LaunchedEffect(clipSourceTimeMs, lastLoadedPath) {
        if (lastLoadedPath != null && !isSeeking) {
            val duration = playerState.metadata.duration ?: 0L
            if (duration > 0) {
                val sliderPos = (clipSourceTimeMs.toFloat() / duration * 1000f).coerceIn(0f, 1000f)
                playerState.seekTo(sliderPos)
            }
        }
    }

    LaunchedEffect(isPlaying, currentClip) {
        if (isPlaying && currentClip != null) {
            while (true) {
                delay(50)
                val duration = playerState.metadata.duration ?: 0L
                if (duration > 0) {
                    val sourcePos = (playerState.sliderPos / 1000f * duration).toLong()
                    val timelinePos = currentClip.startMs + ((sourcePos - currentClip.sourceStartMs) / currentClip.speed).toLong()
                    if (timelinePos in currentClip.startMs until (currentClip.startMs + currentClip.durationMs)) {
                        onPlayheadChange(timelinePos)
                    }
                }
            }
        }
    }

    LaunchedEffect(playerState.isPlaying) {
        onPlayingChange(playerState.isPlaying)
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
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
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
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
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
