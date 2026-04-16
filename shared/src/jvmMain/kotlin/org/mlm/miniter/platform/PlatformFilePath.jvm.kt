package org.mlm.miniter.platform

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

actual fun PlatformFile.platformPath(): String = path
