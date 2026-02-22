package org.mlm.miniter.platform

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun randomUuid(): String {
    return kotlin.uuid.Uuid.random().toString()
}
