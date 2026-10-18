package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.mlm.miniter.ffi.hardwareProbeCodecs
import org.mlm.miniter.ffi.hardwareStats
import org.mlm.miniter.ffi.hardwareProbeStatus

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {return null}

/// The platform backend's own probe (VA-API display, Media Foundation MFTs, VideoToolbox),
/// tried once; `null` when the build has no probe (or it failed), so the OS checks below apply.
private val nativeProbe: Pair<String, List<String>>? by lazy {
    runCatching {
        val status = hardwareProbeStatus()
        if (status.isBlank()) null else status to hardwareProbeCodecs()
    }.getOrNull()
}

actual fun isHardwareAccelerationAvailable(): Boolean =
    nativeProbe?.second?.isNotEmpty() ?: isVaapiAvailable()

actual fun getHardwareAccelerationName(): String = when {
    isWindows() -> "Media Foundation"
    isMac() -> "VideoToolbox"
    else -> "VAAPI"
}

actual fun isHardwareDecoderGuaranteed(): Boolean =
    nativeProbe?.let { it.second.isNotEmpty() } ?: isVaapiAvailable()

actual fun getHardwareDecoderStatus(): String {
    nativeProbe?.let { (status, codecs) ->
        return if (codecs.isEmpty()) "$status (no codecs)" else "$status (${codecs.joinToString(", ")})"
    }
    if (isWindows() || isMac()) return "Unknown (native decoder not probed on this OS)"
    if (!isVaapiAvailable()) {
        return if (isNvidiaPresent()) "Unknown (NVIDIA GPU present, VAAPI not detected)"
        else "Software"
    }
    val codecs = getSupportedHwCodecs()
    return when {
        codecs.isNotEmpty() -> "VAAPI (${codecs.size} HW codecs)"
        isVainfoInstalled() -> "VAAPI (no codecs detected)"
        else -> "VAAPI (install vainfo to list codecs)"
    }
}

actual fun getSupportedHwCodecs(): List<String> {
    nativeProbe?.second?.takeIf { it.isNotEmpty() }?.let { return it }
    cachedHwCodecs?.let { return it }
    if (!isVaapiAvailable() || !isVainfoInstalled()) {
        cachedHwCodecs = emptyList()
        return emptyList()
    }
    return probeVaapiCodecsViaVainfo().also { cachedHwCodecs = it }
}

@Volatile
private var cachedVainfoInstalled: Boolean? = null

@Volatile
private var cachedHwCodecs: List<String>? = null

@Volatile
private var cachedNvidiaPresent: Boolean? = null

private fun osName(): String =
    runCatching { System.getProperty("os.name").orEmpty().lowercase() }.getOrDefault("")

private fun isWindows(): Boolean = osName().contains("win")

private fun isMac(): Boolean = osName().contains("mac") || osName().contains("darwin")

private fun isNvidiaPresent(): Boolean {
    cachedNvidiaPresent?.let { return it }
    val present = runCommand("nvidia-smi", "-L")?.any {
        it.contains("GPU", ignoreCase = true)
    } == true
    cachedNvidiaPresent = present
    return present
}

private fun isVaapiAvailable(): Boolean {
    if (isWindows() || isMac()) return false
    return try {
        File("/dev/dri").listFiles()?.any { it.name.startsWith("card") } == true
    } catch (_: Throwable) {
        false
    }
}

private fun isVainfoInstalled(): Boolean {
    cachedVainfoInstalled?.let { return it }
    val installed = runCommand("which", "vainfo") != null
    cachedVainfoInstalled = installed
    return installed
}

private fun runCommand(vararg command: String, timeoutSeconds: Long = 5): List<String>? {
    return try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            return null
        }
        val lines = process.inputStream.bufferedReader().readLines()
        if (process.exitValue() != 0) return null
        lines
    } catch (_: IOException) {
        null
    } catch (_: Throwable) {
        null
    }
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
        val lines = runCommand("vainfo") ?: return emptyList()
        lines.filter { line ->
            line.contains("VAEntrypointVLD", ignoreCase = true)
        }.mapNotNull { line ->
            profileToMime.entries.firstOrNull { (profile, _) ->
                line.contains(profile, ignoreCase = true)
            }?.value
        }.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}

actual fun getHardwareStats(): String =
    runCatching { hardwareStats() }.getOrDefault("")
