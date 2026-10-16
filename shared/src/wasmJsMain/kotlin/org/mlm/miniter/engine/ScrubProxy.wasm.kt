package org.mlm.miniter.engine

actual object ScrubProxy {
    actual fun setCacheRoot(path: String) = Unit

    actual fun cancel() = Unit

    actual suspend fun ensure(path: String, hardwareAcceleration: Boolean): ProxyStatus =
        ProxyStatus(
            ready = false,
            path = "",
            width = 0,
            height = 0,
            bytes = 0,
            frames = 0,
        )
}