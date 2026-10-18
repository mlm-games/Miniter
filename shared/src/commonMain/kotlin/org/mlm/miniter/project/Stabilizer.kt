package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.engine.ImageData
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

const val STABILIZE_SAMPLE_STEP_MS = 100L
const val STABILIZE_MAX_SAMPLES = 600L
const val STABILIZE_SMOOTH_WINDOW_MS = 500L

private const val STABILIZE_MAX_ZOOM = 1.3

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
