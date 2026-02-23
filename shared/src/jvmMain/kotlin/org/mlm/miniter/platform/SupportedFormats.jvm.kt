package org.mlm.miniter.platform

actual object SupportedFormats {
    actual val videoExtensions: List<String> by lazy {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("linux") -> listOf("mp4", "mov", "webm", "mkv", "avi", "wmv", "3gp")
            os.contains("mac") || os.contains("darwin") -> listOf("mp4", "mov", "3gp")
            os.contains("win") -> listOf("mp4", "mov", "wmv", "3gp")
            else -> listOf("mp4", "mov")
        }
    }

    actual val formatHelpMessage: String by lazy {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("linux") -> "Supported: MP4, MOV, WebM, MKV, AVI, WMV, 3GP"
            os.contains("mac") || os.contains("darwin") -> "Supported: MP4, MOV, 3GP"
            os.contains("win") -> "Supported: MP4, MOV, WMV, 3GP"
            else -> "Supported: MP4, MOV"
        }
    }
}
