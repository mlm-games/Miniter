package org.mlm.miniter.platform

import org.mlm.miniter.rust.RustCoreSession

internal object WasmPlaybackUriCache {
    private val urlsByPath = mutableMapOf<String, String>()

    fun resolve(path: String): String {
        val cached = urlsByPath[path]
        if (cached != null) return cached
        val url = try {
            RustCoreSession.mediaBlobUrl(path)
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to create playback URL for $path: ${e.message}", e)
        }
        if (!url.startsWith("blob:")) {
            throw IllegalStateException("Failed to create playback URL for $path (bridge returned no blob URL)")
        }
        urlsByPath[path] = url
        return url
    }

    fun forget(path: String) {
        val url = urlsByPath.remove(path) ?: return
        runCatching { RustCoreSession.revokeBlobUrl(url) }
    }

    fun delete(path: String) = forget(path)
}

actual fun normalizeMediaUriForPlayback(path: String): String {
    if (!path.startsWith("wasm://local/")) return path
    return WasmPlaybackUriCache.resolve(path)
}
