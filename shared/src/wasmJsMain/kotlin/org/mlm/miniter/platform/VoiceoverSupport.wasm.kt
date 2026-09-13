package org.mlm.miniter.platform

actual val isVoiceoverSupported: Boolean = false

actual fun hasMicPermission(): Boolean = false

actual fun requestMicPermission(onResult: (Boolean) -> Unit) {
    onResult(false)
}
