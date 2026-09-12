package org.mlm.miniter.platform

import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object PendingMediaOpens {
    private val _items = MutableStateFlow<List<PendingMediaOpen>>(emptyList())

    val requests: StateFlow<List<PendingMediaOpen>> = _items.asStateFlow()

    fun submit(items: List<PendingMediaOpen>) {
        if (items.isNotEmpty()) {
            _items.value = (_items.value + items).distinctBy { it.path }
        }
    }

    fun consume() {
        _items.value = emptyList()
    }
}

actual val pendingMediaOpens: StateFlow<List<PendingMediaOpen>> get() = PendingMediaOpens.requests

actual fun consumePendingMediaOpens() {
    PendingMediaOpens.consume()
}


fun pendingMediaFromCommandLineArgs(args: Array<String>): List<PendingMediaOpen> {
    val supported = SupportedFormats.videoExtensions +
        SupportedFormats.audioExtensions +
        SupportedFormats.imageExtensions
    val dropped = mutableListOf<String>()
    val result = args.mapNotNull { arg ->
        val file = argToFile(arg)
        if (file == null || !file.isFile) {
            dropped += arg
            return@mapNotNull null
        }
        if (file.extension.lowercase() !in supported) {
            dropped += arg
            return@mapNotNull null
        }
        file
    }.map { PendingMediaOpen(it.absolutePath, it.name) }
        .distinctBy { it.path }
    dropped.forEach { println("Miniter: ignoring unsupported open argument: $it") }
    return result
}

internal fun argToFile(arg: String): File? {
    val trimmed = arg.trim().removeSurrounding("\"").trim()
    if (trimmed.isEmpty()) return null
    val normalized = trimmed.replace(Regex("^file://localhost(?=/|$)"), "file://")
    if (!normalized.startsWith("file:", ignoreCase = true)) {
        return File(normalized).takeIf { it.path.isNotEmpty() }
    }
    return runCatching {
        val rawPath = URI(normalized).path
        if (rawPath.isNullOrEmpty()) null else File(rawPath)
    }.getOrElse {
        val stripped = normalized.removePrefix("file://").removePrefix("file:")
        File(stripped.replace("%20", " ")).takeIf { file -> file.path.isNotEmpty() }
    }
}
