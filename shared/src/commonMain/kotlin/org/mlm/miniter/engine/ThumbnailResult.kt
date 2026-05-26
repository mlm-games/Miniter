package org.mlm.miniter.engine

sealed class ThumbnailResult {
    data class Success(val image: ImageData) : ThumbnailResult()
    data class Error(val message: String) : ThumbnailResult()
}
