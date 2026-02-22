package org.mlm.miniter.project

import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class MinterProject(
    val version: Int = 1,
    val name: String,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val lastModifiedAt: Long = createdAt,
    val timeline: Timeline = Timeline(),
    val exportSettings: ExportSettings = ExportSettings(),
)

@Serializable
data class Timeline(
    val tracks: List<Track> = listOf(Track(id = "video-0", type = TrackType.Video)),
    val durationMs: Long = 0L,
)

@Serializable
enum class TrackType { Video, Audio, Text }

@Serializable
data class Track(
    val id: String,
    val type: TrackType,
    val label: String = when (type) {
        TrackType.Video -> "Video"
        TrackType.Audio -> "Audio"
        TrackType.Text -> "Text"
    },
    val clips: List<Clip> = emptyList(),
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
)

@Serializable
sealed interface Clip {
    val id: String
    val startMs: Long       // position on the timeline
    val durationMs: Long    // how long it appears on the timeline

    @Serializable
    data class VideoClip(
        override val id: String,
        override val startMs: Long,
        override val durationMs: Long,
        val sourcePath: String,
        val sourceStartMs: Long = 0,
        val sourceEndMs: Long = durationMs,
        val speed: Float = 1.0f,
        val filters: List<VideoFilter> = emptyList(),
        val volume: Float = 1.0f,
        val opacity: Float = 1.0f,
        val transition: Transition? = null,
    ) : Clip

    @Serializable
    data class AudioClip(
        override val id: String,
        override val startMs: Long,
        override val durationMs: Long,
        val sourcePath: String,
        val sourceStartMs: Long = 0,
        val sourceEndMs: Long = durationMs,
        val volume: Float = 1.0f,
        val fadeInMs: Long = 0,
        val fadeOutMs: Long = 0,
    ) : Clip

    @Serializable
    data class TextClip(
        override val id: String,
        override val startMs: Long,
        override val durationMs: Long,
        val text: String,
        val fontSizeSp: Float = 24f,
        val colorHex: String = "#FFFFFF",
        val positionX: Float = 0.5f,    // 0..1 normalized
        val positionY: Float = 0.9f,
        val backgroundColorHex: String? = null,
    ) : Clip
}

@Serializable
data class VideoFilter(
    val type: FilterType,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
enum class FilterType {
    Brightness, Contrast, Saturation, Grayscale, Blur, Sharpen, Sepia
}

@Serializable
data class Transition(
    val type: TransitionType,
    val durationMs: Long = 500,
)

@Serializable
enum class TransitionType {
    CrossFade, SlideLeft, SlideRight, Dissolve
}

@Serializable
data class ExportSettings(
    val format: ExportFormat = ExportFormat.MP4,
    val quality: Float = 80f,
    val width: Int = 0,     // 0 = use source
    val height: Int = 0,
)

@Serializable
enum class ExportFormat(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    WebM("webm", "video/webm"),
    MOV("mov", "video/quicktime"),
}

@Serializable
data class RecentProject(
    val path: String,
    val name: String,
    val lastOpenedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val thumbnailPath: String? = null,
)