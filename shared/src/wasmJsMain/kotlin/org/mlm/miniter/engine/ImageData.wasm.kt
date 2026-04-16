package org.mlm.miniter.engine

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

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
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)

    val expected = safeWidth * safeHeight * 4
    val rgba = if (pixels.size == expected) {
        pixels
    } else {
        ByteArray(expected)
    }

    val bitmap = Bitmap()
    val info = ImageInfo(
        safeWidth,
        safeHeight,
        ColorType.RGBA_8888,
        ColorAlphaType.UNPREMUL,
    )
    bitmap.allocPixels(info)
    bitmap.installPixels(info, rgba, safeWidth * 4)

    return bitmap.asComposeImageBitmap()
}
