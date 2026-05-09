package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun getDynamicColorScheme(
    darkTheme: Boolean,
    useDynamicColors: Boolean
): ColorScheme? {return null}

actual fun isHardwareAccelerationAvailable(): Boolean = false

actual fun getHardwareAccelerationName(): String = "Software (less-avc)"