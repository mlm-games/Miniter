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
import org.mlm.miniter.editor.model.RustFlipFilterSnapshot
import org.mlm.miniter.editor.model.RustHueFilterSnapshot
import org.mlm.miniter.editor.model.RustRotateFilterSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustCropFilterSnapshot
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.rust.RustCoreSession
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.PI

actual class PlatformFrameGrabber actual constructor() {
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
    ): ImageData? = withContext(Dispatchers.Default) {
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

            ImageData.create(fw, fh, rgba)
        } catch (_: Throwable) {
            null
        }
    }

    actual fun release() {
        currentPath = null
    }
}

private fun scaleRgbaToImageData(
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

private fun applyFiltersToRgba(
    src: ByteArray,
    width: Int,
    height: Int,
    filters: List<RustVideoFilterSnapshot>,
    opacity: Float,
): ByteArray {
    var pixels = src.copyOf()
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
                    pixels[b] = gray
                    pixels[b + 1] = gray
                    pixels[b + 2] = gray
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
            is RustHueFilterSnapshot -> {
                val angle = (((filter.degrees % 360f) * PI.toFloat() / 180f))
                val cosA = cos(angle.toDouble()).toFloat()
                val sinA = sin(angle.toDouble()).toFloat()
                for (i in 0 until total) {
                    val b = i * 4
                    val r = pixels[b].toInt().and(0xFF) / 255f
                    val g = pixels[b + 1].toInt().and(0xFF) / 255f
                    val bl = pixels[b + 2].toInt().and(0xFF) / 255f
                    val nr = (0.299f + 0.701f * cosA + 0.168f * sinA) * r +
                            (0.587f - 0.587f * cosA + 0.330f * sinA) * g +
                            (0.114f - 0.114f * cosA - 0.497f * sinA) * bl
                    val ng = (0.299f - 0.299f * cosA - 0.328f * sinA) * r +
                            (0.587f + 0.413f * cosA + 0.035f * sinA) * g +
                            (0.114f - 0.114f * cosA + 0.292f * sinA) * bl
                    val nb = (0.299f - 0.300f * cosA + 1.250f * sinA) * r +
                            (0.587f - 0.588f * cosA - 1.050f * sinA) * g +
                            (0.114f + 0.886f * cosA - 0.203f * sinA) * bl
                    pixels[b] = (nr.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 1] = (ng.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255).toByte()
                    pixels[b + 2] = (nb.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255).toByte()
                }
            }
            is RustFlipFilterSnapshot -> {
                if (filter.horizontal || filter.vertical) {
                    val stride = width * 4
                    for (row in 0 until height) {
                        val srcRow = if (filter.vertical) height - 1 - row else row
                        for (col in 0 until width) {
                            val srcCol = if (filter.horizontal) width - 1 - col else col
                            val di = row * stride + col * 4
                            val si = srcRow * stride + srcCol * 4
                            pixels[di] = src[si]; pixels[di + 1] = src[si + 1]
                            pixels[di + 2] = src[si + 2]; pixels[di + 3] = src[si + 3]
                        }
                    }
                }
            }
            is RustRotateFilterSnapshot -> {
                if (abs(filter.degrees) >= 0.5f) {
                    pixels = applyRotateRgba(pixels, width, height, filter.degrees)
                }
            }
            is RustTransformFilterSnapshot -> {
                if (abs(filter.scale - 1f) >= 1e-6f || abs(filter.translateX) >= 1e-6f || abs(filter.translateY) >= 1e-6f || abs(filter.rotate) >= 1e-6f) {
                    pixels = applyTransformRgba(pixels, width, height, filter.scale, filter.translateX, filter.translateY, filter.rotate)
                }
            }
            is RustCropFilterSnapshot -> {
                pixels = applyCropRgba(pixels, width, height, filter.left, filter.top, filter.right, filter.bottom)
            }
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

private fun applyRotateRgba(src: ByteArray, w: Int, h: Int, deg: Float): ByteArray {
    if (w == 0 || h == 0) return src
    val rad = (deg.toDouble() * PI / 180.0).toFloat()
    val sin = sin(rad.toDouble()).toFloat()
    val cos = cos(rad.toDouble()).toFloat()
    val dst = ByteArray(src.size)
    val cx = w / 2f
    val cy = h / 2f

    for (y in 0 until h) {
        for (x in 0 until w) {
            val px = (x - cx) * cos - (y - cy) * sin
            val py = (x - cx) * sin + (y - cy) * cos
            val sx = (px + cx).toInt().coerceIn(0, w - 1)
            val sy = (py + cy).toInt().coerceIn(0, h - 1)
            val si = sy * w * 4 + sx * 4
            val di = y * w * 4 + x * 4
            dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
        }
    }
    return dst
}

private fun applyTransformRgba(src: ByteArray, w: Int, h: Int, scale: Float, tx: Float, ty: Float, rotate: Float): ByteArray {
    if (w == 0 || h == 0) return src
    val zoom = scale.coerceIn(0.05f, 50f)
    val rad = (rotate.toDouble() * PI / 180.0).toFloat()
    val cosR = cos(rad.toDouble()).toFloat()
    val sinR = sin(rad.toDouble()).toFloat()
    val dst = ByteArray(src.size)
    val cx = 0.5f
    val cy = 0.5f

    for (yd in 0 until h) {
        for (xd in 0 until w) {
            var u = (xd.toFloat() + 0.5f) / w
            var v = (yd.toFloat() + 0.5f) / h
            val px = (u - cx) * w
            val py = (v - cy) * h
            val rx = px * cosR + py * sinR
            val ry = -px * sinR + py * cosR
            u = rx / w + cx
            v = ry / h + cy
            u = u - 0.5f - tx
            v = v - 0.5f - ty
            u = (u + 0.5f) * zoom
            v = (v + 0.5f) * zoom
            u += 0.5f
            v += 0.5f
            val sx = (u * w - 0.5f).toInt().coerceIn(0, w - 1)
            val sy = (v * h - 0.5f).toInt().coerceIn(0, h - 1)
            val si = sy * w * 4 + sx * 4
            val di = yd * w * 4 + xd * 4
            dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
        }
    }
    return dst
}

private fun applyCropRgba(src: ByteArray, w: Int, h: Int, left: Float, top: Float, right: Float, bottom: Float): ByteArray {
    if (w == 0 || h == 0) return src
    val l = (left.coerceIn(0f, 1f) * w).toInt()
    val t = (top.coerceIn(0f, 1f) * h).toInt()
    val r = w - (right.coerceIn(0f, 1f) * w).toInt()
    val b = h - (bottom.coerceIn(0f, 1f) * h).toInt()
    if (r <= l || b <= t) return src

    val dst = ByteArray(r * b * 4)
    for (y in t until b) {
        for (x in l until r) {
            val si = y * w * 4 + x * 4
            val di = (y - t) * r * 4 + (x - l) * 4
            dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
        }
    }
    return dst
}
