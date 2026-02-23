package org.mlm.miniter.platform

actual object SupportedFormats {
    actual val videoExtensions: List<String> = listOf("mp4", "webm", "mkv", "3gp")
    actual val formatHelpMessage: String = "Supported: MP4, WebM, MKV, 3GP"
}
