package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean,
): ColorScheme? = null

actual fun isHardwareAccelerationAvailable(): Boolean = true

actual fun getHardwareAccelerationName(): String = "WebCodecs (HW)"
