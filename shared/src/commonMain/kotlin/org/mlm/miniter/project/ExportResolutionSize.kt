package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustExportResolution

fun RustExportResolution.pixelSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> = when (this) {
    RustExportResolution.Source -> sourceWidth to sourceHeight
    RustExportResolution.Sd480 -> 854 to 480
    RustExportResolution.Hd720 -> 1280 to 720
    RustExportResolution.Hd1080 -> 1920 to 1080
    RustExportResolution.Uhd4k -> 3840 to 2160
    is RustExportResolution.Custom -> width to height
}
