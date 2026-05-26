package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mlm.miniter.editor.RustProjectStore
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustExportFormat
import org.mlm.miniter.editor.model.RustExportProfileSnapshot
import org.mlm.miniter.editor.model.RustExportResolution
import org.mlm.miniter.editor.model.RustFadeInAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFadeOutAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
import org.mlm.miniter.editor.model.RustAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustSepiaFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustSubtitleClipKind
import org.mlm.miniter.editor.model.RustTextClipKind
import org.mlm.miniter.editor.model.RustTextStyleSnapshot
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustTrackSnapshot
import org.mlm.miniter.editor.model.RustTransitionKind
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.editor.model.RustVideoEffectSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.overlapsRange
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.engine.ThumbnailResult
import org.mlm.miniter.engine.VideoInfo
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.msToUs
import org.mlm.miniter.platform.usToMs
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.RecentProjectsRepository
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.ui.util.toArgbHex
import kotlin.time.Clock

data class ProjectUiState(
    val snapshot: RustProjectSnapshot? = null,
    val projectPath: String? = null,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val isDirty: Boolean = false,
    val zoomLevel: Float = 1f,
    val thumbnails: List<ImageData> = emptyList(),
    val isLoadingThumbnails: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val snapIndicatorMs: Long? = null,
)

class ProjectViewModel(
    private val recentProjectsRepository: RecentProjectsRepository,
    private val engine: PlatformVideoEngine,
    private val snackbarManager: SnackbarManager,
    private val rustStore: RustProjectStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state

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
        private const val DEFAULT_SUBTITLE_DURATION_MS = 60_000L
        private const val MIN_SAMPLE_RATE = 44_100
        private const val MIN_TRIM_DURATION_US = 100_000L
        private const val SNAP_THRESHOLD_BASE = 200
    }

    private var sourceDurationMs: Long = 0L
    private var autoSaveJob: Job? = null
    private var preDragSnapshot: RustProjectSnapshot? = null
    private var continuousEditCommandCount = 0

    fun newProject(name: String, initialVideoPath: String, savePath: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val stagedVideoPath = PlatformFileSystem.stageForNativeAccess(initialVideoPath)
                val info = engine.probeVideo(stagedVideoPath)
                if (!info.hasVideo) {
                    handleError("Selected file has no video stream")
                    return@launch
                }
                when (val result = engine.extractSingleThumbnail(stagedVideoPath, 0L, 160, 90)) {
                    is ThumbnailResult.Error -> {
                        handleError(result.message)
                        return@launch
                    }
                    is ThumbnailResult.Success -> { /* decodable */ }
                }
                sourceDurationMs = info.durationMs

                rustStore.create(name)
                val videoTrackId = ensureTrack(RustTrackKind.Video, "Video 1")

                val hasAudio = info.hasAudio
                val audioTrackId = if (hasAudio) ensureTrack(RustTrackKind.Audio, "Audio 1") else null

                val durationUs = info.durationMs.msToUs

                val videoClipVolume = if (hasAudio) 0.0f else 1.0f

                val commands = mutableListOf(
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
                            volume = videoClipVolume,
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
                    selectedTrackId = videoTrackId,
                    selectedClipId = null,
                    playheadMs = 0L,
                    isDirty = true,
                )

                loadThumbnails(stagedVideoPath)
                if (savePath != null) {
                    recentProjectsRepository.addRecent(savePath, name)
                }
            } catch (e: Exception) {
                handleError("Failed to open video: ${e.message}")
            }
        }
    }

    fun loadProject(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val projectJson = PlatformFileSystem.readText(path)
                if (projectJson.isBlank()) {
                    handleError("Selected project file is empty")
                    return@launch
                }

                val snapshot = rustStore.openProjectJson(projectJson)

                val missingFiles = snapshot.timeline.tracks
                    .flatMap { it.clips }
                    .mapNotNull { clip ->
                        when (val kind = clip.kind) {
                            is RustVideoClipKind -> kind.sourcePath
                            is RustAudioClipKind -> kind.sourcePath
                            is RustTextClipKind -> null
                            is RustSubtitleClipKind -> kind.sourcePath
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
                    } catch (_: Exception) {
                        clip.timelineDurationUs / 1000L
                    }
                    loadThumbnails(sourcePath)
                }

                recentProjectsRepository.addRecent(path, snapshot.meta.name)
            } catch (e: Exception) {
                handleError("Failed to load project: ${e.message}")
            }
        }
    }

    fun saveProject(path: String? = null) {
        viewModelScope.launch {
            val snapshot = rustStore.snapshot.value ?: return@launch
            val savePath = path ?: _state.value.projectPath ?: return@launch
            _state.update { it.copy(isSaving = true) }
            try {
                val projectJson = rustStore.exportProjectJson()
                PlatformFileSystem.writeText(savePath, projectJson)
                _state.update { it.copy(projectPath = savePath, isSaving = false, isDirty = false) }
                recentProjectsRepository.addRecent(savePath, snapshot.meta.name)
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
                snackbarManager.showError("Failed to save: ${e.message}")
            }
        }
    }

    fun renameProject(newName: String) {
        val snapshot = rustStore.snapshot.value ?: return
        rustStore.replaceSnapshot(
            snapshot.copy(
                meta = snapshot.meta.copy(
                    name = newName,
                    modifiedAt = Clock.System.now().toEpochMilliseconds(),
                )
            )
        )
        syncFromRust()
    }

    fun startAutoSave(enabled: Boolean, intervalSeconds: Float) {
        autoSaveJob?.cancel()
        if (!enabled) return
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay((intervalSeconds * 1000).toLong())
                val s = _state.value
                if (s.isDirty && !s.isSaving) {
                    if (s.projectPath != null) saveProject()
                    else snackbarManager.show("Auto-save unavailable - save project manually first")
                }
            }
        }
    }

    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    fun undo() {
        rustStore.undo() ?: return
        syncFromRust()
    }

    fun redo() {
        rustStore.redo() ?: return
        syncFromRust()
    }

    fun beginContinuousEdit() {
        continuousEditCommandCount = 0
        preDragSnapshot = rustStore.snapshot.value
    }

    fun commitContinuousEdit() {
        preDragSnapshot = null
        continuousEditCommandCount = 0
        syncFromRust()
    }

    fun cancelContinuousEdit() {
        if (preDragSnapshot == null) return
        repeat(continuousEditCommandCount) {
            rustStore.undo()
        }
        preDragSnapshot = null
        continuousEditCommandCount = 0
        syncFromRust()
    }

    fun beginEdit() = beginContinuousEdit()
    fun commitEdit() = commitContinuousEdit()
    fun cancelEdit() = cancelContinuousEdit()

    fun initProject(
        videoPath: String,
        projectName: String,
        savePath: String?,
        openAsProject: Boolean = false,
    ) {
        if (openAsProject) {
            loadProject(videoPath)
        } else {
            newProject(projectName, videoPath, savePath)
        }
    }

    fun togglePlayPause() {
        setPlaying(!_state.value.isPlaying)
    }

    fun seekTo(ms: Long) = setPlayhead(ms)

    fun seekRelative(deltaMs: Long) {
        val current = _state.value.playheadMs
        val max = if (rustStore.snapshot.value != null) rustStore.durationUs().usToMs else Long.MAX_VALUE
        setPlayhead((current + deltaMs).coerceIn(0, max))
    }

    fun seekToStart() = setPlayhead(0)

    fun seekToEnd() {
        val endMs = if (rustStore.snapshot.value != null) rustStore.durationUs().usToMs else 0L
        setPlayhead(endMs)
    }

    fun zoomIn() {
        setZoom(_state.value.zoomLevel * 1.25f)
    }

    fun zoomOut() {
        setZoom(_state.value.zoomLevel / 1.25f)
    }

    fun addTextOverlay() {
        val textTrackId = ensureTrack(RustTrackKind.Text, "Text 1")
        val playheadUs = _state.value.playheadMs.msToUs
        val clipId = randomUuid()

        rustStore.dispatch(
            rustStore.commands.addClip(
                textTrackId,
                RustClipSnapshot(
                    id = clipId,
                    timelineStartUs = playheadUs,
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
        )
        syncFromRust(
            projectPath = _state.value.projectPath,
            selectedTrackId = textTrackId,
            selectedClipId = clipId,
        )
    }

    fun deleteSelectedClip() {
        val clipId = _state.value.selectedClipId ?: return
        removeClip(clipId)
        selectClip(null)
    }

    fun setPlaying(playing: Boolean) {
        _state.update { it.copy(isPlaying = playing) }
    }

    fun setPlayhead(ms: Long) {
        val safe = ms.coerceAtLeast(0)
        rustStore.setPlayheadUs(safe.msToUs)
        _state.update { it.copy(playheadMs = safe) }
    }

    fun importMediaFiles(files: List<PlatformFile>) {
        viewModelScope.launch {
            val initialSnapshot = rustStore.snapshot.value ?: return@launch
            _state.update { it.copy(isLoading = true) }

            try {
                val selected = initialSnapshot.timeline.tracks
                    .flatMap { track -> track.clips.map { track.id to it } }
                    .firstOrNull { (_, clip) -> clip.id == _state.value.selectedClipId }

                val initialCursorMs = selected?.second
                    ?.let { (it.timelineStartUs + it.timelineDurationUs) / 1000L }
                    ?: _state.value.playheadMs

                data class ImportItem(
                    val file: PlatformFile,
                    val stagedPath: String,
                    val info: VideoInfo,
                )

                val items = files.map { file ->
                    val stagedPath = PlatformFileSystem.stageForNativeAccess(file.platformPath())
                    ImportItem(file, stagedPath, engine.probeVideo(stagedPath))
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
                )

                fun resolveTrack(
                    kind: RustTrackKind,
                    cursorUs: Long,
                    durationUs: Long,
                    currentTrackId: String?,
                    labelPrefix: String,
                ): ImportTarget {
                    val startUs = cursorUs
                    val endUs = startUs + durationUs

                    var trackId = currentTrackId
                    if (trackId == null) {
                        trackId = ensureTrack(kind, "$labelPrefix 1")
                    }

                    val snap = rustStore.snapshot.value ?: return ImportTarget(trackId, cursorUs)
                    val currentTrack = snap.timeline.tracks
                        .firstOrNull { it.id == trackId && it.kind == kind }

                    val hasConflict = currentTrack?.clips?.any { clip ->
                        clip.overlapsRange(startUs, endUs)
                    } ?: false

                    val resolvedId = if (!hasConflict) {
                        trackId
                    } else {
                        val alternate = snap.timeline.tracks
                            .filter { it.kind == kind && it.id != trackId }
                            .firstOrNull { track ->
                                track.clips.none { clip -> clip.overlapsRange(startUs, endUs) }
                            }

                        if (alternate != null) {
                            alternate.id
                        } else {
                            val count = snap.timeline.tracks.count { it.kind == kind }
                            val label = "$labelPrefix ${count + 1}"
                            rustStore.dispatch(rustStore.commands.addTrack(kind, label))
                            rustStore.snapshot.value
                                ?.timeline?.tracks?.last { it.kind == kind }?.id
                                ?: trackId
                        }
                    }

                    return ImportTarget(resolvedId, cursorUs + durationUs)
                }

                for (item in items) {
                    val info = item.info
                    val hasVideo = info.hasVideo
                    val hasAudio = info.hasAudio
                    val durationUs = info.durationMs.msToUs

                    if (hasVideo) {
                        val target = resolveTrack(RustTrackKind.Video, cursorVideoUs, durationUs, videoTrackId, "Video")
                        videoTrackId = target.trackId

                        val videoVolume = if (hasAudio) 0.0f else 1.0f
                        dispatchSilent(
                            rustStore.commands.addClip(
                                target.trackId,
                                RustClipSnapshot(
                                    id = randomUuid(),
                                    timelineStartUs = cursorVideoUs,
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
                    }

                    if (hasAudio) {
                        val target = resolveTrack(RustTrackKind.Audio, cursorAudioUs, durationUs, audioTrackId, "Audio")
                        audioTrackId = target.trackId

                        dispatchSilent(
                            rustStore.commands.addClip(
                                target.trackId,
                                RustClipSnapshot(
                                    id = randomUuid(),
                                    timelineStartUs = cursorAudioUs,
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
                    }
                }

                syncFromRust()
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Imported ${items.size} file(s)")
            } catch (e: Exception) {
                handleError("Failed to import: ${e.message}")
            }
        }
    }

    fun importSubtitleFiles(files: List<PlatformFile>) {
        viewModelScope.launch {
            val initialSnapshot = rustStore.snapshot.value ?: return@launch
            _state.update { it.copy(isLoading = true) }

            try {
                var subtitleTrackId = initialSnapshot.timeline.tracks
                    .firstOrNull { it.kind == RustTrackKind.Subtitle }?.id

                if (subtitleTrackId == null) {
                    subtitleTrackId = ensureTrack(RustTrackKind.Subtitle, "Subtitles")
                }

                val cursorMs = _state.value.playheadMs

                for (file in files) {
                    val stagedPath = PlatformFileSystem.stageForNativeAccess(file.platformPath())
                    val durationMs = DEFAULT_SUBTITLE_DURATION_MS
                    val startUs = cursorMs.msToUs
                    val durationUs = durationMs.msToUs

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
                }

                syncFromRust()
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Imported ${files.size} subtitle file(s)")
            } catch (e: Exception) {
                handleError("Failed to import subtitles: ${e.message}")
            }
        }
    }

    private fun loadThumbnails(videoPath: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingThumbnails = true) }
            try {
                val thumbs = engine.extractThumbnails(videoPath, 12, 160, 90)
                _state.update { it.copy(thumbnails = thumbs, isLoadingThumbnails = false) }
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingThumbnails = false) }
            }
        }
    }

    fun exportProject(outputPath: String) {
        val snapshot = rustStore.snapshot.value ?: return
        viewModelScope.launch {
            var exportError: String? = null
            for (path in snapshot.timeline.tracks.flatMap { it.clips }
                .mapNotNull { clipSnapshot -> (clipSnapshot.kind as? RustVideoClipKind)?.sourcePath }.distinct()) {
                try {
                    val info = engine.probeVideo(path)
                    if (!info.hasVideo) {
                        exportError = "'${path.substringAfterLast("/")}' has no video stream"
                        break
                    }
                    when (val result = engine.extractSingleThumbnail(path, 0L, 160, 90)) {
                        is ThumbnailResult.Error -> {
                            exportError = "'${path.substringAfterLast("/")}': ${result.message}"
                            break
                        }
                        is ThumbnailResult.Success -> { /* decodable */ }
                    }
                } catch (e: Exception) {
                    exportError = "'${path.substringAfterLast("/")}': ${e.message}"
                    break
                }
            }

            if (exportError != null) {
                snackbarManager.showError("Cannot export: $exportError")
                return@launch
            }

            val projectJson = rustStore.exportProjectJson()
            engine.exportProjectJson(projectJson, outputPath)
        }
    }

    fun cancelExport() = engine.cancelExport()

    fun resetExport() = engine.reset()

    fun updateExportProfile(profile: RustExportProfileSnapshot) {
        rustStore.dispatch(
            rustStore.commands.setExportProfile(profile),
        )
        syncFromRust()
    }

    fun startExport(outputPath: String) {
        exportProject(outputPath)
    }

    fun reset() {
        stopAutoSave()
        rustStore.clear()
        _state.update { ProjectUiState() }
        engine.reset()
    }

    fun addTrack(kind: RustTrackKind, label: String? = null) {
        val count = rustStore.snapshot.value?.timeline?.tracks?.count { it.kind == kind } ?: 0
        rustStore.dispatch(
            rustStore.commands.addTrack(kind, label ?: "${kind.name} ${count + 1}"),
        )
        syncFromRust()
    }

    fun removeTrack(trackId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.find { it.id == trackId } ?: return
        if (track.kind == RustTrackKind.Video && snapshot.timeline.tracks.count { it.kind == RustTrackKind.Video } <= 1) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        rustStore.dispatch(rustStore.commands.removeTrack(trackId))
        syncFromRust()
    }

    fun moveClipAbsolute(clipId: String, absoluteStartMs: Long) {
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

        rustStore.dispatch(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = track.id,
                newStartUs = clampedStartUs,
            ),
        )
        syncFromRust()
        if (preDragSnapshot != null) continuousEditCommandCount++
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
            clipId = null,
            requestedStartUs = clip.timelineStartUs,
            durationUs = clip.timelineDurationUs.coerceAtLeast(1L),
        )

        rustStore.dispatch(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = toTrackId,
                newStartUs = targetStartUs,
            ),
        )
        syncFromRust()
        if (preDragSnapshot != null) continuousEditCommandCount++
    }

    fun trimClipStartAbsolute(clipId: String, newStartMs: Long) {
        val clip = findRustClip(clipId) ?: return
        val requestedStartUs = newStartMs.coerceAtLeast(0).msToUs
        val maxStartUs = (clip.timelineStartUs + clip.timelineDurationUs - MIN_TRIM_DURATION_US).coerceAtLeast(0L)
        val newStartUs = requestedStartUs.coerceIn(0L, maxStartUs)
        val deltaTimelineUs = newStartUs - clip.timelineStartUs
        val newSourceStartUs = (clip.sourceStartUs + (deltaTimelineUs * clip.speed).toLong()).coerceAtLeast(0L)

        rustStore.dispatch(
            rustStore.commands.trimClipStart(
                clipId = clipId,
                newStartUs = newStartUs,
                newSourceStartUs = newSourceStartUs,
            ),
        )
        syncFromRust()
        if (preDragSnapshot != null) continuousEditCommandCount++
    }

    fun trimClipEndAbsolute(clipId: String, newEndMs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return

        val requestedDurationUs = (newEndMs.msToUs - clip.timelineStartUs).coerceAtLeast(MIN_TRIM_DURATION_US)
        val maxBySourceUs =
            if (clip.kind is RustTextClipKind || clip.kind is RustSubtitleClipKind) {
                Long.MAX_VALUE
            } else {
                (((clip.sourceTotalDurationUs - clip.sourceStartUs).coerceAtLeast(MIN_TRIM_DURATION_US).toDouble() / clip.speed)
                    .toLong()).coerceAtLeast(MIN_TRIM_DURATION_US)
            }
        val maxByNeighborUs =
            nextClipStartUs(track, clipId)?.let { (it - clip.timelineStartUs).coerceAtLeast(MIN_TRIM_DURATION_US) }
        val maxDurationUs = listOfNotNull(maxByNeighborUs, maxBySourceUs).minOrNull() ?: maxBySourceUs
        val newDurationUs = requestedDurationUs.coerceIn(MIN_TRIM_DURATION_US, maxDurationUs)

        rustStore.dispatch(
            rustStore.commands.trimClipEnd(
                clipId = clipId,
                newDurationUs = newDurationUs,
            ),
        )
        syncFromRust()
        if (preDragSnapshot != null) continuousEditCommandCount++
    }

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = DEFAULT_TEXT_CLIP_DURATION_US / 1000L) {
        rustStore.dispatch(
            rustStore.commands.addClip(
                trackId,
                RustClipSnapshot(
                    id = randomUuid(),
                    timelineStartUs = startMs.msToUs,
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
        syncFromRust()
    }

    fun removeClip(clipId: String) {
        rustStore.dispatch(rustStore.commands.removeClip(clipId))
        syncFromRust()
    }

    fun duplicateClip(clipId: String) {
        val track = rustStore.snapshot.value
            ?.timeline
            ?.tracks
            ?.firstOrNull { t -> t.clips.any { it.id == clipId } }
            ?: return

        val endUs = track.clips.maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L

        rustStore.dispatch(
            rustStore.commands.duplicateClip(
                sourceClipId = clipId,
                newClipId = randomUuid(),
                targetTrackId = track.id,
                targetStartUs = endUs,
            ),
        )
        syncFromRust()
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

    fun setClipSpeed(clipId: String, speed: Float) {
        rustStore.dispatch(
            rustStore.commands.setClipSpeed(clipId, speed.toDouble()),
        )
        syncFromRust()
    }

    fun setClipVolume(clipId: String, volume: Float) {
        rustStore.dispatch(
            rustStore.commands.setClipVolume(clipId, volume),
        )
        syncFromRust()
    }

    fun setClipOpacity(clipId: String, opacity: Float) {
        rustStore.dispatch(
            rustStore.commands.setClipOpacity(clipId, opacity),
        )
        syncFromRust()
    }

    fun addAudioFilter(clipId: String, filter: RustAudioFilterSnapshot) {
        rustStore.dispatch(
            rustStore.commands.addAudioFilter(clipId = clipId, filter = filter),
        )
        syncFromRust()
    }

    fun removeAudioFilter(clipId: String, filterIndex: Int) {
        rustStore.dispatch(
            rustStore.commands.removeAudioFilter(clipId, filterIndex),
        )
        syncFromRust()
    }

    fun updateAudioFilterDuration(clipId: String, filterIndex: Int, durationUs: Long) {
        rustStore.dispatch(
            rustStore.commands.updateAudioFilterDuration(clipId, filterIndex, durationUs),
        )
        syncFromRust()
    }

    fun updateTextClip(clipId: String, newText: String) {
        rustStore.dispatch(
            rustStore.commands.updateTextContent(clipId, newText),
        )
        syncFromRust()
    }

    fun updateTextClipStyle(
        clipId: String,
        fontSizeSp: Float? = null,
        colorHex: String? = null,
        backgroundColorHex: String? = null,
        positionX: Float? = null,
        positionY: Float? = null,
        isBold: Boolean? = null,
        isItalic: Boolean? = null,
    ) {
        val clip = findRustClip(clipId)?.kind as? RustTextClipKind ?: return
        val style = clip.style.copy(
            fontSize = fontSizeSp ?: clip.style.fontSize,
            color = colorHex?.toArgbHex() ?: clip.style.color,
            backgroundColor = backgroundColorHex?.toArgbHex() ?: clip.style.backgroundColor,
            positionX = positionX ?: clip.style.positionX,
            positionY = positionY ?: clip.style.positionY,
            bold = isBold ?: clip.style.bold,
            italic = isItalic ?: clip.style.italic,
        )

        rustStore.dispatch(
            rustStore.commands.updateTextStyle(clipId, style),
        )
        syncFromRust()
    }

    fun setTextTransitionIn(clipId: String, transition: RustTransitionSnapshot?) {
        rustStore.dispatch(
            rustStore.commands.setTransitionIn(clipId, transition),
        )
        syncFromRust()
    }

    fun setTextTransitionOut(clipId: String, transition: RustTransitionSnapshot?) {
        rustStore.dispatch(
            rustStore.commands.setTransitionOut(clipId, transition),
        )
        syncFromRust()
    }

    fun updateFilterParams(clipId: String, filterIndex: Int, newParams: Map<String, Float>) {
        val video = findRustClip(clipId)?.kind as? RustVideoClipKind ?: return
        val current = video.filters.getOrNull(filterIndex) ?: return
        val updatedFilter = when (val filter = current.filter) {
            is RustBrightnessFilterSnapshot -> RustBrightnessFilterSnapshot(newParams["value"] ?: filter.value)
            is RustContrastFilterSnapshot -> RustContrastFilterSnapshot(newParams["value"] ?: filter.value)
            is RustSaturationFilterSnapshot -> RustSaturationFilterSnapshot(newParams["value"] ?: filter.value)
            is RustBlurFilterSnapshot -> RustBlurFilterSnapshot(newParams["radius"] ?: filter.radius)
            is RustSharpenFilterSnapshot -> RustSharpenFilterSnapshot(newParams["amount"] ?: filter.amount)
            is RustTransformFilterSnapshot -> RustTransformFilterSnapshot(
                scale = newParams["scale"] ?: filter.scale,
                translateX = newParams["translate_x"] ?: filter.translateX,
                translateY = newParams["translate_y"] ?: filter.translateY,
                rotate = newParams["rotate"] ?: filter.rotate,
            )
            else -> filter
        }

        rustStore.dispatch(
            rustStore.commands.updateVideoFilter(
                clipId = clipId,
                index = filterIndex,
                filter = current.copy(filter = updatedFilter),
            ),
        )
        syncFromRust()
    }

    fun addFilter(clipId: String, filter: RustVideoEffectSnapshot) {
        rustStore.dispatch(
            rustStore.commands.addVideoFilter(
                clipId = clipId,
                filter = filter,
            ),
        )
        syncFromRust()
    }

    fun removeFilter(clipId: String, filterIndex: Int) {
        rustStore.dispatch(
            rustStore.commands.removeVideoFilter(clipId, filterIndex),
        )
        syncFromRust()
    }

    fun setFilterEnabled(clipId: String, filterIndex: Int, enabled: Boolean) {
        rustStore.dispatch(
            rustStore.commands.setVideoFilterEnabled(clipId, filterIndex, enabled),
        )
        syncFromRust()
    }

    fun moveFilter(clipId: String, fromIndex: Int, toIndex: Int) {
        rustStore.dispatch(
            rustStore.commands.moveVideoFilter(clipId, fromIndex, toIndex),
        )
        syncFromRust()
    }

    fun setTransitionIn(clipId: String, transition: RustTransitionSnapshot?) {
        rustStore.dispatch(
            rustStore.commands.setTransitionIn(clipId, transition),
        )
        syncFromRust()
    }

    fun setTransitionOut(clipId: String, transition: RustTransitionSnapshot?) {
        rustStore.dispatch(
            rustStore.commands.setTransitionOut(clipId, transition),
        )
        syncFromRust()
    }

    fun addKeyframe(clipId: String, keyframe: RustKeyframe) {
        rustStore.dispatch(
            rustStore.commands.addKeyframe(clipId, keyframe),
        )
        syncFromRust()
    }

    fun removeKeyframe(clipId: String, index: Int) {
        rustStore.dispatch(
            rustStore.commands.removeKeyframe(clipId, index),
        )
        syncFromRust()
    }

    fun updateKeyframe(clipId: String, index: Int, keyframe: RustKeyframe) {
        rustStore.dispatch(
            rustStore.commands.updateKeyframe(clipId, index, keyframe),
        )
        syncFromRust()
    }

    fun toggleTrackMute(trackId: String) {
        val muted = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.muted ?: return
        rustStore.dispatch(
            rustStore.commands.setTrackMuted(trackId, !muted),
        )
        syncFromRust()
    }

    fun toggleTrackLock(trackId: String) {
        val locked = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.locked ?: return
        rustStore.dispatch(
            rustStore.commands.setTrackLocked(trackId, !locked),
        )
        syncFromRust()
    }

    fun selectClip(clipId: String?) {
        _state.update { it.copy(selectedClipId = clipId) }
    }

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoomLevel = zoom.coerceIn(0.1f, 10f)) }
    }

    fun snapPosition(ms: Long, excludeClipId: String? = null): Long {
        val nearest = computeSnapPosition(ms, excludeClipId)
        _state.update { it.copy(snapIndicatorMs = if (nearest != ms) nearest else null) }
        return nearest
    }

    private fun computeSnapPosition(ms: Long, excludeClipId: String? = null): Long {
        val snapThresholdMs = (SNAP_THRESHOLD_BASE / _state.value.zoomLevel).toLong().coerceAtLeast(50)
        val snapshot = rustStore.snapshot.value ?: return ms
        val playhead = _state.value.playheadMs

        var nearest = ms
        var minDist = snapThresholdMs

        val dph = kotlin.math.abs(ms - playhead)
        if (dph < minDist) {
            nearest = playhead
            minDist = dph
        }

        for (clip in snapshot.timeline.tracks.flatMap { it.clips }) {
            if (clip.id == excludeClipId) continue
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

        if (ms < snapThresholdMs) nearest = 0

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

        val reqStart = requestedStartUs.coerceAtLeast(0L)
        val reqEnd = reqStart + durationUs

        for (clip in clips) {
            val start = clip.timelineStartUs
            val end = clip.timelineStartUs + clip.timelineDurationUs
            if (reqStart < end && start < reqEnd) {
                val leftEnd = start - durationUs
                val rightStart = end

                val leftValid = leftEnd >= 0L && fitsWithoutOverlap(clips, leftEnd, durationUs)
                val rightValid = fitsWithoutOverlap(clips, rightStart, durationUs)

                return when {
                    leftValid && rightValid -> {
                        if (kotlin.math.abs(reqStart - leftEnd) <= kotlin.math.abs(rightStart - reqStart)) {
                            leftEnd
                        } else {
                            rightStart
                        }
                    }

                    leftValid -> leftEnd
                    rightValid -> rightStart
                    else -> reqStart
                }
            }
        }

        return reqStart
    }

    private fun fitsWithoutOverlap(clips: List<RustClipSnapshot>, startUs: Long, durationUs: Long): Boolean {
        val endUs = startUs + durationUs
        return clips.none { existing -> existing.overlapsRange(startUs, endUs) }
    }

    private fun nextClipStartUs(track: RustTrackSnapshot, clipId: String): Long? {
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return null
        val currentStart = clip.timelineStartUs
        return track.clips
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

    /** skips ui state sync (should call syncFromRust too) */
    private fun dispatchSilent(commandJson: String) {
        rustStore.dispatch(commandJson)
    }

    private fun syncFromRust(
        projectPath: String? = _state.value.projectPath,
        selectedTrackId: String? = _state.value.selectedTrackId,
        selectedClipId: String? = _state.value.selectedClipId,
        playheadMs: Long = _state.value.playheadMs,
        isDirty: Boolean = true,
    ) {
        val snapshot = rustStore.snapshot.value
        _state.update {
            it.copy(
                snapshot = snapshot,
                projectPath = projectPath,
                selectedTrackId = selectedTrackId,
                selectedClipId = selectedClipId,
                playheadMs = playheadMs,
                isLoading = false,
                isDirty = isDirty,
                canUndo = rustStore.canUndo(),
                canRedo = rustStore.canRedo(),
            )
        }
    }

    private fun handleError(message: String) {
        _state.update { it.copy(isLoading = false) }
        snackbarManager.showError(message)
    }
}
