package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.mlm.miniter.project.*
import org.mlm.miniter.ffi.probeVideo as nativeProbeVideo
import org.mlm.miniter.ffi.extractThumbnail as nativeExtractThumbnail
import org.mlm.miniter.ffi.extractThumbnails as nativeExtractThumbnails
import org.mlm.miniter.ffi.exportProjectJson as nativeExportProjectJson
import org.mlm.miniter.ffi.cancelExport as nativeCancelExport
import org.mlm.miniter.platform.AndroidContext

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    @Volatile
    private var exportCancelled = false

    actual suspend fun probeVideo(path: String): VideoInfo = withContext(Dispatchers.IO) {
        val result = nativeProbeVideo(path)
        VideoInfo(
            durationMs = result.durationUs / 1000L,
            width = result.width.toInt(),
            height = result.height.toInt(),
            frameRate = result.frameRate,
            videoBitrate = result.videoBitrate.toInt(),
            audioChannels = result.audioChannels.toInt(),
            audioSampleRate = result.audioSampleRate.toInt(),
            audioCodecName = null,
            videoCodecName = result.videoCodec,
            hasAudio = result.hasAudio,
            hasVideo = result.width > 0u && result.height > 0u,
        )
    }

    actual suspend fun exportVideo(
        project: MinterProject,
        outputPath: String,
    ) = withContext(Dispatchers.IO) {
        _exportProgress.value = ExportProgress(
            error = "Export not yet implemented in pure-Rust pipeline. " +
                    "Use the Rust render plan + OpenH264 encoder."
        )
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

            val ok = nativeExportProjectJson(projectJson, outputPath)
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
            val info = nativeProbeVideo(path)
            val durationUs = info.durationUs

            if (durationUs <= 0L) return@withContext emptyList()

            val frames = nativeExtractThumbnails(path, count.toUInt(), durationUs)

            frames.map { frame ->
                ensureActive()
                val rgba = frame.rgba.toList().map { it.toByte() }.toByteArray()
                val fw = frame.width.toInt()
                val fh = frame.height.toInt()

                if (width > 0 && height > 0 && (fw != width || fh != height)) {
                    scaleRgba(rgba, fw, fh, width, height)
                } else {
                    ImageData(fw, fh, rgba)
                }
            }
        } catch (e: Exception) {
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
            val frame = nativeExtractThumbnail(path, timestampMs * 1000L)
            val rgba = frame.rgba.toList().map { it.toByte() }.toByteArray()
            val fw = frame.width.toInt()
            val fh = frame.height.toInt()

            if (width > 0 && height > 0 && (fw != width || fh != height)) {
                scaleRgba(rgba, fw, fh, width, height)
            } else {
                ImageData(fw, fh, rgba)
            }
        } catch (e: Exception) {
            null
        }
    }

    actual fun cancelExport() {
        exportCancelled = true
        nativeCancelExport()

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