package org.mlm.miniter.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

actual object PlatformFileSystem {

    actual suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        if (path.startsWith("content://")) {
            val context = AndroidContext.get()
            val uri = path.toUri()
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.FileNotFoundException("Cannot open: $path")
        } else {
            File(path).readText(Charsets.UTF_8)
        }
    }

    actual suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        if (path.startsWith("content://")) {
            val context = AndroidContext.get()
            val uri = path.toUri()
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(content)
            } ?: throw java.io.FileNotFoundException("Cannot write: $path")
        } else {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
    }

    actual fun exists(path: String): Boolean {
        return if (path.startsWith("content://")) {
            try {
                val context = AndroidContext.get()
                context.contentResolver.openInputStream(path.toUri())?.close()
                true
            } catch (_: Exception) { false }
        } else {
            File(path).exists()
        }
    }

    actual fun delete(path: String): Boolean {
        return if (path.startsWith("content://")) {
            try {
                val context = AndroidContext.get()
                context.contentResolver.delete(path.toUri(), null, null) > 0
            } catch (_: Exception) { false }
        } else {
            File(path).delete()
        }
    }

    actual fun getParentDirectory(path: String): String {
        return if (path.startsWith("content://")) {
            val uri = path.toUri()
            uri.pathSegments.dropLast(1).joinToString("/").let {
                if (it.startsWith("tree/")) "content://$it" else "content://root/${it}"
            }
        } else {
            File(path).parent ?: ""
        }
    }

    actual fun combinePath(parent: String, child: String): String {
        return if (parent.startsWith("content://")) {
            "$parent/$child"
        } else {
            File(parent, child).absolutePath
        }
    }

    actual fun getAppDataDirectory(appName: String): String {
        val context = AndroidContext.get()
        val dir = File(context.filesDir, appName)
        dir.mkdirs()
        return dir.absolutePath
    }
}
