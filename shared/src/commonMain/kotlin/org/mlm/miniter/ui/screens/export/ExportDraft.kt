package org.mlm.miniter.ui.screens.export

import org.mlm.miniter.editor.model.RustExportProfileSnapshot
import org.mlm.miniter.editor.model.RustExportResolution

data class ExportDraft(
    val widthText: String = "",
    val heightText: String = "",
    val fpsText: String = "30",
    val videoBitrateKbpsText: String = "8000",
    val audioBitrateText: String = "192",
    val audioSampleRate: Int = 48_000,
) {
    data class Parsed(
        val width: Int,
        val height: Int,
        val fps: Double,
        val videoBitrateKbps: Int,
        val audioBitrateKbps: Int,
    )

    data class Validation(
        val parsed: Parsed?,
        val errors: Map<String, String>,
    )

    fun validate(audioOnly: Boolean = false): Validation {
        val errors = mutableMapOf<String, String>()

        var evenWidth = 0
        var evenHeight = 0
        var fps: Double? = null
        var videoBitrate: Int? = null
        if (!audioOnly) {
            val rawWidth = widthText.trim()
            val rawHeight = heightText.trim()
            val width = rawWidth.toIntOrNull()
            val height = rawHeight.toIntOrNull()
            if (rawWidth.isEmpty() && rawHeight.isEmpty()) {
            } else if (rawWidth.isEmpty() || rawHeight.isEmpty()) {
                errors["resolution"] = "Enter both width and height, or clear both for source."
            } else if (width == null || height == null || width <= 0 || height <= 0) {
                errors["resolution"] = "Resolution must be positive whole numbers."
            } else if (width > 7680 || height > 7680) {
                errors["resolution"] = "Resolution is capped at 7680×7680."
            }
            evenWidth = if (width != null && width > 0) (width / 2) * 2 else 0
            evenHeight = if (height != null && height > 0) (height / 2) * 2 else 0

            fps = fpsText.trim().toDoubleOrNull()
            if (fps == null || fps !in 1.0..240.0) {
                errors["fps"] = "FPS must be between 1 and 240."
            }

            videoBitrate = videoBitrateKbpsText.trim().toIntOrNull()
            if (videoBitrate == null || videoBitrate < 500) {
                errors["videoBitrate"] = "Video bitrate must be at least 500 kbps."
            } else if (videoBitrate > 100_000) {
                errors["videoBitrate"] = "Video bitrate is capped at 100,000 kbps."
            }
        }

        val audioBitrate = audioBitrateText.trim().toIntOrNull()
        if (audioBitrate == null || audioBitrate !in 32..510) {
            errors["audioBitrate"] = "Audio bitrate must be 32–510 kbps."
        }

        val parsed = if (errors.isEmpty()) {
            Parsed(
                width = evenWidth,
                height = evenHeight,
                fps = fps ?: 30.0,
                videoBitrateKbps = videoBitrate ?: 8000,
                audioBitrateKbps = audioBitrate!!,
            )
        } else {
            null
        }
        return Validation(parsed, errors)
    }
}

fun ExportDraft.applyTo(
    profile: RustExportProfileSnapshot,
    sourceWidth: Int,
    sourceHeight: Int,
    audioOnly: Boolean = false,
): RustExportProfileSnapshot? {
    val parsed = validate(audioOnly).parsed ?: return null
    return profile.copy(
        resolution = when {
            parsed.width <= 0 || parsed.height <= 0 -> RustExportResolution.Source
            sourceWidth > 0 && sourceHeight > 0 &&
                parsed.width == sourceWidth && parsed.height == sourceHeight ->
                RustExportResolution.Source
            parsed.width == 854 && parsed.height == 480 -> RustExportResolution.Sd480
            parsed.width == 1280 && parsed.height == 720 -> RustExportResolution.Hd720
            parsed.width == 1920 && parsed.height == 1080 -> RustExportResolution.Hd1080
            parsed.width == 3840 && parsed.height == 2160 -> RustExportResolution.Uhd4k
            else -> RustExportResolution.Custom(parsed.width, parsed.height)
        },
        fps = parsed.fps,
        videoBitrateKbps = parsed.videoBitrateKbps,
        audioBitrateKbps = parsed.audioBitrateKbps,
    )
}

fun resolutionToTexts(
    resolution: RustExportResolution,
    sourceWidth: Int,
    sourceHeight: Int,
): Pair<String, String> {
    val (w, h) = when (resolution) {
        RustExportResolution.Source -> sourceWidth to sourceHeight
        RustExportResolution.Sd480 -> 854 to 480
        RustExportResolution.Hd720 -> 1280 to 720
        RustExportResolution.Hd1080 -> 1920 to 1080
        RustExportResolution.Uhd4k -> 3840 to 2160
        is RustExportResolution.Custom -> resolution.width to resolution.height
    }
    return (w.takeIf { it > 0 }?.toString() ?: "") to (h.takeIf { it > 0 }?.toString() ?: "")
}

fun effectiveResolutionText(
    widthText: String,
    heightText: String,
    sourceWidth: Int,
    sourceHeight: Int,
    isAudioOnly: Boolean,
): String {
    if (isAudioOnly) return "Audio only"
    val w = widthText.trim().toIntOrNull()?.takeIf { it > 0 } ?: sourceWidth.takeIf { it > 0 }
    val h = heightText.trim().toIntOrNull()?.takeIf { it > 0 } ?: sourceHeight.takeIf { it > 0 }
    return if (w != null && h != null) "${w}×${h}" else "Source"
}
