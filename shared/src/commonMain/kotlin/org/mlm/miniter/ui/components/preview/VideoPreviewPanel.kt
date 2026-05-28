package org.mlm.miniter.ui.components.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.mlm.miniter.editor.model.*
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformFrameGrabber
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.platform.normalizeMediaUriForPlayback
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.defaultOf
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
        val keyframes: RustKeyframeCurve = RustKeyframeCurve(),
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
                            keyframes = clip.keyframes,
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

private data class TransformValues(
    val scale: Float,
    val tx: Float,
    val ty: Float,
    val rot: Float,
)

private fun clipTransformModifier(
    tv: TransformValues,
    viewportSize: IntSize,
): Modifier = Modifier.graphicsLayer {
    scaleX = tv.scale
    scaleY = tv.scale
    translationX = tv.tx * viewportSize.width
    translationY = tv.ty * viewportSize.height
    rotationZ = tv.rot
}

private fun findTransformFilter(filters: List<RustVideoEffectSnapshot>): RustTransformFilterSnapshot? {
    return filters
        .filter { it.enabled }
        .mapNotNull { it.filter as? RustTransformFilterSnapshot }
        .firstOrNull()
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
    initialVisualScale: Float = defaultOf(KeyframeParams.TRANSFORM_SCALE),
    initialVisualTranslateX: Float = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X),
    initialVisualTranslateY: Float = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y),
    selectedClipId: String? = null,
    transformFilter: RustTransformFilterSnapshot? = null,
    onTransformChanged: ((scale: Float, translateX: Float, translateY: Float, rotate: Float) -> Unit)? = null,
    onCommitTransform: (() -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    autoKeyframeEnabled: Boolean = false,
    onSetKeyframe: ((clipId: String, playheadMs: Long, scale: Float, tx: Float, ty: Float, rot: Float) -> Unit)? = null,
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
    var primaryVideoUri by remember(primaryVideoPath) { mutableStateOf<String?>(null) }
    val canPlayPrimary = !primaryVideoUri.isNullOrBlank()
    val primarySpeed = primaryVideo?.speed ?: 1f
    val primaryVolume = primaryVideo?.volume ?: defaultOf(KeyframeParams.VOLUME)
    val primaryFilters = primaryVideo?.filters ?: emptyList()
    val primaryOpacity = primaryVideo?.opacity ?: defaultOf(KeyframeParams.OPACITY)
    val primaryEffectiveOpacity = primaryVideo?.keyframes?.evaluate(
        KeyframeParams.OPACITY, (playheadMs - primaryVideo.startMs) * 1000L,
    ) ?: primaryOpacity

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
    var isInteracting by remember { mutableStateOf(false) }
    var showZoomIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(initialVisualScale, initialVisualTranslateX, initialVisualTranslateY) {
        visualScale = initialVisualScale
        visualTranslateX = initialVisualTranslateX
        visualTranslateY = initialVisualTranslateY
    }

    val clipKey = selectedClipId
    var renderScale by remember(clipKey) { mutableFloatStateOf(transformFilter?.scale ?: defaultOf(KeyframeParams.TRANSFORM_SCALE)) }
    var renderTranslateX by remember(clipKey) { mutableFloatStateOf(transformFilter?.translateX ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)) }
    var renderTranslateY by remember(clipKey) { mutableFloatStateOf(transformFilter?.translateY ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)) }
    var renderRotate by remember(clipKey) { mutableFloatStateOf(transformFilter?.rotate ?: defaultOf(KeyframeParams.TRANSFORM_ROTATE)) }

    LaunchedEffect(transformFilter) {
        if (transformFilter != null) {
            renderScale = transformFilter.scale
            renderTranslateX = transformFilter.translateX
            renderTranslateY = transformFilter.translateY
            renderRotate = transformFilter.rotate
        }
    }

    val currentPlayheadMs by rememberUpdatedState(playheadMs)
    val currentAutoKeyframeEnabled by rememberUpdatedState(autoKeyframeEnabled)
    val currentOnSetKeyframe by rememberUpdatedState(onSetKeyframe)

    fun syncVisualTransform() {
        if (onVisualTransformChange != null && (visualScale != defaultOf(KeyframeParams.TRANSFORM_SCALE) || visualTranslateX != defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X) || visualTranslateY != defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y))) {
            onVisualTransformChange(visualScale, visualTranslateX, visualTranslateY)
        }
    }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    fun commitTransform() {
        onCommitTransform?.invoke()
    }

    LaunchedEffect(audioSourcePath, audioPlaybackUri) {
        if (audioSourcePath != null && canPlayAudio && audioSourcePath != lastLoadedAudioPath) {
            audioPlayerReady = false
            audioFileDurationMs = 0L
            lastLoadedAudioPath = audioSourcePath

            audioPlayerState.openUri(audioPlaybackUri, InitialPlayerState.PAUSE)

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

    LaunchedEffect(primaryVideoPath) {
        if (primaryVideoPath != null && primaryVideoPath != lastLoadedPath) {
            playerReady = false
            grabberReady = false
            scrubbedFrame = null
            fullFileDurationMs = 0L
            lastLoadedPath = primaryVideoPath
            primaryVideoUri = null

            try {
                frameGrabber.open(primaryVideoPath)
                grabberReady = true
            } catch (e: Exception) {
                e.printStackTrace()
                grabberReady = false
            }

            val uri = normalizeMediaUriForPlayback(primaryVideoPath)
            primaryVideoUri = uri

            if (!uri.isNullOrBlank()) {
                playerState.openUri(uri, InitialPlayerState.PAUSE)

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
            } else {
                playerReady = false
            }
        }

        if (primaryVideoPath == null) {
            playerReady = false
            grabberReady = false
            fullFileDurationMs = 0L
            lastLoadedPath = null
            primaryVideoUri = null
            playerState.pause()
        }
    }

    LaunchedEffect(primarySpeed) {
        playerState.playbackSpeed = primarySpeed.coerceIn(0.25f, 4f)
    }
    LaunchedEffect(primaryVolume) {
        playerState.volume = primaryVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(playheadMs, isPlaying, grabberReady, playerReady, fullFileDurationMs, primaryFilters, primaryEffectiveOpacity) {
        if (isPlaying) return@LaunchedEffect
        if (primaryVideo == null || primaryVideoPath == null) return@LaunchedEffect

        val offsetInClip = playheadMs - primaryVideo.startMs
        val sourceTimeMs = primaryVideo.sourceStartMs + (offsetInClip * primaryVideo.speed).toLong()
        if (sourceTimeMs < 0) return@LaunchedEffect

        delay(80)

        val nonTransformFilters = primaryFilters
            .map { it.filter }
            .filter { it !is RustTransformFilterSnapshot }

        if (grabberReady && (primaryFilters.any { it.enabled } || primaryEffectiveOpacity < 1f)) {
            try {
                scrubbedFrame = frameGrabber.grabFrame(
                    timestampMs = sourceTimeMs,
                    filters = nonTransformFilters,
                    opacity = primaryEffectiveOpacity,
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

    val primaryLocalUs = if (primaryVideo != null) (playheadMs - primaryVideo.startMs) * 1000L else 0L
    val primaryOwnTransform = findTransformFilter(primaryVideo?.filters ?: emptyList())
    val primaryKfScale = primaryVideo?.keyframes?.evaluate(KeyframeParams.TRANSFORM_SCALE, primaryLocalUs)
    val primaryKfTx = primaryVideo?.keyframes?.evaluate(KeyframeParams.TRANSFORM_TRANSLATE_X, primaryLocalUs)
    val primaryKfTy = primaryVideo?.keyframes?.evaluate(KeyframeParams.TRANSFORM_TRANSLATE_Y, primaryLocalUs)
    val primaryKfRot = primaryVideo?.keyframes?.evaluate(KeyframeParams.TRANSFORM_ROTATE, primaryLocalUs)
    val hasPrimaryKF = primaryKfScale != null || primaryKfTx != null || primaryKfTy != null || primaryKfRot != null

    val currentEffectiveScale by rememberUpdatedState(
        if (hasPrimaryKF) (primaryKfScale ?: primaryOwnTransform?.scale ?: defaultOf(KeyframeParams.TRANSFORM_SCALE)).coerceIn(0.1f, 10f)
        else primaryOwnTransform?.scale ?: defaultOf(KeyframeParams.TRANSFORM_SCALE)
    )
    val currentEffectiveTx by rememberUpdatedState(
        if (hasPrimaryKF) (primaryKfTx ?: primaryOwnTransform?.translateX ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X))
        else primaryOwnTransform?.translateX ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)
    )
    val currentEffectiveTy by rememberUpdatedState(
        if (hasPrimaryKF) (primaryKfTy ?: primaryOwnTransform?.translateY ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y))
        else primaryOwnTransform?.translateY ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)
    )
    val currentEffectiveRot by rememberUpdatedState(
        if (hasPrimaryKF) (primaryKfRot ?: primaryOwnTransform?.rotate ?: defaultOf(KeyframeParams.TRANSFORM_ROTATE))
        else primaryOwnTransform?.rotate ?: defaultOf(KeyframeParams.TRANSFORM_ROTATE)
    )

    val previewModifier = modifier
        .background(Color.Black)
        .onSizeChanged { viewportSize = it }
        .pointerInput(clipKey) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                if (isPlaying) return@awaitEachGesture

                var initialRs = renderScale
                var initialRtx = renderTranslateX
                var initialRty = renderTranslateY
                var initialRrot = renderRotate

                if (transformFilter != null && !isInteracting) {
                    isInteracting = true
                    initialRs = renderScale
                    initialRtx = renderTranslateX
                    initialRty = renderTranslateY
                    initialRrot = renderRotate
                    onDragStart?.invoke()
                }

                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val rotation = event.calculateRotation()

                    if (zoom != 1f || pan != Offset.Zero || rotation != 0f) {
                        if (transformFilter != null) {
                            renderScale = (renderScale * zoom).coerceIn(0.1f, 10f)
                            renderRotate += rotation
                            val normPanX = pan.x / maxOf(viewportSize.width, 1)
                            val normPanY = pan.y / maxOf(viewportSize.height, 1)
                            renderTranslateX += normPanX
                            renderTranslateY += normPanY
                            onTransformChanged?.invoke(renderScale, renderTranslateX, renderTranslateY, renderRotate)
                        } else {
                            visualScale = (visualScale * zoom).coerceIn(0.5f, 5f)
                            visualTranslateX += pan.x
                            visualTranslateY += pan.y
                            showZoomIndicator = true
                        }
                    }
                } while (event.changes.any { it.pressed })

                // Gesture ended — all pointers up
                if (transformFilter != null) {
                    isInteracting = false
                    if (currentAutoKeyframeEnabled && selectedClipId != null) {
                        val scaleDelta = if (initialRs != 0f) renderScale / initialRs else renderScale
                        val txDelta = renderTranslateX - initialRtx
                        val tyDelta = renderTranslateY - initialRty
                        val rotDelta = renderRotate - initialRrot
                        currentOnSetKeyframe?.invoke(
                            selectedClipId, currentPlayheadMs,
                            currentEffectiveScale * scaleDelta,
                            currentEffectiveTx + txDelta,
                            currentEffectiveTy + tyDelta,
                            currentEffectiveRot + rotDelta,
                        )
                    } else {
                        onCommitTransform?.invoke()
                    }
                }
            }
        }
        .pointerInput(clipKey) {
            detectTapGestures(
                onDoubleTap = {
                    if (isPlaying) return@detectTapGestures
                    if (transformFilter != null) {
                        renderScale = 1f
                        renderTranslateX = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)
                        renderTranslateY = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)
                        renderRotate = defaultOf(KeyframeParams.TRANSFORM_ROTATE)
                        onTransformChanged?.invoke(defaultOf(KeyframeParams.TRANSFORM_SCALE), defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X), defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y), defaultOf(KeyframeParams.TRANSFORM_ROTATE))
                        if (currentAutoKeyframeEnabled && selectedClipId != null) {
                            currentOnSetKeyframe?.invoke(selectedClipId, currentPlayheadMs, defaultOf(KeyframeParams.TRANSFORM_SCALE), defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X), defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y), defaultOf(KeyframeParams.TRANSFORM_ROTATE))
                        } else {
                            onCommitTransform?.invoke()
                        }
                    } else {
                        visualScale = defaultOf(KeyframeParams.TRANSFORM_SCALE)
                        visualTranslateX = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)
                        visualTranslateY = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)
                        syncVisualTransform()
                    }
                }
            )
        }

    fun clipDisplayValues(
        clip: VisibleMedia.Video?,
        kfScale: Float?,
        kfTx: Float?,
        kfTy: Float?,
        kfRot: Float?,
        hasKF: Boolean,
        staticFilter: RustTransformFilterSnapshot?,
    ): TransformValues {
        val scale = if (hasKF) (kfScale ?: staticFilter?.scale ?: defaultOf(KeyframeParams.TRANSFORM_SCALE)).coerceIn(0.1f, 10f)
            else staticFilter?.scale ?: defaultOf(KeyframeParams.TRANSFORM_SCALE)
        val tx = if (hasKF) (kfTx ?: staticFilter?.translateX ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X))
            else staticFilter?.translateX ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)
        val ty = if (hasKF) (kfTy ?: staticFilter?.translateY ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y))
            else staticFilter?.translateY ?: defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)
        val rot = if (hasKF) (kfRot ?: staticFilter?.rotate ?: defaultOf(KeyframeParams.TRANSFORM_ROTATE))
            else staticFilter?.rotate ?: defaultOf(KeyframeParams.TRANSFORM_ROTATE)
        return TransformValues(scale, tx, ty, rot)
    }

    val primaryDisplay = if (isInteracting && transformFilter != null) {
        TransformValues(renderScale, renderTranslateX, renderTranslateY, renderRotate)
    } else {
        clipDisplayValues(primaryVideo, primaryKfScale, primaryKfTx, primaryKfTy, primaryKfRot, hasPrimaryKF, primaryOwnTransform)
    }

    val sourceWidth = snapshot?.timeline?.tracks
        ?.flatMap { it.clips }
        ?.firstNotNullOfOrNull { clip ->
            (clip.kind as? RustVideoClipKind)?.width?.takeIf { it > 0 }
        } ?: 0
    val sourceHeight = snapshot?.timeline?.tracks
        ?.flatMap { it.clips }
        ?.firstNotNullOfOrNull { clip ->
            (clip.kind as? RustVideoClipKind)?.height?.takeIf { it > 0 }
        } ?: 0
    val (exportWidth, exportHeight) = when (val resolution = snapshot?.exportProfile?.resolution) {
        RustExportResolution.Source -> sourceWidth to sourceHeight
        RustExportResolution.Sd480 -> 854 to 480
        RustExportResolution.Hd720 -> 1280 to 720
        RustExportResolution.Hd1080 -> 1920 to 1080
        RustExportResolution.Uhd4k -> 3840 to 2160
        is RustExportResolution.Custom -> resolution.width to resolution.height
        null -> 0 to 0
    }

    val transformModifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            scaleX = visualScale
            scaleY = visualScale
            translationX = visualTranslateX
            translationY = visualTranslateY
            rotationZ = 0f
        }

    Box(
        modifier = previewModifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
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
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = transformModifier) {
                        when {
                            !isPlaying && scrubbedFrame != null -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val bitmap = remember(scrubbedFrame) { scrubbedFrame!!.toImageBitmap() }
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Preview",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(clipTransformModifier(primaryDisplay, viewportSize)),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }

                            canPlayPrimary && lastLoadedPath == primaryVideoPath -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    VideoPlayerSurface(
                                        playerState = playerState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(clipTransformModifier(primaryDisplay, viewportSize)),
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

                            visibleMedia.isNotEmpty() -> {
                                Icon(
                                    Icons.Default.VideoFile, null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color.White.copy(alpha = 0.3f),
                                )
                            }
                        }

                        backgroundVideos.forEach { bgVideo ->
                            val bgLocalUs = (playheadMs - bgVideo.startMs) * 1000L
                            val bgTransformFilter = findTransformFilter(bgVideo.filters)
                            val bgKfScale = bgVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_SCALE, bgLocalUs)
                            val bgKfTx = bgVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_TRANSLATE_X, bgLocalUs)
                            val bgKfTy = bgVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_TRANSLATE_Y, bgLocalUs)
                            val bgKfRot = bgVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_ROTATE, bgLocalUs)
                            val hasBgKF = bgKfScale != null || bgKfTx != null || bgKfTy != null || bgKfRot != null
                            val bgDisplay = clipDisplayValues(bgVideo, bgKfScale, bgKfTx, bgKfTy, bgKfRot, hasBgKF, bgTransformFilter)
                            val bgEffectiveOpacity = bgVideo.keyframes.evaluate(
                                KeyframeParams.OPACITY, bgLocalUs,
                            ) ?: bgVideo.opacity
                            val bgOffset = playheadMs - bgVideo.startMs
                            val bgSourceTime = bgVideo.sourceStartMs + (bgOffset * bgVideo.speed).toLong()
                            BackgroundVideoFrame(
                                sourcePath = bgVideo.sourcePath,
                                sourceTimeMs = bgSourceTime,
                                opacity = bgEffectiveOpacity,
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
                                        .then(clipTransformModifier(bgDisplay, viewportSize))
                                        .graphicsLayer { alpha = bgEffectiveOpacity },
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }

                    if (exportWidth > 0 && exportHeight > 0) {
                        val exportFrameRect = remember(viewportSize, exportWidth, exportHeight) {
                            val vw = viewportSize.width.toFloat()
                            val vh = viewportSize.height.toFloat()
                            val exportAspect = exportWidth.toFloat() / exportHeight.toFloat()
                            val viewAspect = vw / vh
                            val (fw, fh) = if (exportAspect > viewAspect) {
                                vw to vw / exportAspect
                            } else {
                                vh * exportAspect to vh
                            }
                            val fx = (vw - fw) / 2f
                            val fy = (vh - fh) / 2f
                            Rect(fx, fy, fx + fw, fy + fh)
                        }

                        val nonExportColor = MaterialTheme.colorScheme.surfaceContainerLowest
                        val exportColor = Color.Transparent  // .copy(alpha = 0.10f)
                        val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(exportColor, topLeft = Offset(exportFrameRect.left, exportFrameRect.top), size = Size(exportFrameRect.width, exportFrameRect.height))

                            drawRect(nonExportColor, topLeft = Offset(0f, 0f), size = Size(size.width, exportFrameRect.top))
                            drawRect(nonExportColor, topLeft = Offset(0f, exportFrameRect.bottom), size = Size(size.width, size.height - exportFrameRect.bottom))
                            drawRect(nonExportColor, topLeft = Offset(0f, exportFrameRect.top), size = Size(exportFrameRect.left, exportFrameRect.height))
                            drawRect(nonExportColor, topLeft = Offset(exportFrameRect.right, exportFrameRect.top), size = Size(size.width - exportFrameRect.right, exportFrameRect.height))

                            drawRect(
                                borderColor,
                                topLeft = Offset(exportFrameRect.left, exportFrameRect.top),
                                size = Size(exportFrameRect.width, exportFrameRect.height),
                                style = Stroke(width = 2.dp.toPx()),
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
    val frameGrabberOpen = remember { frameGrabber }
    LaunchedEffect(sourcePath) {
        frameGrabberOpen.open(sourcePath)
    }
    LaunchedEffect(sourcePath, sourceTimeMs) {
        try {
            val frame = frameGrabber.grabFrame(
                timestampMs = sourceTimeMs,
                filters = emptyList(),
                opacity = opacity,
                width = 0,
                height = 0,
            )
            if (frame != null) {
                onFrameLoaded(frame)
            }
        } catch (_: Throwable) {}
    }
}
