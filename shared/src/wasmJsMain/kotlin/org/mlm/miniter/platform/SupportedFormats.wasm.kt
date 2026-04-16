package org.mlm.miniter.platform

actual object SupportedFormats {
    actual val videoExtensions: List<String> = listOf("mp4", "webm")
    actual val audioExtensions: List<String> = listOf("mp3", "wav", "ogg", "m4a", "aac")
    actual val subtitleExtensions: List<String> = listOf("ass", "ssa", "srt")
    actual val formatHelpMessage: String = "Supported on Web: MP4, WebM, MP3, WAV, OGG"
}
