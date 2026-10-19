package org.mlm.miniter.ui.components.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import org.koin.compose.koinInject
import org.mlm.miniter.editor.RustProjectStore
import org.mlm.miniter.editor.model.*
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.rust.RustCoreSession
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.platform.normalizeMediaUriForPlayback
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.pixelSize
import org.mlm.miniter.project.defaultOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
    val clampedSourceMs = sourceTimeMs.coerceIn(0L, durationMs)
    val sliderTarget = (clampedSourceMs.toFloat() / durationMs * maxValue)
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
        val muted: Boolean,
        val filters: List<RustVideoEffectSnapshot>,
        val masks: List<RustMaskEffect> = emptyList(),
        val keyframes: RustKeyframeCurve = RustKeyframeCurve(),
        val blendMode: RustBlendMode = RustBlendMode.Normal,
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

    data class Subtitle(
        override val id: String,
        override val trackIndex: Int,
        override val trackId: String,
        override val startMs: Long,
        override val durationMs: Long,
        override val sourceStartMs: Long,
        override val opacity: Float,
        val text: String,
        val fontPath: String?,
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
                    visibleMedia.add(visibleVideoOf(clip, trackIndex, track.id, kind))
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
                is RustSubtitleClipKind -> {
                    val text = try {
                        RustCoreSession.subtitleTextAt(kind.sourcePath, playheadUs)
                    } catch (_: Exception) {
                        null
                    }
                    if (text != null) {
                        visibleMedia.add(
                            VisibleMedia.Subtitle(
                                id = clip.id,
                                trackIndex = trackIndex,
                                trackId = track.id,
                                startMs = clip.timelineStartUs / 1000L,
                                durationMs = clip.timelineDurationUs / 1000L,
                                sourceStartMs = clip.sourceStartUs / 1000L,
                                opacity = clip.opacity,
                                text = text,
                                fontPath = kind.fontPath,
                            )
                        )
                    }
                }
                else -> {}
            }
        }
    }

    return visibleMedia.sortedBy { it.trackIndex }
}

private fun visibleVideoOf(
    clip: RustClipSnapshot,
    trackIndex: Int,
    trackId: String,
    kind: RustVideoClipKind,
): VisibleMedia.Video = VisibleMedia.Video(
    id = clip.id,
    trackIndex = trackIndex,
    trackId = trackId,
    startMs = clip.timelineStartUs / 1000L,
    durationMs = clip.timelineDurationUs / 1000L,
    sourceStartMs = clip.sourceStartUs / 1000L,
    opacity = clip.opacity,
    sourcePath = kind.sourcePath,
    sourceEndMs = clip.sourceEndUs / 1000L,
    sourceTotalDurationMs = clip.sourceTotalDurationUs / 1000L,
    speed = clip.speed.toFloat(),
    volume = clip.volume,
    muted = clip.muted,
    filters = kind.filters,
    masks = kind.masks,
    keyframes = clip.keyframes,
    blendMode = clip.blendMode,
)

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
    onTextPositionChanged: ((clipId: String, positionX: Float, positionY: Float) -> Unit)? = null,
    onTextFontSizeChanged: ((clipId: String, fontSize: Float) -> Unit)? = null,
    onTextDragStart: (() -> Unit)? = null,
    onTextDragEnd: (() -> Unit)? = null,
    onCropChanged: ((clipId: String, left: Float, top: Float, right: Float, bottom: Float) -> Unit)? = null,
    onCropDragStart: (() -> Unit)? = null,
    onCropDragEnd: (() -> Unit)? = null,
    onMaskChanged: ((clipId: String, index: Int, mask: RustMaskEffect) -> Boolean)? = null,
    onMaskDragStart: (() -> Unit)? = null,
    onMaskDragEnd: (() -> Unit)? = null,
    onMaskDragCancel: (() -> Unit)? = null,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)
    val store: RustProjectStore = koinInject()
    val playerState = rememberVideoPlayerState()

    val visibleMedia = remember(snapshot, playheadMs) {
        collectVisibleMedia(snapshot, playheadMs)
    }

    val videosByZ = visibleMedia.filterIsInstance<VisibleMedia.Video>().sortedBy { it.trackIndex }
    val primaryVideo = videosByZ.maxByOrNull { it.trackIndex }
    val textOverlays = visibleMedia.filterIsInstance<VisibleMedia.Text>()

    val selectedClip = remember(snapshot, selectedClipId) {
        snapshot?.timeline?.tracks?.flatMap { it.clips }?.find { it.id == selectedClipId }
    }
    val selectedCropFilter = remember(selectedClip) {
        val video = selectedClip?.kind as? RustVideoClipKind
        video?.filters
            ?.firstOrNull { it.enabled && it.filter is RustCropFilterSnapshot }
            ?.filter as? RustCropFilterSnapshot
    }
    val selectedMasks = remember(selectedClip) {
        (selectedClip?.kind as? RustVideoClipKind)?.masks.orEmpty()
    }
    val shapeMasks = remember(selectedMasks) {
        selectedMasks.withIndex().filter { (_, mask) ->
            mask.enabled && mask.source is RustShapeMaskSource
        }
    }
    val canEditMasks = shapeMasks.isNotEmpty() && selectedClipId != null && selectedClipId == primaryVideo?.id
    var maskEditing by remember(selectedClipId) { mutableStateOf(false) }
    val canEditCrop = selectedCropFilter != null && selectedClipId != null && selectedClipId == primaryVideo?.id
    // Explicit mode: while it is on, the scrub frame is grabbed without the
    // crop filter so the box sits over the whole image and the user can see
    // what is being cut away.
    var cropEditing by remember(selectedClipId) { mutableStateOf(false) }

    val primaryVideoPath = primaryVideo?.sourcePath
    var primaryVideoUri by remember(primaryVideoPath) { mutableStateOf<String?>(null) }
    val canPlayPrimary = !primaryVideoUri.isNullOrBlank()
    val primarySpeed = primaryVideo?.speed ?: 1f
    val primaryVolume = if (primaryVideo?.muted == true) 0f
        else primaryVideo?.volume ?: defaultOf(KeyframeParams.VOLUME)

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
    var compositeFrame by remember { mutableStateOf<ImageData?>(null) }
    var compositePlayheadUs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var playbackRenderEnabled by remember { mutableStateOf(true) }
    val compositeRenderLock = remember { Mutex() }
    var audioFileDurationMs by remember { mutableLongStateOf(0L) }
    var lastLoadedAudioPath by remember { mutableStateOf<String?>(null) }
    var audioPlayerReady by remember { mutableStateOf(false) }
    val audioPlayerState = rememberVideoPlayerState()

    var visualScale by remember { mutableFloatStateOf(initialVisualScale) }
    var visualTranslateX by remember { mutableFloatStateOf(initialVisualTranslateX) }
    var visualTranslateY by remember { mutableFloatStateOf(initialVisualTranslateY) }
    var isInteracting by remember { mutableStateOf(false) }
    var overlayDragActive by remember { mutableStateOf(false) }
    val zoomed = visualScale != defaultOf(KeyframeParams.TRANSFORM_SCALE) ||
        visualTranslateX != defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X) ||
        visualTranslateY != defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)

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
            fullFileDurationMs = 0L
            lastLoadedPath = primaryVideoPath
            primaryVideoUri = null

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
                        if (overlayDragActive) {
                            // A text overlay owns this gesture; otherwise the
                            // view zoom would pan underneath the dragged text.
                        } else if (transformFilter != null) {
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
                        }
                    }
                } while (event.changes.any { it.pressed })

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
    val (exportWidth, exportHeight) = snapshot?.exportProfile?.resolution
        ?.pixelSize(sourceWidth, sourceHeight) ?: (0 to 0)

    val contentArea = remember(viewportSize, exportWidth, exportHeight) {
        val vw = viewportSize.width.coerceAtLeast(1).toFloat()
        val vh = viewportSize.height.coerceAtLeast(1).toFloat()
        if (exportWidth <= 0 || exportHeight <= 0) {
            Rect(0f, 0f, vw, vh)
        } else {
            val exportAspect = exportWidth.toFloat() / exportHeight.toFloat()
            val viewAspect = vw / vh
            val (fw, fh) = if (exportAspect > viewAspect) {
                vw to vw / exportAspect
            } else {
                vh * exportAspect to vh
            }
            Rect((vw - fw) / 2f, (vh - fh) / 2f, (vw + fw) / 2f, (vh + fh) / 2f)
        }
    }

    val fullRenderWidth = if (exportWidth > 0) exportWidth else contentArea.width.toInt().coerceAtLeast(1)
    val fullRenderHeight = if (exportHeight > 0) exportHeight else contentArea.height.toInt().coerceAtLeast(1)
    val playbackRenderWidth = minOf(fullRenderWidth, PLAYBACK_RENDER_MAX_WIDTH)
    val playbackRenderHeight = maxOf(
        1,
        (playbackRenderWidth.toLong() * fullRenderHeight / fullRenderWidth.coerceAtLeast(1)).toInt(),
    )

    val renderTimestampUs = playheadMs * 1000L
    LaunchedEffect(
        renderTimestampUs,
        snapshot,
        visibleMedia,
        isPlaying,
        playbackRenderEnabled,
        cropEditing,
        selectedClipId,
        fullRenderWidth,
        fullRenderHeight,
        settings.hardwareAccelerationEnabled,
    ) {
        if (snapshot == null || visibleMedia.isEmpty()) {
            if (!isPlaying) {
                compositeFrame = null
                compositePlayheadUs = Long.MIN_VALUE
            }
            return@LaunchedEffect
        }
        if (isPlaying && !playbackRenderEnabled) return@LaunchedEffect

        val width = if (isPlaying) playbackRenderWidth else fullRenderWidth
        val height = if (isPlaying) playbackRenderHeight else fullRenderHeight
        val ignoreCropClipId = if (cropEditing) selectedClipId else null

        if (isPlaying) {
            if (!compositeRenderLock.tryLock()) return@LaunchedEffect
        } else {
            compositeRenderLock.lock()
        }
        try {
            val started = TimeSource.Monotonic.markNow()
            val frame = store.renderPreviewFrame(
                timestampUs = renderTimestampUs,
                width = width,
                height = height,
                hardwareAcceleration = settings.hardwareAccelerationEnabled,
                ignoreCropClipId = ignoreCropClipId,
            )
            val elapsedMs = started.elapsedNow().inWholeMilliseconds
            if (frame != null) {
                compositeFrame = frame
                compositePlayheadUs = renderTimestampUs
            }
            if (isPlaying && elapsedMs > PLAYBACK_RENDER_BUDGET_MS) {
                playbackRenderEnabled = false
            }
        } finally {
            compositeRenderLock.unlock()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            playbackRenderEnabled = true
        }
    }

    val compositeBitmap = remember(compositeFrame) { compositeFrame?.toImageBitmap() }
    val compositeIsFresh = compositeBitmap != null &&
        (!isPlaying || renderTimestampUs - compositePlayheadUs <= PLAYBACK_FRAME_MAX_LAG_US)
    val thumbnailBitmap = remember(thumbnailFallback) { thumbnailFallback?.toImageBitmap() }
    val hasVideoLayer = videosByZ.isNotEmpty()
    val surfaceReady = canPlayPrimary && lastLoadedPath == primaryVideoPath

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
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        )

                        if (!hasVideoLayer) {
                            thumbnailBitmap?.let { thumbnail ->
                                Image(
                                    bitmap = thumbnail,
                                    contentDescription = "Thumbnail",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }

                        if (surfaceReady) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                VideoPlayerSurface(
                                    playerState = playerState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(clipTransformModifier(primaryDisplay, viewportSize)),
                                )
                            }
                        }

                        if (compositeBitmap != null && compositeIsFresh) {
                            Image(
                                bitmap = compositeBitmap,
                                contentDescription = "Preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (!surfaceReady && thumbnailBitmap == null && visibleMedia.isNotEmpty()) {
                            Icon(
                                Icons.Default.VideoFile, null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.White.copy(alpha = 0.3f),
                            )
                        }

                        textOverlays.forEach { textOverlay ->
                        val style = textOverlay.style
                        // Read at gesture start, not at modifier creation: the
                        // pointerInput key does not change when the panel or a
                        // drag edits the style.
                        val latestTextPos by rememberUpdatedState(
                            textOverlay.style.positionX to textOverlay.style.positionY
                        )
                        val latestTextSize by rememberUpdatedState(textOverlay.style.fontSize)
                        var layout by remember(textOverlay.id, textOverlay.text, style) { mutableStateOf<TextLayoutResult?>(null) }
                        val textSelected = textOverlay.id == selectedClipId && !isPlaying

                        Text(
                            text = textOverlay.text,
                            color = Color.Transparent,
                            fontSize = style.fontSize.sp,
                            fontFamily = resolveFontFamily(style.fontFamily),
                            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                            textAlign = TextAlign.Start,
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                .wrapContentSize(Alignment.TopStart)
                                .offset {
                                    val cw = contentArea.width.toInt()
                                    val ch = contentArea.height.toInt()
                                    val anchorX = (style.positionX * cw + contentArea.left).toInt()
                                    val anchorY = (style.positionY * ch + contentArea.top).toInt()
                                    val tw = layout?.size?.width ?: 0
                                    val th = layout?.size?.height ?: 0
                                    val ox = when (style.alignment) {
                                        RustTextAlignment.Left -> 0
                                        RustTextAlignment.Center -> -tw / 2
                                        RustTextAlignment.Right -> -tw
                                    }
                                    IntOffset(anchorX + ox, anchorY - th / 2)
                                }
                                .then(
                                    if (textSelected) {
                                        Modifier.border(1.dp, Color.White.copy(alpha = 0.5f))
                                    } else {
                                        Modifier
                                    }
                                )
                                .pointerInput(textOverlay.id, isPlaying, contentArea) {
                                    if (isPlaying) return@pointerInput
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        val startPos = latestTextPos
                                        val startSize = latestTextSize
                                        overlayDragActive = true
                                        onTextDragStart?.invoke()
                                        // Accumulate from the gesture start: the style
                                        // snapshot updates live under the finger, so
                                        // reading it back mid-gesture would compound.
                                        var px = startPos.first
                                        var py = startPos.second
                                        var fs = startSize
                                        do {
                                            val event = awaitPointerEvent()
                                            val zoom = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            if (zoom == 1f && pan == Offset.Zero) continue
                                            event.changes.forEach { it.consume() }
                                            // Both the offset above and this delta live in the
                                            // same (possibly view-zoomed) space, so the ratio
                                            // keeps the text glued under the finger.
                                            val cw = contentArea.width.coerceAtLeast(1f)
                                            val ch = contentArea.height.coerceAtLeast(1f)
                                            if (pan != Offset.Zero) {
                                                px = (px + pan.x / cw).coerceIn(0f, 1f)
                                                py = (py + pan.y / ch).coerceIn(0f, 1f)
                                                onTextPositionChanged?.invoke(textOverlay.id, px, py)
                                            }
                                            if (zoom != 1f) {
                                                fs = (fs * zoom).coerceIn(12f, 120f)
                                                onTextFontSizeChanged?.invoke(textOverlay.id, fs)
                                            }
                                        } while (event.changes.any { it.pressed })
                                        overlayDragActive = false
                                        onTextDragEnd?.invoke()
                                    }
                                },
                        )
                    }
                    }

                    if (canEditCrop && cropEditing) {
                        CropOverlay(
                            contentArea = contentArea,
                            crop = selectedCropFilter,
                            clipId = selectedClipId,
                            isPlaying = isPlaying,
                            onChanged = onCropChanged,
                            onDragStart = onCropDragStart,
                            onDragEnd = onCropDragEnd,
                        )
                    }

                    if (canEditMasks && maskEditing) {
                        MaskOverlay(
                            contentArea = contentArea,
                            renderWidth = fullRenderWidth.toFloat(),
                            renderHeight = fullRenderHeight.toFloat(),
                            masks = shapeMasks,
                            clipId = selectedClipId,
                            keyframes = selectedClip?.keyframes ?: RustKeyframeCurve(),
                            localUs = renderTimestampUs - (selectedClip?.timelineStartUs ?: 0L),
                            isPlaying = isPlaying,
                            onChanged = onMaskChanged,
                            onDragStart = onMaskDragStart,
                            onDragEnd = onMaskDragEnd,
                            onDragCancel = onMaskDragCancel,
                        )
                    }

                    if (exportWidth > 0 && exportHeight > 0) {
                        val exportFrameRect = contentArea

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
                    if (canEditMasks) {
                        FilterChip(
                            selected = maskEditing,
                            onClick = {
                                maskEditing = !maskEditing
                                if (maskEditing) cropEditing = false
                            },
                            label = { Text("Masks", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.align(Alignment.TopStart).padding(start = if (canEditCrop) 92.dp else 8.dp, top = 8.dp),
                        )
                    }
                    if (canEditCrop) {
                        FilterChip(
                            selected = cropEditing,
                            onClick = {
                                cropEditing = !cropEditing
                                if (cropEditing) maskEditing = false
                            },
                            label = { Text("Crop", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                    }
                    if (zoomed) {
                        TextButton(
                            onClick = {
                                visualScale = defaultOf(KeyframeParams.TRANSFORM_SCALE)
                                visualTranslateX = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X)
                                visualTranslateY = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y)
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Reset zoom", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private const val PLAYBACK_RENDER_MAX_WIDTH = 640

private const val PLAYBACK_RENDER_BUDGET_MS = 40L

private const val PLAYBACK_FRAME_MAX_LAG_US = 150_000L

private data class AudioClipInfo(
    val id: String,
    val startMs: Long,
    val durationMs: Long,
    val sourceStartMs: Long,
    val speed: Float,
)

private enum class CropGrab {
    None,
    Move,
    Left,
    Right,
    Top,
    Bottom,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

private data class CropInsets(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/// Crop box with four corner dots, four edge bars and a dimmed surround.
/// Dragging a dot or bar reshapes the box, dragging inside it moves the whole
/// box, and a double tap resets it to the full frame.
@Composable
private fun CropOverlay(
    contentArea: Rect,
    crop: RustCropFilterSnapshot,
    clipId: String,
    isPlaying: Boolean,
    onChanged: ((clipId: String, left: Float, top: Float, right: Float, bottom: Float) -> Unit)?,
    onDragStart: (() -> Unit)?,
    onDragEnd: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val dotRadius = with(density) { 7.dp.toPx() }
    val grabRadius = with(density) { 28.dp.toPx() }
    val lineWidth = with(density) { 2.dp.toPx() }
    val currentInsets by rememberUpdatedState(
        CropInsets(crop.left, crop.top, crop.right, crop.bottom)
    )
    val scrim = Color.Black.copy(alpha = 0.55f)
    val outline = Color.White.copy(alpha = 0.9f)

    fun boxOf(insets: CropInsets): Rect {
        val l = contentArea.left + insets.left.coerceIn(0f, 1f) * contentArea.width
        val t = contentArea.top + insets.top.coerceIn(0f, 1f) * contentArea.height
        val r = contentArea.left + (1f - insets.right.coerceIn(0f, 1f)) * contentArea.width
        val b = contentArea.top + (1f - insets.bottom.coerceIn(0f, 1f)) * contentArea.height
        return Rect(l, t, maxOf(r, l + 1f), maxOf(b, t + 1f))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clipId, contentArea, isPlaying) {
                if (isPlaying) return@pointerInput
                val width = contentArea.width.coerceAtLeast(1f)
                val height = contentArea.height.coerceAtLeast(1f)

                fun grabAt(pos: Offset, box: Rect): CropGrab {
                    fun near(p: Offset, x: Float, y: Float) =
                        kotlin.math.abs(p.x - x) <= grabRadius &&
                            kotlin.math.abs(p.y - y) <= grabRadius
                    if (near(pos, box.left, box.top)) return CropGrab.TopLeft
                    if (near(pos, box.right, box.top)) return CropGrab.TopRight
                    if (near(pos, box.left, box.bottom)) return CropGrab.BottomLeft
                    if (near(pos, box.right, box.bottom)) return CropGrab.BottomRight
                    val withinY = pos.y > box.top - grabRadius && pos.y < box.bottom + grabRadius
                    val withinX = pos.x > box.left - grabRadius && pos.x < box.right + grabRadius
                    if (withinY && kotlin.math.abs(pos.x - box.left) <= grabRadius) return CropGrab.Left
                    if (withinY && kotlin.math.abs(pos.x - box.right) <= grabRadius) return CropGrab.Right
                    if (withinX && kotlin.math.abs(pos.y - box.top) <= grabRadius) return CropGrab.Top
                    if (withinX && kotlin.math.abs(pos.y - box.bottom) <= grabRadius) return CropGrab.Bottom
                    if (pos.x > box.left && pos.x < box.right && pos.y > box.top && pos.y < box.bottom) {
                        return CropGrab.Move
                    }
                    return CropGrab.None
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grab = grabAt(down.position, boxOf(currentInsets))
                    if (grab == CropGrab.None) return@awaitEachGesture

                    val start = currentInsets
                    var insets = start
                    var moveDx = 0f
                    var moveDy = 0f
                    var began = false
                    val minSpan = 0.05f

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) break

                        // Read the delta before consuming: `positionChange()`
                        // reports zero once the change is consumed.
                        val delta = change.positionChange()
                        if (!began) {
                            began = true
                            onDragStart?.invoke()
                        }
                        change.consume()

                        // Edge drags follow the finger; a move keeps the size.
                        val nx = ((change.position.x - contentArea.left) / width).coerceIn(0f, 1f)
                        val ny = ((change.position.y - contentArea.top) / height).coerceIn(0f, 1f)
                        insets = when (grab) {
                            CropGrab.Move -> {
                                moveDx += delta.x / width
                                moveDy += delta.y / height
                                val dx = moveDx.coerceIn(-start.left, start.right)
                                val dy = moveDy.coerceIn(-start.top, start.bottom)
                                CropInsets(
                                    left = start.left + dx,
                                    top = start.top + dy,
                                    right = start.right - dx,
                                    bottom = start.bottom - dy,
                                )
                            }
                            CropGrab.Left -> start.copy(left = nx.coerceAtMost(1f - start.right - minSpan))
                            CropGrab.Right -> start.copy(right = (1f - nx).coerceAtMost(1f - start.left - minSpan))
                            CropGrab.Top -> start.copy(top = ny.coerceAtMost(1f - start.bottom - minSpan))
                            CropGrab.Bottom -> start.copy(bottom = (1f - ny).coerceAtMost(1f - start.top - minSpan))
                            CropGrab.TopLeft -> start.copy(
                                left = nx.coerceAtMost(1f - start.right - minSpan),
                                top = ny.coerceAtMost(1f - start.bottom - minSpan),
                            )
                            CropGrab.TopRight -> start.copy(
                                right = (1f - nx).coerceAtMost(1f - start.left - minSpan),
                                top = ny.coerceAtMost(1f - start.bottom - minSpan),
                            )
                            CropGrab.BottomLeft -> start.copy(
                                left = nx.coerceAtMost(1f - start.right - minSpan),
                                bottom = (1f - ny).coerceAtMost(1f - start.top - minSpan),
                            )
                            CropGrab.BottomRight -> start.copy(
                                right = (1f - nx).coerceAtMost(1f - start.left - minSpan),
                                bottom = (1f - ny).coerceAtMost(1f - start.top - minSpan),
                            )
                            CropGrab.None -> start
                        }
                        onChanged?.invoke(
                            clipId,
                            insets.left.coerceIn(0f, 1f),
                            insets.top.coerceIn(0f, 1f),
                            insets.right.coerceIn(0f, 1f),
                            insets.bottom.coerceIn(0f, 1f),
                        )
                    } while (true)

                    if (began) onDragEnd?.invoke()
                }
            }
            .pointerInput(clipId, contentArea, isPlaying) {
                if (isPlaying) return@pointerInput
                detectTapGestures(
                    onDoubleTap = {
                        onDragStart?.invoke()
                        onChanged?.invoke(clipId, 0f, 0f, 0f, 0f)
                        onDragEnd?.invoke()
                    }
                )
            }
    ) {
        val box = boxOf(crop.let { CropInsets(it.left, it.top, it.right, it.bottom) })
        Canvas(Modifier.fillMaxSize()) {
            // Dim everything outside the kept region.
            drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, box.top))
            drawRect(scrim, topLeft = Offset(0f, box.bottom), size = Size(size.width, size.height - box.bottom))
            drawRect(scrim, topLeft = Offset(0f, box.top), size = Size(box.left, box.height))
            drawRect(
                scrim,
                topLeft = Offset(box.right, box.top),
                size = Size(size.width - box.right, box.height),
            )

            drawRect(
                outline,
                topLeft = Offset(box.left, box.top),
                size = Size(box.width, box.height),
                style = Stroke(width = lineWidth),
            )

            // Rule-of-thirds guides.
            val thirds = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                val fx = box.left + box.width * i / 3f
                val fy = box.top + box.height * i / 3f
                drawLine(thirds, Offset(fx, box.top), Offset(fx, box.bottom), lineWidth / 2f)
                drawLine(thirds, Offset(box.left, fy), Offset(box.right, fy), lineWidth / 2f)
            }

            // Edge bars.
            val bar = lineWidth * 10
            val barThick = lineWidth * 2
            val cx = box.center.x
            val cy = box.center.y
            drawLine(outline, Offset(cx - bar / 2, box.top), Offset(cx + bar / 2, box.top), barThick)
            drawLine(outline, Offset(cx - bar / 2, box.bottom), Offset(cx + bar / 2, box.bottom), barThick)
            drawLine(outline, Offset(box.left, cy - bar / 2), Offset(box.left, cy + bar / 2), barThick)
            drawLine(outline, Offset(box.right, cy - bar / 2), Offset(box.right, cy + bar / 2), barThick)

            // Corner dots.
            for (corner in listOf(
                Offset(box.left, box.top),
                Offset(box.right, box.top),
                Offset(box.left, box.bottom),
                Offset(box.right, box.bottom),
            )) {
                drawCircle(outline, radius = dotRadius, center = corner)
                drawCircle(
                    Color.Black.copy(alpha = 0.6f),
                    radius = dotRadius,
                    center = corner,
                    style = Stroke(width = lineWidth),
                )
            }
        }
    }
}

/// Mask outlines with direct manipulation: dragging inside a mask moves it,
/// a pinch scales it. The drawing mirrors the renderer (rotation happens
/// about the canvas centre), so what is dragged is what will be exported.
@Composable
private fun MaskOverlay(
    contentArea: Rect,
    renderWidth: Float,
    renderHeight: Float,
    masks: List<IndexedValue<RustMaskEffect>>,
    clipId: String,
    keyframes: RustKeyframeCurve,
    localUs: Long,
    isPlaying: Boolean,
    onChanged: ((clipId: String, index: Int, mask: RustMaskEffect) -> Boolean)?,
    onDragStart: (() -> Unit)?,
    onDragEnd: (() -> Unit)?,
    onDragCancel: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val dotRadius = with(density) { 3.5.dp.toPx() }
    val vertexRadius = with(density) { 4.5.dp.toPx() }
    val handleRadius = with(density) { 4.dp.toPx() }
    val handleGrabRadius = with(density) { 18.dp.toPx() }
    val vertexGrabRadius = with(density) { 14.dp.toPx() }
    val bodyGrabRadius = with(density) { 12.dp.toPx() }
    val lineWidth = with(density) { 1.5.dp.toPx() }
    val currentMasks by rememberUpdatedState(masks)
    val currentKeyframes by rememberUpdatedState(keyframes)
    val currentLocalUs by rememberUpdatedState(localUs)
    val currentContentArea by rememberUpdatedState(contentArea)
    val currentRenderWidth by rememberUpdatedState(renderWidth)
    val currentRenderHeight by rememberUpdatedState(renderHeight)
    var selection by remember(clipId) { mutableStateOf<MaskVertexSelection?>(null) }
    val currentSelection by rememberUpdatedState(selection)
    val selectedVertex = selection
    val outline = Color(0xFF80D8FF)
    val handleColor = Color(0xFFFFD54F)

    fun geometries(): List<MaskScreenGeometry> = currentMasks.mapNotNull { (index, mask) ->
        maskScreenGeometry(
            mask,
            index,
            currentKeyframes,
            currentLocalUs,
            currentContentArea,
            currentRenderWidth,
            currentRenderHeight,
        )
    }

    fun handleHitAt(position: Offset, geoms: List<MaskScreenGeometry>): MaskHandleHit? {
        val selected = currentSelection ?: return null
        val geometry = geoms.firstOrNull { it.maskIndex == selected.maskIndex } ?: return null
        val index = selected.vertexIndex
        if (index !in geometry.vertices.indices) return null
        val inDistance = (geometry.handleIn[index] - position).getDistance()
        val outDistance = (geometry.handleOut[index] - position).getDistance()
        if (inDistance > handleGrabRadius && outDistance > handleGrabRadius) return null
        return MaskHandleHit(geometry, index, outDistance <= inDistance)
    }

    fun vertexHitAt(position: Offset, geoms: List<MaskScreenGeometry>): MaskVertexHit? {
        var best: MaskVertexHit? = null
        var bestDistance = vertexGrabRadius
        for (geometry in geoms) {
            geometry.vertices.forEachIndexed { index, point ->
                val distance = (point - position).getDistance()
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = MaskVertexHit(geometry, index)
                }
            }
        }
        return best
    }

    fun hitMaskAt(position: Offset, geoms: List<MaskScreenGeometry>): MaskScreenGeometry? =
        geoms.asReversed().firstOrNull { geometry ->
            polygonContains(geometry.outline, position) ||
                distanceToClosedPolygon(geometry.outline, position) <= bodyGrabRadius
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clipId, contentArea, isPlaying) {
                if (isPlaying) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geoms = geometries()

                    fun finishGesture(began: Boolean, failed: Boolean) {
                        if (!began) return
                        if (failed) (onDragCancel ?: onDragEnd)?.invoke() else onDragEnd?.invoke()
                    }

                    val handleHit = handleHitAt(down.position, geoms)
                    if (handleHit != null) {
                        val geometry = handleHit.geometry
                        val shape = (geometry.mask.source as? RustShapeMaskSource)?.shape as? RustPathMaskShape
                        val vertex = shape?.vertices?.getOrNull(handleHit.vertexIndex)
                        if (shape == null || vertex == null) return@awaitEachGesture
                        val startHandle = if (handleHit.out) {
                            geometry.handleOut[handleHit.vertexIndex]
                        } else {
                            geometry.handleIn[handleHit.vertexIndex]
                        }
                        val area = currentContentArea
                        var moved = Offset.Zero
                        var began = false
                        var failed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                if (delta == Offset.Zero) continue
                                if (!began) {
                                    began = true
                                    onDragStart?.invoke()
                                }
                                change.consume()
                                moved += delta
                                val alt = event.keyboardModifiers.isAltPressed
                                val target = unmapMaskPoint(startHandle + moved, geometry.transform, area)
                                val dragged = Offset(target.x - vertex.p.x, target.y - vertex.p.y)
                                val opposite = if (handleHit.out) vertex.tIn else vertex.tOut
                                val mirrored = if (alt) opposite else mirroredTangent(dragged, opposite)
                                val updated = if (handleHit.out) {
                                    vertex.copy(tOut = RustMaskPoint(dragged.x, dragged.y), tIn = mirrored)
                                } else {
                                    vertex.copy(tIn = RustMaskPoint(dragged.x, dragged.y), tOut = mirrored)
                                }
                                val vertices = shape.vertices.toMutableList()
                                vertices[handleHit.vertexIndex] = updated
                                val updatedMask = geometry.mask.copy(
                                    source = geometry.mask.source.copy(
                                        shape = shape.copy(vertices = vertices),
                                    ),
                                )
                                if (onChanged?.invoke(clipId, geometry.maskIndex, updatedMask) == false) {
                                    failed = true
                                    break
                                }
                            }
                        } finally {
                            finishGesture(began, failed)
                        }
                        return@awaitEachGesture
                    }

                    val vertexHit = vertexHitAt(down.position, geoms)
                    if (vertexHit != null) {
                        selection = MaskVertexSelection(vertexHit.geometry.maskIndex, vertexHit.vertexIndex)
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { if (it.pressed) it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }

                    if (selection != null && hitMaskAt(down.position, geoms) == null) {
                        selection = null
                    }
                    val hit = hitMaskAt(down.position, geoms) ?: return@awaitEachGesture
                    val startMask = hit.mask
                    val stored = startMask.transform
                    val radians = (stored.rotate * PI / 180.0).toFloat()
                    val sinRotation = sin(radians)
                    val cosRotation = cos(radians)
                    var tx = stored.translateX
                    var ty = stored.translateY
                    var scale = stored.scale
                    var began = false
                    var failed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) break
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom == 1f && pan == Offset.Zero) continue
                            if (!began) {
                                began = true
                                onDragStart?.invoke()
                            }
                            event.changes.forEach { it.consume() }
                            if (pan != Offset.Zero) {
                                val dx = pan.x / currentContentArea.width.coerceAtLeast(1f)
                                val dy = pan.y / currentContentArea.height.coerceAtLeast(1f)
                                tx = (tx + dx * cosRotation + dy * sinRotation).coerceIn(-1f, 1f)
                                ty = (ty - dx * sinRotation + dy * cosRotation).coerceIn(-1f, 1f)
                            }
                            if (zoom != 1f) {
                                scale = (scale * zoom).coerceIn(0.05f, 10f)
                            }
                            val updated = startMask.copy(
                                transform = startMask.transform.copy(
                                    scale = scale,
                                    translateX = tx,
                                    translateY = ty,
                                ),
                            )
                            if (onChanged?.invoke(clipId, hit.maskIndex, updated) == false) {
                                failed = true
                                break
                            }
                        }
                    } finally {
                        finishGesture(began, failed)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (geometry in geometries()) {
                if (geometry.outline.size >= 2) {
                    val path = Path()
                    path.moveTo(geometry.outline[0].x, geometry.outline[0].y)
                    for (index in 1 until geometry.outline.size) {
                        val point = geometry.outline[index]
                        path.lineTo(point.x, point.y)
                    }
                    path.close()
                    drawPath(path, outline, style = Stroke(width = lineWidth))
                }
                val centre = geometry.outline.fold(Offset.Zero) { sum, point -> sum + point } /
                    geometry.outline.size.coerceAtLeast(1).toFloat()
                drawCircle(outline.copy(alpha = 0.7f), radius = dotRadius, center = centre)
                val vertex = selectedVertex?.takeIf { it.maskIndex == geometry.maskIndex }
                geometry.vertices.forEachIndexed { index, point ->
                    if (vertex?.vertexIndex == index) {
                        drawCircle(Color.White, radius = vertexRadius, center = point)
                        drawCircle(outline, radius = vertexRadius, center = point, style = Stroke(width = lineWidth))
                    } else {
                        drawCircle(outline, radius = vertexRadius, center = point)
                    }
                }
                if (vertex != null && vertex.vertexIndex in geometry.vertices.indices) {
                    val index = vertex.vertexIndex
                    drawLine(handleColor, geometry.vertices[index], geometry.handleIn[index], lineWidth)
                    drawLine(handleColor, geometry.vertices[index], geometry.handleOut[index], lineWidth)
                    drawCircle(handleColor, radius = handleRadius, center = geometry.handleIn[index])
                    drawCircle(handleColor, radius = handleRadius, center = geometry.handleOut[index])
                }
            }
        }
    }
}

private const val MASK_PATH_SEGMENT_STEPS = 24
private const val MASK_ELLIPSE_SEGMENT_STEPS = 96

private data class MaskOverlayTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
    val rotate: Float,
)

private data class MaskVertexSelection(
    val maskIndex: Int,
    val vertexIndex: Int,
)

private data class MaskScreenGeometry(
    val maskIndex: Int,
    val mask: RustMaskEffect,
    val transform: MaskOverlayTransform,
    val outline: List<Offset>,
    val vertices: List<Offset>,
    val handleIn: List<Offset>,
    val handleOut: List<Offset>,
)

private data class MaskHandleHit(
    val geometry: MaskScreenGeometry,
    val vertexIndex: Int,
    val out: Boolean,
)

private data class MaskVertexHit(
    val geometry: MaskScreenGeometry,
    val vertexIndex: Int,
)

private fun maskCurveValue(
    curve: RustKeyframeCurve,
    index: Int,
    name: String,
    legacy: String,
    localUs: Long,
): Float? {
    curve.evaluate(KeyframeParams.maskParam(index, name), localUs)?.let { return it }
    if (index == 0) return curve.evaluate(legacy, localUs)
    return null
}

private fun maskOverlayTransform(
    mask: RustMaskEffect,
    curve: RustKeyframeCurve,
    index: Int,
    localUs: Long,
): MaskOverlayTransform {
    val stored = mask.transform
    return MaskOverlayTransform(
        scale = maskCurveValue(curve, index, "scale", KeyframeParams.MASK_SCALE, localUs) ?: stored.scale,
        translateX = maskCurveValue(curve, index, "translate_x", KeyframeParams.MASK_TRANSLATE_X, localUs)
            ?: stored.translateX,
        translateY = maskCurveValue(curve, index, "translate_y", KeyframeParams.MASK_TRANSLATE_Y, localUs)
            ?: stored.translateY,
        rotate = maskCurveValue(curve, index, "rotate", KeyframeParams.MASK_ROTATE, localUs) ?: stored.rotate,
    )
}

private fun mapMaskPoint(
    x: Float,
    y: Float,
    transform: MaskOverlayTransform,
    contentArea: Rect,
): Offset {
    val nx = (x - 0.5f) * transform.scale + 0.5f + transform.translateX
    val ny = (y - 0.5f) * transform.scale + 0.5f + transform.translateY
    return mapTransformedMaskPoint(nx, ny, transform, contentArea)
}

private fun mapTransformedMaskPoint(
    nx: Float,
    ny: Float,
    transform: MaskOverlayTransform,
    contentArea: Rect,
): Offset {
    val radians = (transform.rotate * PI / 180.0).toFloat()
    val sinRotation = sin(radians)
    val cosRotation = cos(radians)
    val rx = (nx - 0.5f) * cosRotation - (ny - 0.5f) * sinRotation + 0.5f
    val ry = (nx - 0.5f) * sinRotation + (ny - 0.5f) * cosRotation + 0.5f
    return Offset(contentArea.left + rx * contentArea.width, contentArea.top + ry * contentArea.height)
}

private fun mapMaskPoint(
    point: RustMaskPoint,
    transform: MaskOverlayTransform,
    contentArea: Rect,
): Offset = mapMaskPoint(point.x, point.y, transform, contentArea)

private fun unmapMaskPoint(
    position: Offset,
    transform: MaskOverlayTransform,
    contentArea: Rect,
): Offset {
    val width = contentArea.width.coerceAtLeast(1e-6f)
    val height = contentArea.height.coerceAtLeast(1e-6f)
    val scale = if (transform.scale.isFinite() && abs(transform.scale) > 1e-4f) transform.scale else 1f
    val radians = (-transform.rotate * PI / 180.0).toFloat()
    val sinRotation = sin(radians)
    val cosRotation = cos(radians)
    val nx = (position.x - contentArea.left) / width - 0.5f
    val ny = (position.y - contentArea.top) / height - 0.5f
    val rx = nx * cosRotation - ny * sinRotation + 0.5f
    val ry = nx * sinRotation + ny * cosRotation + 0.5f
    return Offset(
        (rx - 0.5f - transform.translateX) / scale + 0.5f,
        (ry - 0.5f - transform.translateY) / scale + 0.5f,
    )
}

private fun cubicPoint(p0: Offset, c1: Offset, c2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(
        p0.x * a + c1.x * b + c2.x * c + p3.x * d,
        p0.y * a + c1.y * b + c2.y * c + p3.y * d,
    )
}

private fun appendCubicSamples(
    out: MutableList<Offset>,
    p0: Offset,
    c1: Offset,
    c2: Offset,
    p3: Offset,
    steps: Int,
) {
    for (step in 1..steps) {
        out += cubicPoint(p0, c1, c2, p3, step.toFloat() / steps)
    }
}

private fun ellipseOutline(
    centre: Offset,
    radiusX: Float,
    radiusY: Float,
    rotate: Float,
    steps: Int,
): List<Offset> {
    val radians = (rotate * PI / 180.0).toFloat()
    val sinRotation = sin(radians)
    val cosRotation = cos(radians)
    return (0 until steps).map { step ->
        val angle = step.toFloat() / steps * 2f * PI.toFloat()
        val x = radiusX * cos(angle)
        val y = radiusY * sin(angle)
        Offset(
            centre.x + x * cosRotation - y * sinRotation,
            centre.y + x * sinRotation + y * cosRotation,
        )
    }
}

private fun maskScreenGeometry(
    mask: RustMaskEffect,
    maskIndex: Int,
    curve: RustKeyframeCurve,
    localUs: Long,
    contentArea: Rect,
    renderWidth: Float,
    renderHeight: Float,
): MaskScreenGeometry? {
    val shape = (mask.source as? RustShapeMaskSource)?.shape ?: return null
    val transform = maskOverlayTransform(mask, curve, maskIndex, localUs)
    val expandX = mask.expansion / renderWidth
    val expandY = mask.expansion / renderHeight
    return when (shape) {
        is RustPathMaskShape -> {
            val vertices = shape.vertices
            if (vertices.size < 2) return null
            val entries = vertices.map { mapMaskPoint(it.p, transform, contentArea) }
            val handleIn = vertices.map {
                mapMaskPoint(it.p.x + it.tIn.x, it.p.y + it.tIn.y, transform, contentArea)
            }
            val handleOut = vertices.map {
                mapMaskPoint(it.p.x + it.tOut.x, it.p.y + it.tOut.y, transform, contentArea)
            }
            val outline = ArrayList<Offset>(vertices.size * MASK_PATH_SEGMENT_STEPS)
            outline += entries[0]
            val segments = if (shape.closed) vertices.size else vertices.size - 1
            for (i in 0 until segments) {
                val j = (i + 1) % vertices.size
                appendCubicSamples(outline, entries[i], handleOut[i], handleIn[j], entries[j], MASK_PATH_SEGMENT_STEPS)
            }
            if (!shape.closed) {
                val last = vertices.size - 1
                appendCubicSamples(outline, entries[last], handleOut[last], handleIn[0], entries[0], MASK_PATH_SEGMENT_STEPS)
            }
            MaskScreenGeometry(maskIndex, mask, transform, outline, entries, handleIn, handleOut)
        }
        is RustRectangleMaskShape -> {
            val left = shape.left.coerceIn(0f, 1f)
            val top = shape.top.coerceIn(0f, 1f)
            val right = shape.right.coerceIn(0f, 1f)
            val bottom = shape.bottom.coerceIn(0f, 1f)
            val centreX = (left + right) / 2f
            val centreY = (top + bottom) / 2f
            val tcx = (centreX - 0.5f) * transform.scale + 0.5f + transform.translateX
            val tcy = (centreY - 0.5f) * transform.scale + 0.5f + transform.translateY
            val halfWidth = (abs((right - left) * transform.scale / 2f) + expandX).coerceAtLeast(0f)
            val halfHeight = (abs((bottom - top) * transform.scale / 2f) + expandY).coerceAtLeast(0f)
            val outline = listOf(
                mapTransformedMaskPoint(tcx - halfWidth, tcy - halfHeight, transform, contentArea),
                mapTransformedMaskPoint(tcx + halfWidth, tcy - halfHeight, transform, contentArea),
                mapTransformedMaskPoint(tcx + halfWidth, tcy + halfHeight, transform, contentArea),
                mapTransformedMaskPoint(tcx - halfWidth, tcy + halfHeight, transform, contentArea),
            )
            MaskScreenGeometry(maskIndex, mask, transform, outline, emptyList(), emptyList(), emptyList())
        }
        is RustEllipseMaskShape -> {
            val centre = mapMaskPoint(
                shape.centerX.coerceIn(0f, 1f),
                shape.centerY.coerceIn(0f, 1f),
                transform,
                contentArea,
            )
            val normRadiusX = (shape.radiusX.coerceIn(0f, 0.5f) * transform.scale).coerceAtLeast(0.001f)
            val normRadiusY = (shape.radiusY.coerceIn(0f, 0.5f) * transform.scale).coerceAtLeast(0.001f)
            val radiusX = ((normRadiusX + expandX) * contentArea.width).coerceAtLeast(0f)
            val radiusY = ((normRadiusY + expandY) * contentArea.height).coerceAtLeast(0f)
            val outline = ellipseOutline(centre, radiusX, radiusY, transform.rotate, MASK_ELLIPSE_SEGMENT_STEPS)
            MaskScreenGeometry(maskIndex, mask, transform, outline, emptyList(), emptyList(), emptyList())
        }
    }
}

private fun polygonContains(points: List<Offset>, position: Offset): Boolean {
    if (points.size < 3) return false
    var inside = false
    var previous = points.last()
    for (point in points) {
        if ((point.y > position.y) != (previous.y > position.y)) {
            val crossingX = previous.x + (position.y - previous.y) / (point.y - previous.y) * (point.x - previous.x)
            if (position.x < crossingX) inside = !inside
        }
        previous = point
    }
    return inside
}

private fun distanceToSegment(position: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared > 0f) {
        (((position.x - start.x) * dx + (position.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    } else {
        0f
    }
    return hypot(position.x - (start.x + dx * t), position.y - (start.y + dy * t))
}

private fun distanceToClosedPolygon(points: List<Offset>, position: Offset): Float {
    if (points.isEmpty()) return Float.MAX_VALUE
    var best = Float.MAX_VALUE
    for (index in points.indices) {
        best = minOf(best, distanceToSegment(position, points[index], points[(index + 1) % points.size]))
    }
    return best
}

private fun mirroredTangent(dragged: Offset, opposite: RustMaskPoint): RustMaskPoint {
    val length = hypot(dragged.x, dragged.y)
    if (length <= 1e-5f) return opposite
    val oppositeLength = hypot(opposite.x, opposite.y)
    if (oppositeLength <= 1e-5f) return RustMaskPoint(0f, 0f)
    return RustMaskPoint(-dragged.x / length * oppositeLength, -dragged.y / length * oppositeLength)
}

private fun resolveFontFamily(fontFamily: String): FontFamily = when {
    fontFamily.equals("sans-serif", ignoreCase = true) -> FontFamily.SansSerif
    fontFamily.equals("serif", ignoreCase = true) -> FontFamily.Serif
    fontFamily.equals("monospace", ignoreCase = true) -> FontFamily.Monospace
    fontFamily.equals("cursive", ignoreCase = true) -> FontFamily.Cursive
    fontFamily.equals("default", ignoreCase = true) -> FontFamily.Default
    // For font file paths, falls back to default (runtime font loading needs platform-specific code)
    else -> FontFamily.Default
}
