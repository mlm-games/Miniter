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
    ): List<ImageData>

    suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
    ): ImageData?

    fun cancelExport()

    fun reset()
}
