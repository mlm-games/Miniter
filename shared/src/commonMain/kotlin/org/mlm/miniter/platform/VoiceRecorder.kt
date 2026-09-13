package org.mlm.miniter.platform

expect object VoiceRecorder {
    val fileExtension: String
    val isRecording: Boolean
    fun start(outputPath: String): Boolean
    fun stop(): Boolean
}
