package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/** How a new selection combines with the one that's there. */
enum class SelectMode(val label: String) { NEW("New"), ADD("Add"), SUBTRACT("Subtract"), INTERSECT("Intersect") }

/** Selection tool shapes. Tapping the Select button again goes to the next one. */
enum class SelectShape(val label: String) {
    RECT("Rectangle"), ELLIPSE("Ellipse"), LASSO("Lasso"), POLYGON("Polygonal lasso");

    val next: SelectShape get() = entries[(ordinal + 1) % entries.size]
}

/**
 * The selected area: an outline in canvas pixels, with optional soft edges ([feather], pixels).
 * Never changed after it's made (changes make a new one), so undo can keep old ones.
 */
class Selection(path: Path, val feather: Float = 0f) {
    val path: Path = Path(path)

    val bounds: RectF = RectF().also { path.computeBounds(it, true) }

    val isEmpty: Boolean get() = path.isEmpty || bounds.width() < 0.5f || bounds.height() < 0.5f

    /** Area the soft edge can reach. */
    val reach: RectF get() = RectF(bounds).apply { inset(-feather * 2f - 2f, -feather * 2f - 2f) }

    @Volatile
    private var cached: TileGrid? = null

    /** How much of each canvas pixel is selected, as 1-byte tiles in canvas coordinates. Made once. */
    @Synchronized
    fun mask(): TileGrid {
        cached?.let { return it }
        val g = TileGrid(alphaOnly = true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            if (feather > 0.5f) maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
        }
        for (k in g.keysIn(reach)) {
            val t = TileGrid.newTile(alphaOnly = true)
            val c = Canvas(t)
            c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
            c.drawPath(path, paint)
            g.tiles[k] = t
        }
        cached = g
        return g
    }

    /**
     * Keeps only the selected part of what's been drawn in the current layer of [canvas]
     * (call between saveLayer and restore). The canvas is in coordinates where canvas pixel (x, y)
     * is at (x - [offX], y - [offY]) — a layer's own coordinates when offX/offY is its position.
     */
    fun keepInside(canvas: Canvas, offX: Float, offY: Float, visible: RectF? = null) {
        val p = Paint().apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        // DST_IN only touches where something is drawn, so draw the whole selection layer at once
        canvas.saveLayer(visible, p)
        mask().drawTo(canvas, -offX, -offY, Paint().apply { isFilterBitmap = true }, visible)
        canvas.restore()
    }

    /** The selected amount over a canvas area, as a 1-byte bitmap the size of [area]. */
    fun bitmapFor(area: Rect): Bitmap {
        val b = Bitmap.createBitmap(area.width().coerceAtLeast(1), area.height().coerceAtLeast(1), Bitmap.Config.ALPHA_8)
        val c = Canvas(b)
        c.translate(-area.left.toFloat(), -area.top.toFloat())
        mask().drawTo(c, 0f, 0f, null)
        return b
    }

    fun withFeather(f: Float) = Selection(path, f.coerceIn(0f, 250f))

    /** Integer canvas bounds of the selected area (for cropping). */
    fun pixelBounds(): Rect = Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())

    companion object {
        /** Combines a new shape with [old] the way [mode] says. Returns null when nothing is left. */
        fun combine(old: Selection?, shape: Path, mode: SelectMode): Selection? {
            val feather = old?.feather ?: 0f
            val result = when {
                old == null || mode == SelectMode.NEW -> Path(shape)
                else -> Path(old.path).apply {
                    op(
                        shape,
                        when (mode) {
                            SelectMode.ADD -> Path.Op.UNION
                            SelectMode.SUBTRACT -> Path.Op.DIFFERENCE
                            SelectMode.INTERSECT -> Path.Op.INTERSECT
                            SelectMode.NEW -> Path.Op.UNION
                        },
                    )
                }
            }
            val s = Selection(result, if (mode == SelectMode.NEW) 0f else feather)
            return if (s.isEmpty) null else s
        }

        fun all(w: Int, h: Int) = Selection(Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW) })

        fun inverse(old: Selection, w: Int, h: Int): Selection? {
            // Reaches past the canvas, so a soft edge doesn't fade the canvas's own edges
            val m = old.feather * 3f + 2f
            val p = Path().apply { addRect(-m, -m, w + m, h + m, Path.Direction.CW) }
            p.op(old.path, Path.Op.DIFFERENCE)
            val s = Selection(p, old.feather)
            return if (s.isEmpty) null else s
        }

        fun rect(r: RectF) = Path().apply { addRect(r, Path.Direction.CW) }
        fun oval(r: RectF) = Path().apply { addOval(r, Path.Direction.CW) }
        fun polygon(points: List<android.graphics.PointF>) = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
    }
}
