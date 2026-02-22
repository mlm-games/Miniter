package org.mlm.miniter.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

//expect fun getDeviceDisplayName(): String

@Composable
expect fun getDynamicColorScheme(darkTheme: Boolean, useDynamicColors: Boolean): ColorScheme?
