package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.rust.RustCoreSession

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    private var exportCancelled = false

    actual suspend fun probeVideo(path: String): VideoInfo {
        return withContext(Dispatchers.Default) {
            RustCoreSession.probeVideo(PlatformFileSystem.stageForNativeAccess(path))
        }
    }

    actual suspend fun exportProjectJson(
        projectJson: String,
        outputPath: String,
    ) {
        withContext(Dispatchers.Default) {
            exportCancelled = false
            _exportProgress.value = ExportProgress(
                phase = "Encoding video…",
                progress = 0f,
            )

            try {
                coroutineScope {
                    val progressJob = launch {
                        while (isActive && !exportCancelled) {
                            val pct = RustCoreSession.exportProgress().toInt()
                            _exportProgress.value = ExportProgress(
                                phase = "Encoding video…",
                                progress = (pct / 100_000f).coerceIn(0f, 1f),
                            )
                            kotlinx.coroutines.delay(100)
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
                    } catch (e: Throwable) {
                        progressJob.cancel()
                        throw e
                    }
                }
            } catch (e: Throwable) {
                val cancelled = exportCancelled || e.message?.contains("cancel", ignoreCase = true) == true

                _exportProgress.value = if (cancelled) {
                    ExportProgress(
                        phase = "Export cancelled",
                        isCancelled = true,
                    )
                } else {
                    ExportProgress(
                        error = e.message ?: "Export failed",
                    )
                }
            }
        }
    }

    actual suspend fun extractThumbnails(
        path: String,
        count: Int,
        width: Int,
        height: Int,
    ): List<ImageData> = withContext(Dispatchers.Default) {
        try {
            val localPath = PlatformFileSystem.stageForNativeAccess(path)
            val info = RustCoreSession.probeVideo(localPath)
            val durationUs = info.durationMs * 1000L
            if (durationUs <= 0L) return@withContext emptyList()

            RustCoreSession.extractThumbnails(localPath, count, durationUs).map { frame ->
                val fw = frame.width
                val fh = frame.height
                if (width > 0 && height > 0 && (fw != width || fh != height)) {
                    scaleRgba(frame.pixels, fw, fh, width, height)
                } else {
                    frame
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    actual suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
    ): ImageData? = withContext(Dispatchers.Default) {
        try {
            val localPath = PlatformFileSystem.stageForNativeAccess(path)
            val frame = RustCoreSession.extractThumbnail(localPath, timestampMs * 1000L)
            val fw = frame.width
            val fh = frame.height
            if (width > 0 && height > 0 && (fw != width || fh != height)) {
                scaleRgba(frame.pixels, fw, fh, width, height)
            } else {
                frame
            }
        } catch (_: Throwable) {
            null
        }
    }

    actual fun cancelExport() {
        exportCancelled = true
        RustCoreSession.cancelExport()
        _exportProgress.value = ExportProgress(
            phase = "Cancelled",
            isCancelled = true,
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
    return ImageData.create(dstW, dstH, dst)
}
