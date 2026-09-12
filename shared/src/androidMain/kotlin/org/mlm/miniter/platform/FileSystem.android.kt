package org.mlm.miniter.platform

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

actual object PlatformFileSystem {

    actual suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        if (!path.startsWith("content://")) {
            return@withContext File(path).readText(Charsets.UTF_8)
        }

        AndroidContext.get()
            .contentResolver
            .openInputStream(Uri.parse(path))
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw FileNotFoundException("Cannot read: $path")
    }

    actual suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        if (!path.startsWith("content://")) {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            return@withContext
        }

        AndroidContext.get()
            .contentResolver
            .openOutputStream(Uri.parse(path))
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(content) }
            ?: throw FileNotFoundException("Cannot write: $path")
    }

    actual fun exists(path: String): Boolean {
        if (!path.startsWith("content://")) return File(path).exists()

        return try {
            AndroidContext.get()
                .contentResolver
                .openFileDescriptor(Uri.parse(path), "r")
                ?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    actual fun delete(path: String): Boolean {
        if (!path.startsWith("content://")) return File(path).delete()

        return runCatching {
            AndroidContext.get()
                .contentResolver
                .delete(Uri.parse(path), null, null) > 0
        }.getOrDefault(false)
    }

    actual fun getParentDirectory(path: String): String {
        return if (path.startsWith("content://")) "" else File(path).parent ?: ""
    }

    actual fun combinePath(parent: String, child: String): String {
        return File(parent, child).absolutePath
    }

    actual fun getAppDataDirectory(appName: String): String {
        val context = AndroidContext.get()
        val dir = File(context.filesDir, appName)
        dir.mkdirs()
        return dir.absolutePath
    }

    actual suspend fun stageForNativeAccess(path: String): String =
        materializeReadablePath(path)
}

internal data class PreparedOutputPath(
    val localPath: String,
    val commit: suspend () -> Unit = {},
    val discard: suspend () -> Unit = {},
)

internal const val NATIVE_INPUTS_MAX_BYTES = 500L * 1024L * 1024L
internal const val NATIVE_INPUTS_MAX_FILES = 50

internal fun pruneNativeInputs(dir: File) {
    try {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var totalBytes = files.sumOf { it.length() }
        var remaining = files.size
        if (remaining <= NATIVE_INPUTS_MAX_FILES && totalBytes <= NATIVE_INPUTS_MAX_BYTES) return
        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (remaining <= NATIVE_INPUTS_MAX_FILES && totalBytes <= NATIVE_INPUTS_MAX_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                remaining--
            }
        }
    } catch (_: Exception) {
    }
}

internal suspend fun materializeReadablePath(path: String): String = withContext(Dispatchers.IO) {
    if (!path.startsWith("content://")) return@withContext path

    val context = AndroidContext.get()
    val uri = Uri.parse(path)
    val displayName = queryDisplayName(uri)
        ?: "picked_${path.hashCode().toUInt().toString(16)}"
    val safeName = sanitizeFileName(displayName)

    val dir = File(context.filesDir, "native-inputs").apply { mkdirs() }
    val outFile = File(dir, "${path.hashCode().toUInt().toString(16)}_$safeName")

    val sourceSize = querySize(uri)
    val stagedValid = outFile.exists() && outFile.length() > 0L &&
        (sourceSize == null || sourceSize < 0L || outFile.length() == sourceSize)

    if (!stagedValid) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException("Cannot open input URI: $path")
    } else {
        outFile.setLastModified(System.currentTimeMillis())
    }

    pruneNativeInputs(dir)

    outFile.absolutePath
}

internal suspend fun prepareWritablePath(path: String): PreparedOutputPath =
    withContext(Dispatchers.IO) {
        if (!path.startsWith("content://")) {
            return@withContext PreparedOutputPath(path)
        }

        val context = AndroidContext.get()
        val uri = Uri.parse(path)
        val displayName = sanitizeFileName(queryDisplayName(uri) ?: "export.mp4")

        val dir = File(context.cacheDir, "pending-exports").apply { mkdirs() }
        val tempFile = File(dir, "${UUID.randomUUID()}_$displayName")

        PreparedOutputPath(
            localPath = tempFile.absolutePath,
            commit = {
                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            tempFile.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw FileNotFoundException("Cannot open output URI: $path")
                    } finally {
                        runCatching { tempFile.delete() }
                    }
                }
            },
            discard = {
                withContext(Dispatchers.IO) {
                    runCatching { tempFile.delete() }
                }
            }
        )
    }

internal fun queryDisplayName(uri: Uri): String? {
    val resolver = AndroidContext.get().contentResolver
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

internal fun querySize(uri: Uri): Long? {
    return try {
        val resolver = AndroidContext.get().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getLong(index)
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_")
