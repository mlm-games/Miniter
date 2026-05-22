package org.mlm.miniter.platform

import io.github.vinceglb.filekit.PlatformFile

expect val isProjectExportSupported: Boolean
expect val requiresExplicitExportPathSelection: Boolean

expect suspend fun openSaveFileDialog(
    suggestedName: String,
    extension: String
): PlatformFile?

