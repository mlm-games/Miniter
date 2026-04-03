package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustSepiaFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.rust.RustCoreSession

actual class PlatformFrameGrabber {

    private val mutex = Mutex()
    private var currentPath: String? = null

    actual suspend fun open(path: String) {
        mutex.withLock {
            currentPath = path
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
                rgba = applyFiltersToRgba(rgba, fw, fh, filters, opacity)
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

private fun applyFiltersToRgba(
    src: ByteArray,
    width: Int,
    height: Int,
    filters: List<RustVideoFilterSnapshot>,
    opacity: Float,
): ByteArray {
    val pixels = src.copyOf()
    val total = width * height

    for (filter in filters) {
        when (filter) {
            is RustBrightnessFilterSnapshot -> {
                val offset = ((filter.value) / 100f * 255f).toInt()
                for (i in 0 until total) {
                    val b = i * 4
                    pixels[b] = (pixels[b].toInt().and(0xFF) + offset).coerceIn(0, 255).toByte()
                    pixels[b + 1] = (pixels[b + 1].toInt().and(0xFF) + offset).coerceIn(0, 255).toByte()
                    pixels[b + 2] = (pixels[b + 2].toInt().and(0xFF) + offset).coerceIn(0, 255).toByte()
                }
            }
            is RustContrastFilterSnapshot -> {
                val factor = filter.value
                for (i in 0 until total) {
                    val b = i * 4
                    pixels[b] = (((pixels[b].toInt().and(0xFF) - 128) * factor) + 128).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 1] = (((pixels[b + 1].toInt().and(0xFF) - 128) * factor) + 128).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 2] = (((pixels[b + 2].toInt().and(0xFF) - 128) * factor) + 128).toInt().coerceIn(0, 255).toByte()
                }
            }
            is RustSaturationFilterSnapshot -> {
                val factor = filter.value
                for (i in 0 until total) {
                    val b = i * 4
                    val r = pixels[b].toInt().and(0xFF).toFloat()
                    val g = pixels[b + 1].toInt().and(0xFF).toFloat()
                    val bl = pixels[b + 2].toInt().and(0xFF).toFloat()
                    val gray = 0.299f * r + 0.587f * g + 0.114f * bl
                    pixels[b] = (gray + (r - gray) * factor).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 1] = (gray + (g - gray) * factor).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 2] = (gray + (bl - gray) * factor).toInt().coerceIn(0, 255).toByte()
                }
            }
            RustGrayscaleFilterSnapshot -> {
                for (i in 0 until total) {
                    val b = i * 4
                    val r = pixels[b].toInt().and(0xFF).toFloat()
                    val g = pixels[b + 1].toInt().and(0xFF).toFloat()
                    val bl = pixels[b + 2].toInt().and(0xFF).toFloat()
                    val gray = (0.299f * r + 0.587f * g + 0.114f * bl).toInt().coerceIn(0, 255).toByte()
                    pixels[b] = gray; pixels[b + 1] = gray; pixels[b + 2] = gray
                }
            }
            RustSepiaFilterSnapshot -> {
                for (i in 0 until total) {
                    val b = i * 4
                    val r = pixels[b].toInt().and(0xFF).toFloat()
                    val g = pixels[b + 1].toInt().and(0xFF).toFloat()
                    val bl = pixels[b + 2].toInt().and(0xFF).toFloat()
                    pixels[b] = (0.393f * r + 0.769f * g + 0.189f * bl).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 1] = (0.349f * r + 0.686f * g + 0.168f * bl).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 2] = (0.272f * r + 0.534f * g + 0.131f * bl).toInt().coerceIn(0, 255).toByte()
                }
            }
            is RustBlurFilterSnapshot, is RustSharpenFilterSnapshot -> { }
            else -> { }
        }
    }

    if (opacity < 1f) {
        val op = (opacity * 255f).toInt().coerceIn(0, 255)
        for (i in 0 until total) {
            val b = i * 4 + 3
            val origA = pixels[b].toInt().and(0xFF)
            pixels[b] = (origA * op / 255).coerceIn(0, 255).toByte()
        }
    }

    return pixels
}
