package org.mlm.miniter.engine

import kotlinx.coroutines.flow.StateFlow

expect class PlatformVideoEngine() {

    val exportProgress: StateFlow<ExportProgress>

    suspend fun probeVideo(path: String): VideoInfo

    suspend fun exportProjectJson(
        projectJson: String,
        outputPath: String,
    )

    suspend fun extractThumbnails(
        path: String,
        count: Int,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean = true,
    ): List<ImageData>

    suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean = true,
    ): ThumbnailResult

    fun cancelExport()

    /**
     * Land the progress in the terminal cancelled state. Called when the job
     * driving the export is cancelled before the renderer takes over, which is
     * the one path where nothing else would ever write that state and the UI
     * would sit on "Cancelling…" forever.
     */
    fun markCancelled()

    fun reset()

    /** Enter an owned Preparing state before any suspension; ignored while a job is active. */
    fun notePreparing(outputPath: String)
}
