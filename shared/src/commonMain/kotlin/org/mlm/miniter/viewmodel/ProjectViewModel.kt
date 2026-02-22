package org.mlm.miniter.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mlm.miniter.project.*
import java.util.UUID

data class ProjectUiState(
    val project: MinterProject? = null,
    val projectPath: String? = null,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val zoomLevel: Float = 1f,      // pixels per ms
)

class ProjectViewModel(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state

    fun newProject(name: String, initialVideoPath: String) {
        viewModelScope.launch {
            val clip = Clip.VideoClip(
                id = uuid(),
                startMs = 0,
                durationMs = 0, // will be updated when we probe
                sourcePath = initialVideoPath,
            )
            val track = Track(
                id = "video-0",
                type = TrackType.Video,
                clips = listOf(clip),
            )
            val project = MinterProject(
                name = name,
                timeline = Timeline(tracks = listOf(track)),
            )
            _state.update {
                it.copy(
                    project = project,
                    selectedTrackId = track.id,
                    isDirty = true,
                )
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
            } catch (e: Exception) {
                // Handle via snackbar
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
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    // ── Timeline manipulation ──

    fun addVideoClip(trackId: String, sourcePath: String, atMs: Long, durationMs: Long) {
        mutateProject { project ->
            val clip = Clip.VideoClip(
                id = uuid(),
                startMs = atMs,
                durationMs = durationMs,
                sourcePath = sourcePath,
                sourceEndMs = durationMs,
            )
            project.copy(
                timeline = project.timeline.copy(
                    tracks = project.timeline.tracks.map { track ->
                        if (track.id == trackId) track.copy(clips = track.clips + clip)
                        else track
                    }
                )
            )
        }
    }

    fun addAudioTrack() {
        mutateProject { project ->
            val newTrack = Track(
                id = "audio-${project.timeline.tracks.count { it.type == TrackType.Audio }}",
                type = TrackType.Audio,
            )
            project.copy(
                timeline = project.timeline.copy(tracks = project.timeline.tracks + newTrack)
            )
        }
    }

    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long = 3000) {
        mutateProject { project ->
            val textTrack = project.timeline.tracks.firstOrNull { it.type == TrackType.Text }
                ?: Track(id = "text-0", type = TrackType.Text)

            val clip = Clip.TextClip(
                id = uuid(),
                startMs = startMs,
                durationMs = durationMs,
                text = text,
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
                                id = uuid(),
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
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                clip.copy(speed = speed.coerceIn(0.25f, 4.0f))
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
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                clip.copy(filters = clip.filters + filter)
                            } else clip
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
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                clip.copy(filters = clip.filters.filterIndexed { i, _ -> i != filterIndex })
                            } else clip
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
                            if (clip.id == clipId && clip is Clip.VideoClip) {
                                clip.copy(transition = transition)
                            } else clip
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

    fun setPlayhead(ms: Long) {
        _state.update { it.copy(playheadMs = ms.coerceAtLeast(0)) }
    }

    fun setPlaying(playing: Boolean) {
        _state.update { it.copy(isPlaying = playing) }
    }

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoomLevel = zoom.coerceIn(0.1f, 10f)) }
    }

    private fun mutateProject(transform: (MinterProject) -> MinterProject) {
        _state.update { state ->
            val project = state.project ?: return@update state
            state.copy(project = transform(project), isDirty = true)
        }
    }

    private fun uuid(): String = UUID.randomUUID().toString()
}