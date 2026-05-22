package org.mlm.miniter.platform

import io.github.vinceglb.filekit.PlatformFile

actual val isProjectExportSupported: Boolean = true
actual val requiresExplicitExportPathSelection: Boolean = false

actual suspend fun openSaveFileDialog(
    suggestedName: String,
    extension: String
): PlatformFile? {
    // Saving is handled via direct download using RustCoreSession.
    return null
}
