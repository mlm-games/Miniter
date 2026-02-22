package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber

data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val videoBitrate: Int,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val audioCodecName: String?,
    val videoCodecName: String?,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
)

suspend fun probeVideo(path: String): VideoInfo = withContext(Dispatchers.IO) {
    val grabber = FFmpegFrameGrabber(path)
    try {
        grabber.start()
        VideoInfo(
            durationMs = grabber.lengthInTime / 1000,
            width = grabber.imageWidth,
            height = grabber.imageHeight,
            frameRate = grabber.frameRate,
            videoBitrate = grabber.videoBitrate,
            audioChannels = grabber.audioChannels,
            audioSampleRate = grabber.sampleRate,
            audioCodecName = grabber.audioCodecName,
            videoCodecName = grabber.videoCodecName,
            hasAudio = grabber.hasAudio(),
            hasVideo = grabber.hasVideo(),
        )
    } finally {
        grabber.stop()
        grabber.release()
    }
}