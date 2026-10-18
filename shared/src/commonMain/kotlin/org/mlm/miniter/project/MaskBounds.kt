package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEllipseMaskShape
import org.mlm.miniter.editor.model.RustMaskEffect
import org.mlm.miniter.editor.model.RustPathMaskShape
import org.mlm.miniter.editor.model.RustRectangleMaskShape
import org.mlm.miniter.editor.model.RustShapeMaskSource

data class MaskBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

fun maskShapeBounds(mask: RustMaskEffect): MaskBounds? {
    val shape = (mask.source as? RustShapeMaskSource)?.shape ?: return null
    val t = mask.transform
    val scale = t.scale.coerceAtLeast(0.001f)
    if (shape is RustPathMaskShape) {
        if (shape.vertices.isEmpty()) return null
        val count = shape.vertices.size.toFloat()
        val cx0 = shape.vertices.sumOf { it.p.x.toDouble() }.toFloat() / count
        val cy0 = shape.vertices.sumOf { it.p.y.toDouble() }.toFloat() / count
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (vertex in shape.vertices) {
            val x = cx0 + (vertex.p.x - cx0) * scale + t.translateX
            val y = cy0 + (vertex.p.y - cy0) * scale + t.translateY
            minX = minX.coerceAtMost(x)
            minY = minY.coerceAtMost(y)
            maxX = maxX.coerceAtLeast(x)
            maxY = maxY.coerceAtLeast(y)
        }
        return MaskBounds(minX, minY, maxX, maxY)
    }
    val halfW: Float
    val halfH: Float
    val cx: Float
    val cy: Float
    when (shape) {
        is RustRectangleMaskShape -> {
            cx = (shape.left + shape.right) / 2f
            cy = (shape.top + shape.bottom) / 2f
            halfW = (shape.right - shape.left) / 2f
            halfH = (shape.bottom - shape.top) / 2f
        }
        is RustEllipseMaskShape -> {
            cx = shape.centerX
            cy = shape.centerY
            halfW = shape.radiusX
            halfH = shape.radiusY
        }
        is RustPathMaskShape -> return null
    }
    val ncx = cx + t.translateX
    val ncy = cy + t.translateY
    val nhw = halfW * scale
    val nhh = halfH * scale
    return MaskBounds(ncx - nhw, ncy - nhh, ncx + nhw, ncy + nhh)
}
