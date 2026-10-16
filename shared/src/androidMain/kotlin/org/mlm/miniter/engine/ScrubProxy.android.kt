package org.mlm.miniter.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mlm.miniter.ffi.cancelProxyGeneration as nativeCancelProxyGeneration
import org.mlm.miniter.ffi.ensureProxy as nativeEnsureProxy
import org.mlm.miniter.ffi.setProxyCacheRoot as nativeSetProxyCacheRoot

actual object ScrubProxy {
    actual fun setCacheRoot(path: String) {
        nativeSetProxyCacheRoot(path)
    }

    actual fun cancel() {
        nativeCancelProxyGeneration()
    }

    actual suspend fun ensure(path: String, hardwareAcceleration: Boolean): ProxyStatus =
        withContext(Dispatchers.IO) {
            val status = nativeEnsureProxy(path, hardwareAcceleration)
            ProxyStatus(
                ready = status.ready,
                path = status.path,
                width = status.width.toInt(),
                height = status.height.toInt(),
                bytes = status.bytes.toLong(),
                frames = status.frames.toInt(),
            )
        }
}