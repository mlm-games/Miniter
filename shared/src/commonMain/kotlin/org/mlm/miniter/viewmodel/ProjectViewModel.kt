package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.mlm.miniter.editor.RustDispatchException
import org.mlm.miniter.editor.RustProjectStore
import org.mlm.miniter.nav.MAX_EXTRA_IMPORT_PATHS
import org.mlm.miniter.editor.model.RustAdjustmentClipKind
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustBlendMode
import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustChromaKeyFilterSnapshot
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustColorGradeFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustCropFilterSnapshot
import org.mlm.miniter.editor.model.RustFlipFilterSnapshot
import org.mlm.miniter.editor.model.RustHueFilterSnapshot
import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustExportFormat
import org.mlm.miniter.editor.model.RustExportProfileSnapshot
import org.mlm.miniter.editor.model.RustExportRangeSnapshot
import org.mlm.miniter.editor.model.RustExportResolution
import org.mlm.miniter.editor.model.RustFadeInAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFadeOutAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
import org.mlm.miniter.editor.model.RustAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustMaskEffect
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustReverseAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustReverseVideoFilterSnapshot
import org.mlm.miniter.editor.model.RustRotateFilterSnapshot
import org.mlm.miniter.editor.model.RustLutFilterSnapshot
import org.mlm.miniter.editor.model.RustMarkerSnapshot
import org.mlm.miniter.editor.model.RustMosaicFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustVignetteFilterSnapshot
import org.mlm.miniter.editor.model.RustPosterizeFilterSnapshot
import org.mlm.miniter.editor.model.RustGrainFilterSnapshot
import org.mlm.miniter.editor.model.RustSepiaFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustSubtitleClipKind
import org.mlm.miniter.editor.model.RustTextClipKind
import org.mlm.miniter.editor.model.RustTextStyleSnapshot
import org.mlm.miniter.editor.model.RustTextStyleUpdate
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustTrackSnapshot
import org.mlm.miniter.editor.model.RustTransitionKind
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.editor.model.RustVideoEffectSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.overlapsRange
import org.mlm.miniter.editor.model.videoFilters
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformFrameGrabber
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.engine.ThumbnailKey
import org.mlm.miniter.engine.ThumbnailResult
import org.mlm.miniter.engine.isActive
import org.mlm.miniter.engine.ScrubProxy
import org.mlm.miniter.engine.VideoInfo
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.FontManager
import org.mlm.miniter.platform.SupportedFormats
import org.mlm.miniter.platform.msToUs
import org.mlm.miniter.platform.usToMs
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.AUTO_REFRAME_MAX_SAMPLES
import org.mlm.miniter.project.AUTO_REFRAME_SAMPLE_STEP_MS
import org.mlm.miniter.project.AutoReframeSample
import org.mlm.miniter.project.ProjectRecovery
import org.mlm.miniter.project.RecoveryInfo
import org.mlm.miniter.project.RecentProjectsRepository
import org.mlm.miniter.project.KenBurnsPreset
import org.mlm.miniter.project.STABILIZE_MAX_SAMPLES
import org.mlm.miniter.project.STABILIZE_SAMPLE_STEP_MS
import org.mlm.miniter.project.SpeedCurvePreset
import org.mlm.miniter.project.StabilizerAnalysis
import org.mlm.miniter.project.MOTION_TRACK_MAX_SAMPLES
import org.mlm.miniter.project.MOTION_TRACK_SAMPLE_STEP_MS
import org.mlm.miniter.project.MotionTrackerAnalysis
import org.mlm.miniter.project.maskRegionInVideoFrame
import org.mlm.miniter.project.maskShapeBounds
import org.mlm.miniter.project.motionTrackingKeyframes
import org.mlm.miniter.project.TextAnimationBase
import org.mlm.miniter.project.TextAnimationPreset
import org.mlm.miniter.project.autoReframeKeyframes
import org.mlm.miniter.project.autoReframeLocalOffset
import org.mlm.miniter.project.keyframesFor
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.defaultOf
import org.mlm.miniter.project.detectSilentSourceRuns
import org.mlm.miniter.project.pixelSize
import org.mlm.miniter.project.saliencyCentroid
import org.mlm.miniter.project.SCENE_MIN_FRAMES
import org.mlm.miniter.project.SceneSample
import org.mlm.miniter.project.detectSceneBoundaries
import org.mlm.miniter.project.luminanceHistogram
import org.mlm.miniter.project.sceneSampleTimestamps
import org.mlm.miniter.project.SilentRun
import org.mlm.miniter.project.silentRunsToLocal
import org.mlm.miniter.project.smoothAutoReframeSamples
import org.mlm.miniter.rust.BeatTrack
import org.mlm.miniter.rust.RustCoreSession
import org.mlm.miniter.rust.UndoHistoryEntry
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.ui.util.toArgbHex
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.time.Clock

data class ImportProgress(
    val current: Int = 0,
    val total: Int = 0,
    val currentFile: String? = null,
    val stage: String = "Importing",
    val fraction: Float? = null,
)

private data class ClipboardEntry(
    val trackId: String,
    val clip: RustClipSnapshot,
    val offsetUs: Long,
)

private data class PlannedClip(
    val track: RustTrackSnapshot,
    val clip: RustClipSnapshot,
)

data class ProjectUiState(
    val snapshot: RustProjectSnapshot? = null,
    val projectPath: String? = null,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val selectedClipIds: Set<String> = emptySet(),
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val importProgress: ImportProgress? = null,
    val isDirty: Boolean = false,
    val zoomLevel: Float = 1f,
    val thumbnails: List<ImageData> = emptyList(),
    val isLoadingThumbnails: Boolean = false,
    val autoReframeRunning: Boolean = false,
    val stabilizeRunning: Boolean = false,
    val maskTrackRunning: Boolean = false,
    val removeSilenceRunning: Boolean = false,
    val sceneDetectRunning: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val snapIndicatorMs: Long? = null,
    val lastValidationJson: String? = null,
)

class ProjectViewModel(
    private val recentProjectsRepository: RecentProjectsRepository,
    private val engine: PlatformVideoEngine,
    private val snackbarManager: SnackbarManager,
    private val rustStore: RustProjectStore,
    private val settingsRepository: SettingsRepository<AppSettings>,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state

    private val _hasClipboard = MutableStateFlow(false)
    val hasClipboard: StateFlow<Boolean> = _hasClipboard
    private var clipboard: List<ClipboardEntry> = emptyList()

    private val _pendingRecovery = MutableStateFlow<RecoveryInfo?>(null)
    val pendingRecovery: StateFlow<RecoveryInfo?> = _pendingRecovery.asStateFlow()

    val exportProgress = engine.exportProgress

    val playheadMs: StateFlow<Long> = _state.map { it.playheadMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val isPlaying: StateFlow<Boolean> = _state.map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val selectedClipId: StateFlow<String?> = _state.map { it.selectedClipId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val zoomLevel: StateFlow<Float> = _state.map { it.zoomLevel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)
    val snapIndicatorMs: StateFlow<Long?> = _state.map { it.snapIndicatorMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val canUndo: StateFlow<Boolean> = _state.map { it.canUndo }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val canRedo: StateFlow<Boolean> = _state.map { it.canRedo }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isDirty: StateFlow<Boolean> = _state.map { it.isDirty }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val previewFrame: StateFlow<ImageData?> = _state.map { it.thumbnails.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    companion object {
        private const val DEFAULT_TEXT_CLIP_DURATION_US = 3_000_000L
        private const val DEFAULT_ADJUSTMENT_CLIP_DURATION_US = 3_000_000L
        private const val DEFAULT_IMAGE_CLIP_DURATION_US = 5_000_000L
        private const val DEFAULT_SUBTITLE_DURATION_MS = 60_000L
        private const val MIN_SAMPLE_RATE = 44_100
        private const val MIN_TRIM_DURATION_US = 100_000L
        private const val SNAP_THRESHOLD_BASE = 200
        private const val MAX_TIMELINE_THUMBNAILS = 256
        private const val TIMELINE_THUMBNAIL_WIDTH = 160
        private const val TIMELINE_THUMBNAIL_HEIGHT = 90
        private const val TRANSITION_ADJACENCY_EPSILON_US = 2_000L
        private const val SPEED_CURVE_MAX_ITERATIONS = 4
        private const val SPEED_CURVE_TOLERANCE_US = 2_000L
    }

    private var hardwareAccelerationEnabled: Boolean = true
    private var sourceDurationMs: Long = 0L
    private var autoSaveJob: Job? = null
    private var lastRecoveryJson: String? = null
    private var preDragSnapshot: RustProjectSnapshot? = null
    private var continuousEditCommandCount = 0
    private val saveMutex = Mutex()
    private val waveformJson = Json { ignoreUnknownKeys = true }
    private var thumbnailJob: Job? = null
    private var thumbnailRequestId: Long = 0L
    private var thumbnailRequestPath: String? = null
    private val projectLifecycleMutex = Mutex()

    private fun cancelInFlightMediaWork() {
        thumbnailJob?.cancel()
        thumbnailRequestId += 1
        thumbnailRequestPath = null
        // Nothing will clear the flag now that the job is cancelled, and the
        // next project may have no video clip to trigger a fresh load.
        _state.update { it.copy(isLoadingThumbnails = false) }
        timelineThumbnailGeneration += 1
        timelineThumbnailInFlight.clear()
        timelineThumbnailLru.clear()
        _timelineThumbnails.value = emptyMap()
        ScrubProxy.cancel()
        cancelExport()
    }

    private val _waveforms = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val waveforms: StateFlow<Map<String, List<Float>>> = _waveforms
    private val waveformInFlight = mutableSetOf<String>()

    private val _timelineThumbnails = MutableStateFlow<Map<ThumbnailKey, ImageData?>>(emptyMap())
    val timelineThumbnails: StateFlow<Map<ThumbnailKey, ImageData?>> = _timelineThumbnails
    private val timelineThumbnailLru = LinkedHashMap<ThumbnailKey, ImageData?>()
    private val timelineThumbnailInFlight = mutableSetOf<ThumbnailKey>()
    private val timelineThumbnailMutex = Mutex()
    private var timelineThumbnailGeneration = 0L

    private val _beats = MutableStateFlow<Map<String, BeatTrack>>(emptyMap())
    val beats: StateFlow<Map<String, BeatTrack>> = _beats
    private val beatsInFlight = mutableSetOf<String>()

    fun requestWaveform(sourcePath: String, buckets: Int = 80) {
        if (sourcePath.isBlank()) return
        if (_waveforms.value.containsKey(sourcePath)) return
        // Called from the UI thread; viewModelScope also completes on Main,
        // so a plain set check is sufficient (no synchronized in common code).
        if (!waveformInFlight.add(sourcePath)) return
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.Default) {
                    RustCoreSession.extractWaveform(sourcePath, buckets)
                }
                val peaks = parseWaveformPeaks(json)
                // Cache even empty/failed results so silent clips and missing files
                // don't trigger an endless request loop from TimelinePanel.
                _waveforms.update { it + (sourcePath to peaks) }
            } catch (_: Exception) {
                _waveforms.update { it + (sourcePath to emptyList()) }
            } finally {
                waveformInFlight.remove(sourcePath)
            }
        }
    }

    private fun parseWaveformPeaks(json: String): List<Float> {
        return try {
            waveformJson.decodeFromString<List<List<Double>>>(json).map { pair ->
                val lo = pair.getOrNull(0)?.toFloat() ?: 0f
                val hi = pair.getOrNull(1)?.toFloat() ?: 0f
                maxOf(kotlin.math.abs(lo), kotlin.math.abs(hi)).coerceIn(0f, 1f)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun requestTimelineThumbnail(key: ThumbnailKey) {
        if (key.sourcePath.isBlank()) return
        if (timelineThumbnailLru.containsKey(key)) {
            val cached = timelineThumbnailLru.remove(key)
            timelineThumbnailLru[key] = cached
            return
        }
        if (!timelineThumbnailInFlight.add(key)) return
        val generation = timelineThumbnailGeneration
        viewModelScope.launch {
            try {
                val result = timelineThumbnailMutex.withLock {
                    engine.extractSingleThumbnail(
                        path = key.sourcePath,
                        timestampMs = key.timestampBucketUs / 1000L,
                        width = TIMELINE_THUMBNAIL_WIDTH,
                        height = TIMELINE_THUMBNAIL_HEIGHT,
                        hardwareAcceleration = hardwareAccelerationEnabled,
                    )
                }
                if (generation != timelineThumbnailGeneration) return@launch
                val image = (result as? ThumbnailResult.Success)?.image
                timelineThumbnailLru.remove(key)
                timelineThumbnailLru[key] = image
                while (timelineThumbnailLru.size > MAX_TIMELINE_THUMBNAILS) {
                    val eldest = timelineThumbnailLru.keys.firstOrNull() ?: break
                    timelineThumbnailLru.remove(eldest)
                }
                _timelineThumbnails.value = timelineThumbnailLru.toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation != timelineThumbnailGeneration) return@launch
                timelineThumbnailLru.remove(key)
                timelineThumbnailLru[key] = null
                while (timelineThumbnailLru.size > MAX_TIMELINE_THUMBNAILS) {
                    val eldest = timelineThumbnailLru.keys.firstOrNull() ?: break
                    timelineThumbnailLru.remove(eldest)
                }
                _timelineThumbnails.value = timelineThumbnailLru.toMap()
            } finally {
                timelineThumbnailInFlight.remove(key)
            }
        }
    }

    fun requestBeats(sourcePath: String) {
        if (sourcePath.isBlank()) return
        if (_beats.value.containsKey(sourcePath)) return
        if (!beatsInFlight.add(sourcePath)) return
        viewModelScope.launch {
            try {
                val track = withContext(Dispatchers.Default) {
                    RustCoreSession.detectBeats(sourcePath)
                }
                _beats.update { it + (sourcePath to track) }
            } catch (_: Exception) {
                _beats.update { it + (sourcePath to BeatTrack()) }
            } finally {
                beatsInFlight.remove(sourcePath)
            }
        }
    }

    /** Snap a playhead position to the nearest beat of [sourcePath]. */
    fun snapToBeat(sourcePath: String, timeMs: Long, toleranceMs: Long = 150L): Long? =
        _beats.value[sourcePath]?.snap(timeMs, toleranceMs)

    val beatMarkersMs: StateFlow<List<Long>> =
        combine(_beats, rustStore.snapshot) { beats, snapshot ->
            beatMarkersIn(beats, snapshot)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun beatMarkersIn(
        beats: Map<String, BeatTrack>,
        snapshot: RustProjectSnapshot?,
    ): List<Long> {
        if (beats.isEmpty() || snapshot == null) return emptyList()
        val markers = mutableListOf<Long>()
        for (clip in snapshot.timeline.tracks.flatMap { it.clips }) {
            val source = when (val kind = clip.kind) {
                is RustVideoClipKind -> kind.sourcePath to
                    kind.filters.any { it.filter is RustReverseVideoFilterSnapshot }
                is RustAudioClipKind -> kind.sourcePath to
                    kind.filters.any { it is RustReverseAudioFilterSnapshot }
                else -> null
            } ?: continue
            if (source.second) continue
            val onsets = beats[source.first]?.onsetsMs
            if (onsets.isNullOrEmpty()) continue
            val speed = if (clip.speed.isFinite() && clip.speed > 0.0) clip.speed else 1.0
            val sourceStartMs = clip.sourceStartUs / 1000.0
            val clipStartMs = clip.timelineStartUs / 1000.0
            val clipDurationMs = clip.timelineDurationUs / 1000.0
            for (onsetMs in onsets) {
                val localMs = (onsetMs - sourceStartMs) / speed
                if (localMs < 0.0 || localMs > clipDurationMs) continue
                markers += (clipStartMs + localMs).toLong()
            }
        }
        markers.sort()
        return markers
    }

    init {
        viewModelScope.launch {
            settingsRepository.flow.collect { settings ->
                hardwareAccelerationEnabled = settings.hardwareAccelerationEnabled
            }
        }
    }

    init {
        viewModelScope.launch {
            rustStore.snapshot.collect { snapshot ->
                val clips = snapshot?.timeline?.tracks?.flatMap { it.clips } ?: return@collect
                for (clip in clips) {
                    when (val kind = clip.kind) {
                        is RustVideoClipKind -> requestBeats(kind.sourcePath)
                        is RustAudioClipKind -> requestBeats(kind.sourcePath)
                        else -> Unit
                    }
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            ProjectRecovery.beginSession()
            _pendingRecovery.value = ProjectRecovery.pending()
        }
    }

    private suspend fun writeRecoveryIfNeeded() {
        val state = _state.value
        if (!state.isDirty || state.isSaving) return
        if (rustStore.snapshot.value == null) return
        if (rustStore.transactionOpen()) return
        if (exportProgress.value.isActive()) return
        val projectJson = try {
            rustStore.exportProjectJson()
        } catch (_: Exception) {
            return
        }
        if (projectJson == lastRecoveryJson) return
        ProjectRecovery.writeRecovery(
            projectJson = projectJson,
            projectPath = state.projectPath,
            projectName = state.snapshot?.meta?.name,
        )
        lastRecoveryJson = projectJson
    }

    fun discardPendingRecovery() {
        viewModelScope.launch {
            ProjectRecovery.discard()
            _pendingRecovery.value = null
        }
    }

    fun newProject(
        name: String,
        initialVideoPath: String,
        savePath: String? = null,
        extraImportPaths: List<String> = emptyList(),
    ) {
        val cappedExtraImports = extraImportPaths.take(MAX_EXTRA_IMPORT_PATHS)
        viewModelScope.launch {
            projectLifecycleMutex.lock()
            cancelInFlightMediaWork()
            try {
            _state.update { it.copy(isLoading = true) }
            try {
                val stagedVideoPath = PlatformFileSystem.stageForNativeAccess(initialVideoPath)
                val info = engine.probeVideo(stagedVideoPath)
                if (!info.hasVideo && !info.hasAudio) {
                    handleError("Selected file has no video or audio stream")
                    return@launch
                }

                val hasVideo = info.hasVideo
                val hasAudio = info.hasAudio

                if (info.durationMs <= 0L) {
                    snackbarManager.show("Warning: Could not determine video duration - file may not play correctly")
                }

                sourceDurationMs = info.durationMs

                rustStore.create(name)
                clearClipboard()
                val videoTrackId = if (hasVideo) ensureTrack(RustTrackKind.Video, "Video 1") else null
                val audioTrackId = if (hasAudio) ensureTrack(RustTrackKind.Audio, "Audio 1") else null

                val durationUs = info.durationMs.msToUs

                val commands = mutableListOf<String>()

                if (hasVideo && videoTrackId != null) {
                    commands.add(
                        rustStore.commands.addClip(
                            videoTrackId,
                            RustClipSnapshot(
                                id = randomUuid(),
                                timelineStartUs = 0L,
                                timelineDurationUs = durationUs,
                                sourceStartUs = 0L,
                                sourceEndUs = durationUs,
                                sourceTotalDurationUs = durationUs,
                                speed = 1.0,
                                volume = if (hasAudio) 0.0f else 1.0f,
                                opacity = 1.0f,
                                muted = false,
                                transitionIn = null,
                                transitionOut = null,
                                kind = RustVideoClipKind(
                                    sourcePath = stagedVideoPath,
                                    width = info.width,
                                    height = info.height,
                                    fps = if (info.frameRate > 0.0) info.frameRate else 30.0,
                                    filters = emptyList(),
                                    audioFilters = emptyList(),
                                ),
                            ),
                        )
                    )
                }

                if (hasAudio && audioTrackId != null) {
                    commands.add(
                        rustStore.commands.addClip(
                            audioTrackId,
                            RustClipSnapshot(
                                id = randomUuid(),
                                timelineStartUs = 0L,
                                timelineDurationUs = durationUs,
                                sourceStartUs = 0L,
                                sourceEndUs = durationUs,
                                sourceTotalDurationUs = durationUs,
                                speed = 1.0,
                                volume = 1.0f,
                                opacity = 1.0f,
                                muted = false,
                                transitionIn = null,
                                transitionOut = null,
                                kind = RustAudioClipKind(
                                    sourcePath = stagedVideoPath,
                                    sampleRate = info.audioSampleRate.coerceAtLeast(MIN_SAMPLE_RATE),
                                    channels = info.audioChannels.coerceAtLeast(1),
                                    filters = emptyList(),
                                ),
                            ),
                        )
                    )
                }

                rustStore.dispatch(rustStore.commands.batch("NewProjectInitialClips", commands))
                syncFromRust(
                    projectPath = savePath,
                    selectedTrackId = videoTrackId ?: audioTrackId ?: "",
                    selectedClipId = null,
                    playheadMs = 0L,
                    isDirty = true,
                )

                if (hasVideo) {
                    loadThumbnails(stagedVideoPath)
                }
                if (savePath != null) {
                    recentProjectsRepository.addRecent(savePath, name)
                }
                if (cappedExtraImports.isNotEmpty()) {
                    importMediaPathsInternal(cappedExtraImports)
                }
            } catch (e: Exception) {
                Napier.e("Failed to open media", e)
                handleError("Failed to open media: ${e.message}")
            }
            } finally {
                projectLifecycleMutex.unlock()
            }
        }
    }

    fun loadProject(path: String) {
        viewModelScope.launch {
            projectLifecycleMutex.lock()
            cancelInFlightMediaWork()
            try {
            _state.update { it.copy(isLoading = true) }
            try {
                val projectJson = PlatformFileSystem.readText(path)
                if (projectJson.isBlank()) {
                    handleError("Selected project file is empty")
                    return@launch
                }

                val snapshot = rustStore.openProjectJson(projectJson)
                clearClipboard()

                val missingFiles = snapshot.timeline.tracks
                    .flatMap { it.clips }
                    .mapNotNull { clip ->
                        when (val kind = clip.kind) {
                            is RustVideoClipKind -> kind.sourcePath
                            is RustAudioClipKind -> kind.sourcePath
                            is RustTextClipKind -> null
                            is RustSubtitleClipKind -> kind.sourcePath
                            is RustAdjustmentClipKind -> null
                        }
                    }
                    .distinct()
                    .filter { !PlatformFileSystem.exists(it) }

                if (missingFiles.isNotEmpty()) {
                    snackbarManager.showError(
                        "Missing ${missingFiles.size} source file(s). Some clips may not preview or export."
                    )
                }

                syncFromRust(
                    projectPath = path,
                    selectedTrackId = null,
                    selectedClipId = null,
                    playheadMs = 0L,
                    isDirty = false,
                )

                val firstClip = snapshot.timeline.tracks
                    .flatMap { it.clips }
                    .firstNotNullOfOrNull { clip -> (clip.kind as? RustVideoClipKind)?.let { kind -> clip to kind } }

                if (firstClip != null) {
                    val clip = firstClip.first
                    val sourcePath = firstClip.second.sourcePath
                    sourceDurationMs = try {
                        engine.probeVideo(sourcePath).durationMs
                    } catch (e: Exception) {
                        Napier.e("Failed to probe video for thumbnails", e)
                        clip.timelineDurationUs / 1000L
                    }
                    loadThumbnails(sourcePath)
                }

                recentProjectsRepository.addRecent(path, snapshot.meta.name)
            } catch (e: Exception) {
                handleError("Failed to load project: ${e.message}")
            }
            } finally {
                projectLifecycleMutex.unlock()
            }
        }
    }

    fun saveProject(path: String? = null) {
        viewModelScope.launch {
            if (_state.value.isSaving) return@launch
            if (!saveMutex.tryLock()) return@launch
            try {
                val snapshot = rustStore.snapshot.value ?: return@launch
                val savePath = path ?: _state.value.projectPath ?: return@launch
                _state.update { it.copy(isSaving = true) }
                try {
                    val projectJson = rustStore.exportProjectJson()
                    PlatformFileSystem.writeTextDurable(savePath, projectJson)
                    lastRecoveryJson = projectJson
                    ProjectRecovery.clearAfterSave()
                    _state.update { it.copy(projectPath = savePath, isSaving = false, isDirty = false) }
                    recentProjectsRepository.addRecent(savePath, snapshot.meta.name)
                } catch (e: Exception) {
                    _state.update { it.copy(isSaving = false) }
                    snackbarManager.showError("Failed to save: ${e.message}")
                }
            } finally {
                saveMutex.unlock()
            }
        }
    }

    fun renameProject(newName: String) {
        try {
            rustStore.dispatch(rustStore.commands.renameProject(newName))
            syncFromRust(isDirty = true)
        } catch (e: RustDispatchException) {
            Napier.w("RenameProject command rejected, falling back to replaceSnapshot (clears undo): ${e.message}")
            try {
                val fresh = rustStore.snapshot.value ?: return
                rustStore.replaceSnapshot(
                    fresh.copy(
                        meta = fresh.meta.copy(
                            name = newName,
                            modifiedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                    )
                )
                syncFromRust(isDirty = true)
            } catch (e2: Exception) {
                handleError("Failed to rename project: ${e2.message}")
            }
        } catch (e: Exception) {
            handleError("Failed to rename project: ${e.message}")
        }
    }

    fun startAutoSave(enabled: Boolean, intervalSeconds: Float) {
        autoSaveJob?.cancel()
        if (!enabled) return
        if (!intervalSeconds.isFinite() || intervalSeconds < 1f || intervalSeconds > 3600f) return
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay((intervalSeconds * 1000).toLong())
                try {
                    writeRecoveryIfNeeded()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    fun undo() {
        try {
            rustStore.undo() ?: return
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError("Undo failed: ${e.message}")
        }
    }

    fun redo() {
        try {
            rustStore.redo() ?: return
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError("Redo failed: ${e.message}")
        }
    }

    fun undoHistoryEntries(): List<UndoHistoryEntry> = rustStore.historyEntries()

    fun jumpToHistory(index: Int) {
        try {
            rustStore.jumpToHistory(index) ?: return
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError("History jump failed: ${e.message}")
        }
    }

    fun beginContinuousEdit(label: String = "Edit") {
        rustStore.beginEdit(label)
        preDragSnapshot = rustStore.snapshot.value
        continuousEditCommandCount = 0
    }

    fun commitContinuousEdit() {
        try {
            rustStore.commitEdit()
            preDragSnapshot = null
            continuousEditCommandCount = 0
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError("Failed to commit edit: ${e.message}")
        }
    }

    fun cancelContinuousEdit() {
        try {
            val wasOpen = rustStore.transactionOpen()
            val result = try {
                rustStore.cancelEdit()
            } catch (e: Exception) {
                handleError("Failed to cancel edit: ${e.message}")
                null
            }
            if (result == null && wasOpen) {
                val backup = preDragSnapshot
                if (backup != null) {
                    try {
                        rustStore.replaceSnapshot(backup)
                    } catch (e: Exception) {
                        handleError("Failed to restore pre-drag state: ${e.message}")
                    }
                }
            }
            preDragSnapshot = null
            continuousEditCommandCount = 0
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError("Failed to cancel edit: ${e.message}")
        }
    }

    fun beginEdit(label: String = "Move") = beginContinuousEdit(label)
    fun commitEdit() = commitContinuousEdit()
    fun cancelEdit() = cancelContinuousEdit()

    private fun dispatchCoalescing(commandJson: String, label: String): Boolean {
        return try {
            if (rustStore.transactionOpen()) {
                rustStore.dispatchOpen(commandJson)
            } else {
                rustStore.dispatchWithLabel(commandJson, label)
            }
            syncFromRust(isDirty = true)
            if (preDragSnapshot != null) continuousEditCommandCount++
            true
        } catch (e: Exception) {
            handleError("Edit failed ($label): ${e.message}")
            false
        }
    }

    private fun dispatchAndSync(
        commandJson: String,
        selectedTrackId: String? = _state.value.selectedTrackId,
        selectedClipId: String? = _state.value.selectedClipId,
    ): Boolean {
        return try {
            rustStore.dispatch(commandJson)
            syncFromRust(
                selectedTrackId = selectedTrackId,
                selectedClipId = selectedClipId,
                isDirty = true,
            )
            true
        } catch (e: Exception) {
            handleError(e.message ?: "Command failed")
            false
        }
    }

    fun validateCurrentPlan(width: Int = 1920, height: Int = 1080): String? {
        val json = rustStore.validateRenderPlanAtPlayhead(width, height) ?: return null
        _state.update { it.copy(lastValidationJson = json) }
        return json
    }

    fun validateAndLog(width: Int = 1920, height: Int = 1080): Boolean {
        val json = validateCurrentPlan(width, height) ?: return true
        val hasViolations = json.trim() != "[]"
        if (hasViolations) {
            Napier.w("validate_frame_plan violations: $json")
        }
        return !hasViolations
    }

    fun initProject(
        videoPath: String,
        projectName: String,
        savePath: String?,
        openAsProject: Boolean = false,
        resolution: RustExportResolution? = null,
        fps: Int? = null,
        extraImportPaths: List<String> = emptyList(),
        restoreRecovery: Boolean = false,
    ) {
        val capped = extraImportPaths.take(MAX_EXTRA_IMPORT_PATHS)
        when {
            restoreRecovery -> restoreRecoveryCopy()
            openAsProject -> loadProject(videoPath)
            videoPath.isEmpty() -> createBlankProject(projectName, savePath, resolution ?: RustExportResolution.Hd1080, fps ?: 30, capped)
            else -> newProject(projectName, videoPath, savePath, capped)
        }
    }

    private fun restoreRecoveryCopy() {
        viewModelScope.launch {
            projectLifecycleMutex.lock()
            cancelInFlightMediaWork()
            try {
                _state.update { it.copy(isLoading = true) }
                try {
                    val projectJson = ProjectRecovery.readRecoveryJson()
                    if (projectJson.isNullOrBlank()) {
                        handleError("Recovery copy is missing or unreadable")
                        return@launch
                    }
                    val snapshot = rustStore.openProjectJson(projectJson)
                    clearClipboard()

                    val missingFiles = snapshot.timeline.tracks
                        .flatMap { it.clips }
                        .mapNotNull { clip ->
                            when (val kind = clip.kind) {
                                is RustVideoClipKind -> kind.sourcePath
                                is RustAudioClipKind -> kind.sourcePath
                                is RustSubtitleClipKind -> kind.sourcePath
                                else -> null
                            }
                        }
                        .distinct()
                        .filter { !PlatformFileSystem.exists(it) }
                    if (missingFiles.isNotEmpty()) {
                        snackbarManager.showError(
                            "Missing ${missingFiles.size} source file(s). Some clips may not preview or export."
                        )
                    }

                    ProjectRecovery.claim(projectPath = null, projectName = snapshot.meta.name)
                    syncFromRust(
                        projectPath = null,
                        selectedTrackId = null,
                        selectedClipId = null,
                        playheadMs = 0L,
                        isDirty = true,
                    )
                    lastRecoveryJson = null
                    _pendingRecovery.value = null
                    snackbarManager.show(
                        "Recovered unsaved changes for '${snapshot.meta.name}'. Save the project to keep them."
                    )

                    val firstClip = snapshot.timeline.tracks
                        .flatMap { it.clips }
                        .firstNotNullOfOrNull { clip -> (clip.kind as? RustVideoClipKind)?.let { kind -> clip to kind } }
                    if (firstClip != null) {
                        val sourcePath = firstClip.second.sourcePath
                        sourceDurationMs = try {
                            engine.probeVideo(sourcePath).durationMs
                        } catch (e: Exception) {
                            Napier.e("Failed to probe video for thumbnails", e)
                            firstClip.first.timelineDurationUs / 1000L
                        }
                        loadThumbnails(sourcePath)
                    }
                } catch (e: Exception) {
                    handleError("Failed to load recovery copy: ${e.message}")
                }
            } finally {
                projectLifecycleMutex.unlock()
            }
        }
    }

    fun createBlankProject(
        name: String,
        savePath: String? = null,
        resolution: RustExportResolution = RustExportResolution.Hd1080,
        fps: Int = 30,
        extraImportPaths: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                rustStore.create(name)
                clearClipboard()
                val currentProfile = rustStore.snapshot.value?.exportProfile
                if (currentProfile != null) {
                    try {
                        rustStore.dispatch(
                            rustStore.commands.setExportProfile(
                                currentProfile.copy(resolution = resolution, fps = fps.toDouble())
                            ),
                        )
                    } catch (e: Exception) {
                        handleError("Failed to set export profile: ${e.message}")
                    }
                }
                syncFromRust(
                    projectPath = savePath,
                    selectedTrackId = null,
                    selectedClipId = null,
                    playheadMs = 0L,
                    isDirty = true,
                )
                if (savePath != null) {
                    recentProjectsRepository.addRecent(savePath, name)
                }
                val cappedExtraImports = extraImportPaths.take(MAX_EXTRA_IMPORT_PATHS)
                if (cappedExtraImports.isNotEmpty()) {
                    importMediaPathsInternal(cappedExtraImports)
                }
            } catch (e: Exception) {
                Napier.e("Failed to create project", e)
                handleError("Failed to create project: ${e.message}")
            }
        }
    }

    fun togglePlayPause() {
        setPlaying(!_state.value.isPlaying)
    }

    fun seekTo(ms: Long) = setPlayhead(computeSnapPosition(ms, includePlayhead = false))

    fun seekRelative(deltaMs: Long) {
        val current = _state.value.playheadMs
        val maxMs = if (rustStore.snapshot.value != null) {
            try {
                rustStore.durationUs().usToMs
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
        val target = (current.toDouble() + deltaMs.toDouble())
            .coerceIn(0.0, maxMs.coerceAtLeast(0L).toDouble())
            .toLong()
        setPlayhead(target)
    }

    fun seekToStart() = setPlayhead(0)

    fun seekToEnd() {
        val endMs = if (rustStore.snapshot.value != null) {
            try {
                rustStore.durationUs().usToMs
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
        setPlayhead(endMs)
    }

    fun zoomIn() {
        setZoom(_state.value.zoomLevel * 1.25f)
    }

    fun zoomOut() {
        setZoom(_state.value.zoomLevel / 1.25f)
    }

    fun addTextOverlay() {
        try {
            val textTrackId = ensureTrack(RustTrackKind.Text, "Text 1")
            val trackSnapshot = rustStore.snapshot.value?.timeline?.tracks
                ?.firstOrNull { it.id == textTrackId }
            val playheadUs = _state.value.playheadMs.msToUs
            val timelineStartUs = if (trackSnapshot != null) {
                findNearestNonOverlappingStartUs(
                    track = trackSnapshot,
                    clipId = null,
                    requestedStartUs = playheadUs,
                    durationUs = 3_000_000L,
                )
            } else {
                playheadUs
            }
            val clipId = randomUuid()

            dispatchAndSync(
                rustStore.commands.addClip(
                    textTrackId,
                    RustClipSnapshot(
                        id = clipId,
                        timelineStartUs = timelineStartUs,
                        timelineDurationUs = 3_000_000L,
                        sourceStartUs = 0L,
                        sourceEndUs = 3_000_000L,
                        sourceTotalDurationUs = 3_000_000L,
                        speed = 1.0,
                        volume = 1.0f,
                        opacity = 1.0f,
                        muted = false,
                        transitionIn = null,
                        kind = RustTextClipKind(
                            text = "Text",
                            style = RustTextStyleSnapshot(),
                        ),
                    ),
                ),
                selectedTrackId = textTrackId,
                selectedClipId = clipId,
            )
        } catch (e: Exception) {
            handleError("Failed to add text: ${e.message}")
        }
    }

    fun addAdjustmentLayer() {
        try {
            val trackId = randomUuid()
            val adjustmentCount = rustStore.snapshot.value?.timeline?.tracks
                ?.count { track -> track.clips.any { it.kind is RustAdjustmentClipKind } } ?: 0
            val playheadUs = _state.value.playheadMs.msToUs
            val clipId = randomUuid()
            val clip = RustClipSnapshot(
                id = clipId,
                timelineStartUs = playheadUs,
                timelineDurationUs = DEFAULT_ADJUSTMENT_CLIP_DURATION_US,
                sourceStartUs = 0L,
                sourceEndUs = DEFAULT_ADJUSTMENT_CLIP_DURATION_US,
                sourceTotalDurationUs = DEFAULT_ADJUSTMENT_CLIP_DURATION_US,
                speed = 1.0,
                volume = 1.0f,
                opacity = 1.0f,
                muted = false,
                transitionIn = null,
                transitionOut = null,
                kind = RustAdjustmentClipKind(),
            )
            dispatchAndSync(
                rustStore.commands.batch(
                    "Add adjustment layer",
                    listOf(
                        rustStore.commands.addTrack(
                            RustTrackKind.Video,
                            "Adjustment ${adjustmentCount + 1}",
                            trackId,
                        ),
                        rustStore.commands.addClip(trackId, clip),
                    ),
                ),
                selectedTrackId = trackId,
                selectedClipId = clipId,
            )
        } catch (e: Exception) {
            handleError("Failed to add adjustment layer: ${e.message}")
        }
    }

    fun deleteSelectedClip() {
        val snapshot = rustStore.snapshot.value ?: return
        deleteClipIds(orderedClipIds(snapshot, _state.value.selectedClipIds))
    }

    private fun deleteClipIds(clipIds: List<String>) {
        if (clipIds.isEmpty()) return
        beginContinuousEdit("Delete")
        var failed = false
        try {
            fun send(command: String): Boolean {
                if (failed) return false
                if (!dispatchCoalescing(command, "Delete")) {
                    failed = true
                    return false
                }
                return true
            }
            for (clipId in clipIds) {
                if (!send(rustStore.commands.removeClip(clipId))) break
            }
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    fun setPlaying(playing: Boolean) {
        _state.update { it.copy(isPlaying = playing) }
    }

    fun setPlayhead(ms: Long) {
        val durationMs = if (rustStore.snapshot.value != null) {
            try {
                rustStore.durationUs().usToMs
            } catch (_: Exception) {
                ms.coerceAtLeast(0L)
            }
        } else {
            Long.MAX_VALUE / 1000L
        }
        val safe = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        try {
            rustStore.setPlayheadUs(safe.msToUs)
        } catch (e: Exception) {
            handleError("Failed to seek: ${e.message}")
            return
        }
        _state.update { it.copy(playheadMs = safe) }
    }

    fun importMediaFiles(files: List<PlatformFile>) {
        importMediaPaths(files.map { it.platformPath() })
    }

    fun importMediaPaths(paths: List<String>) {
        viewModelScope.launch {
            if (!projectLifecycleMutex.tryLock()) {
                snackbarManager.showError("Please wait until the project finishes loading")
                return@launch
            }
            try {
                importMediaPathsInternal(paths)
            } finally {
                projectLifecycleMutex.unlock()
            }
        }
    }

    private suspend fun importMediaPathsInternal(paths: List<String>) {
        val initialSnapshot = rustStore.snapshot.value ?: return
        if (paths.isEmpty()) return
        if (_state.value.importProgress != null) {
            snackbarManager.showError("Import already in progress")
            return
        }
        _state.update { it.copy(isLoading = true) }

        try {
                val selected = initialSnapshot.timeline.tracks
                    .flatMap { track -> track.clips.map { track.id to it } }
                    .firstOrNull { (_, clip) -> clip.id == _state.value.selectedClipId }

                val initialCursorMs = selected?.second
                    ?.let { (it.timelineStartUs + it.timelineDurationUs) / 1000L }
                    ?: _state.value.playheadMs

                data class ImportItem(
                    val path: String,
                    val stagedPath: String,
                    val info: VideoInfo,
                )

                val total = paths.size
                val items = mutableListOf<ImportItem>()
                paths.forEachIndexed { index, path ->
                    val fileName = path.substringAfterLast("/").substringAfterLast("\\")
                    _state.update {
                        it.copy(
                            importProgress = ImportProgress(
                                current = index,
                                total = total,
                                currentFile = fileName,
                                stage = "Preparing",
                                fraction = null,
                            ),
                        )
                    }
                    try {
                        val stagedPath = PlatformFileSystem.stageForNativeAccess(path)
                        _state.update {
                            it.copy(
                                importProgress = ImportProgress(
                                    current = index,
                                    total = total,
                                    currentFile = fileName,
                                    stage = "Probing",
                                    fraction = if (total > 1) index.toFloat() / total else null,
                                ),
                            )
                        }
                        items.add(ImportItem(path, stagedPath, engine.probeVideo(stagedPath)))
                    } catch (e: Exception) {
                        Napier.e("Skipping unimportable file $path", e)
                        snackbarManager.showError("Skipped '$fileName': ${e.message}")
                    }
                }
                if (items.isEmpty()) {
                    _state.update { it.copy(isLoading = false, importProgress = null) }
                    return
                }

                var cursorVideoUs = initialCursorMs.msToUs
                var cursorAudioUs = initialCursorMs.msToUs

                var videoTrackId: String? =
                    initialSnapshot.timeline.tracks.firstOrNull { it.kind == RustTrackKind.Video }?.id

                var audioTrackId: String? =
                    rustStore.snapshot.value?.timeline?.tracks
                        ?.firstOrNull { it.kind == RustTrackKind.Audio }?.id

                data class ImportTarget(
                    val trackId: String,
                    val cursor: Long,
                    val startUs: Long = cursor,
                )

                fun resolveTrack(
                    kind: RustTrackKind,
                    cursorUs: Long,
                    durationUs: Long,
                    currentTrackId: String?,
                    labelPrefix: String,
                ): ImportTarget {
                    val startUs = cursorUs
                    val endUs = if (durationUs > Long.MAX_VALUE - startUs) Long.MAX_VALUE
                        else startUs + durationUs

                    var trackId = currentTrackId
                    if (trackId == null) {
                        trackId = try {
                            ensureTrack(kind, "$labelPrefix 1")
                        } catch (e: Exception) {
                            Napier.e("Failed to ensure $kind track", e)
                            return ImportTarget("", cursorUs)
                        }
                    }

                    val snap = rustStore.snapshot.value ?: return ImportTarget(trackId, cursorUs)
                    val currentTrackSnapshot = snap.timeline.tracks
                        .firstOrNull { it.id == trackId && it.kind == kind }
                    val gapStart = if (currentTrackSnapshot != null) {
                        findNearestNonOverlappingStartUs(
                            track = currentTrackSnapshot,
                            clipId = null,
                            requestedStartUs = startUs,
                            durationUs = durationUs,
                        )
                    } else {
                        startUs
                    }
                    val resolvedStartUs = gapStart ?: startUs
                    val resolvedEndUs = if (durationUs > Long.MAX_VALUE - resolvedStartUs) Long.MAX_VALUE
                        else resolvedStartUs + durationUs
                    val currentTrack = snap.timeline.tracks
                        .firstOrNull { it.id == trackId && it.kind == kind }

                    val hasConflict = currentTrack?.clips?.any { clip ->
                        clip.overlapsRange(resolvedStartUs, resolvedEndUs)
                    } ?: false

                    val resolvedId = if (!hasConflict) {
                        trackId
                    } else {
                        val alternate = snap.timeline.tracks
                            .filter { it.kind == kind && it.id != trackId }
                            .firstOrNull { track ->
                                track.clips.none { clip -> clip.overlapsRange(resolvedStartUs, resolvedEndUs) }
                            }

                        if (alternate != null) {
                            alternate.id
                        } else {
                            val count = snap.timeline.tracks.count { it.kind == kind }
                            val label = "$labelPrefix ${count + 1}"
                            try {
                                rustStore.dispatch(rustStore.commands.addTrack(kind, label))
                            } catch (e: Exception) {
                                Napier.e("Failed to add overflow $kind track", e)
                                return ImportTarget(trackId, cursorUs)
                            }
                            rustStore.snapshot.value
                                ?.timeline?.tracks?.last { it.kind == kind }?.id
                                ?: trackId
                        }
                    }

                    return ImportTarget(resolvedId, resolvedEndUs, resolvedStartUs)
                }

                var added = 0
                for (item in items) {
                    val info = item.info
                    val hasVideo = info.hasVideo
                    val hasAudio = info.hasAudio
                    val isImage = item.path.substringAfterLast(".").lowercase() in SupportedFormats.imageExtensions
                        .map { it.lowercase() }
                    // Still images probe with zero duration. give them a default
                    // 5s timeline duration so logos/overlays/PiP just work.
                    // Probe may report hasVideo=false for stills, so key off extension alone.
                    val durationUs = if (info.durationMs.msToUs <= 0L && isImage) {
                        DEFAULT_IMAGE_CLIP_DURATION_US
                    } else {
                        info.durationMs.msToUs
                    }

                    if (durationUs <= 0L) {
                        snackbarManager.show("Warning: Skipping '${item.path.substringAfterLast("/").substringAfterLast("\\")}' (zero duration)")
                        continue
                    }

                    val baseUs = maxOf(cursorVideoUs, cursorAudioUs)
                    var fileAdded = false

                    if (hasVideo) {
                        val target = resolveTrack(RustTrackKind.Video, baseUs, durationUs, videoTrackId, "Video")
                        if (target.trackId.isNotEmpty()) {
                            videoTrackId = target.trackId
                            val videoVolume = if (hasAudio) 0.0f else 1.0f
                            val videoStartUs = target.startUs
                            try {
                                dispatchSilent(
                                    rustStore.commands.addClip(
                                        target.trackId,
                                        RustClipSnapshot(
                                            id = randomUuid(),
                                            timelineStartUs = videoStartUs,
                                            timelineDurationUs = durationUs,
                                            sourceStartUs = 0L,
                                            sourceEndUs = durationUs,
                                            sourceTotalDurationUs = durationUs,
                                            speed = 1.0,
                                            volume = videoVolume,
                                            opacity = 1.0f,
                                            muted = false,
                                            transitionIn = null,
                                            transitionOut = null,
                                            kind = RustVideoClipKind(
                                                sourcePath = item.stagedPath,
                                                width = info.width,
                                                height = info.height,
                                                fps = if (info.frameRate > 0.0) info.frameRate else 30.0,
                                                filters = emptyList(),
                                                audioFilters = emptyList(),
                                            ),
                                        ),
                                    )
                                )
                                cursorVideoUs = target.cursor
                                fileAdded = true
                            } catch (e: Exception) {
                                Napier.e("Skipping video clip for ${item.path}", e)
                                snackbarManager.showError("Skipped video in '${item.path.substringAfterLast("/")}': ${e.message}")
                            }
                        }
                    }

                    if (hasAudio) {
                        val target = resolveTrack(RustTrackKind.Audio, baseUs, durationUs, audioTrackId, "Audio")
                        if (target.trackId.isNotEmpty()) {
                            audioTrackId = target.trackId
                            val audioStartUs = target.startUs
                            try {
                                dispatchSilent(
                                    rustStore.commands.addClip(
                                        target.trackId,
                                        RustClipSnapshot(
                                            id = randomUuid(),
                                            timelineStartUs = audioStartUs,
                                            timelineDurationUs = durationUs,
                                            sourceStartUs = 0L,
                                            sourceEndUs = durationUs,
                                            sourceTotalDurationUs = durationUs,
                                            speed = 1.0,
                                            volume = 1.0f,
                                            opacity = 1.0f,
                                            muted = false,
                                            transitionIn = null,
                                            transitionOut = null,
                                            kind = RustAudioClipKind(
                                                sourcePath = item.stagedPath,
                                                sampleRate = info.audioSampleRate.coerceAtLeast(MIN_SAMPLE_RATE),
                                                channels = info.audioChannels.coerceAtLeast(1),
                                                filters = emptyList(),
                                            ),
                                        ),
                                    )
                                )
                                cursorAudioUs = target.cursor
                                fileAdded = true
                            } catch (e: Exception) {
                                Napier.e("Skipping audio clip for ${item.path}", e)
                                snackbarManager.showError("Skipped audio in '${item.path.substringAfterLast("/")}': ${e.message}")
                            }
                        }
                    }

                    if (fileAdded) added++
                    _state.update {
                        it.copy(
                            snapshot = rustStore.snapshot.value,
                            isLoading = true,
                            importProgress = ImportProgress(
                                current = added,
                                total = total,
                                currentFile = item.path.substringAfterLast("/").substringAfterLast("\\"),
                                stage = "Adding to timeline",
                                fraction = if (total > 1) added.toFloat() / total else null,
                            ),
                        )
                    }
                }

                syncFromRust()
                _state.update { it.copy(isLoading = false, importProgress = null) }
                snackbarManager.show("Imported $added file(s)")
            } catch (e: Exception) {
                Napier.e("Failed to import media", e)
                handleError("Failed to import: ${e.message}", clearImportState = true)
            }
    }

    fun importSubtitleFiles(files: List<PlatformFile>) {
        viewModelScope.launch {
            val initialSnapshot = rustStore.snapshot.value ?: return@launch
            if (files.isEmpty()) return@launch
            if (_state.value.importProgress != null) {
                snackbarManager.showError("Import already in progress")
                return@launch
            }
            _state.update { it.copy(isLoading = true) }

            try {
                var subtitleTrackId = initialSnapshot.timeline.tracks
                    .firstOrNull { it.kind == RustTrackKind.Subtitle }?.id

                if (subtitleTrackId == null) {
                    subtitleTrackId = ensureTrack(RustTrackKind.Subtitle, "Subtitles")
                }

                var cursorUs = _state.value.playheadMs.msToUs

                val total = files.size
                files.forEachIndexed { index, file ->
                    val fileName = file.platformPath().substringAfterLast("/").substringAfterLast("\\")
                    _state.update {
                        it.copy(
                            importProgress = ImportProgress(
                                current = index,
                                total = total,
                                currentFile = fileName,
                                stage = "Importing subtitles",
                                fraction = null,
                            ),
                        )
                    }
                    val stagedPath = try {
                        PlatformFileSystem.stageForNativeAccess(file.platformPath())
                    } catch (e: Exception) {
                        Napier.e("Skipping subtitle $fileName", e)
                        snackbarManager.showError("Skipped subtitle '$fileName': ${e.message}")
                        return@forEachIndexed
                    }
                    val durationMs = DEFAULT_SUBTITLE_DURATION_MS
                    val durationUs = durationMs.msToUs

                    val trackSnapshot = rustStore.snapshot.value?.timeline?.tracks
                        ?.firstOrNull { it.id == subtitleTrackId }
                    val startUs = if (trackSnapshot != null) {
                        findNearestNonOverlappingStartUs(
                            track = trackSnapshot,
                            clipId = null,
                            requestedStartUs = cursorUs,
                            durationUs = durationUs,
                        )
                    } else {
                        cursorUs.coerceAtLeast(0L)
                    }

                    try {
                        dispatchSilent(
                            rustStore.commands.addClip(
                                subtitleTrackId,
                                RustClipSnapshot(
                                    id = randomUuid(),
                                    timelineStartUs = startUs,
                                    timelineDurationUs = durationUs,
                                    sourceStartUs = 0L,
                                    sourceEndUs = durationUs,
                                    sourceTotalDurationUs = durationUs,
                                    speed = 1.0,
                                    volume = 1.0f,
                                    opacity = 1.0f,
                                    muted = false,
                                    transitionIn = null,
                                    transitionOut = null,
                                    kind = RustSubtitleClipKind(sourcePath = stagedPath),
                                ),
                            )
                        )
                    } catch (e: Exception) {
                        Napier.e("Skipping subtitle clip $fileName", e)
                        snackbarManager.showError("Skipped subtitle '$fileName': ${e.message}")
                        return@forEachIndexed
                    }
                    cursorUs = startUs + durationUs
                    _state.update {
                        it.copy(
                            snapshot = rustStore.snapshot.value,
                            isLoading = true,
                            importProgress = ImportProgress(
                                current = index + 1,
                                total = total,
                                currentFile = fileName,
                                stage = "Adding to timeline",
                                fraction = if (total > 1) (index + 1).toFloat() / total else null,
                            ),
                        )
                    }
                }

                syncFromRust()
                _state.update { it.copy(isLoading = false, importProgress = null) }
                snackbarManager.show("Imported ${files.size} subtitle file(s)")
            } catch (e: Exception) {
                handleError("Failed to import subtitles: ${e.message}", clearImportState = true)
            }
        }
    }

    private fun loadThumbnails(videoPath: String) {
        thumbnailJob?.cancel()
        thumbnailRequestId += 1
        val requestId = thumbnailRequestId
        thumbnailRequestPath = videoPath
        thumbnailJob = viewModelScope.launch {
            // Clear the previous project's strip straight away: a proxy build
            // can take seconds, and stale frames would otherwise render as if
            // they belonged to this project.
            _state.update { it.copy(isLoadingThumbnails = true, thumbnails = emptyList()) }
            try {
                // A proxy that fails to build is not fatal: the original still decodes.
                // Cancellation must propagate, so it is not folded into the
                // same catch.
                val proxy = try {
                    ScrubProxy.ensure(videoPath, hardwareAccelerationEnabled)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (requestId != thumbnailRequestId) return@launch
                if (proxy?.ready == true) {
                    Napier.i(
                        "Scrub proxy ready for ${videoPath.substringAfterLast("/")}: " +
                            "${proxy.bytes} bytes" +
                            (if (proxy.width > 0) " at ${proxy.width}x${proxy.height}" else " (cached)")
                    )
                }
                val thumbs = engine.extractThumbnails(videoPath, 12, 160, 90, hardwareAccelerationEnabled)
                if (requestId != thumbnailRequestId) return@launch
                if (thumbnailRequestPath != videoPath) return@launch
                _state.update { it.copy(thumbnails = thumbs, isLoadingThumbnails = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId != thumbnailRequestId) return@launch
                _state.update { it.copy(thumbnails = emptyList(), isLoadingThumbnails = false) }
                val raw = e.message?.removePrefix("detail=")?.removePrefix("Non-Kotlin exception ")
                Napier.w("Preview unavailable for $videoPath: $raw")
                snackbarManager.show(
                    "Preview unavailable for '${videoPath.substringAfterLast("/")}': ${raw ?: "decoder error"}. Timeline remains editable; audio may still be usable."
                )
            }
        }
    }

    private var exportJob: Job? = null

    fun exportProject(outputPath: String) {
        if (exportJob?.isActive == true) return
        val previousExport = exportJob
        val probePaths = rustStore.snapshot.value
            ?.timeline?.tracks?.flatMap { it.clips }
            ?.mapNotNull { clipSnapshot -> (clipSnapshot.kind as? RustVideoClipKind)?.sourcePath }
            ?.distinct() ?: return
        exportJob = viewModelScope.launch {
            // A cancelled export stays inside a blocking native call until the
            // renderer notices the cancel flag. Wait for it to unwind before
            // touching the engine again, so the two cannot interleave: notePreparing
            // clears the cancel flag, and a late commit or progress write from
            // the old export would otherwise clobber this one.
            previousExport?.join()
            engine.notePreparing(outputPath)
            var exportError: String? = null
            for (path in probePaths) {
                try {
                    val info = engine.probeVideo(path)
                    if (!info.hasVideo) {
                        exportError = "'${path.substringAfterLast("/")}' has no video stream"
                        break
                    }
                    if (!info.videoDecoderAvailable) {
                        exportError = "'${path.substringAfterLast("/")}': no decoder available for ${info.videoCodecName ?: "unknown codec"}"
                        break
                    }
                    if (info.hardwareAccelerationRequired && !hardwareAccelerationEnabled) {
                        exportError = "'${path.substringAfterLast("/")}': ${info.videoCodecName ?: "unknown codec"} requires hardware acceleration (currently disabled)"
                        break
                    }
                } catch (e: Exception) {
                    exportError = "'${path.substringAfterLast("/")}': ${e.message}"
                    break
                }
            }

            if (exportError != null) {
                // notePreparing already marked the engine active; without this
                // the sheet would sit on "Preparing…" and refuse further starts.
                try {
                    engine.reset()
                } catch (_: Exception) { /* best effort progress reset */ }
                snackbarManager.showError("Cannot export: $exportError")
                return@launch
            }

            try {
                val projectJson = rustStore.exportProjectJson()
                engine.exportProjectJson(projectJson, outputPath)
            } catch (e: CancellationException) {
                // The cancel can land before the renderer ever runs (during the
                // probe or the notePreparing bookkeeping). The engine's own
                // completion path never executes then, so land the state here.
                try {
                    engine.markCancelled()
                } catch (_: Exception) { /* best effort progress reset */ }
                throw e
            } catch (e: Exception) {
                try {
                    engine.reset()
                } catch (_: Exception) { /* best effort progress reset */ }
                snackbarManager.showError("Export failed: ${e.message}")
            }
        }
    }

    fun cancelExport() {
        engine.cancelExport()
        exportJob?.cancel()
    }

    /**
     * Self-heal a progress state that no job will ever advance: a cancel that
     * raced the export job can park the engine on "Cancelling…" with the
     * renderer never having started.
     */
    fun reconcileExportProgress() {
        if (exportJob?.isActive == true) return
        val current = engine.exportProgress.value
        if (!current.isActive()) return
        try {
            engine.reset()
        } catch (_: Exception) { /* best effort progress reset */ }
    }

    fun resetExport() {
        cancelExport()
        engine.reset()
    }

    fun updateExportProfile(profile: RustExportProfileSnapshot): Boolean {
        if (exportJob?.isActive == true) return false
        return dispatchAndSync(rustStore.commands.setExportProfile(profile))
    }

    fun startExport(outputPath: String) {
        exportProject(outputPath)
    }

    fun reset() {
        stopAutoSave()
        cancelExport()
        thumbnailJob?.cancel()
        ScrubProxy.cancel()
        thumbnailRequestId += 1
        thumbnailRequestPath = null
        rustStore.clear()
        _state.update { ProjectUiState() }
        engine.reset()
    }

    fun addTrack(kind: RustTrackKind, label: String? = null) {
        val count = rustStore.snapshot.value?.timeline?.tracks?.count { it.kind == kind } ?: 0
        dispatchAndSync(rustStore.commands.addTrack(kind, label ?: "${kind.name} ${count + 1}"))
    }

    fun removeTrack(trackId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.find { it.id == trackId } ?: return
        if (track.clips.isNotEmpty()) {
            snackbarManager.showError("Delete this track's clips first")
            return
        }
        if (track.kind == RustTrackKind.Video && snapshot.timeline.tracks.count { it.kind == RustTrackKind.Video } <= 1) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        if (applySingleEdit("Remove track", rustStore.commands.removeTrack(trackId))) {
            if (_state.value.selectedTrackId == trackId) {
                _state.update { it.copy(selectedTrackId = null) }
            }
        }
    }

    fun moveTrack(trackId: String, toIndex: Int) {
        val tracks = rustStore.snapshot.value?.timeline?.tracks ?: return
        val fromIndex = tracks.indexOfFirst { it.id == trackId }
        if (fromIndex < 0 || toIndex < 0 || toIndex > tracks.lastIndex || toIndex == fromIndex) return
        applySingleEdit("Reorder track", rustStore.commands.moveTrack(trackId, toIndex))
    }

    fun moveTrackUp(trackId: String) {
        val tracks = rustStore.snapshot.value?.timeline?.tracks ?: return
        val fromIndex = tracks.indexOfFirst { it.id == trackId }
        if (fromIndex <= 0) return
        moveTrack(trackId, fromIndex - 1)
    }

    fun moveTrackDown(trackId: String) {
        val tracks = rustStore.snapshot.value?.timeline?.tracks ?: return
        val fromIndex = tracks.indexOfFirst { it.id == trackId }
        if (fromIndex < 0 || fromIndex >= tracks.lastIndex) return
        moveTrack(trackId, fromIndex + 1)
    }

    fun toggleTrackSolo(trackId: String) {
        val solo = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.solo ?: return
        applySingleEdit("Solo", rustStore.commands.setTrackSolo(trackId, !solo))
    }

    private fun applySingleEdit(label: String, commandJson: String): Boolean {
        beginContinuousEdit(label)
        var failed = false
        try {
            if (!dispatchCoalescing(commandJson, label)) failed = true
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
        return !failed
    }

    fun moveClipAbsolute(clipId: String, absoluteStartMs: Long) {
        val dragBase = preDragSnapshot
        if (dragBase != null && _state.value.selectedClipIds.size > 1 && clipId in _state.value.selectedClipIds) {
            moveSelectedClipsAbsolute(dragBase, clipId, absoluteStartMs)
            return
        }

        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return

        val requestedStartUs = absoluteStartMs.coerceAtLeast(0).msToUs
        val durationUs = clip.timelineDurationUs.coerceAtLeast(1L)
        val clampedStartUs = findNearestNonOverlappingStartUs(
            track = track,
            clipId = clipId,
            requestedStartUs = requestedStartUs,
            durationUs = durationUs,
        )

        dispatchCoalescing(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = track.id,
                newStartUs = clampedStartUs,
            ),
            "Move",
        )
    }

    private fun moveSelectedClipsAbsolute(
        dragBase: RustProjectSnapshot,
        anchorClipId: String,
        absoluteStartMs: Long,
    ) {
        val anchor = findClipWithTrack(dragBase, anchorClipId)?.second ?: return
        val deltaUs = absoluteStartMs.coerceAtLeast(0L).msToUs - anchor.timelineStartUs
        if (deltaUs == 0L) return
        val selectedIds = _state.value.selectedClipIds
        val moving = dragBase.timeline.tracks.flatMap { track ->
            track.clips.filter { it.id in selectedIds }.map { track to it }
        }
        if (moving.isEmpty()) return
        for ((track, clip) in moving) {
            val newStartUs = clip.timelineStartUs + deltaUs
            if (track.locked || newStartUs < 0L) return
            val newEndUs = newStartUs + clip.timelineDurationUs
            val collides = track.clips.any { other -> other.id !in selectedIds && other.overlapsRange(newStartUs, newEndUs) }
            if (collides) return
        }
        val ordered = if (deltaUs > 0L) {
            moving.sortedByDescending { it.second.timelineStartUs }
        } else {
            moving.sortedBy { it.second.timelineStartUs }
        }
        for ((track, clip) in ordered) {
            val moved = dispatchCoalescing(
                rustStore.commands.moveClip(
                    clipId = clip.id,
                    trackId = track.id,
                    newStartUs = clip.timelineStartUs + deltaUs,
                ),
                "Move",
            )
            if (!moved) return
        }
    }

    fun moveClipToTrack(clipId: String, fromTrackId: String, toTrackId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val clip = snapshot.timeline.tracks
            .firstOrNull { it.id == fromTrackId }
            ?.clips
            ?.firstOrNull { it.id == clipId }
            ?: return
        val targetTrack = snapshot.timeline.tracks
            .firstOrNull { it.id == toTrackId }
            ?: return

        val targetStartUs = findNearestNonOverlappingStartUs(
            track = targetTrack,
            clipId = clipId,
            requestedStartUs = clip.timelineStartUs,
            durationUs = clip.timelineDurationUs.coerceAtLeast(1L),
        )

        dispatchCoalescing(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = toTrackId,
                newStartUs = targetStartUs,
            ),
            "Move",
        )
    }

    fun moveSelectedClipsToTrack(anchorClipId: String, toTrackId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val tracks = snapshot.timeline.tracks
        val selectedIds = _state.value.selectedClipIds
        val anchorTrackIndex = tracks.indexOfFirst { track -> track.clips.any { it.id == anchorClipId } }
        val targetTrackIndex = tracks.indexOfFirst { track -> track.id == toTrackId }
        if (anchorTrackIndex < 0 || targetTrackIndex < 0 || targetTrackIndex == anchorTrackIndex) return
        val trackDelta = targetTrackIndex - anchorTrackIndex

        val moving = tracks.flatMapIndexed { index, track ->
            track.clips.filter { it.id in selectedIds }.map { index to it }
        }
        if (moving.isEmpty()) return

        for ((sourceIndex, clip) in moving) {
            val sourceTrack = tracks[sourceIndex]
            val destination = tracks.getOrNull(sourceIndex + trackDelta)
            if (destination == null) {
                snackbarManager.showError("Cannot move selection: it would run past the end of the track list")
                return
            }
            if (sourceTrack.locked) {
                snackbarManager.showError("Cannot move selection: '${sourceTrack.name}' is locked")
                return
            }
            if (destination.locked) {
                snackbarManager.showError("Cannot move selection: '${destination.name}' is locked")
                return
            }
            if (!trackAcceptsClip(destination, clip)) {
                snackbarManager.showError("Cannot move selection: '${destination.name}' does not accept this clip")
                return
            }
            val endUs = clip.timelineStartUs + clip.timelineDurationUs
            val collides = destination.clips.any { other ->
                other.id !in selectedIds && other.overlapsRange(clip.timelineStartUs, endUs)
            }
            if (collides) {
                snackbarManager.showError("Cannot move selection: clips would overlap on '${destination.name}'")
                return
            }
        }

        val ordered = if (trackDelta > 0) {
            moving.sortedByDescending { it.first }
        } else {
            moving.sortedBy { it.first }
        }
        val commands = ordered.map { (sourceIndex, clip) ->
            rustStore.commands.moveClip(
                clipId = clip.id,
                trackId = tracks[sourceIndex + trackDelta].id,
                newStartUs = clip.timelineStartUs,
            )
        }
        dispatchCoalescing(rustStore.commands.batch("Move selection", commands), "Move")
    }

    fun trimClipStartAbsolute(clipId: String, newStartMs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return
        val requestedStartUs = newStartMs.coerceAtLeast(0).msToUs
        val maxStartUs = clip.timelineStartUs + clip.timelineDurationUs - MIN_TRIM_DURATION_US
        if (maxStartUs < clip.timelineStartUs) {
            snackbarManager.showError("Clip is already at minimum duration")
            return
        }
        val prevEndUs = track.clips
            .filter { it.id != clipId && it.timelineStartUs < clip.timelineStartUs }
            .maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L
        val minStartUs = prevEndUs.coerceAtLeast(0L)
        if (minStartUs > maxStartUs) {
            snackbarManager.showError("Cannot trim: no room before next clip")
            return
        }
        val newStartUs = requestedStartUs.coerceIn(minStartUs, maxStartUs)
        val deltaTimelineUs = newStartUs - clip.timelineStartUs
        val isTextual = clip.kind is RustTextClipKind || clip.kind is RustSubtitleClipKind
        val newSourceStartUs = if (isTextual) {
            0L
        } else {
            val scaled = (deltaTimelineUs.toDouble() * clip.speed).toLong()
            val raw = clip.sourceStartUs + scaled
            raw.coerceIn(0L, clip.sourceTotalDurationUs.coerceAtLeast(0L))
        }

        dispatchCoalescing(
            rustStore.commands.trimClipStart(
                clipId = clipId,
                newStartUs = newStartUs,
                newSourceStartUs = newSourceStartUs,
            ),
            "Trim",
        )
    }

    fun trimClipEndAbsolute(clipId: String, newEndMs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return

        val requestedDurationUs = (newEndMs.msToUs - clip.timelineStartUs).coerceAtLeast(MIN_TRIM_DURATION_US)
        val safeSpeed = if (clip.speed.isFinite() && clip.speed > 0.0) clip.speed else {
            snackbarManager.showError("Cannot trim: invalid clip speed")
            return
        }
        val maxBySourceUs =
            if (clip.kind is RustTextClipKind || clip.kind is RustSubtitleClipKind) {
                Long.MAX_VALUE
            } else {
                (((clip.sourceTotalDurationUs - clip.sourceStartUs).coerceAtLeast(MIN_TRIM_DURATION_US).toDouble() / safeSpeed)
                    .toLong()).coerceAtLeast(MIN_TRIM_DURATION_US)
            }
        val maxByNeighborUs =
            nextClipStartUs(track, clipId)?.let { (it - clip.timelineStartUs).coerceAtLeast(MIN_TRIM_DURATION_US) }
        val maxDurationUs = listOfNotNull(maxByNeighborUs, maxBySourceUs).minOrNull() ?: maxBySourceUs
        val newDurationUs = requestedDurationUs.coerceIn(MIN_TRIM_DURATION_US, maxDurationUs)

        dispatchCoalescing(
            rustStore.commands.trimClipEnd(
                clipId = clipId,
                newDurationUs = newDurationUs,
            ),
            "Trim",
        )
    }

    fun slipClip(clipId: String, backward: Boolean) {
        val clip = findRustClip(clipId) ?: return
        if (clip.kind !is RustVideoClipKind && clip.kind !is RustAudioClipKind) {
            snackbarManager.showError("Slip needs a clip with source media")
            return
        }
        if (hasSpeedRamp(clip)) {
            snackbarManager.showError("Slip is unavailable on speed-ramped clips")
            return
        }
        val stepUs = sourceFrameStepUs(clip)
        val headroomUs = clip.sourceStartUs.coerceAtLeast(0L)
        val tailroomUs = (clip.sourceTotalDurationUs - clip.sourceEndUs).coerceAtLeast(0L)
        val deltaUs = (if (backward) -stepUs else stepUs).coerceIn(-headroomUs, tailroomUs)
        if (deltaUs == 0L) {
            snackbarManager.showError(
                if (backward) "Clip is already at the first frame of its source"
                else "Clip is already at the last frame of its source",
            )
            return
        }

        beginContinuousEdit("Slip")
        var failed = false
        try {
            fun send(command: String): Boolean {
                if (failed) return false
                if (!dispatchCoalescing(command, "Slip")) {
                    failed = true
                    return false
                }
                return true
            }

            send(rustStore.commands.slipClip(clipId, deltaUs))
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    fun slideClip(clipId: String, backward: Boolean) {
        val snapshot = rustStore.snapshot.value ?: return
        val located = findClipWithTrack(snapshot, clipId) ?: return
        val (track, clip) = located
        val ordered = track.clips.sortedBy { it.timelineStartUs }
        val index = ordered.indexOfFirst { it.id == clipId }
        if (index <= 0 || index >= ordered.lastIndex) {
            snackbarManager.showError("Slide needs a clip on both sides")
            return
        }
        val previous = ordered[index - 1]
        val next = ordered[index + 1]
        if (previous.endUs != clip.timelineStartUs || next.timelineStartUs != clip.endUs) {
            snackbarManager.showError("Slide needs clips touching on both sides")
            return
        }
        if (listOf(previous, clip, next).any { hasSpeedRamp(it) }) {
            snackbarManager.showError("Slide is unavailable on speed-ramped clips")
            return
        }
        val stepUs = timelineFrameStepUs()
        val shrinking = if (backward) previous else next
        if (shrinking.timelineDurationUs <= stepUs) {
            snackbarManager.showError("Slide needs a neighbouring clip longer than one frame")
            return
        }

        beginContinuousEdit("Slide")
        var failed = false
        try {
            fun send(command: String): Boolean {
                if (failed) return false
                if (!dispatchCoalescing(command, "Slide")) {
                    failed = true
                    return false
                }
                return true
            }

            if (backward) {
                if (!send(rustStore.commands.trimClipEnd(previous.id, previous.timelineDurationUs - stepUs))) return
                if (!send(rustStore.commands.moveClip(clip.id, track.id, clip.timelineStartUs - stepUs))) return
                val newNextStartUs = next.timelineStartUs - stepUs
                if (!send(
                        rustStore.commands.trimClipStart(
                            next.id,
                            newNextStartUs,
                            trimSourceStartFor(next, newNextStartUs),
                        ),
                    )
                ) return
            } else {
                val newNextStartUs = next.timelineStartUs + stepUs
                if (!send(
                        rustStore.commands.trimClipStart(
                            next.id,
                            newNextStartUs,
                            trimSourceStartFor(next, newNextStartUs),
                        ),
                    )
                ) return
                if (!send(rustStore.commands.moveClip(clip.id, track.id, clip.timelineStartUs + stepUs))) return
                if (!send(rustStore.commands.trimClipEnd(previous.id, previous.timelineDurationUs + stepUs))) return
            }

            val after = rustStore.snapshot.value?.timeline?.tracks
                ?.firstOrNull { it.id == track.id }
                ?.clips
            val movedAfter = after?.firstOrNull { it.id == clipId }
            val leftAfter = after?.firstOrNull { it.id == previous.id }
            val rightAfter = after?.firstOrNull { it.id == next.id }
            val slid = movedAfter != null && leftAfter != null && rightAfter != null &&
                leftAfter.endUs == movedAfter.timelineStartUs &&
                movedAfter.endUs == rightAfter.timelineStartUs &&
                movedAfter.timelineDurationUs == clip.timelineDurationUs
            if (!slid) {
                failed = true
                snackbarManager.showError("Slide was blocked by the neighbouring clips")
            }
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    fun rollClip(clipId: String, backward: Boolean) {
        val snapshot = rustStore.snapshot.value ?: return
        val located = findClipWithTrack(snapshot, clipId) ?: return
        val (track, clip) = located
        val ordered = track.clips.sortedBy { it.timelineStartUs }
        val index = ordered.indexOfFirst { it.id == clipId }
        if (index < 0 || index >= ordered.lastIndex) {
            snackbarManager.showError("Roll needs a following clip")
            return
        }
        val next = ordered[index + 1]
        if (next.timelineStartUs != clip.endUs) {
            snackbarManager.showError("Roll needs two touching clips")
            return
        }
        if (hasSpeedRamp(clip) || hasSpeedRamp(next)) {
            snackbarManager.showError("Roll is unavailable on speed-ramped clips")
            return
        }
        val stepUs = timelineFrameStepUs()
        val newBoundaryUs = if (backward) clip.endUs - stepUs else clip.endUs + stepUs
        if (newBoundaryUs <= clip.timelineStartUs || newBoundaryUs >= next.endUs) {
            snackbarManager.showError("Roll would leave a clip empty")
            return
        }

        beginContinuousEdit("Roll")
        var failed = false
        try {
            fun send(command: String): Boolean {
                if (failed) return false
                if (!dispatchCoalescing(command, "Roll")) {
                    failed = true
                    return false
                }
                return true
            }

            if (backward) {
                if (!send(rustStore.commands.trimClipEnd(clip.id, newBoundaryUs - clip.timelineStartUs))) return
                if (!send(
                        rustStore.commands.trimClipStart(
                            next.id,
                            newBoundaryUs,
                            trimSourceStartFor(next, newBoundaryUs),
                        ),
                    )
                ) return
            } else {
                if (!send(
                        rustStore.commands.trimClipStart(
                            next.id,
                            newBoundaryUs,
                            trimSourceStartFor(next, newBoundaryUs),
                        ),
                    )
                ) return
                if (!send(rustStore.commands.trimClipEnd(clip.id, newBoundaryUs - clip.timelineStartUs))) return
            }

            val after = rustStore.snapshot.value?.timeline?.tracks
                ?.firstOrNull { it.id == track.id }
                ?.clips
            val leftAfter = after?.firstOrNull { it.id == clipId }
            val rightAfter = after?.firstOrNull { it.id == next.id }
            val rolled = leftAfter != null && rightAfter != null &&
                leftAfter.timelineStartUs == clip.timelineStartUs &&
                leftAfter.endUs == rightAfter.timelineStartUs &&
                abs(rightAfter.endUs - next.endUs) <= 1L
            if (!rolled) {
                failed = true
                snackbarManager.showError("Roll was blocked by the source bounds")
            }
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    private fun hasSpeedRamp(clip: RustClipSnapshot): Boolean =
        clip.keyframes.keyframes.any { it.param == KeyframeParams.SPEED }

    private fun exportFrameRate(): Double {
        val fps = rustStore.snapshot.value?.exportProfile?.fps ?: 0.0
        return if (fps.isFinite() && fps > 0.0) fps else 30.0
    }

    private fun timelineFrameStepUs(): Long =
        (1_000_000.0 / exportFrameRate()).roundToLong().coerceAtLeast(1L)

    private fun sourceFrameStepUs(clip: RustClipSnapshot): Long {
        val fps = (clip.kind as? RustVideoClipKind)?.fps ?: 0.0
        val effective = if (fps.isFinite() && fps > 0.0) fps else exportFrameRate()
        return (1_000_000.0 / effective).roundToLong().coerceAtLeast(1L)
    }

    private fun trimSourceStartFor(clip: RustClipSnapshot, newStartUs: Long): Long {
        val deltaTimelineUs = newStartUs - clip.timelineStartUs
        val scaled = (deltaTimelineUs.toDouble() * clip.speed).toLong()
        return (clip.sourceStartUs + scaled).coerceIn(0L, clip.sourceTotalDurationUs.coerceAtLeast(0L))
    }

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = DEFAULT_TEXT_CLIP_DURATION_US / 1000L) {
        val requestedUs = startMs.msToUs
        val durationUs = durationMs.msToUs
        val resolvedUs = rustStore.snapshot.value?.timeline?.tracks
            ?.firstOrNull { it.id == trackId }
            ?.let { track ->
                findNearestNonOverlappingStartUs(
                    track = track,
                    clipId = null,
                    requestedStartUs = requestedUs,
                    durationUs = durationUs,
                )
            } ?: requestedUs
        dispatchAndSync(
            rustStore.commands.addClip(
                trackId,
                RustClipSnapshot(
                    id = randomUuid(),
                    timelineStartUs = resolvedUs,
                    timelineDurationUs = durationMs.msToUs,
                    sourceStartUs = 0L,
                    sourceEndUs = durationMs.msToUs,
                    sourceTotalDurationUs = durationMs.msToUs,
                    speed = 1.0,
                    volume = 1.0f,
                    opacity = 1.0f,
                    muted = false,
                    transitionIn = null,
                    transitionOut = null,
                    kind = RustTextClipKind(
                        text = text,
                        style = RustTextStyleSnapshot(),
                    ),
                ),
            ),
        )
    }

    fun removeClip(clipId: String) {
        dispatchAndSync(rustStore.commands.removeClip(clipId))
    }

    fun toggleClipMute(clipId: String) {
        val clip = findRustClip(clipId) ?: return
        dispatchAndSync(rustStore.commands.setClipMuted(clipId, !clip.muted))
    }

    fun rippleDeleteClip(clipId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: run {
            removeClip(clipId)
            return
        }
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return
        selectClip(null)
        val moves = org.mlm.miniter.project.TimelineBasics.rippleMovesForRemoval(track, clip)
        if (moves.isEmpty()) {
            dispatchAndSync(rustStore.commands.removeClip(clipId))
            return
        }
        val cmds = buildList {
            add(rustStore.commands.removeClip(clipId))
            moves.forEach { (id, newStart) ->
                add(rustStore.commands.moveClip(id, track.id, newStart))
            }
        }
        try {
            rustStore.dispatch(rustStore.commands.batch("RippleDelete", cmds))
            syncFromRust(selectedClipId = null, isDirty = true)
        } catch (e: Exception) {
            handleError(e.message ?: "Ripple delete failed")
        }
    }

    fun closeGapOnTrack(trackId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { it.id == trackId } ?: return
        val moves = org.mlm.miniter.project.TimelineBasics.compactMoves(track)
        if (moves.isEmpty()) {
            snackbarManager.show("No gaps to close")
            return
        }
        val cmds = moves.map { (id, newStart) ->
            rustStore.commands.moveClip(id, track.id, newStart)
        }
        try {
            rustStore.dispatch(rustStore.commands.batch("CloseGap", cmds))
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError(e.message ?: "Close gap failed")
        }
    }

    fun splitAllAtPlayhead() {
        val snapshot = rustStore.snapshot.value ?: return
        val atUs = _state.value.playheadMs.msToUs
        val targets = org.mlm.miniter.project.TimelineBasics.splitTargetsAt(
            snapshot.timeline.tracks, atUs,
        )
        if (targets.isEmpty()) {
            snackbarManager.show("Nothing to split at playhead")
            return
        }
        val cmds = targets.map { target ->
            rustStore.commands.splitClip(target.clipId, target.atUs, randomUuid())
        }
        try {
            rustStore.dispatch(rustStore.commands.batch("SplitAll", cmds))
            syncFromRust(isDirty = true)
        } catch (e: Exception) {
            handleError(e.message ?: "Split all failed")
        }
    }

    fun duplicateSelectedClips() {
        duplicateClips(_state.value.selectedClipIds)
    }

    fun duplicateClip(clipId: String) {
        duplicateClips(clipIdsForAction(clipId))
    }

    private fun duplicateClips(clipIds: Set<String>) {
        if (clipIds.isEmpty()) return
        val snapshot = rustStore.snapshot.value ?: return
        val sources = snapshot.timeline.tracks.mapNotNull { track ->
            val clips = track.clips.filter { it.id in clipIds }.sortedBy { it.timelineStartUs }
            if (clips.isEmpty()) null else track to clips
        }
        if (sources.isEmpty()) return
        val lockedTrack = sources.firstOrNull { (track, _) -> track.locked }?.first
        if (lockedTrack != null) {
            snackbarManager.showError("Cannot duplicate: '${lockedTrack.name}' is locked")
            return
        }
        val plan = mutableListOf<PlannedClip>()
        for ((track, clips) in sources) {
            val occupied = track.clips.toMutableList()
            for (clip in clips) {
                val startUs = firstFreeStartUs(occupied, clip.endUs, clip.timelineDurationUs)
                val copy = clip.copy(id = randomUuid(), timelineStartUs = startUs)
                plan += PlannedClip(track, copy)
                occupied += copy
            }
        }
        dispatchPlannedClips("Duplicate", sanitizePlannedTransitions(snapshot, plan))
    }

    fun copySelectedClips() {
        copyClips(null)
    }

    fun copyClip(clipId: String) {
        copyClips(clipId)
    }

    private fun copyClips(explicitClipId: String?) {
        setClipboard(clipIdsForAction(explicitClipId))
    }

    fun cutSelectedClips() {
        cutClips(null)
    }

    fun cutClip(clipId: String) {
        cutClips(clipId)
    }

    private fun cutClips(explicitClipId: String?) {
        val ids = clipIdsForAction(explicitClipId)
        if (ids.isEmpty()) return
        val snapshot = rustStore.snapshot.value ?: return
        val lockedTrack = snapshot.timeline.tracks.firstOrNull { track ->
            track.locked && track.clips.any { it.id in ids }
        }
        if (lockedTrack != null) {
            snackbarManager.showError("Cannot cut: '${lockedTrack.name}' is locked")
            return
        }
        if (!setClipboard(ids)) return
        deleteClipIds(orderedClipIds(snapshot, ids))
    }

    fun pasteClips() {
        val entries = clipboard
        if (entries.isEmpty()) return
        val snapshot = rustStore.snapshot.value ?: return
        val playheadUs = _state.value.playheadMs.msToUs
        val singleSelectedTrackId = singleSelectedTrack(snapshot)?.id
        val overrideTrackId = singleSelectedTrackId?.takeIf {
            entries.map { entry -> entry.trackId }.distinct().size == 1
        }
        val plan = mutableListOf<PlannedClip>()
        for (entry in entries) {
            val track = snapshot.timeline.tracks.firstOrNull {
                it.id == (overrideTrackId ?: entry.trackId)
            } ?: run {
                clearClipboard()
                return
            }
            if (track.locked) {
                snackbarManager.showError("Cannot paste: '${track.name}' is locked")
                return
            }
            if (!trackAcceptsClip(track, entry.clip)) {
                snackbarManager.showError("Cannot paste: '${track.name}' does not accept this clip")
                return
            }
            val startUs = playheadUs + entry.offsetUs
            if (startUs < 0L) {
                snackbarManager.showError("Cannot paste before the timeline start")
                return
            }
            val endUs = startUs + entry.clip.timelineDurationUs
            if (track.clips.any { it.overlapsRange(startUs, endUs) }) {
                snackbarManager.showError("Cannot paste: clips would overlap on '${track.name}'")
                return
            }
            if (plan.any { it.track.id == track.id && it.clip.overlapsRange(startUs, endUs) }) {
                snackbarManager.showError("Cannot paste: the clipboard clips would overlap")
                return
            }
            plan += PlannedClip(track, entry.clip.copy(id = randomUuid(), timelineStartUs = startUs))
        }
        dispatchPlannedClips("Paste", sanitizePlannedTransitions(snapshot, plan))
    }

    private fun clipIdsForAction(explicitClipId: String?): Set<String> {
        val selected = _state.value.selectedClipIds
        return when {
            explicitClipId == null -> selected
            explicitClipId in selected -> selected
            else -> setOf(explicitClipId)
        }
    }

    private fun setClipboard(clipIds: Set<String>): Boolean {
        if (clipIds.isEmpty()) return false
        val snapshot = rustStore.snapshot.value ?: return false
        val entries = snapshot.timeline.tracks.flatMap { track ->
            track.clips.filter { it.id in clipIds }.map { track.id to it }
        }
        if (entries.isEmpty()) return false
        val originUs = entries.minOf { (_, clip) -> clip.timelineStartUs }
        clipboard = entries.map { (trackId, clip) ->
            ClipboardEntry(trackId, clip, clip.timelineStartUs - originUs)
        }
        _hasClipboard.value = true
        return true
    }

    private fun clearClipboard() {
        clipboard = emptyList()
        _hasClipboard.value = false
    }

    private fun dispatchPlannedClips(label: String, plan: List<PlannedClip>) {
        if (plan.isEmpty()) return
        beginContinuousEdit(label)
        var failed = false
        try {
            fun send(command: String): Boolean {
                if (failed) return false
                if (!dispatchCoalescing(command, label)) {
                    failed = true
                    return false
                }
                return true
            }
            for (planned in plan) {
                if (!send(rustStore.commands.addClip(planned.track.id, planned.clip))) break
            }
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    private fun sanitizePlannedTransitions(
        snapshot: RustProjectSnapshot,
        planned: List<PlannedClip>,
    ): List<PlannedClip> {
        if (planned.isEmpty()) return planned
        val plannedByTrack = planned.groupBy { it.track.id }
        val clipsByTrack = snapshot.timeline.tracks.associate { track ->
            track.id to (track.clips + plannedByTrack[track.id].orEmpty().map { it.clip })
        }
        return planned.map { plannedClip ->
            val clip = plannedClip.clip
            val startUs = clip.timelineStartUs
            val endUs = startUs + clip.timelineDurationUs
            val others = clipsByTrack[plannedClip.track.id].orEmpty().filter { it.id != clip.id }
            val hasPrevious = others.any { other ->
                abs(other.timelineStartUs + other.timelineDurationUs - startUs) <=
                    TRANSITION_ADJACENCY_EPSILON_US
            }
            val hasNext = others.any { other ->
                abs(other.timelineStartUs - endUs) <= TRANSITION_ADJACENCY_EPSILON_US
            }
            plannedClip.copy(
                clip = clip.copy(
                    transitionIn = clip.transitionIn.takeIf { hasPrevious },
                    transitionOut = clip.transitionOut.takeIf { hasNext },
                ),
            )
        }
    }

    private fun singleSelectedTrack(snapshot: RustProjectSnapshot): RustTrackSnapshot? {
        val selected = _state.value.selectedClipIds
        if (selected.size != 1) return null
        return snapshot.timeline.tracks.firstOrNull { track -> track.clips.any { it.id in selected } }
    }

    private fun orderedClipIds(snapshot: RustProjectSnapshot, clipIds: Set<String>): List<String> =
        snapshot.timeline.tracks.flatMap { track ->
            track.clips.map { it.id }.filter { it in clipIds }
        }

    private fun firstFreeStartUs(
        clips: List<RustClipSnapshot>,
        requestedStartUs: Long,
        durationUs: Long,
    ): Long {
        val duration = durationUs.coerceAtLeast(1L)
        var candidate = requestedStartUs.coerceAtLeast(0L)
        for (clip in clips.sortedBy { it.timelineStartUs }) {
            val clipEndUs = clip.timelineStartUs + clip.timelineDurationUs
            if (clipEndUs <= candidate) continue
            if (clip.timelineStartUs >= candidate + duration) break
            candidate = clipEndUs
        }
        return candidate
    }

    private fun trackAcceptsClip(track: RustTrackSnapshot, clip: RustClipSnapshot): Boolean =
        when (clip.kind) {
            is RustVideoClipKind, is RustAdjustmentClipKind -> track.kind == RustTrackKind.Video
            is RustAudioClipKind -> track.kind == RustTrackKind.Audio
            is RustTextClipKind -> track.kind == RustTrackKind.Text
            is RustSubtitleClipKind -> track.kind == RustTrackKind.Subtitle
        }

    fun splitClipAtPlayhead(clipId: String) {
        try {
            rustStore.dispatch(
                rustStore.commands.splitClip(
                    clipId = clipId,
                    atUs = _state.value.playheadMs.msToUs,
                    newClipId = randomUuid(),
                ),
            )
            syncFromRust()
        } catch (e: Exception) {
            handleError(e.message ?: "Failed to split clip")
        }
    }

    fun joinClipWithNext(clipId: String) {
        val snapshot = rustStore.snapshot.value
        val track = snapshot?.timeline?.tracks?.firstOrNull { t -> t.clips.any { it.id == clipId } }
        val clip = track?.clips?.find { it.id == clipId }
        val next = clip?.let {
            val index = track.clips.indexOfFirst { c -> c.id == clipId }
            track.clips.getOrNull(index + 1)
        }
        if (clip == null || next == null) {
            snackbarManager.showError("No clip after this one to join")
            return
        }
        try {
            rustStore.dispatch(rustStore.commands.joinClips(clip.id, next.id))
            syncFromRust()
        } catch (e: Exception) {
            handleError(e.message ?: "Clips are not pieces of one cut")
        }
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        if (!speed.isFinite() || speed < 0.1f || speed > 8.0f) {
            snackbarManager.showError("Speed must be between 0.1x and 8.0x")
            return
        }
        dispatchCoalescing(
            rustStore.commands.setClipSpeed(clipId, speed.toDouble()),
            "Speed",
        )
    }

    fun freezeFrame(clipId: String, durationSeconds: Float) {
        if (!durationSeconds.isFinite() || durationSeconds < 1f || durationSeconds > 5f) return
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return
        val video = clip.kind as? RustVideoClipKind ?: return
        val fps = video.fps
        val speed = clip.speed
        val playheadUs = _state.value.playheadMs.msToUs
        val clipEndUs = clip.timelineStartUs + clip.timelineDurationUs
        if (!fps.isFinite() || fps <= 0.0) {
            snackbarManager.showError("Freeze needs a clip with a valid frame rate")
            return
        }
        if (!speed.isFinite() || speed <= 0.0) {
            snackbarManager.showError("Freeze needs a clip with a valid speed")
            return
        }
        if (clip.keyframes.keyframes.any { it.param == KeyframeParams.SPEED }) {
            snackbarManager.showError("Freeze is unavailable on speed-ramped clips")
            return
        }
        if (playheadUs < clip.timelineStartUs || playheadUs >= clipEndUs) {
            snackbarManager.showError("Place the playhead inside the clip to freeze a frame")
            return
        }
        val holdUs = (durationSeconds.toDouble() * 1_000_000.0).roundToLong()
        val frameUs = (1_000_000.0 / fps).roundToLong().coerceAtLeast(1L)
        val frameTimelineUs = floor(frameUs.toDouble() / speed).roundToLong().coerceAtLeast(1L)
        if (holdUs <= frameTimelineUs) {
            snackbarManager.showError("The hold must be longer than one frame")
            return
        }

        beginContinuousEdit("Freeze")
        var failed = false
        try {
            fun send(command: String): Boolean {
                if (failed) return false
                if (!dispatchCoalescing(command, "Freeze")) {
                    failed = true
                    return false
                }
                return true
            }

            var freezeId = clipId
            if (playheadUs > clip.timelineStartUs) {
                val rightId = randomUuid()
                if (!send(rustStore.commands.splitClip(clipId, playheadUs, rightId))) return
                freezeId = rightId
            }
            val afterFirstSplit = findRustClip(freezeId)
            if (afterFirstSplit == null) {
                failed = true
                return
            }
            val pieceEndUs = afterFirstSplit.timelineStartUs + afterFirstSplit.timelineDurationUs
            val frameEndUs = afterFirstSplit.timelineStartUs + frameTimelineUs
            if (frameEndUs < pieceEndUs) {
                if (!send(rustStore.commands.splitClip(freezeId, frameEndUs, randomUuid()))) return
            }
            val freeze = findRustClip(freezeId)
            if (freeze == null) {
                failed = true
                return
            }
            val shiftUs = holdUs - freeze.timelineDurationUs
            if (shiftUs <= 0L) {
                failed = true
                return
            }
            val latestTracks = rustStore.snapshot.value?.timeline?.tracks
            if (latestTracks == null) {
                failed = true
                return
            }
            val freezePointUs = freeze.timelineStartUs
            val straddling = latestTracks.any { other ->
                other.id != track.id && other.clips.any { later ->
                    later.timelineStartUs < freezePointUs &&
                        later.timelineStartUs + later.timelineDurationUs > freezePointUs
                }
            }
            if (straddling) {
                failed = true
                snackbarManager.showError("Another track has a clip across the playhead")
                return
            }
            for (rippleTrack in latestTracks) {
                val following = rippleTrack.clips
                    .filter { it.id != freezeId && it.timelineStartUs >= freezePointUs }
                    .sortedByDescending { it.timelineStartUs }
                for (later in following) {
                    val newStartUs = later.timelineStartUs + shiftUs
                    if (!send(rustStore.commands.moveClip(later.id, rippleTrack.id, newStartUs))) return
                }
            }
            val beforeTrim = findRustClip(freezeId)
            if (beforeTrim == null) {
                failed = true
                return
            }
            val trimDurationUs = minOf(beforeTrim.timelineDurationUs, frameTimelineUs)
            if (!send(rustStore.commands.trimClipEnd(freezeId, trimDurationUs))) return
            val trimmed = findRustClip(freezeId)
            if (trimmed == null) {
                failed = true
                return
            }
            val heldSourceUs = (trimmed.sourceEndUs - trimmed.sourceStartUs).coerceAtLeast(1L)
            val freezeSpeed = heldSourceUs.toDouble() / holdUs.toDouble()
            if (!send(rustStore.commands.setClipSpeed(freezeId, freezeSpeed))) return
            val result = findRustClip(freezeId)
            if (result == null || result.timelineDurationUs != holdUs) {
                failed = true
                snackbarManager.showError("Could not apply the freeze duration")
            }
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    fun addMarkerAtPlayhead() {
        addMarker(_state.value.playheadMs)
    }

    fun addMarker(timeMs: Long): String? {
        val marker = RustMarkerSnapshot(
            id = randomUuid(),
            time = timeMs.coerceAtLeast(0L).msToUs,
        )
        return if (applyMarkerEdit("Add marker", rustStore.commands.addMarker(marker))) {
            marker.id
        } else {
            null
        }
    }

    fun removeMarker(markerId: String) {
        applyMarkerEdit("Remove marker", rustStore.commands.removeMarker(markerId))
    }

    fun moveMarker(markerId: String, timeMs: Long) {
        applyMarkerEdit(
            "Move marker",
            rustStore.commands.moveMarker(markerId, timeMs.coerceAtLeast(0L).msToUs),
        )
    }

    fun relabelMarker(markerId: String, label: String) {
        applyMarkerEdit(
            "Label marker",
            rustStore.commands.relabelMarker(markerId, label.trim().ifEmpty { null }),
        )
    }

    fun setMarkerColor(markerId: String, color: String?) {
        applyMarkerEdit("Marker color", rustStore.commands.setMarkerColor(markerId, color))
    }

    private fun applyMarkerEdit(label: String, commandJson: String): Boolean {
        beginContinuousEdit(label)
        var failed = false
        try {
            if (!dispatchCoalescing(commandJson, label)) failed = true
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
        return !failed
    }

    fun setExportRangeIn(timeMs: Long = _state.value.playheadMs) {
        val contentEndUs = rustStore.durationUs()
        val inUs = timeMs.coerceAtLeast(0L).msToUs.coerceAtMost(contentEndUs)
        val outUs = rustStore.snapshot.value?.exportRange?.endUs
        if (outUs != null && inUs >= outUs) {
            snackbarManager.showError("The in point must be before the out point")
            return
        }
        if (outUs == null && inUs >= contentEndUs) {
            snackbarManager.showError("Place the in point before the end of the timeline")
            return
        }
        applyExportRange("Set in point", RustExportRangeSnapshot(startUs = inUs, endUs = outUs))
    }

    fun setExportRangeOut(timeMs: Long = _state.value.playheadMs) {
        val contentEndUs = rustStore.durationUs()
        val outUs = timeMs.coerceAtLeast(0L).msToUs.coerceAtMost(contentEndUs)
        val inUs = rustStore.snapshot.value?.exportRange?.startUs
        if (inUs != null && outUs <= inUs) {
            snackbarManager.showError("The out point must be after the in point")
            return
        }
        if (inUs == null && outUs <= 0L) {
            snackbarManager.showError("Place the out point after the start of the timeline")
            return
        }
        applyExportRange("Set out point", RustExportRangeSnapshot(startUs = inUs, endUs = outUs))
    }

    fun clearExportRange() {
        applyExportRange("Clear in/out", null)
    }

    private fun applyExportRange(label: String, range: RustExportRangeSnapshot?): Boolean {
        val normalized = range?.takeUnless { it.startUs == null && it.endUs == null }
        if (rustStore.snapshot.value?.exportRange == normalized) return false
        beginContinuousEdit(label)
        var failed = false
        try {
            if (!dispatchCoalescing(rustStore.commands.setExportRange(normalized), label)) failed = true
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
        return !failed
    }

    fun removeSilence(clipId: String) {
        if (_state.value.removeSilenceRunning ||
            _state.value.autoReframeRunning ||
            _state.value.stabilizeRunning ||
            _state.value.sceneDetectRunning ||
            _state.value.maskTrackRunning
        ) return
        val clip = findRustClip(clipId) ?: return
        val video = clip.kind as? RustVideoClipKind
        if (video == null) {
            snackbarManager.showError("Remove silence needs a video clip")
            return
        }
        if (video.sourcePath.isBlank()) {
            snackbarManager.showError("Remove silence needs a clip with a source file")
            return
        }
        if (clip.keyframes.keyframes.any { it.param == KeyframeParams.SPEED }) {
            snackbarManager.showError("Remove silence is unavailable on speed-ramped clips")
            return
        }
        if (video.filters.any { it.enabled && it.filter is RustReverseVideoFilterSnapshot }) {
            snackbarManager.showError("Remove silence is unavailable on reversed clips")
            return
        }
        if (!clip.speed.isFinite() || clip.speed <= 0.0) {
            snackbarManager.showError("Remove silence needs a clip with a valid speed")
            return
        }
        if (clip.timelineDurationUs <= 0L || clip.sourceEndUs <= clip.sourceStartUs) {
            snackbarManager.showError("Remove silence needs a clip with a valid source range")
            return
        }

        _state.update { it.copy(removeSilenceRunning = true) }
        viewModelScope.launch {
            try {
                val beatTrack = withContext(Dispatchers.Default) {
                    RustCoreSession.detectBeats(video.sourcePath)
                }
                val sourceRuns = detectSilentSourceRuns(
                    windowEnergy = beatTrack.windowEnergy,
                    windowMs = beatTrack.windowMs,
                    sourceStartUs = clip.sourceStartUs,
                    sourceEndUs = clip.sourceEndUs,
                )
                if (sourceRuns.isEmpty()) {
                    snackbarManager.showError("Remove silence found no silence long enough to remove")
                    return@launch
                }
                val current = findRustClip(clipId)
                if (current == null ||
                    current.timelineStartUs != clip.timelineStartUs ||
                    current.timelineDurationUs != clip.timelineDurationUs ||
                    current.sourceStartUs != clip.sourceStartUs ||
                    current.sourceEndUs != clip.sourceEndUs
                ) {
                    snackbarManager.showError("The clip changed during analysis; run Remove silence again")
                    return@launch
                }
                val localRuns = silentRunsToLocal(current, sourceRuns)
                if (localRuns.isEmpty()) {
                    snackbarManager.showError("Remove silence found no silence long enough to remove")
                    return@launch
                }
                val track = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { t ->
                    t.clips.any { it.id == clipId }
                }
                if (track == null) {
                    snackbarManager.showError("The clip changed during analysis; run Remove silence again")
                    return@launch
                }
                val clipStartUs = current.timelineStartUs
                var failed = false
                fun send(command: String): Boolean {
                    if (failed) return false
                    if (!dispatchCoalescing(command, "Remove silence")) {
                        failed = true
                        return false
                    }
                    return true
                }
                fun applyRun(run: SilentRun): Boolean {
                    val spanStartUs = clipStartUs + run.startUs
                    val spanEndUs = clipStartUs + run.endUs
                    val shiftUs = spanEndUs - spanStartUs
                    if (!send(rustStore.commands.splitClip(clipId, spanEndUs, randomUuid()))) return false
                    val silentId = randomUuid()
                    if (!send(rustStore.commands.splitClip(clipId, spanStartUs, silentId))) return false
                    if (!send(rustStore.commands.removeClip(silentId))) return false
                    val latestTracks = rustStore.snapshot.value?.timeline?.tracks
                    if (latestTracks == null) {
                        failed = true
                        return false
                    }
                    val straddling = latestTracks.any { other ->
                        other.id != track.id && other.clips.any { c ->
                            c.timelineStartUs < spanEndUs &&
                                c.timelineStartUs + c.timelineDurationUs > spanStartUs
                        }
                    }
                    if (straddling) {
                        failed = true
                        snackbarManager.showError("Another track has a clip across the silence")
                        return false
                    }
                    for (rippleTrack in latestTracks) {
                        val following = rippleTrack.clips
                            .filter { it.timelineStartUs >= spanStartUs }
                            .sortedBy { it.timelineStartUs }
                        for (later in following) {
                            if (!send(rustStore.commands.moveClip(later.id, rippleTrack.id, later.timelineStartUs - shiftUs))) {
                                return false
                            }
                        }
                    }
                    return true
                }
                beginContinuousEdit("Remove silence")
                try {
                    for (run in localRuns.sortedByDescending { it.startUs }) {
                        if (!applyRun(run)) break
                    }
                    if (!failed) {
                        val gaps = localRuns.size
                        snackbarManager.show("Removed $gaps silence ${if (gaps == 1) "gap" else "gaps"}")
                    }
                } finally {
                    if (failed) cancelContinuousEdit() else commitContinuousEdit()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.showError("Remove silence failed: ${e.message}")
            } finally {
                _state.update { it.copy(removeSilenceRunning = false) }
            }
        }
    }

    fun detectScenes(clipId: String) {
        val uiState = _state.value
        if (uiState.sceneDetectRunning ||
            uiState.autoReframeRunning ||
            uiState.stabilizeRunning ||
            uiState.maskTrackRunning ||
            uiState.removeSilenceRunning
        ) return
        val clip = findRustClip(clipId) ?: return
        val video = clip.kind as? RustVideoClipKind
        if (video == null) {
            snackbarManager.showError("Detect scenes needs a video clip")
            return
        }
        if (video.sourcePath.isBlank()) {
            snackbarManager.showError("Detect scenes needs a clip with a source file")
            return
        }
        if (clip.keyframes.keyframes.any { it.param == KeyframeParams.SPEED }) {
            snackbarManager.showError("Detect scenes is unavailable on speed-ramped clips")
            return
        }
        if (!clip.speed.isFinite() || clip.speed <= 0.0) {
            snackbarManager.showError("Detect scenes needs a clip with a valid speed")
            return
        }
        if (clip.timelineDurationUs <= 0L || clip.sourceEndUs <= clip.sourceStartUs) {
            snackbarManager.showError("Detect scenes needs a clip with a valid source range")
            return
        }

        _state.update { it.copy(sceneDetectRunning = true) }
        viewModelScope.launch {
            try {
                val samples = gatherSceneSamples(clip, video)
                if (samples.size < SCENE_MIN_FRAMES) {
                    snackbarManager.showError("Detect scenes found too few frames to analyse")
                    return@launch
                }
                val boundaries = detectSceneBoundaries(samples)
                if (boundaries.isEmpty()) {
                    snackbarManager.showError("Detect scenes found no shot boundaries")
                    return@launch
                }
                val current = findRustClip(clipId)
                if (current == null ||
                    current.timelineStartUs != clip.timelineStartUs ||
                    current.timelineDurationUs != clip.timelineDurationUs ||
                    current.sourceStartUs != clip.sourceStartUs ||
                    current.sourceEndUs != clip.sourceEndUs
                ) {
                    snackbarManager.showError("The clip changed during analysis; run Detect scenes again")
                    return@launch
                }
                val clipStartUs = current.timelineStartUs
                val clipEndUs = clipStartUs + current.timelineDurationUs
                val cuts = boundaries
                    .map { clipStartUs + it.localOffsetUs }
                    .filter { it > clipStartUs && it < clipEndUs }
                    .distinct()
                    .sortedDescending()
                if (cuts.isEmpty()) {
                    snackbarManager.showError("Detect scenes found no shot boundaries")
                    return@launch
                }
                var failed = false
                fun send(command: String): Boolean {
                    if (failed) return false
                    if (!dispatchCoalescing(command, "Detect scenes")) {
                        failed = true
                        return false
                    }
                    return true
                }
                beginContinuousEdit("Detect scenes")
                try {
                    for (cutUs in cuts) {
                        if (!send(rustStore.commands.splitClip(clipId, cutUs, randomUuid()))) break
                    }
                    if (!failed) {
                        val count = cuts.size
                        snackbarManager.show("Detected $count scene ${if (count == 1) "cut" else "cuts"}")
                    }
                } finally {
                    if (failed) cancelContinuousEdit() else commitContinuousEdit()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.showError("Detect scenes failed: ${e.message}")
            } finally {
                _state.update { it.copy(sceneDetectRunning = false) }
            }
        }
    }

    private suspend fun gatherSceneSamples(
        clip: RustClipSnapshot,
        video: RustVideoClipKind,
    ): List<SceneSample> {
        val startMs = clip.sourceStartUs / 1000L
        val timestamps = sceneSampleTimestamps(clip.sourceStartUs, clip.sourceEndUs)
        if (timestamps.isEmpty()) return emptyList()

        val samples = mutableListOf<SceneSample>()
        val grabber = PlatformFrameGrabber()
        try {
            grabber.open(video.sourcePath)
            for (sampleMs in timestamps) {
                val frame = grabber.grabFrame(
                    timestampMs = sampleMs,
                    width = 160,
                    height = 0,
                ) ?: continue
                val signature = withContext(Dispatchers.Default) { luminanceHistogram(frame) }
                val sourceOffsetUs = (sampleMs - startMs) * 1000L
                samples += SceneSample(
                    localOffsetUs = autoReframeLocalOffset(clip, sourceOffsetUs),
                    signature = signature,
                )
            }
        } finally {
            grabber.release()
        }
        return samples
    }

    fun setClipVolume(clipId: String, volume: Float) {
        dispatchCoalescing(
            rustStore.commands.setClipVolume(clipId, volume),
            "Volume",
        )
    }

    fun setClipOpacity(clipId: String, opacity: Float) {
        dispatchCoalescing(
            rustStore.commands.setClipOpacity(clipId, opacity),
            "Opacity",
        )
    }

    fun setClipBlendMode(clipId: String, blendMode: RustBlendMode) {
        dispatchAndSync(rustStore.commands.setClipBlendMode(clipId, blendMode.name))
    }

    fun addAudioFilter(clipId: String, filter: RustAudioFilterSnapshot) {
        dispatchAndSync(rustStore.commands.addAudioFilter(clipId = clipId, filter = filter))
    }

    fun removeAudioFilter(clipId: String, filterIndex: Int) {
        dispatchAndSync(rustStore.commands.removeAudioFilter(clipId, filterIndex))
    }

    fun updateAudioFilterDuration(clipId: String, filterIndex: Int, durationUs: Long) {
        dispatchCoalescing(
            rustStore.commands.updateAudioFilterDuration(clipId, filterIndex, durationUs),
            "AudioFilter",
        )
    }

    fun replaceAudioFilter(clipId: String, filterIndex: Int, filter: RustAudioFilterSnapshot) {
        dispatchAndSync(rustStore.commands.updateAudioFilter(clipId, filterIndex, filter))
    }

    fun updateTextClip(clipId: String, newText: String) {
        dispatchCoalescing(
            rustStore.commands.updateTextContent(clipId, newText),
            "Text",
        )
    }

    fun updateTextClipStyle(clipId: String, update: RustTextStyleUpdate) {
        val clip = findRustClip(clipId)?.kind as? RustTextClipKind ?: return
        val fontFamily = update.fontFamily
        if (fontFamily != null && (fontFamily.contains("/") || fontFamily.contains("\\"))) {
            viewModelScope.launch {
                val staged = FontManager.importFont(fontFamily)
                if (staged == null) {
                    snackbarManager.showError("Not a usable font file (need .ttf/.otf)")
                    return@launch
                }
                applyTextStyle(clipId, clip.style.copy(fontFamily = staged))
            }
            return
        }
        val style = clip.style.copy(
            fontSize = update.fontSize ?: clip.style.fontSize,
            color = update.color?.toArgbHex() ?: clip.style.color,
            backgroundColor = if (update.clearBackground) {
                null
            } else {
                update.backgroundColor?.toArgbHex() ?: clip.style.backgroundColor
            },
            positionX = update.positionX ?: clip.style.positionX,
            positionY = update.positionY ?: clip.style.positionY,
            bold = update.bold ?: clip.style.bold,
            italic = update.italic ?: clip.style.italic,
            fontFamily = fontFamily ?: clip.style.fontFamily,
            outlineColor = if (update.clearOutline) {
                null
            } else {
                update.outlineColor?.toArgbHex() ?: clip.style.outlineColor
            },
            outlineWidth = update.outlineWidth ?: clip.style.outlineWidth,
            shadow = update.shadow ?: clip.style.shadow,
            curveRadius = update.curveRadius ?: clip.style.curveRadius,
        )

        applyTextStyle(clipId, style)
    }

    private fun applyTextStyle(clipId: String, style: RustTextStyleSnapshot) {
        val command = rustStore.commands.updateTextStyle(clipId, style)
        if (rustStore.transactionOpen()) {
            dispatchCoalescing(command, "Style")
            return
        }
        applySingleEdit("Style", command)
    }

    fun setSubtitleFont(clipId: String, fontPath: String?) {
        findRustClip(clipId)?.kind as? RustSubtitleClipKind ?: return
        if (fontPath == null) {
            dispatchAndSync(rustStore.commands.setSubtitleFont(clipId, null))
            return
        }
        viewModelScope.launch {
            try {
                val stagedPath = FontManager.importFont(fontPath)
                if (stagedPath == null) {
                    snackbarManager.showError("Not a usable font file (need .ttf/.otf)")
                    return@launch
                }
                dispatchAndSync(rustStore.commands.setSubtitleFont(clipId, stagedPath))
            } catch (e: Exception) {
                Napier.e("Failed to stage subtitle font", e)
                snackbarManager.showError("Could not use font: ${e.message}")
            }
        }
    }

    fun addMask(clipId: String, mask: RustMaskEffect) {
        dispatchAndSync(rustStore.commands.addMask(clipId, mask))
    }

    fun removeMask(clipId: String, index: Int) {
        dispatchAndSync(rustStore.commands.removeMask(clipId, index))
    }

    fun updateMask(clipId: String, index: Int, mask: RustMaskEffect): Boolean {
        return dispatchCoalescing(
            rustStore.commands.updateMask(clipId, index, mask),
            "Mask",
        )
    }

    fun setMaskEnabled(clipId: String, index: Int, enabled: Boolean) {
        dispatchAndSync(rustStore.commands.setMaskEnabled(clipId, index, enabled))
    }

    fun setTextTransitionIn(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(rustStore.commands.setTransitionIn(clipId, transition))
    }

    fun setTextTransitionOut(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(rustStore.commands.setTransitionOut(clipId, transition))
    }

    fun updateCropFilter(clipId: String, left: Float, top: Float, right: Float, bottom: Float) {
        val video = findRustClip(clipId)?.kind as? RustVideoClipKind ?: return
        val index = video.filters.indexOfFirst { it.enabled && it.filter is RustCropFilterSnapshot }
        if (index == -1) return
        updateFilterParams(
            clipId,
            index,
            mapOf("left" to left, "top" to top, "right" to right, "bottom" to bottom),
        )
    }

    fun fitClipToCanvas(clipId: String, fill: Boolean) {
        val snapshot = rustStore.snapshot.value ?: return
        val clip = findRustClip(clipId) ?: return
        val video = clip.kind as? RustVideoClipKind ?: return
        if (video.width <= 0 || video.height <= 0) return
        val videos = snapshot.timeline.tracks
            .flatMap { it.clips }
            .mapNotNull { it.kind as? RustVideoClipKind }
            .filter { it.width > 0 && it.height > 0 }
        val source = videos.firstOrNull() ?: return
        val (canvasWidth, canvasHeight) = snapshot.exportProfile.resolution.pixelSize(source.width, source.height)
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        val fitScale = minOf(
            canvasWidth.toFloat() / video.width,
            canvasHeight.toFloat() / video.height,
        )
        val targetScale = if (fill) {
            maxOf(
                canvasWidth.toFloat() / video.width,
                canvasHeight.toFloat() / video.height,
            ) / fitScale
        } else {
            1f
        }
        val index = video.filters.indexOfFirst { it.filter is RustTransformFilterSnapshot }
        val existing = video.filters.getOrNull(index)
        val effect = RustVideoEffectSnapshot(
            filter = RustTransformFilterSnapshot(
                scale = targetScale,
                translateX = 0f,
                translateY = 0f,
                rotate = (existing?.filter as? RustTransformFilterSnapshot)?.rotate ?: 0f,
            ),
            enabled = existing?.enabled ?: true,
        )
        val framingParams = listOf(
            KeyframeParams.TRANSFORM_SCALE,
            KeyframeParams.TRANSFORM_TRANSLATE_X,
            KeyframeParams.TRANSFORM_TRANSLATE_Y,
        )
        beginContinuousEdit("Framing")
        try {
            clip.keyframes.keyframes
                .withIndex()
                .filter { it.value.param in framingParams }
                .map { it.index }
                .sortedDescending()
                .forEach { keyframeIndex ->
                    dispatchCoalescing(rustStore.commands.removeKeyframe(clipId, keyframeIndex), "Framing")
                }
            if (index >= 0) {
                dispatchCoalescing(rustStore.commands.updateVideoFilter(clipId, index, effect), "Framing")
            } else {
                dispatchCoalescing(rustStore.commands.addVideoFilter(clipId, effect), "Framing")
            }
        } finally {
            commitContinuousEdit()
        }
    }

    fun autoReframeClip(clipId: String) {
        if (_state.value.autoReframeRunning || _state.value.sceneDetectRunning || _state.value.maskTrackRunning) return
        val snapshot = rustStore.snapshot.value ?: return
        val clip = findRustClip(clipId) ?: return
        val video = clip.kind as? RustVideoClipKind
        if (video == null) {
            snackbarManager.showError("Auto reframe needs a video clip")
            return
        }
        if (!video.fps.isFinite() || video.fps <= 0.0 || video.width <= 0 || video.height <= 0) {
            snackbarManager.showError("Auto reframe needs a clip with valid frame rate and dimensions")
            return
        }
        if (video.sourcePath.isBlank()) {
            snackbarManager.showError("Auto reframe needs a clip with a source file")
            return
        }
        if (clip.timelineDurationUs <= 0L || clip.sourceEndUs <= clip.sourceStartUs) {
            snackbarManager.showError("Auto reframe needs a clip with a valid source range")
            return
        }
        val source = snapshot.timeline.tracks
            .flatMap { it.clips }
            .mapNotNull { it.kind as? RustVideoClipKind }
            .firstOrNull { it.width > 0 && it.height > 0 }
            ?: video
        val (canvasWidth, canvasHeight) = snapshot.exportProfile.resolution.pixelSize(source.width, source.height)
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            snackbarManager.showError("Auto reframe needs a valid export resolution")
            return
        }

        _state.update { it.copy(autoReframeRunning = true) }
        viewModelScope.launch {
            try {
                val samples = gatherAutoReframeSamples(clip, video)
                if (samples.isEmpty()) {
                    snackbarManager.showError("Auto reframe found no frames to analyse")
                    return@launch
                }
                val keyframes = autoReframeKeyframes(
                    samples = smoothAutoReframeSamples(samples),
                    videoWidth = video.width,
                    videoHeight = video.height,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                )
                if (keyframes.isEmpty()) {
                    snackbarManager.showError("Auto reframe could not fit this clip to the canvas")
                    return@launch
                }
                val current = findRustClip(clipId)
                if (current == null ||
                    current.timelineDurationUs != clip.timelineDurationUs ||
                    current.sourceStartUs != clip.sourceStartUs ||
                    current.sourceEndUs != clip.sourceEndUs
                ) {
                    snackbarManager.showError("The clip changed during analysis; run Auto reframe again")
                    return@launch
                }
                val framingParams = listOf(
                    KeyframeParams.TRANSFORM_SCALE,
                    KeyframeParams.TRANSFORM_TRANSLATE_X,
                    KeyframeParams.TRANSFORM_TRANSLATE_Y,
                )
                beginContinuousEdit("Auto reframe")
                try {
                    current.keyframes.keyframes
                        .withIndex()
                        .filter { it.value.param in framingParams }
                        .map { it.index }
                        .sortedDescending()
                        .forEach { index ->
                            dispatchCoalescing(rustStore.commands.removeKeyframe(clipId, index), "Auto reframe")
                        }
                    for (keyframe in keyframes) {
                        dispatchCoalescing(rustStore.commands.addKeyframe(clipId, keyframe), "Auto reframe")
                    }
                } finally {
                    commitContinuousEdit()
                }
                snackbarManager.show("Auto reframe applied")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.showError("Auto reframe failed: ${e.message}")
            } finally {
                _state.update { it.copy(autoReframeRunning = false) }
            }
        }
    }

    private suspend fun gatherAutoReframeSamples(
        clip: RustClipSnapshot,
        video: RustVideoClipKind,
    ): List<AutoReframeSample> {
        val startMs = clip.sourceStartUs / 1000L
        val endMs = clip.sourceEndUs / 1000L
        if (endMs < startMs) return emptyList()
        val spanMs = endMs - startMs
        val stepMs = maxOf(AUTO_REFRAME_SAMPLE_STEP_MS, spanMs / AUTO_REFRAME_MAX_SAMPLES + 1L)
        val timestamps = mutableListOf<Long>()
        var timestampMs = startMs
        while (timestampMs <= endMs) {
            timestamps += timestampMs
            if (endMs - timestampMs < stepMs) break
            timestampMs += stepMs
        }
        if (timestamps.last() < endMs) timestamps += endMs

        val samples = mutableListOf<AutoReframeSample>()
        val grabber = PlatformFrameGrabber()
        try {
            grabber.open(video.sourcePath)
            for (sampleMs in timestamps) {
                val frame = grabber.grabFrame(
                    timestampMs = sampleMs,
                    width = 160,
                    height = 0,
                ) ?: continue
                val (x, y) = withContext(Dispatchers.Default) { saliencyCentroid(frame) }
                val sourceOffsetUs = (sampleMs - startMs) * 1000L
                samples += AutoReframeSample(
                    sourceMs = sampleMs,
                    localOffsetUs = autoReframeLocalOffset(clip, sourceOffsetUs),
                    x = x,
                    y = y,
                )
            }
        } finally {
            grabber.release()
        }
        return samples
    }

    fun stabilizeClip(clipId: String) {
        if (_state.value.stabilizeRunning || _state.value.autoReframeRunning || _state.value.sceneDetectRunning || _state.value.maskTrackRunning) return
        val clip = findRustClip(clipId) ?: return
        val video = clip.kind as? RustVideoClipKind
        if (video == null) {
            snackbarManager.showError("Stabilize needs a video clip")
            return
        }
        if (!video.fps.isFinite() || video.fps <= 0.0 || video.width <= 0 || video.height <= 0) {
            snackbarManager.showError("Stabilize needs a clip with valid frame rate and dimensions")
            return
        }
        if (video.sourcePath.isBlank()) {
            snackbarManager.showError("Stabilize needs a clip with a source file")
            return
        }
        if (clip.timelineDurationUs <= 0L || clip.sourceEndUs <= clip.sourceStartUs) {
            snackbarManager.showError("Stabilize needs a clip with a valid source range")
            return
        }

        _state.update { it.copy(stabilizeRunning = true) }
        viewModelScope.launch {
            try {
                val analysis = StabilizerAnalysis()
                val sampleStepMs = gatherStabilizerFrames(clip, video, analysis)
                if (analysis.frameCount < 3 || analysis.trackedSteps < 2) {
                    snackbarManager.showError("Stabilize needs at least 3 frames with trackable motion")
                    return@launch
                }
                val current = findRustClip(clipId)
                if (current == null ||
                    current.timelineDurationUs != clip.timelineDurationUs ||
                    current.sourceStartUs != clip.sourceStartUs ||
                    current.sourceEndUs != clip.sourceEndUs
                ) {
                    snackbarManager.showError("The clip changed during analysis; run Stabilize again")
                    return@launch
                }
                val currentVideo = current.kind as? RustVideoClipKind
                if (currentVideo == null) {
                    snackbarManager.showError("Stabilize needs a video clip")
                    return@launch
                }
                val base = currentVideo.filters
                    .firstOrNull { it.enabled && it.filter is RustTransformFilterSnapshot }
                    ?.filter as? RustTransformFilterSnapshot
                val keyframes = analysis.keyframes(
                    sourceWidth = currentVideo.width,
                    sourceHeight = currentVideo.height,
                    baseScale = base?.scale ?: 1f,
                    baseRotate = base?.rotate ?: 0f,
                    baseTranslateX = base?.translateX ?: 0f,
                    baseTranslateY = base?.translateY ?: 0f,
                    sampleStepMs = sampleStepMs,
                )
                if (keyframes.isEmpty()) {
                    snackbarManager.showError("Stabilize found no usable motion")
                    return@launch
                }
                val stabilizeParams = listOf(
                    KeyframeParams.TRANSFORM_SCALE,
                    KeyframeParams.TRANSFORM_TRANSLATE_X,
                    KeyframeParams.TRANSFORM_TRANSLATE_Y,
                    KeyframeParams.TRANSFORM_ROTATE,
                )
                beginContinuousEdit("Stabilize")
                try {
                    current.keyframes.keyframes
                        .withIndex()
                        .filter { it.value.param in stabilizeParams }
                        .map { it.index }
                        .sortedDescending()
                        .forEach { index ->
                            dispatchCoalescing(rustStore.commands.removeKeyframe(clipId, index), "Stabilize")
                        }
                    for (keyframe in keyframes) {
                        dispatchCoalescing(rustStore.commands.addKeyframe(clipId, keyframe), "Stabilize")
                    }
                } finally {
                    commitContinuousEdit()
                }
                snackbarManager.show("Stabilize applied")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.showError("Stabilize failed: ${e.message}")
            } finally {
                _state.update { it.copy(stabilizeRunning = false) }
            }
        }
    }

    private suspend fun gatherStabilizerFrames(
        clip: RustClipSnapshot,
        video: RustVideoClipKind,
        analysis: StabilizerAnalysis,
    ): Long {
        val startMs = clip.sourceStartUs / 1000L
        val endMs = clip.sourceEndUs / 1000L
        if (endMs < startMs) return STABILIZE_SAMPLE_STEP_MS
        val spanMs = endMs - startMs
        val stepMs = maxOf(STABILIZE_SAMPLE_STEP_MS, spanMs / STABILIZE_MAX_SAMPLES + 1L)
        val timestamps = mutableListOf<Long>()
        var timestampMs = startMs
        while (timestampMs <= endMs) {
            timestamps += timestampMs
            if (endMs - timestampMs < stepMs) break
            timestampMs += stepMs
        }
        if (timestamps.last() < endMs) timestamps += endMs

        val grabber = PlatformFrameGrabber()
        try {
            grabber.open(video.sourcePath)
            for (sampleMs in timestamps) {
                val frame = grabber.grabFrame(
                    timestampMs = sampleMs,
                    width = 320,
                    height = 0,
                ) ?: continue
                val sourceOffsetUs = (sampleMs - startMs) * 1000L
                val localOffsetUs = autoReframeLocalOffset(clip, sourceOffsetUs)
                withContext(Dispatchers.Default) {
                    analysis.addFrame(frame, localOffsetUs, video.width, video.height)
                }
            }
        } finally {
            grabber.release()
        }
        return stepMs
    }

    fun trackMask(clipId: String, maskIndex: Int) {
        val uiState = _state.value
        if (uiState.maskTrackRunning || uiState.autoReframeRunning || uiState.stabilizeRunning || uiState.removeSilenceRunning || uiState.sceneDetectRunning) return
        val snapshot = rustStore.snapshot.value ?: return
        val clip = findRustClip(clipId) ?: return
        val video = clip.kind as? RustVideoClipKind
        if (video == null) {
            snackbarManager.showError("Tracking needs a video clip")
            return
        }
        val mask = video.masks.getOrNull(maskIndex)
        if (mask == null) {
            snackbarManager.showError("Tracking needs a mask on this clip")
            return
        }
        if (!video.fps.isFinite() || video.fps <= 0.0 || video.width <= 0 || video.height <= 0) {
            snackbarManager.showError("Tracking needs a clip with valid frame rate and dimensions")
            return
        }
        if (video.sourcePath.isBlank()) {
            snackbarManager.showError("Tracking needs a clip with a source file")
            return
        }
        if (clip.timelineDurationUs <= 0L || clip.sourceEndUs <= clip.sourceStartUs) {
            snackbarManager.showError("Tracking needs a clip with a valid source range")
            return
        }
        if (clip.keyframes.keyframes.any { it.param == KeyframeParams.SPEED }) {
            snackbarManager.showError("Tracking does not support speed keyframes")
            return
        }
        val bounds = maskShapeBounds(mask)
        if (bounds == null) {
            snackbarManager.showError("Tracking needs a mask with a visible extent")
            return
        }
        val source = snapshot.timeline.tracks
            .flatMap { it.clips }
            .mapNotNull { it.kind as? RustVideoClipKind }
            .firstOrNull { it.width > 0 && it.height > 0 }
            ?: video
        val (canvasWidth, canvasHeight) = snapshot.exportProfile.resolution.pixelSize(source.width, source.height)
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            snackbarManager.showError("Tracking needs a valid export resolution")
            return
        }
        val region = maskRegionInVideoFrame(bounds, canvasWidth, canvasHeight, video.width, video.height)
        if (region == null) {
            snackbarManager.showError("Tracking could not map the mask onto the video frame")
            return
        }

        _state.update { it.copy(maskTrackRunning = true) }
        viewModelScope.launch {
            try {
                val analysis = MotionTrackerAnalysis(region)
                gatherMotionTrackerFrames(clip, video, analysis)
                if (analysis.frameCount < 3 || analysis.trackedSteps < 2) {
                    snackbarManager.showError("Tracking needs at least 3 frames with trackable motion in the mask region")
                    return@launch
                }
                val current = findRustClip(clipId)
                if (current == null ||
                    current.timelineDurationUs != clip.timelineDurationUs ||
                    current.sourceStartUs != clip.sourceStartUs ||
                    current.sourceEndUs != clip.sourceEndUs
                ) {
                    snackbarManager.showError("The clip changed during analysis; run Track again")
                    return@launch
                }
                val currentVideo = current.kind as? RustVideoClipKind
                if (currentVideo == null) {
                    snackbarManager.showError("Tracking needs a video clip")
                    return@launch
                }
                val currentMask = currentVideo.masks.getOrNull(maskIndex)
                if (currentMask == null) {
                    snackbarManager.showError("Tracking needs a mask on this clip")
                    return@launch
                }
                val bake = motionTrackingKeyframes(
                    samples = analysis.motionSamples(),
                    mask = currentMask,
                    maskIndex = maskIndex,
                    videoWidth = currentVideo.width,
                    videoHeight = currentVideo.height,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                )
                if (bake == null) {
                    snackbarManager.showError("Tracking found no usable motion")
                    return@launch
                }
                beginContinuousEdit("Motion tracking")
                try {
                    current.keyframes.keyframes
                        .withIndex()
                        .filter { it.value.param in bake.params }
                        .map { it.index }
                        .sortedDescending()
                        .forEach { index ->
                            dispatchCoalescing(rustStore.commands.removeKeyframe(clipId, index), "Motion tracking")
                        }
                    for (keyframe in bake.keyframes) {
                        dispatchCoalescing(rustStore.commands.addKeyframe(clipId, keyframe), "Motion tracking")
                    }
                } finally {
                    commitContinuousEdit()
                }
                snackbarManager.show("Tracking applied")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.showError("Tracking failed: ${e.message}")
            } finally {
                _state.update { it.copy(maskTrackRunning = false) }
            }
        }
    }

    private suspend fun gatherMotionTrackerFrames(
        clip: RustClipSnapshot,
        video: RustVideoClipKind,
        analysis: MotionTrackerAnalysis,
    ) {
        val startMs = clip.sourceStartUs / 1000L
        val endMs = clip.sourceEndUs / 1000L
        if (endMs < startMs) return
        val spanMs = endMs - startMs
        val stepMs = maxOf(MOTION_TRACK_SAMPLE_STEP_MS, spanMs / MOTION_TRACK_MAX_SAMPLES + 1L)
        val timestamps = mutableListOf<Long>()
        var timestampMs = startMs
        while (timestampMs <= endMs) {
            timestamps += timestampMs
            if (endMs - timestampMs < stepMs) break
            timestampMs += stepMs
        }
        if (timestamps.last() < endMs) timestamps += endMs

        val grabber = PlatformFrameGrabber()
        try {
            grabber.open(video.sourcePath)
            for (sampleMs in timestamps) {
                val frame = grabber.grabFrame(
                    timestampMs = sampleMs,
                    width = 320,
                    height = 0,
                ) ?: continue
                val sourceOffsetUs = (sampleMs - startMs) * 1000L
                val localOffsetUs = autoReframeLocalOffset(clip, sourceOffsetUs)
                withContext(Dispatchers.Default) {
                    analysis.addFrame(frame, localOffsetUs, video.width, video.height)
                }
            }
        } finally {
            grabber.release()
        }
    }

    fun updateFilterParams(clipId: String, filterIndex: Int, newParams: Map<String, Float>) {
        val filters = findRustClip(clipId)?.videoFilters ?: return
        val current = filters.getOrNull(filterIndex) ?: return
        val updatedFilter = when (val filter = current.filter) {
            is RustBrightnessFilterSnapshot -> RustBrightnessFilterSnapshot(newParams["value"] ?: filter.value)
            is RustContrastFilterSnapshot -> RustContrastFilterSnapshot(newParams["value"] ?: filter.value)
            is RustSaturationFilterSnapshot -> RustSaturationFilterSnapshot(newParams["value"] ?: filter.value)
            is RustColorGradeFilterSnapshot -> RustColorGradeFilterSnapshot(
                brightness = newParams["brightness"] ?: filter.brightness,
                contrast = newParams["contrast"] ?: filter.contrast,
                saturation = newParams["saturation"] ?: filter.saturation,
                vibrance = newParams["vibrance"] ?: filter.vibrance,
                temperature = newParams["temperature"] ?: filter.temperature,
                tint = newParams["tint"] ?: filter.tint,
                exposure = newParams["exposure"] ?: filter.exposure,
                gamma = newParams["gamma"] ?: filter.gamma,
                lift = newParams["lift"] ?: filter.lift,
                gain = newParams["gain"] ?: filter.gain,
                highlights = newParams["highlights"] ?: filter.highlights,
                shadows = newParams["shadows"] ?: filter.shadows,
            )
            is RustChromaKeyFilterSnapshot -> filter.copy(
                tolerance = newParams["tolerance"] ?: filter.tolerance,
                softness = newParams["softness"] ?: filter.softness,
                spill = newParams["spill"] ?: filter.spill,
                spillSuppression = newParams["spill_suppression"] ?: filter.spillSuppression,
                choke = newParams["choke"] ?: filter.choke,
            )
            is RustMosaicFilterSnapshot -> filter.copy(
                size = newParams["size"] ?: filter.size,
            )
            is RustLutFilterSnapshot -> filter.copy(
                strength = newParams["strength"] ?: filter.strength,
            )
            is RustVignetteFilterSnapshot -> filter.copy(
                amount = newParams["amount"] ?: filter.amount,
                radius = newParams["radius"] ?: filter.radius,
                softness = newParams["softness"] ?: filter.softness,
            )
            is RustPosterizeFilterSnapshot -> filter.copy(
                levels = newParams["levels"] ?: filter.levels,
            )
            is RustGrainFilterSnapshot -> filter.copy(
                amount = newParams["amount"] ?: filter.amount,
                size = newParams["size"] ?: filter.size,
            )
            is RustBlurFilterSnapshot -> filter.copy(
                radius = newParams["radius"] ?: filter.radius,
                angle = newParams["angle"] ?: filter.angle,
            )
            is RustSharpenFilterSnapshot -> RustSharpenFilterSnapshot(newParams["amount"] ?: filter.amount)
            is RustTransformFilterSnapshot -> RustTransformFilterSnapshot(
                scale = newParams["scale"] ?: filter.scale,
                translateX = newParams["translate_x"] ?: filter.translateX,
                translateY = newParams["translate_y"] ?: filter.translateY,
                rotate = newParams["rotate"] ?: filter.rotate,
            )
            is RustCropFilterSnapshot -> RustCropFilterSnapshot(
                left = newParams["left"] ?: filter.left,
                top = newParams["top"] ?: filter.top,
                right = newParams["right"] ?: filter.right,
                bottom = newParams["bottom"] ?: filter.bottom,
            )
            is RustRotateFilterSnapshot -> RustRotateFilterSnapshot(
                degrees = newParams["degrees"] ?: filter.degrees,
            )
            is RustHueFilterSnapshot -> RustHueFilterSnapshot(
                degrees = newParams["degrees"] ?: filter.degrees,
            )
            is RustFlipFilterSnapshot -> RustFlipFilterSnapshot(
                horizontal = newParams["horizontal"]?.let { it > 0.5f } ?: filter.horizontal,
                vertical = newParams["vertical"]?.let { it > 0.5f } ?: filter.vertical,
            )
            else -> filter
        }

        dispatchCoalescing(
            rustStore.commands.updateVideoFilter(
                clipId = clipId,
                index = filterIndex,
                filter = current.copy(filter = updatedFilter),
            ),
            "Filter",
        )
    }

    fun replaceFilter(clipId: String, filterIndex: Int, filter: RustVideoFilterSnapshot) {
        val filters = findRustClip(clipId)?.videoFilters ?: return
        val current = filters.getOrNull(filterIndex) ?: return
        dispatchAndSync(
            rustStore.commands.updateVideoFilter(
                clipId = clipId,
                index = filterIndex,
                filter = current.copy(filter = filter),
            ),
        )
    }

    fun addFilter(clipId: String, filter: RustVideoEffectSnapshot) {
        dispatchAndSync(
            rustStore.commands.addVideoFilter(
                clipId = clipId,
                filter = filter,
            ),
        )
    }

    fun updateClipTransform(clipId: String, scale: Float, translateX: Float, translateY: Float, rotate: Float) {
        val video = findRustClip(clipId)?.kind as? RustVideoClipKind ?: return
        val values = mapOf("scale" to scale, "translate_x" to translateX, "translate_y" to translateY, "rotate" to rotate)
        val index = video.filters.indexOfFirst { it.filter is RustTransformFilterSnapshot }
        if (index != -1) {
            updateFilterParams(clipId, index, values)
            return
        }
        dispatchAndSync(
            rustStore.commands.addVideoFilter(
                clipId = clipId,
                filter = RustVideoEffectSnapshot(
                    filter = RustTransformFilterSnapshot(
                        scale = scale,
                        translateX = translateX,
                        translateY = translateY,
                        rotate = rotate,
                    ),
                    enabled = true,
                ),
            ),
        )
    }

    fun applyFilterToAllClips(sourceClipId: String, filterIndex: Int) {
        val snapshot = rustStore.snapshot.value ?: return
        val clips = snapshot.timeline.tracks.flatMap { it.clips }
        val source = clips.firstOrNull { it.id == sourceClipId } ?: return
        val effect = source.videoFilters.getOrNull(filterIndex) ?: return
        val targets = clips.filter { it.id != sourceClipId && it.kind is RustVideoClipKind }
        if (targets.isEmpty()) return

        beginContinuousEdit("Apply filter to all")
        try {
            for (clip in targets) {
                dispatchCoalescing(
                    rustStore.commands.addVideoFilter(clip.id, effect),
                    "Apply filter to all",
                )
            }
        } finally {
            commitContinuousEdit()
        }
    }

    fun applyTransitionToAllCuts(kind: RustTransitionKind, durationUs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val targets = mutableListOf<String>()
        for (track in snapshot.timeline.tracks) {
            val clips = track.clips
            for (i in 0 until clips.size - 1) {
                val current = clips[i]
                val next = clips[i + 1]
                if (next.kind !is RustVideoClipKind) continue
                val gap = next.timelineStartUs - (current.timelineStartUs + current.timelineDurationUs)
                if (gap > 2_000L) continue
                targets += next.id
            }
        }
        if (targets.isEmpty()) return

        beginContinuousEdit("Apply transition to all")
        try {
            for (clipId in targets) {
                dispatchCoalescing(
                    rustStore.commands.setTransitionIn(
                        clipId,
                        RustTransitionSnapshot(kind, durationUs),
                    ),
                    "Apply transition to all",
                )
            }
        } finally {
            commitContinuousEdit()
        }
    }

    fun removeFilter(clipId: String, filterIndex: Int) {
        dispatchAndSync(rustStore.commands.removeVideoFilter(clipId, filterIndex))
    }

    fun setFilterEnabled(clipId: String, filterIndex: Int, enabled: Boolean) {
        dispatchAndSync(rustStore.commands.setVideoFilterEnabled(clipId, filterIndex, enabled))
    }

    fun moveFilter(clipId: String, fromIndex: Int, toIndex: Int) {
        dispatchAndSync(rustStore.commands.moveVideoFilter(clipId, fromIndex, toIndex))
    }

    fun setTransitionIn(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(rustStore.commands.setTransitionIn(clipId, transition))
    }

    fun setTransitionOut(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(rustStore.commands.setTransitionOut(clipId, transition))
    }

    fun addKeyframe(clipId: String, keyframe: RustKeyframe) {
        dispatchAndSync(rustStore.commands.addKeyframe(clipId, keyframe))
    }

    fun applyKenBurns(clipId: String, preset: KenBurnsPreset) {
        val clip = findRustClip(clipId) ?: return
        val durationUs = clip.timelineDurationUs
        if (durationUs <= 0L) return
        beginContinuousEdit("Ken Burns")
        try {
            for (keyframe in preset.keyframesFor(durationUs)) {
                dispatchCoalescing(rustStore.commands.addKeyframe(clipId, keyframe), "Ken Burns")
            }
        } finally {
            commitContinuousEdit()
        }
    }

    fun applyTextAnimation(clipId: String, preset: TextAnimationPreset) {
        val clip = findRustClip(clipId) ?: return
        val kind = clip.kind as? RustTextClipKind ?: return
        val durationUs = clip.timelineDurationUs
        if (durationUs <= 0L) return
        val base = TextAnimationBase(
            opacity = clip.opacity,
            positionY = kind.style.positionY,
            fontSize = kind.style.fontSize,
        )
        beginContinuousEdit("Text Animation")
        try {
            clip.keyframes.keyframes
                .withIndex()
                .filter { it.value.param in preset.paramKeys }
                .map { it.index }
                .sortedDescending()
                .forEach { index ->
                    dispatchCoalescing(rustStore.commands.removeKeyframe(clipId, index), "Text Animation")
                }
            for (keyframe in preset.keyframesFor(durationUs, base)) {
                dispatchCoalescing(rustStore.commands.addKeyframe(clipId, keyframe), "Text Animation")
            }
        } finally {
            commitContinuousEdit()
        }
    }

    fun applySpeedCurve(clipId: String, preset: SpeedCurvePreset?) {
        val clip = findRustClip(clipId) ?: return
        if (preset == null) {
            if (clip.keyframes.keyframes.none { it.param == KeyframeParams.SPEED }) return
        } else if (clip.timelineDurationUs <= 0L) {
            return
        }
        val clipEndUs = clip.timelineStartUs + clip.timelineDurationUs
        var shiftUs = 0L
        if (preset != null) {
            val predictedUs = predictSpeedCurveDurationUs(clipId, preset, clip.timelineDurationUs)
            if (predictedUs == null) {
                snackbarManager.showError("Could not measure the speed curve")
                return
            }
            shiftUs = predictedUs - clip.timelineDurationUs
            if (shiftUs > 0L && straddlesRippleBoundary(clipId, clipEndUs)) {
                snackbarManager.showError("Another track has a clip across the clip end")
                return
            }
        }
        beginContinuousEdit("Speed curve")
        var failed = false
        try {
            if (!removeSpeedKeyframes(clipId)) {
                failed = true
            } else if (preset != null && shiftUs > 0L && !rippleFrom(clipId, clipEndUs, shiftUs)) {
                failed = true
            } else if (preset != null && !writeSpeedCurveFixedPoint(clipId, preset, clip.timelineDurationUs)) {
                failed = true
            }
        } finally {
            if (failed) cancelContinuousEdit() else commitContinuousEdit()
        }
    }

    private fun predictSpeedCurveDurationUs(
        clipId: String,
        preset: SpeedCurvePreset,
        durationUs: Long,
    ): Long? {
        var baseUs = durationUs
        var predictedUs =
            rustStore.predictedSpeedCurveDurationUs(clipId, preset.keyframesFor(baseUs)) ?: return null
        var longestUs = predictedUs
        var iterations = 0
        while (iterations < SPEED_CURVE_MAX_ITERATIONS && abs(predictedUs - baseUs) > SPEED_CURVE_TOLERANCE_US) {
            baseUs = predictedUs
            predictedUs =
                rustStore.predictedSpeedCurveDurationUs(clipId, preset.keyframesFor(baseUs)) ?: return null
            if (predictedUs > longestUs) longestUs = predictedUs
            iterations++
        }
        return longestUs
    }

    private fun writeSpeedCurveFixedPoint(
        clipId: String,
        preset: SpeedCurvePreset,
        durationUs: Long,
    ): Boolean {
        var baseUs = durationUs
        if (!writeSpeedKeyframes(clipId, preset, baseUs)) return false
        var iterations = 0
        while (iterations < SPEED_CURVE_MAX_ITERATIONS) {
            val nextDurationUs = findRustClip(clipId)?.timelineDurationUs ?: break
            if (abs(nextDurationUs - baseUs) <= SPEED_CURVE_TOLERANCE_US) break
            if (!removeSpeedKeyframes(clipId)) return false
            baseUs = nextDurationUs
            if (!writeSpeedKeyframes(clipId, preset, baseUs)) return false
            iterations++
        }
        return true
    }

    private fun straddlesRippleBoundary(clipId: String, boundaryUs: Long): Boolean {
        val tracks = rustStore.snapshot.value?.timeline?.tracks ?: return false
        val track = tracks.firstOrNull { candidate -> candidate.clips.any { it.id == clipId } } ?: return false
        return tracks.any { other ->
            other.id != track.id && other.clips.any { clip ->
                clip.timelineStartUs < boundaryUs &&
                    clip.timelineStartUs + clip.timelineDurationUs > boundaryUs
            }
        }
    }

    private fun rippleFrom(clipId: String, boundaryUs: Long, shiftUs: Long): Boolean {
        val tracks = rustStore.snapshot.value?.timeline?.tracks ?: return false
        for (track in tracks) {
            val following = track.clips
                .filter { it.id != clipId && it.timelineStartUs >= boundaryUs }
                .sortedByDescending { it.timelineStartUs }
            for (later in following) {
                val moved = dispatchCoalescing(
                    rustStore.commands.moveClip(later.id, track.id, later.timelineStartUs + shiftUs),
                    "Speed curve",
                )
                if (!moved) return false
            }
        }
        return true
    }

    private fun writeSpeedKeyframes(clipId: String, preset: SpeedCurvePreset, durationUs: Long): Boolean {
        for (keyframe in preset.keyframesFor(durationUs)) {
            if (!dispatchCoalescing(rustStore.commands.addKeyframe(clipId, keyframe), "Speed curve")) {
                return false
            }
        }
        return true
    }

    private fun removeSpeedKeyframes(clipId: String): Boolean {
        val clip = findRustClip(clipId) ?: return true
        return clip.keyframes.keyframes
            .withIndex()
            .filter { it.value.param == KeyframeParams.SPEED }
            .map { it.index }
            .sortedDescending()
            .all { index ->
                dispatchCoalescing(rustStore.commands.removeKeyframe(clipId, index), "Speed curve")
            }
    }

    fun removeKeyframe(clipId: String, index: Int) {
        dispatchAndSync(rustStore.commands.removeKeyframe(clipId, index))
    }

    fun updateKeyframe(clipId: String, index: Int, keyframe: RustKeyframe) {
        dispatchCoalescing(
            rustStore.commands.updateKeyframe(clipId, index, keyframe),
            "Keyframe",
        )
    }

    fun toggleTrackMute(trackId: String) {
        val muted = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.muted ?: return
        dispatchAndSync(rustStore.commands.setTrackMuted(trackId, !muted))
    }

    fun toggleTrackLock(trackId: String) {
        val locked = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.locked ?: return
        dispatchAndSync(rustStore.commands.setTrackLocked(trackId, !locked))
    }

    fun selectClip(clipId: String?) {
        _state.update {
            it.copy(
                selectedClipId = clipId,
                selectedClipIds = if (clipId == null) emptySet() else setOf(clipId),
            )
        }
    }

    fun toggleClipSelection(clipId: String) {
        if (findRustClip(clipId) == null) return
        _state.update { state ->
            val next = if (clipId in state.selectedClipIds) {
                state.selectedClipIds - clipId
            } else {
                state.selectedClipIds + clipId
            }
            val primary = when {
                clipId in next -> clipId
                state.selectedClipId == clipId -> next.firstOrNull()
                else -> state.selectedClipId
            }
            state.copy(selectedClipId = primary, selectedClipIds = next)
        }
    }

    fun extendClipSelection(clipId: String) {
        if (findRustClip(clipId) == null) return
        _state.update {
            it.copy(
                selectedClipId = clipId,
                selectedClipIds = it.selectedClipIds + clipId,
            )
        }
    }

    fun selectAllClips() {
        val snapshot = rustStore.snapshot.value ?: return
        val clipIds = snapshot.timeline.tracks.flatMap { track -> track.clips.map { it.id } }
        if (clipIds.isEmpty()) return
        val primary = _state.value.selectedClipId?.takeIf { it in clipIds } ?: clipIds.first()
        _state.update { it.copy(selectedClipId = primary, selectedClipIds = clipIds.toSet()) }
    }

    fun clearClipSelection() = selectClip(null)

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoomLevel = zoom.coerceIn(0.1f, 4f)) }
    }

    fun snapPosition(ms: Long, excludeClipIds: Set<String> = emptySet()): Long {
        val nearest = computeSnapPosition(ms, excludeClipIds)
        _state.update { it.copy(snapIndicatorMs = if (nearest != ms) nearest else null) }
        return nearest
    }

    private fun computeSnapPosition(
        ms: Long,
        excludeClipIds: Set<String> = emptySet(),
        includePlayhead: Boolean = true,
    ): Long {
        val snapThresholdMs = (SNAP_THRESHOLD_BASE / _state.value.zoomLevel).toLong().coerceAtLeast(50)
        val snapshot = rustStore.snapshot.value ?: return ms
        val playhead = _state.value.playheadMs

        var nearest = ms
        var minDist = snapThresholdMs

        // Scrubbing must not snap to where the playhead already is, or it
        // sticks to its own previous position.
        if (includePlayhead) {
            val dph = kotlin.math.abs(ms - playhead)
            if (dph < minDist) {
                nearest = playhead
                minDist = dph
            }
        }

        for (beatMs in beatMarkersMs.value) {
            val dbe = kotlin.math.abs(ms - beatMs)
            if (dbe < minDist) {
                nearest = beatMs
                minDist = dbe
            }
        }

        for (marker in snapshot.markers) {
            val markerMs = marker.time.usToMs
            val dm = kotlin.math.abs(ms - markerMs)
            if (dm < minDist) {
                nearest = markerMs
                minDist = dm
            }
        }

        for (clip in snapshot.timeline.tracks.flatMap { it.clips }) {
            if (clip.id in excludeClipIds) continue
            val startMs = clip.timelineStartUs.usToMs
            val endMs = (clip.timelineStartUs + clip.timelineDurationUs).usToMs

            val ds = kotlin.math.abs(ms - startMs)
            if (ds < minDist) {
                nearest = startMs
                minDist = ds
            }
            val de = kotlin.math.abs(ms - endMs)
            if (de < minDist) {
                nearest = endMs
                minDist = de
            }
        }

        val d0 = kotlin.math.abs(ms - 0L)
        if (d0 <= snapThresholdMs && d0 < minDist) {
            nearest = 0L
        }

        return nearest
    }

    fun clearSnapIndicator() {
        _state.update { it.copy(snapIndicatorMs = null) }
    }

    private fun findNearestNonOverlappingStartUs(
        track: RustTrackSnapshot,
        clipId: String?,
        requestedStartUs: Long,
        durationUs: Long,
    ): Long {
        val clips = track.clips
            .asSequence()
            .filter { clipId == null || it.id != clipId }
            .sortedBy { it.timelineStartUs }
            .toList()

        if (clips.isEmpty()) {
            return requestedStartUs.coerceAtLeast(0L)
        }

        val safeDuration = durationUs.coerceAtLeast(1L)
        val reqStart = requestedStartUs.coerceAtLeast(0L)

        if (fitsWithoutOverlap(clips, reqStart, safeDuration)) return reqStart

        var prevEnd = 0L
        var best: Long? = null
        var bestDist = Long.MAX_VALUE
        fun consider(candidate: Long) {
            if (candidate < 0L) return
            if (!fitsWithoutOverlap(clips, candidate, safeDuration)) return
            val dist = kotlin.math.abs(candidate - reqStart)
            if (dist < bestDist) {
                bestDist = dist
                best = candidate
            }
        }
        for (clip in clips) {
            val gapStart = prevEnd
            val gapEnd = clip.timelineStartUs
            if (gapEnd - gapStart >= safeDuration) {
                val candidate = reqStart.coerceIn(gapStart, (gapEnd - safeDuration).coerceAtLeast(gapStart))
                consider(candidate)
                consider(gapStart)
            }
            prevEnd = maxOf(prevEnd, clip.timelineStartUs + clip.timelineDurationUs)
        }
        consider(maxOf(prevEnd, 0L))
        if (best != null) return best!!
        var candidate = reqStart
        for (clip in clips) {
            val start = clip.timelineStartUs
            val end = clip.timelineStartUs + clip.timelineDurationUs
            if (candidate < end && start < candidate + safeDuration) {
                candidate = end
            }
        }
        return candidate
    }

    private fun fitsWithoutOverlap(clips: List<RustClipSnapshot>, startUs: Long, durationUs: Long): Boolean {
        val endUs = if (durationUs > Long.MAX_VALUE - startUs) Long.MAX_VALUE else startUs + durationUs
        return clips.none { existing -> existing.overlapsRange(startUs, endUs) }
    }

    private fun nextClipStartUs(track: RustTrackSnapshot, clipId: String): Long? {
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return null
        val currentStart = clip.timelineStartUs
        val clipEnd = clip.timelineStartUs + clip.timelineDurationUs
        return track.clips
            .asSequence()
            .filter { it.id != clipId }
            .filter { other ->
                val otherEnd = other.timelineStartUs + other.timelineDurationUs
                other.timelineStartUs < clipEnd && otherEnd > currentStart
            }
            .filter { it.timelineStartUs > currentStart }
            .minOfOrNull { it.timelineStartUs }
            ?: track.clips
                .asSequence()
                .filter { it.id != clipId && it.timelineStartUs > currentStart }
                .minOfOrNull { it.timelineStartUs }
    }

    private fun ensureTrack(kind: RustTrackKind, defaultName: String): String {
        val existing = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.kind == kind }?.id
        if (existing != null) return existing

        rustStore.dispatch(rustStore.commands.addTrack(kind, defaultName))
        return rustStore.snapshot.value
            ?.timeline
            ?.tracks
            ?.firstOrNull { it.kind == kind }
            ?.id
            ?: error("Track creation failed for $kind")
    }

    private fun findRustClip(clipId: String): RustClipSnapshot? {
        return rustStore.snapshot.value
            ?.timeline
            ?.tracks
            ?.flatMap { it.clips }
            ?.firstOrNull { it.id == clipId }
    }

    private fun findClipWithTrack(
        snapshot: RustProjectSnapshot,
        clipId: String,
    ): Pair<RustTrackSnapshot, RustClipSnapshot>? {
        return snapshot.timeline.tracks.firstNotNullOfOrNull { track ->
            track.clips.firstOrNull { it.id == clipId }?.let { track to it }
        }
    }

    private fun dispatchSilent(commandJson: String) {
        rustStore.dispatch(commandJson)
    }

    private fun syncFromRust(
        projectPath: String? = _state.value.projectPath,
        selectedTrackId: String? = _state.value.selectedTrackId,
        selectedClipId: String? = _state.value.selectedClipId,
        selectedClipIds: Set<String> = _state.value.selectedClipIds,
        playheadMs: Long = _state.value.playheadMs,
        isDirty: Boolean = true,
    ) {
        val snapshot = rustStore.snapshot.value
        val hasVideoClips = snapshot?.timeline?.tracks
            ?.flatMap { it.clips }
            ?.any { it.kind is RustVideoClipKind } == true
        if (!hasVideoClips) {
            sourceDurationMs = 0L
        }
        val liveClipIds = snapshot?.timeline?.tracks
            ?.flatMap { it.clips }
            ?.map { it.id }
            ?.toSet()
            ?: emptySet()
        val reconciledSelection: Set<String> = if (selectedClipId == null) {
            emptySet()
        } else {
            val kept = selectedClipIds.filterTo(mutableSetOf()) { it in liveClipIds }
            if (selectedClipId in liveClipIds) kept.add(selectedClipId)
            kept
        }
        val primaryClipId = selectedClipId?.takeIf { it in reconciledSelection } ?: reconciledSelection.firstOrNull()
        _state.update {
            it.copy(
                snapshot = snapshot,
                projectPath = projectPath,
                selectedTrackId = selectedTrackId,
                selectedClipId = primaryClipId,
                selectedClipIds = reconciledSelection,
                playheadMs = playheadMs,
                isLoading = false,
                isDirty = isDirty,
                canUndo = rustStore.canUndo(),
                canRedo = rustStore.canRedo(),
                undoLabel = rustStore.undoLabel(),
                redoLabel = rustStore.redoLabel(),
                thumbnails = if (hasVideoClips) it.thumbnails else emptyList(),
            )
        }
    }

    private fun handleError(message: String, clearImportState: Boolean = false) {
        _state.update {
            it.copy(
                isLoading = false,
                importProgress = if (clearImportState) null else it.importProgress,
            )
        }
        snackbarManager.showError(message)
    }
}
