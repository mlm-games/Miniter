package org.mlm.miniter.engine

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.synchronized as platformSynchronized
import org.mlm.miniter.rust.RustCoreSession
import org.mlm.miniter.rust.WasmEditorHandle

@Serializable
private data class WasmCompositeFramePayload(
    val width: Int,
    val height: Int,
    val rgbaBase64: String,
    val ptsUs: Long = 0,
)

private val compositePayloadJson = Json { ignoreUnknownKeys = true }

actual class PlatformFrameGrabber actual constructor() {
    private val mutex = Mutex()
    private val guard = Any()
    private var currentPath: String? = null
    private var invalidateEpoch = 0L
    private var compositeHandle: WasmEditorHandle? = null

    actual suspend fun open(path: String) {
        mutex.withLock {
            val epoch = platformSynchronized(guard) { invalidateEpoch }
            val localPath = PlatformFileSystem.stageForNativeAccess(path)
            platformSynchronized(guard) {
                if (epoch == invalidateEpoch) {
                    currentPath = localPath
                }
            }
        }
    }

    actual suspend fun grabFrame(
        timestampMs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
    ): ImageData? = withContext(Dispatchers.Default) {
        val path = platformSynchronized(guard) { currentPath } ?: return@withContext null

        try {
            val frame = RustCoreSession.extractThumbnail(path, timestampMs * 1000L, hardwareAcceleration)
            val finalW = if (width > 0) width else frame.width
            val finalH = if (height > 0) height else frame.height
            if (frame.width != finalW || frame.height != finalH) {
                return@withContext scaleRgbaToImageData(frame.pixels, frame.width, frame.height, finalW, finalH)
            }

            ImageData.create(frame.width, frame.height, frame.pixels)
        } catch (_: Throwable) {
            null
        }
    }

    actual suspend fun openProject(projectJson: String) {
        compositeHandle = WasmEditorHandle.fromJson(projectJson)
    }

    @OptIn(ExperimentalEncodingApi::class)
    actual suspend fun grabCompositeFrame(
        timestampUs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
        ignoreCropClipId: String?,
    ): ImageData? = withContext(Dispatchers.Default) {
        val handle = compositeHandle ?: return@withContext null
        try {
            val payload = compositePayloadJson.decodeFromString<WasmCompositeFramePayload>(
                handle.renderPreviewFrame(
                    timestampUs.toDouble(),
                    width.toDouble(),
                    height.toDouble(),
                    hardwareAcceleration,
                    ignoreCropClipId ?: "",
                )
            )
            val pixels = Base64.decode(payload.rgbaBase64)
            if (pixels.size == payload.width * payload.height * 4) {
                ImageData.create(payload.width, payload.height, pixels)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    actual fun release() {
        platformSynchronized(guard) {
            invalidateEpoch++
            currentPath = null
        }
        compositeHandle = null
    }
}

private fun scaleRgbaToImageData(
    src: ByteArray,
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
): ImageData {
    val dst = ByteArray(dstW * dstH * 4)
    for (y in 0 until dstH) {
        val srcY = y * srcH / dstH
        for (x in 0 until dstW) {
            val srcX = x * srcW / dstW
            val si = (srcY * srcW + srcX) * 4
            val di = (y * dstW + x) * 4
            dst[di] = src[si]
            dst[di + 1] = src[si + 1]
            dst[di + 2] = src[si + 2]
            dst[di + 3] = src[si + 3]
        }
    }
    return ImageData.create(dstW, dstH, dst)
}
