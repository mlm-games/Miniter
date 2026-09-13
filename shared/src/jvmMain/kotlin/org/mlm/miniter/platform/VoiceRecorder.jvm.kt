package org.mlm.miniter.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

actual object VoiceRecorder {
    actual val fileExtension: String = "wav"
    actual val isRecording: Boolean get() = recordingThread != null

    private const val SAMPLE_RATE = 44_100f
    @Volatile
    private var line: TargetDataLine? = null
    @Volatile
    private var recordingThread: Thread? = null
    @Volatile
    private var outputFile: File? = null

    actual fun start(outputPath: String): Boolean {
        if (recordingThread != null) return false
        return try {
            val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
            val info = javax.sound.sampled.DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return false
            val dataLine = AudioSystem.getLine(info) as TargetDataLine
            dataLine.open(format)
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            // Placeholder header; patched with real sizes on stop().
            FileOutputStream(file).use { writeWavHeader(it, 0) }
            dataLine.start()
            line = dataLine
            outputFile = file
            val worker = Thread({
                try {
                    FileOutputStream(file, true).use { out ->
                        val buffer = ByteArray(4096)
                        while (recordingThread === Thread.currentThread()) {
                            val read = dataLine.read(buffer, 0, buffer.size)
                            if (read > 0) out.write(buffer, 0, read)
                        }
                    }
                } catch (_: Exception) {
                }
            }, "voiceover-recorder")
            recordingThread = worker
            worker.start()
            true
        } catch (_: Exception) {
            cleanup()
            false
        }
    }

    actual fun stop(): Boolean {
        val worker = recordingThread ?: return false
        return try {
            recordingThread = null
            line?.stop()
            line?.close()
            // Unbounded join: the worker holds the file stream open, so patching
            // the header before it exits would corrupt the WAV.
            worker.join()
            patchWavHeader()
            true
        } catch (_: Exception) {
            false
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        recordingThread = null
        try { line?.close() } catch (_: Exception) { }
        line = null
    }

    private fun patchWavHeader() {
        val file = outputFile ?: return
        try {
            val dataSize = (file.length() - 44).coerceAtLeast(0).toInt()
            // Overwrite just the 44-byte header in place; never truncate the file.
            java.io.RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write(wavHeader(dataSize))
            }
        } catch (_: Exception) {
        }
    }

    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        out.write(wavHeader(dataSize))
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val totalSize = 36 + dataSize
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(SAMPLE_RATE.toInt())
        buf.putInt((SAMPLE_RATE * 2).toInt()) // byte rate
        buf.putShort(2) // block align
        buf.putShort(16) // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        return buf.array()
    }
}
