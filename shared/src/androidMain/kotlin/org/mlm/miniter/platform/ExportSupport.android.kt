package org.mlm.miniter.platform

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver

actual val isProjectExportSupported: Boolean = true
actual val requiresExplicitExportPathSelection: Boolean = true

actual suspend fun openSaveFileDialog(
    suggestedName: String,
    extension: String
): PlatformFile? {
    return FileKit.openFileSaver(
        suggestedName = suggestedName,
        defaultExtension = extension,
        allowedExtensions = setOf(extension)
    )
}

