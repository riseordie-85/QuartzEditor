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
        ARROW -> CUSTOM
        else -> RECT
    }

    companion object {
        /** The shapes the Shape tool draws, in menu order. */
        val TOOL_SHAPES = listOf(RECT, ELLIPSE, TRIANGLE, POLYGON, STAR, ARROW, CUSTOM)
    }
}

/**
 * One outline of a custom shape. [op] says how it joins the outlines before it, like Photoshop's
 * path operations: 1 add, 2 subtract, 3 keep only the overlap, 0 keep where they don't overlap;
 * -1 is part of one outline with the others, filled by the shape's fill rule (SVG files).
 */
data class ShapePart(val points: List<PenPoint>, val closed: Boolean = true, val op: Int = -1)

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
                        a.put(JSONObject().put("closed", part.closed).put("op", part.op).put("pts", JSONArray().also { pa ->
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
                else groups.add((if (part.op < 0) 1 else part.op) to Path().apply { fillType = rule; addPath(p) })
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
            if (closed && pts.size > 2) {
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
                val e = when {
                    r.end < p -> r.end
                    r.end == p -> if (r.start < p) newEnd else p
                    r.end < oldEnd -> newEnd
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
)

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
        val text = (if (t.allCaps) t.text.uppercase() else t.text).ifEmpty { " " }
        val fm = paint.fontMetrics
        val slack = size * 0.35f
        val curve = t.curve.coerceIn(-1f, 1f)
        if (abs(curve) >= 0.02f && !text.contains('\n')) {
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
                r.size?.let { sp.setSpan(android.text.style.AbsoluteSizeSpan(it.coerceIn(1f, 4000f).roundToInt()), a, b, flags) }
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
        if (b.arc != null) {
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
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.fill!! })
        }
        if (s.hasStroke) {
            val closed = s.kind != ShapeKind.PATH || s.closed
            val align = if (closed) s.strokeAlign else StrokeAlign.CENTER
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = s.stroke!!
                strokeWidth = if (align == StrokeAlign.CENTER) s.strokeWidth else s.strokeWidth * 2f
                strokeJoin = if ((s.kind == ShapeKind.RECT && s.radius <= 0f) || s.kind == ShapeKind.ARROW) Paint.Join.MITER else Paint.Join.ROUND
                strokeMiter = 10f
                strokeCap = Paint.Cap.ROUND
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

    /** Layer-local bounds of what [props] draws (generous), or null for nothing. */
    fun bounds(props: LayerProps): RectF? {
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

    /** [props] with its shape or text moved by [m] (layer-local → new layer-local). */
    fun transformed(props: LayerProps, m: Matrix): LayerProps = props.copy(
        shape = props.shape?.let { it.copy(matrix = it.matrix.then(m)) },
        text = props.text?.let { it.copy(matrix = it.matrix.then(m)) },
    )

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
