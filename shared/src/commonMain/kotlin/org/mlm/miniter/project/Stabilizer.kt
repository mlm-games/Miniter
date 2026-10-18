package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.engine.ImageData
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

const val STABILIZE_SAMPLE_STEP_MS = 100L
const val STABILIZE_MAX_SAMPLES = 600L
const val STABILIZE_SMOOTH_WINDOW_MS = 500L

private const val STABILIZE_PYRAMID_LEVELS = 3
private const val STABILIZE_MAX_FEATURES = 160
private const val STABILIZE_FEATURE_GRID = 8
private const val STABILIZE_FEATURE_MIN_DISTANCE = 5f
private const val STABILIZE_LK_WINDOW = 7
private const val STABILIZE_LK_ITERATIONS = 10
private const val STABILIZE_FB_MAX = 1.0f
private const val STABILIZE_RANSAC_ITERATIONS = 48
private const val STABILIZE_RANSAC_THRESHOLD = 1.0
private const val STABILIZE_MAX_ZOOM = 1.3

private data class TrackPoint(val x: Float, val y: Float)

private data class FeatureCandidate(val point: TrackPoint, val response: Float)

private data class SimilarityTransform(
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

private data class PathParams(
    val tx: Double,
    val ty: Double,
    val angle: Double,
    val logScale: Double,
)

private data class StabilizerSample(
    val localOffsetUs: Long,
    val step: SimilarityTransform?,
)

private class GrayFrame(
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

private class Pyramid(val levels: List<GrayFrame>)

class StabilizerAnalysis {
    private val samples = ArrayList<StabilizerSample>()
    private var previous: Pyramid? = null

    var frameCount: Int = 0
        private set

    var trackedSteps: Int = 0
        private set

    fun addFrame(frame: ImageData, localOffsetUs: Long, sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        if (frame.width < 8 || frame.height < 8) return
        if (frame.pixels.size < frame.width * frame.height * 4) return
        val gray = grayscale(frame)
        val pyramid = buildPyramid(gray)
        val previousPyramid = previous
        if (previousPyramid != null) {
            val base = previousPyramid.levels[0]
            if (base.width != gray.width || base.height != gray.height) {
                previous = pyramid
                return
            }
        }
        val step = if (previousPyramid == null) {
            null
        } else {
            trackStep(previousPyramid, pyramid, sourceWidth, sourceHeight)
        }
        if (step != null) trackedSteps++
        samples += StabilizerSample(localOffsetUs, step)
        frameCount++
        previous = pyramid
    }

    fun keyframes(
        sourceWidth: Int,
        sourceHeight: Int,
        baseScale: Float,
        baseRotate: Float,
        baseTranslateX: Float,
        baseTranslateY: Float,
        sampleStepMs: Long,
    ): List<RustKeyframe> {
        if (samples.size < 3 || trackedSteps < 2) return emptyList()
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyList()
        val n = samples.size
        val cumulative = ArrayList<SimilarityTransform>(n)
        cumulative += SimilarityTransform.IDENTITY
        for (k in 1 until n) {
            val step = samples[k].step ?: SimilarityTransform.IDENTITY
            cumulative += step.then(cumulative[k - 1])
        }
        val centreX = sourceWidth / 2.0
        val centreY = sourceHeight / 2.0
        val toCentre = SimilarityTransform(1.0, 0.0, -centreX, -centreY)
        val fromCentre = SimilarityTransform(1.0, 0.0, centreX, centreY)
        val params = cumulative.map { decompose(toCentre.then(it).then(fromCentre)) }.toMutableList()
        for (k in 1 until n) {
            val delta = params[k].angle - params[k - 1].angle
            val wrap = round(delta / (2.0 * PI)) * (2.0 * PI)
            params[k] = params[k].copy(angle = params[k].angle - wrap)
        }
        val sigma = max(1.0, STABILIZE_SMOOTH_WINDOW_MS.toDouble() / sampleStepMs.coerceAtLeast(1L))
        val radius = ceil(sigma * 3.0).toInt()
        val smoothed = ArrayList<PathParams>(n)
        for (k in 0 until n) {
            var tx = 0.0
            var ty = 0.0
            var angle = 0.0
            var logScale = 0.0
            var weight = 0.0
            for (j in -radius..radius) {
                val index = (k + j).coerceIn(0, n - 1)
                val w = exp(-(j * j).toDouble() / (2.0 * sigma * sigma))
                tx += params[index].tx * w
                ty += params[index].ty * w
                angle += params[index].angle * w
                logScale += params[index].logScale * w
                weight += w
            }
            if (weight <= 0.0) {
                smoothed += params[k]
            } else {
                smoothed += PathParams(tx / weight, ty / weight, angle / weight, logScale / weight)
            }
        }
        val scale = if (baseScale.isFinite() && baseScale > 0f) baseScale.toDouble() else 1.0
        val angle = if (baseRotate.isFinite()) baseRotate.toDouble() * PI / 180.0 else 0.0
        val baseTx = if (baseTranslateX.isFinite()) baseTranslateX.toDouble() else 0.0
        val baseTy = if (baseTranslateY.isFinite()) baseTranslateY.toDouble() else 0.0
        val base = SimilarityTransform(
            a = scale * cos(angle),
            b = scale * sin(angle),
            tx = baseTx * sourceWidth,
            ty = baseTy * sourceHeight,
        )
        val keyframes = ArrayList<RustKeyframe>(n * 4)
        var lastOffsetUs = Long.MIN_VALUE
        var lastAngleDegrees: Double? = null
        for (k in 0 until n) {
            val offsetUs = samples[k].localOffsetUs
            if (offsetUs == lastOffsetUs) continue
            lastOffsetUs = offsetUs
            val raw = compose(params[k])
            val smoothedRaw = compose(smoothed[k])
            val inverse = raw.inverse() ?: SimilarityTransform.IDENTITY
            val centredCorrection = smoothedRaw.then(inverse)
            val correction = fromCentre.then(centredCorrection).then(toCentre)
            val covered = coverCorrection(correction, sourceWidth, sourceHeight)
            val transform = covered.then(base)
            val transformScale = hypot(transform.a, transform.b)
            var transformAngle = atan2(transform.b, transform.a) * 180.0 / PI
            val translateX = transform.tx / sourceWidth
            val translateY = transform.ty / sourceHeight
            if (!transformScale.isFinite() || transformScale <= 0.0) continue
            if (!transformAngle.isFinite() || !translateX.isFinite() || !translateY.isFinite()) continue
            val previousAngle = lastAngleDegrees
            if (previousAngle != null) {
                while (transformAngle - previousAngle > 180.0) transformAngle -= 360.0
                while (transformAngle - previousAngle < -180.0) transformAngle += 360.0
            }
            lastAngleDegrees = transformAngle
            keyframes += RustKeyframe(
                KeyframeParams.TRANSFORM_SCALE,
                offsetUs,
                transformScale.toFloat(),
                RustEasing.Linear,
            )
            keyframes += RustKeyframe(
                KeyframeParams.TRANSFORM_TRANSLATE_X,
                offsetUs,
                translateX.toFloat(),
                RustEasing.Linear,
            )
            keyframes += RustKeyframe(
                KeyframeParams.TRANSFORM_TRANSLATE_Y,
                offsetUs,
                translateY.toFloat(),
                RustEasing.Linear,
            )
            keyframes += RustKeyframe(
                KeyframeParams.TRANSFORM_ROTATE,
                offsetUs,
                transformAngle.toFloat(),
                RustEasing.Linear,
            )
        }
        return keyframes
    }
}

private fun grayscale(frame: ImageData): GrayFrame {
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

private fun downsample(frame: GrayFrame): GrayFrame {
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

private fun buildPyramid(base: GrayFrame): Pyramid {
    val levels = ArrayList<GrayFrame>(STABILIZE_PYRAMID_LEVELS)
    levels += base
    while (levels.size < STABILIZE_PYRAMID_LEVELS) {
        val last = levels.last()
        if (last.width < 16 || last.height < 16) break
        levels += downsample(last)
    }
    return Pyramid(levels)
}

private fun detectFeatures(base: GrayFrame): List<TrackPoint> {
    val margin = STABILIZE_LK_WINDOW + 2
    if (base.width <= margin * 2 || base.height <= margin * 2) return emptyList()
    val radius = 3
    val candidates = ArrayList<FeatureCandidate>()
    var best = 0f
    var y = margin
    while (y <= base.height - 1 - margin) {
        var x = margin
        while (x <= base.width - 1 - margin) {
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
            x += STABILIZE_FEATURE_GRID
        }
        y += STABILIZE_FEATURE_GRID
    }
    if (best <= 1e-9f) return emptyList()
    val threshold = best * 0.01f
    candidates.sortWith(compareByDescending<FeatureCandidate> { it.response })
    val selected = ArrayList<TrackPoint>(STABILIZE_MAX_FEATURES)
    val minDistanceSquared = STABILIZE_FEATURE_MIN_DISTANCE * STABILIZE_FEATURE_MIN_DISTANCE
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
            if (selected.size >= STABILIZE_MAX_FEATURES) break
        }
    }
    return selected
}

private fun lk(
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
        val size = STABILIZE_LK_WINDOW * 2 + 1
        val template = FloatArray(size * size)
        val gradientX = FloatArray(size * size)
        val gradientY = FloatArray(size * size)
        var gxx = 0f
        var gxy = 0f
        var gyy = 0f
        var index = 0
        for (wy in -STABILIZE_LK_WINDOW..STABILIZE_LK_WINDOW) {
            for (wx in -STABILIZE_LK_WINDOW..STABILIZE_LK_WINDOW) {
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
        while (iteration < STABILIZE_LK_ITERATIONS) {
            var bx = 0f
            var by = 0f
            var k = 0
            for (wy in -STABILIZE_LK_WINDOW..STABILIZE_LK_WINDOW) {
                for (wx in -STABILIZE_LK_WINDOW..STABILIZE_LK_WINDOW) {
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

private fun trackStep(
    previous: Pyramid,
    next: Pyramid,
    sourceWidth: Int,
    sourceHeight: Int,
): SimilarityTransform? {
    val features = detectFeatures(previous.levels[0])
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
        if (ex * ex + ey * ey >= STABILIZE_FB_MAX * STABILIZE_FB_MAX) continue
        src += TrackPoint((feature.x * scaleX).toFloat(), (feature.y * scaleY).toFloat())
        dst += TrackPoint((tracked.x * scaleX).toFloat(), (tracked.y * scaleY).toFloat())
    }
    if (src.size < 3) return null
    return fitRobust(src, dst)
}

private fun fitSimilarity(src: List<TrackPoint>, dst: List<TrackPoint>): SimilarityTransform? {
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

private class XorShift(seed: Long) {
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

private fun distanceSquared(a: TrackPoint, b: TrackPoint): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    return dx * dx + dy * dy
}

private fun inliers(
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
        if (dx * dx + dy * dy < STABILIZE_RANSAC_THRESHOLD * STABILIZE_RANSAC_THRESHOLD) {
            result += i
        }
    }
    return result.toIntArray()
}

private fun fitRobust(src: List<TrackPoint>, dst: List<TrackPoint>): SimilarityTransform? {
    val n = src.size
    if (n < 3 || dst.size != n) return null
    val random = XorShift(0x9E3779B97F4A7C15uL.toLong() xor n.toLong())
    var best = IntArray(0)
    for (iteration in 0 until STABILIZE_RANSAC_ITERATIONS) {
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

private fun decompose(transform: SimilarityTransform): PathParams {
    val scale = max(hypot(transform.a, transform.b), 1e-9)
    return PathParams(
        tx = transform.tx,
        ty = transform.ty,
        angle = atan2(transform.b, transform.a),
        logScale = ln(scale),
    )
}

private fun compose(params: PathParams): SimilarityTransform {
    val scale = exp(params.logScale)
    return SimilarityTransform(
        a = scale * cos(params.angle),
        b = scale * sin(params.angle),
        tx = params.tx,
        ty = params.ty,
    )
}

private fun zoomAbout(transform: SimilarityTransform, zoom: Double, width: Int, height: Int): SimilarityTransform {
    val centreX = width / 2.0
    val centreY = height / 2.0
    return SimilarityTransform(
        a = transform.a * zoom,
        b = transform.b * zoom,
        tx = centreX + (transform.tx - centreX) * zoom,
        ty = centreY + (transform.ty - centreY) * zoom,
    )
}

private fun covers(transform: SimilarityTransform, width: Int, height: Int): Boolean {
    val inverse = transform.inverse() ?: return false
    val w = width.toDouble()
    val h = height.toDouble()
    val corners = doubleArrayOf(0.0, 0.0, w, 0.0, 0.0, h, w, h)
    var i = 0
    while (i < corners.size) {
        val x = inverse.a * corners[i] - inverse.b * corners[i + 1] + inverse.tx
        val y = inverse.b * corners[i] + inverse.a * corners[i + 1] + inverse.ty
        if (x < -1e-6 || y < -1e-6 || x > w + 1e-6 || y > h + 1e-6) return false
        i += 2
    }
    return true
}

private fun clampTranslation(transform: SimilarityTransform, width: Int, height: Int): SimilarityTransform {
    val det = transform.a * transform.a + transform.b * transform.b
    if (!det.isFinite() || det < 1e-12) return SimilarityTransform.IDENTITY
    val a = transform.a
    val b = transform.b
    val ux = (a * transform.tx + b * transform.ty) / det
    val uy = (-b * transform.tx + a * transform.ty) / det
    val w = width.toDouble()
    val h = height.toDouble()
    val corners = doubleArrayOf(0.0, 0.0, w, 0.0, 0.0, h, w, h)
    var minX = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var i = 0
    while (i < corners.size) {
        val vx = (a * corners[i] + b * corners[i + 1]) / det
        val vy = (-b * corners[i] + a * corners[i + 1]) / det
        if (vx < minX) minX = vx
        if (vx > maxX) maxX = vx
        if (vy < minY) minY = vy
        if (vy > maxY) maxY = vy
        i += 2
    }
    val lowX = maxX - w
    val highX = minX
    val lowY = maxY - h
    val highY = minY
    if (lowX > highX || lowY > highY) return SimilarityTransform.IDENTITY
    val clampedX = ux.coerceIn(lowX, highX)
    val clampedY = uy.coerceIn(lowY, highY)
    return SimilarityTransform(
        a = a,
        b = b,
        tx = a * clampedX - b * clampedY,
        ty = b * clampedX + a * clampedY,
    )
}

private fun coverCorrection(transform: SimilarityTransform, width: Int, height: Int): SimilarityTransform {
    if (covers(transform, width, height)) {
        return clampTranslation(transform, width, height)
    }
    var low = 1.0
    var high = STABILIZE_MAX_ZOOM
    val maximum = zoomAbout(transform, high, width, height)
    if (!covers(maximum, width, height)) {
        return clampTranslation(maximum, width, height)
    }
    for (iteration in 0 until 24) {
        val mid = (low + high) / 2.0
        if (covers(zoomAbout(transform, mid, width, height), width, height)) {
            high = mid
        } else {
            low = mid
        }
    }
    return clampTranslation(zoomAbout(transform, high, width, height), width, height)
}
