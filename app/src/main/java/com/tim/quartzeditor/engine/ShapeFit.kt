package com.tim.quartzeditor.engine

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draw and hold (like Procreate's QuickShape): what shape a hand-drawn stroke was meant to be — a
 * straight line, a few straight lines, an arc, an ellipse, a rectangle or a triangle — and that shape
 * made clean. [perfect]: a line at 45° steps, a circle, a square, an even triangle.
 */
object ShapeFit {
    class Result(val points: List<PointF>, val closed: Boolean, val kind: String)

    /** The shape [pts] look like (null: none of them, or the stroke is shorter than [minLength]). */
    fun fit(pts: List<PointF>, minLength: Float = 16f): Result? = fitLoose(pts, minLength)

    /** [r] (fitted to [pts]) made perfect: a circle, a square, an even triangle, a line at 45° steps. */
    fun perfect(pts: List<PointF>, r: Result): Result {
        return when (r.kind) {
            "line" -> openShape(pts, 1f, true)?.takeIf { it.kind == "line" } ?: r
            "ellipse" -> ellipse(pts, true) ?: r
            "rectangle" -> rectangle(pts, true) ?: r
            "triangle" -> triangle(r.points, true)
            else -> r
        }
    }

    private fun fitLoose(pts: List<PointF>, minLength: Float): Result? {
        if (pts.size < 3) return null
        var length = 0f
        for (i in 1 until pts.size) length += dist(pts[i - 1], pts[i])
        if (length < max(16f, minLength)) return null
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val diag = hypot(maxX - minX, maxY - minY).coerceAtLeast(1f)
        val a = pts.first()
        val b = pts.last()
        val gap = dist(a, b)
        val closed = gap < diag * 0.2f && length > diag * 1.8f
        return if (closed) closedShape(pts, diag, false) else openShape(pts, diag, false)
    }

    // ------------------------------------------------------------ open: line or lines

    private fun openShape(pts: List<PointF>, diag: Float, perfect: Boolean): Result? {
        val a = pts.first()
        var b = pts.last()
        val ab = dist(a, b)
        val worst = pts.maxOf { segDist(it, a, b) }
        if (ab > 0f && worst < ab * 0.07f) {
            if (perfect) {
                // 45° steps from where it started, as long as it was
                val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                val snapped = Math.round(ang / (Math.PI / 4)) * (Math.PI / 4)
                b = PointF(a.x + (cos(snapped) * ab).toFloat(), a.y + (sin(snapped) * ab).toFloat())
            }
            return Result(listOf(a, b), false, "line")
        }
        // A curve that's part of a circle, or a few straight lines with corners: whichever is closer
        val arc = arc(pts, diag)
        val arcErr = arc?.let { averageError(pts, it.points, false) } ?: Float.MAX_VALUE
        val simple = simplify(pts, diag * 0.045f)
        val linesErr = if (simple.size in 3..7 && sharpCorners(simple, diag)) averageError(pts, simple, false) else Float.MAX_VALUE
        if (arc != null && arcErr < diag * 0.03f && arcErr <= linesErr * 1.15f) return arc
        if (linesErr < diag * 0.03f) return Result(simple, false, "lines")
        return null
    }

    /** A closed outline's corners without the ones it goes almost straight through (turning under 25°). */
    private fun dropStraight(loop: List<PointF>): List<PointF> {
        var p = loop
        while (p.size > 3) {
            var removed = false
            for (i in p.indices) {
                val a = p[(i - 1 + p.size) % p.size]
                val b = p[i]
                val c = p[(i + 1) % p.size]
                val a1 = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                val a2 = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble())
                var d = abs(a2 - a1)
                if (d > Math.PI) d = 2 * Math.PI - d
                if (d < Math.toRadians(25.0)) {
                    p = p.filterIndexed { k, _ -> k != i }
                    removed = true
                    break
                }
            }
            if (!removed) break
        }
        return p
    }

    /** Real corners (each turns 40° or more) between sides that aren't tiny: not a curve cut into pieces. */
    private fun sharpCorners(p: List<PointF>, diag: Float): Boolean {
        for (i in 1 until p.size) if (dist(p[i - 1], p[i]) < diag * 0.1f) return false
        for (i in 1 until p.lastIndex) {
            val a1 = atan2((p[i].y - p[i - 1].y).toDouble(), (p[i].x - p[i - 1].x).toDouble())
            val a2 = atan2((p[i + 1].y - p[i].y).toDouble(), (p[i + 1].x - p[i].x).toDouble())
            var d = abs(a2 - a1)
            if (d > Math.PI) d = 2 * Math.PI - d
            if (d < Math.toRadians(40.0)) return false
        }
        return true
    }

    /** The circle closest to the points (Kåsa's fit), as the arc they go round on it. */
    private fun arc(pts: List<PointF>, diag: Float): Result? {
        // Least squares for x² + y² + Dx + Ey + F = 0 (centred first, for precision)
        val mx = pts.sumOf { it.x.toDouble() } / pts.size
        val my = pts.sumOf { it.y.toDouble() } / pts.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (p in pts) {
            val x = p.x - mx
            val y = p.y - my
            val z = x * x + y * y
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y
            sxz += x * z; syz += y * z; sz += z
        }
        val n = pts.size.toDouble()
        // [sxx sxy sx; sxy syy sy; sx sy n] · [D E F] = -[sxz syz sz]
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx, -sxz), doubleArrayOf(sxy, syy, sy, -syz), doubleArrayOf(sx, sy, n, -sz))
        for (c in 0 until 3) {
            var piv = c
            for (r in c + 1 until 3) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-9) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in 0 until 3) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c until 4) m[r][k] -= f * m[c][k]
            }
        }
        val d = m[0][3] / m[0][0]
        val e = m[1][3] / m[1][1]
        val f = m[2][3] / m[2][2]
        val cx = -d / 2
        val cy = -e / 2
        val r2 = cx * cx + cy * cy - f
        if (r2 <= 0) return null
        val r = sqrt(r2)
        // Nearly straight (a huge circle) is a line's job
        if (r > diag * 4) return null
        // How far round it goes, the way it was drawn
        var sweep = 0.0
        var prev = atan2(pts[0].y - my - cy, pts[0].x - mx - cx)
        val a0 = prev
        for (p in pts) {
            val a = atan2(p.y - my - cy, p.x - mx - cx)
            var da = a - prev
            if (da > Math.PI) da -= 2 * Math.PI else if (da < -Math.PI) da += 2 * Math.PI
            sweep += da
            prev = a
        }
        if (abs(sweep) < 0.35 || abs(sweep) > 2 * Math.PI * 0.95) return null
        val steps = max(8, (abs(sweep) / (2 * Math.PI) * 96).roundToInt())
        val out = (0..steps).map { i ->
            val a = a0 + sweep * i / steps
            PointF((mx + cx + cos(a) * r).toFloat(), (my + cy + sin(a) * r).toFloat())
        }
        return Result(out, false, "arc")
    }

    // ------------------------------------------------------------ closed: ellipse, rectangle, triangle

    private fun closedShape(pts: List<PointF>, diag: Float, perfect: Boolean): Result? {
        val ell = ellipse(pts, perfect)
        val rect = rectangle(pts, perfect)
        // Its corners (where it started may be partway along a side: not a corner)
        val loop = dropStraight(simplify(pts + pts.first(), diag * 0.09f).dropLast(1))
        val tri = if (loop.size == 3) triangle(loop, perfect) else null
        val candidates = listOfNotNull(
            ell?.let { it to averageError(pts, it.points, true) },
            rect?.let { it to averageError(pts, it.points, true) * (if (loop.size == 4) 0.8f else 1f) },
            tri?.let { it to averageError(pts, it.points, true) * 0.85f },
        )
        val best = candidates.minByOrNull { it.second } ?: return null
        return if (best.second < diag * 0.06f) best.first else null
    }

    private fun ellipse(pts: List<PointF>, perfect: Boolean): Result? {
        // Each point counts by how much of the outline it stands for (hands slow down round the
        // tight ends, which would otherwise count more)
        val wts = DoubleArray(pts.size) { i ->
            val a = pts[if (i == 0) pts.lastIndex else i - 1]
            val b = pts[if (i == pts.lastIndex) 0 else i + 1]
            (dist(a, pts[i]) + dist(pts[i], b)).toDouble() / 2 + 1e-3
        }
        val n = wts.sum()
        var cxd = 0.0
        var cyd = 0.0
        for (i in pts.indices) {
            cxd += pts[i].x * wts[i]
            cyd += pts[i].y * wts[i]
        }
        val cx = (cxd / n).toFloat()
        val cy = (cyd / n).toFloat()
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val dx = (p.x - cx).toDouble()
            val dy = (p.y - cy).toDouble()
            sxx += dx * dx * wts[i]
            syy += dy * dy * wts[i]
            sxy += dx * dy * wts[i]
        }
        sxx /= n
        syy /= n
        sxy /= n
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val c = cos(angle)
        val s = sin(angle)
        // Points round an ellipse spend about half their time at each axis: radius = √(2 × variance)
        var vx = 0.0
        var vy = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val dx = (p.x - cx).toDouble()
            val dy = (p.y - cy).toDouble()
            val u = dx * c + dy * s
            val v = -dx * s + dy * c
            vx += u * u * wts[i]
            vy += v * v * wts[i]
        }
        var rx = sqrt(2 * vx / n).toFloat()
        var ry = sqrt(2 * vy / n).toFloat()
        if (rx < 2f || ry < 2f) return null
        if (perfect) {
            val r = (rx + ry) / 2f
            rx = r
            ry = r
        }
        val out = (0 until 96).map { i ->
            val t = i / 96.0 * 2 * Math.PI
            val u = cos(t) * rx
            val v = sin(t) * ry
            PointF((cx + u * c - v * s).toFloat(), (cy + u * s + v * c).toFloat())
        }
        return Result(out, true, if (perfect) "circle" else "ellipse")
    }

    /** The smallest box (at any angle) around the points, as a rectangle. */
    private fun rectangle(pts: List<PointF>, perfect: Boolean): Result? {
        var best: FloatArray? = null
        var deg = 0
        while (deg < 90) {
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var lo2 = Float.MAX_VALUE
            var hi2 = -Float.MAX_VALUE
            for (p in pts) {
                val u = p.x * c + p.y * s
                val v = -p.x * s + p.y * c
                lo = min(lo, u)
                hi = max(hi, u)
                lo2 = min(lo2, v)
                hi2 = max(hi2, v)
            }
            val area = (hi - lo) * (hi2 - lo2)
            if (best == null || area < best[0]) best = floatArrayOf(area, deg.toFloat(), lo, hi, lo2, hi2)
            deg += 2
        }
        val b = best ?: return null
        val a = Math.toRadians(b[1].toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        var (u0, u1, v0, v1) = listOf(b[2], b[3], b[4], b[5])
        if (u1 - u0 < 3f || v1 - v0 < 3f) return null
        if (perfect) {
            val side = ((u1 - u0) + (v1 - v0)) / 2f
            val mu = (u0 + u1) / 2f
            val mv = (v0 + v1) / 2f
            u0 = mu - side / 2f
            u1 = mu + side / 2f
            v0 = mv - side / 2f
            v1 = mv + side / 2f
        }
        fun back(u: Float, v: Float) = PointF(u * c - v * s, u * s + v * c)
        return Result(listOf(back(u0, v0), back(u1, v0), back(u1, v1), back(u0, v1)), true, if (perfect) "square" else "rectangle")
    }

    private fun triangle(corners: List<PointF>, perfect: Boolean): Result {
        if (!perfect) return Result(corners, true, "triangle")
        // Even: the same middle and size, pointing the way the first corner does
        val cx = corners.sumOf { it.x.toDouble() }.toFloat() / 3f
        val cy = corners.sumOf { it.y.toDouble() }.toFloat() / 3f
        val r = corners.map { dist(it, PointF(cx, cy)) }.average().toFloat()
        val a0 = atan2((corners[0].y - cy).toDouble(), (corners[0].x - cx).toDouble())
        val out = (0 until 3).map { i ->
            val a = a0 + i * 2 * Math.PI / 3
            PointF(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat())
        }
        return Result(out, true, "triangle")
    }

    // ------------------------------------------------------------ helpers

    /** How far, on average, the drawn points are from the shape's outline. */
    private fun averageError(pts: List<PointF>, shape: List<PointF>, closed: Boolean): Float {
        val segs = if (closed) shape.size else shape.size - 1
        if (segs <= 0) return Float.MAX_VALUE
        var total = 0.0
        for (p in pts) {
            var m = Float.MAX_VALUE
            for (i in 0 until segs) m = min(m, segDist(p, shape[i], shape[(i + 1) % shape.size]))
            total += m
        }
        return (total / pts.size).toFloat()
    }

    /** Douglas–Peucker: the points that keep the line within [tol]. */
    private fun simplify(pts: List<PointF>, tol: Float): List<PointF> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to pts.lastIndex)
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            if (j - i < 2) continue
            var best = -1
            var bestD = tol
            for (k in i + 1 until j) {
                val d = segDist(pts[k], pts[i], pts[j])
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

    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    private fun segDist(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
