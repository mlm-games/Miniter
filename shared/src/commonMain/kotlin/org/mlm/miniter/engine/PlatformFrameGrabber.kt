package org.mlm.miniter.engine

expect class PlatformFrameGrabber() {

    suspend fun open(path: String)

    suspend fun grabFrame(
        timestampMs: Long,
        width: Int = 0,
        height: Int = 0,
        hardwareAcceleration: Boolean = true,
    ): ImageData?

    suspend fun openProject(projectJson: String)

    suspend fun grabCompositeFrame(
        timestampUs: Long,
        width: Int,
        height: Int,
        hardwareAcceleration: Boolean,
        ignoreCropClipId: String? = null,
    ): ImageData?

    fun release()
}
