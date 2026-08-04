package org.mlm.miniter.platform

import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object PendingMediaOpens {
    private val _items = MutableStateFlow<List<PendingMediaOpen>>(emptyList())

    val requests: StateFlow<List<PendingMediaOpen>> = _items.asStateFlow()

    fun submit(items: List<PendingMediaOpen>) {
        if (items.isNotEmpty()) {
            _items.value = items
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
    return args.mapNotNull { arg ->
        val raw = arg.removePrefix("file://")
        val path = URLDecoder.decode(raw, Charsets.UTF_8.name())
        val file = File(path)
        file.takeIf { it.isFile }
    }.filter { it.extension.lowercase() in supported }
        .map { PendingMediaOpen(it.absolutePath, it.name) }
        .distinctBy { it.path }
}
