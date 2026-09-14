package org.mlm.miniter.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.github.vinceglb.filekit.readBytes

private val fontCache = mutableMapOf<String, FontFamily?>()
private val fontCacheMissing = mutableSetOf<String>()

@Composable
actual fun rememberSubtitleFontFamily(fontPath: String?): FontFamily? {
    if (fontPath.isNullOrBlank()) return null
    fontCache[fontPath]?.let { return it }
    if (fontCacheMissing.contains(fontPath)) return null
    val state = produceState<FontFamily?>(initialValue = fontCache[fontPath], key1 = fontPath) {
        value = try {
            val bytes: ByteArray = WasmPlatformFileRegistry.get(fontPath)?.readBytes() ?: ByteArray(0)
            if (bytes.size == 0) {
                fontCacheMissing.add(fontPath)
                null
            } else {
                val family = FontFamily(Font(identity = fontPath, data = bytes))
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
