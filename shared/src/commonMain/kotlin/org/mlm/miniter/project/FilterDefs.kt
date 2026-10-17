package org.mlm.miniter.project

import androidx.compose.ui.graphics.Color

data class FilterPropertyDef(
    val paramKey: String,
    val displayName: String,
    val range: ClosedFloatingPointRange<Float>,
    val steps: Int = 0,
    val format: (Float) -> String,
    val keyframeSuffix: String,
    val color: Color = Color(0xFFEF5350),
)

data class FilterDef(
    val serialName: String,
    val displayName: String,
    val properties: List<FilterPropertyDef>,
)

val speedPresets: List<Pair<Float, String>> = listOf(
    0.25f to "0.25x",
    0.5f to "0.5x",
    1f to "1x",
    2f to "2x",
    4f to "4x",
)
