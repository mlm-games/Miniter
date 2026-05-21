package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {return null}

actual fun isHardwareAccelerationAvailable(): Boolean = isVaapiAvailable()

actual fun getHardwareAccelerationName(): String = "VAAPI"

actual fun isHardwareDecoderGuaranteed(): Boolean = isVaapiAvailable()

actual fun getHardwareDecoderStatus(): String {
    val codecs = getSupportedHwCodecs()
    return if (isVaapiAvailable()) {
        if (codecs.isNotEmpty()) {
            "VAAPI (${codecs.size} HW codecs)"
        } else {
            "VAAPI"
        }
    } else {
        "Software"
    }
}

actual fun getSupportedHwCodecs(): List<String> {
    if (!isVaapiAvailable()) return emptyList()
    return probeVaapiCodecsViaVainfo()
}

private fun isVaapiAvailable(): Boolean {
    return try {
        File("/dev/dri").listFiles()?.any { it.name.startsWith("card") } == true
    } catch (_: Throwable) {
        false
    }
}

private fun probeVaapiCodecsViaVainfo(): List<String> {
    val codecMap = mapOf(
        "H.264" to "video/avc",
        "HEVC" to "video/hevc",
        "VP8" to "video/vp8",
        "VP9" to "video/vp9",
        "AV1" to "video/av01"
    )
    return try {
        val process = Runtime.getRuntime().exec("vainfo")
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        reader.close()
        process.waitFor()

        val failed = output.contains("failed", ignoreCase = true) ||
            output.contains("error", ignoreCase = true) ||
            output.contains("not supported", ignoreCase = true)

        if (failed) return emptyList()

        codecMap.entries.filter { (name, _) ->
            output.contains(name, ignoreCase = true)
        }.map { it.value }
    } catch (_: Throwable) {
        emptyList()
    }
}