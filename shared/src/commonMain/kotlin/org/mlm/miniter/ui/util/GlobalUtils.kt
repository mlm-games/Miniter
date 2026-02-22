package org.mlm.miniter.ui.util

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

fun <T : NavKey> NavBackStack<T>.popBack() {
    if (size > 1) {
        removeAt(lastIndex)
    }
}

fun <T : NavKey> NavBackStack<T>.popUntil(predicate: (T) -> Boolean) {
    while (size > 1 && !predicate(this[lastIndex])) {
        removeAt(lastIndex)
    }
}

fun <T : NavKey> NavBackStack<T>.replaceTop(key: T) {
    if (isEmpty()) add(key) else set(lastIndex, key)
}

fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (ms % 1000) / 10
    return "%02d:%02d.%02d".format(minutes, seconds, millis)
}
