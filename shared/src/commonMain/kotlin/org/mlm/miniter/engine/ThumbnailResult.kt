package org.mlm.miniter.engine

const val THUMBNAIL_BUCKET_US = 500_000L

data class ThumbnailKey(
    val sourcePath: String,
    val timestampBucketUs: Long,
)

fun thumbnailKey(sourcePath: String, timestampUs: Long): ThumbnailKey =
    ThumbnailKey(
        sourcePath = sourcePath,
        timestampBucketUs = (timestampUs.coerceAtLeast(0L) / THUMBNAIL_BUCKET_US) * THUMBNAIL_BUCKET_US,
    )

sealed class ThumbnailResult {
    data class Success(val image: ImageData) : ThumbnailResult()
    data class Error(val message: String) : ThumbnailResult()
}
