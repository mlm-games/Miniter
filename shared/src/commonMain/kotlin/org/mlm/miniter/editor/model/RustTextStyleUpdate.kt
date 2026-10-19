package org.mlm.miniter.editor.model

data class RustTextStyleUpdate(
    val fontSize: Float? = null,
    val color: String? = null,
    val backgroundColor: String? = null,
    val clearBackground: Boolean = false,
    val positionX: Float? = null,
    val positionY: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val fontFamily: String? = null,
    val outlineColor: String? = null,
    val outlineWidth: Float? = null,
    val clearOutline: Boolean = false,
    val shadow: Boolean? = null,
    val curveRadius: Float? = null,
)
