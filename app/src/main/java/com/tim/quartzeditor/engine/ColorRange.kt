package com.tim.quartzeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What Color Range picks out: a color you sample, a family of hues, a range of tones, or skin. */
enum class RangeKind(val label: String) {
    SAMPLED("Sampled color"), REDS("Reds"), YELLOWS("Yellows"), GREENS("Greens"), CYANS("Cyans"), BLUES("Blues"), MAGENTAS("Magentas"),
    HIGHLIGHTS("Highlights"), MIDTONES("Midtones"), SHADOWS("Shadows"), SKIN("Skin tones");

    /** Fuzziness only widens or narrows a sampled color. */
    val usesFuzziness: Boolean get() = this == SAMPLED
}

/**
 * Color Range (like Photoshop's): how much each pixel is selected (0..255), soft at the edges of
 * the range so the selection blends. Plain Kotlin, so it can be tested off the phone.
 */
object ColorRange {
    /**
     * Coverage for [px] (ARGB): [kind] (with [color] for a sampled color and [fuzziness] 0..200 for
     * how far from it still counts), see-through pixels never counted, [invert] the other way round.
     */
    fun coverage(px: IntArray, kind: RangeKind, color: Int, fuzziness: Float, invert: Boolean): ByteArray {
        val out = ByteArray(px.size)
        val cr = (color shr 16) and 0xFF
        val cg = (color shr 8) and 0xFF
        val cb = color and 0xFF
        // A sampled color: fully in up to a little past the fuzziness, fading out by twice it
        val lo = fuzziness * 0.75f
        val hi = fuzziness * 2f + 6f
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            var w = if (a == 0) 0f else when (kind) {
                RangeKind.SAMPLED -> {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    1f - smooth(lo, hi, distance(r, g, b, cr, cg, cb))
                }
                else -> rangeWeight(kind, c)
            } * (a / 255f)
            if (invert) w = (a / 255f) - w
            out[i] = (w.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        return out
    }

    /** How far apart two colors look (the "redmean" weighting of RGB, about 0..765). */
    fun distance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
        val rm = (r1 + r2) / 2f
        val dr = (r1 - r2).toFloat()
        val dg = (g1 - g2).toFloat()
        val db = (b1 - b2).toFloat()
        return sqrt((2f + rm / 256f) * dr * dr + 4f * dg * dg + (2f + (255f - rm) / 256f) * db * db)
    }

    private fun rangeWeight(kind: RangeKind, c: Int): Float {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx <= 0f) 0f else (mx - mn) / mx
        val hue = hue(r, g, b, mx, mn)
        fun family(center: Float): Float =
            (1f - smooth(22f, 45f, hueDistance(hue, center))) * smooth(0.08f, 0.25f, sat) * smooth(0.06f, 0.18f, mx)
        return when (kind) {
            RangeKind.REDS -> family(0f)
            RangeKind.YELLOWS -> family(60f)
            RangeKind.GREENS -> family(120f)
            RangeKind.CYANS -> family(180f)
            RangeKind.BLUES -> family(240f)
            RangeKind.MAGENTAS -> family(300f)
            RangeKind.HIGHLIGHTS -> smooth(0.6f, 0.8f, l)
            RangeKind.SHADOWS -> 1f - smooth(0.2f, 0.4f, l)
            RangeKind.MIDTONES -> 1f - smooth(0.15f, 0.35f, abs(l - 0.5f))
            // Warm, fairly but not very saturated, not too dark
            RangeKind.SKIN -> (1f - smooth(18f, 38f, hueDistance(hue, 22f))) *
                smooth(0.1f, 0.2f, sat) * (1f - smooth(0.6f, 0.75f, sat)) * smooth(0.2f, 0.35f, mx)
            RangeKind.SAMPLED -> 0f
        }
    }

    private fun hue(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d <= 1e-5f) return 0f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** 0 below [a], 1 above [b], smooth between. */
    private fun smooth(a: Float, b: Float, x: Float): Float {
        if (b <= a) return if (x >= b) 1f else 0f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
