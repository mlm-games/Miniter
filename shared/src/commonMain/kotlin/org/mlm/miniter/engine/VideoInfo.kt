package org.mlm.miniter.engine

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
