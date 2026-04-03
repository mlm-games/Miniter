package org.mlm.miniter.engine

import org.mlm.miniter.editor.model.RustVideoFilterSnapshot

expect class PlatformFrameGrabber() {

    suspend fun open(path: String)

    suspend fun grabFrame(
        timestampMs: Long,
        filters: List<RustVideoFilterSnapshot> = emptyList(),
        opacity: Float = 1f,
        width: Int = 0,
        height: Int = 0,
    ): ImageData?

    fun release()
}
