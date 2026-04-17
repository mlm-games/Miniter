package org.mlm.miniter.platform

import org.mlm.miniter.rust.RustCoreSession

internal object WasmPlaybackUriCache {
    private val urlsByPath = mutableMapOf<String, String>()

    fun resolve(path: String): String =
        urlsByPath.getOrPut(path) { RustCoreSession.mediaBlobUrl(path) }

    fun forget(path: String) {
        val url = urlsByPath.remove(path) ?: return
        runCatching { RustCoreSession.revokeBlobUrl(url) }
    }
}

actual fun normalizeMediaUriForPlayback(path: String): String {
    if (!path.startsWith("wasm://local/")) return path
    return runCatching { WasmPlaybackUriCache.resolve(path) }.getOrDefault("")
}
