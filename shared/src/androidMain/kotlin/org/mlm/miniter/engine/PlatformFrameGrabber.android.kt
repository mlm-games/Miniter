package org.mlm.miniter.engine

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mlm.miniter.platform.AndroidContext
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.VideoFilter

actual class PlatformFrameGrabber {

    private var retriever: MediaMetadataRetriever? = null
    private val mutex = Mutex()
    private var currentPath: String? = null

    actual suspend fun open(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (currentPath == path) return@withContext

            retriever?.release()
            retriever = MediaMetadataRetriever().apply {
                if (path.startsWith("content://")) {
                    setDataSource(AndroidContext.get(), Uri.parse(path))
                } else {
                    setDataSource(path)
                }
            }
            currentPath = path
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
            val r = retriever ?: return@withContext null

            val bitmap = r.getFrameAtTime(
                timestampMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return@withContext null

            var resultBitmap = bitmap

            if (filters.isNotEmpty() || opacity < 1f) {
                resultBitmap = applyFilters(bitmap, filters, opacity)
                if (resultBitmap != bitmap) bitmap.recycle()
            }

            val finalWidth = if (width > 0) width else resultBitmap.width
            val finalHeight = if (height > 0) height else resultBitmap.height

            val scaled = if (resultBitmap.width != finalWidth || resultBitmap.height != finalHeight) {
                val s = Bitmap.createScaledBitmap(resultBitmap, finalWidth, finalHeight, true)
                if (resultBitmap != bitmap) resultBitmap.recycle()
                s
            } else resultBitmap

            val imageData = scaled.toImageData()
            scaled.recycle()

            imageData
        }
    }

    actual fun release() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                retriever?.release()
                retriever = null
                currentPath = null
            }
        }
    }
}

private fun Bitmap.toImageData(): ImageData {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)

    val rgba = ByteArray(width * height * 4)
    for (i in argb.indices) {
        val base = i * 4
        rgba[base]     = ((argb[i] shr 16) and 0xFF).toByte()
        rgba[base + 1] = ((argb[i] shr 8) and 0xFF).toByte()
        rgba[base + 2] = (argb[i] and 0xFF).toByte()
        rgba[base + 3] = ((argb[i] shr 24) and 0xFF).toByte()
    }

    return ImageData(width, height, rgba)
}

private fun applyFilters(
    source: Bitmap,
    filters: List<VideoFilter>,
    opacity: Float,
): Bitmap {
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

private fun adjustBrightness(img: Bitmap, amount: Float): Bitmap {
    val result = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
    val offset = (amount * 255).toInt()
    val pixels = IntArray(img.width * img.height)
    img.getPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val a = (argb shr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF) + offset
        val g = ((argb shr 8) and 0xFF) + offset
        val b = (argb and 0xFF) + offset
        pixels[i] = (a shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
    }
    result.setPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    return result
}

private fun adjustContrast(img: Bitmap, factor: Float): Bitmap {
    val result = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(img.width * img.height)
    img.getPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val a = (argb shr 24) and 0xFF
        val r = (((((argb shr 16) and 0xFF) - 128) * factor) + 128).toInt()
        val g = (((((argb shr 8) and 0xFF) - 128) * factor) + 128).toInt()
        val b = ((((argb and 0xFF) - 128) * factor) + 128).toInt()
        pixels[i] = (a shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
    }
    result.setPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    return result
}

private fun adjustSaturation(img: Bitmap, factor: Float): Bitmap {
    val result = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(img.width * img.height)
    img.getPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val a = (argb shr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val gray = 0.299f * r + 0.587f * g + 0.114f * b
        val nr = (gray + (r - gray) * factor).toInt().coerceIn(0, 255)
        val ng = (gray + (g - gray) * factor).toInt().coerceIn(0, 255)
        val nb = (gray + (b - gray) * factor).toInt().coerceIn(0, 255)
        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    result.setPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    return result
}

private fun toGrayscale(img: Bitmap): Bitmap {
    return adjustSaturation(img, 0f)
}

private fun toSepia(img: Bitmap): Bitmap {
    val result = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(img.width * img.height)
    img.getPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val a = (argb shr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val nr = (0.393f * r + 0.769f * g + 0.189f * b).toInt().coerceIn(0, 255)
        val ng = (0.349f * r + 0.686f * g + 0.168f * b).toInt().coerceIn(0, 255)
        val nb = (0.272f * r + 0.534f * g + 0.131f * b).toInt().coerceIn(0, 255)
        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    result.setPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    return result
}

private fun boxBlur(img: Bitmap, radius: Int): Bitmap {
    val result = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
    val srcPixels = IntArray(img.width * img.height)
    val dstPixels = IntArray(img.width * img.height)
    img.getPixels(srcPixels, 0, img.width, 0, 0, img.width, img.height)
    val r = radius.coerceIn(1, 10)
    val w = img.width
    val h = img.height

    for (y in 0 until h) {
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val argb = srcPixels[ny * w + nx]
                    rSum += (argb shr 16) and 0xFF
                    gSum += (argb shr 8) and 0xFF
                    bSum += argb and 0xFF
                    count++
                }
            }
            val a = (srcPixels[y * w + x] shr 24) and 0xFF
            dstPixels[y * w + x] = (a shl 24) or
                    ((rSum / count) shl 16) or
                    ((gSum / count) shl 8) or
                    (bSum / count)
        }
    }
    result.setPixels(dstPixels, 0, w, 0, 0, w, h)
    return result
}

private fun applyOpacity(img: Bitmap, opacity: Float): Bitmap {
    val result = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(img.width * img.height)
    img.getPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    val op = (opacity * 255).toInt().coerceIn(0, 255)
    for (i in pixels.indices) {
        val argb = pixels[i]
        val origA = (argb shr 24) and 0xFF
        val newA = (origA * op / 255).coerceIn(0, 255)
        pixels[i] = (newA shl 24) or (argb and 0x00FFFFFF)
    }
    result.setPixels(pixels, 0, img.width, 0, 0, img.width, img.height)
    return result
}
