package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Levels and Curves: both become one lookup table (256 entries per color channel) that the
// adjustments shader reads, so they cost the same however many points or channels you change.

/** Which adjustment an adjustment layer (or a window) is about. */
enum class FxKind { ADJUST, LEVELS, CURVES, BLUR, HUE_SAT, COLOR_BALANCE, BLACK_WHITE, PHOTO_FILTER, GRADIENT_MAP, VIBRANCE, CHANNEL_MIXER, SELECTIVE_COLOR, COLOR_LOOKUP, POSTERIZE, THRESHOLD }

/** Channels in Levels and Curves: all three together, or one. */
enum class ToneChannel(val label: String) { RGB("RGB"), RED("Red"), GREEN("Green"), BLUE("Blue") }

/** Levels for one channel (all 0..1 except [gamma]). */
data class LevelsChannel(
    val inBlack: Float = 0f,
    val inWhite: Float = 1f,
    /** Midtones: above 1 lightens, below 1 darkens. */
    val gamma: Float = 1f,
    val outBlack: Float = 0f,
    val outWhite: Float = 1f,
) {
    val isIdentity: Boolean get() = inBlack == 0f && inWhite == 1f && gamma == 1f && outBlack == 0f && outWhite == 1f

    fun map(x: Float): Float {
        val span = (inWhite - inBlack).coerceAtLeast(1f / 255f)
        val t = ((x - inBlack) / span).coerceIn(0f, 1f)
        return outBlack + (outWhite - outBlack) * t.pow(1f / gamma.coerceIn(0.1f, 9.99f))
    }

    fun toJson(): JSONArray = JSONArray().put(inBlack.toDouble()).put(inWhite.toDouble()).put(gamma.toDouble()).put(outBlack.toDouble()).put(outWhite.toDouble())

    companion object {
        fun fromJson(a: JSONArray?): LevelsChannel = if (a == null || a.length() < 5) LevelsChannel() else LevelsChannel(
            a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 1.0).toFloat(), a.optDouble(2, 1.0).toFloat(),
            a.optDouble(3, 0.0).toFloat(), a.optDouble(4, 1.0).toFloat(),
        )
    }
}

data class Levels(val channels: List<LevelsChannel> = List(4) { LevelsChannel() }) {
    operator fun get(c: ToneChannel): LevelsChannel = channels.getOrElse(c.ordinal) { LevelsChannel() }
    fun with(c: ToneChannel, ch: LevelsChannel): Levels = Levels(channels.mapIndexed { i, x -> if (i == c.ordinal) ch else x })
    val isIdentity: Boolean get() = channels.all { it.isIdentity }

    fun toJson(): JSONArray = JSONArray().also { a -> channels.forEach { a.put(it.toJson()) } }

    companion object {
        fun fromJson(a: JSONArray?): Levels? = a?.let { arr -> Levels(List(4) { i -> LevelsChannel.fromJson(arr.optJSONArray(i)) }) }
    }
}

data class CurvePoint(val x: Float, val y: Float)

private val STRAIGHT = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

/** A curve per channel: points (0..1, sorted by x) joined smoothly. */
data class Curves(val channels: List<List<CurvePoint>> = List(4) { STRAIGHT }) {
    operator fun get(c: ToneChannel): List<CurvePoint> = channels.getOrElse(c.ordinal) { STRAIGHT }
    fun with(c: ToneChannel, pts: List<CurvePoint>): Curves = Curves(channels.mapIndexed { i, x -> if (i == c.ordinal) pts.sortedBy { it.x } else x })
    val isIdentity: Boolean get() = channels.all { it == STRAIGHT }

    fun toJson(): JSONArray = JSONArray().also { a ->
        channels.forEach { pts -> a.put(JSONArray().also { pa -> pts.forEach { pa.put(it.x.toDouble()).put(it.y.toDouble()) } }) }
    }

    companion object {
        val LINE: List<CurvePoint> = STRAIGHT

        fun fromJson(a: JSONArray?): Curves? = a?.let { arr ->
            Curves(List(4) { i ->
                val pa = arr.optJSONArray(i)
                if (pa == null || pa.length() < 4) STRAIGHT
                else List(pa.length() / 2) { k -> CurvePoint(pa.optDouble(k * 2).toFloat(), pa.optDouble(k * 2 + 1).toFloat()) }.sortedBy { it.x }
            })
        }
    }
}

object Tone {

    /**
     * The curve through [pts] at 256 places. Smooth, without overshooting between points
     * (monotone cubic), flat before the first point and after the last.
     */
    fun curveTable(pts: List<CurvePoint>): FloatArray {
        val out = FloatArray(256)
        val p = pts.sortedBy { it.x }
        if (p.size < 2) {
            for (i in 0..255) out[i] = i / 255f
            return out
        }
        val n = p.size
        val d = FloatArray(n - 1) { k ->
            val dx = (p[k + 1].x - p[k].x).coerceAtLeast(1e-4f)
            (p[k + 1].y - p[k].y) / dx
        }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        for (k in 0 until n - 1) {
            if (d[k] == 0f) {
                m[k] = 0f
                m[k + 1] = 0f
            } else {
                val a = m[k] / d[k]
                val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[k] = t * a * d[k]
                    m[k + 1] = t * b * d[k]
                }
            }
        }
        var seg = 0
        for (i in 0..255) {
            val x = i / 255f
            out[i] = when {
                x <= p[0].x -> p[0].y
                x >= p[n - 1].x -> p[n - 1].y
                else -> {
                    while (seg < n - 2 && x > p[seg + 1].x) seg++
                    val h = (p[seg + 1].x - p[seg].x).coerceAtLeast(1e-4f)
                    val t = (x - p[seg].x) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * p[seg].y + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * p[seg + 1].y + (t3 - t2) * h * m[seg + 1]
                }
            }.coerceIn(0f, 1f)
        }
        return out
    }

    /** Levels then Curves as a 256 × 1 picture: entry i holds what red, green and blue value i become. */
    fun lut(levels: Levels?, curves: Curves?): Bitmap {
        val px = IntArray(256)
        val cr = curves?.let { arrayOf(curveTable(it[ToneChannel.RGB]), curveTable(it[ToneChannel.RED]), curveTable(it[ToneChannel.GREEN]), curveTable(it[ToneChannel.BLUE])) }
        fun lookup(t: FloatArray, v: Float): Float {
            val f = v.coerceIn(0f, 1f) * 255f
            val i = f.toInt().coerceAtMost(254)
            val k = f - i
            return t[i] + (t[i + 1] - t[i]) * k
        }
        for (i in 0..255) {
            val v = i / 255f
            val ch = IntArray(3) { c ->
                var x = v
                if (levels != null) {
                    // The channel's own levels, then the RGB ones
                    x = levels[ToneChannel.entries[c + 1]].map(x)
                    x = levels[ToneChannel.RGB].map(x)
                }
                if (cr != null) {
                    x = lookup(cr[c + 1], x)
                    x = lookup(cr[0], x)
                }
                (x * 255f).roundToInt().coerceIn(0, 255)
            }
            px[i] = Color.argb(255, ch[0], ch[1], ch[2])
        }
        return Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
    }

    /** How many pixels of [bmp] have each brightness (or each value of one channel). Transparent pixels don't count. */
    fun histogram(bmp: Bitmap, channel: ToneChannel): IntArray {
        val h = IntArray(256)
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (c in row) {
                val a = c ushr 24
                if (a < 8) continue
                // getPixels gives straight (not premultiplied) colors
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val v = when (channel) {
                    ToneChannel.RGB -> (r * 54 + g * 183 + b * 19) shr 8
                    ToneChannel.RED -> r
                    ToneChannel.GREEN -> g
                    ToneChannel.BLUE -> b
                }
                h[v.coerceIn(0, 255)]++
            }
        }
        return h
    }

    /** Black and white points that clip 0.1% of pixels at each end (Auto levels). */
    fun autoPoints(hist: IntArray): Pair<Float, Float> {
        val total = hist.sum().coerceAtLeast(1)
        val clip = total * 0.001f
        var acc = 0
        var lo = 0
        while (lo < 254 && acc + hist[lo] <= clip) acc += hist[lo++]
        acc = 0
        var hi = 255
        while (hi > lo + 1 && acc + hist[hi] <= clip) acc += hist[hi--]
        return lo / 255f to hi / 255f
    }
}
