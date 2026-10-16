package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Warp, Puppet Warp and Liquify all stretch a layer's picture over a grid of points (a mesh) and
// move the points: Warp from a 4×4 grid of curve controls, Puppet Warp from pins, Liquify with
// brushes. The picture is drawn with Canvas.drawBitmapMesh, on screen and at full size.

enum class WarpKind(val label: String) { WARP("Warp"), PUPPET("Puppet Warp"), LIQUIFY("Liquify") }

enum class LiquifyTool(val label: String) {
    PUSH("Push"), BLOAT("Bloat"), PUCKER("Pucker"), TWIRL_RIGHT("Twirl ↻"), TWIRL_LEFT("Twirl ↺"), SMOOTH("Smooth"), RESTORE("Restore");

    /** Works while the finger is held still (the others only when it moves). */
    val continuous: Boolean get() = this != PUSH
}

enum class WarpPreset(val label: String) { CUSTOM("Custom"), ARC("Arc"), BULGE("Bulge"), FLAG("Flag"), WAVE("Wave"), SQUEEZE("Squeeze"), INFLATE("Inflate") }

/**
 * A grid of [cols]×[rows] cells over canvas area [area]. [pts] are where its points are now
 * (canvas pixels, x then y for each point, row by row); [rest] where they started.
 */
class WarpMesh(val area: RectF, val cols: Int, val rows: Int) {
    val stride = cols + 1
    val count = (cols + 1) * (rows + 1)
    val rest = FloatArray(count * 2).also { a ->
        for (j in 0..rows) for (i in 0..cols) {
            val k = (j * stride + i) * 2
            a[k] = area.left + area.width() * i / cols
            a[k + 1] = area.top + area.height() * j / rows
        }
    }
    val pts: FloatArray = rest.copyOf()

    /** Width of a cell (canvas pixels). */
    val cell: Float get() = max(area.width() / cols, area.height() / rows)

    fun isBorder(i: Int, j: Int) = i == 0 || j == 0 || i == cols || j == rows

    /** Where the points reach now (canvas pixels). */
    fun bounds(): RectF {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var k = 0
        while (k < pts.size) {
            val x = pts[k]
            val y = pts[k + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
            k += 2
        }
        return RectF(l, t, r, b)
    }

    /** The point nearest (x, y) now, as (column, row), and how far it is. */
    fun nearest(x: Float, y: Float): Triple<Int, Int, Float> {
        var best = 0
        var bd = Float.MAX_VALUE
        var k = 0
        while (k < pts.size) {
            val d = (pts[k] - x) * (pts[k] - x) + (pts[k + 1] - y) * (pts[k + 1] - y)
            if (d < bd) {
                bd = d
                best = k / 2
            }
            k += 2
        }
        return Triple(best % stride, best / stride, sqrt(bd))
    }

    val isUnchanged: Boolean get() = pts.contentEquals(rest)

    /** At least as far as any point has moved from where it started (so brushes know where to look). */
    var maxShift = 0f

    /** Works out [maxShift] again after the points were replaced. */
    fun refreshShift() {
        var m = 0f
        var k = 0
        while (k < pts.size) {
            m = max(m, max(abs(pts[k] - rest[k]), abs(pts[k + 1] - rest[k + 1])))
            k += 2
        }
        maxShift = m
    }

    /**
     * The columns and rows of points that could be within [r] of (x, y) now: those that started
     * within [r] + [maxShift] (inside the edge, which never moves). Null when there are none.
     */
    fun window(x: Float, y: Float, r: Float): IntArray? {
        val reach = r + maxShift
        val cw = area.width() / cols
        val ch = area.height() / rows
        val i0 = kotlin.math.floor((x - reach - area.left) / cw).toInt().coerceAtLeast(1)
        val i1 = ceil((x + reach - area.left) / cw).toInt().coerceAtMost(cols - 1)
        val j0 = kotlin.math.floor((y - reach - area.top) / ch).toInt().coerceAtLeast(1)
        val j1 = ceil((y + reach - area.top) / ch).toInt().coerceAtMost(rows - 1)
        return if (i0 > i1 || j0 > j1) null else intArrayOf(i0, i1, j0, j1)
    }

    /**
     * Where the spot now at (x, y) was before warping: found in the warped cell that holds it
     * (searched around the nearest point). Null when it isn't on the mesh.
     */
    fun restOf(x: Float, y: Float): PointF? {
        val (ni, nj, _) = nearest(x, y)
        for (j in (nj - 2).coerceAtLeast(0)..(nj + 1).coerceAtMost(rows - 1)) {
            for (i in (ni - 2).coerceAtLeast(0)..(ni + 1).coerceAtMost(cols - 1)) {
                val a = j * stride + i
                val b = a + 1
                val c = a + stride
                val d = c + 1
                // drawBitmapMesh splits each cell along a–d: (a, c, d) and (a, d, b)
                for (tri in arrayOf(intArrayOf(a, c, d), intArrayOf(a, d, b))) {
                    val w = barycentric(x, y, tri) ?: continue
                    var rx = 0f
                    var ry = 0f
                    for (n in 0..2) {
                        rx += rest[tri[n] * 2] * w[n]
                        ry += rest[tri[n] * 2 + 1] * w[n]
                    }
                    return PointF(rx, ry)
                }
            }
        }
        return null
    }

    private fun barycentric(x: Float, y: Float, t: IntArray): FloatArray? {
        val x0 = pts[t[0] * 2]; val y0 = pts[t[0] * 2 + 1]
        val x1 = pts[t[1] * 2]; val y1 = pts[t[1] * 2 + 1]
        val x2 = pts[t[2] * 2]; val y2 = pts[t[2] * 2 + 1]
        val det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(det) < 1e-6f) return null
        val l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det
        val l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
        val l2 = 1f - l0 - l1
        val e = -1e-3f
        return if (l0 >= e && l1 >= e && l2 >= e) floatArrayOf(l0, l1, l2) else null
    }

    companion object {
        /** A mesh over [area] with cells about [targetCell] pixels across (at least 4 and at most [maxCells] along a side). */
        fun over(area: RectF, targetCell: Float, maxCells: Int): WarpMesh {
            val c = (area.width() / targetCell).roundToInt().coerceIn(4, maxCells)
            val r = (area.height() / targetCell).roundToInt().coerceIn(4, maxCells)
            return WarpMesh(area, c, r)
        }
    }
}

object WarpMath {

    // ------------------------------------------------------------ Warp: a bicubic curve patch

    /** The 16 controls of an unwarped patch over [area] (x, y each; row by row). */
    fun restControls(area: RectF): FloatArray = FloatArray(32).also { c ->
        for (j in 0..3) for (i in 0..3) {
            c[(j * 4 + i) * 2] = area.left + area.width() * i / 3f
            c[(j * 4 + i) * 2 + 1] = area.top + area.height() * j / 3f
        }
    }

    private fun bern(i: Int, t: Float): Float {
        val s = 1f - t
        return when (i) {
            0 -> s * s * s
            1 -> 3f * t * s * s
            2 -> 3f * t * t * s
            else -> t * t * t
        }
    }

    /** A point on the patch at (u, v), each 0..1. */
    fun patchPoint(ctrl: FloatArray, u: Float, v: Float, out: FloatArray = FloatArray(2)): FloatArray {
        var x = 0f
        var y = 0f
        for (j in 0..3) {
            val bv = bern(j, v)
            for (i in 0..3) {
                val w = bern(i, u) * bv
                x += ctrl[(j * 4 + i) * 2] * w
                y += ctrl[(j * 4 + i) * 2 + 1] * w
            }
        }
        out[0] = x
        out[1] = y
        return out
    }

    /** Puts every mesh point where the patch [ctrl] says. */
    fun applyPatch(ctrl: FloatArray, mesh: WarpMesh) {
        val bu = Array(mesh.cols + 1) { i -> FloatArray(4) { k -> bern(k, i / mesh.cols.toFloat()) } }
        val bv = Array(mesh.rows + 1) { j -> FloatArray(4) { k -> bern(k, j / mesh.rows.toFloat()) } }
        for (j in 0..mesh.rows) for (i in 0..mesh.cols) {
            var x = 0f
            var y = 0f
            for (b in 0..3) for (a in 0..3) {
                val w = bu[i][a] * bv[j][b]
                x += ctrl[(b * 4 + a) * 2] * w
                y += ctrl[(b * 4 + a) * 2 + 1] * w
            }
            val k = (j * mesh.stride + i) * 2
            mesh.pts[k] = x
            mesh.pts[k + 1] = y
        }
    }

    /**
     * The patch [start] with its surface at (u, v) dragged by (dx, dy): every control moves in
     * proportion to how much it shapes that spot, so the spot under the finger follows it exactly.
     */
    fun dragSurface(start: FloatArray, u: Float, v: Float, dx: Float, dy: Float): FloatArray {
        val w = FloatArray(16) { k -> bern(k % 4, u) * bern(k / 4, v) }
        val sum2 = w.sumOf { (it * it).toDouble() }.toFloat().coerceAtLeast(1e-6f)
        return FloatArray(32) { n ->
            val k = n / 2
            start[n] + (if (n % 2 == 0) dx else dy) * w[k] / sum2
        }
    }

    /** A ready-made warp of the patch over [area]; [bend] −1..1. */
    fun preset(p: WarpPreset, bend: Float, area: RectF): FloatArray {
        val c = restControls(area)
        val w = area.width()
        val h = area.height()
        // A cubic curve whose two middle controls are lifted by k bulges by 3/4 k in the middle
        val lift = 4f / 3f
        for (j in 0..3) for (i in 0..3) {
            val k = (j * 4 + i) * 2
            val inner = i == 1 || i == 2
            val fj = 1f - 2f * j / 3f // 1 at the top row, −1 at the bottom
            val fi = 1f - 2f * i / 3f // 1 at the left, −1 at the right
            when (p) {
                WarpPreset.CUSTOM -> Unit
                WarpPreset.ARC -> if (inner) c[k + 1] -= bend * h * 0.45f * lift
                WarpPreset.BULGE -> if (inner) c[k + 1] -= bend * h * 0.35f * lift * fj
                WarpPreset.FLAG -> c[k + 1] += bend * h * 0.3f * lift * (if (i == 1) -1f else if (i == 2) 1f else 0f)
                WarpPreset.WAVE -> c[k + 1] += bend * h * 0.3f * lift * (if (i == 1) -1f else if (i == 2) 1f else 0f) * fj
                WarpPreset.SQUEEZE -> if (j == 1 || j == 2) c[k] += bend * w * 0.3f * lift * fi
                WarpPreset.INFLATE -> if (inner && (j == 1 || j == 2)) {
                    c[k] += (c[k] - area.centerX()) * bend * 1.2f
                    c[k + 1] += (c[k + 1] - area.centerY()) * bend * 1.2f
                }
            }
        }
        return c
    }

    // ------------------------------------------------------------ Puppet Warp: pins, rigid as can be

    /**
     * Moves every mesh point by moving-least-squares "as rigid as possible" deformation: pins
     * that started at [from] are now at [to] (x, y each). Points near a pin follow it closely;
     * between pins the picture bends rather than stretches. [stiffness] 1..3: how locally pins act.
     */
    fun puppet(mesh: WarpMesh, from: FloatArray, to: FloatArray, stiffness: Float = 1f) {
        val n = from.size / 2
        val rest = mesh.rest
        val out = mesh.pts
        if (n == 0) {
            rest.copyInto(out)
            return
        }
        if (n == 1) {
            val dx = to[0] - from[0]
            val dy = to[1] - from[1]
            var k = 0
            while (k < rest.size) {
                out[k] = rest[k] + dx
                out[k + 1] = rest[k + 1] + dy
                k += 2
            }
            return
        }
        val eps = (mesh.cell * 0.25f).let { it * it }.coerceAtLeast(1e-3f)
        val w = FloatArray(n)
        var k = 0
        while (k < rest.size) {
            val vx = rest[k]
            val vy = rest[k + 1]
            var sw = 0f
            var pxs = 0f
            var pys = 0f
            var qxs = 0f
            var qys = 0f
            var exact = -1
            for (i in 0 until n) {
                val dx = from[i * 2] - vx
                val dy = from[i * 2 + 1] - vy
                val d2 = dx * dx + dy * dy
                if (d2 < 1e-4f) exact = i
                val wi = 1f / (if (stiffness == 1f) d2 + eps else Math.pow((d2 + eps).toDouble(), stiffness.toDouble()).toFloat())
                w[i] = wi
                sw += wi
                pxs += wi * from[i * 2]
                pys += wi * from[i * 2 + 1]
                qxs += wi * to[i * 2]
                qys += wi * to[i * 2 + 1]
            }
            if (exact >= 0) {
                out[k] = to[exact * 2]
                out[k + 1] = to[exact * 2 + 1]
                k += 2
                continue
            }
            pxs /= sw
            pys /= sw
            qxs /= sw
            qys /= sw
            // Weights that add up to 1, so far-off points (tiny weights at high stiffness) keep their rotation
            for (i in 0 until n) w[i] /= sw
            // M = Σ w q̂ conj(p̂) (as complex numbers): the best rotation from the pins' starting to current places
            var mr = 0f
            var mi = 0f
            for (i in 0 until n) {
                val px = from[i * 2] - pxs
                val py = from[i * 2 + 1] - pys
                val qx = to[i * 2] - qxs
                val qy = to[i * 2 + 1] - qys
                mr += w[i] * (qx * px + qy * py)
                mi += w[i] * (qy * px - qx * py)
            }
            val len = hypot(mr, mi)
            val dx = vx - pxs
            val dy = vy - pys
            if (len < 1e-12f) {
                out[k] = dx + qxs
                out[k + 1] = dy + qys
            } else {
                val cr = mr / len
                val ci = mi / len
                out[k] = cr * dx - ci * dy + qxs
                out[k + 1] = ci * dx + cr * dy + qys
            }
            k += 2
        }
    }

    // ------------------------------------------------------------ Liquify brushes

    private fun falloff(t: Float): Float {
        if (t >= 1f) return 0f
        val s = 1f - t * t
        return s * s
    }

    /**
     * One dab of a Liquify brush at (cx, cy) with radius [r] and [strength] 0..1. Push moves the
     * picture by (dx, dy); the others work a little each time they're called. The mesh's edge stays
     * put. Only points near the brush are looked at. Returns whether anything moved.
     */
    fun liquify(mesh: WarpMesh, tool: LiquifyTool, cx: Float, cy: Float, r: Float, strength: Float, dx: Float = 0f, dy: Float = 0f): Boolean {
        val s = strength.coerceIn(0.01f, 1f)
        val win = mesh.window(cx, cy, r) ?: return false
        if (tool == LiquifyTool.SMOOTH) return smooth(mesh, win, cx, cy, r, s)
        val p = mesh.pts
        val rest = mesh.rest
        val r2 = r * r
        var any = false
        var shift = mesh.maxShift
        for (j in win[2]..win[3]) {
            for (i in win[0]..win[1]) {
                val k = (j * mesh.stride + i) * 2
                val ox = p[k] - cx
                val oy = p[k + 1] - cy
                val d2 = ox * ox + oy * oy
                if (d2 >= r2) continue
                val f = falloff(sqrt(d2) / r)
                if (f <= 0f) continue
                any = true
                when (tool) {
                    LiquifyTool.PUSH -> {
                        p[k] += dx * f * s
                        p[k + 1] += dy * f * s
                    }
                    LiquifyTool.BLOAT -> {
                        val a = 0.012f * s * f
                        p[k] += ox * a
                        p[k + 1] += oy * a
                    }
                    LiquifyTool.PUCKER -> {
                        val a = 0.012f * s * f
                        p[k] -= ox * a
                        p[k + 1] -= oy * a
                    }
                    LiquifyTool.TWIRL_RIGHT, LiquifyTool.TWIRL_LEFT -> {
                        val ang = 0.025f * s * f * (if (tool == LiquifyTool.TWIRL_RIGHT) 1f else -1f)
                        val c = cos(ang)
                        val sn = sin(ang)
                        p[k] = cx + ox * c - oy * sn
                        p[k + 1] = cy + ox * sn + oy * c
                    }
                    LiquifyTool.RESTORE -> {
                        val a = (0.08f * s * f).coerceAtMost(1f)
                        p[k] += (rest[k] - p[k]) * a
                        p[k + 1] += (rest[k + 1] - p[k + 1]) * a
                    }
                    LiquifyTool.SMOOTH -> Unit
                }
                shift = max(shift, max(abs(p[k] - rest[k]), abs(p[k + 1] - rest[k + 1])))
            }
        }
        mesh.maxShift = shift
        return any
    }

    /** Push dragged from (x0, y0) to (x1, y1), in small enough steps that the picture doesn't tear. */
    fun push(mesh: WarpMesh, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, strength: Float): Boolean {
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 0.01f) return false
        val steps = ceil(dist / max(1f, r * 0.2f)).toInt().coerceIn(1, 200)
        var any = false
        for (n in 0 until steps) {
            val a = n / steps.toFloat()
            val cx = x0 + (x1 - x0) * a
            val cy = y0 + (y1 - y0) * a
            any = liquify(mesh, LiquifyTool.PUSH, cx, cy, r, strength, (x1 - x0) / steps, (y1 - y0) / steps) || any
        }
        return any
    }

    /** Evens out how much nearby points have been moved. */
    private fun smooth(mesh: WarpMesh, win: IntArray, cx: Float, cy: Float, r: Float, s: Float): Boolean {
        val p = mesh.pts
        val rest = mesh.rest
        val r2 = r * r
        // New places are worked out from the old ones, then written
        val cols = win[1] - win[0] + 1
        val rows = win[3] - win[2] + 1
        val next = FloatArray(cols * rows * 2)
        val touched = BooleanArray(cols * rows)
        var any = false
        for (j in win[2]..win[3]) for (i in win[0]..win[1]) {
            val k = (j * mesh.stride + i) * 2
            val ox = p[k] - cx
            val oy = p[k + 1] - cy
            val d2 = ox * ox + oy * oy
            if (d2 >= r2) continue
            val f = falloff(sqrt(d2) / r)
            var ax = 0f
            var ay = 0f
            for ((di, dj) in NEIGHBORS) {
                val kk = ((j + dj) * mesh.stride + (i + di)) * 2
                ax += p[kk] - rest[kk]
                ay += p[kk + 1] - rest[kk + 1]
            }
            ax /= 4f
            ay /= 4f
            val a = (0.15f * s * f).coerceAtMost(1f)
            val n = (j - win[2]) * cols + (i - win[0])
            next[n * 2] = p[k] + (rest[k] + ax - p[k]) * a
            next[n * 2 + 1] = p[k + 1] + (rest[k + 1] + ay - p[k + 1]) * a
            touched[n] = true
            any = true
        }
        for (j in win[2]..win[3]) for (i in win[0]..win[1]) {
            val n = (j - win[2]) * cols + (i - win[0])
            if (!touched[n]) continue
            val k = (j * mesh.stride + i) * 2
            p[k] = next[n * 2]
            p[k + 1] = next[n * 2 + 1]
        }
        return any
    }

    private val NEIGHBORS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    // ------------------------------------------------------------ drawing

    /**
     * Draws [source] (covering the mesh's area) stretched over the mesh, onto [canvas] in canvas
     * pixels.
     */
    fun draw(canvas: Canvas, source: Bitmap, cols: Int, rows: Int, pts: FloatArray, paint: Paint = MESH_PAINT) {
        canvas.drawBitmapMesh(source, cols, rows, pts, 0, null, 0, paint)
    }

    private val MESH_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * The full-size result for one layer: its tiles ([alphaOnly] for a mask) in layer-local area
     * [src] (the area the mesh started over, less the layer's position) stretched over the mesh's
     * points [pts] (canvas pixels).
     * Returns the picture and where it goes on the canvas (within [limit]), or null if nothing's left.
     */
    fun render(grid: Map<Long, Bitmap>, alphaOnly: Boolean, src: Rect, pts: FloatArray, cols: Int, rows: Int, limit: Rect): Pair<Bitmap, Rect>? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var k = 0
        while (k < pts.size) {
            l = min(l, pts[k]); r = max(r, pts[k])
            t = min(t, pts[k + 1]); b = max(b, pts[k + 1])
            k += 2
        }
        val out = Rect(floor(l).toInt() - 1, floor(t).toInt() - 1, ceil(r).toInt() + 1, ceil(b).toInt() + 1)
        if (!out.intersect(limit) || out.isEmpty) return null
        val config = if (alphaOnly) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
        // The source as one picture, so filtering has no seams at tile edges
        val source = Bitmap.createBitmap(src.width().coerceAtLeast(1), src.height().coerceAtLeast(1), config)
        Canvas(source).apply {
            translate(-src.left.toFloat(), -src.top.toFloat())
            for ((key, tile) in grid) drawBitmap(tile, (TileGrid.tx(key) * TileGrid.SIZE).toFloat(), (TileGrid.ty(key) * TileGrid.SIZE).toFloat(), null)
        }
        val bmp = Bitmap.createBitmap(out.width(), out.height(), config)
        if (!alphaOnly) bmp.setHasAlpha(true)
        val c = Canvas(bmp)
        c.translate(-out.left.toFloat(), -out.top.toFloat())
        c.drawBitmapMesh(source, cols, rows, pts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return bmp to out
    }

    /**
     * The full-size mask after warping: the warped part of [grid] (layer-local area [src], layer at
     * ([lx], [ly])) over the rest of the mask, which stays where it was. Canvas pixels, within [limit].
     */
    fun renderMask(grid: Map<Long, Bitmap>, src: Rect, lx: Int, ly: Int, pts: FloatArray, cols: Int, rows: Int, limit: Rect): Pair<Bitmap, Rect>? {
        val warped = render(grid, true, src, pts, cols, rows, limit)
        val all = TileGrid(alphaOnly = true).also { it.tiles.putAll(grid) }.tileBounds()?.apply { offset(lx, ly) }
        val out = Rect(warped?.second ?: all ?: return null)
        if (all != null) out.union(all)
        if (!out.intersect(limit) || out.isEmpty) {
            warped?.first?.recycle()
            return null
        }
        val bmp = Bitmap.createBitmap(out.width(), out.height(), Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.translate(-out.left.toFloat(), -out.top.toFloat())
        // The mask as it was, without the part that was warped (where it was, and where it went)...
        for ((key, tile) in grid) c.drawBitmap(tile, (lx + TileGrid.tx(key) * TileGrid.SIZE).toFloat(), (ly + TileGrid.ty(key) * TileGrid.SIZE).toFloat(), null)
        val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        c.drawRect(Rect(src).apply { offset(lx, ly) }, clear)
        val solid = Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8).apply { eraseColor(android.graphics.Color.BLACK) }
        c.drawBitmapMesh(solid, cols, rows, pts, 0, null, 0, clear)
        solid.recycle()
        // ...and that part, warped
        warped?.let { (w, area) ->
            c.drawBitmap(w, area.left.toFloat(), area.top.toFloat(), null)
            w.recycle()
        }
        return bmp to out
    }

    /** Whether the warp moved things by less than a tenth of a pixel everywhere. */
    fun nearlyUnchanged(mesh: WarpMesh): Boolean = nearlyUnchanged(mesh.pts, mesh.rest)

    fun nearlyUnchanged(pts: FloatArray, rest: FloatArray): Boolean {
        for (k in pts.indices) if (abs(pts[k] - rest[k]) > 0.1f) return false
        return true
    }
}
