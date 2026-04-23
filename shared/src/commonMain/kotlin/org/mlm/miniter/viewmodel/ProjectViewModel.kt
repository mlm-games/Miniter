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
import org.mlm.miniter.editor.model.RustExportFormat
import org.mlm.miniter.editor.model.RustExportProfileSnapshot
import org.mlm.miniter.editor.model.RustExportResolution
import org.mlm.miniter.editor.model.RustFadeInAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFadeOutAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
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
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.engine.VideoInfo
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.RecentProjectsRepository
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
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

    private var sourceDurationMs: Long = 0L
    private var autoSaveJob: Job? = null
    private var preDragSnapshot: RustProjectSnapshot? = null

    fun newProject(name: String, initialVideoPath: String, savePath: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val stagedVideoPath = PlatformFileSystem.stageForNativeAccess(initialVideoPath)
                val info = engine.probeVideo(stagedVideoPath)
                if (!info.hasVideo) {
                    _state.update { it.copy(isLoading = false) }
                    snackbarManager.showError("Selected file has no video stream")
                    return@launch
                }
                try {
                    val decodable = engine.extractSingleThumbnail(stagedVideoPath, 0L, 160, 90) != null
                    if (!decodable) {
                        _state.update { it.copy(isLoading = false) }
                        snackbarManager.showError("Selected video cannot be decoded. Try H.264 MP4 or MOV.")
                        return@launch
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(isLoading = false) }
                    snackbarManager.showError("Decode error: ${e.message}")
                    return@launch
                }
                sourceDurationMs = info.durationMs

                rustStore.create(name)
                val videoTrackId = ensureTrack(RustTrackKind.Video, "Video 1")

                val hasAudio = info.hasAudio
                val audioTrackId = if (hasAudio) ensureTrack(RustTrackKind.Audio, "Audio 1") else null

                val durationUs = info.durationMs * 1000L

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
                                    sampleRate = info.audioSampleRate.coerceAtLeast(44_100),
                                    channels = info.audioChannels.coerceAtLeast(1),
                                    filters = emptyList(),
                                ),
                            ),
                        )
                    )
                }

                dispatchAndSync(
                    rustStore.commands.batch("NewProjectInitialClips", commands),
                    projectPath = savePath,
                    selectedTrackId = videoTrackId,
                    isDirty = true,
                )

                loadThumbnails(stagedVideoPath)
                if (savePath != null) {
                    recentProjectsRepository.addRecent(savePath, name)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to open video: ${e.message}")
            }
        }
    }

    fun loadProject(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val projectJson = PlatformFileSystem.readText(path)
                if (projectJson.isBlank()) {
                    _state.update { it.copy(isLoading = false) }
                    snackbarManager.showError("Selected project file is empty")
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
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to load project: ${e.message}")
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
        syncFromRust(isDirty = true)
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
                    else snackbarManager.show("Auto-save unavailable — save project manually first")
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
        syncFromRust(isDirty = true)
    }

    fun redo() {
        rustStore.redo() ?: return
        syncFromRust(isDirty = true)
    }

    fun beginContinuousEdit() {
        preDragSnapshot = rustStore.snapshot.value
    }

    fun commitContinuousEdit() {
        preDragSnapshot = null
        syncFromRust(isDirty = true)
    }

    fun cancelContinuousEdit() {
        val snapshot = preDragSnapshot ?: return
        rustStore.replaceSnapshot(snapshot)
        preDragSnapshot = null
        syncFromRust(isDirty = true)
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
        val max = if (rustStore.snapshot.value != null) rustStore.durationUs() / 1000L else Long.MAX_VALUE
        setPlayhead((current + deltaMs).coerceIn(0, max))
    }

    fun seekToStart() = setPlayhead(0)

    fun seekToEnd() {
        val endMs = if (rustStore.snapshot.value != null) rustStore.durationUs() / 1000L else 0L
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
        val playheadUs = _state.value.playheadMs * 1000L
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
            isDirty = true,
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
        rustStore.setPlayheadUs(safe * 1000L)
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

                var cursorVideoUs = initialCursorMs * 1000L
                var cursorAudioUs = initialCursorMs * 1000L

                var videoTrackId: String? =
                    initialSnapshot.timeline.tracks.firstOrNull { it.kind == RustTrackKind.Video }?.id

                var audioTrackId: String? =
                    rustStore.snapshot.value?.timeline?.tracks
                        ?.firstOrNull { it.kind == RustTrackKind.Audio }?.id

                for (item in items) {
                    val info = item.info
                    val hasVideo = info.hasVideo
                    val hasAudio = info.hasAudio
                    val durationUs = info.durationMs * 1000L

                    val snapshotNow = rustStore.snapshot.value ?: initialSnapshot

                    if (hasVideo) {
                        val startUs = cursorVideoUs
                        val endUs = startUs + durationUs

                        if (videoTrackId == null) {
                            videoTrackId = ensureTrack(RustTrackKind.Video, "Video 1")
                        }

                        val currentVideoTrackId = videoTrackId
                        val currentVideoTrack = snapshotNow.timeline.tracks
                            .firstOrNull { it.id == currentVideoTrackId && it.kind == RustTrackKind.Video }

                        val currentConflicts = currentVideoTrack?.clips?.any { clip ->
                            val cs = clip.timelineStartUs
                            val ce = clip.timelineStartUs + clip.timelineDurationUs
                            cs < endUs && ce > startUs
                        } ?: false

                        val targetVideoTrackId = if (!currentConflicts) {
                            currentVideoTrackId
                        } else {
                            val alternate = snapshotNow.timeline.tracks
                                .filter { it.kind == RustTrackKind.Video && it.id != currentVideoTrackId }
                                .firstOrNull { track ->
                                    track.clips.none { clip ->
                                        val cs = clip.timelineStartUs
                                        val ce = clip.timelineStartUs + clip.timelineDurationUs
                                        cs < endUs && ce > startUs
                                    }
                                }

                            if (alternate != null) {
                                alternate.id
                            } else {
                                val videoCount = snapshotNow.timeline.tracks.count { it.kind == RustTrackKind.Video }
                                val label = "Video ${videoCount + 1}"
                                rustStore.dispatch(rustStore.commands.addTrack(RustTrackKind.Video, label))
                                val updated = rustStore.snapshot.value ?: snapshotNow
                                updated.timeline.tracks.last { it.kind == RustTrackKind.Video }.id
                            }
                        }

                        videoTrackId = targetVideoTrackId

                        val videoVolume = if (hasAudio) 0.0f else 1.0f

                        dispatchNoSync(
                            rustStore.commands.addClip(
                                targetVideoTrackId,
                                RustClipSnapshot(
                                    id = randomUuid(),
                                    timelineStartUs = startUs,
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

                        cursorVideoUs += durationUs
                    }

                    if (hasAudio) {
                        val startUs = cursorAudioUs
                        val endUs = startUs + durationUs

                        var currentAudioTrackId = audioTrackId
                        if (currentAudioTrackId == null) {
                            currentAudioTrackId = ensureTrack(RustTrackKind.Audio, "Audio 1")
                            audioTrackId = currentAudioTrackId
                        }

                        val snapshotNow2 = rustStore.snapshot.value ?: snapshotNow

                        val currentAudioTrack = snapshotNow2.timeline.tracks
                            .firstOrNull { it.id == currentAudioTrackId && it.kind == RustTrackKind.Audio }

                        val currentConflicts = currentAudioTrack?.clips?.any { clip ->
                            val cs = clip.timelineStartUs
                            val ce = clip.timelineStartUs + clip.timelineDurationUs
                            cs < endUs && ce > startUs
                        } ?: false

                        val targetAudioTrackId = if (!currentConflicts) {
                            currentAudioTrackId
                        } else {
                            val alternate = snapshotNow2.timeline.tracks
                                .filter { it.kind == RustTrackKind.Audio && it.id != currentAudioTrackId }
                                .firstOrNull { track ->
                                    track.clips.none { clip ->
                                        val cs = clip.timelineStartUs
                                        val ce = clip.timelineStartUs + clip.timelineDurationUs
                                        cs < endUs && ce > startUs
                                    }
                                }

                            if (alternate != null) {
                                alternate.id
                            } else {
                                val audioCount = snapshotNow2.timeline.tracks.count { it.kind == RustTrackKind.Audio }
                                val label = "Audio ${audioCount + 1}"
                                rustStore.dispatch(rustStore.commands.addTrack(RustTrackKind.Audio, label))
                                val updated = rustStore.snapshot.value ?: snapshotNow2
                                updated.timeline.tracks.last { it.kind == RustTrackKind.Audio }.id
                            }
                        }

                        audioTrackId = targetAudioTrackId

                        dispatchNoSync(
                            rustStore.commands.addClip(
                                targetAudioTrackId,
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
kind = RustAudioClipKind(
    sourcePath = item.stagedPath,
                                        sampleRate = info.audioSampleRate.coerceAtLeast(44_100),
                                        channels = info.audioChannels.coerceAtLeast(1),
                                        filters = emptyList(),
                                    ),
                                ),
                            )
                        )

                        cursorAudioUs += durationUs
                    }
                }

                syncFromRust(isDirty = true)
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Imported ${items.size} file(s)")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to import: ${e.message}")
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
                    val durationMs = 60000L
                    val startUs = cursorMs * 1000L
                    val durationUs = durationMs * 1000L

                    dispatchNoSync(
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

                syncFromRust(isDirty = true)
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Imported ${files.size} subtitle file(s)")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to import subtitles: ${e.message}")
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
            for (path in snapshot.timeline.tracks.flatMap { it.clips }.mapNotNull { clipSnapshot -> (clipSnapshot.kind as? RustVideoClipKind)?.sourcePath }.distinct()) {
                try {
                    val info = engine.probeVideo(path)
                    if (!info.hasVideo) {
                        exportError = "'${path.substringAfterLast("/")}' has no video stream"
                        break
                    }
                    if (engine.extractSingleThumbnail(path, 0L, 160, 90) == null) {
                        exportError = "'${path.substringAfterLast("/")}' cannot be decoded"
                        break
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
        dispatchAndSync(
            rustStore.commands.setExportProfile(profile),
            isDirty = true,
        )
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
        dispatchAndSync(
            rustStore.commands.addTrack(kind, label ?: "${kind.name} ${count + 1}"),
            isDirty = true,
        )
    }

    fun removeTrack(trackId: String) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.find { it.id == trackId } ?: return
        if (track.kind == RustTrackKind.Video && snapshot.timeline.tracks.count { it.kind == RustTrackKind.Video } <= 1) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        dispatchAndSync(rustStore.commands.removeTrack(trackId), isDirty = true)
    }

    fun moveClipAbsolute(clipId: String, absoluteStartMs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return

        val requestedStartUs = absoluteStartMs.coerceAtLeast(0) * 1000L
        val durationUs = clip.timelineDurationUs.coerceAtLeast(1L)
        val clampedStartUs = findNearestNonOverlappingStartUs(
            track = track,
            clipId = clipId,
            requestedStartUs = requestedStartUs,
            durationUs = durationUs,
        )

        dispatchAndSync(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = track.id,
                newStartUs = clampedStartUs,
            ),
            isDirty = true,
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

        val targetStartUs = findNearestOpenStartUs(
            track = targetTrack,
            requestedStartUs = clip.timelineStartUs,
            durationUs = clip.timelineDurationUs.coerceAtLeast(1L),
        )

        dispatchAndSync(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = toTrackId,
                newStartUs = targetStartUs,
            ),
            isDirty = true,
        )
    }

    fun trimClipStartAbsolute(clipId: String, newStartMs: Long) {
        val clip = findRustClip(clipId) ?: return
        val requestedStartUs = newStartMs.coerceAtLeast(0) * 1000L
        val maxStartUs = (clip.timelineStartUs + clip.timelineDurationUs - 100_000L).coerceAtLeast(0L)
        val newStartUs = requestedStartUs.coerceIn(0L, maxStartUs)
        val deltaTimelineUs = newStartUs - clip.timelineStartUs
        val newSourceStartUs = (clip.sourceStartUs + (deltaTimelineUs * clip.speed).toLong()).coerceAtLeast(0L)

        dispatchAndSync(
            rustStore.commands.trimClipStart(
                clipId = clipId,
                newStartUs = newStartUs,
                newSourceStartUs = newSourceStartUs,
            ),
            isDirty = true,
        )
    }

    fun trimClipEndAbsolute(clipId: String, newEndMs: Long) {
        val snapshot = rustStore.snapshot.value ?: return
        val track = snapshot.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return

        val requestedDurationUs = ((newEndMs * 1000L) - clip.timelineStartUs).coerceAtLeast(100_000L)
        val maxBySourceUs = (((clip.sourceTotalDurationUs - clip.sourceStartUs).coerceAtLeast(100_000L).toDouble() / clip.speed)
            .toLong()).coerceAtLeast(100_000L)
        val maxByNeighborUs = nextClipStartUs(track, clipId)?.let { (it - clip.timelineStartUs).coerceAtLeast(100_000L) }
        val maxDurationUs = listOfNotNull(maxByNeighborUs, maxBySourceUs).minOrNull() ?: maxBySourceUs
        val newDurationUs = requestedDurationUs.coerceIn(100_000L, maxDurationUs)

        dispatchAndSync(
            rustStore.commands.trimClipEnd(
                clipId = clipId,
                newDurationUs = newDurationUs,
            ),
            isDirty = true,
        )
    }

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = 3000) {
        dispatchAndSync(
            rustStore.commands.addClip(
                trackId,
                RustClipSnapshot(
                    id = randomUuid(),
                    timelineStartUs = startMs * 1000L,
                    timelineDurationUs = durationMs * 1000L,
                    sourceStartUs = 0L,
                    sourceEndUs = durationMs * 1000L,
                    sourceTotalDurationUs = durationMs * 1000L,
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
            isDirty = true,
        )
    }

    fun removeClip(clipId: String) {
        dispatchAndSync(rustStore.commands.removeClip(clipId), isDirty = true)
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
            isDirty = true,
        )
    }

    fun splitClipAtPlayhead(clipId: String) {
        dispatchAndSync(
            rustStore.commands.splitClip(
                clipId = clipId,
                atUs = _state.value.playheadMs * 1000L,
                newClipId = randomUuid(),
            ),
            isDirty = true,
        )
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        dispatchAndSync(
            rustStore.commands.setClipSpeed(clipId, speed.toDouble()),
            isDirty = true,
        )
    }

    fun setClipVolume(clipId: String, volume: Float) {
        dispatchAndSync(
            rustStore.commands.setClipVolume(clipId, volume),
            isDirty = true,
        )
    }

    fun setClipOpacity(clipId: String, opacity: Float) {
        dispatchAndSync(
            rustStore.commands.setClipOpacity(clipId, opacity),
            isDirty = true,
        )
    }

    fun updateTextClip(clipId: String, newText: String) {
        dispatchAndSync(
            rustStore.commands.updateTextContent(clipId, newText),
            isDirty = true,
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
    ) {
        val clip = findRustClip(clipId)?.kind as? RustTextClipKind ?: return
        val style = clip.style.copy(
            fontSize = fontSizeSp ?: clip.style.fontSize,
            color = colorHex?.removePrefix("#")?.let { "FF$it" } ?: clip.style.color,
            backgroundColor = backgroundColorHex?.removePrefix("#")?.let { "FF$it" } ?: clip.style.backgroundColor,
            positionX = positionX ?: clip.style.positionX,
            positionY = positionY ?: clip.style.positionY,
            bold = isBold ?: clip.style.bold,
            italic = isItalic ?: clip.style.italic,
        )

        dispatchAndSync(
            rustStore.commands.updateTextStyle(clipId, style),
            isDirty = true,
        )
    }

    fun setTextClipDuration(clipId: String, durationMs: Long) {
        dispatchAndSync(
            rustStore.commands.trimClipEnd(
                clipId = clipId,
                newDurationUs = durationMs.coerceAtLeast(100L) * 1000L,
            ),
            isDirty = true,
        )
    }

    fun setTextTransitionIn(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(
            rustStore.commands.setTransitionIn(clipId, transition),
            isDirty = true,
        )
    }

    fun setTextTransitionOut(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(
            rustStore.commands.setTransitionOut(clipId, transition),
            isDirty = true,
        )
    }

    fun updateFilterParams(clipId: String, filterIndex: Int, newParams: Map<String, Float>) {
        val video = findRustClip(clipId)?.kind as? RustVideoClipKind ?: return
        val current = video.filters.getOrNull(filterIndex) ?: return
        val updated = when (current) {
            is RustBrightnessFilterSnapshot -> RustBrightnessFilterSnapshot(newParams["value"] ?: current.value)
            is RustContrastFilterSnapshot -> RustContrastFilterSnapshot(newParams["value"] ?: current.value)
            is RustSaturationFilterSnapshot -> RustSaturationFilterSnapshot(newParams["value"] ?: current.value)
            is RustBlurFilterSnapshot -> RustBlurFilterSnapshot(newParams["radius"] ?: current.radius)
            is RustSharpenFilterSnapshot -> RustSharpenFilterSnapshot(newParams["amount"] ?: current.amount)
            else -> current
        }

        dispatchAndSync(
            rustStore.commands.updateVideoFilter(
                clipId = clipId,
                index = filterIndex,
                filter = updated,
            ),
            isDirty = true,
        )
    }

    fun addFilter(clipId: String, filter: RustVideoFilterSnapshot) {
        dispatchAndSync(
            rustStore.commands.addVideoFilter(
                clipId = clipId,
                filter = filter,
            ),
            isDirty = true,
        )
    }

    fun removeFilter(clipId: String, filterIndex: Int) {
        dispatchAndSync(
            rustStore.commands.removeVideoFilter(clipId, filterIndex),
            isDirty = true,
        )
    }

    fun setTransitionIn(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(
            rustStore.commands.setTransitionIn(clipId, transition),
            isDirty = true,
        )
    }

    fun setTransitionOut(clipId: String, transition: RustTransitionSnapshot?) {
        dispatchAndSync(
            rustStore.commands.setTransitionOut(clipId, transition),
            isDirty = true,
        )
    }

    fun toggleTrackMute(trackId: String) {
        val muted = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.muted ?: return
        dispatchAndSync(
            rustStore.commands.setTrackMuted(trackId, !muted),
            isDirty = true,
        )
    }

    fun toggleTrackLock(trackId: String) {
        val locked = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.id == trackId }?.locked ?: return
        dispatchAndSync(
            rustStore.commands.setTrackLocked(trackId, !locked),
            isDirty = true,
        )
    }

    fun selectClip(clipId: String?) {
        _state.update { it.copy(selectedClipId = clipId) }
    }

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoomLevel = zoom.coerceIn(0.1f, 10f)) }
    }

    fun snapPosition(ms: Long, excludeClipId: String? = null): Long {
        val snapThresholdMs = (200 / _state.value.zoomLevel).toLong().coerceAtLeast(50)
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
            val startMs = clip.timelineStartUs / 1000L
            val endMs = (clip.timelineStartUs + clip.timelineDurationUs) / 1000L

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

        _state.update { it.copy(snapIndicatorMs = if (nearest != ms) nearest else null) }
        return nearest
    }

    fun clearSnapIndicator() {
        _state.update { it.copy(snapIndicatorMs = null) }
    }

    private fun findNearestNonOverlappingStartUs(
        track: RustTrackSnapshot,
        clipId: String,
        requestedStartUs: Long,
        durationUs: Long,
    ): Long {
        val others = track.clips
            .asSequence()
            .filter { it.id != clipId }
            .sortedBy { it.timelineStartUs }
            .toList()

        if (others.isEmpty()) {
            return requestedStartUs.coerceAtLeast(0L)
        }

        val reqStart = requestedStartUs.coerceAtLeast(0L)
        val reqEnd = reqStart + durationUs

        for (other in others) {
            val otherStart = other.timelineStartUs
            val otherEnd = other.timelineStartUs + other.timelineDurationUs
            if (reqStart < otherEnd && otherStart < reqEnd) {
                val leftEnd = otherStart - durationUs
                val rightStart = otherEnd

                val leftValid = leftEnd >= 0L && fitsWithoutOverlap(others, leftEnd, durationUs)
                val rightValid = fitsWithoutOverlap(others, rightStart, durationUs)

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

    private fun findNearestOpenStartUs(
        track: RustTrackSnapshot,
        requestedStartUs: Long,
        durationUs: Long,
    ): Long {
        val clips = track.clips.sortedBy { it.timelineStartUs }
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
        return clips.none { existing ->
            val existingStart = existing.timelineStartUs
            val existingEnd = existing.timelineStartUs + existing.timelineDurationUs
            startUs < existingEnd && existingStart < endUs
        }
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

    private fun dispatchNoSync(commandJson: String) {
        rustStore.dispatch(commandJson)
    }

    private fun dispatchAndSync(
        commandJson: String,
        projectPath: String? = _state.value.projectPath,
        selectedTrackId: String? = _state.value.selectedTrackId,
        selectedClipId: String? = _state.value.selectedClipId,
        playheadMs: Long = _state.value.playheadMs,
        isDirty: Boolean = _state.value.isDirty,
    ) {
        rustStore.dispatch(commandJson)
        syncFromRust(
            projectPath = projectPath,
            selectedTrackId = selectedTrackId,
            selectedClipId = selectedClipId,
            playheadMs = playheadMs,
            isDirty = isDirty,
        )
    }

    private fun syncFromRust(
        projectPath: String? = _state.value.projectPath,
        selectedTrackId: String? = _state.value.selectedTrackId,
        selectedClipId: String? = _state.value.selectedClipId,
        playheadMs: Long = _state.value.playheadMs,
        isDirty: Boolean = _state.value.isDirty,
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
}
