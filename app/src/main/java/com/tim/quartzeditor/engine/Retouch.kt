package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The retouch brushes: Clone Stamp, Dodge, Burn, Smudge, Blur and Sharpen work dab by dab on the
// layer's pixels (copy-on-write, one undo step per stroke). Spot Healing collects where you
// painted and heals it when you lift your finger (see [Heal]).

enum class RetouchKind(val label: String) {
    SPOT_HEAL("Spot healing"), AI_REMOVE("AI Remove"), CLONE("Clone stamp"), DODGE("Dodge"), BURN("Burn"), SMUDGE("Smudge"), BLUR("Blur"), SHARPEN("Sharpen");

    /** Painted where to work, then done all at once when the finger lifts. */
    val paintsArea: Boolean get() = this == SPOT_HEAL || this == AI_REMOVE

    companion object {
        fun byName(n: String?): RetouchKind = entries.firstOrNull { it.name == n } ?: SPOT_HEAL
    }
}

/** Where the Clone Stamp copies from: [grid]'s pixels, placed with their (0, 0) at canvas ([ox], [oy]). */
class CloneSource(val grid: TileGrid, val ox: Int, val oy: Int)

/**
 * One retouch stroke on [layer] (not Spot Healing). [strength] 0..1. For the Clone Stamp, [source]
 * and ([offX], [offY]) = where it copies from relative to where you paint (canvas pixels).
 */
class RetouchSession(
    val layer: Layer,
    val kind: RetouchKind,
    size: Float,
    softness: Float,
    private val strength: Float,
    private val selection: Selection?,
    private val range: ToneRange = ToneRange.MIDTONES,
    private val source: CloneSource? = null,
    private val offX: Float = 0f,
    private val offY: Float = 0f,
) {
    private val grid = layer.tiles
    private val saved = HashMap<Long, Bitmap?>()
    private val d = size.roundToInt().coerceIn(2, Dabs.MAX_SIZE)
    private val mask: FloatArray = run {
        val b = Dabs.make(BrushTip.ROUND, d, softness)
        val bytes = ByteArray(d * d)
        b.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
        b.recycle()
        FloatArray(d * d) { (bytes[it].toInt() and 0xFF) / 255f }
    }
    private val spacing = max(1f, d * when (kind) {
        RetouchKind.SMUDGE -> 0.08f
        RetouchKind.CLONE -> 0.12f
        else -> 0.2f
    })
    private val lockAlpha = layer.props.lockAlpha
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var carry = 0f
    /** The smudge's paint: what it picked up last. */
    private var smudge: IntArray? = null

    /** Where the stroke is (canvas pixels), for drawing the clone source marker. */
    var at: android.graphics.PointF? = null
        private set

    val changed: Boolean get() = saved.isNotEmpty()

    /** Adds the next touch point (canvas pixels). */
    fun addPoint(cx: Float, cy: Float) {
        at = android.graphics.PointF(cx, cy)
        val x = cx - layer.x
        val y = cy - layer.y
        if (lastX.isNaN()) {
            dab(x, y)
            lastX = x
            lastY = y
            carry = spacing
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        if (len <= 0f) return
        var t = carry
        while (t <= len) {
            dab(lastX + dx * t / len, lastY + dy * t / len)
            t += spacing
        }
        carry = t - len
        lastX = x
        lastY = y
    }

    /** One dab at layer-local ([x], [y]). */
    private fun dab(x: Float, y: Float) {
        val left = (x - d / 2f).roundToInt()
        val top = (y - d / 2f).roundToInt()
        val pad = when (kind) {
            RetouchKind.BLUR -> blurRadius() * 2 + 1
            RetouchKind.SHARPEN -> 4
            else -> 0
        }
        val r = Rect(left - pad, top - pad, left + d + pad, top + d + pad)
        val w = r.width()
        val h = r.height()
        val orig = readPatch(grid, r, 0, 0)
        val has = orig.any { (it ushr 24) != 0 }
        if (!has && kind != RetouchKind.CLONE) return
        val target: IntArray = when (kind) {
            RetouchKind.DODGE, RetouchKind.BURN -> IntArray(orig.size) { tone(orig[it]) }
            RetouchKind.BLUR -> blurred(orig, w, h, blurRadius())
            RetouchKind.SHARPEN -> sharpened(orig, w, h)
            RetouchKind.SMUDGE -> smudge ?: run {
                smudge = orig.copyOf()
                return
            }
            RetouchKind.CLONE -> {
                val src = source ?: return
                readPatch(src.grid, Rect(r).apply { offset(offX.roundToInt() + layer.x - src.ox, offY.roundToInt() + layer.y - src.oy) }, 0, 0)
            }
            RetouchKind.SPOT_HEAL, RetouchKind.AI_REMOVE -> return
        }
        // How much of each pixel this dab changes: the dab's shape, the strength, the selection
        val sel = selection?.let { readMask(it.mask(), Rect(r).apply { offset(layer.x, layer.y) }) }
        val k = strength * when (kind) {
            RetouchKind.DODGE, RetouchKind.BURN -> 0.22f
            RetouchKind.BLUR -> 0.6f
            RetouchKind.SHARPEN -> 0.35f
            RetouchKind.SMUDGE -> 1f
            else -> 1f
        }
        val out = orig.copyOf()
        var any = false
        for (py in 0 until d) for (px in 0 until d) {
            var m = mask[py * d + px] * k
            if (m <= 0f) continue
            val i = (py + pad) * w + (px + pad)
            if (sel != null) m *= (sel[i].toInt() and 0xFF) / 255f
            if (m <= 0.001f) continue
            out[i] = mix(orig[i], target[i], m.coerceAtMost(1f), lockAlpha)
            any = true
        }
        if (kind == RetouchKind.SMUDGE) {
            // Picks up what's there now for the next dab
            smudge = out.copyOf()
        }
        if (!any) return
        writePatch(r, out, w, h, createMissing = kind == RetouchKind.CLONE)
    }

    private fun blurRadius(): Int = max(1, (d * 0.04f).roundToInt()).coerceAtMost(12)

    /** Dodge lightens, Burn darkens, mostly in the chosen range of tones. */
    private fun tone(c: Int): Int {
        val a = c ushr 24
        if (a == 0) return c
        var r = ((c shr 16) and 0xFF) / 255f
        var g = ((c shr 8) and 0xFF) / 255f
        var b = (c and 0xFF) / 255f
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val w = when (range) {
            ToneRange.SHADOWS -> (1f - l) * (1f - l)
            ToneRange.MIDTONES -> 1f - abs(2f * l - 1f)
            ToneRange.HIGHLIGHTS -> l * l
        }
        if (kind == RetouchKind.DODGE) {
            r += (1f - r) * w
            g += (1f - g) * w
            b += (1f - b) * w
        } else {
            r *= 1f - w
            g *= 1f - w
            b *= 1f - w
        }
        return (a shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }

    private fun sharpened(src: IntArray, w: Int, h: Int): IntArray {
        val bl = blurred(src, w, h, 2)
        return IntArray(src.size) { i ->
            val c = src[i]
            val b = bl[i]
            val a = c ushr 24
            if (a == 0) c else {
                fun ch(s: Int): Int {
                    val v = (c shr s) and 0xFF
                    val bv = (b shr s) and 0xFF
                    return (v + (v - bv) * 1.2f).roundToInt().coerceIn(0, 255)
                }
                (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }

    /** Draws the changed patch back into the layer's tiles (copies them first, for undo). */
    private fun writePatch(r: Rect, px: IntArray, w: Int, h: Int, createMissing: Boolean) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        for (k in grid.keysIn(android.graphics.RectF(r))) {
            val existing = grid[k]
            if (existing == null && !createMissing) continue
            if (k !in saved) {
                saved[k] = existing
                grid.tiles[k] = grid.editableCopy(existing)
            }
            val t = grid.tiles.getValue(k)
            val c = Canvas(t)
            c.drawBitmap(bmp, (r.left - TileGrid.tx(k) * TileGrid.SIZE).toFloat(), (r.top - TileGrid.ty(k) * TileGrid.SIZE).toFloat(), src)
        }
        bmp.recycle()
    }

    /** The stroke as one undo step (null if nothing changed). */
    fun commit(): TilePatch? {
        if (saved.isEmpty()) return null
        return TilePatch(layer, grid, HashMap(saved), kind.label)
    }

    /** Puts everything back (the stroke turned out to be a two-finger gesture). */
    fun cancel() {
        for ((k, b) in saved) grid.swap(k, b)
        saved.clear()
    }

    companion object {
        private fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

        /** [a] → [b] by [t], in premultiplied terms (so see-through pixels don't darken). */
        fun mix(a: Int, b: Int, t: Float, keepAlpha: Boolean): Int {
            val aa = (a ushr 24) / 255f
            val ba = (b ushr 24) / 255f
            val oa = if (keepAlpha) aa else aa + (ba - aa) * t
            if (oa <= 0.0001f) return 0
            fun ch(s: Int): Int {
                val av = ((a shr s) and 0xFF) / 255f * aa
                val bv = ((b shr s) and 0xFF) / 255f * (if (keepAlpha) aa else ba)
                return to8((av + (bv - av) * t) / oa)
            }
            return (to8(oa) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** [grid]'s pixels (unpremultiplied ARGB) over [r] in its own coordinates. */
        fun readPatch(grid: TileGrid, r: Rect, ox: Int, oy: Int): IntArray {
            val w = r.width()
            val h = r.height()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(-(r.left - ox).toFloat(), -(r.top - oy).toFloat())
            grid.drawTo(c, 0f, 0f, null, android.graphics.RectF(r))
            val out = IntArray(w * h)
            bmp.getPixels(out, 0, w, 0, 0, w, h)
            bmp.recycle()
            return out
        }

        /** A 1-byte grid's values over [r]. */
        fun readMask(grid: TileGrid, r: Rect): ByteArray {
            val w = r.width()
            val h = r.height()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            c.translate(-r.left.toFloat(), -r.top.toFloat())
            grid.drawTo(c, 0f, 0f, null, android.graphics.RectF(r))
            val out = ByteArray(w * h)
            bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(out))
            bmp.recycle()
            return out
        }

        /** A box blur (two passes, close to a Gaussian) of radius [rad], in premultiplied terms. */
        fun blurred(src: IntArray, w: Int, h: Int, rad: Int): IntArray {
            val n = w * h
            val ch = Array(4) { FloatArray(n) }
            for (i in 0 until n) {
                val c = src[i]
                val a = (c ushr 24) / 255f
                ch[0][i] = a
                ch[1][i] = ((c shr 16) and 0xFF) / 255f * a
                ch[2][i] = ((c shr 8) and 0xFF) / 255f * a
                ch[3][i] = (c and 0xFF) / 255f * a
            }
            val tmp = FloatArray(n)
            repeat(2) {
                for (k in 0 until 4) {
                    boxH(ch[k], tmp, w, h, rad)
                    boxV(tmp, ch[k], w, h, rad)
                }
            }
            return IntArray(n) { i ->
                val a = ch[0][i]
                if (a <= 0.0001f) 0 else (to8(a) shl 24) or (to8(ch[1][i] / a) shl 16) or (to8(ch[2][i] / a) shl 8) or to8(ch[3][i] / a)
            }
        }

        private fun boxH(s: FloatArray, o: FloatArray, w: Int, h: Int, r: Int) {
            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                var cnt = 0
                for (x in -r..r) if (x in 0 until w) { sum += s[row + x]; cnt++ }
                for (x in 0 until w) {
                    o[row + x] = sum / cnt
                    val out = x - r
                    val inn = x + r + 1
                    if (out >= 0) { sum -= s[row + out]; cnt-- }
                    if (inn < w) { sum += s[row + inn]; cnt++ }
                }
            }
        }

        private fun boxV(s: FloatArray, o: FloatArray, w: Int, h: Int, r: Int) {
            for (x in 0 until w) {
                var sum = 0f
                var cnt = 0
                for (y in -r..r) if (y in 0 until h) { sum += s[y * w + x]; cnt++ }
                for (y in 0 until h) {
                    o[y * w + x] = sum / cnt
                    val out = y - r
                    val inn = y + r + 1
                    if (out >= 0) { sum -= s[out * w + x]; cnt-- }
                    if (inn < h) { sum += s[inn * w + x]; cnt++ }
                }
            }
        }
    }
}

/**
 * Spot healing: fills the painted area with texture from nearby (the best-matching place around
 * it), then evens out the color and light so it blends in (like Photoshop's healing).
 */
object Heal {
    /**
     * [img] the surroundings (unpremultiplied ARGB, [w]×[h]), [hole] how much of each pixel to heal
     * (0..255). Returns the healed pixels for the whole area (unchanged outside the hole).
     */
    fun heal(img: IntArray, hole: ByteArray, w: Int, h: Int): IntArray {
        val n = w * h
        val inHole = BooleanArray(n) { (hole[it].toInt() and 0xFF) > 8 }
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (inHole[y * w + x]) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        if (x1 < 0) return img.copyOf()
        val hw = x1 - x0 + 1
        val hh = y1 - y0 + 1
        // A band of known pixels around the hole: what the patch has to match
        val band = max(3, (max(hw, hh) * 0.12f).roundToInt()).coerceAtMost(12)
        // The work happens in the box around the hole and its band (the rest of the area is only
        // looked at, as the place to copy from)
        val bx0 = max(0, x0 - band - 1)
        val by0 = max(0, y0 - band - 1)
        val bx1 = min(w - 1, x1 + band + 1)
        val by1 = min(h - 1, y1 + band + 1)
        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        val bn = bw * bh
        fun full(i: Int): Int = (by0 + i / bw) * w + (bx0 + i % bw)
        val holeB = BooleanArray(bn) { inHole[full(it)] }
        val areaB = dilate(holeB, bw, bh, band)
        val areaIdx = (0 until bn).filter { areaB[it] }.toIntArray()
        val ringIdx = (0 until bn).filter { areaB[it] && !holeB[it] }.toIntArray()
        // Premultiplied channel k (0 = alpha) of a picture pixel
        fun pm(c: Int, k: Int): Float {
            val a = (c ushr 24) / 255f
            return when (k) {
                0 -> a
                1 -> ((c shr 16) and 0xFF) / 255f * a
                2 -> ((c shr 8) and 0xFF) / 255f * a
                else -> (c and 0xFF) / 255f * a
            }
        }
        // Look around the hole for the place whose surroundings match best
        val span = max(hw, hh)
        var best: Pair<Int, Int>? = null
        var bestScore = Float.MAX_VALUE
        fun score(dx: Int, dy: Int): Float {
            for (i in areaIdx) {
                val fi = full(i)
                val x = fi % w + dx
                val y = fi / w + dy
                if (x !in 0 until w || y !in 0 until h) return Float.MAX_VALUE
                if (inHole[y * w + x]) return Float.MAX_VALUE
            }
            var sc = 0f
            for (i in ringIdx) {
                val fi = full(i)
                val a = img[fi]
                val b = img[fi + dy * w + dx]
                for (k in 0 until 4) {
                    val e = pm(a, k) - pm(b, k)
                    sc += e * e
                }
            }
            // A slight preference for nearby places
            return sc / ringIdx.size.coerceAtLeast(1) * (1f + 0.05f * hypot(dx.toFloat(), dy.toFloat()) / span)
        }
        for (f in floatArrayOf(1.1f, 1.4f, 1.8f, 2.4f, 3.2f)) {
            val rr = span * f + band
            for (a in 0 until 24) {
                val ang = a * Math.PI * 2 / 24
                val dx = (kotlin.math.cos(ang) * rr).roundToInt()
                val dy = (kotlin.math.sin(ang) * rr).roundToInt()
                val sc = score(dx, dy)
                if (sc < bestScore) {
                    bestScore = sc
                    best = dx to dy
                }
            }
        }
        // Fine-tune around the best one
        best?.let { (bx, by) ->
            for (oy in -4..4 step 2) for (ox in -4..4 step 2) {
                val sc = score(bx + ox, by + oy)
                if (sc < bestScore) {
                    bestScore = sc
                    best = (bx + ox) to (by + oy)
                }
            }
        }
        val result = img.copyOf()
        val found = best
        val known = BooleanArray(bn)
        val values = Array(4) { FloatArray(bn) }
        if (found != null) {
            // Copy the texture, then even out the difference at the edge across the hole
            val (dx, dy) = found
            for (i in ringIdx) {
                val fi = full(i)
                for (k in 0 until 4) values[k][i] = pm(img[fi], k) - pm(img[fi + dy * w + dx], k)
                known[i] = true
            }
        } else {
            // No good place to copy from: a smooth fill from the edges
            for (i in 0 until bn) if (!holeB[i]) {
                for (k in 0 until 4) values[k][i] = pm(img[full(i)], k)
                known[i] = true
            }
        }
        val where = if (found != null) areaB else BooleanArray(bn) { true }
        val smooth = Array(4) { k -> pushPull(values[k], known, where, bw, bh) }
        for (i in 0 until bn) {
            if (!holeB[i]) continue
            val fi = full(i)
            val src = if (found != null) img[fi + found.second * w + found.first] else 0
            val base = FloatArray(4) { k -> if (found != null) pm(src, k) else 0f }
            val a = (base[0] + smooth[0][i]).coerceIn(0f, 1f)
            if (a <= 0.0001f) {
                result[fi] = 0
                continue
            }
            fun c(k: Int): Int = (((base[k] + smooth[k][i]) / a).coerceIn(0f, 1f) * 255f).roundToInt()
            result[fi] = ((a * 255f).roundToInt() shl 24) or (c(1) shl 16) or (c(2) shl 8) or c(3)
        }
        return result
    }

    /**
     * Fills the unknown values of [v] (where [known] is false, inside [where]) smoothly from the
     * known ones: averaged down a pyramid, then back up, then smoothed a little.
     */
    fun pushPull(v: FloatArray, known: BooleanArray, where: BooleanArray, w: Int, h: Int): FloatArray {
        val levels = ArrayList<Triple<FloatArray, FloatArray, Pair<Int, Int>>>()
        var cv = FloatArray(w * h) { if (known[it]) v[it] else 0f }
        var cw = FloatArray(w * h) { if (known[it]) 1f else 0f }
        var cwid = w
        var chei = h
        levels.add(Triple(cv, cw, cwid to chei))
        while (cwid > 1 || chei > 1) {
            val nw = (cwid + 1) / 2
            val nh = (chei + 1) / 2
            val nv = FloatArray(nw * nh)
            val nwt = FloatArray(nw * nh)
            for (y in 0 until chei) for (x in 0 until cwid) {
                val i = y * cwid + x
                val j = (y / 2) * nw + x / 2
                nv[j] += cv[i] * cw[i]
                nwt[j] += cw[i]
            }
            for (j in nv.indices) if (nwt[j] > 0f) {
                nv[j] /= nwt[j]
                nwt[j] = min(1f, nwt[j])
            }
            cv = nv
            cw = nwt
            cwid = nw
            chei = nh
            levels.add(Triple(cv, cw, cwid to chei))
        }
        // Back up: unknown values come from the level above
        for (li in levels.size - 2 downTo 0) {
            val (lv, lw, size) = levels[li]
            val (uv, _, usize) = levels[li + 1]
            val (lwid, lhei) = size
            val uwid = usize.first
            for (y in 0 until lhei) for (x in 0 until lwid) {
                val i = y * lwid + x
                val up = uv[(y / 2).coerceAtMost(usize.second - 1) * uwid + (x / 2).coerceAtMost(uwid - 1)]
                lv[i] = lv[i] * lw[i] + up * (1f - lw[i])
            }
        }
        val out = levels[0].first
        // A few smoothing passes over the filled part (so the steps from the pyramid don't show)
        val fill = BooleanArray(w * h) { !known[it] && where[it] }
        val tmp = out.copyOf()
        repeat(24) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!fill[i]) continue
                var s = 0f
                var c = 0
                if (x > 0) { s += out[i - 1]; c++ }
                if (x < w - 1) { s += out[i + 1]; c++ }
                if (y > 0) { s += out[i - w]; c++ }
                if (y < h - 1) { s += out[i + w]; c++ }
                tmp[i] = s / c
            }
            for (i in out.indices) if (fill[i]) out[i] = tmp[i]
        }
        return out
    }

    /** [m] grown by [r] pixels (a square). */
    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            var last = -100000
            for (x in 0 until w) {
                if (m[y * w + x]) last = x
                if (x - last <= r) tmp[y * w + x] = true
            }
            last = 100000
            for (x in w - 1 downTo 0) {
                if (m[y * w + x]) last = x
                if (last - x <= r) tmp[y * w + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -100000
            for (y in 0 until h) {
                if (tmp[y * w + x]) last = y
                if (y - last <= r) out[y * w + x] = true
            }
            last = 100000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) last = y
                if (last - y <= r) out[y * w + x] = true
            }
        }
        return out
    }
}
