package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
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
import org.mlm.miniter.editor.model.RustTextClipKind
import org.mlm.miniter.editor.model.RustTextStyleSnapshot
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustTransitionKind
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.engine.VideoInfo
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.Clip
import org.mlm.miniter.project.ExportFormat
import org.mlm.miniter.project.ExportSettings
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.MinterProject
import org.mlm.miniter.project.ProjectRepository
import org.mlm.miniter.project.RecentProjectsRepository
import org.mlm.miniter.project.TrackType
import org.mlm.miniter.project.Transition
import org.mlm.miniter.project.TransitionType
import org.mlm.miniter.project.VideoFilter
import org.mlm.miniter.ui.components.snackbar.SnackbarManager
import kotlin.time.Clock

data class ProjectUiState(
    val project: MinterProject? = null,
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
    private val projectRepository: ProjectRepository,
    private val recentProjectsRepository: RecentProjectsRepository,
    private val engine: PlatformVideoEngine,
    private val snackbarManager: SnackbarManager,
    private val rustStore: RustProjectStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state

    val exportProgress = engine.exportProgress

    val project: StateFlow<MinterProject?> = _state.map { it.project }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
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
                val info = engine.probeVideo(initialVideoPath)
                sourceDurationMs = info.durationMs

                rustStore.create(name)
                val videoTrackId = ensureTrack(RustTrackKind.Video, "Video 1")

                dispatchAndSync(
                    rustStore.commands.addClip(
                        videoTrackId,
                        RustClipSnapshot(
                            id = randomUuid(),
                            timelineStartUs = 0L,
                            timelineDurationUs = info.durationMs * 1000L,
                            sourceStartUs = 0L,
                            sourceEndUs = info.durationMs * 1000L,
                            sourceTotalDurationUs = info.durationMs * 1000L,
                            speed = 1.0,
                            volume = 1.0f,
                            opacity = 1.0f,
                            muted = false,
                            transitionIn = null,
                            kind = RustVideoClipKind(
                                sourcePath = initialVideoPath,
                                width = info.width,
                                height = info.height,
                                fps = if (info.frameRate > 0.0) info.frameRate else 30.0,
                                filters = emptyList(),
                                audioFilters = emptyList(),
                            ),
                        ),
                    ),
                    projectPath = savePath,
                    selectedTrackId = videoTrackId,
                    isDirty = true,
                )

                loadThumbnails(initialVideoPath)
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
                val project = projectRepository.load(path)

                val missingFiles = project.timeline.tracks
                    .flatMap { it.clips }
                    .mapNotNull { clip ->
                        when (clip) {
                            is Clip.VideoClip -> clip.sourcePath
                            is Clip.AudioClip -> clip.sourcePath
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

                rustStore.importLegacyProject(project)
                syncFromRust(
                    projectPath = path,
                    playheadMs = 0L,
                    isDirty = false,
                )

                val firstClip = project.timeline.tracks
                    .flatMap { it.clips }
                    .filterIsInstance<Clip.VideoClip>()
                    .firstOrNull()

                if (firstClip != null) {
                    sourceDurationMs = try {
                        engine.probeVideo(firstClip.sourcePath).durationMs
                    } catch (_: Exception) {
                        firstClip.durationMs
                    }
                    loadThumbnails(firstClip.sourcePath)
                }

                recentProjectsRepository.addRecent(path, project.name)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to load project: ${e.message}")
            }
        }
    }

    fun saveProject(path: String? = null) {
        viewModelScope.launch {
            val project = _state.value.project ?: return@launch
            val savePath = path ?: _state.value.projectPath ?: return@launch
            _state.update { it.copy(isSaving = true) }
            try {
                projectRepository.save(project, savePath)
                _state.update { it.copy(projectPath = savePath, isSaving = false, isDirty = false) }
                recentProjectsRepository.addRecent(savePath, project.name)
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

    fun initProject(videoPath: String, projectName: String, savePath: String?) {
        if (videoPath.endsWith(".mntr") && projectRepository.exists(videoPath)) {
            loadProject(videoPath)
        } else if (savePath != null && savePath.endsWith(".mntr") && projectRepository.exists(savePath)) {
            loadProject(savePath)
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
        val max = _state.value.project?.timeline?.durationMs ?: Long.MAX_VALUE
        setPlayhead((current + deltaMs).coerceIn(0, max))
    }

    fun seekToStart() = setPlayhead(0)

    fun seekToEnd() {
        setPlayhead(_state.value.project?.timeline?.durationMs ?: 0L)
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
            val snapshot = rustStore.snapshot.value ?: return@launch
            _state.update { it.copy(isLoading = true) }

            try {
                val selected = _state.value.project?.timeline?.tracks
                    ?.flatMap { t -> t.clips.map { t.id to it } }
                    ?.firstOrNull { (_, c) -> c.id == _state.value.selectedClipId }

                val initialCursorMs = when (val clip = selected?.second) {
                    null -> _state.value.playheadMs
                    else -> clip.startMs + clip.durationMs
                }

                data class ImportItem(
                    val file: PlatformFile,
                    val info: VideoInfo,
                )

                val items = files.map { file ->
                    ImportItem(file, engine.probeVideo(file.path))
                }

                var cursorVideoUs = initialCursorMs * 1000L
                var cursorAudioUs = initialCursorMs * 1000L

                var videoTrackId = snapshot.timeline.tracks.firstOrNull { it.kind == RustTrackKind.Video }?.id
                if (videoTrackId == null) videoTrackId = ensureTrack(RustTrackKind.Video, "Video 1")

                var audioTrackId = rustStore.snapshot.value?.timeline?.tracks?.firstOrNull { it.kind == RustTrackKind.Audio }?.id
                if (audioTrackId == null) audioTrackId = ensureTrack(RustTrackKind.Audio, "Audio 1")

                for (item in items) {
                    val info = item.info
                    when {
                        info.hasVideo -> {
                            dispatchNoSync(
                                rustStore.commands.addClip(
                                    videoTrackId,
                                    RustClipSnapshot(
                                        id = randomUuid(),
                                        timelineStartUs = cursorVideoUs,
                                        timelineDurationUs = info.durationMs * 1000L,
                                        sourceStartUs = 0L,
                                        sourceEndUs = info.durationMs * 1000L,
                                        sourceTotalDurationUs = info.durationMs * 1000L,
                                        speed = 1.0,
                                        volume = 1.0f,
                                        opacity = 1.0f,
                                        muted = false,
                                        transitionIn = null,
                                        kind = RustVideoClipKind(
                                            sourcePath = item.file.path,
                                            width = info.width,
                                            height = info.height,
                                            fps = if (info.frameRate > 0.0) info.frameRate else 30.0,
                                            filters = emptyList(),
                                            audioFilters = emptyList(),
                                        ),
                                    ),
                                )
                            )
                            cursorVideoUs += info.durationMs * 1000L
                        }

                        info.hasAudio -> {
                            dispatchNoSync(
                                rustStore.commands.addClip(
                                    audioTrackId,
                                    RustClipSnapshot(
                                        id = randomUuid(),
                                        timelineStartUs = cursorAudioUs,
                                        timelineDurationUs = info.durationMs * 1000L,
                                        sourceStartUs = 0L,
                                        sourceEndUs = info.durationMs * 1000L,
                                        sourceTotalDurationUs = info.durationMs * 1000L,
                                        speed = 1.0,
                                        volume = 1.0f,
                                        opacity = 1.0f,
                                        muted = false,
                                        transitionIn = null,
                                        kind = RustAudioClipKind(
                                            sourcePath = item.file.path,
                                            sampleRate = info.audioSampleRate.coerceAtLeast(44_100),
                                            channels = info.audioChannels.coerceAtLeast(1),
                                            filters = emptyList(),
                                        ),
                                    ),
                                )
                            )
                            cursorAudioUs += info.durationMs * 1000L
                        }
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
        if (_state.value.project == null) return
        viewModelScope.launch {
            val projectJson = rustStore.exportProjectJson()
            engine.exportProjectJson(projectJson, outputPath)
        }
    }

    fun cancelExport() = engine.cancelExport()

    fun resetExport() = engine.reset()

    fun updateExportSettings(settings: ExportSettings) {
        dispatchAndSync(
            rustStore.commands.setExportProfile(settings.toRustExportProfile()),
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

    fun addTrack(type: TrackType, label: String? = null) {
        val kind = when (type) {
            TrackType.Video -> RustTrackKind.Video
            TrackType.Audio -> RustTrackKind.Audio
            TrackType.Text -> RustTrackKind.Text
        }
        val count = rustStore.snapshot.value?.timeline?.tracks?.count { it.kind == kind } ?: 0
        dispatchAndSync(
            rustStore.commands.addTrack(kind, label ?: "${type.name} ${count + 1}"),
            isDirty = true,
        )
    }

    fun removeTrack(trackId: String) {
        val project = _state.value.project ?: return
        val track = project.timeline.tracks.find { it.id == trackId } ?: return
        if (track.type == TrackType.Video && project.timeline.tracks.count { it.type == TrackType.Video } <= 1) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        dispatchAndSync(rustStore.commands.removeTrack(trackId), isDirty = true)
    }

    fun moveClipAbsolute(clipId: String, absoluteStartMs: Long) {
        val trackId = rustStore.snapshot.value
            ?.timeline
            ?.tracks
            ?.firstOrNull { track -> track.clips.any { it.id == clipId } }
            ?.id ?: return

        dispatchAndSync(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = trackId,
                newStartUs = absoluteStartMs.coerceAtLeast(0) * 1000L,
            ),
            isDirty = true,
        )
    }

    fun moveClipToTrack(clipId: String, fromTrackId: String, toTrackId: String) {
        val clip = rustStore.snapshot.value
            ?.timeline
            ?.tracks
            ?.firstOrNull { it.id == fromTrackId }
            ?.clips
            ?.firstOrNull { it.id == clipId }
            ?: return

        dispatchAndSync(
            rustStore.commands.moveClip(
                clipId = clipId,
                trackId = toTrackId,
                newStartUs = clip.timelineStartUs,
            ),
            isDirty = true,
        )
    }

    fun trimClipStartAbsolute(clipId: String, newStartMs: Long) {
        val clip = findRustClip(clipId) ?: return
        val newStartUs = newStartMs.coerceAtLeast(0) * 1000L
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
        val clip = findRustClip(clipId) ?: return
        val newDurationUs = ((newEndMs * 1000L) - clip.timelineStartUs).coerceAtLeast(100_000L)

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

    fun setTextTransition(clipId: String, transition: Transition?) {
        dispatchAndSync(
            rustStore.commands.setTransitionIn(clipId, transition?.toRustTransition()),
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

    fun addFilter(clipId: String, filter: VideoFilter) {
        dispatchAndSync(
            rustStore.commands.addVideoFilter(
                clipId = clipId,
                filter = filter.toRustVideoFilter(),
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

    fun setTransition(clipId: String, transition: Transition?) {
        dispatchAndSync(
            rustStore.commands.setTransitionIn(clipId, transition?.toRustTransition()),
            isDirty = true,
        )
    }

    fun toggleTrackMute(trackId: String) {
        val muted = _state.value.project?.timeline?.tracks?.firstOrNull { it.id == trackId }?.isMuted ?: return
        dispatchAndSync(
            rustStore.commands.setTrackMuted(trackId, !muted),
            isDirty = true,
        )
    }

    fun toggleTrackLock(trackId: String) {
        val locked = _state.value.project?.timeline?.tracks?.firstOrNull { it.id == trackId }?.isLocked ?: return
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
        val project = _state.value.project ?: return ms
        val playhead = _state.value.playheadMs

        var nearest = ms
        var minDist = snapThresholdMs

        val dph = kotlin.math.abs(ms - playhead)
        if (dph < minDist) {
            nearest = playhead
            minDist = dph
        }

        for (clip in project.timeline.tracks.flatMap { it.clips }) {
            if (clip.id == excludeClipId) continue
            val ds = kotlin.math.abs(ms - clip.startMs)
            if (ds < minDist) {
                nearest = clip.startMs
                minDist = ds
            }
            val de = kotlin.math.abs(ms - (clip.startMs + clip.durationMs))
            if (de < minDist) {
                nearest = clip.startMs + clip.durationMs
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
        val legacy = rustStore.legacyProject.value
        _state.update {
            it.copy(
                project = legacy,
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

private fun ExportSettings.toRustExportProfile(): RustExportProfileSnapshot {
    return RustExportProfileSnapshot(
        format = when (format) {
            ExportFormat.MP4 -> RustExportFormat.Mp4
            ExportFormat.WebM -> RustExportFormat.WebM
            ExportFormat.MOV -> RustExportFormat.Mov
        },
        resolution = when {
            width == 854 && height == 480 -> RustExportResolution.Sd480
            width == 1280 && height == 720 -> RustExportResolution.Hd720
            width == 1920 && height == 1080 -> RustExportResolution.Hd1080
            width == 3840 && height == 2160 -> RustExportResolution.Uhd4k
            width > 0 && height > 0 -> RustExportResolution.Custom(width, height)
            else -> RustExportResolution.Hd1080
        },
        fps = 30.0,
        videoBitrateKbps = (500 + quality * 80).toInt().coerceAtLeast(500),
        audioBitrateKbps = 192,
        audioSampleRate = 48_000,
        outputPath = "",
    )
}

private fun Transition.toRustTransition(): RustTransitionSnapshot {
    return RustTransitionSnapshot(
        kind = when (type) {
            TransitionType.CrossFade -> RustTransitionKind.CrossFade
            TransitionType.SlideLeft -> RustTransitionKind.SlideLeft
            TransitionType.SlideRight -> RustTransitionKind.SlideRight
            TransitionType.Dissolve -> RustTransitionKind.Dissolve
        },
        duration = durationMs * 1000L,
    )
}

private fun VideoFilter.toRustVideoFilter(): RustVideoFilterSnapshot {
    return when (type) {
        FilterType.Brightness -> RustBrightnessFilterSnapshot(params["value"] ?: 0f)
        FilterType.Contrast -> RustContrastFilterSnapshot(params["value"] ?: 1f)
        FilterType.Saturation -> RustSaturationFilterSnapshot(params["value"] ?: 1f)
        FilterType.Grayscale -> RustGrayscaleFilterSnapshot
        FilterType.Blur -> RustBlurFilterSnapshot(params["radius"] ?: 5f)
        FilterType.Sharpen -> RustSharpenFilterSnapshot(params["amount"] ?: 1f)
        FilterType.Sepia -> RustSepiaFilterSnapshot
    }
}
