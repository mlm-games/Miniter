package org.mlm.miniter.engine

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mlm.miniter.platform.AndroidContext
import org.mlm.miniter.project.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    @Volatile
    private var currentSessionId: Long = -1L

    private fun MediaMetadataRetriever.setDataSourceCompat(path: String) {
        if (path.startsWith("content://")) {
            setDataSource(AndroidContext.get(), Uri.parse(path))
        } else {
            setDataSource(path)
        }
    }

    actual suspend fun probeVideo(path: String): VideoInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSourceCompat(path)

            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val frameRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toDoubleOrNull() ?: 30.0

            val bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull() ?: 0

            val hasAudio = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
            ) == "yes"

            val hasVideo = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
            ) == "yes"

            val mimeType = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            )

            VideoInfo(
                durationMs = duration,
                width = width,
                height = height,
                frameRate = frameRate,
                videoBitrate = bitrate,
                audioChannels = 2,
                audioSampleRate = 44100,
                audioCodecName = null,
                videoCodecName = mimeType,
                hasAudio = hasAudio,
                hasVideo = hasVideo,
            )
        } finally {
            retriever.release()
        }
    }

    actual suspend fun exportVideo(
        project: MinterProject,
        outputPath: String,
    ) = withContext(Dispatchers.IO) {
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
            val outWidth = if (project.exportSettings.width > 0) {
                project.exportSettings.width
            } else {
                firstInfo.width
            }
            val outHeight = if (project.exportSettings.height > 0) {
                project.exportSettings.height
            } else {
                firstInfo.height
            }

            val totalDurationMs = videoClips.sumOf { it.durationMs }.toFloat()

            val command = buildFFmpegCommand(
                clips = videoClips,
                format = project.exportSettings.format,
                quality = project.exportSettings.quality,
                outWidth = outWidth,
                outHeight = outHeight,
                outputPath = outputPath,
                hasAudio = firstInfo.hasAudio,
            )

            _exportProgress.value = ExportProgress(phase = "Exporting…", progress = 0f)

            executeFFmpegCommand(command, totalDurationMs, outputPath)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _exportProgress.value = ExportProgress(
                error = "Export failed: ${e.message}"
            )
        }
    }

    private fun buildFFmpegCommand(
        clips: List<Clip.VideoClip>,
        format: ExportFormat,
        quality: Float,
        outWidth: Int,
        outHeight: Int,
        outputPath: String,
        hasAudio: Boolean,
    ): String {
        val sb = StringBuilder()

        if (clips.size == 1) {
            val clip = clips.first()

            sb.append("-y ")
            sb.append("-ss ${clip.sourceStartMs / 1000.0} ")
            sb.append("-i \"${clip.sourcePath}\" ")
            sb.append("-t ${(clip.sourceEndMs - clip.sourceStartMs) / 1000.0} ")

            val vf = FilterGraphBuilder.buildVideoFilterString(
                filters = clip.filters,
                speed = clip.speed,
                outWidth = outWidth,
                outHeight = outHeight,
            )
            if (vf.isNotEmpty()) {
                sb.append("-vf \"$vf\" ")
            }

            if (hasAudio) {
                val af = FilterGraphBuilder.buildAudioFilterString(
                    speed = clip.speed,
                    volume = clip.volume,
                )
                if (af.isNotEmpty()) {
                    sb.append("-af \"$af\" ")
                }
            }

            appendCodecSettings(sb, format, quality, hasAudio)

            sb.append("\"$outputPath\"")

        } else {
            sb.append("-y ")

            for (clip in clips) {
                sb.append("-i \"${clip.sourcePath}\" ")
            }

            val filterComplex = StringBuilder()
            for ((i, clip) in clips.withIndex()) {
                val vf = FilterGraphBuilder.buildVideoFilterString(
                    filters = clip.filters,
                    speed = clip.speed,
                    outWidth = outWidth,
                    outHeight = outHeight,
                )
                val trimFilter = "trim=start=${clip.sourceStartMs / 1000.0}:" +
                        "end=${clip.sourceEndMs / 1000.0},setpts=PTS-STARTPTS"

                filterComplex.append("[$i:v]$trimFilter,$vf[v$i];")

                if (hasAudio) {
                    val af = FilterGraphBuilder.buildAudioFilterString(
                        speed = clip.speed,
                        volume = clip.volume,
                    )
                    val atrimFilter = "atrim=start=${clip.sourceStartMs / 1000.0}:" +
                            "end=${clip.sourceEndMs / 1000.0},asetpts=PTS-STARTPTS"
                    val audioFilters = if (af.isNotEmpty()) "$atrimFilter,$af" else atrimFilter
                    filterComplex.append("[$i:a]$audioFilters[a$i];")
                }
            }

            for (i in clips.indices) {
                filterComplex.append("[v$i]")
                if (hasAudio) filterComplex.append("[a$i]")
            }
            val streamCount = if (hasAudio) "v=1:a=1" else "v=1:a=0"
            filterComplex.append("concat=n=${clips.size}:$streamCount[outv]")
            if (hasAudio) filterComplex.append("[outa]")

            sb.append("-filter_complex \"$filterComplex\" ")
            sb.append("-map \"[outv]\" ")
            if (hasAudio) sb.append("-map \"[outa]\" ")

            appendCodecSettings(sb, format, quality, hasAudio)
            sb.append("\"$outputPath\"")
        }

        return sb.toString()
    }

    private fun appendCodecSettings(
        sb: StringBuilder,
        format: ExportFormat,
        quality: Float,
        hasAudio: Boolean,
    ) {
        when (format) {
            ExportFormat.MP4, ExportFormat.MOV -> {
                sb.append("-c:v libx264 ")
                val crf = ((100 - quality) / 100f * 45f + 6f).toInt().coerceIn(0, 51)
                sb.append("-crf $crf ")
                sb.append("-preset medium ")
                sb.append("-pix_fmt yuv420p ")
                if (hasAudio) sb.append("-c:a aac -b:a 192k ")
            }
            ExportFormat.WebM -> {
                sb.append("-c:v libvpx-vp9 ")
                val crf = ((100 - quality) / 100f * 56f + 7f).toInt().coerceIn(0, 63)
                sb.append("-crf $crf -b:v 0 ")
                if (hasAudio) sb.append("-c:a libopus -b:a 128k ")
            }
        }

        sb.append("-f ${format.extension} ")
    }

    private suspend fun executeFFmpegCommand(
        command: String,
        totalDurationMs: Float,
        outputPath: String,
    ) = suspendCancellableCoroutine { continuation ->

        val session = FFmpegKit.executeAsync(
            command,
            { session ->
                val returnCode = session.returnCode
                when {
                    ReturnCode.isSuccess(returnCode) -> {
                        _exportProgress.value = ExportProgress(
                            phase = "Complete",
                            progress = 1f,
                            isComplete = true,
                        )
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                    ReturnCode.isCancel(returnCode) -> {
                        _exportProgress.value = ExportProgress(
                            phase = "Cancelled",
                            isCancelled = true,
                        )
                        try { java.io.File(outputPath).delete() } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                    else -> {
                        _exportProgress.value = ExportProgress(
                            error = "Export failed (code ${returnCode.value}): ${session.failStackTrace ?: "Unknown error"}"
                        )
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                RuntimeException("FFmpeg failed: code ${returnCode.value}")
                            )
                        }
                    }
                }
            },
            null,
            { statistics: Statistics ->
                if (totalDurationMs > 0) {
                    val timeMs = statistics.time.toFloat()
                    val progress = (timeMs / totalDurationMs).coerceIn(0f, 1f)
                    _exportProgress.value = ExportProgress(
                        phase = "Exporting… ${(progress * 100).toInt()}%",
                        progress = progress,
                    )
                }
            }
        )

        currentSessionId = session.sessionId

        continuation.invokeOnCancellation {
            FFmpegKit.cancel(session.sessionId)
            try { java.io.File(outputPath).delete() } catch (_: Exception) {}
        }
    }

    actual suspend fun extractThumbnails(
        path: String,
        count: Int,
        width: Int,
        height: Int,
    ): List<ImageData> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val thumbnails = mutableListOf<ImageData>()

        try {
            retriever.setDataSourceCompat(path)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return@withContext emptyList()

            if (durationMs <= 0) return@withContext emptyList()

            val interval = durationMs / count

            for (i in 0 until count) {
                ensureActive()

                val timeUs = i * interval * 1000
                val bitmap = retriever.getFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue

                thumbnails.add(bitmap.toImageData(width, height))
                bitmap.recycle()
            }
        } finally {
            retriever.release()
        }

        thumbnails
    }

    actual suspend fun extractSingleThumbnail(
        path: String,
        timestampMs: Long,
        width: Int,
        height: Int,
    ): ImageData? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSourceCompat(path)
            val bitmap = retriever.getFrameAtTime(
                timestampMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return@withContext null

            val result = bitmap.toImageData(width, height)
            bitmap.recycle()
            result
        } finally {
            retriever.release()
        }
    }

    actual fun cancelExport() {
        if (currentSessionId >= 0) {
            FFmpegKit.cancel(currentSessionId)
        }
    }

    actual fun reset() {
        _exportProgress.value = ExportProgress()
        currentSessionId = -1
    }
}
