package org.mlm.miniter.platform

import android.graphics.Typeface
import java.io.File

actual suspend fun isUsableFont(path: String): Boolean {
    return try {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return false
        Typeface.createFromFile(file) != null
    } catch (_: Exception) {
        false
    }
}
