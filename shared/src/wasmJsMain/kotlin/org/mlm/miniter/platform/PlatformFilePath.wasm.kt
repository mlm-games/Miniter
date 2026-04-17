package org.mlm.miniter.platform

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name

internal object WasmPlatformFileRegistry {
    private var nextId: Long = 0L
    private val pathsByFile = mutableMapOf<PlatformFile, String>()
    private val files = mutableMapOf<String, PlatformFile>()

    fun remember(file: PlatformFile): String {
        pathsByFile[file]?.let { return it }

        nextId += 1L
        val fileName = file.name.ifBlank { "file-$nextId" }
        val key = "wasm://local/$nextId/$fileName"
        pathsByFile[file] = key
        files[key] = file
        return key
    }

    fun get(path: String): PlatformFile? = files[path]

    fun contains(path: String): Boolean = files.containsKey(path)

    fun remove(path: String): Boolean {
        val removed = files.remove(path) ?: return false
        pathsByFile.remove(removed)
        WasmPlaybackUriCache.forget(path)
        return true
    }
}

actual fun PlatformFile.platformPath(): String = WasmPlatformFileRegistry.remember(this)
