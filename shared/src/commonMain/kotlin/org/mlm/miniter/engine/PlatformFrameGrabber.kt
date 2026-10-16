package org.mlm.miniter.engine

import kotlinx.serialization.json.Json
import org.mlm.miniter.editor.model.RustMaskEffect
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot

expect class PlatformFrameGrabber() {

    suspend fun open(path: String)

    suspend fun grabFrame(
        timestampMs: Long,
        filters: List<RustVideoFilterSnapshot> = emptyList(),
        masks: List<RustMaskEffect> = emptyList(),
        opacity: Float = 1f,
        width: Int = 0,
        height: Int = 0,
        hardwareAcceleration: Boolean = true,
    ): ImageData?

    fun release()
}

private val maskWireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

internal fun masksToWireJson(masks: List<RustMaskEffect>): String =
    maskWireJson.encodeToString(masks)
