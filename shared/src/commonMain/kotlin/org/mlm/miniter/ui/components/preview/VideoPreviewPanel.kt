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
import kotlinx.coroutines.isActive
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
import org.mlm.miniter.editor.model.RustSepiaFilterSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformFrameGrabber
import org.mlm.miniter.engine.toImageBitmap
import kotlin.time.TimeSource

@Composable
fun EditorVideoPreview(
    snapshot: RustProjectSnapshot?,
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

    val currentClip = remember(snapshot, playheadMs) {
        val playheadUs = playheadMs * 1000L
        snapshot?.timeline?.tracks
            ?.filter { it.kind == RustTrackKind.Video && !it.muted }
            ?.flatMap { it.clips }
            ?.mapNotNull { clip ->
                if (clip.muted) return@mapNotNull null
                val video = clip.kind as? RustVideoClipKind ?: return@mapNotNull null
                ClipPreview(
                    id = clip.id,
                    startMs = clip.timelineStartUs / 1000L,
                    durationMs = clip.timelineDurationUs / 1000L,
                    sourcePath = video.sourcePath,
                    sourceStartMs = clip.sourceStartUs / 1000L,
                    sourceEndMs = clip.sourceEndUs / 1000L,
                    sourceTotalDurationMs = clip.sourceTotalDurationUs / 1000L,
                    speed = clip.speed.toFloat(),
                    volume = clip.volume,
                    opacity = clip.opacity,
                    filters = video.filters,
                )
            }
            ?.find { clip -> playheadUs >= clip.startMs * 1000L && playheadUs < (clip.startMs + clip.durationMs) * 1000L }
    }

    val currentAudioClip = remember(snapshot, playheadMs) {
        val playheadUs = playheadMs * 1000L
        snapshot?.timeline?.tracks
            ?.filter { it.kind == RustTrackKind.Audio && !it.muted }
            ?.flatMap { it.clips }
            ?.mapNotNull { clip ->
                if (clip.muted || clip.volume <= 0f) return@mapNotNull null
                val audio = clip.kind as? RustAudioClipKind ?: return@mapNotNull null
                AudioClipPreview(
                    id = clip.id,
                    startMs = clip.timelineStartUs / 1000L,
                    durationMs = clip.timelineDurationUs / 1000L,
                    sourcePath = audio.sourcePath,
                    sourceStartMs = clip.sourceStartUs / 1000L,
                    sourceEndMs = clip.sourceEndUs / 1000L,
                    sourceTotalDurationMs = clip.sourceTotalDurationUs / 1000L,
                    speed = clip.speed.toFloat(),
                    volume = clip.volume,
                )
            }
            ?.find { clip -> playheadUs >= clip.startMs * 1000L && playheadUs < (clip.startMs + clip.durationMs) * 1000L }
    }

    val clipSourcePath = currentClip?.sourcePath
    val clipSpeed = currentClip?.speed ?: 1f
    val clipVolume = currentClip?.volume ?: 1f
    val clipFilters = currentClip?.filters ?: emptyList()
    val clipOpacity = currentClip?.opacity ?: 1f

    val audioClipSourcePath = currentAudioClip?.sourcePath
    val audioClipVolume = currentAudioClip?.volume ?: 1f
    val audioClipSpeed = currentAudioClip?.speed ?: 1f

    var fullFileDurationMs by remember { mutableLongStateOf(0L) }
    var lastLoadedPath by remember { mutableStateOf<String?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var scrubbedFrame by remember { mutableStateOf<ImageData?>(null) }
    var grabberReady by remember { mutableStateOf(false) }
    var audioFileDurationMs by remember { mutableLongStateOf(0L) }
    var lastLoadedAudioPath by remember { mutableStateOf<String?>(null) }
    var audioPlayerReady by remember { mutableStateOf(false) }

    val audioPlayerState = rememberVideoPlayerState()

    LaunchedEffect(audioClipSourcePath) {
        if (audioClipSourcePath != null && audioClipSourcePath != lastLoadedAudioPath) {
            audioPlayerReady = false
            audioFileDurationMs = 0L
            lastLoadedAudioPath = audioClipSourcePath

            audioPlayerState.openUri(audioClipSourcePath)
            delay(200)
            audioPlayerState.pause()

            var attempts = 0
            while (attempts < 40) {
                val dur = audioPlayerState.metadata.duration
                if (dur != null && dur > 0L) {
                    audioFileDurationMs = dur
                    break
                }
                delay(100)
                attempts++
            }

            if (audioFileDurationMs <= 0L) {
                audioFileDurationMs = maxOf(
                    currentAudioClip.sourceTotalDurationMs,
                    currentAudioClip.sourceEndMs,
                )
            }

            audioPlayerReady = true
        }

        if (audioClipSourcePath == null) {
            audioPlayerReady = false
            audioFileDurationMs = 0L
            lastLoadedAudioPath = null
            audioPlayerState.pause()
        }
    }

    LaunchedEffect(audioClipVolume) {
        audioPlayerState.volume = audioClipVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(audioClipSpeed) {
        audioPlayerState.playbackSpeed = audioClipSpeed.coerceIn(0.25f, 4f)
    }

    LaunchedEffect(clipSourcePath) {
        if (clipSourcePath != null && clipSourcePath != lastLoadedPath) {
            playerReady = false
            grabberReady = false
            scrubbedFrame = null
            fullFileDurationMs = 0L
            lastLoadedPath = clipSourcePath

            playerState.openUri(clipSourcePath)
            delay(200)
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
                fullFileDurationMs = maxOf(currentClip.sourceTotalDurationMs, currentClip.sourceEndMs)
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

    LaunchedEffect(playheadMs, isPlaying, grabberReady, playerReady, fullFileDurationMs, clipFilters, clipOpacity) {
        if (isPlaying) return@LaunchedEffect
        if (currentClip == null || clipSourcePath == null) return@LaunchedEffect

        val offsetInClip = playheadMs - currentClip.startMs
        val sourceTimeMs = currentClip.sourceStartMs + (offsetInClip * currentClip.speed).toLong()
        if (sourceTimeMs < 0) return@LaunchedEffect

        delay(80)

        if (grabberReady && (clipFilters.isNotEmpty() || clipOpacity < 1f)) {
            try {
                scrubbedFrame = frameGrabber.grabFrame(
                    timestampMs = sourceTimeMs,
                    filters = clipFilters,
                    opacity = clipOpacity,
                )
            } catch (_: Exception) {}
        }

        if (playerReady && fullFileDurationMs > 0L && sourceTimeMs in 0..fullFileDurationMs) {
            val sliderTarget = (sourceTimeMs.toFloat() / fullFileDurationMs * 1000f)
                .coerceIn(0f, 1000f)
            if (!sliderTarget.isNaN() && !sliderTarget.isInfinite()) {
                playerState.sliderPos = sliderTarget
                playerState.userDragging = true
                delay(50)
                playerState.userDragging = false
                playerState.seekTo(playerState.sliderPos)
            }
        }
    }

    LaunchedEffect(
        isPlaying,
        currentClip?.id,
        currentAudioClip?.id,
        playerReady,
        audioPlayerReady,
        fullFileDurationMs,
        audioFileDurationMs,
    ) {
        if (!isPlaying) {
            playerState.pause()
            audioPlayerState.pause()
            return@LaunchedEffect
        }

        if (currentClip == null) {
            playerState.pause()
            val audioClip = currentAudioClip
            if (audioClip == null || !audioPlayerReady || audioFileDurationMs <= 0L) {
                onPlayingChange(false)
                playerState.pause()
                audioPlayerState.pause()
                return@LaunchedEffect
            }

            val audioStartMs = audioClip.startMs
            val audioEndMs = audioClip.startMs + audioClip.durationMs
            if (playheadMs < audioStartMs || playheadMs >= audioEndMs) {
                onPlayingChange(false)
                playerState.pause()
                audioPlayerState.pause()
                return@LaunchedEffect
            }

            val audioOffset = playheadMs - audioStartMs
            val audioSourceTimeMs = audioClip.sourceStartMs + (audioOffset * audioClip.speed).toLong()
            val audioSeekTarget = (audioSourceTimeMs.toFloat() / audioFileDurationMs * 1000f)
                .coerceIn(0f, 999f)
            if (!audioSeekTarget.isNaN() && !audioSeekTarget.isInfinite()) {
                audioPlayerState.sliderPos = audioSeekTarget
                audioPlayerState.userDragging = true
                delay(50)
                audioPlayerState.userDragging = false
                audioPlayerState.seekTo(audioPlayerState.sliderPos)
                delay(100)
            }
            audioPlayerState.play()

            val startMark = TimeSource.Monotonic.markNow()
            val startPlayheadMs = playheadMs
            while (isActive) {
                delay(33)
                val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
                val newPlayhead = startPlayheadMs + elapsedMs

                if (newPlayhead >= audioEndMs) {
                    onPlayheadChange(audioEndMs)
                    onPlayingChange(false)
                    playerState.pause()
                    audioPlayerState.pause()
                    break
                }

                onPlayheadChange(newPlayhead)
            }
            return@LaunchedEffect
        }

        if (!playerReady) return@LaunchedEffect

        if (fullFileDurationMs <= 0L) {
            onPlayingChange(false)
            audioPlayerState.pause()
            return@LaunchedEffect
        }

        scrubbedFrame = null

        val clipEndMs = currentClip.startMs + currentClip.durationMs
        val offsetInClip = playheadMs - currentClip.startMs
        val sourceTimeMs = currentClip.sourceStartMs + (offsetInClip * currentClip.speed).toLong()
        val seekTarget = (sourceTimeMs.toFloat() / fullFileDurationMs * 1000f)
            .coerceIn(0f, 999f)

        if (!seekTarget.isNaN() && !seekTarget.isInfinite()) {
            playerState.sliderPos = seekTarget
            playerState.userDragging = true
            delay(50)
            playerState.userDragging = false
            playerState.seekTo(playerState.sliderPos)
            delay(100)
        }

        playerState.play()

        val audioStartMs = currentAudioClip?.startMs
        val audioEndMs = currentAudioClip?.let { it.startMs + it.durationMs }
        val audioSpeed = currentAudioClip?.speed ?: 1f
        val audioSourceStartMs = currentAudioClip?.sourceStartMs ?: 0L

        if (audioClipSourcePath != null && audioStartMs != null && audioEndMs != null && audioPlayerReady && audioFileDurationMs > 0L) {
            if (playheadMs in audioStartMs until audioEndMs) {
                val audioOffset = playheadMs - audioStartMs
                val audioSourceTimeMs = audioSourceStartMs + (audioOffset * audioSpeed).toLong()
                val audioSeekTarget = (audioSourceTimeMs.toFloat() / audioFileDurationMs * 1000f)
                    .coerceIn(0f, 999f)
                if (!audioSeekTarget.isNaN() && !audioSeekTarget.isInfinite()) {
                    audioPlayerState.sliderPos = audioSeekTarget
                    audioPlayerState.userDragging = true
                    delay(50)
                    audioPlayerState.userDragging = false
                    audioPlayerState.seekTo(audioPlayerState.sliderPos)
                    delay(100)
                }
                audioPlayerState.play()
            } else {
                audioPlayerState.pause()
            }
        } else {
            audioPlayerState.pause()
        }

        val startMark = TimeSource.Monotonic.markNow()
        val startPlayheadMs = playheadMs

        while (isActive) {
            delay(33)

            val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
            val advancedMs = elapsedMs
            val newPlayhead = startPlayheadMs + advancedMs

            if (newPlayhead >= clipEndMs) {
                onPlayheadChange(clipEndMs)
                onPlayingChange(false)
                playerState.pause()
                audioPlayerState.pause()
                break
            }

            onPlayheadChange(newPlayhead)

            if (audioEndMs != null && newPlayhead >= audioEndMs) {
                audioPlayerState.pause()
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

private data class ClipPreview(
    val id: String,
    val startMs: Long,
    val durationMs: Long,
    val sourcePath: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val sourceTotalDurationMs: Long,
    val speed: Float,
    val volume: Float,
    val opacity: Float,
    val filters: List<RustVideoFilterSnapshot>,
)

private data class AudioClipPreview(
    val id: String,
    val startMs: Long,
    val durationMs: Long,
    val sourcePath: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val sourceTotalDurationMs: Long,
    val speed: Float,
    val volume: Float,
)
