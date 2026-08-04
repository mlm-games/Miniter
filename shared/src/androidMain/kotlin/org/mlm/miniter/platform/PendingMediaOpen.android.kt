package org.mlm.miniter.platform

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android-side holder for media files handed to the app via intents
 * (ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE).
 */
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

/** Resolves a display name for a media open handed to the app via an intent. */
fun mediaOpenDisplayName(uriString: String): String {
    val uri = Uri.parse(uriString)
    return queryDisplayName(uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "video"
}