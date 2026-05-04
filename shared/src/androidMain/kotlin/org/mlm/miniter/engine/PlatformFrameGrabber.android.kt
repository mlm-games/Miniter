package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.rust.RustCoreSession

actual class PlatformFrameGrabber {

    private val mutex = Mutex()
    private var currentPath: String? = null

    actual suspend fun open(path: String) {
        val localPath = PlatformFileSystem.stageForNativeAccess(path)
        mutex.withLock {
            currentPath = localPath
        }
    }

    actual suspend fun grabFrame(
        timestampMs: Long,
        filters: List<RustVideoFilterSnapshot>,
        opacity: Float,
        width: Int,
        height: Int,
    ): ImageData? = withContext(Dispatchers.IO) {
        val path = mutex.withLock { currentPath } ?: return@withContext null

        try {
            val frame = RustCoreSession.extractThumbnail(path, timestampMs * 1000L)
            var rgba = frame.pixels
            var fw = frame.width
            var fh = frame.height

            if (filters.isNotEmpty() || opacity < 1f) {
                rgba = FrameFilterProcessors.applyFilters(rgba, fw, fh, filters, opacity)
            }

            val finalW = if (width > 0) width else fw
            val finalH = if (height > 0) height else fh
            if (fw != finalW || fh != finalH) {
                return@withContext scaleRgbaToImageData(rgba, fw, fh, finalW, finalH)
            }

            ImageData(fw, fh, rgba)
        } catch (e: Exception) {
            null
        }
    }

    actual fun release() {
        currentPath = null
    }
}

private fun scaleRgbaToImageData(
    src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int
): ImageData {
    val dst = ByteArray(dstW * dstH * 4)
    for (y in 0 until dstH) {
        val srcY = y * srcH / dstH
        for (x in 0 until dstW) {
            val srcX = x * srcW / dstW
            val si = (srcY * srcW + srcX) * 4
            val di = (y * dstW + x) * 4
            dst[di] = src[si]; dst[di + 1] = src[si + 1]
            dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
        }
    }
    return ImageData(dstW, dstH, dst)
}