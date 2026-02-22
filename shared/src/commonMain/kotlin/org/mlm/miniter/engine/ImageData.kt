package org.mlm.miniter.engine

import androidx.compose.ui.graphics.ImageBitmap

expect class ImageData {
    val width: Int
    val height: Int
    val pixels: ByteArray

    companion object {
        fun create(width: Int, height: Int, pixels: ByteArray): ImageData
    }
}

expect fun ImageData.toImageBitmap(): ImageBitmap
