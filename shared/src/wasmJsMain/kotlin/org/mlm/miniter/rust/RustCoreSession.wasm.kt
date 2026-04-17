package org.mlm.miniter.rust

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mlm.miniter.engine.ImageData
import org.mlm.miniter.engine.VideoInfo
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val wasmBridgeJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

@Serializable
private data class WasmVideoProbePayload(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val videoCodec: String,
    val hasAudio: Boolean,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val videoBitrate: Int,
)

@Serializable
private data class WasmFramePayload(
    val width: Int,
    val height: Int,
    val rgbaBase64: String,
    val ptsUs: Long,
)

@Serializable
internal data class WasmExportPayload(
    val ok: Boolean,
    val bytesBase64: String,
    val fileName: String,
    val mimeType: String,
)

private fun decodeBase64ToBytes(encoded: String): ByteArray {
    if (encoded.isEmpty()) return ByteArray(0)

    val clean = encoded.trim().replace("\n", "").replace("\r", "")
    val output = ByteArray((clean.length * 3) / 4)
    var outIndex = 0
    var i = 0

    fun value(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch.code - 'A'.code
        in 'a'..'z' -> ch.code - 'a'.code + 26
        in '0'..'9' -> ch.code - '0'.code + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    while (i < clean.length) {
        val c0 = clean.getOrNull(i++) ?: break
        val c1 = clean.getOrNull(i++) ?: break
        val c2 = clean.getOrNull(i++) ?: '='
        val c3 = clean.getOrNull(i++) ?: '='

        val b0 = value(c0)
        val b1 = value(c1)
        val b2 = if (c2 == '=') -1 else value(c2)
        val b3 = if (c3 == '=') -1 else value(c3)

        if (b0 < 0 || b1 < 0 || (b2 < 0 && c2 != '=') || (b3 < 0 && c3 != '=')) {
            return ByteArray(0)
        }

        val triple = (b0 shl 18) or (b1 shl 12) or
            ((if (b2 >= 0) b2 else 0) shl 6) or
            (if (b3 >= 0) b3 else 0)

        if (outIndex < output.size) output[outIndex++] = ((triple shr 16) and 0xFF).toByte()
        if (c2 != '=' && outIndex < output.size) output[outIndex++] = ((triple shr 8) and 0xFF).toByte()
        if (c3 != '=' && outIndex < output.size) output[outIndex++] = (triple and 0xFF).toByte()
    }

    return if (outIndex == output.size) output else output.copyOf(outIndex)
}

private fun WasmFramePayload.toImageData(): ImageData {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val decoded = decodeBase64ToBytes(rgbaBase64)
    return if (decoded.size == safeWidth * safeHeight * 4) {
        ImageData.create(safeWidth, safeHeight, decoded)
    } else {
        ImageData.create(1, 1, byteArrayOf(0, 0, 0, 0xFF.toByte()))
    }
}

actual class RustCoreSession private constructor(
    private val handle: WasmEditorHandle,
) {
    actual constructor(projectName: String) : this(WasmEditorHandle(projectName))

    actual fun toJson(): String = handle.toJson()

    actual fun dispatch(commandJson: String): Boolean = handle.dispatch(commandJson)

    actual fun undo(): Boolean = handle.undo()
    actual fun redo(): Boolean = handle.redo()
    actual fun canUndo(): Boolean = handle.canUndo()
    actual fun canRedo(): Boolean = handle.canRedo()

    actual fun playheadUs(): Long = handle.playheadUs().toLong()

    actual fun setPlayheadUs(us: Long) {
        handle.setPlayheadUs(us.toDouble())
    }

    actual fun renderPlanAtPlayhead(width: Int, height: Int): String =
        handle.renderPlanAtPlayhead(width.toDouble(), height.toDouble())

    actual fun durationUs(): Long = handle.durationUs().toLong()

    actual companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun registerFile(path: String, bytes: ByteArray, extension: String?) {
            val encoded = Base64.encode(bytes)
            val ok = wasmRegisterFile(path, encoded, extension)
            if (!ok) {
                error("Failed to register wasm file: $path")
            }
        }

        actual fun fromJson(json: String): RustCoreSession =
            RustCoreSession(WasmEditorHandle.fromJson(json))

        actual fun probeAudio(path: String): String = wasmProbeAudio(path)

        actual fun extractWaveform(path: String, buckets: Int): String =
            wasmExtractWaveform(path, buckets.toDouble())

        actual fun probeVideo(path: String): VideoInfo {
            val payload = try {
                wasmBridgeJson.decodeFromString<WasmVideoProbePayload>(wasmProbeVideo(path))
            } catch (_: SerializationException) {
                return VideoInfo(
                    durationMs = 0L,
                    width = 0,
                    height = 0,
                    frameRate = 0.0,
                    videoBitrate = 0,
                    audioChannels = 0,
                    audioSampleRate = 0,
                    audioCodecName = null,
                    videoCodecName = null,
                    hasAudio = false,
                    hasVideo = false,
                )
            }
            return VideoInfo(
                durationMs = payload.durationUs / 1000L,
                width = payload.width,
                height = payload.height,
                frameRate = payload.frameRate,
                videoBitrate = payload.videoBitrate,
                audioChannels = payload.audioChannels,
                audioSampleRate = payload.audioSampleRate,
                audioCodecName = null,
                videoCodecName = payload.videoCodec,
                hasAudio = payload.hasAudio,
                hasVideo = payload.width > 0 && payload.height > 0,
            )
        }

        actual fun extractThumbnail(path: String, timestampUs: Long): ImageData {
            val payload = try {
                wasmBridgeJson.decodeFromString<WasmFramePayload>(
                    wasmExtractThumbnail(path, timestampUs.toDouble())
                )
            } catch (_: SerializationException) {
                return ImageData.create(1, 1, byteArrayOf(0, 0, 0, 0xFF.toByte()))
            }
            return payload.toImageData()
        }

        actual fun extractThumbnails(path: String, count: Int, durationUs: Long): List<ImageData> {
            val payload = try {
                wasmBridgeJson.decodeFromString<List<WasmFramePayload>>(
                    wasmExtractThumbnails(path, count.toDouble(), durationUs.toDouble())
                )
            } catch (_: SerializationException) {
                return emptyList()
            }
            return payload.map { it.toImageData() }
        }

        actual fun exportProjectJson(projectJson: String, outputPath: String): Boolean =
            wasmExportProjectJson(projectJson, outputPath)

        internal fun exportProjectBlob(projectJson: String, outputPath: String): WasmExportPayload {
            return wasmBridgeJson.decodeFromString(wasmExportProjectBlob(projectJson, outputPath))
        }

        actual fun cancelExport() {
            wasmCancelExport()
        }

        actual fun exportProgress(): UInt = wasmExportProgress().toUInt()
    }
}
