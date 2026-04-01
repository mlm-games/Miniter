package org.mlm.miniter.editor.model

import org.mlm.miniter.platform.randomUuid
import org.mlm.miniter.project.Clip
import org.mlm.miniter.project.ExportFormat
import org.mlm.miniter.project.ExportSettings
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.MinterProject
import org.mlm.miniter.project.Timeline
import org.mlm.miniter.project.Track
import org.mlm.miniter.project.TrackType
import org.mlm.miniter.project.Transition
import org.mlm.miniter.project.TransitionType
import org.mlm.miniter.project.VideoFilter
import kotlin.math.roundToInt

fun RustProjectSnapshot.toLegacyProject(): MinterProject {
    val tracks = timeline.tracks.map { it.toLegacyTrack() }
    val durationMs = tracks.flatMap { it.clips }.maxOfOrNull { it.startMs + it.durationMs } ?: 0L

    return MinterProject(
        version = meta.schemaVersion,
        name = meta.name,
        createdAt = meta.createdAt,
        lastModifiedAt = meta.modifiedAt,
        timeline = Timeline(
            tracks = tracks,
            durationMs = durationMs,
        ),
        exportSettings = exportProfile.toLegacyExportSettings(),
    )
}

fun MinterProject.toRustSnapshot(): RustProjectSnapshot {
    val rustTracks = timeline.tracks.map { it.toRustTrack() }
    val durationUs = (timeline.durationMs * 1000L).coerceAtLeast(
        rustTracks.flatMap { it.clips }
            .maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L
    )

    return RustProjectSnapshot(
        id = randomUuid(),
        meta = RustProjectMetaSnapshot(
            name = name,
            createdAt = createdAt,
            modifiedAt = lastModifiedAt,
            schemaVersion = maxOf(version, 2),
        ),
        timeline = RustTimelineSnapshot(tracks = rustTracks),
        exportProfile = exportSettings.toRustExportProfile(),
    ).copy(
        timeline = RustTimelineSnapshot(
            tracks = rustTracks.map { track ->
                track.copy(
                    clips = track.clips.map { clip ->
                        if (clip.sourceTotalDurationUs <= 0L) {
                            clip.copy(
                                sourceTotalDurationUs = maxOf(
                                    clip.sourceEndUs,
                                    clip.timelineStartUs + durationUs
                                )
                            )
                        } else clip
                    }
                )
            }
        )
    )
}

fun RustTrackSnapshot.toLegacyTrack(): Track {
    return Track(
        id = id,
        type = kind.toLegacyTrackType(),
        label = name,
        clips = clips.mapNotNull { it.toLegacyClip(kind) },
        isMuted = muted,
        isLocked = locked,
    )
}

fun Track.toRustTrack(): RustTrackSnapshot {
    return RustTrackSnapshot(
        id = id,
        name = label,
        kind = type.toRustTrackKind(),
        muted = isMuted,
        locked = isLocked,
        clips = clips.mapNotNull { it.toRustClip() },
    )
}

private fun RustClipSnapshot.toLegacyClip(trackKind: RustTrackKind): Clip? {
    return when (val payload = kind) {
        is RustVideoClipKind -> Clip.VideoClip(
            id = id,
            startMs = timelineStartUs / 1000L,
            durationMs = timelineDurationUs / 1000L,
            sourcePath = payload.sourcePath,
            sourceStartMs = sourceStartUs / 1000L,
            sourceEndMs = sourceEndUs / 1000L,
            sourceFileDurationMs = maxOf(sourceTotalDurationUs / 1000L, sourceEndUs / 1000L),
            speed = speed.toFloat(),
            filters = payload.filters.mapNotNull { it.toLegacyVideoFilter() },
            volume = volume,
            opacity = opacity,
            transition = transitionIn?.toLegacyTransition(),
        )

        is RustAudioClipKind -> Clip.AudioClip(
            id = id,
            startMs = timelineStartUs / 1000L,
            durationMs = timelineDurationUs / 1000L,
            sourcePath = payload.sourcePath,
            sourceStartMs = sourceStartUs / 1000L,
            sourceEndMs = sourceEndUs / 1000L,
            sourceFileDurationMs = maxOf(sourceTotalDurationUs / 1000L, sourceEndUs / 1000L),
            volume = volume,
            fadeInMs = payload.filters.filterIsInstance<RustFadeInAudioFilterSnapshot>().firstOrNull()?.durationUs?.div(1000L) ?: 0L,
            fadeOutMs = payload.filters.filterIsInstance<RustFadeOutAudioFilterSnapshot>().firstOrNull()?.durationUs?.div(1000L) ?: 0L,
        )

        is RustTextClipKind -> Clip.TextClip(
            id = id,
            startMs = timelineStartUs / 1000L,
            durationMs = timelineDurationUs / 1000L,
            text = payload.text,
            fontSizeSp = payload.style.fontSize,
            colorHex = rustColorToUi(payload.style.color),
            positionX = payload.style.positionX,
            positionY = payload.style.positionY,
            backgroundColorHex = payload.style.backgroundColor?.let(::rustColorToUi),
            isBold = payload.style.bold,
            isItalic = payload.style.italic,
            transition = transitionIn?.toLegacyTransition(),
        )
    }
}

private fun Clip.toRustClip(): RustClipSnapshot? {
    return when (this) {
        is Clip.VideoClip -> RustClipSnapshot(
            id = id,
            timelineStartUs = startMs * 1000L,
            timelineDurationUs = durationMs * 1000L,
            sourceStartUs = sourceStartMs * 1000L,
            sourceEndUs = sourceEndMs * 1000L,
            sourceTotalDurationUs = sourceFileDurationMs * 1000L,
            speed = speed.toDouble(),
            volume = volume,
            opacity = opacity,
            muted = false,
            transitionIn = transition?.toRustTransition(),
            kind = RustVideoClipKind(
                sourcePath = sourcePath,
                width = 0,
                height = 0,
                fps = 30.0,
                filters = filters.map { it.toRustVideoFilter() },
                audioFilters = emptyList(),
            ),
        )

        is Clip.AudioClip -> RustClipSnapshot(
            id = id,
            timelineStartUs = startMs * 1000L,
            timelineDurationUs = durationMs * 1000L,
            sourceStartUs = sourceStartMs * 1000L,
            sourceEndUs = sourceEndMs * 1000L,
            sourceTotalDurationUs = sourceFileDurationMs * 1000L,
            speed = 1.0,
            volume = volume,
            opacity = 1.0f,
            muted = false,
            transitionIn = null,
            kind = RustAudioClipKind(
                sourcePath = sourcePath,
                sampleRate = 44_100,
                channels = 2,
                filters = buildList {
                    if (fadeInMs > 0) add(RustFadeInAudioFilterSnapshot(fadeInMs * 1000L))
                    if (fadeOutMs > 0) add(RustFadeOutAudioFilterSnapshot(fadeOutMs * 1000L))
                },
            ),
        )

        is Clip.TextClip -> RustClipSnapshot(
            id = id,
            timelineStartUs = startMs * 1000L,
            timelineDurationUs = durationMs * 1000L,
            sourceStartUs = 0L,
            sourceEndUs = durationMs * 1000L,
            sourceTotalDurationUs = durationMs * 1000L,
            speed = 1.0,
            volume = 1.0f,
            opacity = 1.0f,
            muted = false,
            transitionIn = transition?.toRustTransition(),
            kind = RustTextClipKind(
                text = text,
                style = RustTextStyleSnapshot(
                    fontFamily = "sans-serif",
                    fontSize = fontSizeSp,
                    color = uiColorToRust(colorHex),
                    backgroundColor = backgroundColorHex?.let(::uiColorToRust),
                    alignment = RustTextAlignment.Center,
                    positionX = positionX,
                    positionY = positionY,
                    outlineColor = null,
                    outlineWidth = 0f,
                    shadow = false,
                    bold = isBold,
                    italic = isItalic,
                ),
            ),
        )
    }
}

private fun RustExportProfileSnapshot.toLegacyExportSettings(): ExportSettings {
    val (width, height) = when (val res = resolution) {
        RustExportResolution.Sd480 -> 854 to 480
        RustExportResolution.Hd720 -> 1280 to 720
        RustExportResolution.Hd1080 -> 1920 to 1080
        RustExportResolution.Uhd4k -> 3840 to 2160
        is RustExportResolution.Custom -> res.width to res.height
    }

    val quality = ((videoBitrateKbps.toFloat() - 500f) / 8000f * 100f)
        .coerceIn(1f, 100f)
        .roundToInt()
        .toFloat()

    return ExportSettings(
        format = format.toLegacyExportFormat(),
        quality = quality,
        width = width,
        height = height,
    )
}

private fun ExportSettings.toRustExportProfile(): RustExportProfileSnapshot {
    return RustExportProfileSnapshot(
        format = format.toRustExportFormat(),
        resolution = if (width > 0 && height > 0) {
            when {
                width == 854 && height == 480 -> RustExportResolution.Sd480
                width == 1280 && height == 720 -> RustExportResolution.Hd720
                width == 1920 && height == 1080 -> RustExportResolution.Hd1080
                width == 3840 && height == 2160 -> RustExportResolution.Uhd4k
                else -> RustExportResolution.Custom(width, height)
            }
        } else {
            RustExportResolution.Hd1080
        },
        fps = 30.0,
        videoBitrateKbps = (500 + quality * 80).roundToInt().coerceAtLeast(500),
        audioBitrateKbps = 192,
        audioSampleRate = 48_000,
        outputPath = "",
    )
}

private fun RustTransitionSnapshot.toLegacyTransition(): Transition {
    return Transition(
        type = kind.toLegacyTransitionType(),
        durationMs = duration / 1000L,
    )
}

private fun Transition.toRustTransition(): RustTransitionSnapshot {
    return RustTransitionSnapshot(
        kind = type.toRustTransitionKind(),
        duration = durationMs * 1000L,
    )
}

private fun RustVideoFilterSnapshot.toLegacyVideoFilter(): VideoFilter? {
    return when (this) {
        is RustBrightnessFilterSnapshot -> VideoFilter(FilterType.Brightness, mapOf("value" to value))
        is RustContrastFilterSnapshot -> VideoFilter(FilterType.Contrast, mapOf("value" to value))
        is RustSaturationFilterSnapshot -> VideoFilter(FilterType.Saturation, mapOf("value" to value))
        RustGrayscaleFilterSnapshot -> VideoFilter(FilterType.Grayscale)
        is RustBlurFilterSnapshot -> VideoFilter(FilterType.Blur, mapOf("radius" to radius))
        is RustSharpenFilterSnapshot -> VideoFilter(FilterType.Sharpen, mapOf("amount" to amount))
        RustSepiaFilterSnapshot -> VideoFilter(FilterType.Sepia)
        else -> null
    }
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

private fun RustTrackKind.toLegacyTrackType(): TrackType = when (this) {
    RustTrackKind.Video -> TrackType.Video
    RustTrackKind.Audio -> TrackType.Audio
    RustTrackKind.Text -> TrackType.Text
}

private fun TrackType.toRustTrackKind(): RustTrackKind = when (this) {
    TrackType.Video -> RustTrackKind.Video
    TrackType.Audio -> RustTrackKind.Audio
    TrackType.Text -> RustTrackKind.Text
}

private fun RustExportFormat.toLegacyExportFormat(): ExportFormat = when (this) {
    RustExportFormat.Mp4 -> ExportFormat.MP4
    RustExportFormat.WebM -> ExportFormat.WebM
    RustExportFormat.Mov -> ExportFormat.MOV
}

private fun ExportFormat.toRustExportFormat(): RustExportFormat = when (this) {
    ExportFormat.MP4 -> RustExportFormat.Mp4
    ExportFormat.WebM -> RustExportFormat.WebM
    ExportFormat.MOV -> RustExportFormat.Mov
}

private fun RustTransitionKind.toLegacyTransitionType(): TransitionType = when (this) {
    RustTransitionKind.CrossFade -> TransitionType.CrossFade
    RustTransitionKind.SlideLeft -> TransitionType.SlideLeft
    RustTransitionKind.SlideRight -> TransitionType.SlideRight
    RustTransitionKind.Dissolve -> TransitionType.Dissolve
}

private fun TransitionType.toRustTransitionKind(): RustTransitionKind = when (this) {
    TransitionType.CrossFade -> RustTransitionKind.CrossFade
    TransitionType.SlideLeft -> RustTransitionKind.SlideLeft
    TransitionType.SlideRight -> RustTransitionKind.SlideRight
    TransitionType.Dissolve -> RustTransitionKind.Dissolve
}

fun uiColorToRust(hex: String): String {
    val raw = hex.removePrefix("#").uppercase()
    return when (raw.length) {
        6 -> "FF$raw"
        8 -> raw
        else -> "FFFFFFFF"
    }
}

fun rustColorToUi(value: String): String {
    val raw = value.removePrefix("#").uppercase()
    return when (raw.length) {
        8 -> "#${raw.substring(2)}"
        6 -> "#$raw"
        else -> "#FFFFFF"
    }
}