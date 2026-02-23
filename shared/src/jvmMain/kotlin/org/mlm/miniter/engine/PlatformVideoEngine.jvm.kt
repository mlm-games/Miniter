package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import org.mlm.miniter.project.*
import java.awt.image.BufferedImage

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    @Volatile
    private var exportJob: Job? = null

    init {
        try { FFmpegLogCallback.set() } catch (_: Exception) {}
    }

    /** Round up to nearest even number for yuv420p compatibility */
    private fun evenUp(value: Int): Int = if (value % 2 == 0) value else value + 1

    actual suspend fun probeVideo(path: String): VideoInfo = withContext(Dispatchers.IO) {
        val grabber = FFmpegFrameGrabber(path)
        try {
            grabber.start()
            VideoInfo(
                durationMs = grabber.lengthInTime / 1000,
                width = grabber.imageWidth,
                height = grabber.imageHeight,
                frameRate = if (grabber.frameRate > 0) grabber.frameRate else 30.0,
                videoBitrate = grabber.videoBitrate,
                audioChannels = grabber.audioChannels,
                audioSampleRate = grabber.sampleRate,
                audioCodecName = grabber.audioCodecName,
                videoCodecName = grabber.videoCodecName,
                hasAudio = grabber.hasAudio(),
                hasVideo = grabber.hasVideo(),
            )
        } finally {
            try { grabber.stop() } catch (_: Exception) {}
            try { grabber.release() } catch (_: Exception) {}
        }
    }

    actual suspend fun exportVideo(
        project: MinterProject,
        outputPath: String,
    ) = withContext(Dispatchers.IO) {
        exportJob = currentCoroutineContext()[Job]

        try {
            _exportProgress.value = ExportProgress(phase = "Preparing…", progress = 0f)

            val videoTrack = project.timeline.tracks
                .firstOrNull { it.type == TrackType.Video }
                ?: run {
                    _exportProgress.value = ExportProgress(error = "No video track found")
                    return@withContext
                }

            val videoClips = videoTrack.clips.filterIsInstance<Clip.VideoClip>()
            if (videoClips.isEmpty()) {
                _exportProgress.value = ExportProgress(error = "No video clips on timeline")
                return@withContext
            }

            val firstInfo = probeVideo(videoClips.first().sourcePath)

            // Force even dimensions throughout the entire pipeline
            val outWidth = evenUp(
                if (project.exportSettings.width > 0) project.exportSettings.width
                else firstInfo.width
            )
            val outHeight = evenUp(
                if (project.exportSettings.height > 0) project.exportSettings.height
                else firstInfo.height
            )
            val outFrameRate = firstInfo.frameRate

            val format = project.exportSettings.format

            val recorder = FFmpegFrameRecorder(outputPath, outWidth, outHeight, firstInfo.audioChannels)
            recorder.format = format.extension
            recorder.frameRate = outFrameRate
            recorder.videoBitrate = (firstInfo.videoBitrate * (project.exportSettings.quality / 100.0))
                .toInt()
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
            try {
                for ((clipIndex, clip) in videoClips.withIndex()) {
                    ensureActive()

                    _exportProgress.value = ExportProgress(
                        phase = "Exporting clip ${clipIndex + 1}/$totalClips",
                        progress = clipIndex.toFloat() / totalClips,
                    )

                    exportSingleClip(
                        clip = clip,
                        recorder = recorder,
                        outWidth = outWidth,
                        outHeight = outHeight,
                        progressBase = clipIndex.toFloat() / totalClips,
                        progressScale = 1f / totalClips,
                    )
                }

                recorder.stop()
                recorder.release()

                _exportProgress.value = ExportProgress(
                    phase = "Complete",
                    progress = 1f,
                    isComplete = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                recorder.stop()
                recorder.release()
                try { java.io.File(outputPath).delete() } catch (_: Exception) {}
                _exportProgress.value = ExportProgress(
                    phase = "Cancelled",
                    isCancelled = true,
                )
                throw e
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _exportProgress.value = ExportProgress(
                error = "Export failed: ${e.message}"
            )
        }
    }

    private suspend fun exportSingleClip(
        clip: Clip.VideoClip,
        recorder: FFmpegFrameRecorder,
        outWidth: Int,
        outHeight: Int,
        progressBase: Float,
        progressScale: Float,
    ) {
        val grabber = FFmpegFrameGrabber(clip.sourcePath)
        var filter: FFmpegFrameFilter? = null

        try {
            grabber.start()
            grabber.setTimestamp(clip.sourceStartMs * 1000)

            val clipDuration = clip.sourceEndMs - clip.sourceStartMs
            val endUs = clip.sourceEndMs * 1000

            val filterStr = FilterGraphBuilder.buildVideoFilterString(
                filters = clip.filters,
                speed = clip.speed,
                outWidth = outWidth,
                outHeight = outHeight,
            )

            if (filterStr.isNotEmpty()) {
                // Use GRABBER dimensions as input — the filter chain handles resize
                val inputW = grabber.imageWidth
                val inputH = grabber.imageHeight

                filter = FFmpegFrameFilter(filterStr, inputW, inputH)

                // Use grabber's native pixel format so the buffer source matches actual frames.
                // The format=yuv420p at the start of the filter string normalizes it.
                val grabberFmt = grabber.pixelFormat
                filter.pixelFormat = if (grabberFmt >= 0) grabberFmt else avutil.AV_PIX_FMT_YUV420P

                filter.start()
            }

            var frame: Frame?
            while (true) {
                currentCoroutineContext().ensureActive()

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

                    if (clipDuration > 0) {
                        val clipProgress = ((grabber.timestamp / 1000) - clip.sourceStartMs)
                            .toFloat() / clipDuration
                        _exportProgress.value = ExportProgress(
                            phase = _exportProgress.value.phase,
                            progress = progressBase + (clipProgress.coerceIn(0f, 1f) * progressScale),
                        )
                    }
                } else if (frame.samples != null) {
                    recorder.record(frame)
                }
            }
        } finally {
            try { filter?.stop() } catch (_: Exception) {}
            try { filter?.release() } catch (_: Exception) {}
            try { grabber.stop() } catch (_: Exception) {}
            try { grabber.release() } catch (_: Exception) {}
        }
    }

    actual suspend fun extractThumbnails(
        path: String,
        count: Int,
        width: Int,
        height: Int,
    ): List<ImageData> = withContext(Dispatchers.IO) {
        val grabber = FFmpegFrameGrabber(path)
        val converter = Java2DFrameConverter()
        val thumbnails = mutableListOf<ImageData>()

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
                thumbnails.add(img.toImageData(width, height))
            }
        } finally {
            try { grabber.stop() } catch (_: Exception) {}
            try { grabber.release() } catch (_: Exception) {}
        }

        thumbnails
    }

    actual suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
    ): ImageData? = withContext(Dispatchers.IO) {
        val grabber = FFmpegFrameGrabber(path)
        val converter = Java2DFrameConverter()

        try {
            grabber.start()
            grabber.setTimestamp(timestampMs * 1000, true)
            val frame = grabber.grabImage() ?: return@withContext null
            val img = converter.convert(frame) ?: return@withContext null
            img.toImageData(width, height)
        } finally {
            try { grabber.stop() } catch (_: Exception) {}
            try { grabber.release() } catch (_: Exception) {}
        }
    }

    actual fun cancelExport() {
        exportJob?.cancel()
    }

    actual fun reset() {
        _exportProgress.value = ExportProgress()
        exportJob = null
    }
}