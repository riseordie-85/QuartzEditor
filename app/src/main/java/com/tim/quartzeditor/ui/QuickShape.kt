package com.tim.quartzeditor.ui

import android.graphics.PointF
import com.tim.quartzeditor.engine.ShapeFit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// QuickShape: draw, then hold the pen (or finger) still at the end, and the stroke becomes the
// shape it looks like — a straight line, a few straight lines, an arc, an ellipse, a rectangle or
// a triangle — drawn again with the same brush. Keep holding and move to size and turn it; Shift,
// the Modifier button or a finger on the screen makes it perfect (45° lines, a circle, a square).

/** A stroke snapped to a shape, still held. */
class QuickShapeHold(
    /** The shape as fitted (not perfect), in canvas pixels. */
    val shape: ShapeFit.Result,
    /** The fitted shape made perfect. */
    val perfectShape: ShapeFit.Result?,
    /** What it sizes and turns around: a line's start, or a shape's middle. */
    val pivot: PointF,
    /** Where the pen was when it snapped. */
    val grab: PointF,
    /** The pressure, tilt and lean the shape is drawn with (the stroke's typical ones). */
    val pressure: Float,
    val tilt: Float,
    val azimuth: Float,
    /** Shown as made perfect. */
    var perfect: Boolean = false,
) {
    /** Where the pen was when the shape was last drawn (canvas pixels), to skip moves too small to matter. */
    var lastX = grab.x
    var lastY = grab.y
}

/** Forgets the snapped shape and the points it was fitted from (a stroke ended or was dropped). */
internal fun EditorViewModel.resetQuickShape() {
    quickShape = null
    strokePoints.clear()
    strokeRemake = null
}

/** The stroke under way could snap to a shape now (when it's held). */
internal val EditorViewModel.quickShapeReady: Boolean
    get() = quickShapeOn && quickShape == null && stroke != null && strokeRemake != null && strokeHeals == null && retouch == null && strokePoints.size >= 3

/**
 * The pen's been held still: the stroke becomes the shape it looks like (true when it did).
 * [perfect]: a circle, a square, a line at 45° steps.
 */
fun EditorViewModel.quickShapeSnap(perfect: Boolean): Boolean {
    if (!quickShapeReady) return false
    val pts = strokePoints.map { PointF(it[0], it[1]) }
    // (Very short strokes, on the screen, aren't shapes: a shaky end of a dab when zoomed out)
    val shape = ShapeFit.fit(pts, minLength = 24f / scale) ?: return false
    val perfectShape = ShapeFit.perfect(pts, shape).takeIf { it.closed == shape.closed && it !== shape }
    // Drawn evenly, with the stroke's typical pressure (not the light start and lift)
    val ps = strokePoints.map { it[2] }.sorted()
    val pressure = ps[ps.size / 2]
    val last = strokePoints.last()
    val pivot = if (shape.closed) {
        PointF(shape.points.map { it.x }.average().toFloat(), shape.points.map { it.y }.average().toFloat())
    } else {
        shape.points.first()
    }
    val hold = QuickShapeHold(shape, perfectShape, pivot, PointF(last[0], last[1]), pressure, last[3], last[4], perfect && perfectShape != null)
    smoother = null
    quickShape = hold
    redrawShape(hold, if (hold.perfect) perfectShape!! else shape)
    if (!prefs.getBoolean("quickShapeHint", false)) {
        prefs.edit().putBoolean("quickShapeHint", true).apply()
        toast("Shape! Keep holding and move to size or turn it. Shift or a finger down makes it perfect")
    }
    return true
}

/** While a snapped shape is held: the pen at canvas ([x], [y]) sizes and turns it. */
fun EditorViewModel.quickShapeMove(x: Float, y: Float, perfect: Boolean) {
    val h = quickShape ?: return
    val usePerfect = perfect && h.perfectShape != null
    // Moves under half a screen pixel don't change anything you'd see
    if (usePerfect == h.perfect && hypot(x - h.lastX, y - h.lastY) * scale < 0.5f) return
    h.lastX = x
    h.lastY = y
    val base = if (usePerfect) h.perfectShape!! else h.shape
    h.perfect = usePerfect
    val out: ShapeFit.Result = if (!base.closed && base.kind != "arc") {
        // A line's end (or the last corner of a few lines) follows the pen
        val pts = base.points.toMutableList()
        var end = PointF(x, y)
        if (usePerfect) {
            val a = pts[pts.size - 2]
            val len = hypot(end.x - a.x, end.y - a.y)
            val ang = Math.round(atan2((end.y - a.y).toDouble(), (end.x - a.x).toDouble()) / (Math.PI / 4)) * (Math.PI / 4)
            end = PointF(a.x + (cos(ang) * len).toFloat(), a.y + (sin(ang) * len).toFloat())
        }
        pts[pts.lastIndex] = end
        ShapeFit.Result(pts, false, base.kind)
    } else {
        // Bigger or smaller by how far the pen is from the middle (an arc: its start), turned by which way it is
        val gx = h.grab.x - h.pivot.x
        val gy = h.grab.y - h.pivot.y
        val px = x - h.pivot.x
        val py = y - h.pivot.y
        val g = hypot(gx, gy)
        if (g < 1f) return
        val k = (hypot(px, py) / g).coerceIn(0.05f, 20f)
        var turn = atan2(py.toDouble(), px.toDouble()) - atan2(gy.toDouble(), gx.toDouble())
        // A perfect shape turns in 15° steps
        if (usePerfect) turn = Math.round(turn / (Math.PI / 12)) * (Math.PI / 12)
        val c = cos(turn).toFloat() * k
        val s = sin(turn).toFloat() * k
        ShapeFit.Result(base.points.map { p ->
            val dx = p.x - h.pivot.x
            val dy = p.y - h.pivot.y
            PointF(h.pivot.x + dx * c - dy * s, h.pivot.y + dx * s + dy * c)
        }, base.closed, base.kind)
    }
    redrawShape(h, out)
}

/** The stroke drawn again from scratch, along [shape]. */
private fun EditorViewModel.redrawShape(h: QuickShapeHold, shape: ShapeFit.Result) {
    val remake = strokeRemake ?: return
    // (Only while the brush stroke it came from is still under way)
    if (stroke == null || retouch != null || strokeHeals != null) return
    val s = remake(shape.closed)
    // The stroke spaces its own dabs between the corners
    val line = if (shape.closed) shape.points + shape.points.first() else shape.points
    for (p in line) s.addPoint(p.x, p.y, h.pressure, h.tilt, h.azimuth)
    stroke = s
    doc?.invalidate()
}
