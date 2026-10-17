package org.mlm.miniter.engine

import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.miniter.editor.model.*
import org.mlm.miniter.platform.PlatformFileSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object FrameFilterProcessors {
    private const val GRAY_R = 0.299f
    private const val GRAY_G = 0.587f
    private const val GRAY_B = 0.114f

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    private inline fun eachPixelInLinear(
        pixels: ByteArray,
        total: Int,
        body: (FloatArray) -> Unit,
    ) {
        val rgb = FloatArray(3)
        for (i in 0 until total) {
            val b = i * 4
            for (c in 0 until 3) {
                rgb[c] = srgbToLinear(pixels[b + c].toInt().and(0xFF) / 255f)
            }
            body(rgb)
            for (c in 0 until 3) {
                pixels[b + c] = (linearToSrgb(rgb[c]) * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    private inline fun eachPixelInLinearXY(
        pixels: ByteArray,
        width: Int,
        height: Int,
        body: (Int, Int, FloatArray) -> Unit,
    ) {
        val rgb = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val b = (y * width + x) * 4
                for (c in 0 until 3) {
                    rgb[c] = srgbToLinear(pixels[b + c].toInt().and(0xFF) / 255f)
                }
                body(x, y, rgb)
                for (c in 0 until 3) {
                    pixels[b + c] = (linearToSrgb(rgb[c]) * 255f).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
    }

    suspend fun applyFilters(
        src: ByteArray,
        width: Int,
        height: Int,
        filters: List<RustVideoFilterSnapshot>,
        opacity: Float,
    ): ByteArray {
        if (width <= 0 || height <= 0 || src.size < width * height * 4) return src
        var pixels = src.copyOf()
        val total = width * height

        for (filter in filters) {
            when (filter) {
                is RustBrightnessFilterSnapshot -> {
                    val offset = filter.value / 100f
                    eachPixelInLinear(pixels, total) { rgb ->
                        for (c in 0 until 3) rgb[c] = (rgb[c] + offset).coerceIn(0f, 1f)
                    }
                }
                is RustContrastFilterSnapshot -> {
                    val factor = filter.value
                    eachPixelInLinear(pixels, total) { rgb ->
                        for (c in 0 until 3) {
                            rgb[c] = ((rgb[c] - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
                        }
                    }
                }
                is RustSaturationFilterSnapshot -> {
                    val factor = filter.value
                    eachPixelInLinear(pixels, total) { rgb ->
                        val gray = GRAY_R * rgb[0] + GRAY_G * rgb[1] + GRAY_B * rgb[2]
                        for (c in 0 until 3) {
                            rgb[c] = (gray + (rgb[c] - gray) * factor).coerceIn(0f, 1f)
                        }
                    }
                }
                RustGrayscaleFilterSnapshot -> {
                    for (i in 0 until total) {
                        val b = i * 4
                        val r = pixels[b].toInt().and(0xFF).toFloat()
                        val g = pixels[b + 1].toInt().and(0xFF).toFloat()
                        val bl = pixels[b + 2].toInt().and(0xFF).toFloat()
                        val gray = (GRAY_R * r + GRAY_G * g + GRAY_B * bl).toInt().coerceIn(0, 255).toByte()
                        pixels[b] = gray
                        pixels[b + 1] = gray
                        pixels[b + 2] = gray
                    }
                }
                RustSepiaFilterSnapshot -> {
                    for (i in 0 until total) {
                        val b = i * 4
                        val r = pixels[b].toInt().and(0xFF).toFloat()
                        val g = pixels[b + 1].toInt().and(0xFF).toFloat()
                        val bl = pixels[b + 2].toInt().and(0xFF).toFloat()
                        pixels[b] = (0.393f * r + 0.769f * g + 0.189f * bl).toInt().coerceIn(0, 255).toByte()
                        pixels[b + 1] = (0.349f * r + 0.686f * g + 0.168f * bl).toInt().coerceIn(0, 255).toByte()
                        pixels[b + 2] = (0.272f * r + 0.534f * g + 0.131f * bl).toInt().coerceIn(0, 255).toByte()
                    }
                }
                is RustBlurFilterSnapshot -> {
                    val radius = filter.radius.coerceAtLeast(1f)
                    pixels = when (filter.kind) {
                        RustBlurKind.Stack, RustBlurKind.Box ->
                            applyBoxBlur(pixels, width, height, radius.toInt())
                        RustBlurKind.Gaussian ->
                            applyGaussianBlur(pixels, width, height, radius)
                        RustBlurKind.Motion ->
                            applyMotionBlur(pixels, width, height, radius, filter.angle)
                        RustBlurKind.Radial ->
                            applyRadialBlur(pixels, width, height, radius)
                        RustBlurKind.Zoom ->
                            applyZoomBlur(pixels, width, height, radius)
                    }
                }
                is RustSharpenFilterSnapshot -> {
                    val amount = filter.amount.coerceAtLeast(0f)
                    if (amount > 0f) {
                        pixels = applySharpen(pixels, width, height, amount)
                    }
                }
                is RustHueFilterSnapshot -> {
                    val angle = (filter.degrees % 360f * PI.toFloat() / 180f)
                    val cosA = cos(angle.toDouble()).toFloat()
                    val sinA = sin(angle.toDouble()).toFloat()
                    eachPixelInLinear(pixels, total) { rgb ->
                        val r = rgb[0]
                        val g = rgb[1]
                        val bl = rgb[2]
                        val nr = (0.299f + 0.701f * cosA + 0.168f * sinA) * r +
                                (0.587f - 0.587f * cosA + 0.330f * sinA) * g +
                                (0.114f - 0.114f * cosA - 0.497f * sinA) * bl
                        val ng = (0.299f - 0.299f * cosA - 0.328f * sinA) * r +
                                (0.587f + 0.413f * cosA + 0.035f * sinA) * g +
                                (0.114f - 0.114f * cosA + 0.292f * sinA) * bl
                        val nb = (0.299f - 0.300f * cosA + 1.250f * sinA) * r +
                                (0.587f - 0.588f * cosA - 1.050f * sinA) * g +
                                (0.114f + 0.886f * cosA - 0.203f * sinA) * bl
                        rgb[0] = nr.coerceIn(0f, 1f)
                        rgb[1] = ng.coerceIn(0f, 1f)
                        rgb[2] = nb.coerceIn(0f, 1f)
                    }
                }
                is RustColorGradeFilterSnapshot -> {
                    val identity = filter.brightness == 0f && filter.contrast == 1f &&
                        filter.saturation == 1f && filter.vibrance == 0f &&
                        filter.temperature == 0f && filter.tint == 0f &&
                        filter.exposure == 0f && filter.gamma == 1f &&
                        filter.lift == 0f && filter.gain == 1f &&
                        filter.highlights == 0f && filter.shadows == 0f
                    if (!identity) {
                        val ev = 2f.pow(filter.exposure)
                        val rGain = ev * (1f + 0.3f * filter.temperature)
                        val gGain = ev * (1f + 0.3f * filter.tint)
                        val bGain = ev * (1f - 0.3f * filter.temperature)
                        val invGamma = if (filter.gamma > 0f) 1f / filter.gamma else 1f
                        val toneMap = filter.highlights != 0f || filter.shadows != 0f
                        val saturate = filter.saturation != 1f || filter.vibrance != 0f
                        eachPixelInLinear(pixels, total) { rgb ->
                            var r = rgb[0] * rGain * filter.gain + filter.lift
                            var g = rgb[1] * gGain * filter.gain + filter.lift
                            var bl = rgb[2] * bGain * filter.gain + filter.lift
                            if (toneMap) {
                                val l = (0.2126f * r + 0.7152f * g + 0.0722f * bl).coerceIn(0f, 1f)
                                val adjust = filter.highlights * l * l +
                                    filter.shadows * (1f - l) * (1f - l)
                                r += adjust
                                g += adjust
                                bl += adjust
                            }
                            r = ((r + filter.brightness) - 0.5f) * filter.contrast + 0.5f
                            g = ((g + filter.brightness) - 0.5f) * filter.contrast + 0.5f
                            bl = ((bl + filter.brightness) - 0.5f) * filter.contrast + 0.5f
                            r = r.coerceAtLeast(0f).pow(invGamma)
                            g = g.coerceAtLeast(0f).pow(invGamma)
                            bl = bl.coerceAtLeast(0f).pow(invGamma)
                            if (saturate) {
                                val l = 0.2126f * r + 0.7152f * g + 0.0722f * bl
                                val mx = maxOf(r, g, bl)
                                val mn = minOf(r, g, bl)
                                val current = if (mx > 0f) (mx - mn) / mx else 0f
                                val s = (filter.saturation + filter.vibrance * (1f - current))
                                    .coerceAtLeast(0f)
                                r = l + (r - l) * s
                                g = l + (g - l) * s
                                bl = l + (bl - l) * s
                            }
                            rgb[0] = r.coerceIn(0f, 1f)
                            rgb[1] = g.coerceIn(0f, 1f)
                            rgb[2] = bl.coerceIn(0f, 1f)
                        }
                    }
                }
                is RustChromaKeyFilterSnapshot -> {
                    val s = filter.keyColor.trim().removePrefix("#")
                    val packed = s.toLongOrNull(16)
                    if (packed != null && (s.length == 6 || s.length == 8)) {
                        val kr = (packed shr 16 and 0xFF) / 255f
                        val kg = (packed shr 8 and 0xFF) / 255f
                        val kb = (packed and 0xFF) / 255f
                        val ky = 0.299f * kr + 0.587f * kg + 0.114f * kb
                        val kCb = 0.564f * (kb - ky)
                        val kCr = 0.713f * (kr - ky)
                        val tol = filter.tolerance.coerceIn(0f, 1f)
                        val soft = filter.softness.coerceIn(1f / 255f, 1f)
                        val spill = filter.spill.coerceIn(0f, 1f)
                        for (i in 0 until total) {
                            val b = i * 4
                            val r = pixels[b].toInt().and(0xFF) / 255f
                            val g = pixels[b + 1].toInt().and(0xFF) / 255f
                            val bl = pixels[b + 2].toInt().and(0xFF) / 255f
                            val y = 0.299f * r + 0.587f * g + 0.114f * bl
                            val dcb = 0.564f * (bl - y) - kCb
                            val dcr = 0.713f * (r - y) - kCr
                            val matte = ((sqrt(dcb * dcb + dcr * dcr) - tol) / soft).coerceIn(0f, 1f)
                            if (matte >= 1f) continue
                            val a = pixels[b + 3].toInt().and(0xFF) / 255f
                            pixels[b + 3] = (a * matte * 255f).toInt().coerceIn(0, 255).toByte()
                            if (spill > 0f) {
                                val sp = spill * (1f - matte)
                                pixels[b] = ((r + (y - r) * sp).coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
                                pixels[b + 1] = ((g + (y - g) * sp).coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
                                pixels[b + 2] = ((bl + (y - bl) * sp).coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
                            }
                        }
                    }
                }
                is RustLutFilterSnapshot -> {
                    if (filter.path.isNotEmpty()) {
                        val lut = loadCubeLut(filter.path)
                        if (lut != null) {
                            applyCubeLut(pixels, total, lut, filter.strength)
                        }
                    }
                }
                is RustMosaicFilterSnapshot -> {
                    pixels = applyMosaic(pixels, width, height, filter.kind, filter.size)
                }
                is RustVignetteFilterSnapshot -> {
                    val amount = filter.amount.coerceIn(0f, 1f)
                    if (amount > 0f) {
                        val cx = (width / 2f).coerceAtLeast(1f)
                        val cy = (height / 2f).coerceAtLeast(1f)
                        val radius = filter.radius.coerceIn(0f, 1.5f)
                        val softness = filter.softness.coerceIn(0.001f, 2f)
                        val invDiagonal = 1f / sqrt(2f)
                        eachPixelInLinearXY(pixels, width, height) { x, y, rgb ->
                            val dx = (x + 0.5f - cx) / cx
                            val dy = (y + 0.5f - cy) / cy
                            val distance = sqrt(dx * dx + dy * dy) * invDiagonal
                            val falloff = ((distance - radius) / softness).coerceIn(0f, 1f)
                            if (falloff > 0f) {
                                val factor = 1f - amount * falloff
                                rgb[0] *= factor
                                rgb[1] *= factor
                                rgb[2] *= factor
                            }
                        }
                    }
                }
                is RustPosterizeFilterSnapshot -> {
                    val steps = filter.levels.roundToInt().coerceIn(2, 64) - 1
                    if (steps > 0) {
                        for (i in 0 until total) {
                            val b = i * 4
                            for (c in 0 until 3) {
                                val value = pixels[b + c].toInt().and(0xFF) / 255f
                                pixels[b + c] = ((value * steps).roundToInt().toFloat() / steps * 255f)
                                    .roundToInt().coerceIn(0, 255).toByte()
                            }
                        }
                    }
                }
                is RustDuotoneFilterSnapshot -> {
                    val dark = parseArgbRgb(filter.dark) ?: intArrayOf(0, 0, 0)
                    val light = parseArgbRgb(filter.light) ?: intArrayOf(255, 255, 255)
                    for (i in 0 until total) {
                        val b = i * 4
                        val luma = (GRAY_R * pixels[b].toInt().and(0xFF) +
                            GRAY_G * pixels[b + 1].toInt().and(0xFF) +
                            GRAY_B * pixels[b + 2].toInt().and(0xFF)) / 255f
                        for (c in 0 until 3) {
                            val from = dark[c].toFloat()
                            pixels[b + c] = (from + (light[c] - from) * luma)
                                .roundToInt().coerceIn(0, 255).toByte()
                        }
                    }
                }
                is RustGrainFilterSnapshot -> {
                    val amount = filter.amount.coerceIn(0f, 1f)
                    if (amount > 0f) {
                        val cell = filter.size.coerceIn(1f, 64f)
                        for (y in 0 until height) {
                            val cy = (y / cell).toInt()
                            for (x in 0 until width) {
                                val noise = grainNoise((x / cell).toInt(), cy)
                                val offset = (noise - 0.5f) * 2f * amount * 255f
                                val b = (y * width + x) * 4
                                for (c in 0 until 3) {
                                    pixels[b + c] = (pixels[b + c].toInt().and(0xFF) + offset)
                                        .roundToInt().coerceIn(0, 255).toByte()
                                }
                            }
                        }
                    }
                }
                is RustFlipFilterSnapshot -> {
                    if (filter.horizontal || filter.vertical) {
                        val tmp = pixels.copyOf()
                        val stride = width * 4
                        for (row in 0 until height) {
                            val srcRow = if (filter.vertical) height - 1 - row else row
                            for (col in 0 until width) {
                                val srcCol = if (filter.horizontal) width - 1 - col else col
                                val di = row * stride + col * 4
                                val si = srcRow * stride + srcCol * 4
                                pixels[di] = tmp[si]; pixels[di + 1] = tmp[si + 1]
                                pixels[di + 2] = tmp[si + 2]; pixels[di + 3] = tmp[si + 3]
                            }
                        }
                    }
                }
                is RustRotateFilterSnapshot -> {
                    if (abs(filter.degrees) >= 0.5f) {
                        pixels = applyRotateRgba(pixels, width, height, filter.degrees)
                    }
                }
                is RustTransformFilterSnapshot -> {
                    if (abs(filter.scale - 1f) >= 1e-6f || abs(filter.translateX) >= 1e-6f ||
                        abs(filter.translateY) >= 1e-6f || abs(filter.rotate) >= 1e-6f) {
                        pixels = applyTransformRgba(pixels, width, height, filter.scale, filter.translateX, filter.translateY, filter.rotate)
                    }
                }
                is RustCropFilterSnapshot -> {
                    pixels = applyCropRgba(pixels, width, height, filter.left, filter.top, filter.right, filter.bottom)
                }
                RustReverseVideoFilterSnapshot,
                is RustSpeedFilterSnapshot,
                is RustCanvasBackgroundFilterSnapshot,
                is RustOpacityFilterSnapshot,
                is RustBlendModeFilterSnapshot -> { }
            }
        }

        if (opacity < 1f) {
            val op = (opacity * 255f).toInt().coerceIn(0, 255)
            for (i in 0 until total) {
                val b = i * 4 + 3
                val origA = pixels[b].toInt().and(0xFF)
                pixels[b] = (origA * op / 255).coerceIn(0, 255).toByte()
            }
        }

        return pixels
    }

    fun applyRotateRgba(src: ByteArray, w: Int, h: Int, deg: Float): ByteArray {
        if (w <= 0 || h <= 0 || src.size < w * h * 4) return src
        val rad = (deg.toDouble() * PI / 180.0).toFloat()
        val sin = sin(rad.toDouble()).toFloat()
        val cos = cos(rad.toDouble()).toFloat()
        val dst = ByteArray(src.size)
        val cx = w / 2f
        val cy = h / 2f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = (x - cx) * cos + (y - cy) * sin
                val py = -(x - cx) * sin + (y - cy) * cos
                val sx = (px + cx).toInt().coerceIn(0, w - 1)
                val sy = (py + cy).toInt().coerceIn(0, h - 1)
                val si = sy * w * 4 + sx * 4
                val di = y * w * 4 + x * 4
                dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
            }
        }
        return dst
    }

    fun applyTransformRgba(src: ByteArray, w: Int, h: Int, scale: Float, tx: Float, ty: Float, rotate: Float): ByteArray {
        if (w <= 0 || h <= 0 || src.size < w * h * 4) return src
        val zoom = scale.coerceIn(0.05f, 50f)
        val rad = (rotate.toDouble() * PI / 180.0).toFloat()
        val cosR = cos(rad.toDouble()).toFloat()
        val sinR = sin(rad.toDouble()).toFloat()
        val dst = ByteArray(src.size)
        val cx = 0.5f
        val cy = 0.5f

        for (yd in 0 until h) {
            for (xd in 0 until w) {
                var u = (xd.toFloat() + 0.5f) / w
                var v = (yd.toFloat() + 0.5f) / h
                val px = (u - cx) * w
                val py = (v - cy) * h
                val rx = px * cosR + py * sinR
                val ry = -px * sinR + py * cosR
                u = rx / w + cx
                v = ry / h + cy
                u = u - 0.5f - tx
                v = v - 0.5f - ty
                u = u / zoom
                v = v / zoom
                u += 0.5f
                v += 0.5f
                val sx = (u * w - 0.5f).toInt().coerceIn(0, w - 1)
                val sy = (v * h - 0.5f).toInt().coerceIn(0, h - 1)
                val si = sy * w * 4 + sx * 4
                val di = yd * w * 4 + xd * 4
                dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
            }
        }
        return dst
    }

    private fun hexCell(x: Float, y: Float, r: Float): Pair<Int, Int> {
        val cx = (0.57735027f * x - (1f / 3f) * y) / r
        val cz = ((2f / 3f) * y) / r
        val cy = -cx - cz
        val rx = cx.roundToInt()
        val ry = cy.roundToInt()
        val rz = cz.roundToInt()
        val dx = abs(rx - cx)
        val dy = abs(ry - cy)
        val dz = abs(rz - cz)
        return when {
            dx > dy && dx > dz -> Pair(-ry - rz, rz)
            dy > dz -> Pair(rx, rz)
            else -> Pair(rx, -rx - ry)
        }
    }

    private fun applyMosaic(
        src: ByteArray,
        w: Int,
        h: Int,
        kind: RustMosaicKind,
        size: Float,
    ): ByteArray {
        val s = size.coerceAtLeast(1f)
        val total = w * h
        val index = HashMap<Long, Int>()
        val sums = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        val owner = IntArray(total)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val cell = when (kind) {
                    RustMosaicKind.Square -> Pair((x / s).toInt(), (y / s).toInt())
                    RustMosaicKind.Hexagon -> hexCell(x.toFloat(), y.toFloat(), s)
                    RustMosaicKind.Triangle -> {
                        val gx = (x / s).toInt()
                        val gy = (y / s).toInt()
                        val fx = x - gx * s
                        val fy = y - gy * s
                        Pair(gx * 2 + if (fx + fy < s) 0 else 1, gy)
                    }
                }
                val key = (cell.first.toLong() shl 32) xor (cell.second.toLong() and 0xFFFFFFFFL)
                val idx = index.getOrPut(key) {
                    sums.add(FloatArray(4))
                    counts.add(0)
                    sums.size - 1
                }
                val p = (y * w + x) * 4
                for (c in 0 until 4) {
                    sums[idx][c] += src[p + c].toInt().and(0xFF)
                }
                counts[idx]++
                owner[y * w + x] = idx
            }
        }

        val out = src.copyOf()
        for (i in 0 until total) {
            val c = owner[i]
            val n = counts[c].coerceAtLeast(1)
            val p = i * 4
            for (ch in 0 until 4) {
                out[p + ch] = (sums[c][ch] / n).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private fun blurTaps(radius: Float): Int =
        (radius * 3f).roundToInt().coerceIn(4, 64)

    private fun bilinear(src: ByteArray, w: Int, h: Int, x: Float, y: Float): FloatArray {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = cx - x0
        val fy = cy - y0
        val out = FloatArray(4)
        for (c in 0 until 4) {
            val p00 = src[(y0 * w + x0) * 4 + c].toInt().and(0xFF).toFloat()
            val p10 = src[(y0 * w + x1) * 4 + c].toInt().and(0xFF).toFloat()
            val p01 = src[(y1 * w + x0) * 4 + c].toInt().and(0xFF).toFloat()
            val p11 = src[(y1 * w + x1) * 4 + c].toInt().and(0xFF).toFloat()
            val top = p00 + (p10 - p00) * fx
            val bottom = p01 + (p11 - p01) * fx
            out[c] = top + (bottom - top) * fy
        }
        return out
    }

    private fun accumulateTaps(
        src: ByteArray,
        w: Int,
        h: Int,
        taps: Int,
        tap: (Float, Float, Float) -> Pair<Float, Float>,
    ): ByteArray {
        val out = src.copyOf()
        val inv = 1f / taps.coerceAtLeast(1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val acc = FloatArray(4)
                for (t in 0 until taps) {
                    val f = if (taps > 1) t.toFloat() / (taps - 1) else 0f
                    val (sx, sy) = tap(x.toFloat(), y.toFloat(), f)
                    val s = bilinear(src, w, h, sx, sy)
                    for (c in 0 until 4) acc[c] += s[c]
                }
                val p = (y * w + x) * 4
                for (c in 0 until 4) {
                    out[p + c] = (acc[c] * inv).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    private fun applyMotionBlur(src: ByteArray, w: Int, h: Int, radius: Float, angleDeg: Float): ByteArray {
        val taps = blurTaps(radius)
        val reach = radius.coerceAtLeast(1f)
        val angle = angleDeg * PI.toFloat() / 180f
        val dx = cos(angle) * reach
        val dy = sin(angle) * reach
        return accumulateTaps(src, w, h, taps) { x, y, t ->
            val o = t - 0.5f
            Pair(x + dx * o * 2f, y + dy * o * 2f)
        }
    }

    private fun applyRadialBlur(src: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
        val taps = blurTaps(radius)
        val cx = w / 2f
        val cy = h / 2f
        val sweep = radius * 2f * PI.toFloat() / 180f
        return accumulateTaps(src, w, h, taps) { x, y, t ->
            val a = (t - 0.5f) * sweep
            val s = sin(a)
            val c = cos(a)
            val px = x - cx
            val py = y - cy
            Pair(cx + px * c - py * s, cy + px * s + py * c)
        }
    }

    private fun applyZoomBlur(src: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
        val taps = blurTaps(radius)
        val cx = w / 2f
        val cy = h / 2f
        val spread = (radius * 0.02f).coerceIn(0f, 0.9f)
        return accumulateTaps(src, w, h, taps) { x, y, t ->
            val k = 1f + spread * t
            Pair(cx + (x - cx) * k, cy + (y - cy) * k)
        }
    }

    private fun gaussianKernel(sigma: Float): FloatArray {
        val s = sigma.coerceAtLeast(0.1f)
        val r = ceil(s * 3f).toInt().coerceAtLeast(1)
        val kernel = FloatArray(r * 2 + 1)
        var sum = 0f
        for (i in kernel.indices) {
            val d = (i - r).toFloat()
            val v = exp(-(d * d) / (2f * s * s))
            kernel[i] = v
            sum += v
        }
        if (sum > 0f) {
            for (i in kernel.indices) kernel[i] /= sum
        }
        return kernel
    }

    private fun weightedPass(
        src: ByteArray,
        w: Int,
        h: Int,
        kernel: FloatArray,
        vertical: Boolean,
    ): ByteArray {
        val out = ByteArray(src.size)
        val r = kernel.size / 2
        val len = if (vertical) h else w
        val lines = if (vertical) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                val acc = FloatArray(4)
                for (ki in kernel.indices) {
                    val j = (i + ki - r).coerceIn(0, len - 1)
                    val x = if (vertical) line else j
                    val y = if (vertical) j else line
                    val p = (y * w + x) * 4
                    for (c in 0 until 4) acc[c] += src[p + c].toInt().and(0xFF) * kernel[ki]
                }
                val x = if (vertical) line else i
                val y = if (vertical) i else line
                val p = (y * w + x) * 4
                for (c in 0 until 4) {
                    out[p + c] = acc[c].roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    private fun applyGaussianBlur(src: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
        val kernel = gaussianKernel(radius / 3f)
        val horizontal = weightedPass(src, w, h, kernel, false)
        return weightedPass(horizontal, w, h, kernel, true)
    }

    private fun parseArgbRgb(hex: String): IntArray? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6 && s.length != 8) return null
        val packed = s.toLongOrNull(16) ?: return null
        return intArrayOf(
            (packed shr 16 and 0xFF).toInt(),
            (packed shr 8 and 0xFF).toInt(),
            (packed and 0xFF).toInt(),
        )
    }

    private fun grainNoise(cellX: Int, cellY: Int): Float {
        var hash = cellX * 0x9E3779B1.toInt() xor (cellY * 0x85EBCA77.toInt())
        hash = hash xor (hash ushr 15)
        hash *= 0x2545F491
        hash = hash xor (hash ushr 13)
        return (hash and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
    }

    private fun applyBoxBlur(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (w == 0 || h == 0 || radius <= 0) return src
        val size = radius * 2 + 1
        val temp = ByteArray(src.size)
        val dst = ByteArray(src.size)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var a = 0
                var count = 0
                for (kx in -radius..radius) {
                    val sx = (x + kx).coerceIn(0, w - 1)
                    val si = (y * w + sx) * 4
                    r += src[si].toInt().and(0xFF)
                    g += src[si + 1].toInt().and(0xFF)
                    b += src[si + 2].toInt().and(0xFF)
                    a += src[si + 3].toInt().and(0xFF)
                    count++
                }
                val di = (y * w + x) * 4
                temp[di] = (r / count).toByte()
                temp[di + 1] = (g / count).toByte()
                temp[di + 2] = (b / count).toByte()
                temp[di + 3] = (a / count).toByte()
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var a = 0
                var count = 0
                for (ky in -radius..radius) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    val si = (sy * w + x) * 4
                    r += temp[si].toInt().and(0xFF)
                    g += temp[si + 1].toInt().and(0xFF)
                    b += temp[si + 2].toInt().and(0xFF)
                    a += temp[si + 3].toInt().and(0xFF)
                    count++
                }
                val di = (y * w + x) * 4
                dst[di] = (r / count).toByte()
                dst[di + 1] = (g / count).toByte()
                dst[di + 2] = (b / count).toByte()
                dst[di + 3] = (a / count).toByte()
            }
        }
        return dst
    }

    private fun applySharpen(src: ByteArray, w: Int, h: Int, amount: Float): ByteArray {
        if (w < 3 || h < 3 || amount <= 0f) return src
        val dst = src.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val di = (y * w + x) * 4
                for (c in 0..2) {
                    val center = src[di + c].toInt().and(0xFF)
                    var sum = 0
                    for (ky in -1..1) {
                        for (kx in -1..1) {
                            if (ky == 0 && kx == 0) continue
                            val si = ((y + ky) * w + (x + kx)) * 4 + c
                            sum += src[si].toInt().and(0xFF)
                        }
                    }
                    val neighbors = sum / 8
                    val result = (center + ((center - neighbors) * amount).toInt()).coerceIn(0, 255)
                    dst[di + c] = result.toByte()
                }
            }
        }
        return dst
    }

    private fun applyCropRgba(src: ByteArray, w: Int, h: Int, left: Float, top: Float, right: Float, bottom: Float): ByteArray {
        if (w <= 0 || h <= 0 || src.size < w * h * 4) return src
        val l = (left.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w)
        val t = (top.coerceIn(0f, 1f) * h).toInt().coerceIn(0, h)
        val r = (w - (right.coerceIn(0f, 1f) * w).toInt()).coerceIn(0, w)
        val b = (h - (bottom.coerceIn(0f, 1f) * h).toInt()).coerceIn(0, h)
        if (r <= l || b <= t) return src

        val cw = r - l
        val ch = b - t
        val dst = ByteArray(w * h * 4) // zero-filled = transparent black
        val offX = (w - cw) / 2
        val offY = (h - ch) / 2
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val si = (y + t) * w * 4 + (x + l) * 4
                val di = (y + offY) * w * 4 + (x + offX) * 4
                dst[di] = src[si]; dst[di + 1] = src[si + 1]; dst[di + 2] = src[si + 2]; dst[di + 3] = src[si + 3]
            }
        }
        return dst
    }
}

private const val MAX_LUT_1D_SIZE = 65_536
private const val MAX_LUT_3D_SIZE = 256
private const val LUT_VALUE_TOLERANCE = 1e-3f

enum class CubeLutKind { OneD, ThreeD }

class CubeLut internal constructor(
    val kind: CubeLutKind,
    val size: Int,
    internal val domainMin: FloatArray,
    internal val domainMax: FloatArray,
    internal val entries: FloatArray,
)

private val lutMutex = Mutex()
private val lutCache = HashMap<String, CubeLut?>()

fun parseCubeLut(text: String): Result<CubeLut> = runCatching {
    var kind: CubeLutKind? = null
    var size = 0
    val domainMin = floatArrayOf(0f, 0f, 0f)
    val domainMax = floatArrayOf(1f, 1f, 1f)
    var entries = FloatArray(3 * 1024)
    var count = 0

    var line = 0
    for (raw in text.lineSequence()) {
        line++
        val content = raw.substringBefore('#').trim()
        if (content.isEmpty()) continue
        val tokens = content.split(' ', '\t', '\r', '\u000B', '\u000C').filter { it.isNotEmpty() }
        val first = tokens.first()

        if (looksNumeric(first)) {
            val entry = parseCubeLutEntry(tokens, line)
            if ((count + 1) * 3 > entries.size) entries = entries.copyOf(entries.size * 2)
            entry.copyInto(entries, count * 3)
            count++
            continue
        }

        when (first.uppercase()) {
            "LUT_1D_SIZE" -> {
                val parsed = parseCubeLutSize(tokens.drop(1), MAX_LUT_1D_SIZE, line)
                if (kind != null) throw IllegalArgumentException("conflicting LUT size declarations")
                kind = CubeLutKind.OneD
                size = parsed
            }
            "LUT_3D_SIZE" -> {
                val parsed = parseCubeLutSize(tokens.drop(1), MAX_LUT_3D_SIZE, line)
                if (kind != null) throw IllegalArgumentException("conflicting LUT size declarations")
                kind = CubeLutKind.ThreeD
                size = parsed
            }
            "DOMAIN_MIN" -> parseCubeLutDomain(tokens.drop(1), line).copyInto(domainMin)
            "DOMAIN_MAX" -> parseCubeLutDomain(tokens.drop(1), line).copyInto(domainMax)
        }
    }

    val lutKind = kind ?: throw IllegalArgumentException("missing LUT_1D_SIZE or LUT_3D_SIZE")
    for (channel in 0 until 3) {
        if (domainMax[channel] <= domainMin[channel]) {
            throw IllegalArgumentException("invalid domain: maximum must exceed minimum")
        }
    }
    val expected = when (lutKind) {
        CubeLutKind.OneD -> size
        CubeLutKind.ThreeD -> size * size * size
    }
    if (count != expected) {
        throw IllegalArgumentException("LUT declares $expected entries but contains $count")
    }
    CubeLut(lutKind, size, domainMin, domainMax, entries.copyOf(count * 3))
}

private fun looksNumeric(token: String): Boolean =
    token.first().let { it in '0'..'9' || it == '-' || it == '+' || it == '.' }

private fun parseCubeLutSize(tokens: List<String>, max: Int, line: Int): Int {
    val size = tokens.singleOrNull()?.toIntOrNull()
        ?: throw IllegalArgumentException("line $line: expected one LUT size")
    if (size < 2 || size > max) {
        throw IllegalArgumentException("unsupported LUT size $size")
    }
    return size
}

private fun parseCubeLutDomain(tokens: List<String>, line: Int): FloatArray {
    if (tokens.size != 3) {
        throw IllegalArgumentException("line $line: expected three domain values")
    }
    val domain = FloatArray(3)
    for (channel in 0 until 3) {
        val value = tokens[channel].toFloatOrNull()
        if (value == null || !value.isFinite()) {
            throw IllegalArgumentException("line $line: value is not finite")
        }
        domain[channel] = value
    }
    return domain
}

private fun parseCubeLutEntry(tokens: List<String>, line: Int): FloatArray {
    if (tokens.size != 3) {
        throw IllegalArgumentException("line $line: expected three values")
    }
    val entry = FloatArray(3)
    for (channel in 0 until 3) {
        val value = tokens[channel].toFloatOrNull()
        if (value == null || !value.isFinite()) {
            throw IllegalArgumentException("line $line: value is not finite")
        }
        if (value < -LUT_VALUE_TOLERANCE || value > 1f + LUT_VALUE_TOLERANCE) {
            throw IllegalArgumentException("line $line: value is outside the 0..1 range")
        }
        entry[channel] = value.coerceIn(0f, 1f)
    }
    return entry
}

private suspend fun loadCubeLut(path: String): CubeLut? = lutMutex.withLock {
    lutCache[path]?.let { return@withLock it }
    if (lutCache.containsKey(path) && !PlatformFileSystem.exists(path)) return@withLock null

    val lut = try {
        parseCubeLut(PlatformFileSystem.readText(path)).getOrElse { error ->
            Napier.w("LUT file $path ignored: ${error.message}")
            null
        }
    } catch (error: Exception) {
        Napier.w("LUT file $path could not be read: ${error.message}")
        null
    }
    lutCache[path] = lut
    lut
}

private fun cubeLutAxis(value: Float, min: Float, max: Float, size: Int): Float {
    val span = max - min
    val normalized = if (span > 0f) (value - min) / span else value
    val last = (size - 1).toFloat()
    return (normalized * last).coerceIn(0f, last)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun sampleCubeLut1d(lut: CubeLut, size: Int, value: FloatArray, out: FloatArray) {
    for (channel in 0 until 3) {
        val axis = cubeLutAxis(value[channel], lut.domainMin[channel], lut.domainMax[channel], size)
        val low = floor(axis).toInt()
        val high = minOf(low + 1, size - 1)
        out[channel] = lerp(
            lut.entries[low * 3 + channel],
            lut.entries[high * 3 + channel],
            axis - low,
        )
    }
}

private fun sampleCubeLut3d(lut: CubeLut, size: Int, value: FloatArray, out: FloatArray) {
    val axis = FloatArray(3)
    for (channel in 0 until 3) {
        axis[channel] = cubeLutAxis(value[channel], lut.domainMin[channel], lut.domainMax[channel], size)
    }

    val r0 = floor(axis[0]).toInt()
    val g0 = floor(axis[1]).toInt()
    val b0 = floor(axis[2]).toInt()
    val r1 = minOf(r0 + 1, size - 1)
    val g1 = minOf(g0 + 1, size - 1)
    val b1 = minOf(b0 + 1, size - 1)
    val tr = axis[0] - r0
    val tg = axis[1] - g0
    val tb = axis[2] - b0

    for (channel in 0 until 3) {
        val c000 = lut.entries[(r0 + g0 * size + b0 * size * size) * 3 + channel]
        val c100 = lut.entries[(r1 + g0 * size + b0 * size * size) * 3 + channel]
        val c010 = lut.entries[(r0 + g1 * size + b0 * size * size) * 3 + channel]
        val c110 = lut.entries[(r1 + g1 * size + b0 * size * size) * 3 + channel]
        val c001 = lut.entries[(r0 + g0 * size + b1 * size * size) * 3 + channel]
        val c101 = lut.entries[(r1 + g0 * size + b1 * size * size) * 3 + channel]
        val c011 = lut.entries[(r0 + g1 * size + b1 * size * size) * 3 + channel]
        val c111 = lut.entries[(r1 + g1 * size + b1 * size * size) * 3 + channel]
        val c00 = lerp(c000, c100, tr)
        val c10 = lerp(c010, c110, tr)
        val c01 = lerp(c001, c101, tr)
        val c11 = lerp(c011, c111, tr)
        out[channel] = lerp(lerp(c00, c10, tg), lerp(c01, c11, tg), tb)
    }
}

private fun applyCubeLut(pixels: ByteArray, total: Int, lut: CubeLut, strength: Float) {
    val mix = strength.coerceIn(0f, 1f)
    if (mix <= 0f) return
    val original = FloatArray(3)
    val mapped = FloatArray(3)
    for (i in 0 until total) {
        val b = i * 4
        for (channel in 0 until 3) {
            original[channel] = pixels[b + channel].toInt().and(0xFF) / 255f
        }
        when (lut.kind) {
            CubeLutKind.OneD -> sampleCubeLut1d(lut, lut.size, original, mapped)
            CubeLutKind.ThreeD -> sampleCubeLut3d(lut, lut.size, original, mapped)
        }
        for (channel in 0 until 3) {
            val value = original[channel] + (mapped[channel] - original[channel]) * mix
            pixels[b + channel] = (value * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
    }
}
