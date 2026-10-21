package com.tim.quartzeditor.ui

import android.graphics.Matrix
import android.graphics.PointF
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.PenPoint
import com.tim.quartzeditor.engine.ShapeKind
import com.tim.quartzeditor.engine.ShapeSpec
import com.tim.quartzeditor.engine.toMatrix
import kotlin.math.hypot

// Point editing for pen shapes: drag points and curve handles, tap a point to pick it (then make
// it a corner or a curve, or delete it), tap the outline to add a point there.

/** What a finger grabbed while editing points. */
sealed class PenHit {
    abstract val index: Int
    data class Anchor(override val index: Int) : PenHit()
    /** The handle toward the next point. */
    data class Out(override val index: Int) : PenHit()
    /** The handle toward the previous point. */
    data class In(override val index: Int) : PenHit()
}

/** The pen shape whose points are being edited, if any. */
val EditorViewModel.penEditTarget: Layer?
    get() {
        if (tool != Tool.PEN || !penEditing || penPoints.isNotEmpty()) return null
        return doc?.active?.takeIf { it.isShape && it.props.shape?.kind == ShapeKind.PATH && !it.props.locked }
    }

/** Shape space → canvas. */
internal fun Layer.pathMatrix(): Matrix = (props.shape?.matrix?.toMatrix() ?: Matrix()).apply { postTranslate(x.toFloat(), y.toFloat()) }

internal fun Matrix.map(x: Float, y: Float): PointF {
    val a = floatArrayOf(x, y)
    mapPoints(a)
    return PointF(a[0], a[1])
}

internal fun Layer.toSpec(cx: Float, cy: Float): PointF {
    val inv = Matrix()
    if (!pathMatrix().invert(inv)) return PointF(cx, cy)
    return inv.map(cx, cy)
}

/** Canvas positions of a pen shape's points and handle ends (for drawing and touching). */
class PenLayout(val anchors: List<PointF>, val outs: List<PointF?>, val ins: List<PointF?>, val closed: Boolean)

fun EditorViewModel.penLayout(l: Layer): PenLayout? {
    val s = l.props.shape ?: return null
    val m = l.pathMatrix()
    return PenLayout(
        s.path.map { m.map(it.x, it.y) },
        s.path.map { if (it.hx != 0f || it.hy != 0f) m.map(it.x + it.hx, it.y + it.hy) else null },
        s.path.map { if (it.ix != 0f || it.iy != 0f) m.map(it.x + it.ix, it.y + it.iy) else null },
        s.closed,
    )
}

/** Switches to the pen with point editing on (the Edit points button). */
fun EditorViewModel.startPenEditing() {
    if (pendingFirst { startPenEditing() }) return
    if (tool != Tool.PEN) onToolButton(Tool.PEN)
    if (tool != Tool.PEN) return
    val a = doc?.active
    if (!(a != null && a.isShape && a.props.shape?.kind == ShapeKind.PATH)) {
        toast("Select a shape drawn with the pen to edit its points")
        return
    }
    penEditing = true
    penSelected = -1
}

/** Toggles point editing (tapping the pen tool again). */
fun EditorViewModel.togglePenEditing() {
    val a = doc?.active
    if (!penEditing && !(a != null && a.isShape && a.props.shape?.kind == ShapeKind.PATH)) {
        toast("Select a shape drawn with the pen to edit its points")
        return
    }
    penEditing = !penEditing
    penSelected = -1
    toast(if (penEditing) "Edit points: drag points and handles, tap the outline to add a point" else "Draw a new shape")
}

/** The point or handle within [radius] (canvas pixels) of ([cx], [cy]). Handles of the picked point win. */
fun EditorViewModel.penHit(cx: Float, cy: Float, radius: Float): PenHit? {
    val l = penEditTarget ?: return null
    val p = penLayout(l) ?: return null
    fun near(q: PointF?) = q != null && hypot(q.x - cx, q.y - cy) <= radius
    val order = (listOf(penSelected) + p.anchors.indices).filter { it in p.anchors.indices }.distinct()
    for (i in order) {
        if (near(p.outs[i])) return PenHit.Out(i)
        if (near(p.ins[i])) return PenHit.In(i)
    }
    var best = -1
    var bestD = radius
    p.anchors.forEachIndexed { i, a ->
        val d = hypot(a.x - cx, a.y - cy)
        if (d <= bestD) {
            bestD = d
            best = i
        }
    }
    return if (best >= 0) PenHit.Anchor(best) else null
}

fun EditorViewModel.penEditBegin(hit: PenHit) {
    val l = penEditTarget ?: return
    penDragStart = l.props.shape
    penSelected = hit.index
}

/** Drags what [hit] grabbed to canvas point ([cx], [cy]); ([dx], [dy]) is how far the finger moved. */
fun EditorViewModel.penEditDrag(hit: PenHit, cx: Float, cy: Float, startCx: Float, startCy: Float, snapRadius: Float = 0f) {
    val l = penEditTarget ?: return
    val start = penDragStart ?: return
    val pts = start.path.toMutableList()
    val i = hit.index
    if (i !in pts.indices) return
    val p = pts[i]
    val q = l.toSpec(cx, cy)
    when (hit) {
        is PenHit.Anchor -> {
            // Move by how far the finger went, handles along; the point snaps to the grid
            val a0 = l.pathMatrix().map(p.x, p.y)
            val (tx, ty) = snapPoint(a0.x + (cx - startCx), a0.y + (cy - startCy), snapRadius)
            val target = l.toSpec(tx, ty)
            pts[i] = p.copy(x = target.x, y = target.y)
        }
        is PenHit.Out -> {
            val hx = q.x - p.x
            val hy = q.y - p.y
            // The other handle turns to stay opposite (a smooth curve), keeping its length
            val len = hypot(p.ix, p.iy)
            val k = hypot(hx, hy).coerceAtLeast(0.001f)
            pts[i] = if (len > 0f) p.copy(hx = hx, hy = hy, ix = -hx / k * len, iy = -hy / k * len) else p.copy(hx = hx, hy = hy)
        }
        is PenHit.In -> {
            val ix = q.x - p.x
            val iy = q.y - p.y
            val len = hypot(p.hx, p.hy)
            val k = hypot(ix, iy).coerceAtLeast(0.001f)
            pts[i] = if (len > 0f) p.copy(ix = ix, iy = iy, hx = -ix / k * len, hy = -iy / k * len) else p.copy(ix = ix, iy = iy)
        }
    }
    editVectorLive(l.id) { props -> props.copy(shape = props.shape?.copy(path = pts)) }
}

/** The finger lifted: one undo step if anything moved. */
fun EditorViewModel.penEditEnd(moved: Boolean) {
    penDragStart = null
    if (moved) commitVector("Edit points")
}

/** A second finger came down: put the point back. */
fun EditorViewModel.penEditCancel() {
    val l = penEditTarget
    val start = penDragStart
    penDragStart = null
    if (l == null || start == null || vectorBefore == null) return
    l.props = l.props.copy(shape = start)
    vectorBefore = null
    redrawVectorNow(l)
}

/**
 * A tap while editing points: picks the point under the finger, adds a point where the outline was
 * tapped, or (anywhere else) unpicks.
 */
fun EditorViewModel.penEditTap(cx: Float, cy: Float, radius: Float) {
    val l = penEditTarget ?: return
    penHit(cx, cy, radius)?.let {
        penSelected = it.index
        return
    }
    val added = insertPenPoint(l, cx, cy, radius)
    penSelected = added ?: -1
}

/**
 * Adds a point to pen shape [l] where its outline passes within [radius] of canvas point ([cx], [cy])
 * (the shape doesn't change, it just gains a point there). Returns the new point's index, or null
 * if the outline isn't there.
 */
internal fun EditorViewModel.insertPenPoint(l: Layer, cx: Float, cy: Float, radius: Float): Int? {
    val s = l.props.shape ?: return null
    val local = l.toSpec(cx, cy)
    // Distance is measured on the canvas, so convert the touch radius roughly into shape space
    val r0 = l.toSpec(cx + radius, cy)
    val r = hypot(r0.x - local.x, r0.y - local.y).coerceAtLeast(0.5f)
    val hit = nearestOnOutline(s, local.x, local.y, r) ?: return null
    val (seg, t) = hit
    val pts = s.path.toMutableList()
    val a = pts[seg]
    val bi = (seg + 1) % pts.size
    val b = pts[bi]
    // Split the curve at t (de Casteljau): the shape doesn't change, it just gains a point
    val p0x = a.x; val p0y = a.y
    val p1x = a.x + a.hx; val p1y = a.y + a.hy
    val p2x = b.x + b.ix; val p2y = b.y + b.iy
    val p3x = b.x; val p3y = b.y
    fun lerp(u: Float, v: Float) = u + (v - u) * t
    val q0x = lerp(p0x, p1x); val q0y = lerp(p0y, p1y)
    val q1x = lerp(p1x, p2x); val q1y = lerp(p1y, p2y)
    val q2x = lerp(p2x, p3x); val q2y = lerp(p2y, p3y)
    val r0x = lerp(q0x, q1x); val r0y = lerp(q0y, q1y)
    val r1x = lerp(q1x, q2x); val r1y = lerp(q1y, q2y)
    val sx = lerp(r0x, r1x); val sy = lerp(r0y, r1y)
    val curved = a.hx != 0f || a.hy != 0f || b.ix != 0f || b.iy != 0f
    val mid = if (curved) PenPoint(sx, sy, r1x - sx, r1y - sy, r0x - sx, r0y - sy) else PenPoint(sx, sy)
    if (curved) {
        pts[seg] = a.copy(hx = q0x - p0x, hy = q0y - p0y)
        pts[bi] = b.copy(ix = q2x - p3x, iy = q2y - p3y)
    }
    pts.add(seg + 1, mid)
    editVector(l.id, "Add point") { it.copy(shape = it.shape?.copy(path = pts)) }
    return seg + 1
}

/** Nearest place on a pen shape's outline to ([x], [y]) within [r]: segment index and position along it. */
private fun nearestOnOutline(s: ShapeSpec, x: Float, y: Float, r: Float): Pair<Int, Float>? {
    val pts = s.path
    if (pts.size < 2) return null
    val segs = if (s.closed && pts.size > 2) pts.size else pts.size - 1
    var best: Pair<Int, Float>? = null
    var bestD = r
    for (i in 0 until segs) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        val steps = 48
        for (k in 1 until steps) {
            val t = k / steps.toFloat()
            val u = 1f - t
            val px = u * u * u * a.x + 3 * u * u * t * (a.x + a.hx) + 3 * u * t * t * (b.x + b.ix) + t * t * t * b.x
            val py = u * u * u * a.y + 3 * u * u * t * (a.y + a.hy) + 3 * u * t * t * (b.y + b.iy) + t * t * t * b.y
            val d = hypot(px - x, py - y)
            if (d < bestD) {
                bestD = d
                best = i to t
            }
        }
    }
    return best
}

/** Removes the picked point. */
fun EditorViewModel.deletePenPoint() {
    val l = penEditTarget ?: return
    val s = l.props.shape ?: return
    val i = penSelected
    if (i !in s.path.indices) return
    val min = if (s.closed) 3 else 2
    if (s.path.size <= min) {
        toast(if (s.closed) "A closed shape needs at least three points" else "A line needs at least two points")
        return
    }
    editVector(l.id, "Delete point") { it.copy(shape = it.shape?.copy(path = s.path.filterIndexed { k, _ -> k != i })) }
    penSelected = -1
}

/** Makes the picked point a sharp corner, or a smooth curve (handles along its neighbors). */
fun EditorViewModel.togglePenPointCurve() {
    val l = penEditTarget ?: return
    val s = l.props.shape ?: return
    val i = penSelected
    if (i !in s.path.indices) return
    val pts = s.path.toMutableList()
    val p = pts[i]
    pts[i] = if (p.smooth) {
        p.corner()
    } else {
        val n = pts.size
        val prev = if (i > 0) pts[i - 1] else if (s.closed) pts[n - 1] else p
        val next = if (i < n - 1) pts[i + 1] else if (s.closed) pts[0] else p
        // Handles point along the line from the previous point to the next one
        var dx = (next.x - prev.x) * 0.2f
        var dy = (next.y - prev.y) * 0.2f
        if (dx == 0f && dy == 0f) {
            dx = 20f
            dy = 0f
        }
        p.copy(hx = dx, hy = dy, ix = -dx, iy = -dy)
    }
    editVector(l.id, if (p.smooth) "Corner point" else "Curve point") { it.copy(shape = it.shape?.copy(path = pts)) }
}
