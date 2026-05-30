package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import java.io.File
import java.io.IOException

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {return null}

actual fun isHardwareAccelerationAvailable(): Boolean = isVaapiAvailable()

actual fun getHardwareAccelerationName(): String = "VAAPI"

actual fun isHardwareDecoderGuaranteed(): Boolean = isVaapiAvailable()

actual fun getHardwareDecoderStatus(): String {
    if (!isVaapiAvailable()) return "Software"
    val codecs = getSupportedHwCodecs()
    return when {
        codecs.isNotEmpty() -> "VAAPI (${codecs.size} HW codecs)"
        isVainfoInstalled() -> "VAAPI (no codecs detected)"
        else -> "VAAPI (install vainfo to list codecs)"
    }
}

actual fun getSupportedHwCodecs(): List<String> {
    if (!isVaapiAvailable() || !isVainfoInstalled()) return emptyList()
    return probeVaapiCodecsViaVainfo()
}

private fun isVaapiAvailable(): Boolean {
    return try {
        File("/dev/dri").listFiles()?.any { it.name.startsWith("card") } == true
    } catch (_: Throwable) {
        false
    }
}

private fun isVainfoInstalled(): Boolean = try {
    ProcessBuilder("which", "vainfo").start().waitFor() == 0
} catch (_: IOException) {
    false
}

private fun probeVaapiCodecsViaVainfo(): List<String> {
    val profileToMime = mapOf(
        "VAProfileH264" to "video/avc",
        "VAProfileHEVC" to "video/hevc",
        "VAProfileVP8" to "video/vp8",
        "VAProfileVP9" to "video/vp9",
        "VAProfileAV1" to "video/av01"
    )
    return try {
        val process = ProcessBuilder("vainfo")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines.filter { line ->
            line.contains("VAEntrypointEncSlice", ignoreCase = true)
        }.mapNotNull { line ->
            profileToMime.entries.firstOrNull { (profile, _) ->
                line.contains(profile, ignoreCase = true)
            }?.value
        }.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}