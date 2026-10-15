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
    PATH("Custom"),
    ;

    /** The next shape when the Shape tool is tapped again (custom shapes come from the pen). */
    val next: ShapeKind get() = when (this) {
        RECT -> ELLIPSE
        ELLIPSE -> STAR
        else -> RECT
    }
}

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
    val path: List<PenPoint> = emptyList(),
    val closed: Boolean = true,
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
        .put("path", JSONArray().also { a ->
            path.forEach { p ->
                a.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.hx.toDouble()).put(p.hy.toDouble()).put(p.ix.toDouble()).put(p.iy.toDouble()))
            }
        })
        .put("closed", closed)
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
                path = if (pts == null) emptyList() else List(pts.length()) { i ->
                    val p = pts.getJSONArray(i)
                    val hx = p.optDouble(2, 0.0).toFloat()
                    val hy = p.optDouble(3, 0.0).toFloat()
                    PenPoint(p.optDouble(0).toFloat(), p.optDouble(1).toFloat(), hx, hy, p.optDouble(4, -hx.toDouble()).toFloat(), p.optDouble(5, -hy.toDouble()).toFloat())
                },
                closed = o.optBoolean("closed", true),
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
            ShapeKind.STAR -> starPath(s.w, s.h, s.points, s.depth)
            ShapeKind.PATH -> penPath(s.path, s.closed)
        }

        fun starPath(w: Float, h: Float, points: Int, depth: Float): Path {
            val n = points.coerceIn(3, 100)
            val d = depth.coerceIn(0f, 0.95f)
            val cx = w / 2f
            val cy = h / 2f
            val p = Path()
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
 * by [align]) of the first line's baseline. [matrix] places it on the layer.
 */
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
) {
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
        val widest = text.split('\n').maxOf { paint.measureText(it) }
        val width = ceil(widest.toDouble()).toInt().coerceAtLeast(1) + 1
        val alignment = when (t.align) {
            TextAlignOption.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignOption.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignOption.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, t.leading.coerceIn(0.5f, 3f))
            .build()
        val left = -width * t.align.anchor
        val top = -layout.getLineBaseline(0).toFloat()
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
                strokeJoin = if (s.kind == ShapeKind.RECT && s.radius <= 0f) Paint.Join.MITER else Paint.Join.ROUND
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
            val pad = (if (s.hasStroke) s.strokeWidth * 1.5f else 0f) + 2f
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
