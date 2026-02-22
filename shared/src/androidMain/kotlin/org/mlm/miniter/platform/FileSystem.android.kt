package org.mlm.miniter.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual object PlatformFileSystem {

    actual suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        File(path).readText(Charsets.UTF_8)
    }

    actual suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun delete(path: String): Boolean = File(path).delete()

    actual fun getParentDirectory(path: String): String {
        return File(path).parent ?: ""
    }

    actual fun combinePath(parent: String, child: String): String {
        return File(parent, child).absolutePath
    }

    actual fun getAppDataDirectory(appName: String): String {
        val dir = File(System.getProperty("user.dir") ?: "/data/local/tmp", appName)
        dir.mkdirs()
        return dir.absolutePath
    }
}
