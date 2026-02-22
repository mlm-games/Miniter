package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.mlm.miniter.project.*

data class ExportProgress(
    val phase: String = "Idle",
    val progress: Float = 0f,       // 0..1
    val isComplete: Boolean = false,
    val error: String? = null,
)

class VideoExporter {
    private val _progress = MutableStateFlow(ExportProgress())
    val progress: StateFlow<ExportProgress> = _progress

    suspend fun export(
        project: MinterProject,
        outputPath: String,
    ) = withContext(Dispatchers.IO) {
        try {
            _progress.value = ExportProgress(phase = "Preparing...", progress = 0f)

            val videoTrack = project.timeline.tracks
                .firstOrNull { it.type == TrackType.Video } ?: run {
                _progress.value = ExportProgress(error = "No video track found")
                return@withContext
            }

            val videoClips = videoTrack.clips.filterIsInstance<Clip.VideoClip>()
            if (videoClips.isEmpty()) {
                _progress.value = ExportProgress(error = "No video clips on timeline")
                return@withContext
            }

            // Probe first clip for output dimensions
            val firstInfo = probeVideo(videoClips.first().sourcePath)
            val outWidth = if (project.exportSettings.width > 0) project.exportSettings.width else firstInfo.width
            val outHeight = if (project.exportSettings.height > 0) project.exportSettings.height else firstInfo.height
            val outFrameRate = firstInfo.frameRate

            val format = project.exportSettings.format
            val recorder = FFmpegFrameRecorder(outputPath, outWidth, outHeight, firstInfo.audioChannels)
            recorder.format = format.extension
            recorder.frameRate = outFrameRate
            recorder.videoBitrate = (firstInfo.videoBitrate * (project.exportSettings.quality / 100.0)).toInt()
                .coerceAtLeast(500_000)
            recorder.videoCodec = when (format) {
                ExportFormat.MP4 -> avcodec.AV_CODEC_ID_H264
                ExportFormat.WebM -> avcodec.AV_CODEC_ID_VP9
                ExportFormat.MOV -> avcodec.AV_CODEC_ID_H264
            }
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            if (firstInfo.hasAudio) {
                recorder.audioChannels = firstInfo.audioChannels
                recorder.sampleRate = firstInfo.audioSampleRate
                recorder.audioCodec = when (format) {
                    ExportFormat.MP4, ExportFormat.MOV -> avcodec.AV_CODEC_ID_AAC
                    ExportFormat.WebM -> avcodec.AV_CODEC_ID_OPUS
                }
            }

            recorder.start()

            val totalClips = videoClips.size
            for ((clipIndex, clip) in videoClips.withIndex()) {
                if (!isActive) break

                _progress.value = ExportProgress(
                    phase = "Exporting clip ${clipIndex + 1}/$totalClips",
                    progress = clipIndex.toFloat() / totalClips
                )

                exportClip(clip, recorder, outWidth, outHeight, outFrameRate)
            }

            recorder.stop()
            recorder.release()

            _progress.value = ExportProgress(
                phase = "Complete",
                progress = 1f,
                isComplete = true,
            )
        } catch (e: Exception) {
            _progress.value = ExportProgress(
                error = "Export failed: ${e.message}"
            )
        }
    }

    private fun exportClip(
        clip: Clip.VideoClip,
        recorder: FFmpegFrameRecorder,
        outWidth: Int,
        outHeight: Int,
        outFrameRate: Double,
    ) {
        val grabber = FFmpegFrameGrabber(clip.sourcePath)
        var filter: FFmpegFrameFilter? = null

        try {
            grabber.start()
            grabber.setTimestamp(clip.sourceStartMs * 1000)

            // Build FFmpeg filter string
            val filterStr = buildFilterString(clip.filters, clip.speed, outWidth, outHeight)
            if (filterStr.isNotEmpty()) {
                filter = FFmpegFrameFilter(filterStr, outWidth, outHeight)
                filter.pixelFormat = grabber.pixelFormat
                filter.start()
            }

            val endUs = clip.sourceEndMs * 1000
            var frame: Frame?

            while (true) {
                frame = grabber.grab() ?: break
                if (grabber.timestamp > endUs) break

                if (frame.image != null) {
                    if (filter != null) {
                        filter.push(frame)
                        val filtered = filter.pull()
                        if (filtered != null) recorder.record(filtered)
                    } else {
                        recorder.record(frame)
                    }
                } else if (frame.samples != null) {
                    // Audio frame — adjust volume
                    recorder.record(frame)
                }
            }
        } finally {
            filter?.stop()
            filter?.release()
            grabber.stop()
            grabber.release()
        }
    }

    private fun buildFilterString(
        filters: List<VideoFilter>,
        speed: Float,
        width: Int,
        height: Int,
    ): String {
        val parts = mutableListOf<String>()

        parts.add("scale=$width:$height")

        for (f in filters) {
            when (f.type) {
                FilterType.Brightness -> {
                    val v = f.params["value"] ?: 0f
                    parts.add("eq=brightness=${v / 100f}")
                }
                FilterType.Contrast -> {
                    val v = f.params["value"] ?: 1f
                    parts.add("eq=contrast=$v")
                }
                FilterType.Saturation -> {
                    val v = f.params["value"] ?: 1f
                    parts.add("eq=saturation=$v")
                }
                FilterType.Grayscale -> {
                    parts.add("hue=s=0")
                }
                FilterType.Blur -> {
                    val r = (f.params["radius"] ?: 5f).toInt()
                    parts.add("boxblur=$r:$r")
                }
                FilterType.Sharpen -> {
                    parts.add("unsharp=5:5:1.0:5:5:0.0")
                }
                FilterType.Sepia -> {
                    parts.add("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131")
                }
            }
        }

        if (speed != 1.0f) {
            parts.add("setpts=${1.0 / speed}*PTS")
        }

        return parts.joinToString(",")
    }

    fun reset() {
        _progress.value = ExportProgress()
    }
}