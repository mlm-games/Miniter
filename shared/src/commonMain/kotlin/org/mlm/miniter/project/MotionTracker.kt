package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustMaskEffect
import org.mlm.miniter.engine.ImageData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

const val MOTION_TRACK_SAMPLE_STEP_MS = 100L
const val MOTION_TRACK_MAX_SAMPLES = 600L
const val MOTION_TRACK_ROTATE_THRESHOLD_DEGREES = 0.5
const val MOTION_TRACK_SCALE_THRESHOLD = 0.01

private const val TRACK_PYRAMID_LEVELS = 3
private const val TRACK_MAX_FEATURES = 160
private const val TRACK_FEATURE_GRID = 8
private const val TRACK_FEATURE_MIN_DISTANCE = 5f
private const val TRACK_LK_WINDOW = 7
private const val TRACK_LK_ITERATIONS = 10
private const val TRACK_FB_MAX = 1.0f
private const val TRACK_RANSAC_ITERATIONS = 48
private const val TRACK_RANSAC_THRESHOLD = 1.0

data class MotionTrackSample(
    val localOffsetUs: Long,
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
)

data class MotionTrackBake(
    val keyframes: List<RustKeyframe>,
    val params: List<String>,
)

private data class TrackedPoint(val x: Float, val y: Float)

private data class TrackFeatureCandidate(val point: TrackedPoint, val response: Float)

private data class TrackRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

private data class TrackedStep(val localOffsetUs: Long, val step: TrackSimilarityTransform?)

private data class TrackKinematics(
    val sigma: Double,
    val angleDegrees: Double,
    val translateX: Double,
    val translateY: Double,
)

private data class TrackSimilarityTransform(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
) {
    fun then(other: TrackSimilarityTransform): TrackSimilarityTransform = TrackSimilarityTransform(
        a = a * other.a - b * other.b,
        b = a * other.b + b * other.a,
        tx = a * other.tx - b * other.ty + tx,
        ty = b * other.tx + a * other.ty + ty,
    )

    companion object {
        val IDENTITY = TrackSimilarityTransform(1.0, 0.0, 0.0, 0.0)
    }
}

private class TrackGrayFrame(
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

private class TrackPyramid(val levels: List<TrackGrayFrame>)

class MotionTrackerAnalysis(private val region: MaskBounds) {
    private val samples = ArrayList<TrackedStep>()
    private var previous: TrackPyramid? = null

    var frameCount: Int = 0
        private set

    var trackedSteps: Int = 0
        private set

    fun addFrame(frame: ImageData, localOffsetUs: Long, sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        if (frame.width < 8 || frame.height < 8) return
        if (frame.pixels.size < frame.width * frame.height * 4) return
        val gray = trackGrayscale(frame)
        val pyramid = trackBuildPyramid(gray)
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
            trackFrameStep(previousPyramid, pyramid, sourceWidth, sourceHeight, pixelRegion(frame))
        }
        if (step != null) trackedSteps++
        samples += TrackedStep(localOffsetUs, step)
        frameCount++
        previous = pyramid
    }

    fun motionSamples(): List<MotionTrackSample> {
        val result = ArrayList<MotionTrackSample>(samples.size)
        var cumulative = TrackSimilarityTransform.IDENTITY
        for (sample in samples) {
            val step = sample.step
            if (step != null) cumulative = step.then(cumulative)
            result += MotionTrackSample(
                localOffsetUs = sample.localOffsetUs,
                a = cumulative.a,
                b = cumulative.b,
                tx = cumulative.tx,
                ty = cumulative.ty,
            )
        }
        return result
    }

    private fun pixelRegion(frame: ImageData): TrackRegion = TrackRegion(
        left = (region.left * frame.width).coerceIn(0f, frame.width.toFloat()),
        top = (region.top * frame.height).coerceIn(0f, frame.height.toFloat()),
        right = (region.right * frame.width).coerceIn(0f, frame.width.toFloat()),
        bottom = (region.bottom * frame.height).coerceIn(0f, frame.height.toFloat()),
    )
}

fun maskRegionInVideoFrame(
    bounds: MaskBounds,
    canvasWidth: Int,
    canvasHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
): MaskBounds? {
    if (canvasWidth <= 0 || canvasHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null
    if (!bounds.left.isFinite() || !bounds.top.isFinite() || !bounds.right.isFinite() || !bounds.bottom.isFinite()) {
        return null
    }
    val fitScale = min(canvasWidth.toDouble() / videoWidth, canvasHeight.toDouble() / videoHeight)
    if (!fitScale.isFinite() || fitScale <= 0.0) return null
    val scaledWidth = round(videoWidth * fitScale).toInt().coerceAtLeast(1)
    val scaledHeight = round(videoHeight * fitScale).toInt().coerceAtLeast(1)
    val width = scaledWidth.toDouble() / canvasWidth
    val height = scaledHeight.toDouble() / canvasHeight
    if (width <= 0.0 || height <= 0.0) return null
    val offsetX = (canvasWidth - scaledWidth) / 2.0 / canvasWidth
    val offsetY = (canvasHeight - scaledHeight) / 2.0 / canvasHeight
    return MaskBounds(
        left = ((bounds.left - offsetX) / width).coerceIn(0.0, 1.0).toFloat(),
        top = ((bounds.top - offsetY) / height).coerceIn(0.0, 1.0).toFloat(),
        right = ((bounds.right - offsetX) / width).coerceIn(0.0, 1.0).toFloat(),
        bottom = ((bounds.bottom - offsetY) / height).coerceIn(0.0, 1.0).toFloat(),
    )
}

fun motionTrackingKeyframes(
    samples: List<MotionTrackSample>,
    mask: RustMaskEffect,
    videoWidth: Int,
    videoHeight: Int,
    canvasWidth: Int,
    canvasHeight: Int,
): MotionTrackBake? {
    if (samples.size < 2) return null
    if (videoWidth <= 0 || videoHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return null
    val fitScale = min(canvasWidth.toDouble() / videoWidth, canvasHeight.toDouble() / videoHeight)
    if (!fitScale.isFinite() || fitScale <= 0.0) return null
    val scaledWidth = round(videoWidth * fitScale).toInt().coerceAtLeast(1)
    val scaledHeight = round(videoHeight * fitScale).toInt().coerceAtLeast(1)
    val pixelScaleX = scaledWidth.toDouble() / videoWidth
    val pixelScaleY = scaledHeight.toDouble() / videoHeight
    val offsetX = (canvasWidth - scaledWidth) / 2.0
    val offsetY = (canvasHeight - scaledHeight) / 2.0
    val transform = mask.transform
    val baseScale = if (transform.scale.isFinite() && transform.scale > 0f) transform.scale.toDouble() else 1.0
    val baseRotate = if (transform.rotate.isFinite()) transform.rotate.toDouble() else 0.0
    val baseTranslateX = if (transform.translateX.isFinite()) transform.translateX.toDouble() else 0.0
    val baseTranslateY = if (transform.translateY.isFinite()) transform.translateY.toDouble() else 0.0

    val kinematics = ArrayList<TrackKinematics>(samples.size)
    var maxAngle = 0.0
    var maxScaleDeviation = 0.0
    for (sample in samples) {
        val a = sample.a
        val b = sample.b
        if (!a.isFinite() || !b.isFinite() || !sample.tx.isFinite() || !sample.ty.isFinite()) return null
        val sigma = hypot(a, b)
        if (!sigma.isFinite() || sigma <= 1e-9) return null
        val angleDegrees = atan2(b, a) * 180.0 / PI
        if (!angleDegrees.isFinite()) return null
        val canvasX = pixelScaleX * sample.tx + offsetX - (a * offsetX - b * offsetY * (pixelScaleX / pixelScaleY))
        val canvasY = pixelScaleY * sample.ty + offsetY - (b * offsetX * (pixelScaleY / pixelScaleX) + a * offsetY)
        if (!canvasX.isFinite() || !canvasY.isFinite()) return null
        kinematics += TrackKinematics(
            sigma = sigma,
            angleDegrees = angleDegrees,
            translateX = canvasX / canvasWidth,
            translateY = canvasY / canvasHeight,
        )
        maxAngle = max(maxAngle, abs(angleDegrees))
        maxScaleDeviation = max(maxScaleDeviation, abs(sigma - 1.0))
    }

    val bakeRotate = maxAngle >= MOTION_TRACK_ROTATE_THRESHOLD_DEGREES
    val bakeScale = maxScaleDeviation >= MOTION_TRACK_SCALE_THRESHOLD
    val params = ArrayList<String>(4)
    params += KeyframeParams.MASK_TRANSLATE_X
    params += KeyframeParams.MASK_TRANSLATE_Y
    if (bakeRotate) params += KeyframeParams.MASK_ROTATE
    if (bakeScale) params += KeyframeParams.MASK_SCALE

    val keyframes = ArrayList<RustKeyframe>(samples.size * params.size)
    var lastOffsetUs = Long.MIN_VALUE
    var lastAngle: Double? = null
    for ((index, sample) in samples.withIndex()) {
        if (sample.localOffsetUs == lastOffsetUs) continue
        lastOffsetUs = sample.localOffsetUs
        val sampleKinematics = kinematics[index]
        val sigma = if (bakeScale) sampleKinematics.sigma else 1.0
        val motionAngle = if (bakeRotate) sampleKinematics.angleDegrees else 0.0
        var angle = baseRotate + motionAngle
        val previousAngle = lastAngle
        if (previousAngle != null) {
            while (angle - previousAngle > 180.0) angle -= 360.0
            while (angle - previousAngle < -180.0) angle += 360.0
        }
        lastAngle = angle
        val theta = angle * PI / 180.0
        val motionTheta = motionAngle * PI / 180.0
        val displacementX = sigma * (0.5 * cos(motionTheta) - 0.5 * sin(motionTheta)) +
            sampleKinematics.translateX - 0.5
        val displacementY = sigma * (0.5 * sin(motionTheta) + 0.5 * cos(motionTheta)) +
            sampleKinematics.translateY - 0.5
        val translateX = sigma * baseTranslateX + cos(theta) * displacementX + sin(theta) * displacementY
        val translateY = sigma * baseTranslateY - sin(theta) * displacementX + cos(theta) * displacementY
        val scale = baseScale * sigma
        if (!scale.isFinite() || !angle.isFinite() || !translateX.isFinite() || !translateY.isFinite()) continue
        keyframes += RustKeyframe(
            KeyframeParams.MASK_TRANSLATE_X,
            sample.localOffsetUs,
            translateX.toFloat(),
            RustEasing.Linear,
        )
        keyframes += RustKeyframe(
            KeyframeParams.MASK_TRANSLATE_Y,
            sample.localOffsetUs,
            translateY.toFloat(),
            RustEasing.Linear,
        )
        if (bakeRotate) {
            keyframes += RustKeyframe(
                KeyframeParams.MASK_ROTATE,
                sample.localOffsetUs,
                angle.toFloat(),
                RustEasing.Linear,
            )
        }
        if (bakeScale) {
            keyframes += RustKeyframe(
                KeyframeParams.MASK_SCALE,
                sample.localOffsetUs,
                scale.toFloat(),
                RustEasing.Linear,
            )
        }
    }
    if (keyframes.isEmpty()) return null
    return MotionTrackBake(keyframes = keyframes, params = params.toList())
}

private fun trackGrayscale(frame: ImageData): TrackGrayFrame {
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
    return TrackGrayFrame(width, height, gray)
}

private fun trackDownsample(frame: TrackGrayFrame): TrackGrayFrame {
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
    return TrackGrayFrame(width, height, pixels)
}

private fun trackBuildPyramid(base: TrackGrayFrame): TrackPyramid {
    val levels = ArrayList<TrackGrayFrame>(TRACK_PYRAMID_LEVELS)
    levels += base
    while (levels.size < TRACK_PYRAMID_LEVELS) {
        val last = levels.last()
        if (last.width < 16 || last.height < 16) break
        levels += trackDownsample(last)
    }
    return TrackPyramid(levels)
}

private fun trackDetectFeatures(base: TrackGrayFrame, region: TrackRegion): List<TrackedPoint> {
    val margin = TRACK_LK_WINDOW + 2
    if (base.width <= margin * 2 || base.height <= margin * 2) return emptyList()
    val radius = 3
    val candidates = ArrayList<TrackFeatureCandidate>()
    var best = 0f
    var y = margin
    while (y <= base.height - 1 - margin) {
        var x = margin
        while (x <= base.width - 1 - margin) {
            if (x < region.left || x > region.right || y < region.top || y > region.bottom) {
                x += TRACK_FEATURE_GRID
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
                candidates += TrackFeatureCandidate(TrackedPoint(x.toFloat(), y.toFloat()), response)
                if (response > best) best = response
            }
            x += TRACK_FEATURE_GRID
        }
        y += TRACK_FEATURE_GRID
    }
    if (best <= 1e-9f) return emptyList()
    val threshold = best * 0.01f
    candidates.sortWith(compareByDescending<TrackFeatureCandidate> { it.response })
    val selected = ArrayList<TrackedPoint>(TRACK_MAX_FEATURES)
    val minDistanceSquared = TRACK_FEATURE_MIN_DISTANCE * TRACK_FEATURE_MIN_DISTANCE
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
            if (selected.size >= TRACK_MAX_FEATURES) break
        }
    }
    return selected
}

private fun trackLk(
    previous: List<TrackGrayFrame>,
    next: List<TrackGrayFrame>,
    point: TrackedPoint,
): TrackedPoint? {
    val levels = min(previous.size, next.size)
    var dx = 0f
    var dy = 0f
    for (level in levels - 1 downTo 0) {
        val scale = (1 shl level).toFloat()
        val a = previous[level]
        val b = next[level]
        val px = point.x / scale
        val py = point.y / scale
        val size = TRACK_LK_WINDOW * 2 + 1
        val template = FloatArray(size * size)
        val gradientX = FloatArray(size * size)
        val gradientY = FloatArray(size * size)
        var gxx = 0f
        var gxy = 0f
        var gyy = 0f
        var index = 0
        for (wy in -TRACK_LK_WINDOW..TRACK_LK_WINDOW) {
            for (wx in -TRACK_LK_WINDOW..TRACK_LK_WINDOW) {
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
        while (iteration < TRACK_LK_ITERATIONS) {
            var bx = 0f
            var by = 0f
            var k = 0
            for (wy in -TRACK_LK_WINDOW..TRACK_LK_WINDOW) {
                for (wx in -TRACK_LK_WINDOW..TRACK_LK_WINDOW) {
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
    return TrackedPoint(qx, qy)
}

private fun trackFrameStep(
    previous: TrackPyramid,
    next: TrackPyramid,
    sourceWidth: Int,
    sourceHeight: Int,
    region: TrackRegion,
): TrackSimilarityTransform? {
    val features = trackDetectFeatures(previous.levels[0], region)
    if (features.size < 3) return null
    val scaleX = sourceWidth.toDouble() / previous.levels[0].width
    val scaleY = sourceHeight.toDouble() / previous.levels[0].height
    val src = ArrayList<TrackedPoint>(features.size)
    val dst = ArrayList<TrackedPoint>(features.size)
    for (feature in features) {
        val tracked = trackLk(previous.levels, next.levels, feature) ?: continue
        val back = trackLk(next.levels, previous.levels, tracked) ?: continue
        val ex = back.x - feature.x
        val ey = back.y - feature.y
        if (ex * ex + ey * ey >= TRACK_FB_MAX * TRACK_FB_MAX) continue
        src += TrackedPoint((feature.x * scaleX).toFloat(), (feature.y * scaleY).toFloat())
        dst += TrackedPoint((tracked.x * scaleX).toFloat(), (tracked.y * scaleY).toFloat())
    }
    if (src.size < 3) return null
    return trackFitRobust(src, dst)
}

private fun trackFitSimilarity(src: List<TrackedPoint>, dst: List<TrackedPoint>): TrackSimilarityTransform? {
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
    return TrackSimilarityTransform(a, b, tx, ty)
}

private class TrackXorShift(seed: Long) {
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

private fun trackDistanceSquared(a: TrackedPoint, b: TrackedPoint): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    return dx * dx + dy * dy
}

private fun trackInliers(
    transform: TrackSimilarityTransform,
    src: List<TrackedPoint>,
    dst: List<TrackedPoint>,
): IntArray {
    val result = ArrayList<Int>(src.size)
    for (i in src.indices) {
        val x = transform.a * src[i].x - transform.b * src[i].y + transform.tx
        val y = transform.b * src[i].x + transform.a * src[i].y + transform.ty
        val dx = x - dst[i].x
        val dy = y - dst[i].y
        if (dx * dx + dy * dy < TRACK_RANSAC_THRESHOLD * TRACK_RANSAC_THRESHOLD) {
            result += i
        }
    }
    return result.toIntArray()
}

private fun trackFitRobust(src: List<TrackedPoint>, dst: List<TrackedPoint>): TrackSimilarityTransform? {
    val n = src.size
    if (n < 3 || dst.size != n) return null
    val random = TrackXorShift(0x9E3779B97F4A7C15uL.toLong() xor n.toLong())
    var best = IntArray(0)
    for (iteration in 0 until TRACK_RANSAC_ITERATIONS) {
        val i = random.below(n)
        var j = random.below(n)
        var guard = 0
        while ((j == i || trackDistanceSquared(src[j], src[i]) < 16.0) && guard < 16) {
            j = random.below(n)
            guard++
        }
        if (j == i) continue
        val sampleSrc = listOf(src[i], src[j])
        val sampleDst = listOf(dst[i], dst[j])
        val model = trackFitSimilarity(sampleSrc, sampleDst) ?: continue
        val found = trackInliers(model, src, dst)
        if (found.size > best.size) {
            best = found
            if (best.size == n) break
        }
    }
    if (best.size < 3) return null
    for (round in 0..1) {
        val inlierSrc = best.map { src[it] }
        val inlierDst = best.map { dst[it] }
        val model = trackFitSimilarity(inlierSrc, inlierDst) ?: return null
        val found = trackInliers(model, src, dst)
        if (found.size < best.size) break
        best = found
    }
    val transform = trackFitSimilarity(best.map { src[it] }, best.map { dst[it] }) ?: return null
    return if (trackInliers(transform, src, dst).size >= 3) transform else null
}
