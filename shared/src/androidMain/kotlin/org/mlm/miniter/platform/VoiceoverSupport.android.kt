package org.mlm.miniter.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

actual val isVoiceoverSupported: Boolean = true

actual fun hasMicPermission(): Boolean {
    return try {
        ContextCompat.checkSelfPermission(
            AndroidContext.get(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
}

actual fun requestMicPermission(onResult: (Boolean) -> Unit) {
    val requester = MicPermissionRegistry.requester
    if (requester == null) {
        // No Activity-registered launcher (e.g. in tests): report current state.
        onResult(hasMicPermission())
    } else {
        requester(onResult)
    }
}

/**
 * Host Activity registers its [androidx.activity.result.ActivityResultLauncher]
 * here so shared UI can trigger the runtime RECORD_AUDIO prompt on demand.
 */
object MicPermissionRegistry {
    @Volatile
    var requester: ((onResult: (Boolean) -> Unit) -> Unit)? = null
        private set

    fun register(requester: (onResult: (Boolean) -> Unit) -> Unit) {
        this.requester = requester
    }
}
