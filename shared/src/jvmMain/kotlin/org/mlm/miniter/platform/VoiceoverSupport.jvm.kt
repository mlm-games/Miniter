package org.mlm.miniter.platform

actual val isVoiceoverSupported: Boolean = true

actual fun hasMicPermission(): Boolean = true

actual fun requestMicPermission(onResult: (Boolean) -> Unit) {
    onResult(true)
}
