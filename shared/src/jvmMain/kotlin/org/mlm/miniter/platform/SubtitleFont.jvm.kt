package org.mlm.miniter.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.io.File

private val fontCache = java.util.Collections.synchronizedMap(mutableMapOf<String, FontFamily?>())
private val fontCacheMissing: MutableSet<String> =
    java.util.Collections.synchronizedSet(mutableSetOf())

@Composable
actual fun rememberSubtitleFontFamily(fontPath: String?): FontFamily? {
    if (fontPath.isNullOrBlank()) return null
    fontCache[fontPath]?.let { return it }
    if (fontCacheMissing.contains(fontPath)) return null
    val state = produceState<FontFamily?>(initialValue = fontCache[fontPath], key1 = fontPath) {
        value = try {
            val file = File(fontPath)
            if (!file.isFile || !file.canRead()) {
                fontCacheMissing.add(fontPath)
                null
            } else {
                val family = FontFamily(Font(file))
                fontCache[fontPath] = family
                family
            }
        } catch (_: Exception) {
            fontCacheMissing.add(fontPath)
            null
        }
    }
    return state.value
}
