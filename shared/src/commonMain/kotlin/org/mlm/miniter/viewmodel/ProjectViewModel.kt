package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aakira.napier.Napier
import io.github.mlmgames.settings.core.SettingsRepository
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
import kotlinx.coroutines.sync.Mutex
import org.mlm.miniter.editor.RustDispatchException
import org.mlm.miniter.editor.RustProjectStore
import org.mlm.miniter.nav.MAX_EXTRA_IMPORT_PATHS
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
import org.mlm.miniter.editor.model.RustMaskEffect
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
import org.mlm.miniter.settings.AppSettings
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.msToUs
import org.mlm.miniter.platform.usToMs
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.RecentProjectsRepository
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import org.mlm.miniter.ui.util.toArgbHex
import kotlin.time.Clock

data class ImportProgress(
    val current: Int = 0,
    val total: Int = 0,
    val currentFile: String? = null,
    val stage: String = "Importing",
    val fraction: Float? = null,
)

data class ProjectUiState(
    val snapshot: RustProjectSnapshot? = null,
    val projectPath: String? = null,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val importProgress: ImportProgress? = null,
    val isDirty: Boolean = false,
    val zoomLevel: Float = 1f,
    val thumbnails: List<ImageData> = emptyList(),
    val isLoadingThumbnails: Boolean = false,
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

    private var hardwareAccelerationEnabled: Boolean = true
    private var sourceDurationMs: Long = 0L
    private var autoSaveJob: Job? = null
    private var autoSaveNoPathWarned: Boolean = false
    private var preDragSnapshot: RustProjectSnapshot? = null
    private var continuousEditCommandCount = 0
    private val saveMutex = Mutex()
    private var thumbnailJob: Job? = null
    private var thumbnailRequestId: Long = 0L
    private var thumbnailRequestPath: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.flow.collect { settings ->
                hardwareAccelerationEnabled = settings.hardwareAccelerationEnabled
            }
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

                if (hasVideo) {
                    when (val result = engine.extractSingleThumbnail(stagedVideoPath, 0L, 160, 90, hardwareAccelerationEnabled)) {
                        is ThumbnailResult.Error -> {
                            handleError(result.message)
                            return@launch
                        }
                        is ThumbnailResult.Success -> { /* decodable */ }
                    }
                }

                sourceDurationMs = info.durationMs

                rustStore.create(name)
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
                    PlatformFileSystem.writeText(savePath, projectJson)
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
        val snapshot = rustStore.snapshot.value ?: return
        try {
            rustStore.dispatch(rustStore.commands.renameProject(newName))
            syncFromRust(isDirty = true)
        } catch (e: RustDispatchException) {
            Napier.w("RenameProject command rejected, falling back to replaceSnapshot (clears undo): ${e.message}")
            try {
                rustStore.replaceSnapshot(
                    snapshot.copy(
                        meta = snapshot.meta.copy(
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
        autoSaveNoPathWarned = false
        if (!enabled) return
        if (!intervalSeconds.isFinite() || intervalSeconds < 1f || intervalSeconds > 3600f) return
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay((intervalSeconds * 1000).toLong())
                val s = _state.value
                if (s.isDirty && !s.isSaving) {
                    if (s.projectPath != null) {
                        autoSaveNoPathWarned = false
                        saveProject()
                    } else if (!autoSaveNoPathWarned) {
                        autoSaveNoPathWarned = true
                        snackbarManager.show("Auto-save unavailable - save project manually first")
                    }
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
            val result = try {
                rustStore.cancelEdit()
            } catch (e: Exception) {
                handleError("Failed to cancel edit: ${e.message}")
                null
            }
            if (result == null) {
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
    ) {
        val capped = extraImportPaths.take(MAX_EXTRA_IMPORT_PATHS)
        when {
            openAsProject -> loadProject(videoPath)
            videoPath.isEmpty() -> createBlankProject(projectName, savePath, resolution ?: RustExportResolution.Hd1080, fps ?: 30, capped)
            else -> newProject(projectName, videoPath, savePath, capped)
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

    fun seekTo(ms: Long) = setPlayhead(ms)

    fun seekRelative(deltaMs: Long) {
        val current = _state.value.playheadMs
        val maxMs = if (rustStore.snapshot.value != null) {
            try {
                rustStore.durationUs().usToMs
            } catch (_: Exception) {
                0L
            }
        } else {
            Long.MAX_VALUE / 1000L
        }
        val target = when {
            deltaMs > 0 && current > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE / 1000L
            deltaMs < 0 && current < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
            else -> current + deltaMs
        }.coerceIn(0L, maxMs.coerceAtLeast(0L))
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
            val playheadUs = _state.value.playheadMs.msToUs
            val clipId = randomUuid()

            dispatchAndSync(
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
                selectedTrackId = textTrackId,
                selectedClipId = clipId,
            )
        } catch (e: Exception) {
            handleError("Failed to add text: ${e.message}")
        }
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
            importMediaPathsInternal(paths)
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
                val items = paths.mapIndexed { index, path ->
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
                    ImportItem(path, stagedPath, engine.probeVideo(stagedPath))
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

                var added = 0
                for (item in items) {
                    val info = item.info
                    val hasVideo = info.hasVideo
                    val hasAudio = info.hasAudio
                    val durationUs = info.durationMs.msToUs

                    if (durationUs <= 0L) {
                        snackbarManager.show("Warning: Skipping '${item.path.substringAfterLast("/").substringAfterLast("\\")}' (zero duration)")
                        continue
                    }

                    val baseUs = maxOf(cursorVideoUs, cursorAudioUs)
                    var fileAdded = false

                    if (hasVideo) {
                        val target = resolveTrack(RustTrackKind.Video, baseUs, durationUs, videoTrackId, "Video")
                        videoTrackId = target.trackId

                        val videoVolume = if (hasAudio) 0.0f else 1.0f
                        dispatchSilent(
                            rustStore.commands.addClip(
                                target.trackId,
                                RustClipSnapshot(
                                    id = randomUuid(),
                                    timelineStartUs = baseUs,
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
                        cursorVideoUs = baseUs + durationUs
                        fileAdded = true
                    }

                    if (hasAudio) {
                        val target = resolveTrack(RustTrackKind.Audio, baseUs, durationUs, audioTrackId, "Audio")
                        audioTrackId = target.trackId

                        dispatchSilent(
                            rustStore.commands.addClip(
                                target.trackId,
                                RustClipSnapshot(
                                    id = randomUuid(),
                                    timelineStartUs = baseUs,
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
                        cursorAudioUs = baseUs + durationUs
                        fileAdded = true
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
                handleError("Failed to import: ${e.message}")
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
                    val stagedPath = PlatformFileSystem.stageForNativeAccess(file.platformPath())
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
                handleError("Failed to import subtitles: ${e.message}")
            }
        }
    }

    private fun loadThumbnails(videoPath: String) {
        thumbnailJob?.cancel()
        thumbnailRequestId += 1
        val requestId = thumbnailRequestId
        thumbnailRequestPath = videoPath
        thumbnailJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingThumbnails = true) }
            try {
                val thumbs = engine.extractThumbnails(videoPath, 12, 160, 90, hardwareAccelerationEnabled)
                if (requestId != thumbnailRequestId) return@launch
                if (thumbnailRequestPath != videoPath) return@launch
                _state.update { it.copy(thumbnails = thumbs, isLoadingThumbnails = false) }
            } catch (e: Exception) {
                if (requestId != thumbnailRequestId) return@launch
                _state.update { it.copy(isLoadingThumbnails = false) }
                val msg = e.message?.removePrefix("detail=")?.removePrefix("Non-Kotlin exception ")
                    ?: "Failed to load thumbnails"
                snackbarManager.showError(msg)
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
                    if (!info.videoDecoderAvailable) {
                        exportError = "'${path.substringAfterLast("/")}': no decoder available for ${info.videoCodecName ?: "unknown codec"}"
                        break
                    }
                    if (info.hardwareAccelerationRequired && !hardwareAccelerationEnabled) {
                        exportError = "'${path.substringAfterLast("/")}': ${info.videoCodecName ?: "unknown codec"} requires hardware acceleration (currently disabled)"
                        break
                    }
                    when (val result = engine.extractSingleThumbnail(path, 0L, 160, 90, hardwareAccelerationEnabled)) {
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

            try {
                val projectJson = rustStore.exportProjectJson()
                engine.exportProjectJson(projectJson, outputPath)
            } catch (e: Exception) {
                try {
                    engine.reset()
                } catch (_: Exception) { /* best effort progress reset */ }
                snackbarManager.showError("Export failed: ${e.message}")
            }
        }
    }

    fun cancelExport() = engine.cancelExport()

    fun resetExport() = engine.reset()

    fun updateExportProfile(profile: RustExportProfileSnapshot) {
        dispatchAndSync(rustStore.commands.setExportProfile(profile))
    }

    fun startExport(outputPath: String) {
        exportProject(outputPath)
    }

    fun reset() {
        stopAutoSave()
        thumbnailJob?.cancel()
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
        if (track.kind == RustTrackKind.Video && snapshot.timeline.tracks.count { it.kind == RustTrackKind.Video } <= 1) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        dispatchAndSync(rustStore.commands.removeTrack(trackId))
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

        dispatchCoalescing(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = track.id,
                newStartUs = clampedStartUs,
            ),
            "Move",
        )
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

    fun trimClipStartAbsolute(clipId: String, newStartMs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return
        val requestedStartUs = newStartMs.coerceAtLeast(0).msToUs
        val maxStartUs = clip.timelineStartUs + clip.timelineDurationUs - MIN_TRIM_DURATION_US
        if (maxStartUs <= 0L) {
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
        val newSourceStartUs = (clip.sourceStartUs + (deltaTimelineUs * clip.speed).toLong()).coerceAtLeast(0L)

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

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = DEFAULT_TEXT_CLIP_DURATION_US / 1000L) {
        dispatchAndSync(
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
    }

    fun removeClip(clipId: String) {
        dispatchAndSync(rustStore.commands.removeClip(clipId))
    }

    fun duplicateClip(clipId: String) {
        val track = rustStore.snapshot.value
            ?.timeline
            ?.tracks
            ?.firstOrNull { t -> t.clips.any { it.id == clipId } }
            ?: return

        val endUs = track.clips.maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L

        dispatchAndSync(
            rustStore.commands.duplicateClip(
                sourceClipId = clipId,
                newClipId = randomUuid(),
                targetTrackId = track.id,
                targetStartUs = endUs,
            ),
        )
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
        if (!speed.isFinite() || speed < 0.1f || speed > 8.0f) {
            snackbarManager.showError("Speed must be between 0.1x and 8.0x")
            return
        }
        dispatchCoalescing(
            rustStore.commands.setClipSpeed(clipId, speed.toDouble()),
            "Speed",
        )
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

    fun updateTextClip(clipId: String, newText: String) {
        dispatchCoalescing(
            rustStore.commands.updateTextContent(clipId, newText),
            "Text",
        )
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
        fontFamily: String? = null,
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
            fontFamily = fontFamily ?: clip.style.fontFamily,
        )

        dispatchCoalescing(
            rustStore.commands.updateTextStyle(clipId, style),
            "Style",
        )
    }

    fun setSubtitleFont(clipId: String, fontPath: String?) {
        findRustClip(clipId)?.kind as? RustSubtitleClipKind ?: return
        dispatchAndSync(rustStore.commands.setSubtitleFont(clipId, fontPath))
    }

    fun addMask(clipId: String, mask: RustMaskEffect) {
        dispatchAndSync(rustStore.commands.addMask(clipId, mask))
    }

    fun removeMask(clipId: String, index: Int) {
        dispatchAndSync(rustStore.commands.removeMask(clipId, index))
    }

    fun updateMask(clipId: String, index: Int, mask: RustMaskEffect) {
        dispatchCoalescing(
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

        dispatchCoalescing(
            rustStore.commands.updateVideoFilter(
                clipId = clipId,
                index = filterIndex,
                filter = current.copy(filter = updatedFilter),
            ),
            "Filter",
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
        val endUs = startUs + durationUs
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
        val hasVideoClips = snapshot?.timeline?.tracks
            ?.flatMap { it.clips }
            ?.any { it.kind is RustVideoClipKind } == true
        if (!hasVideoClips) {
            sourceDurationMs = 0L
        }
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
                undoLabel = rustStore.undoLabel(),
                redoLabel = rustStore.redoLabel(),
                thumbnails = if (hasVideoClips) it.thumbnails else emptyList(),
            )
        }
    }

    private fun handleError(message: String) {
        _state.update { it.copy(isLoading = false, importProgress = null) }
        snackbarManager.showError(message)
    }
}
