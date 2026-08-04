package org.mlm.miniter.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual val pendingMediaOpens: StateFlow<List<PendingMediaOpen>> =
    MutableStateFlow(emptyList())

actual fun consumePendingMediaOpens() {
    // Not applicable.
}