package org.mlm.miniter.ui.components.properties

import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.project.FilterDef
import org.mlm.miniter.project.FilterPropertyDef

private fun fmtPct(v: Float) = "${(v * 100).toInt()}%"
private fun fmt1d(v: Float) = "${(v * 10).toInt() / 10f}x"
private fun fmtDeg(v: Float) = "${v.toInt()}°"

val FILTERS: List<FilterDef> = listOf(
    FilterDef("Transform", "Transform", listOf(
        FilterPropertyDef("scale", "Scale", 0.5f..3f, 24, ::fmt1d, "scale"),
        FilterPropertyDef("translate_x", "Pan X", -1f..1f, format = ::fmtPct, keyframeSuffix = "translate_x"),
        FilterPropertyDef("translate_y", "Pan Y", -1f..1f, format = ::fmtPct, keyframeSuffix = "translate_y"),
        FilterPropertyDef("rotate", "Rotate", -180f..180f, format = ::fmtDeg, keyframeSuffix = "rotate"),
    )),
    FilterDef("Brightness", "Brightness", listOf(
        FilterPropertyDef("value", "Brightness", -100f..100f, 39, { "${it.toInt()}" }, "brightness"),
    )),
    FilterDef("Contrast", "Contrast", listOf(
        FilterPropertyDef("value", "Contrast", 0f..3f, 29, ::fmt1d, "contrast"),
    )),
    FilterDef("Saturation", "Saturation", listOf(
        FilterPropertyDef("value", "Saturation", 0f..3f, 29, ::fmt1d, "saturation"),
    )),
    FilterDef("Blur", "Blur", listOf(
        FilterPropertyDef("radius", "Blur radius", 1f..20f, 18, { "${it.toInt()}px" }, "blur_radius"),
    )),
    FilterDef("Sharpen", "Sharpen", listOf(
        FilterPropertyDef("amount", "Sharpen amount", 0f..3f, 29, ::fmt1d, "sharpen_amount"),
    )),
)

private val FILTERS_BY_SERIAL_NAME: Map<String, FilterDef> = FILTERS.associateBy { it.serialName }

fun filterDefByType(filter: RustVideoFilterSnapshot): FilterDef? = when (filter) {
    is RustBrightnessFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Brightness"]
    is RustContrastFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Contrast"]
    is RustSaturationFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Saturation"]
    is RustBlurFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Blur"]
    is RustSharpenFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Sharpen"]
    is RustTransformFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Transform"]
    else -> null
}

fun readFilterProperty(filter: RustVideoFilterSnapshot, paramKey: String): Float = when (filter) {
    is RustBrightnessFilterSnapshot -> filter.value
    is RustContrastFilterSnapshot -> filter.value
    is RustSaturationFilterSnapshot -> filter.value
    is RustBlurFilterSnapshot -> filter.radius
    is RustSharpenFilterSnapshot -> filter.amount
    is RustTransformFilterSnapshot -> when (paramKey) {
        "scale" -> filter.scale
        "translate_x" -> filter.translateX
        "translate_y" -> filter.translateY
        "rotate" -> filter.rotate
        else -> 0f
    }
    else -> 0f
}
