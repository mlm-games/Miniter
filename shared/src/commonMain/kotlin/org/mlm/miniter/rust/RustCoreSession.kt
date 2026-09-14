package org.mlm.miniter.rust

import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.VideoInfo


data class BeatTrack(
    val onsetsMs: List<Long> = emptyList(),
    val windowEnergy: List<Float> = emptyList(),
    val windowMs: Int = 20,
) {
    /** Snap [timeMs] to the nearest onset within [toleranceMs], if any. */
    fun snap(timeMs: Long, toleranceMs: Long): Long? =
        onsetsMs
            .map { it to kotlin.math.abs(it - timeMs) }
            .filter { (_, dist) -> dist <= toleranceMs }
            .minByOrNull { (_, dist) -> dist }
            ?.first
}

expect val isWebCodecsHardwareAccelerated: Boolean

expect val supportedHwCodecs: List<String>

expect class RustCoreSession {
    constructor(projectName: String)

    fun toJson(): String
    fun dispatch(commandJson: String): Boolean
    fun dispatchWithLabel(commandJson: String, label: String): Boolean
    fun beginEdit(label: String)
    fun dispatchOpen(commandJson: String): Boolean
    fun commitEdit()
    fun cancelEdit(): Boolean

    fun undo(): Boolean
    fun redo(): Boolean
    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun undoLabel(): String?
    fun redoLabel(): String?
    fun undoDepth(): UInt
    fun redoDepth(): UInt
    fun transactionOpen(): Boolean

    fun playheadUs(): Long
    fun setPlayheadUs(us: Long)

    fun renderPlanAtPlayhead(width: Int, height: Int): String
    fun validateRenderPlanAtPlayhead(width: Int, height: Int): String
    fun durationUs(): Long

    companion object {
        fun fromJson(json: String): RustCoreSession
        fun probeAudio(path: String): String
        fun extractWaveform(path: String, buckets: Int): String
        fun detectBeats(path: String): BeatTrack
        fun probeVideo(path: String): VideoInfo
        suspend fun extractThumbnail(path: String, timestampUs: Long, hardwareAcceleration: Boolean): ImageData
        suspend fun extractThumbnails(path: String, count: Int, durationUs: Long, hardwareAcceleration: Boolean): List<ImageData>
        fun exportProjectJson(projectJson: String, outputPath: String): Boolean
        fun cancelExport()
        fun exportProgress(): UInt
        fun exportPreviewFrame(): ImageData?
        fun clearExportPreview()
        fun wasExportHardwareAccelerated(): Boolean
        fun subtitleTextAt(path: String, timestampUs: Long): String?
    }
}
