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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
import org.mlm.miniter.editor.model.*
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.rust.RustCoreSession
import org.mlm.miniter.engine.PlatformFrameGrabber
import org.mlm.miniter.engine.toImageBitmap
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.platform.normalizeMediaUriForPlayback
import org.mlm.miniter.platform.rememberSubtitleFontFamily
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.pixelSize
import org.mlm.miniter.project.defaultOf
import org.mlm.miniter.ui.components.properties.blendModeLabel
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

private fun VisibleMedia.Video.opacityAt(playheadUs: Long): Float =
    keyframes.evaluate(KeyframeParams.OPACITY, playheadUs - startMs * 1000L) ?: opacity

/// Where a clip sits in a transition. `Incoming` is the layer the transition
/// brings in, `Outgoing` the one it leaves behind, and `Stationary` a clip the
/// exporter draws unchanged underneath a transition.
private enum class TransitionRole {
    Incoming,
    Outgoing,
    Stationary,
}

private fun previewLayer(
    video: VisibleMedia.Video,
    isPrimary: Boolean,
    clipAlpha: Float,
    kind: RustTransitionKind,
    eased: Float,
    role: TransitionRole,
    contentArea: Rect,
): PreviewLayer {
    val (outAlpha, inAlpha) = opacityPair(kind, eased)
    val (outScale, inScale) = transitionScales(kind, eased)
    return when (role) {
        TransitionRole.Incoming -> PreviewLayer(
            id = video.id,
            video = video,
            isPrimary = isPrimary,
            alpha = clipAlpha * inAlpha,
            scale = inScale,
            offsetX = slideOffset(kind, eased),
            offsetY = slideOffsetY(kind, eased),
            wipe = wipeReveal(kind, eased, contentArea),
        )
        TransitionRole.Outgoing -> PreviewLayer(
            id = video.id,
            video = video,
            isPrimary = isPrimary,
            alpha = clipAlpha * outAlpha,
            scale = outScale,
        )
        TransitionRole.Stationary -> PreviewLayer(
            id = video.id,
            video = video,
            isPrimary = isPrimary,
            alpha = clipAlpha,
        )
    }
}

/// A layer outside any transition keeps its own blend mode; the exporter
/// composites both ends of a transition with plain source-over instead.
private fun plainPreviewLayer(
    video: VisibleMedia.Video,
    isPrimary: Boolean,
    alpha: Float,
): PreviewLayer = PreviewLayer(
    id = video.id,
    video = video,
    isPrimary = isPrimary,
    alpha = alpha,
    blend = blendModeFor(video.blendMode),
)

private fun shadeLayer(id: String, alpha: Float): PreviewLayer =
    PreviewLayer(id = "$id:shade", video = null, alpha = alpha)

/// The layers the playhead sees, in the order the exporter's stack composites
/// them. A clip inside a transition contributes both ends of it, so the
/// neighbouring clip the exporter samples joins the stack even though the
/// playhead is not over it.
private fun buildPreviewLayers(
    snapshot: RustProjectSnapshot?,
    visibleMedia: List<VisibleMedia>,
    primaryVideoId: String?,
    playheadUs: Long,
    contentArea: Rect,
): List<PreviewLayer> {
    if (snapshot == null) return emptyList()

    val layers = mutableListOf<PreviewLayer>()

    visibleMedia.filterIsInstance<VisibleMedia.Video>().forEach { video ->
        val track = snapshot.timeline.tracks.getOrNull(video.trackIndex) ?: return@forEach
        val index = track.clips.indexOfFirst { it.id == video.id }
        if (index < 0) return@forEach

        val clip = track.clips[index]
        val previous = track.clips.getOrNull(index - 1)
        val incomingKind = previous?.kind as? RustVideoClipKind
        val next = track.clips.getOrNull(index + 1)
        val nextKind = next?.kind as? RustVideoClipKind
        val transitionIn = clip.transitionIn
        val transitionOut = clip.transitionOut

        val isPrimary = video.id == primaryVideoId
        // The primary's own opacity is baked into the frame it grabs.
        val clipAlpha = if (isPrimary) 1f else video.opacityAt(playheadUs)

        when {
            transitionIn != null && previous != null && incomingKind != null &&
                isNeighbouring(previous.endUs, clip.timelineStartUs) -> {
                val kind = transitionIn.kind
                val eased = easeInOut(
                    transitionInProgress(clip.timelineStartUs, transitionIn.duration, playheadUs)
                )
                val previousVideo = visibleVideoOf(previous, video.trackIndex, video.trackId, incomingKind)
                val previousAlpha = previousVideo.opacityAt(playheadUs)
                when {
                    kind == RustTransitionKind.FadeThroughBlack && eased >= 0.5f -> {
                        layers += shadeLayer(video.id, 1f)
                        layers += previewLayer(video, isPrimary, clipAlpha, kind, eased, TransitionRole.Incoming, contentArea)
                    }
                    kind == RustTransitionKind.FadeThroughBlack -> {
                        layers += previewLayer(previousVideo, false, previousAlpha, kind, eased, TransitionRole.Stationary, contentArea)
                        layers += shadeLayer(video.id, (2f * eased).coerceIn(0f, 1f))
                    }
                    else -> {
                        layers += previewLayer(previousVideo, false, previousAlpha, kind, eased, TransitionRole.Outgoing, contentArea)
                        layers += previewLayer(video, isPrimary, clipAlpha, kind, eased, TransitionRole.Incoming, contentArea)
                    }
                }
            }
            // No clip to hand over from: the exporter fades the clip in on its
            // own here, without any slide, zoom or wipe.
            transitionIn != null && previous != null &&
                !isNeighbouring(previous.endUs, clip.timelineStartUs) -> {
                val topAlpha = opacityPair(
                    transitionIn.kind,
                    easeInOut(transitionInProgress(clip.timelineStartUs, transitionIn.duration, playheadUs)),
                ).second
                layers += plainPreviewLayer(video, isPrimary, clipAlpha * topAlpha)
            }
            else -> {
                val outDurationUs = transitionOut?.duration ?: 0L
                val outProgress = transitionOutProgress(clip.endUs, outDurationUs, playheadUs)
                val fadeStartUs = transitionOutFadeStartUs(clip.endUs, outDurationUs)
                when {
                    transitionOut != null && outProgress > 0f && outProgress < 1f &&
                        next != null && nextKind != null &&
                        isNeighbouring(clip.endUs, next.timelineStartUs) &&
                        next.timelineStartUs + (playheadUs - fadeStartUs) < next.endUs -> {
                        val kind = transitionOut.kind
                        val eased = easeInOut(outProgress)
                        val nextVideo = visibleVideoOf(next, video.trackIndex, video.trackId, nextKind)
                        val nextAlpha = nextVideo.opacityAt(playheadUs)
                        when {
                            kind == RustTransitionKind.FadeThroughBlack && eased >= 0.5f -> {
                                layers += shadeLayer(video.id, 1f)
                                layers += previewLayer(nextVideo, false, nextAlpha, kind, eased, TransitionRole.Incoming, contentArea)
                            }
                            kind == RustTransitionKind.FadeThroughBlack -> {
                                layers += previewLayer(video, isPrimary, clipAlpha, kind, eased, TransitionRole.Stationary, contentArea)
                                layers += shadeLayer(video.id, (2f * eased).coerceIn(0f, 1f))
                            }
                            else -> {
                                layers += previewLayer(video, isPrimary, clipAlpha, kind, eased, TransitionRole.Outgoing, contentArea)
                                layers += previewLayer(nextVideo, false, nextAlpha, kind, eased, TransitionRole.Incoming, contentArea)
                            }
                        }
                    }
                    else -> layers += plainPreviewLayer(video, isPrimary, clipAlpha)
                }
            }
        }
    }

    return layers
}

private data class TextTransition(
    val alpha: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
) {
    companion object {
        val Identity = TextTransition(1f, 0f, 0f, 1f)
    }
}

/// Text and subtitles carry transitions too, as a fade plus motion on the text
/// itself rather than as a composite of two clips.
private fun collectTextTransitions(
    snapshot: RustProjectSnapshot?,
    playheadUs: Long,
): Map<String, TextTransition> {
    if (snapshot == null) return emptyMap()

    val transitions = mutableMapOf<String, TextTransition>()

    snapshot.timeline.tracks.forEachIndexed { _, track ->
        if (track.muted) return@forEachIndexed

        track.clips.forEach { clip ->
            if (playheadUs < clip.timelineStartUs || playheadUs >= clip.endUs) return@forEach
            if (clip.kind !is RustTextClipKind && clip.kind !is RustSubtitleClipKind) return@forEach

            var alpha = 1f
            var offsetX = 0f
            var offsetY = 0f
            var scale = 1f

            clip.transitionIn?.let { trans ->
                val progress = transitionInProgress(clip.timelineStartUs, trans.duration, playheadUs)
                if (progress < 1f) {
                    val eased = easeInOut(progress)
                    alpha *= opacityPair(trans.kind, eased).second
                    offsetX += slideOffset(trans.kind, eased)
                    offsetY += slideOffsetY(trans.kind, eased)
                    scale *= transitionScales(trans.kind, eased).second
                }
            }

            clip.transitionOut?.let { trans ->
                val progress = transitionOutProgress(clip.endUs, trans.duration, playheadUs)
                if (progress < 1f) {
                    val eased = easeInOut(progress)
                    alpha *= opacityPair(trans.kind, eased).first
                    offsetX += slideExitOffset(trans.kind, eased)
                    offsetY += slideExitOffsetY(trans.kind, eased)
                    scale *= transitionScales(trans.kind, eased).first
                }
            }

            transitions[clip.id] = TextTransition(alpha, offsetX, offsetY, scale)
        }
    }

    return transitions
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
    onTextPositionChanged: ((clipId: String, positionX: Float, positionY: Float) -> Unit)? = null,
    onTextFontSizeChanged: ((clipId: String, fontSize: Float) -> Unit)? = null,
    onTextDragStart: (() -> Unit)? = null,
    onTextDragEnd: (() -> Unit)? = null,
    onCropChanged: ((clipId: String, left: Float, top: Float, right: Float, bottom: Float) -> Unit)? = null,
    onCropDragStart: (() -> Unit)? = null,
    onCropDragEnd: (() -> Unit)? = null,
    onMaskChanged: ((clipId: String, index: Int, mask: RustMaskEffect) -> Unit)? = null,
    onMaskDragStart: (() -> Unit)? = null,
    onMaskDragEnd: (() -> Unit)? = null,
) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(settingsRepository.schema.default)
    val playerState = rememberVideoPlayerState()
    val frameGrabber = remember { PlatformFrameGrabber() }
    val grabberMutex = remember { Mutex() }

    DisposableEffect(Unit) {
        onDispose { frameGrabber.release() }
    }

    val visibleMedia = remember(snapshot, playheadMs) {
        collectVisibleMedia(snapshot, playheadMs)
    }

    val videosByZ = visibleMedia.filterIsInstance<VisibleMedia.Video>().sortedBy { it.trackIndex }
    val primaryVideo = videosByZ.maxByOrNull { it.trackIndex }
    val textOverlays = visibleMedia.filterIsInstance<VisibleMedia.Text>()
    val subtitles = visibleMedia.filterIsInstance<VisibleMedia.Subtitle>()

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
    val primaryFilters = primaryVideo?.filters ?: emptyList()
    // Disabled masks stay out of the wire payload entirely.
    val primaryMasks = remember(primaryVideo?.masks) {
        primaryVideo?.masks?.filter { it.enabled }.orEmpty()
    }
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

    LaunchedEffect(playheadMs, isPlaying, grabberReady, playerReady, fullFileDurationMs, primaryFilters, primaryMasks, primaryEffectiveOpacity, cropEditing) {
        if (isPlaying) return@LaunchedEffect
        if (primaryVideo == null || primaryVideoPath == null) return@LaunchedEffect

        val offsetInClip = playheadMs - primaryVideo.startMs
        val sourceTimeMs = primaryVideo.sourceStartMs + (offsetInClip * primaryVideo.speed).toLong()
        if (sourceTimeMs < 0) return@LaunchedEffect

        delay(80)

        val nonTransformFilters = primaryFilters
            .filter { it.enabled }
            .map { it.filter }
            .filter { it !is RustTransformFilterSnapshot }
            .filter { !(cropEditing && it is RustCropFilterSnapshot) }

        if (grabberReady && (primaryFilters.any { it.enabled } || primaryEffectiveOpacity < 1f)) {
            try {
                scrubbedFrame = frameGrabber.grabFrame(
                    timestampMs = sourceTimeMs,
                    filters = nonTransformFilters,
                    masks = primaryMasks,
                    opacity = primaryEffectiveOpacity,
                    hardwareAcceleration = settings.hardwareAccelerationEnabled,
                )
            } catch (_: Exception) {}
        } else if (grabberReady) {
            try {
                scrubbedFrame = frameGrabber.grabFrame(
                    timestampMs = sourceTimeMs,
                    filters = emptyList(),
                    masks = primaryMasks,
                    opacity = 1f,
                    hardwareAcceleration = settings.hardwareAccelerationEnabled,
                )
            } catch (_: Exception) {}
        }

        // The surface is hidden behind the still while paused, so seeking it
        // here only buys a full-resolution decode per scrub tick. Playback
        // seeks for itself before it starts; seek only when no still covers it.
        if (scrubbedFrame == null &&
            playerReady &&
            fullFileDurationMs > 0L &&
            sourceTimeMs in 0..fullFileDurationMs
        ) {
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

    val previewLayers = remember(snapshot, visibleMedia, primaryVideo?.id, playheadMs, contentArea) {
        buildPreviewLayers(snapshot, visibleMedia, primaryVideo?.id, playheadMs * 1000L, contentArea)
    }

    val textTransitions = remember(snapshot, playheadMs) {
        collectTextTransitions(snapshot, playheadMs * 1000L)
    }

    val approximatedBlendMode = remember(visibleMedia) {
        visibleMedia
            .filterIsInstance<VisibleMedia.Video>()
            .map { it.blendMode }
            .firstOrNull { it in approximatedBlendModes }
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
                        // The exporter flattens the stack over black, so a blend
                        // mode with nothing under it lands on black, not on the
                        // panel's own background.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        )

                        previewLayers.forEach { layer ->
                            key(layer.id) {
                                val wipe = layer.wipe
                                val layerModifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = layer.alpha
                                        blendMode = layer.blend
                                        scaleX = layer.scale
                                        scaleY = layer.scale
                                        translationX = layer.offsetX * contentArea.width
                                        translationY = layer.offsetY * contentArea.height
                                        if (wipe != null) {
                                            clip = true
                                            shape = wipe
                                        }
                                    }

                                val layerVideo = layer.video
                                if (layerVideo == null) {
                                    Box(modifier = layerModifier.background(Color.Black))
                                } else {
                                    val layerLocalUs = (playheadMs - layerVideo.startMs) * 1000L
                                    val layerTransformFilter = findTransformFilter(layerVideo.filters)
                                    val layerKfScale = layerVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_SCALE, layerLocalUs)
                                    val layerKfTx = layerVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_TRANSLATE_X, layerLocalUs)
                                    val layerKfTy = layerVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_TRANSLATE_Y, layerLocalUs)
                                    val layerKfRot = layerVideo.keyframes.evaluate(KeyframeParams.TRANSFORM_ROTATE, layerLocalUs)
                                    val layerHasKF = layerKfScale != null || layerKfTx != null || layerKfTy != null || layerKfRot != null
                                    val layerDisplay = clipDisplayValues(layerVideo, layerKfScale, layerKfTx, layerKfTy, layerKfRot, layerHasKF, layerTransformFilter)
                                    val layerOffset = playheadMs - layerVideo.startMs
                                    val layerSourceTime = (layerVideo.sourceStartMs + (layerOffset * layerVideo.speed).toLong())
                                        .coerceAtLeast(0L)

                                    Box(modifier = layerModifier) {
                                        if (layer.isPrimary) {
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
                                        } else {
                                            BackgroundVideoFrame(
                                                sourcePath = layerVideo.sourcePath,
                                                sourceTimeMs = layerSourceTime,
                                                masks = layerVideo.masks.filter { it.enabled },
                                                opacity = 1f,
                                                onFrameLoaded = { frame ->
                                                    backgroundFrames = backgroundFrames + (layerVideo.id to frame)
                                                }
                                            )

                                            backgroundFrames[layerVideo.id]?.let { frame ->
                                                val layerBitmap = remember(frame) { frame.toImageBitmap() }
                                                Image(
                                                    bitmap = layerBitmap,
                                                    contentDescription = "Video layer",
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .then(clipTransformModifier(layerDisplay, viewportSize)),
                                                    contentScale = ContentScale.Fit,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Nothing video-shaped at the playhead: keep the
                        // thumbnail and the placeholder the panel always
                        // showed behind text-only regions.
                        if (previewLayers.none { it.isPrimary }) {
                            when {
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
                        }

                    textOverlays.forEach { textOverlay ->
                        val style = textOverlay.style
                        val textTransition = textTransitions[textOverlay.id] ?: TextTransition.Identity
                        // Read at gesture start, not at modifier creation: the
                        // pointerInput key does not change when the panel or a
                        // drag edits the style.
                        val latestTextPos by rememberUpdatedState(
                            textOverlay.style.positionX to textOverlay.style.positionY
                        )
                        val latestTextSize by rememberUpdatedState(textOverlay.style.fontSize)
                        val textColor = parseArgbHex(style.color, Color.White)
                        var layout by remember(textOverlay.id, textOverlay.text, style) { mutableStateOf<TextLayoutResult?>(null) }

                        Text(
                            text = textOverlay.text,
                            color = textColor.copy(alpha = textColor.alpha * textOverlay.opacity * textTransition.alpha),
                            fontSize = (style.fontSize * textTransition.scale).sp,
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
                                    val anchorX = ((style.positionX + textTransition.offsetX) * cw + contentArea.left).toInt()
                                    val anchorY = ((style.positionY + textTransition.offsetY) * ch + contentArea.top).toInt()
                                    val tw = layout?.size?.width ?: 0
                                    val th = layout?.size?.height ?: 0
                                    val ox = when (style.alignment) {
                                        RustTextAlignment.Left -> 0
                                        RustTextAlignment.Center -> -tw / 2
                                        RustTextAlignment.Right -> -tw
                                    }
                                    IntOffset(anchorX + ox, anchorY - th / 2)
                                }
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

                    subtitles.forEach { subtitle ->
                        var subLayout by remember(subtitle.id, subtitle.text, subtitle.fontPath) { mutableStateOf<TextLayoutResult?>(null) }
                        val subtitleFontFamily = rememberSubtitleFontFamily(subtitle.fontPath)
                        val subtitleTransition = textTransitions[subtitle.id] ?: TextTransition.Identity
                        Text(
                            text = subtitle.text,
                            color = Color.White.copy(alpha = 0.9f * subtitleTransition.alpha),
                            fontSize = 16.sp,
                            fontFamily = subtitleFontFamily,
                            textAlign = TextAlign.Center,
                            onTextLayout = { subLayout = it },
                            modifier = Modifier
                                .wrapContentSize(Alignment.TopStart)
                                .offset {
                                    val tw = subLayout?.size?.width ?: 0
                                    val th = subLayout?.size?.height ?: 0
                                    IntOffset(
                                        (contentArea.left + contentArea.width / 2 - tw / 2).toInt(),
                                        (contentArea.bottom - th - 40).toInt(),
                                    )
                                }
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    }

                    approximatedBlendMode?.let { mode ->
                        Text(
                            text = "Preview approximates ${blendModeLabel(mode)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
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
                            masks = shapeMasks,
                            clipId = selectedClipId,
                            isPlaying = isPlaying,
                            onChanged = onMaskChanged,
                            onDragStart = onMaskDragStart,
                            onDragEnd = onMaskDragEnd,
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

private fun parseArgbHex(hex: String, default: Color): Color {
    val s = hex.trimStart('#')
    if (s.length != 8 && s.length != 6) return default
    val v = s.toLongOrNull(16) ?: return default
    val argb = if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    return Color(argb)
}

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
    masks: List<IndexedValue<RustMaskEffect>>,
    clipId: String,
    isPlaying: Boolean,
    onChanged: ((clipId: String, index: Int, mask: RustMaskEffect) -> Unit)?,
    onDragStart: (() -> Unit)?,
    onDragEnd: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val dotRadius = with(density) { 6.dp.toPx() }
    val lineWidth = with(density) { 1.5.dp.toPx() }
    val currentMasks by rememberUpdatedState(masks)
    val outline = Color(0xFF80D8FF)

    fun shapeBounds(mask: RustMaskEffect): Rect? {
        val shape = (mask.source as? RustShapeMaskSource)?.shape ?: return null
        val t = mask.transform
        val scale = t.scale.coerceAtLeast(0.001f)
        if (shape is RustPathMaskShape) {
            if (shape.vertices.isEmpty()) return null
            val count = shape.vertices.size.toFloat()
            val cx0 = shape.vertices.sumOf { it.p.x.toDouble() }.toFloat() / count
            val cy0 = shape.vertices.sumOf { it.p.y.toDouble() }.toFloat() / count
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (vertex in shape.vertices) {
                val x = cx0 + (vertex.p.x - cx0) * scale + t.translateX
                val y = cy0 + (vertex.p.y - cy0) * scale + t.translateY
                minX = minX.coerceAtMost(x)
                minY = minY.coerceAtMost(y)
                maxX = maxX.coerceAtLeast(x)
                maxY = maxY.coerceAtLeast(y)
            }
            return Rect(minX, minY, maxX, maxY)
        }
        val halfW: Float
        val halfH: Float
        val cx: Float
        val cy: Float
        when (shape) {
            is RustRectangleMaskShape -> {
                cx = (shape.left + shape.right) / 2f
                cy = (shape.top + shape.bottom) / 2f
                halfW = (shape.right - shape.left) / 2f
                halfH = (shape.bottom - shape.top) / 2f
            }
            is RustEllipseMaskShape -> {
                cx = shape.centerX
                cy = shape.centerY
                halfW = shape.radiusX
                halfH = shape.radiusY
            }
            is RustPathMaskShape -> return null
        }
        val ncx = cx + t.translateX
        val ncy = cy + t.translateY
        val nhw = halfW * scale
        val nhh = halfH * scale
        return Rect(ncx - nhw, ncy - nhh, ncx + nhw, ncy + nhh)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clipId, contentArea, isPlaying) {
                if (isPlaying) return@pointerInput
                val width = contentArea.width.coerceAtLeast(1f)
                val height = contentArea.height.coerceAtLeast(1f)

                fun hitTest(pos: Offset): IndexedValue<RustMaskEffect>? {
                    // Topmost first, so the last drawn mask wins a tie.
                    return currentMasks.reversed().firstOrNull { (_, mask) ->
                        val bounds = shapeBounds(mask) ?: return@firstOrNull false
                        val l = contentArea.left + bounds.left * width
                        val t = contentArea.top + bounds.top * height
                        val r = contentArea.left + bounds.right * width
                        val b = contentArea.top + bounds.bottom * height
                        pos.x in l..r && pos.y in t..b
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = hitTest(down.position) ?: return@awaitEachGesture
                    val startMask = hit.value
                    val startTx = startMask.transform.translateX
                    val startTy = startMask.transform.translateY
                    val startScale = startMask.transform.scale
                    var tx = startTx
                    var ty = startTy
                    var scale = startScale
                    var began = false

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) break

                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom == 1f && pan == Offset.Zero) continue

                        // Deltas are read before consumption.
                        if (!began) {
                            began = true
                            onDragStart?.invoke()
                        }
                        event.changes.forEach { it.consume() }

                        if (pan != Offset.Zero) {
                            // Pan is the per-event delta, so it accumulates.
                            tx = (tx + pan.x / width).coerceIn(-1f, 1f)
                            ty = (ty + pan.y / height).coerceIn(-1f, 1f)
                        }
                        if (zoom != 1f) {
                            scale = (scale * zoom).coerceIn(0.05f, 10f)
                        }
                        onChanged?.invoke(
                            clipId,
                            hit.index,
                            startMask.copy(
                                transform = startMask.transform.copy(
                                    scale = scale,
                                    translateX = tx,
                                    translateY = ty,
                                )
                            ),
                        )
                    } while (true)

                    if (began) onDragEnd?.invoke()
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for ((_, mask) in currentMasks) {
                val bounds = shapeBounds(mask) ?: continue
                val shape = (mask.source as? RustShapeMaskSource)?.shape
                val cx = contentArea.left + ((bounds.left + bounds.right) / 2f) * size.width
                val cy = contentArea.top + ((bounds.top + bounds.bottom) / 2f) * size.height
                val hw = bounds.width / 2f * size.width
                val hh = bounds.height / 2f * size.height
                val pivot = Offset(
                    contentArea.left + contentArea.width / 2f,
                    contentArea.top + contentArea.height / 2f,
                )
                rotate(mask.transform.rotate, pivot = pivot) {
                    when (shape) {
                        is RustPathMaskShape -> {
                            val vertices = shape.vertices
                            if (vertices.size >= 2) {
                                val scale = mask.transform.scale.coerceAtLeast(0.001f)
                                val count = vertices.size.toFloat()
                                val cx0 = vertices.sumOf { it.p.x.toDouble() }.toFloat() / count
                                val cy0 = vertices.sumOf { it.p.y.toDouble() }.toFloat() / count
                                val points = vertices.map { vertex ->
                                    val nx = cx0 + (vertex.p.x - cx0) * scale + mask.transform.translateX
                                    val ny = cy0 + (vertex.p.y - cy0) * scale + mask.transform.translateY
                                    Offset(
                                        contentArea.left + nx * size.width,
                                        contentArea.top + ny * size.height,
                                    )
                                }
                                val segments = if (shape.closed) points.size else points.size - 1
                                for (i in 0 until segments) {
                                    drawLine(
                                        color = outline,
                                        start = points[i],
                                        end = points[(i + 1) % points.size],
                                        strokeWidth = lineWidth,
                                    )
                                }
                            }
                        }
                        is RustEllipseMaskShape -> drawOval(
                            color = outline,
                            topLeft = Offset(cx - hw, cy - hh),
                            size = Size(hw * 2f, hh * 2f),
                            style = Stroke(width = lineWidth),
                        )
                        else -> drawRect(
                            color = outline,
                            topLeft = Offset(cx - hw, cy - hh),
                            size = Size(hw * 2f, hh * 2f),
                            style = Stroke(width = lineWidth),
                        )
                    }
                }
                drawCircle(outline, radius = dotRadius, center = Offset(cx, cy))
            }
        }
    }
}

@Composable
private fun BackgroundVideoFrame(
    sourcePath: String,
    sourceTimeMs: Long,
    masks: List<RustMaskEffect>,
    opacity: Float,
    onFrameLoaded: (ImageData) -> Unit,
) {
    val ownGrabber = remember(sourcePath) { PlatformFrameGrabber() }
    DisposableEffect(sourcePath) {
        onDispose { ownGrabber.release() }
    }
    LaunchedEffect(sourcePath) {
        try {
            ownGrabber.open(sourcePath)
        } catch (_: Throwable) {}
    }
    var lastDecodedMs by remember(sourcePath) { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(sourcePath, sourceTimeMs, masks) {
        if (lastDecodedMs != Long.MIN_VALUE &&
            kotlin.math.abs(sourceTimeMs - lastDecodedMs) <= 100L
        ) return@LaunchedEffect
        try {
            val frame = ownGrabber.grabFrame(
                timestampMs = sourceTimeMs.coerceAtLeast(0L),
                filters = emptyList(),
                masks = masks,
                opacity = opacity,
                width = 0,
                height = 0,
            )
            if (frame != null) {
                lastDecodedMs = sourceTimeMs
                onFrameLoaded(frame)
            }
            } catch (_: Throwable) {}
    }
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
