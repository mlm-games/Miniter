package org.mlm.miniter.platform

/**
 * False on platforms with no microphone capture path (e.g. wasmJs).
 * UI should hide/disable the voiceover action when this is false.
 */
expect val isVoiceoverSupported: Boolean

/** True when the app is currently allowed to capture microphone audio. */
expect fun hasMicPermission(): Boolean

/**
 * Requests microphone capture permission if needed, then invokes [onResult]
 * with the outcome. On platforms without a runtime permission model the
 * callback fires immediately with [hasMicPermission].
 */
expect fun requestMicPermission(onResult: (Boolean) -> Unit)
