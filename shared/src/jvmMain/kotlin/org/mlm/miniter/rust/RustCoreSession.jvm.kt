package org.mlm.miniter.rust

import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.VideoInfo
import org.mlm.miniter.ffi.EditorHandle as NativeEditorHandle
import org.mlm.miniter.ffi.cancelExport as nativeCancelExport
import org.mlm.miniter.ffi.exportProgress as nativeExportProgress
import org.mlm.miniter.ffi.exportProjectJson as nativeExportProjectJson
import org.mlm.miniter.ffi.extractThumbnail as nativeExtractThumbnail
import org.mlm.miniter.ffi.extractThumbnails as nativeExtractThumbnails
import org.mlm.miniter.ffi.extractWaveform as nativeExtractWaveform
import org.mlm.miniter.ffi.probeAudio as nativeProbeAudio
import org.mlm.miniter.ffi.probeVideo as nativeProbeVideo

actual class RustCoreSession private constructor(
    private val handle: NativeEditorHandle,
) {
    actual constructor(projectName: String) : this(
        NativeEditorHandle(projectName)
    )

    actual fun toJson(): String = handle.toJson()

    actual fun dispatch(commandJson: String): Boolean =
        handle.dispatch(commandJson)

    actual fun undo(): Boolean = handle.undo()

    actual fun redo(): Boolean = handle.redo()

    actual fun canUndo(): Boolean = handle.canUndo()

    actual fun canRedo(): Boolean = handle.canRedo()

    actual fun playheadUs(): Long = handle.playheadUs()

    actual fun setPlayheadUs(us: Long) {
        handle.setPlayheadUs(us)
    }

    actual fun renderPlanAtPlayhead(width: Int, height: Int): String =
        handle.renderPlanAtPlayhead(width.toUInt(), height.toUInt())

    actual fun durationUs(): Long = handle.durationUs()

    actual companion object {
        actual fun fromJson(json: String): RustCoreSession =
            RustCoreSession(NativeEditorHandle.fromJson(json))

        actual fun probeAudio(path: String): String =
            nativeProbeAudio(path)

        actual fun extractWaveform(path: String, buckets: Int): String =
            nativeExtractWaveform(path, buckets.toUInt())

        actual fun probeVideo(path: String): VideoInfo {
            val result = nativeProbeVideo(path)
            return VideoInfo(
                durationMs = result.durationUs / 1000L,
                width = result.width.toInt(),
                height = result.height.toInt(),
                frameRate = result.frameRate,
                videoBitrate = result.videoBitrate.toInt(),
                audioChannels = result.audioChannels.toInt(),
                audioSampleRate = result.audioSampleRate.toInt(),
                audioCodecName = null,
                videoCodecName = result.videoCodec,
                hasAudio = result.hasAudio,
                hasVideo = result.width > 0u && result.height > 0u,
            )
        }

        actual fun extractThumbnail(path: String, timestampUs: Long): ImageData {
            val frame = nativeExtractThumbnail(path, timestampUs)
            return ImageData.create(
                width = frame.width.toInt(),
                height = frame.height.toInt(),
                pixels = frame.rgba.copyOf(),
            )
        }

        actual fun extractThumbnails(path: String, count: Int, durationUs: Long): List<ImageData> {
            return nativeExtractThumbnails(path, count.toUInt(), durationUs).map { frame ->
                ImageData.create(
                    width = frame.width.toInt(),
                    height = frame.height.toInt(),
                    pixels = frame.rgba.copyOf(),
                )
            }
        }

        actual fun exportProjectJson(projectJson: String, outputPath: String): Boolean =
            nativeExportProjectJson(projectJson, outputPath)

        actual fun cancelExport() {
            nativeCancelExport()
        }

        actual fun exportProgress(): UInt =
            nativeExportProgress()
    }
}
