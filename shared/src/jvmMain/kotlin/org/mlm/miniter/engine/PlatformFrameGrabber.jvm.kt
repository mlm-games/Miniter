package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Java2DFrameConverter
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.VideoFilter
import java.awt.image.BufferedImage

actual class PlatformFrameGrabber {

    private var grabber: FFmpegFrameGrabber? = null
    private val converter = Java2DFrameConverter()
    private val mutex = Mutex()
    private var currentPath: String? = null

    init {
        try { FFmpegLogCallback.set() } catch (_: Exception) {}
    }

    actual suspend fun open(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (currentPath == path) return@withContext

            val oldGrabber = grabber
            grabber = null
            currentPath = null

            try { oldGrabber?.stop() } catch (_: Exception) {}
            try { oldGrabber?.release() } catch (_: Exception) {}

            try {
                val newGrabber = FFmpegFrameGrabber(path)
                newGrabber.start()
                grabber = newGrabber
                currentPath = path
            } catch (e: Exception) {
                System.err.println("PlatformFrameGrabber.open failed for '$path': ${e.message}")
                grabber = null
            }
        }
    }

    actual suspend fun grabFrame(
        timestampMs: Long,
        filters: List<VideoFilter>,
        opacity: Float,
        width: Int,
        height: Int,
    ): ImageData? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val g = grabber ?: return@withContext null

            try {
                g.setTimestamp(timestampMs * 1000L)
                val frame = g.grabImage() ?: return@withContext null

                var image = converter.convert(frame) ?: return@withContext null

                image = applyFilters(image, filters, opacity)

                if (width > 0 && height > 0 && (image.width != width || image.height != height)) {
                    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val g2d = scaled.createGraphics()
                    g2d.drawImage(image, 0, 0, width, height, null)
                    g2d.dispose()
                    image = scaled
                }

                val w = image.width
                val h = image.height
                val rgba = ByteArray(w * h * 4)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val argb = image.getRGB(x, y)
                        val idx = (y * w + x) * 4
                        rgba[idx]     = ((argb shr 16) and 0xFF).toByte()
                        rgba[idx + 1] = ((argb shr 8) and 0xFF).toByte()
                        rgba[idx + 2] = (argb and 0xFF).toByte()
                        rgba[idx + 3] = ((argb shr 24) and 0xFF).toByte()
                    }
                }

                ImageData.create(w, h, rgba)
            } catch (e: Exception) {
                null
            }
        }
    }

    actual fun release() {
        runBlocking {
            mutex.withLock {
                try { grabber?.stop() } catch (_: Exception) {}
                try { grabber?.release() } catch (_: Exception) {}
                grabber = null
                currentPath = null
            }
        }
    }
}

private fun applyFilters(
    source: BufferedImage,
    filters: List<VideoFilter>,
    opacity: Float,
): BufferedImage {
    var img = source

    for (filter in filters) {
        img = when (filter.type) {
            FilterType.Brightness -> {
                val v = (filter.params["value"] ?: 0f) / 100f
                adjustBrightness(img, v)
            }
            FilterType.Contrast -> {
                val v = filter.params["value"] ?: 1f
                adjustContrast(img, v)
            }
            FilterType.Saturation -> {
                val v = filter.params["value"] ?: 1f
                adjustSaturation(img, v)
            }
            FilterType.Grayscale -> toGrayscale(img)
            FilterType.Sepia -> toSepia(img)
            FilterType.Blur -> {
                val r = (filter.params["radius"] ?: 5f).toInt()
                boxBlur(img, r)
            }
            FilterType.Sharpen -> img
        }
    }

    if (opacity < 1f) {
        img = applyOpacity(img, opacity)
    }

    return img
}

private fun adjustBrightness(img: BufferedImage, amount: Float): BufferedImage {
    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    val offset = (amount * 255).toInt()
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val a = (argb shr 24) and 0xFF
            val r = ((argb shr 16) and 0xFF) + offset
            val g = ((argb shr 8) and 0xFF) + offset
            val b = (argb and 0xFF) + offset
            result.setRGB(x, y,
                (a shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
            )
        }
    }
    return result
}

private fun adjustContrast(img: BufferedImage, factor: Float): BufferedImage {
    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val a = (argb shr 24) and 0xFF
            val r = (((((argb shr 16) and 0xFF) - 128) * factor) + 128).toInt()
            val g = (((((argb shr 8) and 0xFF) - 128) * factor) + 128).toInt()
            val b = ((((argb and 0xFF) - 128) * factor) + 128).toInt()
            result.setRGB(x, y,
                (a shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
            )
        }
    }
    return result
}

private fun adjustSaturation(img: BufferedImage, factor: Float): BufferedImage {
    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val a = (argb shr 24) and 0xFF
            val r = ((argb shr 16) and 0xFF).toFloat()
            val g = ((argb shr 8) and 0xFF).toFloat()
            val b = (argb and 0xFF).toFloat()
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = (gray + (r - gray) * factor).toInt().coerceIn(0, 255)
            val ng = (gray + (g - gray) * factor).toInt().coerceIn(0, 255)
            val nb = (gray + (b - gray) * factor).toInt().coerceIn(0, 255)
            result.setRGB(x, y, (a shl 24) or (nr shl 16) or (ng shl 8) or nb)
        }
    }
    return result
}

private fun toGrayscale(img: BufferedImage): BufferedImage = adjustSaturation(img, 0f)

private fun toSepia(img: BufferedImage): BufferedImage {
    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val a = (argb shr 24) and 0xFF
            val r = ((argb shr 16) and 0xFF).toFloat()
            val g = ((argb shr 8) and 0xFF).toFloat()
            val b = (argb and 0xFF).toFloat()
            val nr = (0.393f * r + 0.769f * g + 0.189f * b).toInt().coerceIn(0, 255)
            val ng = (0.349f * r + 0.686f * g + 0.168f * b).toInt().coerceIn(0, 255)
            val nb = (0.272f * r + 0.534f * g + 0.131f * b).toInt().coerceIn(0, 255)
            result.setRGB(x, y, (a shl 24) or (nr shl 16) or (ng shl 8) or nb)
        }
    }
    return result
}

private fun boxBlur(img: BufferedImage, radius: Int): BufferedImage {
    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    val r = radius.coerceIn(1, 10)
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val nx = (x + dx).coerceIn(0, img.width - 1)
                    val ny = (y + dy).coerceIn(0, img.height - 1)
                    val argb = img.getRGB(nx, ny)
                    rSum += (argb shr 16) and 0xFF
                    gSum += (argb shr 8) and 0xFF
                    bSum += argb and 0xFF
                    count++
                }
            }
            val a = (img.getRGB(x, y) shr 24) and 0xFF
            result.setRGB(x, y,
                (a shl 24) or
                ((rSum / count) shl 16) or
                ((gSum / count) shl 8) or
                (bSum / count)
            )
        }
    }
    return result
}

private fun applyOpacity(img: BufferedImage, opacity: Float): BufferedImage {
    val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
    val op = (opacity * 255).toInt().coerceIn(0, 255)
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val argb = img.getRGB(x, y)
            val origA = (argb shr 24) and 0xFF
            val newA = (origA * op / 255).coerceIn(0, 255)
            result.setRGB(x, y, (newA shl 24) or (argb and 0x00FFFFFF))
        }
    }
    return result
}
