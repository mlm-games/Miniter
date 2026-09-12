package org.mlm.miniter.platform

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun randomUuid(): String {
    return kotlin.uuid.Uuid.random().toString()
}

val Long.usToMs: Long get() = this / 1000L

val Long.msToUs: Long
    get() {
        if (this > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE
        if (this < Long.MIN_VALUE / 1000L) return Long.MIN_VALUE
        return this * 1000L
    }
