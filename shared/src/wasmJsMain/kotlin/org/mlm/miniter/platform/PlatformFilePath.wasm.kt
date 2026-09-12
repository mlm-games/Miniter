package org.mlm.miniter.platform

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import org.mlm.miniter.rust.RustCoreSession

internal object WasmPlatformFileRegistry {
    private const val MAX_ENTRIES = 50
    private var nextId: Long = 0L
    private val pathsByFile = mutableMapOf<PlatformFile, String>()
    private val files = mutableMapOf<String, PlatformFile>()
    private val lastAccess = mutableMapOf<String, Long>()
    private var accessTick = 0L

    fun remember(file: PlatformFile): String {
        pathsByFile[file]?.let {
            touch(it)
            return it
        }

        evictIfNeeded()

        nextId += 1L
        val fileName = file.name.ifBlank { "file-$nextId" }
        val key = "wasm://local/$nextId/$fileName"
        pathsByFile[file] = key
        files[key] = file
        touch(key)
        return key
    }

    fun get(path: String): PlatformFile? {
        touch(path)
        return files[path]
    }

    fun contains(path: String): Boolean = files.containsKey(path)

    fun remove(path: String): Boolean {
        val removed = files.remove(path) ?: return false
        pathsByFile.remove(removed)
        lastAccess.remove(path)
        WasmPlaybackUriCache.forget(path)
        runCatching { RustCoreSession.unregisterFile(path) }
        return true
    }

    private fun touch(path: String) {
        if (files.containsKey(path)) {
            lastAccess[path] = ++accessTick
        }
    }

    private fun evictIfNeeded() {
        while (files.size >= MAX_ENTRIES) {
            val oldest = lastAccess.minByOrNull { it.value }?.key
                ?: files.keys.firstOrNull()
                ?: break
            remove(oldest)
        }
    }
}

actual fun PlatformFile.platformPath(): String = WasmPlatformFileRegistry.remember(this)
