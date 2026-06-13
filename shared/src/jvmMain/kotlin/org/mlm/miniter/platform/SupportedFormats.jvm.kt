package org.mlm.miniter.platform

actual object SupportedFormats {
    actual val videoExtensions: List<String> by lazy {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("linux") -> listOf("mp4", "mov", "webm", "mkv", "avi", "wmv", "3gp", "ivf")
            os.contains("mac") || os.contains("darwin") -> listOf("mp4", "mov", "webm", "mkv", "3gp", "ivf")
            os.contains("win") -> listOf("mp4", "mov", "webm", "mkv", "wmv", "3gp", "ivf")
            else -> listOf("mp4", "mov", "webm", "mkv", "3gp", "ivf")
        }
    }

    actual val audioExtensions: List<String> by lazy {
        listOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
    }

    actual val imageExtensions: List<String> by lazy {
        listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "tiff", "tif")
    }

    actual val subtitleExtensions: List<String> by lazy {
        listOf("ass", "ssa", "srt")
    }

    actual val fontExtensions: List<String> by lazy {
        listOf("ttf", "otf", "woff", "woff2")
    }

    actual val formatHelpMessage: String by lazy {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("linux") -> "Supported: MP4, MOV, WebM, MKV, AVI, IVF, 3GP, MP3, WAV, OGG, PNG, JPG, WebP"
            os.contains("mac") || os.contains("darwin") -> "Supported: MP4, MOV, WebM, MKV, IVF, 3GP, MP3, WAV, PNG, JPG"
            os.contains("win") -> "Supported: MP4, MOV, WebM, MKV, IVF, WMV, 3GP, MP3, WAV, PNG, JPG"
            else -> "Supported: MP4, MOV, WebM, MKV, IVF, 3GP, MP3, WAV, PNG, JPG"
        }
    }
}