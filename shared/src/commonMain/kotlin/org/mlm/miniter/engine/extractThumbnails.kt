package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage

suspend fun extractThumbnails(
    path: String,
    count: Int = 10,
    width: Int = 160,
    height: Int = 90,
): List<BufferedImage> = withContext(Dispatchers.IO) {
    val grabber = FFmpegFrameGrabber(path)
    val converter = Java2DFrameConverter()
    val thumbnails = mutableListOf<BufferedImage>()

    try {
        grabber.start()
        val totalUs = grabber.lengthInTime
        if (totalUs <= 0 || !grabber.hasVideo()) return@withContext emptyList()

        val intervalUs = totalUs / count

        for (i in 0 until count) {
            val timestampUs = i * intervalUs
            grabber.setTimestamp(timestampUs, true)
            val frame = grabber.grabImage() ?: continue
            val img = converter.convert(frame) ?: continue

            val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.drawImage(img, 0, 0, width, height, null)
            g.dispose()
            thumbnails.add(scaled)
        }
    } finally {
        grabber.stop()
        grabber.release()
    }

    thumbnails
}

suspend fun extractSingleThumbnail(
    path: String,
    timestampMs: Long = 0,
    width: Int = 320,
    height: Int = 180,
): BufferedImage? = withContext(Dispatchers.IO) {
    val grabber = FFmpegFrameGrabber(path)
    val converter = Java2DFrameConverter()

    try {
        grabber.start()
        grabber.setTimestamp(timestampMs * 1000, true)
        val frame = grabber.grabImage() ?: return@withContext null
        val img = converter.convert(frame) ?: return@withContext null

        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(img, 0, 0, width, height, null)
        g.dispose()
        scaled
    } finally {
        grabber.stop()
        grabber.release()
    }
}