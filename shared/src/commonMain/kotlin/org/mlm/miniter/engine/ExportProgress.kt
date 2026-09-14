package org.mlm.miniter.engine

data class ExportProgress(
    val phase: String = "Idle",
    val progress: Float = 0f,
    val isComplete: Boolean = false,
    val isCancelled: Boolean = false,
    val error: String? = null,
    val hardwareFallback: Boolean = false,
    val previewFrame: ImageData? = null,
    val outputPath: String? = null,
) {
    companion object {
        const val PHASE_IDLE = "Idle"
        const val PHASE_PREPARING = "Preparing…"
    }
}

/** True while an export job owns the engine (preparing or rendering). */
fun ExportProgress.isActive(): Boolean =
    !isComplete &&
        !isCancelled &&
        error == null &&
        (progress > 0f || (phase != ExportProgress.PHASE_IDLE && phase.isNotBlank()))

fun ExportProgress.isTerminal(): Boolean =
    isComplete || isCancelled || error != null
