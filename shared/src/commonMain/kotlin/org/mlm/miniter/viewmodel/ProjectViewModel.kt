package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.PlatformVideoEngine
import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.*

data class ProjectUiState(
    val project: MinterProject? = null,
    val projectPath: String? = null,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val zoomLevel: Float = 1f,
    val thumbnails: List<ImageData> = emptyList(),
    val isLoadingThumbnails: Boolean = false,
)

class ProjectViewModel(
    private val projectRepository: ProjectRepository,
    private val engine: PlatformVideoEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state

    val exportProgress = engine.exportProgress

    private var sourceDurationMs: Long = 0L

    fun newProject(name: String, initialVideoPath: String) {
        viewModelScope.launch {
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
                val track = Track(
                    id = "video-0",
                    type = TrackType.Video,
                    clips = listOf(clip),
                )
                val project = MinterProject(
                    name = name,
                    timeline = Timeline(
                        tracks = listOf(track),
                        durationMs = info.durationMs,
                    ),
                )
                _state.update {
                    it.copy(
                        project = project,
                        selectedTrackId = track.id,
                        isDirty = true,
                    )
                }
                loadThumbnails(initialVideoPath)
            } catch (e: Exception) {
                val clip = Clip.VideoClip(
                    id = randomUuid(),
                    startMs = 0,
                    durationMs = 0,
                    sourcePath = initialVideoPath,
                )
                val track = Track(
                    id = "video-0",
                    type = TrackType.Video,
                    clips = listOf(clip),
                )
                _state.update {
                    it.copy(
                        project = MinterProject(
                            name = name,
                            timeline = Timeline(tracks = listOf(track)),
                        ),
                        selectedTrackId = track.id,
                        isDirty = true,
                    )
                }
            }
        }
    }

    fun loadProject(path: String) {
        viewModelScope.launch {
            try {
                val project = projectRepository.load(path)
                _state.update {
                    it.copy(
                        project = project,
                        projectPath = path,
                        playheadMs = 0,
                        isDirty = false,
                    )
                }
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
            } catch (_: Exception) { }
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
            } catch (_: Exception) {
                _state.update { it.copy(isSaving = false) }
            }
        }
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
        return project.timeline.tracks
            .flatMap { it.clips }
            .filterIsInstance<Clip.VideoClip>()
            .firstOrNull { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
            ?.speed ?: 1f
    }

    fun getCurrentClipVolume(): Float {
        val project = _state.value.project ?: return 1f
        val playhead = _state.value.playheadMs
        return project.timeline.tracks
            .filter { !it.isMuted }
            .flatMap { it.clips }
            .filterIsInstance<Clip.VideoClip>()
            .firstOrNull { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
            ?.volume ?: 1f
    }

    fun getVisibleTextClips(): List<Clip.TextClip> {
        val project = _state.value.project ?: return emptyList()
        val playhead = _state.value.playheadMs
        return project.timeline.tracks
            .flatMap { it.clips }
            .filterIsInstance<Clip.TextClip>()
            .filter { playhead >= it.startMs && playhead < it.startMs + it.durationMs }
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

    fun addVideoClip(trackId: String, sourcePath: String, atMs: Long) {
        viewModelScope.launch {
            try {
                val info = engine.probeVideo(sourcePath)
                mutateProject { project ->
                    val clip = Clip.VideoClip(
                        id = randomUuid(),
                        startMs = atMs,
                        durationMs = info.durationMs,
                        sourcePath = sourcePath,
                        sourceEndMs = info.durationMs,
                    )
                    project.copy(
                        timeline = project.timeline.copy(
                            tracks = project.timeline.tracks.map { track ->
                                if (track.id == trackId) track.copy(clips = track.clips + clip)
                                else track
                            },
                            durationMs = maxOf(project.timeline.durationMs, atMs + info.durationMs),
                        )
                    )
                }
            } catch (_: Exception) { }
        }
    }

    fun addAudioTrack() {
        mutateProject { project ->
            val newTrack = Track(
                id = "audio-${project.timeline.tracks.count { it.type == TrackType.Audio }}",
                type = TrackType.Audio,
            )
            project.copy(timeline = project.timeline.copy(tracks = project.timeline.tracks + newTrack))
        }
    }

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = 3000) {
        mutateProject { project ->
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
                    } else {
                        project.timeline.tracks + updatedTrack
                    }
                )
            )
        }
    }

    fun removeClip(clipId: String) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.filter { it.id != clipId })
                    }
                )
            )
        }
    }

    fun trimClip(clipId: String, newStartMs: Long, newDurationMs: Long) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                clip.copy(
                                    startMs = newStartMs,
                                    durationMs = newDurationMs,
                                    sourceEndMs = clip.sourceStartMs + newDurationMs,
                                )
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun splitClipAtPlayhead(clipId: String) {
        val playhead = _state.value.playheadMs
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        val clip = track.clips.find { it.id == clipId }
                        if (clip != null && clip is Clip.VideoClip
                            && playhead > clip.startMs
                            && playhead < clip.startMs + clip.durationMs
                        ) {
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
                        } else track
                    }
                )
            )
        }
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip)
                                clip.copy(speed = speed.coerceIn(0.25f, 4.0f))
                            else clip
                        })
                    }
                )
            )
        }
    }

    fun setClipVolume(clipId: String, volume: Float) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            when {
                                clip.id == clipId && clip is Clip.VideoClip ->
                                    clip.copy(volume = volume.coerceIn(0f, 2f))
                                clip.id == clipId && clip is Clip.AudioClip ->
                                    clip.copy(volume = volume.coerceIn(0f, 2f))
                                else -> clip
                            }
                        })
                    }
                )
            )
        }
    }

    fun updateTextClip(clipId: String, newText: String) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.TextClip)
                                clip.copy(text = newText)
                            else clip
                        })
                    }
                )
            )
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
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.TextClip) {
                                clip.copy(
                                    fontSizeSp = fontSizeSp ?: clip.fontSizeSp,
                                    colorHex = colorHex ?: clip.colorHex,
                                    backgroundColorHex = backgroundColorHex ?: clip.backgroundColorHex,
                                    positionX = positionX ?: clip.positionX,
                                    positionY = positionY ?: clip.positionY,
                                )
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun addFilter(clipId: String, filter: VideoFilter) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip)
                                clip.copy(filters = clip.filters + filter)
                            else clip
                        })
                    }
                )
            )
        }
    }

    fun removeFilter(clipId: String, filterIndex: Int) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip)
                                clip.copy(filters = clip.filters.filterIndexed { i, _ -> i != filterIndex })
                            else clip
                        })
                    }
                )
            )
        }
    }

    fun setTransition(clipId: String, transition: Transition?) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip)
                                clip.copy(transition = transition)
                            else clip
                        })
                    }
                )
            )
        }
    }

    fun moveClip(clipId: String, newStartMs: Long) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId) {
                                when (clip) {
                                    is Clip.VideoClip -> clip.copy(startMs = newStartMs.coerceAtLeast(0))
                                    is Clip.AudioClip -> clip.copy(startMs = newStartMs.coerceAtLeast(0))
                                    is Clip.TextClip -> clip.copy(startMs = newStartMs.coerceAtLeast(0))
                                }
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun toggleTrackMute(trackId: String) {
        mutateProject { project ->
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
        mutateProject { project ->
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

    // ── Track management ──

    fun addTrack(type: TrackType, label: String? = null) {
        mutateProject { project ->
            val count = project.timeline.tracks.count { it.type == type }
            val id = "${type.name.lowercase()}-$count"
            val newTrack = Track(
                id = id,
                type = type,
                label = label ?: "${type.name} ${count + 1}",
            )
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks + newTrack
                )
            )
        }
    }

    fun removeTrack(trackId: String) {
        mutateProject { project ->
            val track = project.timeline.tracks.find { it.id == trackId } ?: return@mutateProject project
            if (track.type == TrackType.Video && project.timeline.tracks.count { it.type == TrackType.Video } <= 1) {
                return@mutateProject project
            }
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.filter { it.id != trackId }
                )
            )
        }
    }

    fun duplicateClip(clipId: String) {
        mutateProject { project ->
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

    fun trimClipStartEdge(clipId: String, deltaMs: Long) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                val trimmed = deltaMs.coerceIn(-clip.sourceStartMs, clip.durationMs - 100)
                                clip.copy(
                                    startMs = clip.startMs + trimmed,
                                    durationMs = clip.durationMs - trimmed,
                                    sourceStartMs = clip.sourceStartMs + trimmed,
                                )
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun trimClipEndEdge(clipId: String, deltaMs: Long) {
        mutateProject { project ->
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        track.copy(clips = track.clips.map { clip ->
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                val newDuration = (clip.durationMs + deltaMs).coerceAtLeast(100)
                                val newSourceEnd = clip.sourceStartMs + newDuration
                                clip.copy(durationMs = newDuration, sourceEndMs = newSourceEnd)
                            } else clip
                        })
                    }
                )
            )
        }
    }

    fun recalculateTimelineDuration() {
        mutateProject { project ->
            val maxEnd = project.timeline.tracks.flatMap { it.clips }.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
            project.copy(timeline = project.timeline.copy(durationMs = maxEnd))
        }
    }

    fun snapPosition(ms: Long, excludeClipId: String? = null): Long {
        val snapThresholdMs = (200 / _state.value.zoomLevel).toLong().coerceAtLeast(50)
        val project = _state.value.project ?: return ms
        val playhead = _state.value.playheadMs

        var nearest = ms
        var minDist = snapThresholdMs

        val distToPlayhead = kotlin.math.abs(ms - playhead)
        if (distToPlayhead < minDist) {
            nearest = playhead
            minDist = distToPlayhead
        }

        for (clip in project.timeline.tracks.flatMap { it.clips }) {
            if (clip.id == excludeClipId) continue
            val distToStart = kotlin.math.abs(ms - clip.startMs)
            if (distToStart < minDist) {
                nearest = clip.startMs
                minDist = distToStart
            }
            val clipEnd = clip.startMs + clip.durationMs
            val distToEnd = kotlin.math.abs(ms - clipEnd)
            if (distToEnd < minDist) {
                nearest = clipEnd
                minDist = distToEnd
            }
        }

        if (ms < snapThresholdMs) nearest = 0
        return nearest
    }

    private fun mutateProject(transform: (MinterProject) -> MinterProject) {
        _state.update { state ->
            val project = state.project ?: return@update state
            state.copy(project = transform(project), isDirty = true)
        }
    }
}
