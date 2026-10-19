package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustMaskEffect
import org.mlm.miniter.engine.ImageData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

const val MOTION_TRACK_SAMPLE_STEP_MS = 100L
const val MOTION_TRACK_MAX_SAMPLES = 600L
const val MOTION_TRACK_ROTATE_THRESHOLD_DEGREES = 0.5
const val MOTION_TRACK_SCALE_THRESHOLD = 0.01

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

private data class TrackedStep(val localOffsetUs: Long, val step: SimilarityTransform?)

private data class TrackKinematics(
    val sigma: Double,
    val angleDegrees: Double,
    val translateX: Double,
    val translateY: Double,
)

class MotionTrackerAnalysis(private val region: MaskBounds) {
    private val samples = ArrayList<TrackedStep>()
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
            trackStep(previousPyramid, pyramid, sourceWidth, sourceHeight, pixelRegion(frame))
        }
        if (step != null) trackedSteps++
        samples += TrackedStep(localOffsetUs, step)
        frameCount++
        previous = pyramid
    }

    fun motionSamples(): List<MotionTrackSample> {
        val result = ArrayList<MotionTrackSample>(samples.size)
        var cumulative = SimilarityTransform.IDENTITY
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
    maskIndex: Int,
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
    val aspect = canvasHeight.toDouble() / canvasWidth.toDouble()
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
    val translateXParam = KeyframeParams.maskParam(maskIndex, "translate_x")
    val translateYParam = KeyframeParams.maskParam(maskIndex, "translate_y")
    val rotateParam = KeyframeParams.maskParam(maskIndex, "rotate")
    val scaleParam = KeyframeParams.maskParam(maskIndex, "scale")
    val params = ArrayList<String>(4)
    params += translateXParam
    params += translateYParam
    if (bakeRotate) params += rotateParam
    if (bakeScale) params += scaleParam

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
        val displacementX = sigma * (0.5 * cos(motionTheta) - 0.5 * sin(motionTheta) * aspect) +
            sampleKinematics.translateX - 0.5
        val displacementY = sigma * (0.5 * sin(motionTheta) / aspect + 0.5 * cos(motionTheta)) +
            sampleKinematics.translateY - 0.5
        val translateX = sigma * baseTranslateX + cos(theta) * displacementX + sin(theta) * displacementY * aspect
        val translateY = sigma * baseTranslateY - sin(theta) * displacementX / aspect + cos(theta) * displacementY
        val scale = baseScale * sigma
        if (!scale.isFinite() || !angle.isFinite() || !translateX.isFinite() || !translateY.isFinite()) continue
        keyframes += RustKeyframe(
            translateXParam,
            sample.localOffsetUs,
            translateX.toFloat(),
            RustEasing.Linear,
        )
        keyframes += RustKeyframe(
            translateYParam,
            sample.localOffsetUs,
            translateY.toFloat(),
            RustEasing.Linear,
        )
        if (bakeRotate) {
            keyframes += RustKeyframe(
                rotateParam,
                sample.localOffsetUs,
                angle.toFloat(),
                RustEasing.Linear,
            )
        }
        if (bakeScale) {
            keyframes += RustKeyframe(
                scaleParam,
                sample.localOffsetUs,
                scale.toFloat(),
                RustEasing.Linear,
            )
        }
    }
    if (keyframes.isEmpty()) return null
    return MotionTrackBake(keyframes = keyframes, params = params.toList())
}
