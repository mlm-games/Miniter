package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import org.mlm.miniter.rust.isWebCodecsHardwareAccelerated
import org.mlm.miniter.rust.supportedHwCodecs

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean,
): ColorScheme? = null

actual fun isHardwareAccelerationAvailable(): Boolean =
    isWebCodecsHardwareAccelerated

actual fun getHardwareAccelerationName(): String = "WebCodecs"

actual fun isHardwareDecoderGuaranteed(): Boolean = isWebCodecsHardwareAccelerated

actual fun getHardwareDecoderStatus(): String {
    val codecs = supportedHwCodecs
    return if (isWebCodecsHardwareAccelerated) {
        if (codecs.isNotEmpty()) {
            "WebCodecs (${codecs.size} HW codecs)"
        } else {
            "WebCodecs (HW)"
        }
    } else {
        "WebCodecs (software)"
    }
}

actual fun getSupportedHwCodecs(): List<String> {
    return supportedHwCodecs
}
