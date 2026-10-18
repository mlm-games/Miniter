package org.mlm.miniter.engine

private const val MAX_LUT_1D_SIZE = 65_536
private const val MAX_LUT_3D_SIZE = 256
private const val LUT_VALUE_TOLERANCE = 1e-3f

enum class CubeLutKind { OneD, ThreeD }

class CubeLut internal constructor(
    val kind: CubeLutKind,
    val size: Int,
)

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
    CubeLut(lutKind, size)
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
