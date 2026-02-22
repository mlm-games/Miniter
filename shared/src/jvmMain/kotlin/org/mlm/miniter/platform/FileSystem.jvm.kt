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
        val os = System.getProperty("os.name").lowercase()
        val dir = when {
            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: System.getenv("APPDATA")
                    ?: "${System.getProperty("user.home")}\\AppData\\Local"
                File(localAppData, appName)
            }
            os.contains("mac") || os.contains("darwin") -> {
                File(System.getProperty("user.home"), "Library/Application Support/$appName")
            }
            else -> {
                val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: "${System.getProperty("user.home")}/.local/share"
                File(dataHome, appName)
            }
        }
        dir.mkdirs()
        return dir.absolutePath
    }
}
