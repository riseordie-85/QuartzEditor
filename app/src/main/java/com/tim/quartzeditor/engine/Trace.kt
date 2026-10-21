package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Selection to shape: the edge of what's selected (where it's at least half selected) traced into
 * outlines (marching squares), thinned to the points that matter (Douglas–Peucker, [tolerance]
 * canvas pixels), and given smooth curves through those points except at sharp corners.
 */
object SelectionTrace {
    /** The longest side traced at full detail; bigger selections are traced smaller (and scaled back up). */
    private const val MAX_SIDE = 2048

    /** Turns sharper than this (degrees) stay corners; gentler ones become smooth curves. */
    private const val CORNER_DEGREES = 55.0

    /**
     * The selection's outlines in canvas pixels, each a closed loop of pen points (with curve handles),
     * largest first. Holes are loops inside loops (fill them even-odd). Empty when nothing's selected.
     */
    fun outlines(sel: Selection, docW: Int, docH: Int, tolerance: Float = 1.25f): List<List<PenPoint>> {
        val b = sel.reach
        val area = Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
        // Outside the canvas there's nothing to keep
        if (!area.intersect(-2, -2, docW + 2, docH + 2) || area.width() < 1 || area.height() < 1) return emptyList()
        val s = min(1f, MAX_SIDE.toFloat() / max(area.width(), area.height()))
        val w = max(1, ceil(area.width() * s).toInt())
        val h = max(1, ceil(area.height() * s).toInt())
        // How selected each (scaled) pixel is, with an empty border so every outline closes
        val gw = w + 2
        val gh = h + 2
        val v = ByteArray(gw * gh)
        run {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            c.scale(w / area.width().toFloat(), h / area.height().toFloat())
            c.translate(-area.left.toFloat(), -area.top.toFloat())
            sel.mask().drawTo(c, 0f, 0f, android.graphics.Paint().apply { isFilterBitmap = true })
            val stride = bmp.rowBytes
            val buf = java.nio.ByteBuffer.allocate(stride * h)
            bmp.copyPixelsToBuffer(buf)
            bmp.recycle()
            val arr = buf.array()
            for (y in 0 until h) System.arraycopy(arr, y * stride, v, (y + 1) * gw + 1, w)
        }
        val loops = march(v, gw, gh)
        val sx = area.width() / w.toFloat()
        val sy = area.height() / h.toFloat()
        // Grid point (gx, gy) is the middle of scaled pixel (gx − 1, gy − 1)
        fun toCanvas(p: PointF) = PointF(area.left + (p.x - 0.5f) * sx, area.top + (p.y - 0.5f) * sy)
        val tol = tolerance * s
        val minArea = 4f
        return loops
            .map { simplify(it, tol) }
            .filter { it.size >= 3 && abs(signedArea(it)) >= minArea }
            .sortedByDescending { abs(signedArea(it)) }
            .map { loop -> smooth(loop.map(::toCanvas)) }
    }

    /** Value of grid point (x, y), 0..255. */
    private fun at(v: ByteArray, gw: Int, x: Int, y: Int): Int = v[y * gw + x].toInt() and 0xFF

    /**
     * Marching squares over the grid (inside = at least half, 128): closed loops through the points
     * where the edge crosses between grid points, in grid coordinates.
     */
    private fun march(v: ByteArray, gw: Int, gh: Int): List<List<PointF>> {
        // Edge ids: horizontal edge from (x, y) to (x + 1, y) is 2·(y·gw + x); vertical, to (x, y + 1), is that + 1
        fun hEdge(x: Int, y: Int) = 2 * (y * gw + x)
        fun vEdge(x: Int, y: Int) = 2 * (y * gw + x) + 1
        val links = HashMap<Int, IntArray>()
        fun link(a: Int, b: Int) {
            fun add(from: Int, to: Int) {
                val l = links[from]
                if (l == null) links[from] = intArrayOf(to, -1) else if (l[1] == -1 && l[0] != to) l[1] = to
            }
            add(a, b)
            add(b, a)
        }
        val inside = 128
        for (y in 0 until gh - 1) for (x in 0 until gw - 1) {
            val tl = at(v, gw, x, y) >= inside
            val tr = at(v, gw, x + 1, y) >= inside
            val br = at(v, gw, x + 1, y + 1) >= inside
            val bl = at(v, gw, x, y + 1) >= inside
            val case = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
            if (case == 0 || case == 15) continue
            val top = hEdge(x, y)
            val bottom = hEdge(x, y + 1)
            val left = vEdge(x, y)
            val right = vEdge(x + 1, y)
            when (case) {
                1, 14 -> link(left, bottom)
                2, 13 -> link(bottom, right)
                3, 12 -> link(left, right)
                4, 11 -> link(top, right)
                6, 9 -> link(top, bottom)
                7, 8 -> link(top, left)
                5, 10 -> {
                    // Two corners in, two out: the middle decides which pairs join
                    val mid = (at(v, gw, x, y) + at(v, gw, x + 1, y) + at(v, gw, x + 1, y + 1) + at(v, gw, x, y + 1)) / 4 >= inside
                    if ((case == 5) == mid) {
                        link(top, left)
                        link(bottom, right)
                    } else {
                        link(top, right)
                        link(left, bottom)
                    }
                }
            }
        }
        // Where an edge is crossed: between its two grid points, where the value passes 128
        fun point(id: Int): PointF {
            val cell = id / 2
            val x = cell % gw
            val y = cell / gw
            val horizontal = id % 2 == 0
            val a = at(v, gw, x, y).toFloat()
            val b = if (horizontal) at(v, gw, x + 1, y).toFloat() else at(v, gw, x, y + 1).toFloat()
            val t = if (abs(b - a) < 0.5f) 0.5f else ((inside - 0.5f - a) / (b - a)).coerceIn(0f, 1f)
            return if (horizontal) PointF(x + t, y.toFloat()) else PointF(x.toFloat(), y + t)
        }
        val seen = HashSet<Int>()
        val out = ArrayList<List<PointF>>()
        for (start in links.keys) {
            if (start in seen) continue
            val loop = ArrayList<PointF>()
            var prev = -1
            var cur = start
            while (true) {
                seen.add(cur)
                loop.add(point(cur))
                val l = links[cur] ?: break
                val next = if (l[0] != prev) l[0] else l[1]
                if (next == -1 || next == start) break
                if (next in seen) break
                prev = cur
                cur = next
            }
            if (loop.size >= 3) out.add(loop)
        }
        return out
    }

    private fun signedArea(p: List<PointF>): Float {
        var a = 0f
        for (i in p.indices) {
            val q = p[i]
            val r = p[(i + 1) % p.size]
            a += q.x * r.y - r.x * q.y
        }
        return a / 2f
    }

    /** A closed loop with only the points that keep it within [tol] of where it was. */
    private fun simplify(loop: List<PointF>, tol: Float): List<PointF> {
        if (loop.size < 8) return loop
        // Split at the point farthest from the first, simplify both halves
        val a = loop[0]
        var far = 0
        var farD = -1f
        for (i in loop.indices) {
            val d = hypot(loop[i].x - a.x, loop[i].y - a.y)
            if (d > farD) {
                farD = d
                far = i
            }
        }
        val first = dp(loop.subList(0, far + 1), tol)
        val second = dp(loop.subList(far, loop.size) + loop[0], tol)
        return first.dropLast(1) + second.dropLast(1)
    }

    /** Douglas–Peucker on an open line (iterative, so long outlines don't run out of stack). */
    private fun dp(pts: List<PointF>, tol: Float): List<PointF> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to pts.lastIndex)
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            if (j - i < 2) continue
            val p = pts[i]
            val q = pts[j]
            val dx = q.x - p.x
            val dy = q.y - p.y
            val len = hypot(dx, dy)
            var best = -1
            var bestD = tol
            for (k in i + 1 until j) {
                val r = pts[k]
                val d = if (len < 1e-4f) hypot(r.x - p.x, r.y - p.y) else abs(dx * (p.y - r.y) - dy * (p.x - r.x)) / len
                if (d > bestD) {
                    bestD = d
                    best = k
                }
            }
            if (best >= 0) {
                keep[best] = true
                stack.add(i to best)
                stack.add(best to j)
            }
        }
        return pts.filterIndexed { k, _ -> keep[k] }
    }

    /** Pen points through the loop's points: smooth curves, except where it turns sharply. */
    private fun smooth(loop: List<PointF>): List<PenPoint> {
        val n = loop.size
        val pts = loop.map { PenPoint(it.x, it.y) }
        val corner = BooleanArray(n) { i ->
            val p = loop[(i - 1 + n) % n]
            val q = loop[i]
            val r = loop[(i + 1) % n]
            val ax = q.x - p.x
            val ay = q.y - p.y
            val bx = r.x - q.x
            val by = r.y - q.y
            val la = sqrt(ax * ax + ay * ay)
            val lb = sqrt(bx * bx + by * by)
            if (la < 1e-4f || lb < 1e-4f) true else {
                val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
                Math.toDegrees(acos(cos.toDouble())) > CORNER_DEGREES
            }
        }
        return PenPoint.curvature(pts, { corner[it] }, closed = true)
    }
}
