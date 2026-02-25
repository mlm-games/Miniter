package org.mlm.miniter.platform

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

actual object PlatformFileSystem {

    private fun resolveToInternal(contentUri: String): String {
        val uri = Uri.parse(contentUri)
        val rawName = uri.lastPathSegment
            ?.substringAfterLast("/")
            ?.substringAfterLast(":")
            ?: "project_${contentUri.hashCode().toUInt().toString(16)}"
        val safeName = if (rawName.contains(".")) rawName else "$rawName.mntr"
        val dir = File(AndroidContext.get().filesDir, "projects")
        dir.mkdirs()
        return File(dir, safeName).absolutePath
    }

    private fun resolve(path: String): String {
        return if (path.startsWith("content://")) resolveToInternal(path) else path
    }

    actual suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val resolved = resolve(path)
        val file = File(resolved)
        if (file.exists()) {
            return@withContext file.readText(Charsets.UTF_8)
        }
        if (path.startsWith("content://")) {
            val context = AndroidContext.get()
            val text = context.contentResolver.openInputStream(Uri.parse(path))
                ?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.FileNotFoundException("Cannot read: $path")
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            return@withContext text
        }
        throw java.io.FileNotFoundException("File not found: $path")
    }

    actual suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val resolved = resolve(path)
        val file = File(resolved)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)

        if (path.startsWith("content://")) {
            try {
                val context = AndroidContext.get()
                context.contentResolver.openOutputStream(Uri.parse(path))?.use { out ->
                    out.bufferedWriter().use { it.write(content) }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    actual fun exists(path: String): Boolean {
        return File(resolve(path)).exists()
    }

    actual fun delete(path: String): Boolean {
        return File(resolve(path)).delete()
    }

    actual fun getParentDirectory(path: String): String {
        return File(resolve(path)).parent ?: ""
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
}
