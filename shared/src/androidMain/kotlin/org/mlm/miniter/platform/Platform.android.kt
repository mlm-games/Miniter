package org.mlm.miniter.platform

import android.media.MediaCodecList
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val COMMON_CODECS = listOf("video/avc", "video/hevc", "video/vp8", "video/vp9", "video/av01")

@Composable
actual fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme? {
    return if (useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        null
    }
}

actual fun isHardwareAccelerationAvailable(): Boolean = getSupportedHwCodecs().isNotEmpty()

actual fun getHardwareAccelerationName(): String = "MediaCodec"

actual fun isHardwareDecoderGuaranteed(): Boolean {
    return getSupportedHwCodecs().any { it.contains("video/avc", ignoreCase = true) }
}

actual fun getHardwareDecoderStatus(): String {
    val hwCodecs = getSupportedHwCodecs()
    return if (hwCodecs.isNotEmpty()) {
        "MediaCodec (${hwCodecs.size} HW codecs)"
    } else {
        "MediaCodec (software)"
    }
}

actual fun getSupportedHwCodecs(): List<String> {
    return try {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hwCodecs = mutableListOf<String>()
        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.isEncoder) continue
            for (type in codecInfo.supportedTypes) {
                val mime = type.lowercase()
                if (COMMON_CODECS.any { it in mime } && codecInfo.isHardwareAccelerated) {
                    hwCodecs.add(type)
                }
            }
        }
        hwCodecs.distinct().sorted()
    } catch (_: Throwable) {
        emptyList()
    }
}