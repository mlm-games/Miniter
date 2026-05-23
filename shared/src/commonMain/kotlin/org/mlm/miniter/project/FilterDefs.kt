package org.mlm.miniter.project

data class FilterPropertyDef(
    val paramKey: String,
    val displayName: String,
    val range: ClosedFloatingPointRange<Float>,
    val steps: Int = 0,
    val format: (Float) -> String,
    val keyframeSuffix: String,
)

data class FilterDef(
    val serialName: String,
    val displayName: String,
    val properties: List<FilterPropertyDef>,
)
