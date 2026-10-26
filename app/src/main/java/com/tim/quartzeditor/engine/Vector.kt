package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Shape and text layers. They keep what they are (a star with 5 points, a line of text in a font)
// so they can be changed at any time, and their pixels are drawn from that whenever it changes.
// Everything else (blend modes, masks, layer styles, export) just uses those pixels.

/** A 3×3 matrix as a list (so specs compare by value). Maps the shape's own space to the layer. */
typealias Affine = List<Float>

val AFFINE_IDENTITY: Affine = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

fun Affine.toMatrix(): Matrix = Matrix().apply { setValues(this@toMatrix.toFloatArray()) }

fun Matrix.toAffine(): Affine = FloatArray(9).also { getValues(it) }.toList()

/** [outer] then this: maps through this matrix, then [outer]. */
fun Affine.then(outer: Matrix): Affine = toMatrix().apply { postConcat(outer) }.toAffine()

fun translation(x: Float, y: Float): Affine = listOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f)

private fun Affine.toJson(): JSONArray = JSONArray().also { a -> forEach { a.put(it.toDouble()) } }

private fun affineFrom(a: JSONArray?): Affine =
    if (a == null || a.length() != 9) AFFINE_IDENTITY else List(9) { a.optDouble(it, 0.0).toFloat() }

// ===================================================================== shapes

enum class ShapeKind(val label: String) {
    RECT("Rectangle"),
    ELLIPSE("Ellipse"),
    STAR("Star"),
    /** Drawn with the shape pen. */
    PATH("Pen shape"),
    TRIANGLE("Triangle"),
    POLYGON("Polygon"),
    /** Drawn from where the drag starts to where it ends. */
    ARROW("Arrow"),
    /** A shape from the library, an .svg or a Photoshop file: outlines ([ShapeSpec.parts]) stretched to the box. */
    CUSTOM("Custom shape"),
    ;

    /** The next shape when the Shape tool is tapped again (custom shapes come from the pen). */
    val next: ShapeKind get() = when (this) {
        RECT -> ELLIPSE
        ELLIPSE -> TRIANGLE
        TRIANGLE -> POLYGON
        POLYGON -> STAR
        STAR -> ARROW
        else -> RECT
    }

    companion object {
        /** The shapes the Shape tool draws, in menu order. */
        val TOOL_SHAPES = listOf(RECT, ELLIPSE, TRIANGLE, POLYGON, STAR, ARROW)
    }
}

/**
 * One outline of a custom shape. [op] says how it joins the outlines before it, like Photoshop's
 * path operations: 1 add, 2 subtract, 3 keep only the overlap, 0 keep where they don't overlap;
 * -1 is part of one outline with the others, filled by the shape's fill rule (SVG files).
 */
data class ShapePart(
    val points: List<PenPoint>,
    val closed: Boolean = true,
    val op: Int = -1,
    /** For an outline that starts a group: its fill rule, when it isn't the shape's (SVG elements each have their own). */
    val evenOdd: Boolean? = null,
)

enum class StrokeAlign(val label: String) { INSIDE("Inside"), CENTER("Center"), OUTSIDE("Outside") }

/**
 * A point of a pen shape. ([hx], [hy]) is its outgoing curve handle and ([ix], [iy]) its incoming
 * one, both relative to the point (all zero = a sharp corner). Drawing with the pen makes them
 * mirror images; point editing can give them different lengths.
 */
data class PenPoint(
    val x: Float,
    val y: Float,
    val hx: Float = 0f,
    val hy: Float = 0f,
    val ix: Float = -hx,
    val iy: Float = -hy,
) {
    val smooth: Boolean get() = hx != 0f || hy != 0f || ix != 0f || iy != 0f

    /** The same point as a sharp corner. */
    fun corner(): PenPoint = PenPoint(x, y)

    companion object {
        /**
         * The Curvature Pen: a smooth curve through [anchors] (their handles are ignored). Each point's
         * handles lie along the line from the point before it to the point after it, a third of the way
         * to each (so the curve passes through every point without kinks); [corner] points, and the two
         * ends of an open line, stay sharp. [before]/[after]: the points beyond the ends, when the line
         * carries on from an existing shape.
         */
        fun curvature(anchors: List<PenPoint>, corner: (Int) -> Boolean, closed: Boolean, before: PenPoint? = null, after: PenPoint? = null): List<PenPoint> {
            val n = anchors.size
            if (n < 2) return anchors.map { it.corner() }
            val wrap = closed && n > 2
            return List(n) { i ->
                val p = anchors[i]
                if (corner(i)) return@List p.corner()
                val prev = if (i > 0) anchors[i - 1] else if (wrap) anchors[n - 1] else before
                val next = if (i < n - 1) anchors[i + 1] else if (wrap) anchors[0] else after
                if (prev == null || next == null) return@List p.corner()
                val dx = next.x - prev.x
                val dy = next.y - prev.y
                val len = kotlin.math.hypot(dx, dy)
                if (len < 0.001f) return@List p.corner()
                val ux = dx / len
                val uy = dy / len
                val dOut = kotlin.math.hypot(next.x - p.x, next.y - p.y) / 3f
                val dIn = kotlin.math.hypot(p.x - prev.x, p.y - prev.y) / 3f
                PenPoint(p.x, p.y, ux * dOut, uy * dOut, -ux * dIn, -uy * dIn)
            }
        }
    }
}

/**
 * A shape layer. Rectangles, ellipses and stars sit in the box (0, 0)–([w], [h]) of their own
 * space; pen shapes are their [path] points. [matrix] places that space on the layer, so moving,
 * scaling, rotating and distorting keep the shape sharp.
 */
data class ShapeSpec(
    val kind: ShapeKind,
    val w: Float = 0f,
    val h: Float = 0f,
    /** Rectangle corner radius in pixels. */
    val radius: Float = 0f,
    /** Star / polygon corners. */
    val points: Int = 5,
    /** How deep the star's valleys go: 0 = a plain polygon, 0.95 = very spiky. */
    val depth: Float = 0.5f,
    /**
     * How flat the star's tips and valleys are: 0 = sharp points, 1 = square teeth (a cog).
     * In between, the teeth taper like a gear's.
     */
    val flat: Float = 0f,
    /** Polygon sides. */
    val sides: Int = 6,
    /** Arrow head: length as a multiple of the arrow's height, and its width (1 = the full height). */
    val headLength: Float = 1f,
    val headWidth: Float = 1f,
    /** Arrow shaft thickness (fraction of the height). */
    val shaftWidth: Float = 0.38f,
    val path: List<PenPoint> = emptyList(),
    val closed: Boolean = true,
    /** Custom shape: its outlines, in a box [viewW] × [viewH] that's stretched to [w] × [h]. */
    val parts: List<ShapePart> = emptyList(),
    val viewW: Float = 0f,
    val viewH: Float = 0f,
    /** Custom shape: holes by the even-odd rule (otherwise outlines going the other way make the holes). */
    val evenOdd: Boolean = false,
    /** Custom shape: what it's called in the library. */
    val shapeName: String = "",
    /** Fill color, or null for no fill. */
    val fill: Int? = Color.BLACK,
    /** Outline color, or null for none. */
    val stroke: Int? = null,
    val strokeWidth: Float = 6f,
    val strokeAlign: StrokeAlign = StrokeAlign.CENTER,
    val matrix: Affine = AFFINE_IDENTITY,
    /** Filled with a gradient instead of [fill]'s color (across the shape's box; [fill] still says it's filled). */
    val fillGradient: GradientFx? = null,
    /** Filled with a pattern instead (it stays put on the picture as the shape moves). */
    val fillPattern: PatternFx? = null,
    /** Dashed outline: dash and gap lengths, in outline widths (null = solid). */
    val dash: List<Float>? = null,
    /** 3D: pushed back into a solid (null = flat). */
    val extrude: Extrude3D? = null,
) {
    val hasFill: Boolean get() = fill != null && closed
    val hasStroke: Boolean get() = stroke != null && strokeWidth > 0f

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("w", w.toDouble())
        .put("h", h.toDouble())
        .put("radius", radius.toDouble())
        .put("points", points)
        .put("depth", depth.toDouble())
        .put("flat", flat.toDouble())
        .put("sides", sides)
        .put("headLength", headLength.toDouble())
        .put("headWidth", headWidth.toDouble())
        .put("shaftWidth", shaftWidth.toDouble())
        .put("path", JSONArray().also { a ->
            path.forEach { p ->
                a.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.hx.toDouble()).put(p.hy.toDouble()).put(p.ix.toDouble()).put(p.iy.toDouble()))
            }
        })
        .put("closed", closed)
        .also { o ->
            if (parts.isNotEmpty()) {
                o.put("parts", JSONArray().also { a ->
                    parts.forEach { part ->
                        a.put(JSONObject().put("closed", part.closed).put("op", part.op).also { po -> part.evenOdd?.let { po.put("eo", it) } }.put("pts", JSONArray().also { pa ->
                            part.points.forEach { pt -> pa.put(pt.x.toDouble()).put(pt.y.toDouble()).put(pt.hx.toDouble()).put(pt.hy.toDouble()).put(pt.ix.toDouble()).put(pt.iy.toDouble()) }
                        }))
                    }
                })
                o.put("viewW", viewW.toDouble()).put("viewH", viewH.toDouble()).put("evenOdd", evenOdd).put("shapeName", shapeName)
            }
        }
        .put("fill", fill?.toLong()?.and(0xFFFFFFFFL) ?: JSONObject.NULL)
        .put("stroke", stroke?.toLong()?.and(0xFFFFFFFFL) ?: JSONObject.NULL)
        .put("strokeWidth", strokeWidth.toDouble())
        .put("strokeAlign", strokeAlign.name)
        .put("matrix", matrix.toJson())
        .also { o -> fillGradient?.let { o.put("fillGradient", LayerStyle.gradientJson(it)) } }
        .also { o ->
            fillPattern?.let { p ->
                o.put("fillPattern", JSONObject().put("key", p.key).put("a", p.colorA.toLong() and 0xFFFFFFFFL).put("b", p.colorB.toLong() and 0xFFFFFFFFL).put("scale", p.scale.toDouble()))
            }
        }
        .also { o -> dash?.let { d -> o.put("dash", JSONArray().also { a -> d.forEach { a.put(it.toDouble()) } }) } }
        .also { o -> extrude?.let { o.put("extrude", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject): ShapeSpec {
            val pts = o.optJSONArray("path")
            return ShapeSpec(
                kind = ShapeKind.entries.firstOrNull { it.name == o.optString("kind") } ?: ShapeKind.RECT,
                w = o.optDouble("w", 0.0).toFloat(),
                h = o.optDouble("h", 0.0).toFloat(),
                radius = o.optDouble("radius", 0.0).toFloat(),
                points = o.optInt("points", 5),
                depth = o.optDouble("depth", 0.5).toFloat(),
                flat = o.optDouble("flat", 0.0).toFloat(),
                sides = o.optInt("sides", 6),
                headLength = o.optDouble("headLength", 1.0).toFloat(),
                headWidth = o.optDouble("headWidth", 1.0).toFloat(),
                shaftWidth = o.optDouble("shaftWidth", 0.38).toFloat(),
                path = if (pts == null) emptyList() else List(pts.length()) { i ->
                    val p = pts.getJSONArray(i)
                    val hx = p.optDouble(2, 0.0).toFloat()
                    val hy = p.optDouble(3, 0.0).toFloat()
                    PenPoint(p.optDouble(0).toFloat(), p.optDouble(1).toFloat(), hx, hy, p.optDouble(4, -hx.toDouble()).toFloat(), p.optDouble(5, -hy.toDouble()).toFloat())
                },
                closed = o.optBoolean("closed", true),
                parts = o.optJSONArray("parts")?.let { a ->
                    List(a.length()) { i ->
                        val po = a.getJSONObject(i)
                        val pa = po.optJSONArray("pts")
                        val n = (pa?.length() ?: 0) / 6
                        ShapePart(
                            List(n) { j ->
                                fun v(k: Int) = pa!!.optDouble(j * 6 + k, 0.0).toFloat()
                                PenPoint(v(0), v(1), v(2), v(3), v(4), v(5))
                            },
                            po.optBoolean("closed", true), po.optInt("op", -1),
                            if (po.has("eo")) po.optBoolean("eo") else null,
                        )
                    }
                } ?: emptyList(),
                viewW = o.optDouble("viewW", 0.0).toFloat(),
                viewH = o.optDouble("viewH", 0.0).toFloat(),
                evenOdd = o.optBoolean("evenOdd", false),
                shapeName = o.optString("shapeName", ""),
                fill = if (o.isNull("fill")) null else o.optLong("fill").toInt(),
                stroke = if (o.isNull("stroke")) null else o.optLong("stroke").toInt(),
                strokeWidth = o.optDouble("strokeWidth", 6.0).toFloat(),
                strokeAlign = StrokeAlign.entries.firstOrNull { it.name == o.optString("strokeAlign") } ?: StrokeAlign.CENTER,
                matrix = affineFrom(o.optJSONArray("matrix")),
                fillGradient = o.optJSONObject("fillGradient")?.let { LayerStyle.gradientFrom(it, GradientFx(on = true)) },
                fillPattern = o.optJSONObject("fillPattern")?.let { p ->
                    PatternFx(true, p.optString("key", "b:checker"), p.optLong("a", 0xFF000000L).toInt(), p.optLong("b", 0xFFFFFFFFL).toInt(), p.optDouble("scale", 1.0).toFloat())
                },
                dash = o.optJSONArray("dash")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } }?.takeIf { it.isNotEmpty() },
                extrude = runCatching { Extrude3D.fromJson(o.optJSONObject("extrude")) }.getOrNull(),
            )
        }

        /** The outline of a shape in its own space. */
        fun outline(s: ShapeSpec): Path = when (s.kind) {
            ShapeKind.RECT -> Path().apply {
                val r = s.radius.coerceIn(0f, min(abs(s.w), abs(s.h)) / 2f)
                if (r > 0f) addRoundRect(0f, 0f, s.w, s.h, r, r, Path.Direction.CW) else addRect(0f, 0f, s.w, s.h, Path.Direction.CW)
            }
            ShapeKind.ELLIPSE -> Path().apply { addOval(0f, 0f, s.w, s.h, Path.Direction.CW) }
            ShapeKind.STAR -> starPath(s.w, s.h, s.points, s.depth, s.flat)
            ShapeKind.PATH -> penPath(s.path, s.closed)
            ShapeKind.POLYGON -> starPath(s.w, s.h, s.sides, 0f)
            ShapeKind.TRIANGLE -> Path().apply {
                moveTo(s.w / 2f, 0f)
                lineTo(s.w, s.h)
                lineTo(0f, s.h)
                close()
            }
            ShapeKind.ARROW -> arrowPath(s)
            ShapeKind.CUSTOM -> customPath(s.parts, s.evenOdd, if (s.viewW > 0f && s.viewH > 0f) RectF(0f, 0f, s.viewW, s.viewH) else null).apply {
                if (s.viewW > 0f && s.viewH > 0f) transform(Matrix().apply { setScale(s.w / s.viewW, s.h / s.viewH) })
            }
        }

        /**
         * A custom shape's outlines joined into one (in their own box). Outlines with [ShapePart.op]
         * -1 belong to the one before them (filled together by [evenOdd] or by direction); the
         * groups are then joined by their ops like Photoshop's path operations, where subtracting
         * or intersecting first starts from [full] (the whole area) rather than from nothing.
         */
        fun customPath(parts: List<ShapePart>, evenOdd: Boolean, full: RectF? = null): Path {
            val rule = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            val groups = ArrayList<Pair<Int, Path>>()
            for (part in parts) {
                if (part.points.isEmpty()) continue
                val p = penPath(part.points, part.closed)
                if (part.op < 0 && groups.isNotEmpty()) groups.last().second.addPath(p)
                else groups.add((if (part.op < 0) 1 else part.op) to Path().apply {
                    fillType = part.evenOdd?.let { if (it) Path.FillType.EVEN_ODD else Path.FillType.WINDING } ?: rule
                    addPath(p)
                })
            }
            if (groups.isEmpty()) return Path()
            if (groups.size == 1 && (groups[0].first == 1 || groups[0].first == 0 || full == null)) return groups[0].second
            var out: Path? = null
            for ((i, g) in groups.withIndex()) {
                val (op, p) = g
                if (i == 0) {
                    if ((op == 2 || op == 3) && full != null) {
                        out = Path().apply { addRect(full, Path.Direction.CW) }
                    } else {
                        out = Path(p)
                        continue
                    }
                }
                val o = out ?: continue
                val mode = when (op) {
                    2 -> Path.Op.DIFFERENCE
                    3 -> Path.Op.INTERSECT
                    0 -> Path.Op.XOR
                    else -> Path.Op.UNION
                }
                if (!o.op(p, mode)) o.addPath(p)
            }
            return out ?: Path()
        }

        /** An arrow pointing right along the box: a shaft, then a triangular head. */
        fun arrowPath(s: ShapeSpec): Path {
            val w = s.w
            val h = s.h
            val cy = h / 2f
            val hl = (s.headLength * h).coerceIn(0f, w * 0.95f)
            val sw = (s.shaftWidth.coerceIn(0.02f, 1f) * h) / 2f
            // The head is never narrower than the shaft (that would notch the outline)
            val hw = max((s.headWidth.coerceIn(0.1f, 3f) * h) / 2f, sw)
            return Path().apply {
                moveTo(0f, cy - sw)
                lineTo(w - hl, cy - sw)
                lineTo(w - hl, cy - hw)
                lineTo(w, cy)
                lineTo(w - hl, cy + hw)
                lineTo(w - hl, cy + sw)
                lineTo(0f, cy + sw)
                close()
            }
        }

        fun starPath(w: Float, h: Float, points: Int, depth: Float, flat: Float = 0f): Path {
            val n = points.coerceIn(3, 100)
            val d = depth.coerceIn(0f, 0.95f)
            val cx = w / 2f
            val cy = h / 2f
            val p = Path()
            val f = flat.coerceIn(0f, 1f)
            if (f > 0.005f && d >= 0.005f) {
                // Flattened: each tip and each valley is a short arc-like edge instead of a point.
                // At 1 the edges reach halfway to the next corner, so the sides of a tooth run
                // straight out from the middle (square teeth).
                val half = 180.0 / n
                val spread = f * half / 2.0
                for (i in 0 until n * 2) {
                    val r = if (i % 2 == 0) 1f else 1f - d
                    val mid = -90.0 + i * half
                    for (a in doubleArrayOf(mid - spread, mid + spread)) {
                        val rad = Math.toRadians(a)
                        val x = cx + (cos(rad) * r).toFloat() * w / 2f
                        val y = cy + (sin(rad) * r).toFloat() * h / 2f
                        if (i == 0 && a < mid) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                }
                p.close()
                return p
            }
            // A polygon only has the outer corners
            val step = if (d < 0.005f) 2 else 1
            val count = n * 2
            var first = true
            var i = 0
            while (i < count) {
                val r = if (i % 2 == 0) 1f else 1f - d
                val a = Math.toRadians(-90.0 + i * 180.0 / n)
                val x = cx + (cos(a) * r).toFloat() * w / 2f
                val y = cy + (sin(a) * r).toFloat() * h / 2f
                if (first) p.moveTo(x, y) else p.lineTo(x, y)
                first = false
                i += step
            }
            p.close()
            return p
        }

        /** Pen points joined by straight lines, or curves where a point has a handle. */
        fun penPath(pts: List<PenPoint>, closed: Boolean): Path {
            val p = Path()
            if (pts.isEmpty()) return p
            p.moveTo(pts[0].x, pts[0].y)
            fun seg(a: PenPoint, b: PenPoint) {
                if (a.hx == 0f && a.hy == 0f && b.ix == 0f && b.iy == 0f) p.lineTo(b.x, b.y)
                else p.cubicTo(a.x + a.hx, a.y + a.hy, b.x + b.ix, b.y + b.iy, b.x, b.y)
            }
            for (i in 1 until pts.size) seg(pts[i - 1], pts[i])
            // Two points can close too (a curve there and a curve back: an ellipse or a leaf)
            if (closed && pts.size >= 2) {
                seg(pts.last(), pts[0])
                p.close()
            }
            return p
        }
    }
}

// ===================================================================== text

enum class TextAlignOption(val label: String, val anchor: Float) { LEFT("Left", 0f), CENTER("Center", 0.5f), RIGHT("Right", 1f) }

/**
 * A text layer. The text's own space has its anchor at (0, 0): the start (left / middle / end,
 * by [align]) of the first line's baseline; for box text ([boxWidth] > 0) the box's top-left
 * corner, with lines wrapping to the box. [matrix] places it on the layer.
 */
/**
 * Part of a text layer styled differently from the rest (from Photoshop): characters [start] until
 * [end] of the text, with whatever isn't null here instead of the layer's own setting.
 */
data class TextRun(
    val start: Int,
    val end: Int,
    val fontKey: String? = null,
    val size: Float? = null,
    val color: Int? = null,
    val fauxBold: Boolean? = null,
    val fauxItalic: Boolean? = null,
    val underline: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).also { o ->
        fontKey?.let { o.put("font", it) }
        size?.let { o.put("size", it.toDouble()) }
        color?.let { o.put("color", it.toLong() and 0xFFFFFFFFL) }
        fauxBold?.let { o.put("bold", it) }
        fauxItalic?.let { o.put("italic", it) }
        underline?.let { o.put("underline", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = TextRun(
            o.optInt("s"), o.optInt("e"),
            if (o.has("font")) o.optString("font") else null,
            if (o.has("size")) o.optDouble("size").toFloat() else null,
            if (o.has("color")) o.optLong("color").toInt() else null,
            if (o.has("bold")) o.optBoolean("bold") else null,
            if (o.has("italic")) o.optBoolean("italic") else null,
            if (o.has("underline")) o.optBoolean("underline") else null,
        )

        /**
         * [runs] of text [old] moved to fit its edited version [new]: typing inside or right after
         * a run carries its style on, deleting shortens it.
         */
        fun adjust(runs: List<TextRun>, old: String, new: String): List<TextRun> {
            if (runs.isEmpty() || old == new) return runs
            var p = 0
            val maxP = min(old.length, new.length)
            while (p < maxP && old[p] == new[p]) p++
            var q = 0
            while (q < min(old.length, new.length) - p && old[old.length - 1 - q] == new[new.length - 1 - q]) q++
            val oldEnd = old.length - q
            val newEnd = new.length - q
            val delta = new.length - old.length
            return runs.mapNotNull { r ->
                // Before the edit stays, after it moves; text typed in takes the style of the character before it
                val s = when {
                    r.start < p -> r.start
                    r.start >= oldEnd -> r.start + delta
                    r.start == p -> p
                    else -> newEnd
                }
                // Typed text takes the style before it only when nothing was replaced, or when this
                // run held the replaced text's first character
                val replaced = oldEnd > p
                val e = when {
                    r.end < p -> r.end
                    r.end == p -> if (r.start < p && !replaced) newEnd else p
                    r.end < oldEnd -> if (r.start <= p) newEnd else p
                    else -> r.end + delta
                }
                if (e > s) r.copy(start = s, end = e) else null
            }
        }
    }
}

data class TextSpec(
    val text: String,
    val fontKey: String = TextFont.BOLD.key,
    /** Pixels. */
    val size: Float = 96f,
    val color: Int = Color.BLACK,
    val align: TextAlignOption = TextAlignOption.LEFT,
    /** Extra space between letters, in ems. */
    val tracking: Float = 0f,
    /** Line height as a multiple of the normal one. */
    val leading: Float = 1f,
    val fauxBold: Boolean = false,
    val fauxItalic: Boolean = false,
    val underline: Boolean = false,
    /** Shown in capitals (the text itself keeps its own case). */
    val allCaps: Boolean = false,
    /** Arc: -1 (smile) … 0 (straight) … 1 (rainbow). Single line only. */
    val curve: Float = 0f,
    val matrix: Affine = AFFINE_IDENTITY,
    /** Box text (from Photoshop): lines wrap at this width (text-space pixels); 0 = no box. */
    val boxWidth: Float = 0f,
    /** From Photoshop: the font it used, until the text is first edited (then it's said whether it's here). */
    val psdFont: String? = null,
    /** Parts styled differently (several fonts, sizes or colors in one layer, from Photoshop). */
    val runs: List<TextRun> = emptyList(),
    /** Warp Text: bent into a shape (null = straight; takes the place of [curve]). */
    val warp: TextWarp? = null,
    /** 3D: pushed back into a solid (null = flat). */
    val extrude: Extrude3D? = null,
) {
    /** Every font it uses. */
    val fonts: Set<String> get() = if (runs.isEmpty()) setOf(fontKey) else setOf(fontKey) + runs.mapNotNull { it.fontKey }

    /**
     * After a change made to the whole layer from [before] (a new font, size or color in
     * Properties): parts styled differently follow it. A new size scales every part's size; a new
     * font, color or style replaces the parts' own.
     */
    fun runsFollow(before: TextSpec): TextSpec {
        if (runs.isEmpty()) return this
        val k = if (before.size > 0f && size != before.size) size / before.size else 1f
        val next = runs.map { r ->
            r.copy(
                fontKey = if (fontKey != before.fontKey) null else r.fontKey,
                size = r.size?.let { it * k },
                color = if (color != before.color) null else r.color,
                fauxBold = if (fauxBold != before.fauxBold) null else r.fauxBold,
                fauxItalic = if (fauxItalic != before.fauxItalic) null else r.fauxItalic,
                underline = if (underline != before.underline) null else r.underline,
            )
        }.filter { it.fontKey != null || it.size != null || it.color != null || it.fauxBold != null || it.fauxItalic != null || it.underline != null }
        return if (next == runs) this else copy(runs = next)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("font", fontKey)
        .put("size", size.toDouble())
        .put("color", color.toLong() and 0xFFFFFFFFL)
        .put("align", align.name)
        .put("tracking", tracking.toDouble())
        .put("leading", leading.toDouble())
        .put("bold", fauxBold)
        .put("italic", fauxItalic)
        .put("underline", underline)
        .put("caps", allCaps)
        .put("curve", curve.toDouble())
        .put("matrix", matrix.toJson())
        .put("box", boxWidth.toDouble())
        .also { o -> psdFont?.let { o.put("psdFont", it) } }
        .also { o -> if (runs.isNotEmpty()) o.put("runs", JSONArray().also { a -> runs.forEach { a.put(it.toJson()) } }) }
        .also { o -> warp?.let { o.put("warp", it.toJson()) } }
        .also { o -> extrude?.let { o.put("extrude", it.toJson()) } }

    /** Shown in the Layers panel. */
    val title: String get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: "Text"

    companion object {
        fun fromJson(o: JSONObject): TextSpec = TextSpec(
            text = o.optString("text", ""),
            fontKey = o.optString("font", TextFont.BOLD.key),
            size = o.optDouble("size", 96.0).toFloat(),
            color = o.optLong("color", 0xFF000000L).toInt(),
            align = TextAlignOption.entries.firstOrNull { it.name == o.optString("align") } ?: TextAlignOption.LEFT,
            tracking = o.optDouble("tracking", 0.0).toFloat(),
            leading = o.optDouble("leading", 1.0).toFloat(),
            fauxBold = o.optBoolean("bold", false),
            fauxItalic = o.optBoolean("italic", false),
            underline = o.optBoolean("underline", false),
            allCaps = o.optBoolean("caps", false),
            curve = o.optDouble("curve", 0.0).toFloat(),
            matrix = affineFrom(o.optJSONArray("matrix")),
            boxWidth = o.optDouble("box", 0.0).toFloat(),
            psdFont = if (o.has("psdFont")) o.optString("psdFont") else null,
            runs = o.optJSONArray("runs")?.let { a -> List(a.length()) { TextRun.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            warp = runCatching { TextWarp.fromJson(o.optJSONObject("warp")) }.getOrNull(),
            extrude = runCatching { Extrude3D.fromJson(o.optJSONObject("extrude")) }.getOrNull(),
        )
    }
}

/** Text laid out and ready to draw (in its own space). */
class TextBuilt(
    val paint: TextPaint,
    val layout: StaticLayout?,
    /** Set for arched text instead of [layout]. */
    val arc: Path?,
    val text: String,
    /** Where [layout] is drawn. */
    val left: Float,
    val top: Float,
    /** Generous bounds of the glyphs. */
    val bounds: RectF,
    /** Warped text: the warp, and the straight text's box it bends. */
    val warp: TextWarp? = null,
    val box: RectF? = null,
) {
    /** Warped text drawn straight (for bending), at [scale]; made once per scale. */
    internal var straight: Pair<Float, Bitmap>? = null
}

// ===================================================================== drawing

object VectorArt {

    fun isVector(props: LayerProps): Boolean = props.shape != null || props.text != null

    fun buildText(t: TextSpec, typeface: Typeface = Fonts.resolve(t.fontKey)): TextBuilt {
        val size = t.size.coerceIn(1f, 4000f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            this.typeface = typeface
            letterSpacing = t.tracking
            color = t.color
            isFakeBoldText = t.fauxBold
            textSkewX = if (t.fauxItalic) -0.22f else 0f
            isUnderlineText = t.underline
        }
        // (Capitals letter by letter, so the text keeps its length: the cursor's place lines up)
        val text = (if (t.allCaps) String(CharArray(t.text.length) { t.text[it].uppercaseChar() }) else t.text).ifEmpty { " " }
        val fm = paint.fontMetrics
        val slack = size * 0.35f
        val curve = t.curve.coerceIn(-1f, 1f)
        val warp = t.warp?.takeIf { it.active }
        if (warp == null && abs(curve) >= 0.02f && !text.contains('\n')) {
            val tw = max(1f, paint.measureText(text))
            val theta = abs(curve) * Math.PI.toFloat()
            val r = tw / theta
            val deg = Math.toDegrees(theta.toDouble()).toFloat()
            val asc = -fm.ascent
            val desc = fm.descent
            val path = Path()
            val b = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            fun include(x: Float, y: Float) {
                b.left = min(b.left, x); b.right = max(b.right, x); b.top = min(b.top, y); b.bottom = max(b.bottom, y)
            }
            val steps = 24
            if (curve > 0f) {
                // Rainbow: circle center below, the middle of the baseline at (0, 0)
                path.addArc(RectF(-r, 0f, r, 2 * r), 270f - deg / 2f, deg)
                for (i in 0..steps) {
                    val phi = -theta / 2f + theta * i / steps
                    for (rr in floatArrayOf(r - desc, r + asc)) include(rr * sin(phi), r - rr * cos(phi))
                }
            } else {
                // Smile: circle center above
                path.addArc(RectF(-r, -2 * r, r, 0f), 90f + deg / 2f, -deg)
                for (i in 0..steps) {
                    val phi = -theta / 2f + theta * i / steps
                    for (rr in floatArrayOf(r - asc, r + desc)) include(rr * sin(phi), -r + rr * cos(phi))
                }
            }
            // The arc sits around the anchor like straight text: starting at it, centered on it, or ending at it
            val dx = (0.5f - t.align.anchor) * 2f * r * sin(theta / 2f)
            path.offset(dx, 0f)
            b.offset(dx, 0f)
            b.inset(-slack, -slack)
            return TextBuilt(paint, null, path, text, 0f, 0f, b)
        }
        // Parts styled differently: laid out with spans
        val styled: CharSequence = if (t.runs.isEmpty()) text else android.text.SpannableString(text).also { sp ->
            for (r in t.runs) {
                val a = r.start.coerceIn(0, text.length)
                val b = r.end.coerceIn(a, text.length)
                if (b <= a) continue
                val flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                r.size?.let { sp.setSpan(android.text.style.RelativeSizeSpan(it.coerceIn(1f, 4000f) / size), a, b, flags) }
                r.color?.let { sp.setSpan(android.text.style.ForegroundColorSpan(it), a, b, flags) }
                r.fontKey?.let { sp.setSpan(android.text.style.TypefaceSpan(Fonts.resolve(it)), a, b, flags) }
                if (r.fauxBold != null || r.fauxItalic != null || r.underline != null) sp.setSpan(RunLook(r.fauxBold, r.fauxItalic, r.underline), a, b, flags)
            }
        }
        val widest = if (styled === text) text.split('\n').maxOf { paint.measureText(it) } else {
            val probe = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, 1_000_000).setIncludePad(false).build()
            (0 until probe.lineCount).maxOf { probe.getLineWidth(it) }
        }
        val box = t.boxWidth > 0f
        // Box text wraps to its box; otherwise as wide as the widest line
        val width = if (box) ceil(t.boxWidth.toDouble()).toInt().coerceAtLeast(1) else ceil(widest.toDouble()).toInt().coerceAtLeast(1) + 1
        val alignment = when (t.align) {
            TextAlignOption.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignOption.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignOption.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, t.leading.coerceIn(0.5f, 3f))
            .build()
        // Box text hangs from the box's corner; other text sits on its first baseline at the anchor
        val left = if (box) 0f else -width * t.align.anchor
        val top = if (box) 0f else -layout.getLineBaseline(0).toFloat()
        val b = RectF(left, top, left + width, top + layout.height)
        if (warp != null) {
            // Bent: the box of the glyphs (ink and all) is what bends
            val box = RectF(left, top + fm.top - fm.ascent, left + width, top + layout.height + (fm.bottom - fm.descent))
            val wb = warp.bounds(box)
            wb.inset(-slack, -slack)
            return TextBuilt(paint, layout, null, text, left, top, wb, warp, box)
        }
        b.inset(-slack, -slack)
        return TextBuilt(paint, layout, null, text, left, top, b)
    }

    /** Draws a shape or text layer's content into [c] (layer-local coordinates). */
    fun draw(c: Canvas, props: LayerProps, built: TextBuilt? = null) {
        props.shape?.let { drawShape(c, it) }
        props.text?.let { drawText(c, it, built) }
    }

    fun drawText(c: Canvas, t: TextSpec, built: TextBuilt? = null) {
        val b = built ?: buildText(t)
        c.save()
        c.concat(t.matrix.toMatrix())
        val warp = b.warp
        val box = b.box
        if (warp != null && box != null) {
            // Drawn straight at about the size it shows, then bent
            // (the canvas already has the text's own matrix)
            @Suppress("DEPRECATION") val m = Matrix(c.matrix)
            val v = floatArrayOf(1f, 0f, 0f, 1f)
            m.mapVectors(v)
            var scale = max(kotlin.math.hypot(v[0], v[1]), kotlin.math.hypot(v[2], v[3])).coerceIn(0.05f, 8f)
            // No bigger than about 4096 × 4096
            val most = 4096f / max(box.width(), box.height())
            if (scale > most) scale = most
            val cached = b.straight
            val bmp = if (cached != null && abs(cached.first - scale) / scale < 0.05f) cached.second else {
                val bw = (box.width() * scale).toInt().coerceIn(1, 8192)
                val bh = (box.height() * scale).toInt().coerceIn(1, 8192)
                val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val oc = Canvas(out)
                oc.scale(bw / box.width(), bh / box.height())
                oc.translate(-box.left, -box.top)
                oc.translate(b.left, b.top)
                b.layout?.draw(oc)
                // The one made for another scale goes (only this layout uses it)
                b.straight?.second?.recycle()
                b.straight = scale to out
                out
            }
            warp.draw(c, bmp, box)
        } else if (b.arc != null) {
            b.paint.textAlign = Paint.Align.LEFT
            c.drawTextOnPath(b.text, b.arc, 0f, 0f, b.paint)
        } else {
            c.translate(b.left, b.top)
            b.layout?.draw(c)
        }
        c.restore()
    }

    /** The shape's outline on the layer. */
    fun shapeOutline(s: ShapeSpec): Path = ShapeSpec.outline(s).apply { transform(s.matrix.toMatrix()) }

    fun drawShape(c: Canvas, s: ShapeSpec) {
        val path = shapeOutline(s)
        if (s.hasFill) {
            c.drawPath(path, fillPaint(s, path))
        }
        if (s.hasStroke) {
            // Open lines (a pen line, or the lines of a custom shape) have no inside: outlined down the middle
            val closed = if (s.kind == ShapeKind.CUSTOM) s.parts.all { it.closed } else s.kind != ShapeKind.PATH || s.closed
            val align = if (closed) s.strokeAlign else StrokeAlign.CENTER
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = s.stroke!!
                strokeWidth = if (align == StrokeAlign.CENTER) s.strokeWidth else s.strokeWidth * 2f
                strokeJoin = if ((s.kind == ShapeKind.RECT && s.radius <= 0f) || s.kind == ShapeKind.ARROW) Paint.Join.MITER else Paint.Join.ROUND
                strokeMiter = 10f
                strokeCap = Paint.Cap.ROUND
                s.dash?.let { d ->
                    // Lengths are in outline widths (the shown width, even when drawn double for inside/outside)
                    val unit = s.strokeWidth.coerceAtLeast(0.5f)
                    val arr = (if (d.size % 2 == 1) d + d else d).map { (it * unit).coerceAtLeast(0.01f) }.toFloatArray()
                    // A zero-length dash with round ends is a dot
                    pathEffect = android.graphics.DashPathEffect(arr, 0f)
                    strokeCap = if (d.firstOrNull() == 0f) Paint.Cap.ROUND else Paint.Cap.BUTT
                }
            }
            if (align == StrokeAlign.CENTER) {
                c.drawPath(path, stroke)
            } else {
                // Half of a double-width stroke: keep only the part inside (or outside) the shape
                c.saveLayer(null, null)
                c.drawPath(path, stroke)
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(if (align == StrokeAlign.INSIDE) PorterDuff.Mode.DST_IN else PorterDuff.Mode.DST_OUT)
                })
                c.restore()
            }
        }
    }

    /** How a shape is filled: its color, or its gradient or pattern. */
    private fun fillPaint(s: ShapeSpec, path: Path): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = s.fill ?: Color.BLACK
        val g = s.fillGradient
        val p = s.fillPattern
        if (g != null) {
            val r = RectF()
            path.computeBounds(r, true)
            if (r.width() < 1f) r.right = r.left + 1f
            if (r.height() < 1f) r.bottom = r.top + 1f
            color = Color.BLACK
            alpha = (g.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            shader = Effects.gradientShader(g, r)
        } else if (p != null) {
            val bmp = Patterns.bitmapFor(p.key, p.colorA, p.colorB)
            if (bmp != null) {
                color = Color.BLACK
                shader = Patterns.paint(bmp, p.scale, 0f, 0f).shader
            }
        }
    }

    /** The 3D a shape or text layer has (null: flat). */
    fun extrudeOf(props: LayerProps): Extrude3D? = (props.shape?.extrude ?: props.text?.extrude)?.takeIf { it.active }

    /** Layer-local bounds of what [props] draws (generous), or null for nothing; with 3D, the solid's. */
    fun bounds(props: LayerProps): RectF? {
        val flat = flatBounds(props) ?: return null
        val e = extrudeOf(props) ?: return flat
        return Extrude.bounds(e, flat)
    }

    /** Layer-local bounds of what [props] draws flat (generous), or null for nothing. */
    fun flatBounds(props: LayerProps): RectF? {
        props.shape?.let { s ->
            val r = RectF()
            shapeOutline(s).computeBounds(r, true)
            // Room for the outline: an outside outline is drawn twice as wide, and its square
            // (mitered) corners reach about 1.42× further than its width
            // An arrow's sharp tip (mitered, up to the miter limit of 4) reaches further still
            val pad = (if (s.hasStroke) s.strokeWidth * (if (s.kind == ShapeKind.ARROW) 4f else 1.5f) else 0f) + 2f
            r.inset(-pad, -pad)
            return r
        }
        props.text?.let { t ->
            val r = RectF(buildText(t).bounds)
            t.matrix.toMatrix().mapRect(r)
            return r
        }
        return null
    }

    /**
     * Draws the layer's content into fresh tiles. Only the part inside [clip] (layer-local) is
     * drawn, so an enormous shape can't use up memory.
     */
    fun rasterize(props: LayerProps, clip: RectF): TileGrid {
        extrudeOf(props)?.let { return rasterize3D(props, it, clip) }
        val g = TileGrid(alphaOnly = false)
        val area = bounds(props) ?: return g
        if (!area.intersect(clip)) return g
        // Text is laid out once for all the tiles
        val built = props.text?.let { buildText(it) }
        for (k in g.keysIn(area)) {
            val tile = TileGrid.newTile(false)
            val c = Canvas(tile)
            c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
            draw(c, props, built)
            if (TileGrid.isBlank(tile)) tile.recycle() else g.tiles[k] = tile
        }
        return g
    }

    /**
     * A 3D shape or text layer: drawn flat (no bigger than about 3000 px a side; the solid is scaled
     * back up), made into a solid on the GPU, then cut into tiles within [clip].
     */
    private fun rasterize3D(props: LayerProps, e: Extrude3D, clip: RectF): TileGrid {
        // The same layer drawn again (a slider let go after its live redraw): the same tiles
        synchronized(this) {
            val c = last3D
            if (c != null && c.first == props && c.second == clip) return TileGrid(alphaOnly = false).also { it.tiles.putAll(c.third.tiles) }
        }
        val g = try {
            solid(props, e, clip)
        } catch (err: OutOfMemoryError) {
            null
        } catch (err: Exception) {
            android.util.Log.e("Quartz3D", "3D failed: ${err.message}")
            null
        } ?: return rasterizeFlat(props, clip)
        synchronized(this) { last3D = Triple(props, RectF(clip), g) }
        return TileGrid(alphaOnly = false).also { it.tiles.putAll(g.tiles) }
    }

    /** The last 3D layer drawn (its settings, area and tiles; tiles are never drawn into once made). */
    private var last3D: Triple<LayerProps, RectF, TileGrid>? = null

    /** The layer drawn flat (when its 3D can't be drawn). */
    private fun rasterizeFlat(props: LayerProps, clip: RectF): TileGrid =
        rasterize(props.copy(shape = props.shape?.copy(extrude = null), text = props.text?.copy(extrude = null)), clip)

    private fun solid(props: LayerProps, e: Extrude3D, clip: RectF): TileGrid {
        val g = TileGrid(alphaOnly = false)
        val flat = flatBounds(props) ?: return g
        // Small enough that the solid, and each line of sight through it, stay within what's drawn
        val whole = Extrude.bounds(e, flat)
        val k = minOf(
            1f,
            Extrude.MAX_SIDE / max(flat.width(), flat.height()).coerceAtLeast(1f),
            Extrude.MAX_SIDE / max(whole.width(), whole.height()).coerceAtLeast(1f),
            450f / Extrude.reach(e).coerceAtLeast(1f),
        )
        val fw = ceil(flat.width() * k).toInt().coerceIn(1, 4096)
        val fh = ceil(flat.height() * k).toInt().coerceIn(1, 4096)
        val box = RectF(flat.left * k, flat.top * k, flat.left * k + fw, flat.top * k + fh)
        val front = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val built = props.text?.let { buildText(it) }
        Canvas(front).apply {
            translate(-box.left, -box.top)
            scale(k, k)
            draw(this, props, built)
        }
        val (solid, out) = try {
            Extrude.render(e.scaled(k), front, box)
        } finally {
            front.recycle()
        }
        try {
            val dst = RectF(out.left / k, out.top / k, out.right / k, out.bottom / k)
            val area = RectF(dst)
            if (!area.intersect(clip)) return g
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            for (key in g.keysIn(area)) {
                val tile = TileGrid.newTile(false)
                val c = Canvas(tile)
                c.translate(-(TileGrid.tx(key) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(key) * TileGrid.SIZE).toFloat())
                c.drawBitmap(solid, null, dst, p)
                if (TileGrid.isBlank(tile)) tile.recycle() else g.tiles[key] = tile
            }
            return g
        } finally {
            solid.recycle()
        }
    }

    /** [props] with its shape or text moved by [m] (layer-local → new layer-local); 3D depth scales along. */
    fun transformed(props: LayerProps, m: Matrix): LayerProps {
        val v = FloatArray(9).also { m.getValues(it) }
        val k = kotlin.math.sqrt(abs(v[0] * v[4] - v[1] * v[3])).takeIf { it.isFinite() && it > 0f } ?: 1f
        return props.copy(
            shape = props.shape?.let { it.copy(matrix = it.matrix.then(m), extrude = it.extrude?.scaled(k)) },
            text = props.text?.let { it.copy(matrix = it.matrix.then(m), extrude = it.extrude?.scaled(k)) },
        )
    }

    /** The area (layer-local) a shape or text layer at ([x], [y]) is drawn within on a [w] × [h] canvas. */
    fun clipFor(x: Int, y: Int, w: Int, h: Int): RectF {
        val m = max(w, h).toFloat()
        return RectF(-m - x, -m - y, w + m - x, h + m - y)
    }
}

/** Faux bold, faux italic and underline for part of a text layer. */
private class RunLook(val bold: Boolean?, val italic: Boolean?, val underline: Boolean?) : android.text.style.MetricAffectingSpan() {
    private fun apply(tp: TextPaint) {
        bold?.let { tp.isFakeBoldText = it }
        italic?.let { tp.textSkewX = if (it) -0.22f else 0f }
        underline?.let { tp.isUnderlineText = it }
    }

    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
}
