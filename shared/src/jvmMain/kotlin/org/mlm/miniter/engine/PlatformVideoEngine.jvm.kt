package org.mlm.miniter.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mlm.miniter.rust.RustCoreSession

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    @Volatile
    private var exportCancelled = false

    actual suspend fun probeVideo(path: String): VideoInfo = withContext(Dispatchers.IO) {
        RustCoreSession.probeVideo(path)
    }

    actual suspend fun exportProjectJson(
        projectJson: String,
        outputPath: String,
    ) = withContext(Dispatchers.IO) {
        exportCancelled = false
        _exportProgress.value = ExportProgress(
            phase = "Preparing export…",
            progress = 0.08f,
        )

        try {
            ensureActive()

            _exportProgress.value = ExportProgress(
                phase = "Encoding video…",
                progress = 0.2f,
            )

            coroutineScope {
                val progressJob = launch {
                    while (isActive && !exportCancelled) {
                        val pct = RustCoreSession.exportProgress().toInt()
                        _exportProgress.value = ExportProgress(
                            phase = "Encoding video…",
                            progress = 0.2f + (pct / 100f) * 0.75f,
                        )
                        kotlinx.coroutines.delay(200)
                    }
                }

                try {
                    val ok = RustCoreSession.exportProjectJson(projectJson, outputPath)
                    progressJob.cancel()
                    ensureActive()

                    _exportProgress.value = if (ok && !exportCancelled) {
                        ExportProgress(
                            phase = "Export complete",
                            progress = 1f,
                            isComplete = true,
                        )
                    } else {
                        ExportProgress(
                            phase = "Export cancelled",
                            isCancelled = true,
                        )
                    }
                } catch (e: Exception) {
                    progressJob.cancel()
                    throw e
                }
            }
        } catch (e: CancellationException) {
            RustCoreSession.cancelExport()
            exportCancelled = true
            _exportProgress.value = ExportProgress(
                phase = "Export cancelled",
                isCancelled = true,
            )
        } catch (e: Exception) {
            val cancelled = exportCancelled ||
                (e.message?.contains("cancel", ignoreCase = true) == true)

            _exportProgress.value = if (cancelled) {
                ExportProgress(
                    phase = "Export cancelled",
                    isCancelled = true,
                )
            } else {
                ExportProgress(
                    error = e.message ?: "Export failed"
                )
            }
        }
    }

    actual suspend fun extractThumbnails(
        path: String,
        count: Int,
        width: Int,
        height: Int,
    ): List<ImageData> = withContext(Dispatchers.IO) {
        try {
            val info = RustCoreSession.probeVideo(path)
            val durationUs = info.durationMs * 1000L

            if (durationUs <= 0L) return@withContext emptyList()

            val frames = RustCoreSession.extractThumbnails(path, count, durationUs)

            frames.map { frame ->
                ensureActive()
                val rgba = frame.pixels
                val fw = frame.width
                val fh = frame.height

                if (width > 0 && height > 0 && (fw != width || fh != height)) {
                    scaleRgba(rgba, fw, fh, width, height)
                } else {
                    ImageData(fw, fh, rgba)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    actual suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
    ): ImageData? = withContext(Dispatchers.IO) {
        try {
            val frame = RustCoreSession.extractThumbnail(path, timestampMs * 1000L)
            val rgba = frame.pixels
            val fw = frame.width
            val fh = frame.height

            if (width > 0 && height > 0 && (fw != width || fh != height)) {
                scaleRgba(rgba, fw, fh, width, height)
            } else {
                ImageData(fw, fh, rgba)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    actual fun cancelExport() {
        exportCancelled = true
        RustCoreSession.cancelExport()

        val current = _exportProgress.value
        _exportProgress.value = current.copy(
            phase = "Cancelling…",
        )
    }

    actual fun reset() {
        exportCancelled = false
        _exportProgress.value = ExportProgress()
    }
}

private fun scaleRgba(
    src: ByteArray,
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
): ImageData {
    val dst = ByteArray(dstW * dstH * 4)
    for (y in 0 until dstH) {
        val srcY = y * srcH / dstH
        for (x in 0 until dstW) {
            val srcX = x * srcW / dstW
            val si = (srcY * srcW + srcX) * 4
            val di = (y * dstW + x) * 4
            dst[di] = src[si]
            dst[di + 1] = src[si + 1]
            dst[di + 2] = src[si + 2]
            dst[di + 3] = src[si + 3]
        }
    }
    return ImageData(dstW, dstH, dst)
}
