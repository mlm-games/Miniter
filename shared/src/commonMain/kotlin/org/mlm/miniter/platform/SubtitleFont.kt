package org.mlm.miniter.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily

/**
 * Loads a [FontFamily] from a staged font file path.
 *
 * Returns null while loading or when [fontPath] is null/blank/unreadable,
 * so callers fall back to the default family. Results are cached per path
 * by the platform implementation.
 */
@Composable
expect fun rememberSubtitleFontFamily(fontPath: String?): FontFamily?
