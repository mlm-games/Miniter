package org.mlm.miniter.ui.components.properties

import org.mlm.miniter.editor.model.RustBlendMode

val blendModeOptions: List<Pair<RustBlendMode, String>> = listOf(
    RustBlendMode.Normal to "Normal",
    RustBlendMode.Darken to "Darken",
    RustBlendMode.Multiply to "Multiply",
    RustBlendMode.ColorBurn to "Color Burn",
    RustBlendMode.LinearBurn to "Linear Burn",
    RustBlendMode.Lighten to "Lighten",
    RustBlendMode.Screen to "Screen",
    RustBlendMode.ColorDodge to "Color Dodge",
    RustBlendMode.LinearDodge to "Linear Dodge",
    RustBlendMode.Overlay to "Overlay",
    RustBlendMode.SoftLight to "Soft Light",
    RustBlendMode.HardLight to "Hard Light",
    RustBlendMode.VividLight to "Vivid Light",
    RustBlendMode.LinearLight to "Linear Light",
    RustBlendMode.PinLight to "Pin Light",
    RustBlendMode.HardMix to "Hard Mix",
    RustBlendMode.Difference to "Difference",
    RustBlendMode.Exclusion to "Exclusion",
    RustBlendMode.Subtract to "Subtract",
    RustBlendMode.Average to "Average",
    RustBlendMode.Negation to "Negation",
    RustBlendMode.Phoenix to "Phoenix",
    RustBlendMode.Reflect to "Reflect",
    RustBlendMode.Glow to "Glow",
)

fun blendModeLabel(mode: RustBlendMode): String =
    blendModeOptions.firstOrNull { it.first == mode }?.second ?: mode.name
