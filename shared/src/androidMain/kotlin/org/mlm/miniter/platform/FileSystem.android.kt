package org.mlm.miniter.platform

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

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
)

internal suspend fun materializeReadablePath(path: String): String = withContext(Dispatchers.IO) {
    if (!path.startsWith("content://")) return@withContext path

    val context = AndroidContext.get()
    val uri = Uri.parse(path)
    val displayName = queryDisplayName(uri)
        ?: "picked_${path.hashCode().toUInt().toString(16)}"
    val safeName = sanitizeFileName(displayName)

    val dir = File(context.filesDir, "native-inputs").apply { mkdirs() }
    val outFile = File(dir, "${path.hashCode().toUInt().toString(16)}_$safeName")

    if (!outFile.exists() || outFile.length() == 0L) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException("Cannot open input URI: $path")
    }

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
        val tempFile = File(dir, "${System.currentTimeMillis()}_$displayName")

        PreparedOutputPath(
            localPath = tempFile.absolutePath,
            commit = {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw FileNotFoundException("Cannot open output URI: $path")

                tempFile.delete()
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

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_")
