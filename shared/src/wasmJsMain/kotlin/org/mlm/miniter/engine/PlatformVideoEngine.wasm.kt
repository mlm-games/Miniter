package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.rust.RustCoreSession
import org.mlm.miniter.rust.WasmExportSession
import kotlin.js.JsAny

private val engineJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ChunkResponse(
    val ok: Boolean,
    val done: Boolean = false,
    val progress: UInt = 0u,
    val payload: ChunkPayload? = null,
    val error: String? = null,
    val hardwareFallback: Boolean = false,
)

@Serializable
private data class ChunkPayload(
    val ok: Boolean,
    val bytesBase64: String,
    val fileName: String,
    val mimeType: String,
)

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    private var exportCancelled = false
    private var activeSession: WasmExportSession? = null

    actual suspend fun probeVideo(path: String): VideoInfo {
        return withContext(Dispatchers.Default) {
            RustCoreSession.probeVideo(PlatformFileSystem.stageForNativeAccess(path))
        }
    }

    actual suspend fun exportProjectJson(
        projectJson: String,
        outputPath: String,
    ) {
        exportCancelled = false
        _exportProgress.value = ExportProgress(
            phase = "Encoding video…",
            progress = 0f,
        )

        try {
            val session = WasmExportSession(projectJson, outputPath)
            activeSession = session

            while (true) {
                if (exportCancelled) {
                    session.cancel()
                    _exportProgress.value = ExportProgress(
                        phase = "Export cancelled",
                        isCancelled = true,
                    )
                    return
                }

                val responseJson = session.processChunk().await<JsAny?>()?.toString() ?: ""
                val response = engineJson.decodeFromString<ChunkResponse>(responseJson)

                if (!response.ok) {
                    val cancelled = exportCancelled ||
                        (response.error?.contains("cancel", ignoreCase = true) == true)
                    _exportProgress.value = if (cancelled) {
                        ExportProgress(phase = "Export cancelled", isCancelled = true)
                    } else {
                        ExportProgress(error = response.error ?: "Export failed")
                    }
                    return
                }

                val previewFrame = RustCoreSession.exportPreviewFrame()
                _exportProgress.value = ExportProgress(
                    phase = "Encoding video…",
                    progress = (response.progress.toFloat() / 100_000f).coerceIn(0f, 1f),
                    previewFrame = previewFrame,
                    hardwareFallback = response.hardwareFallback,
                )

                if (response.done) {
                    val payload = response.payload!!
                        if (payload.ok && !exportCancelled) {
                        wasmDownloadBlob(payload.fileName, payload.mimeType, payload.bytesBase64)
                        _exportProgress.value = ExportProgress(
                            phase = "Export complete",
                            progress = 1f,
                            isComplete = true,
                            hardwareFallback = response.hardwareFallback,
                        )
                    } else {
                        _exportProgress.value = ExportProgress(
                            phase = "Export cancelled",
                            isCancelled = true,
                        )
                    }
                    return
                }

                kotlinx.coroutines.delay(0)
            }
        } catch (e: Throwable) {
            println("Export failed: $e")
            val cancelled = exportCancelled ||
                e.message?.contains("cancel", ignoreCase = true) == true

            _exportProgress.value = if (cancelled) {
                ExportProgress(phase = "Export cancelled", isCancelled = true)
            } else {
                val detail = e.message?.removePrefix("detail=") ?: e.toString()
                ExportProgress(error = detail)
            }
        } finally {
            activeSession = null
        }
    }

    actual suspend fun extractThumbnails(
        path: String,
        count: Int,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
    ): List<ImageData> = withContext(Dispatchers.Default) {
        val localPath = PlatformFileSystem.stageForNativeAccess(path)
        val info = RustCoreSession.probeVideo(localPath)
        val durationUs = info.durationMs * 1000L
        if (durationUs <= 0L) return@withContext emptyList()

        RustCoreSession.extractThumbnails(localPath, count, durationUs, hardwareAcceleration).map { frame ->
            val fw = frame.width
            val fh = frame.height
            if (width > 0 && height > 0 && (fw != width || fh != height)) {
                scaleRgba(frame.pixels, fw, fh, width, height)
            } else {
                frame
            }
        }
    }

    actual suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
    ): ThumbnailResult = withContext(Dispatchers.Default) {
        try {
            val localPath = PlatformFileSystem.stageForNativeAccess(path)
            val frame = RustCoreSession.extractThumbnail(localPath, timestampMs * 1000L, hardwareAcceleration)
            val fw = frame.width
            val fh = frame.height
            val image = if (width > 0 && height > 0 && (fw != width || fh != height)) {
                scaleRgba(frame.pixels, fw, fh, width, height)
            } else {
                frame
            }
            ThumbnailResult.Success(image)
        } catch (e: Throwable) {
            println("extractSingleThumbnail failed: $e")
            ThumbnailResult.Error(e.message?.removePrefix("detail=") ?: e.toString())
        }
    }

    actual fun cancelExport() {
        exportCancelled = true
        activeSession?.cancel()
        _exportProgress.value = ExportProgress(
            phase = "Cancelled",
            isCancelled = true,
        )
    }

    actual fun reset() {
        exportCancelled = false
        activeSession = null
        _exportProgress.value = ExportProgress()
    }
}

@JsFun("""(fileName, mimeType, bytesBase64) => {
    const byteChars = atob(bytesBase64);
    const byteNums = new Uint8Array(byteChars.length);
    for (let i = 0; i < byteChars.length; i++) {
        byteNums[i] = byteChars.charCodeAt(i);
    }
    const blob = new Blob([byteNums], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
}""")
private external fun wasmDownloadBlob(fileName: String, mimeType: String, bytesBase64: String)

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
