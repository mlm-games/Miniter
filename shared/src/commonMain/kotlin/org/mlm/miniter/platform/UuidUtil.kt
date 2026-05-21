package org.mlm.miniter.platform

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun randomUuid(): String {
    return kotlin.uuid.Uuid.random().toString()
}

val Long.usToMs: Long get() = this / 1000L
val Long.msToUs: Long get() = this * 1000L
