package org.mlm.miniter.platform

import android.net.Uri
import java.io.File

actual fun normalizeMediaUriForPlayback(path: String): String {
    if (path.startsWith("content://") || path.startsWith("file:")) return path
    if (path.startsWith("/")) return Uri.fromFile(File(path)).toString()
    return path
}
