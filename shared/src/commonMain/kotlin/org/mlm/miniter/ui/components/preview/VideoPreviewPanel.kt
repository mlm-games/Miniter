package org.mlm.miniter.ui.components.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mlm.miniter.editor.model.*
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformFrameGrabber
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.platform.normalizeMediaUriForPlayback
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.TimeSource

private suspend fun seekToPosition(
    playerState: io.github.kdroidfilter.composemediaplayer.VideoPlayerState,
    sourceTimeMs: Long,
    durationMs: Long,
    maxValue: Float = 1000f,
    postDelayMs: Long = 0
) {
    if (durationMs <= 0L) return
    val sliderTarget = (sourceTimeMs.toFloat() / durationMs * 1000f)
        .coerceIn(0f, maxValue)
    if (!sliderTarget.isNaN() && !sliderTarget.isInfinite()) {
        playerState.sliderPos = sliderTarget
        playerState.userDragging = true
        delay(50)
        playerState.userDragging = false
        playerState.seekTo(playerState.sliderPos)
        if (postDelayMs > 0) delay(postDelayMs)
    }
}

sealed interface VisibleMedia {
    val id: String
    val trackIndex: Int
    val trackId: String
    val startMs: Long
    val durationMs: Long
    val sourceStartMs: Long
    val opacity: Float

    data class Video(
        override val id: String,
        override val trackIndex: Int,
        override val trackId: String,
        override val startMs: Long,
        override val durationMs: Long,
        override val sourceStartMs: Long,
        override val opacity: Float,
        val sourcePath: String,
        val sourceEndMs: Long,
        val sourceTotalDurationMs: Long,
        val speed: Float,
        val volume: Float,
        val filters: List<RustVideoEffectSnapshot>,
    ) : VisibleMedia

    data class Text(
        override val id: String,
        override val trackIndex: Int,
        override val trackId: String,
        override val startMs: Long,
        override val durationMs: Long,
        override val sourceStartMs: Long,
        override val opacity: Float,
        val text: String,
        val style: RustTextStyleSnapshot,
    ) : VisibleMedia
}

private fun collectVisibleMedia(
    snapshot: RustProjectSnapshot?,
    playheadMs: Long,
): List<VisibleMedia> {
    if (snapshot == null) return emptyList()

    val playheadUs = playheadMs * 1000L
    val tracks = snapshot.timeline.tracks

    val visibleMedia = mutableListOf<VisibleMedia>()

    tracks.forEachIndexed { trackIndex, track ->
        if (track.muted) return@forEachIndexed

        track.clips.forEach { clip ->
            val clipStartUs = clip.timelineStartUs
            val clipEndUs = clipStartUs + clip.timelineDurationUs

            if (playheadUs < clipStartUs || playheadUs >= clipEndUs) return@forEach

            when (val kind = clip.kind) {
                is RustVideoClipKind -> {
                    visibleMedia.add(
                        VisibleMedia.Video(
                            id = clip.id,
                            trackIndex = trackIndex,
                            trackId = track.id,
                            startMs = clip.timelineStartUs / 1000L,
                            durationMs = clip.timelineDurationUs / 1000L,
                            sourceStartMs = clip.sourceStartUs / 1000L,
                            opacity = clip.opacity,
                            sourcePath = kind.sourcePath,
                            sourceEndMs = clip.sourceEndUs / 1000L,
                            sourceTotalDurationMs = clip.sourceTotalDurationUs / 1000L,
                            speed = clip.speed.toFloat(),
                            volume = clip.volume,
                            filters = kind.filters,
                        )
                    )
                }
                is RustTextClipKind -> {
                    visibleMedia.add(
                        VisibleMedia.Text(
                            id = clip.id,
                            trackIndex = trackIndex,
                            trackId = track.id,
                            startMs = clip.timelineStartUs / 1000L,
                            durationMs = clip.timelineDurationUs / 1000L,
                            sourceStartMs = clip.sourceStartUs / 1000L,
                            opacity = clip.opacity,
                            text = kind.text,
                            style = kind.style,
                        )
                    )
                }
                else -> {}
            }
        }
    }

    return visibleMedia.sortedBy { it.trackIndex }
}

@Composable
fun EditorVideoPreview(
    snapshot: RustProjectSnapshot?,
    playheadMs: Long,
    isPlaying: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onPlayheadChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailFallback: ImageData? = null,
    onVisualTransformChange: ((scale: Float, translateX: Float, translateY: Float) -> Unit)? = null,
    initialVisualScale: Float = 1f,
    initialVisualTranslateX: Float = 0f,
    initialVisualTranslateY: Float = 0f,
) {
    val playerState = rememberVideoPlayerState()
    val frameGrabber = remember { PlatformFrameGrabber() }

    DisposableEffect(Unit) {
        onDispose { frameGrabber.release() }
    }

    val visibleMedia = remember(snapshot, playheadMs) {
        collectVisibleMedia(snapshot, playheadMs)
    }

    val primaryVideo = visibleMedia.filterIsInstance<VisibleMedia.Video>().firstOrNull()
    val backgroundVideos = visibleMedia.filterIsInstance<VisibleMedia.Video>().drop(1)
    val textOverlays = visibleMedia.filterIsInstance<VisibleMedia.Text>()

    val primaryVideoPath = primaryVideo?.sourcePath
    val primaryVideoUri = primaryVideoPath?.let { normalizeMediaUriForPlayback(it) }
    val canPlayPrimary = !primaryVideoUri.isNullOrBlank()
    val primarySpeed = primaryVideo?.speed ?: 1f
    val primaryVolume = primaryVideo?.volume ?: 1f
    val primaryFilters = primaryVideo?.filters ?: emptyList()
    val primaryOpacity = primaryVideo?.opacity ?: 1f

    val audioMedia = remember(snapshot, playheadMs) {
        val playheadUs = playheadMs * 1000L
        snapshot?.timeline?.tracks
            ?.filter { it.kind == RustTrackKind.Audio && !it.muted }
            ?.flatMap { it.clips }
            ?.mapNotNull { clip ->
                if (clip.muted || clip.volume <= 0f) return@mapNotNull null
                val audio = clip.kind as? RustAudioClipKind ?: return@mapNotNull null
                Triple(clip.id, audio.sourcePath, clip.volume)
            }
            ?.find { (id, _, _) ->
                val track = snapshot.timeline.tracks.find { t -> t.clips.any { it.id == id } }
                track?.clips?.any { c ->
                    c.id == id &&
                            playheadUs >= c.timelineStartUs &&
                            playheadUs < c.timelineStartUs + c.timelineDurationUs
                } ?: false
            }
    }

    val audioSourcePath = audioMedia?.second
    val audioPlaybackUri = audioSourcePath?.let { normalizeMediaUriForPlayback(it) }
    val canPlayAudio = !audioPlaybackUri.isNullOrBlank()
    val audioVolume = audioMedia?.third ?: 1f

    var fullFileDurationMs by remember { mutableLongStateOf(0L) }
    var lastLoadedPath by remember { mutableStateOf<String?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var scrubbedFrame by remember { mutableStateOf<ImageData?>(null) }
    var grabberReady by remember { mutableStateOf(false) }
    var audioFileDurationMs by remember { mutableLongStateOf(0L) }
    var lastLoadedAudioPath by remember { mutableStateOf<String?>(null) }
    var audioPlayerReady by remember { mutableStateOf(false) }
    var backgroundFrames by remember { mutableStateOf<Map<String, ImageData>>(emptyMap()) }

    val audioPlayerState = rememberVideoPlayerState()

    var visualScale by remember { mutableFloatStateOf(initialVisualScale) }
    var visualTranslateX by remember { mutableFloatStateOf(initialVisualTranslateX) }
    var visualTranslateY by remember { mutableFloatStateOf(initialVisualTranslateY) }
    var showZoomIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(initialVisualScale, initialVisualTranslateX, initialVisualTranslateY) {
        visualScale = initialVisualScale
        visualTranslateX = initialVisualTranslateX
        visualTranslateY = initialVisualTranslateY
    }

    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    fun syncVisualTransform() {
        if (onVisualTransformChange != null && (visualScale != 1f || visualTranslateX != 0f || visualTranslateY != 0f)) {
            onVisualTransformChange(visualScale, visualTranslateX, visualTranslateY)
        }
    }

    LaunchedEffect(audioSourcePath, audioPlaybackUri) {
        if (audioSourcePath != null && canPlayAudio && audioSourcePath != lastLoadedAudioPath) {
            audioPlayerReady = false
            audioFileDurationMs = 0L
            lastLoadedAudioPath = audioSourcePath

            audioPlayerState.openUri(audioPlaybackUri)
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

            audioPlayerReady = true
        }

        if (audioSourcePath == null || !canPlayAudio) {
            audioPlayerReady = false
            audioFileDurationMs = 0L
            lastLoadedAudioPath = null
            audioPlayerState.pause()
        }
    }

    LaunchedEffect(audioVolume) {
        audioPlayerState.volume = audioVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(primaryVideoPath, primaryVideoUri) {
        if (primaryVideoPath != null && canPlayPrimary && primaryVideoPath != lastLoadedPath) {
            playerReady = false
            grabberReady = false
            scrubbedFrame = null
            fullFileDurationMs = 0L
            lastLoadedPath = primaryVideoPath

            playerState.openUri(primaryVideoUri)
            delay(200)
            playerState.pause()

            try {
                frameGrabber.open(primaryVideoPath)
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

            if (fullFileDurationMs <= 0L && primaryVideo != null) {
                fullFileDurationMs = maxOf(primaryVideo.sourceTotalDurationMs, primaryVideo.sourceEndMs)
            }

            playerReady = true
        }

        if (!canPlayPrimary) {
            playerReady = false
            fullFileDurationMs = 0L
            lastLoadedPath = null
            playerState.pause()
        }
    }

    LaunchedEffect(primarySpeed) {
        playerState.playbackSpeed = primarySpeed.coerceIn(0.25f, 4f)
    }
    LaunchedEffect(primaryVolume) {
        playerState.volume = primaryVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(playheadMs, isPlaying, grabberReady, playerReady, fullFileDurationMs, primaryFilters, primaryOpacity) {
        if (isPlaying) return@LaunchedEffect
        if (primaryVideo == null || primaryVideoPath == null) return@LaunchedEffect

        val offsetInClip = playheadMs - primaryVideo.startMs
        val sourceTimeMs = primaryVideo.sourceStartMs + (offsetInClip * primaryVideo.speed).toLong()
        if (sourceTimeMs < 0) return@LaunchedEffect

        delay(80)

        if (grabberReady && (primaryFilters.any { it.enabled } || primaryOpacity < 1f)) {
            try {
                scrubbedFrame = frameGrabber.grabFrame(
                    timestampMs = sourceTimeMs,
                    filters = primaryFilters.map { it.filter }.filterIsInstance<RustVideoFilterSnapshot>(),
                    opacity = primaryOpacity,
                )
            } catch (_: Exception) {}
        } else if (grabberReady) {
            try {
                scrubbedFrame = frameGrabber.grabFrame(
                    timestampMs = sourceTimeMs,
                    filters = emptyList(),
                    opacity = 1f,
                )
            } catch (_: Exception) {}
        }

        if (playerReady && fullFileDurationMs > 0L && sourceTimeMs in 0..fullFileDurationMs) {
            seekToPosition(playerState, sourceTimeMs, fullFileDurationMs)
        }
    }

    val audioClip = audioMedia?.let { (id, _, vol) ->
        val playheadUs = playheadMs * 1000L
        snapshot?.timeline?.tracks
            ?.filter { it.kind == RustTrackKind.Audio && !it.muted }
            ?.flatMap { it.clips }
            ?.filter { playheadUs >= it.timelineStartUs && playheadUs < it.timelineStartUs + it.timelineDurationUs }
            ?.mapNotNull { clip ->
                if (clip.muted || clip.volume <= 0f) return@mapNotNull null
                val audio = clip.kind as? RustAudioClipKind ?: return@mapNotNull null
                Triple(clip.id, audio, clip)
            }
            ?.firstOrNull()
            ?.let { (_, audio, clip) ->
                AudioClipInfo(clip.id, clip.timelineStartUs / 1000L, clip.timelineDurationUs / 1000L, clip.sourceStartUs / 1000L, clip.speed.toFloat())
            }
    }

    LaunchedEffect(
        isPlaying,
        primaryVideo?.id,
        audioClip?.id,
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

        if (primaryVideo == null) {
            playerState.pause()
            val audioC = audioClip
            if (audioC == null || !canPlayAudio || !audioPlayerReady || audioFileDurationMs <= 0L) {
                onPlayingChange(false)
                playerState.pause()
                audioPlayerState.pause()
                return@LaunchedEffect
            }

            val audioStartMs = audioC.startMs
            val audioEndMs = audioC.startMs + audioC.durationMs
            if (playheadMs < audioStartMs || playheadMs >= audioEndMs) {
                onPlayingChange(false)
                playerState.pause()
                audioPlayerState.pause()
                return@LaunchedEffect
            }

            val audioOffset = playheadMs - audioStartMs
            val audioSourceTimeMs = audioC.sourceStartMs + (audioOffset * audioC.speed).toLong()
            seekToPosition(audioPlayerState, audioSourceTimeMs, audioFileDurationMs, 999f, 100)
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

        if (!canPlayPrimary || !playerReady) {
            onPlayingChange(false)
            playerState.pause()
            audioPlayerState.pause()
            return@LaunchedEffect
        }

        if (fullFileDurationMs <= 0L) {
            onPlayingChange(false)
            audioPlayerState.pause()
            return@LaunchedEffect
        }

        scrubbedFrame = null

        val clipEndMs = primaryVideo.startMs + primaryVideo.durationMs
        val offsetInClip = playheadMs - primaryVideo.startMs
        val sourceTimeMs = primaryVideo.sourceStartMs + (offsetInClip * primaryVideo.speed).toLong()
        seekToPosition(playerState, sourceTimeMs, fullFileDurationMs, 999f, 100)

        playerState.play()

        val audioStartMs = audioClip?.startMs
        val audioEndMs = audioClip?.let { it.startMs + it.durationMs }
        val audioSpeed = audioClip?.speed ?: 1f
        val audioSourceStartMs = audioClip?.sourceStartMs ?: 0L

        if (audioSourcePath != null && canPlayAudio && audioStartMs != null && audioEndMs != null && audioPlayerReady && audioFileDurationMs > 0L) {
            if (playheadMs in audioStartMs until audioEndMs) {
                val audioOffset = playheadMs - audioStartMs
                val audioSourceTimeMs = audioSourceStartMs + (audioOffset * audioSpeed).toLong()
                seekToPosition(audioPlayerState, audioSourceTimeMs, audioFileDurationMs, 999f, 100)
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

    val previewModifier = modifier
        .background(Color.Black)
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, rotation ->
                if (isPlaying) return@detectTransformGestures
                visualScale = (visualScale * zoom).coerceIn(0.5f, 5f)
                visualTranslateX += pan.x
                visualTranslateY += pan.y
                showZoomIndicator = true
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    if (isPlaying) return@detectTapGestures
                    visualScale = 1f
                    visualTranslateX = 0f
                    visualTranslateY = 0f
                    syncVisualTransform()
                }
            )
        }

    Box(
        modifier = previewModifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val transformModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = visualScale
                scaleY = visualScale
                translationX = visualTranslateX
                translationY = visualTranslateY
            }

        when {
            visibleMedia.isEmpty() && thumbnailFallback == null -> {
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
                        "No content at playhead",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                }
            }

            else -> {
                Box(modifier = transformModifier) {
                    when {
                        !isPlaying && scrubbedFrame != null -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val bitmap = remember(scrubbedFrame) { scrubbedFrame!!.toImageBitmap() }
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }

                        canPlayPrimary && lastLoadedPath == primaryVideoPath -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                VideoPlayerSurface(
                                    playerState = playerState,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        thumbnailFallback != null -> {
                            val bitmap = remember(thumbnailFallback) { thumbnailFallback.toImageBitmap() }
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Thumbnail",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }

                        else -> {
                            Icon(
                                Icons.Default.VideoFile, null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }

                    backgroundVideos.forEach { bgVideo ->
                        val bgOffset = playheadMs - bgVideo.startMs
                        val bgSourceTime = bgVideo.sourceStartMs + (bgOffset * bgVideo.speed).toLong()
                        BackgroundVideoFrame(
                            sourcePath = bgVideo.sourcePath,
                            sourceTimeMs = bgSourceTime,
                            opacity = bgVideo.opacity,
                            frameGrabber = frameGrabber,
                            onFrameLoaded = { frame ->
                                backgroundFrames = backgroundFrames + (bgVideo.id to frame)
                            }
                        )

                        backgroundFrames[bgVideo.id]?.let { frame ->
                            val bgBitmap = remember(frame) { frame.toImageBitmap() }
                            Image(
                                bitmap = bgBitmap,
                                contentDescription = "Background video",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = bgVideo.opacity },
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class AudioClipInfo(
    val id: String,
    val startMs: Long,
    val durationMs: Long,
    val sourceStartMs: Long,
    val speed: Float,
)

@Composable
private fun BackgroundVideoFrame(
    sourcePath: String,
    sourceTimeMs: Long,
    opacity: Float,
    frameGrabber: PlatformFrameGrabber,
    onFrameLoaded: (ImageData) -> Unit,
) {
    LaunchedEffect(sourcePath, sourceTimeMs) {
        try {
            val frame = frameGrabber.grabFrame(
                timestampMs = sourceTimeMs,
                filters = emptyList(),
                opacity = opacity,
            )
            if (frame != null) {
                onFrameLoaded(frame)
            }
        } catch (_: Exception) {}
    }
}
