package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where a layer's pixels go while transforming. [src] is the area of the layer being transformed
 * (layer-local pixels). Normally it's placed by center, scale and rotation; in distort mode each
 * corner is placed freely ([quad], canvas pixels, clockwise from the top-left).
 *
 * Negative scales are flips.
 */
/** How the transform box's handles behave. Everything but Free places the corners freely. */
enum class TransformMode(val label: String) {
    FREE("Free"), SKEW("Skew"), PERSPECTIVE("Perspective"), DISTORT("Distort");

    val usesCorners: Boolean get() = this != FREE
}

data class TransformState(
    val src: RectF,
    val cx: Float,
    val cy: Float,
    val sx: Float = 1f,
    val sy: Float = 1f,
    /** Degrees, clockwise. */
    val angle: Float = 0f,
    val quad: List<PointF>? = null,
    val keepAspect: Boolean = true,
    val mode: TransformMode = TransformMode.FREE,
) {
    /** The corners are placed freely (skew, perspective or distort). */
    val distort: Boolean get() = quad != null

    private val rad: Double get() = Math.toRadians(angle.toDouble())

    /** The four corners on the canvas: top-left, top-right, bottom-right, bottom-left of [src]. */
    fun corners(): List<PointF> {
        quad?.let { return it }
        val hw = src.width() / 2f * sx
        val hh = src.height() / 2f * sy
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        fun pt(x: Float, y: Float) = PointF(cx + x * c - y * s, cy + x * s + y * c)
        return listOf(pt(-hw, -hh), pt(hw, -hh), pt(hw, hh), pt(-hw, hh))
    }

    /** Maps layer-local pixels inside [src] to canvas pixels. */
    fun matrix(): Matrix {
        val m = Matrix()
        val q = corners()
        val from = floatArrayOf(src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom)
        val to = floatArrayOf(q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y)
        m.setPolyToPoly(from, 0, to, 0, 4)
        return m
    }

    val center: PointF
        get() {
            val q = corners()
            return PointF((q[0].x + q[1].x + q[2].x + q[3].x) / 4f, (q[0].y + q[1].y + q[2].y + q[3].y) / 4f)
        }

    // ------------------------------------------------------------ edits (all in canvas pixels)

    fun moved(dx: Float, dy: Float): TransformState =
        if (quad != null) copy(quad = quad.map { PointF(it.x + dx, it.y + dy) }) else copy(cx = cx + dx, cy = cy + dy)

    fun rotated(deg: Float): TransformState {
        if (quad == null) return copy(angle = normalize(angle + deg))
        val c = center
        val r = Math.toRadians(deg.toDouble())
        val co = cos(r).toFloat()
        val si = sin(r).toFloat()
        return copy(quad = quad.map { PointF(c.x + (it.x - c.x) * co - (it.y - c.y) * si, c.y + (it.x - c.x) * si + (it.y - c.y) * co) })
    }

    fun flippedH(): TransformState = if (quad != null) {
        val c = center
        val u = axisU()
        copy(quad = quad.map { mirror(it, c, u) })
    } else copy(sx = -sx)

    fun flippedV(): TransformState = if (quad != null) {
        val c = center
        val v = axisV()
        copy(quad = quad.map { mirror(it, c, v) })
    } else copy(sy = -sy)

    /** Width and height as a fraction of the original (always positive). */
    val scaleX: Float get() = abs(sx)
    val scaleY: Float get() = abs(sy)

    fun withScale(newX: Float, newY: Float): TransformState =
        copy(sx = sign(sx) * newX.coerceAtLeast(MIN_SCALE), sy = sign(sy) * newY.coerceAtLeast(MIN_SCALE))

    /**
     * For moves and quarter turns at 100%: shifts by under a pixel so pixels land exactly on pixels
     * (otherwise the whole layer would be resampled and go slightly soft).
     */
    fun pixelAligned(): TransformState {
        if (quad != null) return this
        val quarter = abs(angle / 90f - Math.round(angle / 90f)) < 1e-4f
        if (!quarter || abs(scaleX - 1f) > 1e-4f || abs(scaleY - 1f) > 1e-4f) return this
        val c0 = corners()[0]
        return copy(cx = cx + (Math.round(c0.x) - c0.x), cy = cy + (Math.round(c0.y) - c0.y))
    }

    /** Turns on distort: the corners become free. */
    fun toDistort(): TransformState = withMode(TransformMode.DISTORT)

    /** Switches how the handles work. Once the corners are free, going back to Free keeps the shape (as Distort). */
    fun withMode(m: TransformMode): TransformState = when {
        m == TransformMode.FREE -> if (quad == null) copy(mode = m) else this
        else -> copy(quad = quad ?: corners(), mode = m)
    }

    /**
     * A corner dragged by ([dx], [dy]) from where it was in this (starting) state, the way the mode
     * says: Skew slides it along one of its sides; Perspective moves the other end of that side the
     * opposite way, so the side stays centered; Distort moves it freely. Null when that would fold
     * the box over (the caller keeps what it has).
     */
    fun dragCornerBy(i: Int, dx: Float, dy: Float): TransformState? {
        val q = quad ?: return null
        if (mode == TransformMode.DISTORT || mode == TransformMode.FREE) {
            val moved = q.mapIndexed { j, c -> if (j == i) PointF(c.x + dx, c.y + dy) else c }
            return if (isConvex(moved)) copy(quad = moved) else null
        }
        // The two sides meeting at this corner: towards the next corner, and towards the previous one
        val next = (i + 1) % 4
        val prev = (i + 3) % 4
        fun unit(a: PointF, b: PointF): PointF {
            val l = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
            return PointF((b.x - a.x) / l, (b.y - a.y) / l)
        }
        val un = unit(q[i], q[next])
        val up = unit(q[i], q[prev])
        val tn = dx * un.x + dy * un.y
        val tp = dx * up.x + dy * up.y
        // Whichever side the finger moves along more
        val (partner, u, t) = if (abs(tn) >= abs(tp)) Triple(next, un, tn) else Triple(prev, up, tp)
        val moved = q.mapIndexed { j, c ->
            when {
                j == i -> PointF(c.x + u.x * t, c.y + u.y * t)
                mode == TransformMode.PERSPECTIVE && j == partner -> PointF(c.x - u.x * t, c.y - u.y * t)
                else -> c
            }
        }
        return if (isConvex(moved)) copy(quad = moved) else null
    }

    /**
     * The middle of edge [i] dragged by ([dx], [dy]) from this (starting) state. Skew and Perspective
     * slide the edge along itself (slanting the box); Distort moves it freely. Null when that would
     * fold the box over.
     */
    fun dragEdgeBy(i: Int, dx: Float, dy: Float): TransformState? {
        val q = quad ?: return null
        val a = i
        val b = (i + 1) % 4
        var mx = dx
        var my = dy
        if (mode == TransformMode.SKEW || mode == TransformMode.PERSPECTIVE) {
            val l = hypot(q[b].x - q[a].x, q[b].y - q[a].y).coerceAtLeast(0.001f)
            val ux = (q[b].x - q[a].x) / l
            val uy = (q[b].y - q[a].y) / l
            val t = dx * ux + dy * uy
            mx = ux * t
            my = uy * t
        }
        val moved = q.mapIndexed { j, c -> if (j == a || j == b) PointF(c.x + mx, c.y + my) else c }
        return if (isConvex(moved)) copy(quad = moved) else null
    }

    /**
     * Drags corner [i] to canvas point [p]. Normally scales from the opposite corner (keeping the
     * shape when [keepAspect]); in distort mode just moves that corner.
     */
    fun dragCorner(i: Int, p: PointF): TransformState {
        if (quad != null) {
            val next = quad.mapIndexed { j, q -> if (j == i) PointF(p.x, p.y) else q }
            // A corner can't cross over: the shape must stay convex
            return if (isConvex(next)) copy(quad = next) else this
        }
        val q = corners()
        val o = q[(i + 2) % 4]
        val u = axisU()
        val v = axisV()
        val a = dot(q[i].x - o.x, q[i].y - o.y, u)
        val b = dot(q[i].x - o.x, q[i].y - o.y, v)
        val dA = dot(p.x - o.x, p.y - o.y, u)
        val dB = dot(p.x - o.x, p.y - o.y, v)
        var na: Float
        var nb: Float
        if (keepAspect) {
            val k = ((dA * a + dB * b) / (a * a + b * b)).coerceAtLeast(MIN_SCALE / max(scaleX, scaleY))
            na = a * k
            nb = b * k
        } else {
            na = if (sign(dA) == sign(a)) dA else sign(a) * 1f
            nb = if (sign(dB) == sign(b)) dB else sign(b) * 1f
        }
        na = sign(a) * max(abs(na), 1f)
        nb = sign(b) * max(abs(nb), 1f)
        val nsx = sx * abs(na) / abs(a).coerceAtLeast(0.001f)
        val nsy = sy * abs(nb) / abs(b).coerceAtLeast(0.001f)
        return copy(
            sx = nsx,
            sy = nsy,
            cx = o.x + u.x * na / 2f + v.x * nb / 2f,
            cy = o.y + u.y * na / 2f + v.y * nb / 2f,
        )
    }

    /**
     * Drags the middle of edge [i] (0 = top, 1 = right, 2 = bottom, 3 = left) to [p]: stretches one way
     * from the opposite edge. In distort mode both corners of that edge move together.
     */
    fun dragEdge(i: Int, p: PointF, prev: PointF): TransformState {
        if (quad != null) {
            val dx = p.x - prev.x
            val dy = p.y - prev.y
            val a = i
            val b = (i + 1) % 4
            return copy(quad = quad.mapIndexed { j, q -> if (j == a || j == b) PointF(q.x + dx, q.y + dy) else q })
        }
        val q = corners()
        val mid = PointF((q[i].x + q[(i + 1) % 4].x) / 2f, (q[i].y + q[(i + 1) % 4].y) / 2f)
        val oi = (i + 2) % 4
        val o = PointF((q[oi].x + q[(oi + 1) % 4].x) / 2f, (q[oi].y + q[(oi + 1) % 4].y) / 2f)
        val horizontal = i == 1 || i == 3 // left/right edge: changes width
        val axis = if (horizontal) axisU() else axisV()
        val cur = dot(mid.x - o.x, mid.y - o.y, axis)
        var nd = dot(p.x - o.x, p.y - o.y, axis)
        if (sign(nd) != sign(cur)) nd = sign(cur) * 1f
        nd = sign(cur) * max(abs(nd), 1f)
        val k = abs(nd) / abs(cur).coerceAtLeast(0.001f)
        val ncx = o.x + axis.x * nd / 2f
        val ncy = o.y + axis.y * nd / 2f
        return if (horizontal) copy(sx = sx * k, cx = ncx, cy = ncy) else copy(sy = sy * k, cx = ncx, cy = ncy)
    }

    /** Unit vector along the picture's width (follows rotation). */
    private fun axisU(): PointF {
        if (quad != null) {
            val dx = quad[1].x - quad[0].x
            val dy = quad[1].y - quad[0].y
            val l = hypot(dx, dy).coerceAtLeast(0.001f)
            return PointF(dx / l, dy / l)
        }
        return PointF(cos(rad).toFloat(), sin(rad).toFloat())
    }

    private fun axisV(): PointF {
        val u = axisU()
        return PointF(-u.y, u.x)
    }

    companion object {
        const val MIN_SCALE = 0.01f

        fun sign(v: Float): Float = if (v < 0f) -1f else 1f

        fun isConvex(q: List<PointF>): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val a = q[i]
                val b = q[(i + 1) % 4]
                val c = q[(i + 2) % 4]
                val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
                if (abs(cross) < 1e-3f) return false
                val sg = if (cross > 0) 1 else -1
                if (sign == 0) sign = sg else if (sg != sign) return false
            }
            return true
        }

        private fun dot(x: Float, y: Float, a: PointF) = x * a.x + y * a.y

        private fun normalize(a: Float): Float {
            var v = a % 360f
            if (v > 180f) v -= 360f
            if (v <= -180f) v += 360f
            return v
        }

        private fun mirror(p: PointF, c: PointF, axis: PointF): PointF {
            // Reflect across the line through c perpendicular to axis
            val d = (p.x - c.x) * axis.x + (p.y - c.y) * axis.y
            return PointF(p.x - 2 * d * axis.x, p.y - 2 * d * axis.y)
        }

        /** Starting point: the layer's pixels where they are now. [src] is in layer-local pixels. */
        fun start(src: RectF, layerX: Int, layerY: Int) = TransformState(
            src = src,
            cx = src.centerX() + layerX,
            cy = src.centerY() + layerY,
        )
    }
}

/** Pixel work for transforms (background threads). */
object Transformer {

    /**
     * Draws the part [src] (layer-local) of [grid] through [m] (layer-local → canvas) and returns
     * the result with the canvas area it covers. The result is clipped to [limit].
     */
    fun render(grid: Map<Long, Bitmap>, alphaOnly: Boolean, src: Rect, m: Matrix, limit: Rect): Pair<Bitmap, Rect>? {
        val dst = RectF(src)
        m.mapRect(dst)
        val out = Rect(
            kotlin.math.floor(dst.left).toInt(), kotlin.math.floor(dst.top).toInt(),
            kotlin.math.ceil(dst.right).toInt(), kotlin.math.ceil(dst.bottom).toInt(),
        )
        if (!out.intersect(limit) || out.isEmpty) return null
        val config = if (alphaOnly) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
        // Gather the source into one picture first, so filtering has no seams at tile edges
        val source = Bitmap.createBitmap(src.width().coerceAtLeast(1), src.height().coerceAtLeast(1), config)
        Canvas(source).apply {
            translate(-src.left.toFloat(), -src.top.toFloat())
            for ((k, tile) in grid) drawBitmap(tile, (TileGrid.tx(k) * TileGrid.SIZE).toFloat(), (TileGrid.ty(k) * TileGrid.SIZE).toFloat(), null)
        }
        val bmp = Bitmap.createBitmap(out.width(), out.height(), config)
        if (!alphaOnly) bmp.setHasAlpha(true)
        val c = Canvas(bmp)
        c.translate(-out.left.toFloat(), -out.top.toFloat())
        c.concat(m)
        c.drawBitmap(source, src.left.toFloat(), src.top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return bmp to out
    }

    /** Area a transform's result may cover: the canvas plus a margin, so memory stays sane. */
    fun limitFor(docW: Int, docH: Int): Rect {
        val m = (max(docW, docH) * 0.25f).roundToInt()
        return Rect(-m, -m, docW + m, docH + m)
    }

    /** Exact bounds of the non-transparent pixels in [grid] (layer-local), or null when empty. */
    fun contentBounds(grid: Map<Long, Bitmap>): Rect? {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        val n = TileGrid.SIZE
        val px = IntArray(n * n)
        for ((k, tile) in grid) {
            val ox = TileGrid.tx(k) * n
            val oy = TileGrid.ty(k) * n
            // Skip tiles that can't widen what's found so far
            if (ox >= l && oy >= t && ox + n <= r && oy + n <= b) continue
            tile.getPixels(px, 0, n, 0, 0, n, n)
            var tl = n
            var tt = n
            var tr = -1
            var tb = -1
            for (y in 0 until n) {
                val row = y * n
                for (x in 0 until n) {
                    if ((px[row + x] ushr 24) > 2) {
                        if (x < tl) tl = x
                        if (x > tr) tr = x
                        if (y < tt) tt = y
                        if (y > tb) tb = y
                    }
                }
            }
            if (tr < 0) continue
            l = min(l, ox + tl)
            t = min(t, oy + tt)
            r = max(r, ox + tr + 1)
            b = max(b, oy + tb + 1)
        }
        return if (r < 0 && l == Int.MAX_VALUE) null else Rect(l, t, r, b)
    }
}
