package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.engine.UndoManager
import org.mlm.miniter.engine.VideoInfo
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.*
import org.mlm.miniter.ui.components.snackbar.SnackbarManager

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
    private val undoManager: UndoManager,
    private val snackbarManager: SnackbarManager,
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

    private var preDragSnapshot: MinterProject? = null

    fun newProject(name: String, initialVideoPath: String, savePath: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val info = engine.probeVideo(initialVideoPath)
                sourceDurationMs = info.durationMs

                val clip = Clip.VideoClip(
                    id = randomUuid(),
                    startMs = 0,
                    durationMs = info.durationMs,
                    sourcePath = initialVideoPath,
                    sourceEndMs = info.durationMs,
                )
                val track = Track(id = "video-0", type = TrackType.Video, clips = listOf(clip))
                val project = MinterProject(
                    name = name,
                    timeline = Timeline(tracks = listOf(track), durationMs = info.durationMs),
                )
                undoManager.clear()
                _state.update {
                    it.copy(
                        project = project, projectPath = savePath, selectedTrackId = track.id,
                        isDirty = true, isLoading = false,
                        canUndo = false, canRedo = false,
                    )
                }
                loadThumbnails(initialVideoPath)
                recentProjectsRepository.addRecent(initialVideoPath, name)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to open video: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun loadProject(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val project = projectRepository.load(path)
                undoManager.clear()
                _state.update {
                    it.copy(
                        project = project, projectPath = path,
                        playheadMs = 0, isDirty = false, isLoading = false,
                        canUndo = false, canRedo = false,
                    )
                }
                val firstClip = project.timeline.tracks
                    .flatMap { it.clips }
                    .filterIsInstance<Clip.VideoClip>()
                    .firstOrNull()
                if (firstClip != null) {
                    sourceDurationMs = try {
                        engine.probeVideo(firstClip.sourcePath).durationMs
                    } catch (_: Exception) { firstClip.durationMs }
                    loadThumbnails(firstClip.sourcePath)
                }
                recentProjectsRepository.addRecent(path, project.name)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to load project: ${e.message}")
                e.printStackTrace()
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
                e.printStackTrace()
            }
        }
    }

    fun renameProject(newName: String) {
        val project = _state.value.project ?: return
        _state.update {
            it.copy(
                project = project.copy(name = newName),
                isDirty = true,
            )
        }
    }

    fun startAutoSave(enabled: Boolean, intervalSeconds: Float) {
        autoSaveJob?.cancel()
        if (!enabled) return
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay((intervalSeconds * 1000).toLong())
                val s = _state.value
                if (s.isDirty && s.projectPath != null && !s.isSaving) saveProject()
            }
        }
    }

    fun stopAutoSave() { autoSaveJob?.cancel(); autoSaveJob = null }

    fun undo() {
        val current = _state.value.project ?: return
        val previous = undoManager.undo(current) ?: return
        _state.update {
            it.copy(
                project = previous, isDirty = true,
                canUndo = undoManager.canUndo, canRedo = undoManager.canRedo,
            )
        }
        autoRecalcDuration()
    }

    fun redo() {
        val current = _state.value.project ?: return
        val next = undoManager.redo(current) ?: return
        _state.update {
            it.copy(
                project = next, isDirty = true,
                canUndo = undoManager.canUndo, canRedo = undoManager.canRedo,
            )
        }
        autoRecalcDuration()
    }

    fun beginContinuousEdit() {
        preDragSnapshot = _state.value.project
    }

    fun commitContinuousEdit() {
        val snapshot = preDragSnapshot ?: return
        val current = _state.value.project
        preDragSnapshot = null

        if (current != null && current !== snapshot) {
            undoManager.push(snapshot)
            _state.update {
                it.copy(canUndo = undoManager.canUndo, canRedo = undoManager.canRedo)
            }
            autoRecalcDuration()
        }
    }

    fun cancelContinuousEdit() {
        val snapshot = preDragSnapshot ?: return
        _state.update { it.copy(project = snapshot) }
        preDragSnapshot = null
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
        val current = _state.value.isPlaying
        setPlaying(!current)
    }

    fun seekTo(ms: Long) = setPlayhead(ms)

    fun seekRelative(deltaMs: Long) {
        val current = _state.value.playheadMs
        val max = _state.value.project?.timeline?.durationMs ?: Long.MAX_VALUE
        setPlayhead((current + deltaMs).coerceIn(0, max))
    }

    fun seekToStart() = setPlayhead(0)

    fun seekToEnd() {
        val max = _state.value.project?.timeline?.durationMs ?: 0L
        setPlayhead(max)
    }

    fun zoomIn() {
        val current = _state.value.zoomLevel
        setZoom(current * 1.25f)
    }

    fun zoomOut() {
        val current = _state.value.zoomLevel
        setZoom(current / 1.25f)
    }

    fun addTextOverlay() {
        val ph = _state.value.playheadMs
        addTextClip("text-0", "Text", ph)
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
        _state.update { it.copy(playheadMs = ms.coerceAtLeast(0)) }
    }

    fun onPlayerPositionChanged(sliderPos: Float) {
        if (!_state.value.isPlaying) return
        val totalMs = _state.value.project?.timeline?.durationMs ?: sourceDurationMs
        if (totalMs <= 0) return
        _state.update { it.copy(playheadMs = ((sliderPos / 1000f) * totalMs).toLong().coerceIn(0, totalMs)) }
    }

    fun onPlayerCompleted() {
        _state.update { it.copy(isPlaying = false) }
    }

    fun playheadToSliderPos(): Float {
        val totalMs = _state.value.project?.timeline?.durationMs ?: sourceDurationMs
        if (totalMs <= 0) return 0f
        return (_state.value.playheadMs.toFloat() / totalMs * 1000f).coerceIn(0f, 1000f)
    }

    fun getCurrentClipSpeed(): Float {
        val project = _state.value.project ?: return 1f
        val ph = _state.value.playheadMs
        return project.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.VideoClip>()
            .firstOrNull { ph >= it.startMs && ph < it.startMs + it.durationMs }
            ?.speed ?: 1f
    }

    fun getCurrentClipVolume(): Float {
        val project = _state.value.project ?: return 1f
        val ph = _state.value.playheadMs
        return project.timeline.tracks.filter { !it.isMuted }.flatMap { it.clips }
            .filterIsInstance<Clip.VideoClip>()
            .firstOrNull { ph >= it.startMs && ph < it.startMs + it.durationMs }
            ?.volume ?: 1f
    }

    fun getVisibleTextClips(): List<Clip.TextClip> {
        val project = _state.value.project ?: return emptyList()
        val ph = _state.value.playheadMs
        return project.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.TextClip>()
            .filter { ph >= it.startMs && ph < it.startMs + it.durationMs }
    }

    fun importVideoClip(sourcePath: String, targetTrackId: String? = null) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true) }
                val info = engine.probeVideo(sourcePath)
                val project = _state.value.project ?: return@launch
                val trackId = targetTrackId
                    ?: project.timeline.tracks.firstOrNull { it.type == TrackType.Video }?.id
                    ?: return@launch
                val track = project.timeline.tracks.find { it.id == trackId } ?: return@launch
                val insertAt = track.clips.maxOfOrNull { it.startMs + it.durationMs } ?: 0L

                recordAndMutate { p ->
                    val clip = Clip.VideoClip(
                        id = randomUuid(), startMs = insertAt,
                        durationMs = info.durationMs, sourcePath = sourcePath,
                        sourceEndMs = info.durationMs,
                    )
                    p.withClipAddedToTrack(trackId, clip)
                        .withRecalculatedDuration()
                }
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Video clip added")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to import: ${e.message}")
            }
        }
    }

    fun importAudioClip(sourcePath: String, targetTrackId: String? = null) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true) }
                val info = engine.probeVideo(sourcePath)
                val project = _state.value.project ?: return@launch

                var audioTrackId = targetTrackId
                    ?: project.timeline.tracks.firstOrNull { it.type == TrackType.Audio }?.id

                if (audioTrackId == null) {
                    addTrack(TrackType.Audio)
                    audioTrackId = _state.value.project?.timeline?.tracks
                        ?.lastOrNull { it.type == TrackType.Audio }?.id ?: return@launch
                }

                val track = _state.value.project?.timeline?.tracks
                    ?.find { it.id == audioTrackId } ?: return@launch
                val insertAt = track.clips.maxOfOrNull { it.startMs + it.durationMs } ?: 0L

                recordAndMutate { p ->
                    val clip = Clip.AudioClip(
                        id = randomUuid(), startMs = insertAt,
                        durationMs = info.durationMs, sourcePath = sourcePath,
                        sourceEndMs = info.durationMs,
                    )
                    p.withClipAddedToTrack(audioTrackId!!, clip)
                        .withRecalculatedDuration()
                }
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Audio clip added")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to import audio: ${e.message}")
            }
        }
    }

    fun importMediaFiles(files: List<PlatformFile>) {
        viewModelScope.launch {
            val project = _state.value.project ?: return@launch

            _state.update { it.copy(isLoading = true) }

            try {
                val selected = project.timeline.tracks
                    .flatMap { t -> t.clips.map { t.id to it } }
                    .firstOrNull { (_, c) -> c.id == _state.value.selectedClipId }

                val initialCursor = when (val clip = selected?.second) {
                    null -> _state.value.playheadMs
                    else -> clip.startMs + clip.durationMs
                }

                data class ImportItem(
                    val file: PlatformFile,
                    val info: VideoInfo,
                )

                val items = files.map { f ->
                    ImportItem(f, engine.probeVideo(f.path))
                }

                recordAndMutate { p ->
                    var cursorVideo = initialCursor
                    var cursorAudio = initialCursor

                    val videoTrackId = p.timeline.tracks.firstOrNull { it.type == TrackType.Video }?.id
                        ?: "video-0"

                    var audioTrackId = p.timeline.tracks.firstOrNull { it.type == TrackType.Audio }?.id
                    var projectWithAudioTrack = p

                    if (audioTrackId == null) {
                        val newId = "audio-${p.timeline.tracks.count { it.type == TrackType.Audio }}"
                        projectWithAudioTrack = p.copy(
                            timeline = p.timeline.copy(
                                tracks = p.timeline.tracks + Track(id = newId, type = TrackType.Audio)
                            )
                        )
                        audioTrackId = newId
                    }

                    val finalAudioTrackId = audioTrackId

                    items.fold(projectWithAudioTrack) { acc, item ->
                        val i = item.info
                        if (i.hasVideo) {
                            val clip = Clip.VideoClip(
                                id = randomUuid(),
                                startMs = cursorVideo,
                                durationMs = i.durationMs,
                                sourcePath = item.file.path,
                                sourceStartMs = 0,
                                sourceEndMs = i.durationMs,
                            )
                            cursorVideo += i.durationMs
                            acc.withClipAddedToTrack(videoTrackId, clip)
                        } else if (i.hasAudio) {
                            val clip = Clip.AudioClip(
                                id = randomUuid(),
                                startMs = cursorAudio,
                                durationMs = i.durationMs,
                                sourcePath = item.file.path,
                                sourceStartMs = 0,
                                sourceEndMs = i.durationMs,
                            )
                            cursorAudio += i.durationMs
                            acc.withClipAddedToTrack(finalAudioTrackId, clip)
                        } else {
                            acc
                        }
                    }.withRecalculatedDuration()
                }

                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Imported ${items.size} file(s)")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to import: ${e.message}")
                e.printStackTrace()
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
        val project = _state.value.project ?: return
        viewModelScope.launch { engine.exportVideo(project, outputPath) }
    }
    fun cancelExport() = engine.cancelExport()
    fun resetExport() = engine.reset()

    fun updateExportSettings(settings: ExportSettings) {
        val project = _state.value.project ?: return
        _state.update {
            it.copy(project = project.copy(exportSettings = settings), isDirty = true)
        }
    }

    fun startExport(outputPath: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch { engine.exportVideo(project, outputPath) }
    }

    fun reset() {
        stopAutoSave()
        _state.update {
            ProjectUiState()
        }
        undoManager.clear()
        engine.reset()
    }

    fun addTrack(type: TrackType, label: String? = null) {
        recordAndMutate { project ->
            val count = project.timeline.tracks.count { it.type == type }
            val newTrack = Track(
                id = "${type.name.lowercase()}-$count", type = type,
                label = label ?: "${type.name} ${count + 1}",
            )
            project.copy(timeline = project.timeline.copy(tracks = project.timeline.tracks + newTrack))
        }
    }

    fun removeTrack(trackId: String) {
        val project = _state.value.project ?: return
        val track = project.timeline.tracks.find { it.id == trackId } ?: return
        if (track.type == TrackType.Video &&
            project.timeline.tracks.count { it.type == TrackType.Video } <= 1
        ) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        recordAndMutate { p ->
            p.copy(
                timeline = p.timeline.copy(tracks = p.timeline.tracks.filter { it.id != trackId })
            ).withRecalculatedDuration()
        }
    }

    fun moveClipAbsolute(clipId: String, absoluteStartMs: Long) {
        val project = _state.value.project ?: return
        val newStart = absoluteStartMs.coerceAtLeast(0)

        var targetTrack: Track? = null
        var movingClip: Clip? = null
        for (track in project.timeline.tracks) {
            val c = track.clips.find { it.id == clipId }
            if (c != null) { targetTrack = track; movingClip = c; break }
        }
        if (targetTrack == null || movingClip == null) return

        val movingEnd = newStart + movingClip.durationMs
        val hasOverlap = targetTrack.clips.any { other ->
            other.id != clipId &&
                    newStart < other.startMs + other.durationMs &&
                    other.startMs < movingEnd
        }

        val finalStart = if (hasOverlap) {
            findNonOverlappingPosition(targetTrack, movingClip, newStart)
        } else newStart

        applyContinuousEdit { p ->
            p.withClipStartMs(clipId, finalStart)
        }
    }

    fun moveClipToTrack(clipId: String, fromTrackId: String, toTrackId: String) {
        val project = _state.value.project ?: return
        val fromTrack = project.timeline.tracks.find { it.id == fromTrackId } ?: return
        val toTrack = project.timeline.tracks.find { it.id == toTrackId } ?: return
        val clip = fromTrack.clips.find { it.id == clipId } ?: return

        if (fromTrack.type != toTrack.type) return
        if (toTrack.isLocked) return

        val hasOverlap = toTrack.clips.any { other ->
            clip.startMs < other.startMs + other.durationMs &&
                    other.startMs < clip.startMs + clip.durationMs
        }
        val finalStart = if (hasOverlap) {
            findNonOverlappingPosition(toTrack, clip, clip.startMs)
        } else clip.startMs

        val movedClip = when (clip) {
            is Clip.VideoClip -> clip.copy(startMs = finalStart)
            is Clip.AudioClip -> clip.copy(startMs = finalStart)
            is Clip.TextClip -> clip.copy(startMs = finalStart)
        }

        val isContinuous = preDragSnapshot != null

        if (isContinuous) {
            applyContinuousEdit { p ->
                p.copy(
                    timeline = p.timeline.copy(
                        tracks = p.timeline.tracks.map { t ->
                            when (t.id) {
                                fromTrackId -> t.copy(clips = t.clips.filter { it.id != clipId })
                                toTrackId -> t.copy(clips = t.clips + movedClip)
                                else -> t
                            }
                        }
                    )
                ).withRecalculatedDuration()
            }
        } else {
            recordAndMutate { p ->
                p.copy(
                    timeline = p.timeline.copy(
                        tracks = p.timeline.tracks.map { t ->
                            when (t.id) {
                                fromTrackId -> t.copy(clips = t.clips.filter { it.id != clipId })
                                toTrackId -> t.copy(clips = t.clips + movedClip)
                                else -> t
                            }
                        }
                    )
                ).withRecalculatedDuration()
            }
        }
    }

    private fun findNonOverlappingPosition(track: Track, clip: Clip, desiredStart: Long): Long {
        val duration = clip.durationMs
        val sorted = track.clips.filter { it.id != clip.id }.sortedBy { it.startMs }
        var candidate = desiredStart.coerceAtLeast(0)
        for (other in sorted) {
            val otherEnd = other.startMs + other.durationMs
            if (candidate < otherEnd && other.startMs < candidate + duration) {
                candidate = otherEnd
            }
        }
        return candidate
    }

    fun trimClipStartAbsolute(clipId: String, newStartMs: Long) {
        applyContinuousEdit { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            when {
                                clip.id == clipId && clip is Clip.VideoClip -> {
                                    val maxStart = clip.startMs + clip.durationMs - 100
                                    val clampedStart = newStartMs.coerceIn(
                                        clip.startMs - (clip.sourceStartMs / clip.speed).toLong(),
                                        maxStart,
                                    )
                                    val timelineDelta = clampedStart - clip.startMs
                                    val sourceDelta = (timelineDelta * clip.speed).toLong()
                                    clip.copy(
                                        startMs = clampedStart,
                                        durationMs = clip.durationMs - timelineDelta,
                                        sourceStartMs = clip.sourceStartMs + sourceDelta,
                                    )
                                }
                                clip.id == clipId && clip is Clip.AudioClip -> {
                                    val maxStart = clip.startMs + clip.durationMs - 100
                                    val clampedStart = newStartMs.coerceIn(
                                        clip.startMs - clip.sourceStartMs,
                                        maxStart,
                                    )
                                    val delta = clampedStart - clip.startMs
                                    clip.copy(
                                        startMs = clampedStart,
                                        durationMs = clip.durationMs - delta,
                                        sourceStartMs = clip.sourceStartMs + delta,
                                    )
                                }
                                else -> clip
                            }
                        })
                    }
                )
            )
        }
    }

    fun trimClipEndAbsolute(clipId: String, newEndMs: Long) {
        val project = _state.value.project ?: return

        var targetTrack: Track? = null
        var thisClip: Clip? = null
        for (track in project.timeline.tracks) {
            val c = track.clips.find { it.id == clipId }
            if (c != null && (c is Clip.VideoClip || c is Clip.AudioClip)) {
                targetTrack = track; thisClip = c; break
            }
        }
        if (targetTrack == null || thisClip == null) return

        val minEnd = thisClip.startMs + 100
        var clampedEnd = newEndMs.coerceAtLeast(minEnd)

        val nextClip = targetTrack.clips
            .filter { it.id != clipId && it.startMs > thisClip.startMs }
            .minByOrNull { it.startMs }
        if (nextClip != null) {
            clampedEnd = clampedEnd.coerceAtMost(nextClip.startMs)
        }

        val newTimelineDuration = clampedEnd - thisClip.startMs

        applyContinuousEdit { p ->
            p.copy(
                timeline = p.timeline.copy(
                    tracks = p.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            when {
                                clip.id == clipId && clip is Clip.VideoClip -> {
                                    val newSourceDuration = (newTimelineDuration * clip.speed).toLong()
                                    clip.copy(
                                        durationMs = newTimelineDuration,
                                        sourceEndMs = clip.sourceStartMs + newSourceDuration,
                                    )
                                }
                                clip.id == clipId && clip is Clip.AudioClip -> {
                                    clip.copy(
                                        durationMs = newTimelineDuration,
                                        sourceEndMs = clip.sourceStartMs + newTimelineDuration,
                                    )
                                }
                                else -> clip
                            }
                        })
                    }
                )
            )
        }
    }

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = 3000) {
        recordAndMutate { project ->
            val textTrack = project.timeline.tracks.firstOrNull { it.type == TrackType.Text }
                ?: Track(id = "text-0", type = TrackType.Text)
            val clip = Clip.TextClip(
                id = randomUuid(), startMs = startMs, durationMs = durationMs, text = text,
            )
            val updatedTrack = textTrack.copy(clips = textTrack.clips + clip)
            project.copy(
                timeline = project.timeline.copy(
                    tracks = if (project.timeline.tracks.any { it.id == updatedTrack.id }) {
                        project.timeline.tracks.map { if (it.id == updatedTrack.id) updatedTrack else it }
                    } else project.timeline.tracks + updatedTrack
                )
            ).withRecalculatedDuration()
        }
    }

    fun removeClip(clipId: String) {
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { t ->
                        t.copy(clips = t.clips.filter { it.id != clipId })
                    }
                )
            ).withRecalculatedDuration()
        }
    }

    fun duplicateClip(clipId: String) {
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        val clip = track.clips.find { it.id == clipId } ?: return@map track
                        val endOfTrack = track.clips.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
                        val newClip = when (clip) {
                            is Clip.VideoClip -> clip.copy(id = randomUuid(), startMs = endOfTrack)
                            is Clip.AudioClip -> clip.copy(id = randomUuid(), startMs = endOfTrack)
                            is Clip.TextClip -> clip.copy(id = randomUuid(), startMs = endOfTrack)
                        }
                        track.copy(clips = track.clips + newClip)
                    }
                )
            ).withRecalculatedDuration()
        }
    }

    fun splitClipAtPlayhead(clipId: String) {
        val playhead = _state.value.playheadMs
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        val clip = track.clips.find { it.id == clipId }
                        when {
                            clip is Clip.VideoClip &&
                                playhead > clip.startMs &&
                                playhead < clip.startMs + clip.durationMs -> {
                                val splitPoint = playhead - clip.startMs
                                val firstHalf = clip.copy(
                                    durationMs = splitPoint,
                                    sourceEndMs = clip.sourceStartMs + splitPoint,
                                )
                                val secondHalf = clip.copy(
                                    id = randomUuid(),
                                    startMs = playhead,
                                    durationMs = clip.durationMs - splitPoint,
                                    sourceStartMs = clip.sourceStartMs + splitPoint,
                                )
                                track.copy(clips = track.clips.flatMap {
                                    if (it.id == clipId) listOf(firstHalf, secondHalf) else listOf(it)
                                })
                            }
                            clip is Clip.AudioClip &&
                                playhead > clip.startMs &&
                                playhead < clip.startMs + clip.durationMs -> {
                                val splitPoint = playhead - clip.startMs
                                val firstHalf = clip.copy(
                                    durationMs = splitPoint,
                                    sourceEndMs = clip.sourceStartMs + splitPoint,
                                )
                                val secondHalf = clip.copy(
                                    id = randomUuid(),
                                    startMs = playhead,
                                    durationMs = clip.durationMs - splitPoint,
                                    sourceStartMs = clip.sourceStartMs + splitPoint,
                                )
                                track.copy(clips = track.clips.flatMap {
                                    if (it.id == clipId) listOf(firstHalf, secondHalf) else listOf(it)
                                })
                            }
                            else -> track
                        }
                    }
                )
            )
        }
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                val sourceDuration = clip.sourceEndMs - clip.sourceStartMs
                                val newDuration = (sourceDuration / clamped).toLong().coerceAtLeast(100)
                                clip.copy(speed = clamped, durationMs = newDuration)
                            } else clip
                        })
                    }
                )
            ).withRecalculatedDuration()
        }
    }

    fun setClipVolume(clipId: String, volume: Float) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                when (clip) {
                    is Clip.VideoClip -> clip.copy(volume = volume.coerceIn(0f, 2f))
                    is Clip.AudioClip -> clip.copy(volume = volume.coerceIn(0f, 2f))
                    else -> clip
                }
            }
        }
    }

    fun setClipOpacity(clipId: String, opacity: Float) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.VideoClip) clip.copy(opacity = opacity.coerceIn(0f, 1f)) else clip
            }
        }
    }

    fun updateTextClip(clipId: String, newText: String) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.TextClip) clip.copy(text = newText) else clip
            }
        }
    }

    fun updateTextClipStyle(
        clipId: String,
        fontSizeSp: Float? = null,
        colorHex: String? = null,
        backgroundColorHex: String? = null,
        positionX: Float? = null,
        positionY: Float? = null,
    ) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.TextClip) {
                    clip.copy(
                        fontSizeSp = fontSizeSp ?: clip.fontSizeSp,
                        colorHex = colorHex ?: clip.colorHex,
                        backgroundColorHex = backgroundColorHex ?: clip.backgroundColorHex,
                        positionX = positionX ?: clip.positionX,
                        positionY = positionY ?: clip.positionY,
                    )
                } else clip
            }
        }
    }

    fun setTextClipDuration(clipId: String, durationMs: Long) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.TextClip) clip.copy(durationMs = durationMs.coerceAtLeast(100))
                else clip
            }.withRecalculatedDuration()
        }
    }

    fun updateFilterParams(clipId: String, filterIndex: Int, newParams: Map<String, Float>) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.VideoClip && filterIndex in clip.filters.indices) {
                    val updated = clip.filters.toMutableList()
                    updated[filterIndex] = updated[filterIndex].copy(params = newParams)
                    clip.copy(filters = updated)
                } else clip
            }
        }
    }

    fun addFilter(clipId: String, filter: VideoFilter) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.VideoClip) clip.copy(filters = clip.filters + filter) else clip
            }
        }
    }

    fun removeFilter(clipId: String, filterIndex: Int) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.VideoClip)
                    clip.copy(filters = clip.filters.filterIndexed { i, _ -> i != filterIndex })
                else clip
            }
        }
    }

    fun setTransition(clipId: String, transition: Transition?) {
        recordAndMutate { project ->
            project.withClipTransform(clipId) { clip ->
                if (clip is Clip.VideoClip) clip.copy(transition = transition) else clip
            }
        }
    }

    fun toggleTrackMute(trackId: String) {
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map {
                        if (it.id == trackId) it.copy(isMuted = !it.isMuted) else it
                    }
                )
            )
        }
    }

    fun toggleTrackLock(trackId: String) {
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map {
                        if (it.id == trackId) it.copy(isLocked = !it.isLocked) else it
                    }
                )
            )
        }
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
        if (dph < minDist) { nearest = playhead; minDist = dph }

        for (clip in project.timeline.tracks.flatMap { it.clips }) {
            if (clip.id == excludeClipId) continue
            val ds = kotlin.math.abs(ms - clip.startMs)
            if (ds < minDist) { nearest = clip.startMs; minDist = ds }
            val de = kotlin.math.abs(ms - (clip.startMs + clip.durationMs))
            if (de < minDist) { nearest = clip.startMs + clip.durationMs; minDist = de }
        }

        if (ms < snapThresholdMs) nearest = 0

        _state.update { it.copy(snapIndicatorMs = if (nearest != ms) nearest else null) }
        return nearest
    }

    fun clearSnapIndicator() {
        _state.update { it.copy(snapIndicatorMs = null) }
    }

    private fun recordAndMutate(transform: (MinterProject) -> MinterProject) {
        _state.update { state ->
            val project = state.project ?: return@update state
            undoManager.push(project)
            state.copy(
                project = transform(project),
                isDirty = true,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        }
    }

    private fun applyContinuousEdit(transform: (MinterProject) -> MinterProject) {
        _state.update { state ->
            val project = state.project ?: return@update state
            state.copy(project = transform(project), isDirty = true)
        }
    }

    private fun autoRecalcDuration() {
        _state.update { state ->
            val p = state.project ?: return@update state
            state.copy(project = p.withRecalculatedDuration())
        }
    }
}

private fun MinterProject.withRecalculatedDuration(): MinterProject {
    val maxEnd = timeline.tracks.flatMap { it.clips }
        .maxOfOrNull { it.startMs + it.durationMs } ?: 0L
    return copy(timeline = timeline.copy(durationMs = maxEnd))
}

private fun MinterProject.withClipStartMs(clipId: String, startMs: Long): MinterProject {
    return copy(
        timeline = timeline.copy(
            tracks = timeline.tracks.map { track ->
                track.copy(clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                        when (clip) {
                            is Clip.VideoClip -> clip.copy(startMs = startMs)
                            is Clip.AudioClip -> clip.copy(startMs = startMs)
                            is Clip.TextClip -> clip.copy(startMs = startMs)
                        }
                    } else clip
                })
            }
        )
    )
}

private fun MinterProject.withClipTransform(
    clipId: String,
    transform: (Clip) -> Clip,
): MinterProject {
    return copy(
        timeline = timeline.copy(
            tracks = timeline.tracks.map { track ->
                track.copy(clips = track.clips.map { clip ->
                    if (clip.id == clipId) transform(clip) else clip
                })
            }
        )
    )
}

private fun MinterProject.withClipAddedToTrack(trackId: String, clip: Clip): MinterProject {
    return copy(
        timeline = timeline.copy(
            tracks = timeline.tracks.map { t ->
                if (t.id == trackId) t.copy(clips = t.clips + clip) else t
            }
        )
    )
}
