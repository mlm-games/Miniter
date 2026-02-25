package org.mlm.miniter.engine

import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mlm.miniter.platform.AndroidContext
import org.mlm.miniter.project.*
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.net.toUri

actual class PlatformVideoEngine actual constructor() {

    private val _exportProgress = MutableStateFlow(ExportProgress())
    actual val exportProgress: StateFlow<ExportProgress> = _exportProgress

    @Volatile
    private var currentSessionId: Long = -1L

    companion object {
        private const val ANDROID_FONT = "/system/fonts/Roboto-Regular.ttf"
    }

    private fun MediaMetadataRetriever.setDataSourceCompat(path: String) {
        if (path.startsWith("content://")) {
            setDataSource(AndroidContext.get(), path.toUri())
        } else {
            setDataSource(path)
        }
    }

    private fun normalizePathForFFmpeg(path: String): String {
        return if (path.startsWith("content://")) {
            val context = AndroidContext.get()
            val uri = path.toUri()
            FFmpegKitConfig.getSafParameterForRead(context, uri)
        } else {
            "\"$path\""
        }
    }

    private fun evenUp(value: Int): Int = if (value % 2 == 0) value else value + 1

    /**
     * Create a temp file in cache dir for FFmpeg output, then copy to final destination.
     * This avoids SAF URI issues with FFmpegKit's output path handling.
     */
    private fun createTempOutputFile(extension: String): File {
        val cacheDir = File(AndroidContext.get().cacheDir, "export")
        cacheDir.mkdirs()
        return File(cacheDir, "export_${System.currentTimeMillis()}.$extension")
    }

    private fun copyToFinalDestination(tempFile: File, outputPath: String) {
        if (outputPath.startsWith("content://")) {
            val context = AndroidContext.get()
            val uri = outputPath.toUri()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tempFile).use { input ->
                    input.copyTo(out)
                }
            }
        } else {
            tempFile.copyTo(File(outputPath), overwrite = true)
        }
        tempFile.delete()
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
        var tempFile: File? = null
        try {
            _exportProgress.value = ExportProgress(phase = "Preparing…", progress = 0f)

            val videoClips = project.timeline.tracks
                .filter { it.type == TrackType.Video && !it.isMuted }
                .flatMap { it.clips.filterIsInstance<Clip.VideoClip>() }
                .sortedBy { it.startMs }

            if (videoClips.isEmpty()) {
                _exportProgress.value = ExportProgress(error = "No video clips on timeline")
                return@withContext
            }

            val textClips = project.timeline.tracks
                .filter { it.type == TrackType.Text && !it.isMuted }
                .flatMap { it.clips.filterIsInstance<Clip.TextClip>() }

            val firstInfo = probeVideo(videoClips.first().sourcePath)
            val outWidth = evenUp(
                if (project.exportSettings.width > 0) project.exportSettings.width
                else firstInfo.width
            )
            val outHeight = evenUp(
                if (project.exportSettings.height > 0) project.exportSettings.height
                else firstInfo.height
            )

            val audioTracks = project.timeline.tracks
                .filter { it.type == TrackType.Audio && !it.isMuted }
            val audioClips = audioTracks
                .flatMap { track -> track.clips.filterIsInstance<Clip.AudioClip>() }

            val totalDurationMs = videoClips.sumOf { it.durationMs }.toFloat()

            // Export to temp file to avoid SAF URI issues
            tempFile = createTempOutputFile(project.exportSettings.format.extension)

            val command = buildFFmpegCommand(
                videoClips = videoClips,
                audioClips = audioClips,
                textClips = textClips,
                format = project.exportSettings.format,
                quality = project.exportSettings.quality,
                outWidth = outWidth,
                outHeight = outHeight,
                outputPath = tempFile.absolutePath,
            )

            _exportProgress.value = ExportProgress(phase = "Exporting…", progress = 0f)

            executeFFmpegCommand(command, totalDurationMs, tempFile.absolutePath)

            // Copy temp file to final destination
            if (_exportProgress.value.isComplete) {
                _exportProgress.value = ExportProgress(phase = "Saving…", progress = 0.99f)
                copyToFinalDestination(tempFile, outputPath)
                _exportProgress.value = ExportProgress(
                    phase = "Complete",
                    progress = 1f,
                    isComplete = true,
                )
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            tempFile?.delete()
            throw e
        } catch (e: Exception) {
            tempFile?.delete()
            _exportProgress.value = ExportProgress(
                error = "Export failed: ${e.message}"
            )
            e.printStackTrace()
        }
    }

    private suspend fun buildFFmpegCommand(
        videoClips: List<Clip.VideoClip>,
        audioClips: List<Clip.AudioClip>,
        textClips: List<Clip.TextClip>,
        format: ExportFormat,
        quality: Float,
        outWidth: Int,
        outHeight: Int,
        outputPath: String,
    ): String {
        val sb = StringBuilder()
        val hasExtraAudio = audioClips.isNotEmpty()

        sb.append("-y ")

        for (clip in videoClips) {
            sb.append("-i ${normalizePathForFFmpeg(clip.sourcePath)} ")
        }

        for (clip in audioClips) {
            sb.append("-i ${normalizePathForFFmpeg(clip.sourcePath)} ")
        }

        val filterComplex = StringBuilder()
        val videoInputCount = videoClips.size

        val clipHasAudio = mutableListOf<Boolean>()
        for (clip in videoClips) {
            val info = probeVideo(clip.sourcePath)
            clipHasAudio.add(info.hasAudio)
        }
        val anyEmbeddedAudio = clipHasAudio.any { it }
        val hasAudio = anyEmbeddedAudio || hasExtraAudio

        for ((i, clip) in videoClips.withIndex()) {
            val vf = FilterGraphBuilder.buildVideoFilterString(
                filters = clip.filters,
                speed = clip.speed,
                outWidth = outWidth,
                outHeight = outHeight,
            )
            val trimFilter = "trim=start=${clip.sourceStartMs / 1000.0}:" +
                    "end=${clip.sourceEndMs / 1000.0},setpts=PTS-STARTPTS"
            filterComplex.append("[$i:v]$trimFilter,$vf[v$i];")

            if (clipHasAudio[i]) {
                val af = FilterGraphBuilder.buildAudioFilterString(
                    speed = clip.speed,
                    volume = clip.volume,
                )
                val atrimFilter = "atrim=start=${clip.sourceStartMs / 1000.0}:" +
                        "end=${clip.sourceEndMs / 1000.0},asetpts=PTS-STARTPTS"
                val audioFilters = if (af.isNotEmpty()) "$atrimFilter,$af" else atrimFilter
                filterComplex.append("[$i:a]$audioFilters[va$i];")
            } else if (anyEmbeddedAudio) {
                val durationSec = clip.durationMs / 1000.0
                filterComplex.append(
                    "anullsrc=r=44100:cl=stereo[silence$i];" +
                    "[silence$i]atrim=0:$durationSec,asetpts=PTS-STARTPTS[va$i];"
                )
            }
        }

        // Concat video
        for (i in videoClips.indices) {
            filterComplex.append("[v$i]")
        }
        filterComplex.append("concat=n=${videoClips.size}:v=1:a=0[outv_raw];")

        // Apply text overlays after concat
        val textFilters = FilterGraphBuilder.buildPostConcatTextFilters(
            videoClips = videoClips,
            textClips = textClips,
            fontPath = ANDROID_FONT,
        )
        if (textFilters.isNotEmpty()) {
            filterComplex.append("[outv_raw]$textFilters[outv];")
        } else {
            filterComplex.append("[outv_raw]null[outv];")
        }

        // Concat embedded audio
        if (anyEmbeddedAudio) {
            for (i in videoClips.indices) {
                filterComplex.append("[va$i]")
            }
            filterComplex.append("concat=n=${videoClips.size}:v=0:a=1[video_audio];")
        }

        // Process separate audio clips
        val audioInputs = mutableListOf<String>()
        if (anyEmbeddedAudio) {
            audioInputs.add("[video_audio]")
        }

        for ((i, clip) in audioClips.withIndex()) {
            val inputIdx = videoInputCount + i
            val af = mutableListOf<String>()

            af.add("atrim=start=${clip.sourceStartMs / 1000.0}:end=${clip.sourceEndMs / 1000.0}")
            af.add("asetpts=PTS-STARTPTS")

            if (clip.volume != 1.0f) {
                af.add("volume=${clip.volume}")
            }

            if (clip.fadeInMs > 0) {
                af.add("afade=t=in:st=0:d=${clip.fadeInMs / 1000.0}")
            }
            if (clip.fadeOutMs > 0) {
                val fadeStart = (clip.durationMs - clip.fadeOutMs) / 1000.0
                af.add("afade=t=out:st=$fadeStart:d=${clip.fadeOutMs / 1000.0}")
            }

            val delayMs = clip.startMs
            if (delayMs > 0) {
                af.add("adelay=${delayMs}|${delayMs}")
            }

            filterComplex.append("[$inputIdx:a]${af.joinToString(",")}[audio$i];")
            audioInputs.add("[audio$i]")
        }

        if (audioInputs.size > 1) {
            filterComplex.append("${audioInputs.joinToString("")}amix=inputs=${audioInputs.size}:duration=longest:normalize=1[outa]")
        } else if (audioInputs.size == 1) {
            filterComplex.append("${audioInputs[0]}acopy[outa]")
        }

        val finalFilterComplex = filterComplex.toString().trimEnd(';')

        sb.append("-filter_complex $finalFilterComplex ")
        sb.append("-map [outv] ")
        if (hasAudio) sb.append("-map [outa] ")

        appendCodecSettings(sb, format, quality, hasAudio)

        sb.append(outputPath)

        return sb.toString()
    }

    /**
     * Codec settings for LGPL FFmpegKit build.
     * libx264 is NOT available (requires GPL build).
     * mpeg4 is always available.
     * libvpx-vp9 is included in LGPL builds for WebM.
     */
    private fun appendCodecSettings(
        sb: StringBuilder,
        format: ExportFormat,
        quality: Float,
        hasAudio: Boolean,
    ) {
        when (format) {
            ExportFormat.MP4, ExportFormat.MOV -> {
                // Use bitrate-based quality since mpeg4 doesn't support CRF.
                sb.append("-c:v mpeg4 ")
                val bitrate = (quality / 100f * 8000f + 500f).toInt().coerceIn(500, 8500)
                sb.append("-b:v ${bitrate}k ")
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
        tempOutputPath: String,
    ) = suspendCancellableCoroutine { continuation ->

        val session = FFmpegKit.executeAsync(
            command,
            { session ->
                val returnCode = session.returnCode
                when {
                    ReturnCode.isSuccess(returnCode) -> {
                        // Mark as complete — caller will copy to final destination
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
                        try { File(tempOutputPath).delete() } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                    else -> {
                        val logs = session.allLogsAsString ?: ""
                        val errorDetail = when {
                            "Unknown encoder" in logs -> {
                                val encoder = Regex("Unknown encoder '(\\w+)'")
                                    .find(logs)?.groupValues?.get(1) ?: "unknown"
                                "Encoder '$encoder' not available."
                            }
                            "Error opening output" in logs ->
                                "Cannot write to output location. Check storage permissions."
                            else -> session.failStackTrace ?: "Unknown error (code ${returnCode.value})"
                        }
                        _exportProgress.value = ExportProgress(
                            error = "Export failed: $errorDetail"
                        )
                        try { File(tempOutputPath).delete() } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                RuntimeException("FFmpeg failed: $errorDetail")
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
            try { File(tempOutputPath).delete() } catch (_: Exception) {}
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