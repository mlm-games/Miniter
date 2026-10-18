package org.mlm.miniter.engine

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mlm.miniter.ffi.EditorHandle as NativeEditorHandle
import org.mlm.miniter.platform.synchronized as platformSynchronized
import org.mlm.miniter.rust.RustCoreSession

actual class PlatformFrameGrabber {

    private val mutex = Mutex()
    private val guard = Any()
    @Volatile
    private var currentPath: String? = null
    private var invalidateEpoch = 0L

    private var compositeExecutor: ExecutorCoroutineDispatcher? = null
    private var compositeHandle: NativeEditorHandle? = null

    actual suspend fun open(path: String) {
        mutex.withLock {
            val epoch = platformSynchronized(guard) { invalidateEpoch }
            platformSynchronized(guard) {
                if (epoch == invalidateEpoch) {
                    currentPath = path
                }
            }
        }
    }

    actual suspend fun grabFrame(
        timestampMs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
    ): ImageData? = withContext(Dispatchers.IO) {
        val path = platformSynchronized(guard) { currentPath } ?: return@withContext null

        try {
            val frame = RustCoreSession.extractThumbnail(path, timestampMs * 1000L, hardwareAcceleration)
            val finalW = if (width > 0) width else frame.width
            val finalH = if (height > 0) height else frame.height
            if (frame.width != finalW || frame.height != finalH) {
                return@withContext scaleRgbaToImageData(frame.pixels, frame.width, frame.height, finalW, finalH)
            }

            ImageData(frame.width, frame.height, frame.pixels)
        } catch (e: Exception) {
            null
        }
    }

    actual suspend fun openProject(projectJson: String) {
        val previous = withContext(compositeDispatcher()) {
            val handle = NativeEditorHandle.fromJson(projectJson)
            val old = compositeHandle
            compositeHandle = handle
            old
        }
        previous?.close()
    }

    actual suspend fun grabCompositeFrame(
        timestampUs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
        ignoreCropClipId: String?,
    ): ImageData? = withContext(compositeDispatcher()) {
        val handle = compositeHandle ?: return@withContext null
        try {
            val frame = handle.renderPreviewFrame(
                timestampUs,
                width.toUInt(),
                height.toUInt(),
                hardwareAcceleration,
                ignoreCropClipId,
            )
            ImageData.create(frame.width.toInt(), frame.height.toInt(), frame.rgba.copyOf())
        } catch (_: Exception) {
            null
        }
    }

    actual fun release() {
        platformSynchronized(guard) {
            invalidateEpoch++
            currentPath = null
        }
        compositeHandle?.close()
        compositeHandle = null
        compositeExecutor?.close()
        compositeExecutor = null
    }

    private fun compositeDispatcher(): ExecutorCoroutineDispatcher {
        compositeExecutor?.let { return it }
        val created = Executors
            .newSingleThreadExecutor { runnable -> Thread(runnable, "miniter-preview-render") }
            .asCoroutineDispatcher()
        compositeExecutor = created
        return created
    }
}

private fun scaleRgbaToImageData(
    src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int
): ImageData {
    val dst = ByteArray(dstW * dstH * 4)
    for (y in 0 until dstH) {
        val srcY = y * srcH / dstH
        for (x in 0 until dstW) {
            val srcX = x * srcW / dstW
            val si = (srcY * srcW + srcX) * 4
            val di = (y * dstW + x) * 4
            dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
        }
    }
    return ImageData(dstW, dstH, dst)
}
