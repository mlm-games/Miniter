package org.mlm.miniter.engine

data class ExportProgress(
    val phase: String = "Idle",
    val progress: Float = 0f,
    val isComplete: Boolean = false,
    val isCancelled: Boolean = false,
    val error: String? = null,
)
