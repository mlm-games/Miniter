package org.mlm.miniter.project

import org.mlm.miniter.engine.ImageData
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal const val TRACKING_PYRAMID_LEVELS = 3
internal const val TRACKING_MAX_FEATURES = 160
internal const val TRACKING_FEATURE_GRID = 8
internal const val TRACKING_FEATURE_MIN_DISTANCE = 5f
internal const val TRACKING_LK_WINDOW = 7
internal const val TRACKING_LK_ITERATIONS = 10
internal const val TRACKING_FB_MAX = 1.0f
internal const val TRACKING_RANSAC_ITERATIONS = 48
internal const val TRACKING_RANSAC_THRESHOLD = 1.0

internal data class TrackPoint(val x: Float, val y: Float)

internal data class FeatureCandidate(val point: TrackPoint, val response: Float)

internal data class TrackRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal data class SimilarityTransform(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
) {
    fun then(other: SimilarityTransform): SimilarityTransform = SimilarityTransform(
        a = a * other.a - b * other.b,
        b = a * other.b + b * other.a,
        tx = a * other.tx - b * other.ty + tx,
        ty = b * other.tx + a * other.ty + ty,
    )

    fun inverse(): SimilarityTransform? {
        val det = a * a + b * b
        if (!det.isFinite() || det < 1e-12) return null
        val ia = a / det
        val ib = -b / det
        return SimilarityTransform(
            a = ia,
            b = ib,
            tx = -(ia * tx - ib * ty),
            ty = -(ib * tx + ia * ty),
        )
    }

    companion object {
        val IDENTITY = SimilarityTransform(1.0, 0.0, 0.0, 0.0)
    }
}

internal class GrayFrame(
    val width: Int,
    val height: Int,
    val pixels: FloatArray,
) {
    fun at(x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }

    fun sample(x: Float, y: Float): Float {
        val x0 = floor(x)
        val y0 = floor(y)
        val fx = x - x0
        val fy = y - y0
        val ix = x0.toInt()
        val iy = y0.toInt()
        val a = at(ix, iy)
        val b = at(ix + 1, iy)
        val c = at(ix, iy + 1)
        val d = at(ix + 1, iy + 1)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    fun gradientX(x: Int, y: Int): Float = (at(x + 1, y) - at(x - 1, y)) * 0.5f

    fun gradientY(x: Int, y: Int): Float = (at(x, y + 1) - at(x, y - 1)) * 0.5f
}

internal class Pyramid(val levels: List<GrayFrame>)

internal fun grayscale(frame: ImageData): GrayFrame {
    val width = frame.width
    val height = frame.height
    val pixels = frame.pixels
    val gray = FloatArray(width * height)
    for (i in gray.indices) {
        val p = i * 4
        gray[i] = (0.2126f * (pixels[p].toInt() and 0xFF) +
            0.7152f * (pixels[p + 1].toInt() and 0xFF) +
            0.0722f * (pixels[p + 2].toInt() and 0xFF)) / 255f
    }
    return GrayFrame(width, height, gray)
}

internal fun downsample(frame: GrayFrame): GrayFrame {
    val width = max(1, frame.width / 2)
    val height = max(1, frame.height / 2)
    val pixels = FloatArray(width * height)
    for (y in 0 until height) {
        val y0 = min(2 * y, frame.height - 1)
        val y1 = min(2 * y + 1, frame.height - 1)
        for (x in 0 until width) {
            val x0 = min(2 * x, frame.width - 1)
            val x1 = min(2 * x + 1, frame.width - 1)
            pixels[y * width + x] = (
                frame.pixels[y0 * frame.width + x0] +
                    frame.pixels[y0 * frame.width + x1] +
                    frame.pixels[y1 * frame.width + x0] +
                    frame.pixels[y1 * frame.width + x1]
                ) * 0.25f
        }
    }
    return GrayFrame(width, height, pixels)
}

internal fun buildPyramid(base: GrayFrame): Pyramid {
    val levels = ArrayList<GrayFrame>(TRACKING_PYRAMID_LEVELS)
    levels += base
    while (levels.size < TRACKING_PYRAMID_LEVELS) {
        val last = levels.last()
        if (last.width < 16 || last.height < 16) break
        levels += downsample(last)
    }
    return Pyramid(levels)
}

internal fun detectFeatures(base: GrayFrame, region: TrackRegion? = null): List<TrackPoint> {
    val margin = TRACKING_LK_WINDOW + 2
    if (base.width <= margin * 2 || base.height <= margin * 2) return emptyList()
    val radius = 3
    val candidates = ArrayList<FeatureCandidate>()
    var best = 0f
    var y = margin
    while (y <= base.height - 1 - margin) {
        var x = margin
        while (x <= base.width - 1 - margin) {
            if (region != null && (x < region.left || x > region.right || y < region.top || y > region.bottom)) {
                x += TRACKING_FEATURE_GRID
                continue
            }
            var a = 0f
            var b = 0f
            var c = 0f
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val gx = base.gradientX(x + dx, y + dy)
                    val gy = base.gradientY(x + dx, y + dy)
                    a += gx * gx
                    b += gx * gy
                    c += gy * gy
                }
            }
            val trace = (a + c) * 0.5f
            val diff = (a - c) * 0.5f
            val response = trace - sqrt(diff * diff + b * b)
            if (response > 0f) {
                candidates += FeatureCandidate(TrackPoint(x.toFloat(), y.toFloat()), response)
                if (response > best) best = response
            }
            x += TRACKING_FEATURE_GRID
        }
        y += TRACKING_FEATURE_GRID
    }
    if (best <= 1e-9f) return emptyList()
    val threshold = best * 0.01f
    candidates.sortWith(compareByDescending<FeatureCandidate> { it.response })
    val selected = ArrayList<TrackPoint>(TRACKING_MAX_FEATURES)
    val minDistanceSquared = TRACKING_FEATURE_MIN_DISTANCE * TRACKING_FEATURE_MIN_DISTANCE
    for (candidate in candidates) {
        if (candidate.response < threshold) continue
        val point = candidate.point
        val distant = selected.all { selectedPoint ->
            val dx = selectedPoint.x - point.x
            val dy = selectedPoint.y - point.y
            dx * dx + dy * dy >= minDistanceSquared
        }
        if (distant) {
            selected += point
            if (selected.size >= TRACKING_MAX_FEATURES) break
        }
    }
    return selected
}

internal fun lk(
    previous: List<GrayFrame>,
    next: List<GrayFrame>,
    point: TrackPoint,
): TrackPoint? {
    val levels = min(previous.size, next.size)
    var dx = 0f
    var dy = 0f
    for (level in levels - 1 downTo 0) {
        val scale = (1 shl level).toFloat()
        val a = previous[level]
        val b = next[level]
        val px = point.x / scale
        val py = point.y / scale
        val size = TRACKING_LK_WINDOW * 2 + 1
        val template = FloatArray(size * size)
        val gradientX = FloatArray(size * size)
        val gradientY = FloatArray(size * size)
        var gxx = 0f
        var gxy = 0f
        var gyy = 0f
        var index = 0
        for (wy in -TRACKING_LK_WINDOW..TRACKING_LK_WINDOW) {
            for (wx in -TRACKING_LK_WINDOW..TRACKING_LK_WINDOW) {
                val x = px + wx
                val y = py + wy
                val ix = (a.sample(x + 1f, y) - a.sample(x - 1f, y)) * 0.5f
                val iy = (a.sample(x, y + 1f) - a.sample(x, y - 1f)) * 0.5f
                gxx += ix * ix
                gxy += ix * iy
                gyy += iy * iy
                template[index] = a.sample(x, y)
                gradientX[index] = ix
                gradientY[index] = iy
                index++
            }
        }
        val det = gxx * gyy - gxy * gxy
        if (!det.isFinite() || det < 1e-7f) return null
        var vx = 0f
        var vy = 0f
        var iteration = 0
        while (iteration < TRACKING_LK_ITERATIONS) {
            var bx = 0f
            var by = 0f
            var k = 0
            for (wy in -TRACKING_LK_WINDOW..TRACKING_LK_WINDOW) {
                for (wx in -TRACKING_LK_WINDOW..TRACKING_LK_WINDOW) {
                    val x = px + wx + dx + vx
                    val y = py + wy + dy + vy
                    val diff = template[k] - b.sample(x, y)
                    bx += diff * gradientX[k]
                    by += diff * gradientY[k]
                    k++
                }
            }
            val ex = (gyy * bx - gxy * by) / det
            val ey = (gxx * by - gxy * bx) / det
            vx += ex
            vy += ey
            iteration++
            if (ex * ex + ey * ey < 1e-6f) break
        }
        dx += vx
        dy += vy
        if (level > 0) {
            dx *= 2f
            dy *= 2f
        }
    }
    val qx = point.x + dx
    val qy = point.y + dy
    if (!qx.isFinite() || !qy.isFinite()) return null
    val width = previous[0].width.toFloat()
    val height = previous[0].height.toFloat()
    if (qx < 0f || qy < 0f || qx >= width || qy >= height) return null
    return TrackPoint(qx, qy)
}

internal fun trackStep(
    previous: Pyramid,
    next: Pyramid,
    sourceWidth: Int,
    sourceHeight: Int,
    region: TrackRegion? = null,
): SimilarityTransform? {
    val features = detectFeatures(previous.levels[0], region)
    if (features.size < 3) return null
    val scaleX = sourceWidth.toDouble() / previous.levels[0].width
    val scaleY = sourceHeight.toDouble() / previous.levels[0].height
    val src = ArrayList<TrackPoint>(features.size)
    val dst = ArrayList<TrackPoint>(features.size)
    for (feature in features) {
        val tracked = lk(previous.levels, next.levels, feature) ?: continue
        val back = lk(next.levels, previous.levels, tracked) ?: continue
        val ex = back.x - feature.x
        val ey = back.y - feature.y
        if (ex * ex + ey * ey >= TRACKING_FB_MAX * TRACKING_FB_MAX) continue
        src += TrackPoint((feature.x * scaleX).toFloat(), (feature.y * scaleY).toFloat())
        dst += TrackPoint((tracked.x * scaleX).toFloat(), (tracked.y * scaleY).toFloat())
    }
    if (src.size < 3) return null
    return fitRobust(src, dst)
}

internal fun fitSimilarity(src: List<TrackPoint>, dst: List<TrackPoint>): SimilarityTransform? {
    val n = src.size
    if (n < 2 || dst.size != n) return null
    var srcX = 0.0
    var srcY = 0.0
    var dstX = 0.0
    var dstY = 0.0
    for (i in 0 until n) {
        srcX += src[i].x
        srcY += src[i].y
        dstX += dst[i].x
        dstY += dst[i].y
    }
    val inv = 1.0 / n
    srcX *= inv
    srcY *= inv
    dstX *= inv
    dstY *= inv
    var sa = 0.0
    var sb = 0.0
    var ss = 0.0
    for (i in 0 until n) {
        val x = src[i].x - srcX
        val y = src[i].y - srcY
        val u = dst[i].x - dstX
        val v = dst[i].y - dstY
        sa += x * u + y * v
        sb += x * v - y * u
        ss += x * x + y * y
    }
    if (!ss.isFinite() || ss < 1e-9) return null
    val a = sa / ss
    val b = sb / ss
    val tx = dstX - (a * srcX - b * srcY)
    val ty = dstY - (b * srcX + a * srcY)
    if (!a.isFinite() || !b.isFinite() || !tx.isFinite() || !ty.isFinite()) return null
    if (a * a + b * b < 1e-9) return null
    return SimilarityTransform(a, b, tx, ty)
}

internal class XorShift(seed: Long) {
    private var state: Long = if (seed == 0L) 1L else seed

    fun next(): Long {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return x
    }

    fun below(bound: Int): Int = ((next() ushr 1) % bound.toLong()).toInt()
}

internal fun distanceSquared(a: TrackPoint, b: TrackPoint): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    return dx * dx + dy * dy
}

internal fun inliers(
    transform: SimilarityTransform,
    src: List<TrackPoint>,
    dst: List<TrackPoint>,
): IntArray {
    val result = ArrayList<Int>(src.size)
    for (i in src.indices) {
        val x = transform.a * src[i].x - transform.b * src[i].y + transform.tx
        val y = transform.b * src[i].x + transform.a * src[i].y + transform.ty
        val dx = x - dst[i].x
        val dy = y - dst[i].y
        if (dx * dx + dy * dy < TRACKING_RANSAC_THRESHOLD * TRACKING_RANSAC_THRESHOLD) {
            result += i
        }
    }
    return result.toIntArray()
}

internal fun fitRobust(src: List<TrackPoint>, dst: List<TrackPoint>): SimilarityTransform? {
    val n = src.size
    if (n < 3 || dst.size != n) return null
    val random = XorShift(0x9E3779B97F4A7C15uL.toLong() xor n.toLong())
    var best = IntArray(0)
    for (iteration in 0 until TRACKING_RANSAC_ITERATIONS) {
        val i = random.below(n)
        var j = random.below(n)
        var guard = 0
        while ((j == i || distanceSquared(src[j], src[i]) < 16.0) && guard < 16) {
            j = random.below(n)
            guard++
        }
        if (j == i) continue
        val sampleSrc = listOf(src[i], src[j])
        val sampleDst = listOf(dst[i], dst[j])
        val model = fitSimilarity(sampleSrc, sampleDst) ?: continue
        val found = inliers(model, src, dst)
        if (found.size > best.size) {
            best = found
            if (best.size == n) break
        }
    }
    if (best.size < 3) return null
    for (round in 0..1) {
        val inlierSrc = best.map { src[it] }
        val inlierDst = best.map { dst[it] }
        val model = fitSimilarity(inlierSrc, inlierDst) ?: return null
        val found = inliers(model, src, dst)
        if (found.size < best.size) break
        best = found
    }
    val transform = fitSimilarity(best.map { src[it] }, best.map { dst[it] }) ?: return null
    return if (inliers(transform, src, dst).size >= 3) transform else null
}
