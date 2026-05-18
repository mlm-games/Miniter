package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
expect fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme?

expect fun isHardwareAccelerationAvailable(): Boolean

expect fun getHardwareAccelerationName(): String

expect fun isHardwareDecoderGuaranteed(): Boolean

expect fun getHardwareDecoderStatus(): String

expect fun getSupportedHwCodecs(): List<String>
