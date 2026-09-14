package org.mlm.miniter.platform

actual suspend fun isUsableFont(path: String): Boolean {
    return try {
        val bytes = PlatformFileSystem.readBytes(path)
        if (bytes.size < 12) return false
        val tag = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        tag == 0x00010000 || tag == 0x4F54544F || tag == 0x74746366 ||
            tag == 0x774F4646 || tag == 0x774F4632
    } catch (_: Exception) {
        false
    }
}
