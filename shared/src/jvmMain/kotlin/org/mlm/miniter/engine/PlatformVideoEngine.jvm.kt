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
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    @Volatile
    private var exportJob: Job? = null

    init {
        try { FFmpegLogCallback.set() } catch (_: Exception) {}
    }

    private fun evenUp(value: Int): Int = if (value % 2 == 0) value else value + 1

    private fun findSystemFont(): String? {
        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            os.contains("linux") -> listOf(
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
                "/usr/share/fonts/noto/NotoSans-Regular.ttf",
            )
            os.contains("mac") || os.contains("darwin") -> listOf(
                "/System/Library/Fonts/Helvetica.ttc",
                "/Library/Fonts/Arial.ttf",
                "/System/Library/Fonts/SFNSText.ttf",
            )
            os.contains("win") -> listOf(
                "C:/Windows/Fonts/arial.ttf",
                "C:/Windows/Fonts/segoeui.ttf",
                "C:/Windows/Fonts/tahoma.ttf",
            )
            else -> emptyList()
        }
        return candidates.firstOrNull { File(it).exists() }
    }

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

            // Collect text clips from all non-muted text tracks
            val textClips = project.timeline.tracks
                .filter { it.type == TrackType.Text && !it.isMuted }
                .flatMap { it.clips.filterIsInstance<Clip.TextClip>() }

            // Collect audio clips from all non-muted audio tracks
            val audioClips = project.timeline.tracks
                .filter { it.type == TrackType.Audio && !it.isMuted }
                .flatMap { it.clips.filterIsInstance<Clip.AudioClip>() }
                .sortedBy { it.startMs }

            val firstInfo = probeVideo(videoClips.first().sourcePath)

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

            val hasEmbeddedAudio = firstInfo.hasAudio
            val hasExtraAudio = audioClips.isNotEmpty()
            val hasAnyAudio = hasEmbeddedAudio || hasExtraAudio

            val audioChannels = if (hasAnyAudio) {
                if (hasEmbeddedAudio) firstInfo.audioChannels.coerceAtLeast(1) else 2
            } else 0

            val sampleRate = if (hasEmbeddedAudio) firstInfo.audioSampleRate else 44100

            val recorder = FFmpegFrameRecorder(
                outputPath, outWidth, outHeight,
                if (hasAnyAudio) audioChannels else 0
            )
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

            if (hasAnyAudio) {
                recorder.audioChannels = audioChannels
                recorder.sampleRate = sampleRate
                recorder.audioCodec = when (format) {
                    ExportFormat.MP4, ExportFormat.MOV -> avcodec.AV_CODEC_ID_AAC
                    ExportFormat.WebM -> avcodec.AV_CODEC_ID_OPUS
                }
            }

            recorder.start()

            val fontPath = findSystemFont()
            val totalClips = videoClips.size
            var outputOffsetMs = 0L

            // Build timeline-to-output mapping for audio clips
            val videoClipsSorted = videoClips.sortedBy { it.startMs }

            try {
                for ((clipIndex, clip) in videoClipsSorted.withIndex()) {
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
                        textClips = textClips,
                        fontPath = fontPath,
                        outputOffsetMs = outputOffsetMs,
                        progressBase = clipIndex.toFloat() / totalClips,
                        progressScale = 1f / totalClips,
                    )

                    outputOffsetMs += clip.durationMs
                }

                // Export separate audio tracks
                if (hasExtraAudio) {
                    _exportProgress.value = ExportProgress(
                        phase = "Mixing audio tracks…",
                        progress = 0.9f,
                    )
                    exportAudioClips(
                        audioClips = audioClips,
                        videoClips = videoClipsSorted,
                        recorder = recorder,
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
                try { File(outputPath).delete() } catch (_: Exception) {}
                _exportProgress.value = ExportProgress(
                    phase = "Cancelled",
                    isCancelled = true,
                )
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _exportProgress.value = ExportProgress(
                error = "Export failed: ${e.message}"
            )
            e.printStackTrace()
        }
    }

    private suspend fun exportSingleClip(
        clip: Clip.VideoClip,
        recorder: FFmpegFrameRecorder,
        outWidth: Int,
        outHeight: Int,
        textClips: List<Clip.TextClip>,
        fontPath: String?,
        outputOffsetMs: Long,
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

            // Build the video filter string (scale, pad, effects, speed)
            var filterStr = FilterGraphBuilder.buildVideoFilterString(
                filters = clip.filters,
                speed = clip.speed,
                outWidth = outWidth,
                outHeight = outHeight,
            )

            val textFilters = FilterGraphBuilder.buildTextOverlayFilters(
                textClips = textClips,
                timelineStartMs = clip.startMs,
                timelineEndMs = clip.startMs + clip.durationMs,
                outputOffsetSec = 0.0,
                perClip = true,
                fontPath = fontPath,
            )
            if (textFilters.isNotEmpty()) {
                filterStr = "$filterStr,$textFilters"
            }

            if (filterStr.isNotEmpty()) {
                val inputW = grabber.imageWidth
                val inputH = grabber.imageHeight

                filter = FFmpegFrameFilter(filterStr, inputW, inputH)
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

    private suspend fun exportAudioClips(
        audioClips: List<Clip.AudioClip>,
        videoClips: List<Clip.VideoClip>,
        recorder: FFmpegFrameRecorder,
    ) {
        for (audioClip in audioClips) {
            currentCoroutineContext().ensureActive()

            val grabber = FFmpegFrameGrabber(audioClip.sourcePath)
            var audioFilter: FFmpegFrameFilter? = null

            try {
                grabber.start()
                grabber.setTimestamp(audioClip.sourceStartMs * 1000)

                val endUs = audioClip.sourceEndMs * 1000

                val afParts = mutableListOf<String>()

                if (audioClip.volume != 1.0f) {
                    afParts.add("volume=${audioClip.volume}")
                }
                if (audioClip.fadeInMs > 0) {
                    afParts.add("afade=t=in:st=0:d=${audioClip.fadeInMs / 1000.0}")
                }
                if (audioClip.fadeOutMs > 0) {
                    val fadeStart = (audioClip.durationMs - audioClip.fadeOutMs) / 1000.0
                    afParts.add("afade=t=out:st=$fadeStart:d=${audioClip.fadeOutMs / 1000.0}")
                }

                if (afParts.isNotEmpty()) {
                    audioFilter = FFmpegFrameFilter(
                        afParts.joinToString(","),
                        grabber.audioChannels
                    )
                    audioFilter.sampleRate = grabber.sampleRate
                    audioFilter.sampleFormat = grabber.sampleFormat
                    audioFilter.start()
                }

                // Calculate output offset for this audio clip's timeline position
                val outputOffsetUs = timelineToOutputUs(audioClip.startMs, videoClips)

                var frame: Frame?
                while (true) {
                    currentCoroutineContext().ensureActive()

                    frame = grabber.grab() ?: break
                    if (grabber.timestamp > endUs) break

                    if (frame.samples != null) {
                        val sourceProgressUs = grabber.timestamp - (audioClip.sourceStartMs * 1000)
                        frame.timestamp = outputOffsetUs + sourceProgressUs

                        if (audioFilter != null) {
                            audioFilter.push(frame)
                            val filtered = audioFilter.pull()
                            if (filtered != null) {
                                filtered.timestamp = frame.timestamp
                                recorder.record(filtered)
                            }
                        } else {
                            recorder.record(frame)
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Audio clip export failed: ${e.message}")
            } finally {
                try { audioFilter?.stop() } catch (_: Exception) {}
                try { audioFilter?.release() } catch (_: Exception) {}
                try { grabber.stop() } catch (_: Exception) {}
                try { grabber.release() } catch (_: Exception) {}
            }
        }
    }

    private fun timelineToOutputUs(timelineMs: Long, videoClips: List<Clip.VideoClip>): Long {
        var outputMs = 0L
        for (clip in videoClips) {
            val clipEnd = clip.startMs + clip.durationMs
            if (timelineMs >= clip.startMs && timelineMs < clipEnd) {
                return (outputMs + (timelineMs - clip.startMs)) * 1000L
            }
            if (timelineMs < clip.startMs) {
                return outputMs * 1000L
            }
            outputMs += clip.durationMs
        }
        return outputMs * 1000L
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