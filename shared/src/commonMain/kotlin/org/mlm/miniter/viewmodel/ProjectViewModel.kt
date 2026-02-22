package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.engine.UndoManager
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

    private var sourceDurationMs: Long = 0L
    private var autoSaveJob: Job? = null

    // PROJECT LIFECYCLE

    fun newProject(name: String, initialVideoPath: String) {
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
                        project = project,
                        selectedTrackId = track.id,
                        isDirty = true,
                        isLoading = false,
                        canUndo = false,
                        canRedo = false,
                    )
                }
                loadThumbnails(initialVideoPath)
                recentProjectsRepository.addRecent(initialVideoPath, name)
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
                undoManager.clear()
                _state.update {
                    it.copy(
                        project = project,
                        projectPath = path,
                        playheadMs = 0,
                        isDirty = false,
                        isLoading = false,
                        canUndo = false,
                        canRedo = false,
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

    // AUTO-SAVE

    fun startAutoSave(enabled: Boolean, intervalSeconds: Float) {
        autoSaveJob?.cancel()
        if (!enabled) return
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay((intervalSeconds * 1000).toLong())
                val s = _state.value
                if (s.isDirty && s.projectPath != null && !s.isSaving) {
                    saveProject()
                }
            }
        }
    }

    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    // UNDO / REDO

    fun undo() {
        val current = _state.value.project ?: return
        val previous = undoManager.undo(current) ?: return
        _state.update {
            it.copy(
                project = previous,
                isDirty = true,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        }
    }

    fun redo() {
        val current = _state.value.project ?: return
        val next = undoManager.redo(current) ?: return
        _state.update {
            it.copy(
                project = next,
                isDirty = true,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        }
    }

    // PLAYBACK

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
        val ms = ((sliderPos / 1000f) * totalMs).toLong()
        _state.update { it.copy(playheadMs = ms.coerceIn(0, totalMs)) }
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
        val playhead = _state.value.playheadMs
        return project.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.VideoClip>()
            .firstOrNull { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
            ?.speed ?: 1f
    }

    fun getCurrentClipVolume(): Float {
        val project = _state.value.project ?: return 1f
        val playhead = _state.value.playheadMs
        return project.timeline.tracks.filter { !it.isMuted }.flatMap { it.clips }
            .filterIsInstance<Clip.VideoClip>()
            .firstOrNull { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
            ?.volume ?: 1f
    }

    fun getVisibleTextClips(): List<Clip.TextClip> {
        val project = _state.value.project ?: return emptyList()
        val playhead = _state.value.playheadMs
        return project.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.TextClip>()
            .filter { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
    }

    // IMPORT MEDIA INTO OPEN PROJECT

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
                        id = randomUuid(),
                        startMs = insertAt,
                        durationMs = info.durationMs,
                        sourcePath = sourcePath,
                        sourceEndMs = info.durationMs,
                    )
                    p.copy(
                        timeline = p.timeline.copy(
                            tracks = p.timeline.tracks.map { t ->
                                if (t.id == trackId) t.copy(clips = t.clips + clip) else t
                            },
                            durationMs = maxOf(p.timeline.durationMs, insertAt + info.durationMs),
                        )
                    )
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

                val track = _state.value.project?.timeline?.tracks?.find { it.id == audioTrackId } ?: return@launch
                val insertAt = track.clips.maxOfOrNull { it.startMs + it.durationMs } ?: 0L

                recordAndMutate { p ->
                    val clip = Clip.AudioClip(
                        id = randomUuid(),
                        startMs = insertAt,
                        durationMs = info.durationMs,
                        sourcePath = sourcePath,
                        sourceEndMs = info.durationMs,
                    )
                    p.copy(
                        timeline = p.timeline.copy(
                            tracks = p.timeline.tracks.map { t ->
                                if (t.id == audioTrackId) t.copy(clips = t.clips + clip) else t
                            },
                            durationMs = maxOf(p.timeline.durationMs, insertAt + info.durationMs),
                        )
                    )
                }
                _state.update { it.copy(isLoading = false) }
                snackbarManager.show("Audio clip added")
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                snackbarManager.showError("Failed to import audio: ${e.message}")
            }
        }
    }

    // THUMBNAILS

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

    // EXPORT

    fun exportProject(outputPath: String) {
        val project = _state.value.project ?: return
        viewModelScope.launch { engine.exportVideo(project, outputPath) }
    }
    fun cancelExport() = engine.cancelExport()
    fun resetExport() = engine.reset()

    // TRACK MANAGEMENT

    fun addTrack(type: TrackType, label: String? = null) {
        recordAndMutate { project ->
            val count = project.timeline.tracks.count { it.type == type }
            val id = "${type.name.lowercase()}-$count"
            val newTrack = Track(id = id, type = type, label = label ?: "${type.name} ${count + 1}")
            project.copy(timeline = project.timeline.copy(tracks = project.timeline.tracks + newTrack))
        }
    }

    fun removeTrack(trackId: String) {
        val project = _state.value.project ?: return
        val track = project.timeline.tracks.find { it.id == trackId } ?: return
        if (track.type == TrackType.Video && project.timeline.tracks.count { it.type == TrackType.Video } <= 1) {
            snackbarManager.showError("Cannot remove the only video track")
            return
        }
        recordAndMutate { p ->
            p.copy(timeline = p.timeline.copy(tracks = p.timeline.tracks.filter { it.id != trackId }))
        }
    }

    // CLIP OPERATIONS

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = 3000) {
        recordAndMutate { project ->
            val textTrack = project.timeline.tracks.firstOrNull { it.type == TrackType.Text }
                ?: Track(id = "text-0", type = TrackType.Text)
            val clip = Clip.TextClip(id = randomUuid(), startMs = startMs, durationMs = durationMs, text = text)
            val updatedTrack = textTrack.copy(clips = textTrack.clips + clip)
            project.copy(
                timeline = project.timeline.copy(
                    tracks = if (project.timeline.tracks.any { it.id == updatedTrack.id }) {
                        project.timeline.tracks.map { if (it.id == updatedTrack.id) updatedTrack else it }
                    } else project.timeline.tracks + updatedTrack
                )
            )
        }
    }

    fun removeClip(clipId: String) {
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { t -> t.copy(clips = t.clips.filter { it.id != clipId }) }
                )
            )
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
            )
        }
    }

    fun splitClipAtPlayhead(clipId: String) {
        val playhead = _state.value.playheadMs
        recordAndMutate { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        val clip = track.clips.find { it.id == clipId }
                        if (clip != null && clip is Clip.VideoClip && playhead > clip.startMs && playhead < clip.startMs + clip.durationMs) {
                            val splitPoint = playhead - clip.startMs
                            val firstHalf = clip.copy(durationMs = splitPoint, sourceEndMs = clip.sourceStartMs + splitPoint)
                            val secondHalf = clip.copy(id = randomUuid(), startMs = playhead, durationMs = clip.durationMs - splitPoint, sourceStartMs = clip.sourceStartMs + splitPoint)
                            track.copy(clips = track.clips.flatMap { if (it.id == clipId) listOf(firstHalf, secondHalf) else listOf(it) })
                        } else track
                    }
                )
            )
        }
    }

    fun moveClip(clipId: String, newStartMs: Long) {
        val project = _state.value.project ?: return
        val clampedStart = newStartMs.coerceAtLeast(0)

        var targetTrack: Track? = null
        var movingClip: Clip? = null
        for (track in project.timeline.tracks) {
            val c = track.clips.find { it.id == clipId }
            if (c != null) { targetTrack = track; movingClip = c; break }
        }
        if (targetTrack == null || movingClip == null) return

        val movingEnd = clampedStart + movingClip.durationMs
        val hasOverlap = targetTrack.clips.any { other ->
            if (other.id == clipId) return@any false
            val otherEnd = other.startMs + other.durationMs
            clampedStart < otherEnd && other.startMs < movingEnd
        }

        if (hasOverlap) {
            val adjusted = findNonOverlappingPosition(targetTrack, movingClip, clampedStart)
            mutateNoUndo { p ->
                p.copy(timeline = p.timeline.copy(
                    tracks = p.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId) {
                                when (clip) {
                                    is Clip.VideoClip -> clip.copy(startMs = adjusted)
                                    is Clip.AudioClip -> clip.copy(startMs = adjusted)
                                    is Clip.TextClip -> clip.copy(startMs = adjusted)
                                }
                            } else clip
                        })
                    }
                ))
            }
        } else {
            mutateNoUndo { p ->
                p.copy(timeline = p.timeline.copy(
                    tracks = p.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId) {
                                when (clip) {
                                    is Clip.VideoClip -> clip.copy(startMs = clampedStart)
                                    is Clip.AudioClip -> clip.copy(startMs = clampedStart)
                                    is Clip.TextClip -> clip.copy(startMs = clampedStart)
                                }
                            } else clip
                        })
                    }
                ))
            }
        }
    }

    fun commitMove() {
        val project = _state.value.project ?: return
        undoManager.push(project)
        syncUndoState()
    }

    private fun findNonOverlappingPosition(track: Track, clip: Clip, desiredStart: Long): Long {
        val duration = clip.durationMs
        val otherClips = track.clips.filter { it.id != clip.id }.sortedBy { it.startMs }
        var candidate = desiredStart
        for (other in otherClips) {
            val otherEnd = other.startMs + other.durationMs
            if (candidate < otherEnd && other.startMs < candidate + duration) {
                candidate = otherEnd
            }
        }
        return candidate.coerceAtLeast(0)
    }

    fun trimClipStartEdge(clipId: String, deltaMs: Long) {
        mutateNoUndo { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                val trimmed = deltaMs.coerceIn(-clip.sourceStartMs, clip.durationMs - 100)
                                clip.copy(startMs = clip.startMs + trimmed, durationMs = clip.durationMs - trimmed, sourceStartMs = clip.sourceStartMs + trimmed)
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun trimClipEndEdge(clipId: String, deltaMs: Long) {
        mutateNoUndo { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                val newDuration = (clip.durationMs + deltaMs).coerceAtLeast(100)
                                clip.copy(durationMs = newDuration, sourceEndMs = clip.sourceStartMs + newDuration)
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun commitTrim() {
        val project = _state.value.project ?: return
        undoManager.push(project)
        syncUndoState()
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.VideoClip) clip.copy(speed = speed.coerceIn(0.25f, 4.0f)) else clip
                    })
                }
            ))
        }
    }

    fun setClipVolume(clipId: String, volume: Float) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        when {
                            clip.id == clipId && clip is Clip.VideoClip -> clip.copy(volume = volume.coerceIn(0f, 2f))
                            clip.id == clipId && clip is Clip.AudioClip -> clip.copy(volume = volume.coerceIn(0f, 2f))
                            else -> clip
                        }
                    })
                }
            ))
        }
    }

    fun setClipOpacity(clipId: String, opacity: Float) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.VideoClip) clip.copy(opacity = opacity.coerceIn(0f, 1f)) else clip
                    })
                }
            ))
        }
    }

    fun updateTextClip(clipId: String, newText: String) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.TextClip) clip.copy(text = newText) else clip
                    })
                }
            ))
        }
    }

    fun updateTextClipStyle(clipId: String, fontSizeSp: Float?, colorHex: String?, backgroundColorHex: String?, positionX: Float?, positionY: Float?) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.TextClip) {
                            clip.copy(fontSizeSp = fontSizeSp ?: clip.fontSizeSp, colorHex = colorHex ?: clip.colorHex, backgroundColorHex = backgroundColorHex ?: clip.backgroundColorHex, positionX = positionX ?: clip.positionX, positionY = positionY ?: clip.positionY)
                        } else clip
                    })
                }
            ))
        }
    }

    fun addFilter(clipId: String, filter: VideoFilter) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.VideoClip) clip.copy(filters = clip.filters + filter) else clip
                    })
                }
            ))
        }
    }

    fun removeFilter(clipId: String, filterIndex: Int) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.VideoClip) clip.copy(filters = clip.filters.filterIndexed { i, _ -> i != filterIndex }) else clip
                    })
                }
            ))
        }
    }

    fun setTransition(clipId: String, transition: Transition?) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    track.copy(clips = track.clips.map { clip ->
                        if (clip.id == clipId && clip is Clip.VideoClip) clip.copy(transition = transition) else clip
                    })
                }
            ))
        }
    }

    fun toggleTrackMute(trackId: String) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { if (it.id == trackId) it.copy(isMuted = !it.isMuted) else it }
            ))
        }
    }

    fun toggleTrackLock(trackId: String) {
        recordAndMutate { project ->
            project.copy(timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { if (it.id == trackId) it.copy(isLocked = !it.isLocked) else it }
            ))
        }
    }

    fun selectClip(clipId: String?) {
        _state.update { it.copy(selectedClipId = clipId) }
    }

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoomLevel = zoom.coerceIn(0.1f, 10f)) }
    }

    fun recalculateTimelineDuration() {
        mutateNoUndo { project ->
            val maxEnd = project.timeline.tracks.flatMap { it.clips }.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
            project.copy(timeline = project.timeline.copy(durationMs = maxEnd))
        }
    }

    // SNAP

    fun snapPosition(ms: Long, excludeClipId: String? = null): Long {
        val snapThresholdMs = (200 / _state.value.zoomLevel).toLong().coerceAtLeast(50)
        val project = _state.value.project ?: return ms
        val playhead = _state.value.playheadMs

        var nearest = ms
        var minDist = snapThresholdMs

        val distToPlayhead = kotlin.math.abs(ms - playhead)
        if (distToPlayhead < minDist) { nearest = playhead; minDist = distToPlayhead }

        for (clip in project.timeline.tracks.flatMap { it.clips }) {
            if (clip.id == excludeClipId) continue
            val distToStart = kotlin.math.abs(ms - clip.startMs)
            if (distToStart < minDist) { nearest = clip.startMs; minDist = distToStart }
            val clipEnd = clip.startMs + clip.durationMs
            val distToEnd = kotlin.math.abs(ms - clipEnd)
            if (distToEnd < minDist) { nearest = clipEnd; minDist = distToEnd }
        }

        if (ms < snapThresholdMs) nearest = 0

        _state.update { it.copy(snapIndicatorMs = if (nearest != ms) nearest else null) }
        return nearest
    }

    fun clearSnapIndicator() {
        _state.update { it.copy(snapIndicatorMs = null) }
    }

    // INTERNAL MUTATION HELPERS

    private fun recordAndMutate(transform: (MinterProject) -> MinterProject) {
        _state.update { state ->
            val project = state.project ?: return@update state
            undoManager.push(project)
            val newProject = transform(project)
            state.copy(project = newProject, isDirty = true, canUndo = undoManager.canUndo, canRedo = undoManager.canRedo)
        }
    }

    private fun mutateNoUndo(transform: (MinterProject) -> MinterProject) {
        _state.update { state ->
            val project = state.project ?: return@update state
            state.copy(project = transform(project), isDirty = true)
        }
    }

    private fun syncUndoState() {
        _state.update { it.copy(canUndo = undoManager.canUndo, canRedo = undoManager.canRedo) }
    }
}
