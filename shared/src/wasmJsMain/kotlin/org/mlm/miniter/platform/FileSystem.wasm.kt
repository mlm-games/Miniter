package org.mlm.miniter.platform

import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mlm.miniter.rust.RustCoreSession

actual object PlatformFileSystem {

    private val files = mutableMapOf<String, String>()
    private val nativeRegistered = mutableSetOf<String>()

    actual suspend fun readText(path: String): String = withContext(Dispatchers.Default) {
        files[path]
            ?: WasmPlatformFileRegistry.get(path)?.readString()
            ?: throw IllegalStateException("File not found in wasm fs: $path")
    }

    actual suspend fun writeText(path: String, content: String) = withContext(Dispatchers.Default) {
        files[path] = content
    }

    actual fun exists(path: String): Boolean =
        files.containsKey(path) || WasmPlatformFileRegistry.contains(path)

    actual fun delete(path: String): Boolean =
        (files.remove(path) != null || WasmPlatformFileRegistry.remove(path)).also { deleted ->
            if (deleted) nativeRegistered.remove(path)
        }

    actual fun getParentDirectory(path: String): String = path.substringBeforeLast('/', "")

    actual fun combinePath(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent/$child"

    actual fun getAppDataDirectory(appName: String): String = "/$appName"

    actual suspend fun stageForNativeAccess(path: String): String {
        if (!WasmPlatformFileRegistry.contains(path)) {
            return path
        }

        if (nativeRegistered.contains(path)) {
            return path
        }

        val platformFile = WasmPlatformFileRegistry.get(path)
            ?: throw IllegalStateException("Missing staged wasm file: $path")

        val bytes = platformFile.readBytes()
        val ext = platformFile.name.substringAfterLast('.', "")

        RustCoreSession.registerFile(
            path = path,
            bytes = bytes,
            extension = ext.ifBlank { null },
        )

        nativeRegistered.add(path)

        return path
    }
}
