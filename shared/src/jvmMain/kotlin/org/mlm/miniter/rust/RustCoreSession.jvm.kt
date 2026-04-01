package org.mlm.miniter.rust

import org.mlm.miniter.ffi.EditorHandle as NativeEditorHandle
import org.mlm.miniter.ffi.extractWaveform as nativeExtractWaveform
import org.mlm.miniter.ffi.probeAudio as nativeProbeAudio

actual class RustCoreSession private constructor(
    private val handle: NativeEditorHandle,
) {
    actual constructor(projectName: String) : this(
        NativeEditorHandle(projectName)
    )

    actual fun toJson(): String = handle.toJson()

    actual fun dispatch(commandJson: String): Boolean =
        handle.dispatch(commandJson)

    actual fun undo(): Boolean = handle.undo()

    actual fun redo(): Boolean = handle.redo()

    actual fun canUndo(): Boolean = handle.canUndo()

    actual fun canRedo(): Boolean = handle.canRedo()

    actual fun playheadUs(): Long = handle.playheadUs()

    actual fun setPlayheadUs(us: Long) {
        handle.setPlayheadUs(us)
    }

    actual fun renderPlanAtPlayhead(width: Int, height: Int): String =
        handle.renderPlanAtPlayhead(width.toUInt(), height.toUInt())

    actual fun durationUs(): Long = handle.durationUs()

    actual companion object {
        actual fun fromJson(json: String): RustCoreSession =
            RustCoreSession(NativeEditorHandle.fromJson(json))

        actual fun probeAudio(path: String): String =
            nativeProbeAudio(path)

        actual fun extractWaveform(path: String, buckets: Int): String =
            nativeExtractWaveform(path, buckets.toUInt())
    }
}
