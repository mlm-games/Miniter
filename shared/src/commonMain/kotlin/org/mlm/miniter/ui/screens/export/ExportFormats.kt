package org.mlm.miniter.ui.screens.export

import org.mlm.miniter.editor.model.RustExportFormat

/** Container/encoder capabilities used by the export UI. */
data class ExportCapabilities(
    val supportsAudio: Boolean,
    val supportsEmbeddedSubtitles: Boolean,
    val supportsBurnedInSubtitles: Boolean,
    val stylingWarning: Boolean,
)

val RustExportFormat.capabilities: ExportCapabilities
    get() = when (this) {
        RustExportFormat.Mp4 -> ExportCapabilities(
            supportsAudio = true,
            supportsEmbeddedSubtitles = true,
            supportsBurnedInSubtitles = true,
            stylingWarning = true,
        )
        RustExportFormat.Av1Mp4 -> ExportCapabilities(
            supportsAudio = true,
            supportsEmbeddedSubtitles = true,
            supportsBurnedInSubtitles = true,
            stylingWarning = true,
        )
        RustExportFormat.Av1Ivf -> ExportCapabilities(
            supportsAudio = false,
            supportsEmbeddedSubtitles = false,
            supportsBurnedInSubtitles = true,
            stylingWarning = false,
        )
        RustExportFormat.Mov -> ExportCapabilities(
            supportsAudio = true,
            supportsEmbeddedSubtitles = false,
            supportsBurnedInSubtitles = true,
            stylingWarning = false,
        )
        RustExportFormat.Opus -> ExportCapabilities(
            supportsAudio = true,
            supportsEmbeddedSubtitles = false,
            supportsBurnedInSubtitles = false,
            stylingWarning = false,
        )
    }

val RustExportFormat.label: String
    get() = when (this) {
        RustExportFormat.Mp4 -> "H.264 / MP4"
        RustExportFormat.Av1Mp4 -> "AV1 / MP4"
        RustExportFormat.Av1Ivf -> "AV1 / IVF"
        RustExportFormat.Mov -> "H.264 / MOV"
        RustExportFormat.Opus -> "Opus / Ogg"
    }

val RustExportFormat.description: String
    get() = when (this) {
        RustExportFormat.Mp4 -> "Most compatible video export."
        RustExportFormat.Av1Mp4 -> "Smaller files with modern AV1 video."
        RustExportFormat.Av1Ivf -> "Raw AV1 video stream; audio and embedded subtitles are not included."
        RustExportFormat.Mov -> "QuickTime-compatible H.264 video."
        RustExportFormat.Opus -> "Audio-only Ogg Opus export."
    }

val RustExportFormat.fileExtension: String
    get() = when (this) {
        RustExportFormat.Mp4 -> "mp4"
        RustExportFormat.Av1Ivf -> "ivf"
        RustExportFormat.Av1Mp4 -> "mp4"
        RustExportFormat.Mov -> "mov"
        RustExportFormat.Opus -> "ogg"
    }
