package org.mlm.miniter.rust

expect class RustCoreSession {
    constructor(projectName: String)

    fun toJson(): String
    fun dispatch(commandJson: String): Boolean

    fun undo(): Boolean
    fun redo(): Boolean
    fun canUndo(): Boolean
    fun canRedo(): Boolean

    fun playheadUs(): Long
    fun setPlayheadUs(us: Long)

    fun renderPlanAtPlayhead(width: Int, height: Int): String
    fun durationUs(): Long

    companion object {
        fun fromJson(json: String): RustCoreSession
        fun probeAudio(path: String): String
        fun extractWaveform(path: String, buckets: Int): String
    }
}
