package org.mlm.miniter.engine

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual class ImageData(
    actual val width: Int,
    actual val height: Int,
    actual val pixels: ByteArray,
) {
    actual companion object {
        actual fun create(width: Int, height: Int, pixels: ByteArray): ImageData {
            return ImageData(width, height, pixels)
        }
    }
}

actual fun ImageData.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val argb = IntArray(width * height)
    for (i in argb.indices) {
        val base = i * 4
        val r = pixels[base].toInt() and 0xFF
        val g = pixels[base + 1].toInt() and 0xFF
        val b = pixels[base + 2].toInt() and 0xFF
        val a = pixels[base + 3].toInt() and 0xFF
        argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    bitmap.setPixels(argb, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

fun Bitmap.toImageData(targetWidth: Int, targetHeight: Int): ImageData {
    val scaled = if (width == targetWidth && height == targetHeight) {
        this
    } else {
        Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    val argb = IntArray(targetWidth * targetHeight)
    scaled.getPixels(argb, 0, targetWidth, 0, 0, targetWidth, targetHeight)

    val rgba = ByteArray(targetWidth * targetHeight * 4)
    for (i in argb.indices) {
        val base = i * 4
        rgba[base]     = ((argb[i] shr 16) and 0xFF).toByte()
        rgba[base + 1] = ((argb[i] shr 8) and 0xFF).toByte()
        rgba[base + 2] = (argb[i] and 0xFF).toByte()
        rgba[base + 3] = ((argb[i] shr 24) and 0xFF).toByte()
    }

    if (scaled !== this) scaled.recycle()

    return ImageData(targetWidth, targetHeight, rgba)
}
