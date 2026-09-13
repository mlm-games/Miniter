package org.mlm.miniter.platform

actual object VoiceRecorder {
    actual val fileExtension: String = "wav"
    actual val isRecording: Boolean = false

    actual fun start(outputPath: String): Boolean = false

    actual fun stop(): Boolean = false
}
