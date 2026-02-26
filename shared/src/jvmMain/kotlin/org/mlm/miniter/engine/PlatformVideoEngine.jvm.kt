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
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
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

            val videoTracks = project.timeline.tracks
                .filter { it.type == TrackType.Video && !it.isMuted }

            val allVideoClips = videoTracks.flatMap {
                it.clips.filterIsInstance<Clip.VideoClip>()
            }

            if (allVideoClips.isEmpty()) {
                _exportProgress.value = ExportProgress(error = "No video clips on timeline")
                return@withContext
            }

            val textClips = project.timeline.tracks
                .filter { it.type == TrackType.Text && !it.isMuted }
                .flatMap { it.clips.filterIsInstance<Clip.TextClip>() }

            val audioClips = project.timeline.tracks
                .filter { it.type == TrackType.Audio && !it.isMuted }
                .flatMap { it.clips.filterIsInstance<Clip.AudioClip>() }
                .sortedBy { it.startMs }

            val clipInfos = allVideoClips.map { clip ->
                try { probeVideo(clip.sourcePath) } catch (_: Exception) { null }
            }
            val anyEmbeddedAudio = clipInfos.any { it?.hasAudio == true }
            val hasExtraAudio = audioClips.isNotEmpty()
            val hasAnyAudio = anyEmbeddedAudio || hasExtraAudio

            val firstInfo = clipInfos.firstNotNullOf { it }
            val outWidth = evenUp(
                if (project.exportSettings.width > 0) project.exportSettings.width
                else firstInfo.width
            )
            val outHeight = evenUp(
                if (project.exportSettings.height > 0) project.exportSettings.height
                else firstInfo.height
            )
            val outFrameRate = firstInfo.frameRate
            val audioChannels = if (hasAnyAudio) firstInfo.audioChannels.coerceAtLeast(2) else 0
            val sampleRate = if (anyEmbeddedAudio) firstInfo.audioSampleRate else 44100

            val recorder = FFmpegFrameRecorder(
                outputPath, outWidth, outHeight,
                if (hasAnyAudio) audioChannels else 0
            )
            recorder.format = project.exportSettings.format.extension
            recorder.frameRate = outFrameRate
            recorder.videoBitrate = (firstInfo.videoBitrate *
                    (project.exportSettings.quality / 100.0)).toInt().coerceAtLeast(500_000)
            recorder.videoCodec = when (project.exportSettings.format) {
                ExportFormat.MP4 -> avcodec.AV_CODEC_ID_H264
                ExportFormat.WebM -> avcodec.AV_CODEC_ID_VP9
                ExportFormat.MOV -> avcodec.AV_CODEC_ID_H264
            }
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            if (hasAnyAudio) {
                recorder.audioChannels = audioChannels
                recorder.sampleRate = sampleRate
                recorder.audioCodec = when (project.exportSettings.format) {
                    ExportFormat.MP4, ExportFormat.MOV -> avcodec.AV_CODEC_ID_AAC
                    ExportFormat.WebM -> avcodec.AV_CODEC_ID_OPUS
                }
            }
            recorder.start()

            val fontPath = findSystemFont()
            val timelineDurationMs = project.timeline.durationMs
            val frameDurationMs = (1000.0 / outFrameRate).toLong().coerceAtLeast(1)

            try {
                // ── Frame-by-frame compositing approach ──
                // For each output frame time, grab from each track and composite

                data class TrackGrabber(
                    val track: Track,
                    val clips: List<Clip.VideoClip>,
                    val grabbers: MutableMap<String, FFmpegFrameGrabber>,
                    val filters: MutableMap<String, FFmpegFrameFilter>,
                    val converters: MutableMap<String, Java2DFrameConverter>,
                    val clipInfos: MutableMap<String, VideoInfo>,
                )

                val trackGrabbers = videoTracks.map { track ->
                    val clips = track.clips.filterIsInstance<Clip.VideoClip>().sortedBy { it.startMs }
                    TrackGrabber(track, clips, mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf())
                }

                // Pre-open grabbers for all clips
                for (tg in trackGrabbers) {
                    for (clip in tg.clips) {
                        if (tg.grabbers.containsKey(clip.id)) continue
                        val grabber = FFmpegFrameGrabber(clip.sourcePath)
                        grabber.pixelFormat = avutil.AV_PIX_FMT_BGR24
                        grabber.start()
                        tg.grabbers[clip.id] = grabber
                        tg.converters[clip.id] = Java2DFrameConverter()

                        val info = try { probeVideo(clip.sourcePath) } catch (_: Exception) { null }
                        if (info != null) tg.clipInfos[clip.id] = info

                        // Build filter for this clip
                        var filterStr = FilterGraphBuilder.buildVideoFilterString(
                            filters = clip.filters,
                            speed = clip.speed,
                            outWidth = outWidth,
                            outHeight = outHeight,
                        )
                        val textOverlay = FilterGraphBuilder.buildTextOverlayFilters(
                            textClips = textClips,
                            timelineStartMs = clip.startMs,
                            timelineEndMs = clip.startMs + clip.durationMs,
                            perClip = true,
                            fontPath = fontPath,
                        )
                        if (textOverlay.isNotEmpty()) filterStr = "$filterStr,$textOverlay"
                        filterStr = "$filterStr,format=bgr24"

                        val filter = FFmpegFrameFilter(filterStr, grabber.imageWidth, grabber.imageHeight)
                        filter.pixelFormat = avutil.AV_PIX_FMT_BGR24
                        filter.start()
                        tg.filters[clip.id] = filter

                        // Seek to clip start
                        grabber.setTimestamp(clip.sourceStartMs * 1000)
                    }
                }

                // Also handle embedded audio: export audio from first track's clips
                // (simplified — full multi-track audio mixing would need more work)
                val audioTrackGrabbers = mutableListOf<Pair<Clip.VideoClip, FFmpegFrameGrabber>>()
                if (anyEmbeddedAudio) {
                    for (tg in trackGrabbers) {
                        for (clip in tg.clips) {
                            val info = tg.clipInfos[clip.id] ?: continue
                            if (info.hasAudio) {
                                val audioGrabber = FFmpegFrameGrabber(clip.sourcePath)
                                audioGrabber.start()
                                audioGrabber.setTimestamp(clip.sourceStartMs * 1000)
                                audioTrackGrabbers.add(clip to audioGrabber)
                            }
                        }
                    }
                }

                val converter = Java2DFrameConverter()
                var currentTimeMs = 0L
                val totalFrames = (timelineDurationMs / frameDurationMs).toInt()
                var frameCount = 0

                while (currentTimeMs < timelineDurationMs) {
                    ensureActive()

                    // Composite frame: start with black, overlay each track bottom-to-top
                    var composited: BufferedImage? = null

                    for (tg in trackGrabbers) {
                        // Find active clip on this track at currentTimeMs
                        val activeClip = tg.clips.firstOrNull { clip ->
                            currentTimeMs >= clip.startMs &&
                                    currentTimeMs < clip.startMs + clip.durationMs
                        } ?: continue

                        val grabber = tg.grabbers[activeClip.id] ?: continue
                        val filter = tg.filters[activeClip.id] ?: continue
                        val conv = tg.converters[activeClip.id] ?: continue

                        val offsetInClip = currentTimeMs - activeClip.startMs
                        val sourceTimeMs = activeClip.sourceStartMs + (offsetInClip * activeClip.speed).toLong()

                        if (sourceTimeMs > activeClip.sourceEndMs) continue

                        grabber.setTimestamp(sourceTimeMs * 1000)
                        val frame = grabber.grabImage() ?: continue

                        filter.push(frame)
                        val filtered = filter.pull() ?: continue
                        val image = conv.convert(filtered) ?: continue

                        if (composited == null) {
                            composited = image
                        } else {
                            // Overlay this track's image on top
                            val g = composited.createGraphics()
                            g.drawImage(image, 0, 0, outWidth, outHeight, null)
                            g.dispose()
                        }
                    }

                    // If no clip active, write black frame
                    if (composited == null) {
                        composited = createBlackFrame(outWidth, outHeight)
                    }

                    val outFrame = converter.convert(composited)
                    recorder.record(outFrame)

                    frameCount++
                    if (totalFrames > 0) {
                        val progress = frameCount.toFloat() / totalFrames
                        _exportProgress.value = ExportProgress(
                            phase = "Exporting… ${(progress * 100).toInt()}%",
                            progress = progress.coerceIn(0f, 1f),
                        )
                    }

                    currentTimeMs += frameDurationMs
                }

                // Export embedded audio
                for ((clip, audioGrabber) in audioTrackGrabbers) {
                    val endUs = clip.sourceEndMs * 1000
                    var aFrame: Frame?
                    while (true) {
                        ensureActive()
                        aFrame = audioGrabber.grab() ?: break
                        if (audioGrabber.timestamp > endUs) break
                        if (aFrame.samples != null) {
                            recorder.record(aFrame)
                        }
                    }
                    try { audioGrabber.stop() } catch (_: Exception) {}
                    try { audioGrabber.release() } catch (_: Exception) {}
                }

                // Export separate audio clips
                if (hasExtraAudio) {
                    _exportProgress.value = ExportProgress(phase = "Mixing audio…", progress = 0.95f)
                    exportAudioClips(audioClips, allVideoClips.sortedBy { it.startMs }, recorder)
                }

                // Cleanup grabbers
                for (tg in trackGrabbers) {
                    for ((_, g) in tg.grabbers) {
                        try { g.stop() } catch (_: Exception) {}
                        try { g.release() } catch (_: Exception) {}
                    }
                    for ((_, f) in tg.filters) {
                        try { f.stop() } catch (_: Exception) {}
                        try { f.release() } catch (_: Exception) {}
                    }
                }

                recorder.stop()
                recorder.release()

                _exportProgress.value = ExportProgress(
                    phase = "Complete", progress = 1f, isComplete = true,
                )

            } catch (e: kotlinx.coroutines.CancellationException) {
                recorder.stop()
                recorder.release()
                try { File(outputPath).delete() } catch (_: Exception) {}
                _exportProgress.value = ExportProgress(phase = "Cancelled", isCancelled = true)
                throw e
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _exportProgress.value = ExportProgress(error = "Export failed: ${e.message}")
        }
    }

    private fun writeGapFrames(
        recorder: FFmpegFrameRecorder,
        durationMs: Long,
        width: Int,
        height: Int,
        frameRate: Double,
    ) {
        val converter = Java2DFrameConverter()
        val blackImage = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR).also {
            val g = it.createGraphics()
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            g.dispose()
        }
        val totalFrames = ((durationMs / 1000.0) * frameRate).toInt().coerceAtLeast(1)

        for (i in 0 until totalFrames) {
            val frame = converter.convert(blackImage)
            recorder.record(frame)
        }
    }

    private suspend fun exportSingleClipWithTransition(
        clip: Clip.VideoClip,
        recorder: FFmpegFrameRecorder,
        outWidth: Int,
        outHeight: Int,
        textClips: List<Clip.TextClip>,
        fontPath: String?,
        transitionIn: Transition?,
        previousLastFrame: BufferedImage?,
        hasAudio: Boolean,
        frameRate: Double,
        progressBase: Float,
        progressScale: Float,
    ): BufferedImage? {
        val grabber = FFmpegFrameGrabber(clip.sourcePath)
        val converter = Java2DFrameConverter()
        var filter: FFmpegFrameFilter? = null
        var lastImage: BufferedImage? = null

        try {
            grabber.pixelFormat = avutil.AV_PIX_FMT_BGR24
            grabber.start()
            grabber.setTimestamp(clip.sourceStartMs * 1000)

            val clipSourceDuration = clip.sourceEndMs - clip.sourceStartMs
            val endUs = clip.sourceEndMs * 1000

            var filterStr = FilterGraphBuilder.buildVideoFilterString(
                filters = clip.filters,
                speed = clip.speed,
                outWidth = outWidth,
                outHeight = outHeight,
            )

            val textOverlay = FilterGraphBuilder.buildTextOverlayFilters(
                textClips = textClips,
                timelineStartMs = clip.startMs,
                timelineEndMs = clip.startMs + clip.durationMs,
                outputOffsetSec = 0.0,
                perClip = true,
                fontPath = fontPath,
            )
            if (textOverlay.isNotEmpty()) {
                filterStr = "$filterStr,$textOverlay"
            }

            if (filterStr.isNotEmpty()) {
                filterStr = "$filterStr,format=bgr24"
                val inputW = grabber.imageWidth
                val inputH = grabber.imageHeight
                filter = FFmpegFrameFilter(filterStr, inputW, inputH)
                filter.pixelFormat = avutil.AV_PIX_FMT_BGR24
                filter.start()
            }

            val transitionFrames = if (transitionIn != null) {
                ((transitionIn.durationMs / 1000.0) * frameRate).toInt().coerceAtLeast(1)
            } else 0
            var outputFrameCount = 0

            var frame: Frame?
            while (true) {
                currentCoroutineContext().ensureActive()

                frame = grabber.grab() ?: break
                if (grabber.timestamp > endUs) break

                if (frame.image != null) {
                    val processedFrame = if (filter != null) {
                        filter.push(frame)
                        filter.pull()
                    } else frame

                    if (processedFrame != null) {
                        val inTransitionZone = transitionIn != null
                                && previousLastFrame != null
                                && outputFrameCount < transitionFrames

                        if (inTransitionZone) {
                            val currentImage = converter.convert(processedFrame)
                            if (currentImage != null) {
                                val progress = outputFrameCount.toFloat() / transitionFrames
                                val blended = blendFrames(
                                    from = previousLastFrame!!,
                                    to = currentImage,
                                    progress = progress,
                                    type = transitionIn!!.type,
                                    width = outWidth,
                                    height = outHeight,
                                )
                                lastImage = currentImage
                                val blendedFrame = converter.convert(blended)
                                recorder.record(blendedFrame)
                            }
                        } else {
                            recorder.record(processedFrame)
                            val img = converter.convert(processedFrame)
                            if (img != null) lastImage = img
                        }

                        outputFrameCount++

                        if (clipSourceDuration > 0) {
                            val clipProgress =
                                ((grabber.timestamp / 1000) - clip.sourceStartMs)
                                    .toFloat() / clipSourceDuration
                            _exportProgress.value = ExportProgress(
                                phase = _exportProgress.value.phase,
                                progress = progressBase +
                                        (clipProgress.coerceIn(0f, 1f) * progressScale),
                            )
                        }
                    }
                } else if (frame.samples != null && hasAudio) {
                    recorder.record(frame)
                }
            }

            return lastImage

        } finally {
            try { filter?.stop() } catch (_: Exception) {}
            try { filter?.release() } catch (_: Exception) {}
            try { grabber.stop() } catch (_: Exception) {}
            try { grabber.release() } catch (_: Exception) {}
        }
    }

    private fun createBlackFrame(width: Int, height: Int): BufferedImage {
        return BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR).also {
            val g = it.createGraphics()
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            g.dispose()
        }
    }

    private fun blendFrames(
        from: BufferedImage,
        to: BufferedImage,
        progress: Float,
        type: TransitionType,
        width: Int,
        height: Int,
    ): BufferedImage {
        val result = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g = result.createGraphics()
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )

        when (type) {
            TransitionType.CrossFade, TransitionType.Dissolve -> {
                g.drawImage(from, 0, 0, width, height, null)
                g.composite =
                    AlphaComposite.getInstance(AlphaComposite.SRC_OVER, progress.coerceIn(0f, 1f))
                g.drawImage(to, 0, 0, width, height, null)
            }

            TransitionType.SlideLeft -> {
                val shift = (width * progress).toInt()
                g.drawImage(from, -shift, 0, width, height, null)
                g.drawImage(to, width - shift, 0, width, height, null)
            }

            TransitionType.SlideRight -> {
                val shift = (width * progress).toInt()
                g.drawImage(from, shift, 0, width, height, null)
                g.drawImage(to, -(width - shift), 0, width, height, null)
            }
        }

        g.dispose()
        return result
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

                val outputOffsetUs = timelineToOutputUs(audioClip.startMs, videoClips)

                var frame: Frame?
                while (true) {
                    currentCoroutineContext().ensureActive()
                    frame = grabber.grab() ?: break
                    if (grabber.timestamp > endUs) break

                    if (frame.samples != null) {
                        val sourceProgressUs =
                            grabber.timestamp - (audioClip.sourceStartMs * 1000)
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
                return (outputMs + (timelineMs - (if (outputMs > 0) clip.startMs else 0))) * 1000L
            }
            outputMs += clip.durationMs
            val nextClip = videoClips.getOrNull(videoClips.indexOf(clip) + 1)
            if (nextClip != null) {
                val gapMs = nextClip.startMs - clipEnd
                if (gapMs > 0) outputMs += gapMs
            }
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
