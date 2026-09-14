package org.mlm.miniter.platform

object FontManager {
    const val CUSTOM_FONTS_DIR = "custom_fonts"

    private val VALID_EXTENSIONS = setOf("ttf", "otf")
    private val SANITIZE_REGEX = Regex("[^a-zA-Z0-9_-]")

    fun sanitizeFontName(displayName: String): String {
        val base = displayName.substringBeforeLast(".", displayName)
        val ext = displayName.substringAfterLast(".", "").lowercase()
        val safeBase = base.replace(SANITIZE_REGEX, "_").trim('_')
            .ifEmpty { "font" }.take(64)
        val safeExt = if (ext in VALID_EXTENSIONS) ext else "ttf"
        return "$safeBase.$safeExt"
    }

    fun isFontExtension(path: String): Boolean {
        val ext = path.substringAfterLast(".", "").lowercase()
        return ext in VALID_EXTENSIONS
    }

    suspend fun customFontsDir(): String {
        val base = PlatformFileSystem.getAppDataDirectory("miniter")
        val dir = PlatformFileSystem.combinePath(base, CUSTOM_FONTS_DIR)
        PlatformFileSystem.createDirectories(dir)
        return dir
    }

    suspend fun importFont(sourcePath: String): String? {
        if (!isFontExtension(sourcePath)) return null
        val stagedName = sanitizeFontName(
            sourcePath.substringAfterLast("/").substringAfterLast("\\"),
        )
        val stagedPath = PlatformFileSystem.combinePath(customFontsDir(), stagedName)
        return try {
            val bytes = PlatformFileSystem.readBytes(sourcePath)
            if (bytes.isEmpty()) return null
            PlatformFileSystem.writeBytes(stagedPath, bytes)
            if (!isUsableFont(stagedPath)) {
                runCatching { PlatformFileSystem.delete(stagedPath) }
                return null
            }
            stagedPath
        } catch (_: Exception) {
            runCatching { PlatformFileSystem.delete(stagedPath) }
            null
        }
    }

    suspend fun deleteImportedFont(path: String): Boolean {
        val dir = runCatching { customFontsDir() }.getOrNull() ?: return false
        val parent = PlatformFileSystem.getParentDirectory(path)
        if (!parent.endsWith(CUSTOM_FONTS_DIR) && parent != dir) return false
        return runCatching { PlatformFileSystem.delete(path) }.getOrDefault(false)
    }

    fun displayName(path: String?): String? =
        path?.substringAfterLast("/")?.substringAfterLast("\\")?.takeIf { it.isNotBlank() }
}

expect suspend fun isUsableFont(path: String): Boolean
