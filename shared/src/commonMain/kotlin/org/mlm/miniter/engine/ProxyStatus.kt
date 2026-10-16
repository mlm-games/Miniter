package org.mlm.miniter.engine

data class ProxyStatus(
    val ready: Boolean,
    val path: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val frames: Int,
)

expect object ScrubProxy {
    fun setCacheRoot(path: String)

    fun cancel()

    suspend fun ensure(path: String, hardwareAcceleration: Boolean = true): ProxyStatus
}