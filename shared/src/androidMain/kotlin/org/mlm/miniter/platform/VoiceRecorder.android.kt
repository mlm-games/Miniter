package org.mlm.miniter.platform

import android.media.MediaRecorder
import java.io.File

actual object VoiceRecorder {
    actual val fileExtension: String = "m4a"
    actual val isRecording: Boolean get() = recorder != null

    private var recorder: MediaRecorder? = null

    actual fun start(outputPath: String): Boolean {
        if (recorder != null) return false
        return try {
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            @Suppress("DEPRECATION")
            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            true
        } catch (_: Exception) {
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
            false
        }
    }

    actual fun stop(): Boolean {
        val rec = recorder ?: return false
        return try {
            rec.stop()
            true
        } catch (_: Exception) {
            false
        } finally {
            try { rec.release() } catch (_: Exception) { }
            recorder = null
        }
    }
}
