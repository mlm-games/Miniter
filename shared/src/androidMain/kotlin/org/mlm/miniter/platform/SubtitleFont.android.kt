package org.mlm.miniter.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

private val fontCache = synchronizedMap(mutableMapOf<String, FontFamily?>())
private val fontCacheMissing = synchronizedSet(mutableSetOf<String>())

private fun synchronizedMap(map: MutableMap<String, FontFamily?>): MutableMap<String, FontFamily?> =
    java.util.Collections.synchronizedMap(map)

private fun synchronizedSet(set: MutableSet<String>): MutableSet<String> =
    java.util.Collections.synchronizedSet(set)

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
