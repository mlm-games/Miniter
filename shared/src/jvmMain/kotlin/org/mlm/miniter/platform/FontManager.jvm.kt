package org.mlm.miniter.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.io.File

actual suspend fun isUsableFont(path: String): Boolean {
    return try {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return false
        if (!hasSfntMagic(file)) return false
        FontFamily(Font(file))
        true
    } catch (_: Exception) {
        false
    }
}

private fun hasSfntMagic(file: File): Boolean {
    if (file.length() < 12) return false
    val header = ByteArray(12)
    file.inputStream().use { stream ->
        var read = 0
        while (read < header.size) {
            val n = stream.read(header, read, header.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < header.size) return false
    }
    val tag = ((header[0].toInt() and 0xFF) shl 24) or
        ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or
        (header[3].toInt() and 0xFF)
    return tag == 0x00010000 || tag == 0x4F54544F || tag == 0x74746366
}
