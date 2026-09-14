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
        RustExportFormat.Flac -> ExportCapabilities(
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
        RustExportFormat.Flac -> "FLAC"
    }

val RustExportFormat.fileExtension: String
    get() = when (this) {
        RustExportFormat.Mp4 -> "mp4"
        RustExportFormat.Av1Ivf -> "ivf"
        RustExportFormat.Av1Mp4 -> "mp4"
        RustExportFormat.Mov -> "mov"
        RustExportFormat.Opus -> "ogg"
        RustExportFormat.Flac -> "flac"
    }
