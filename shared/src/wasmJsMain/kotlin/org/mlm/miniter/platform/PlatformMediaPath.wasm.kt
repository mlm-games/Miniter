package org.mlm.miniter.platform

import org.mlm.miniter.rust.RustCoreSession

internal object WasmPlaybackUriCache {
    private val urlsByPath = mutableMapOf<String, String>()

    fun resolve(path: String): String {
        val cached = urlsByPath[path]
        if (cached != null) return cached
        val url = runCatching { RustCoreSession.mediaBlobUrl(path) }.getOrNull().orEmpty()
        if (url.startsWith("blob:")) urlsByPath[path] = url
        return url
    }

    fun forget(path: String) {
        val url = urlsByPath.remove(path) ?: return
        runCatching { RustCoreSession.revokeBlobUrl(url) }
    }
}

actual fun normalizeMediaUriForPlayback(path: String): String {
    if (!path.startsWith("wasm://local/")) return path
    return runCatching { WasmPlaybackUriCache.resolve(path) }.getOrDefault("")
}
