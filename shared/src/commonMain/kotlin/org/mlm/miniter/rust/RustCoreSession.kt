package org.mlm.miniter.rust

import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.VideoInfo

expect class RustCoreSession {
    constructor(projectName: String)

    fun toJson(): String
    fun dispatch(commandJson: String): Boolean

    fun undo(): Boolean
    fun redo(): Boolean
    fun canUndo(): Boolean
    fun canRedo(): Boolean

    fun playheadUs(): Long
    fun setPlayheadUs(us: Long)

    fun renderPlanAtPlayhead(width: Int, height: Int): String
    fun durationUs(): Long

    companion object {
        fun fromJson(json: String): RustCoreSession
        fun probeAudio(path: String): String
        fun extractWaveform(path: String, buckets: Int): String
        fun probeVideo(path: String): VideoInfo
        fun extractThumbnail(path: String, timestampUs: Long): ImageData
        fun extractThumbnails(path: String, count: Int, durationUs: Long): List<ImageData>
        fun exportProjectJson(projectJson: String, outputPath: String): Boolean
        fun cancelExport()
    }
}
