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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a new selection combines with the one that's there. */
enum class SelectMode(val label: String) { NEW("New"), ADD("Add"), SUBTRACT("Subtract"), INTERSECT("Intersect") }

/** Selection tool shapes. Tapping the Select button again goes to the next one. */
enum class SelectShape(val label: String) {
    RECT("Rectangle"), ELLIPSE("Ellipse"), LASSO("Lasso"), POLYGON("Polygonal lasso"), WAND("Magic wand"), OBJECT("Object (AI)");

    val next: SelectShape get() = entries[(ordinal + 1) % entries.size]
}

/**
 * The selected area, in canvas pixels, with optional soft edges ([feather], pixels). Never
 * changed after it's made (changes make a new one), so undo can keep old ones.
 *
 * Most selections are an outline ([path]). The magic wand and Select Subject make pixel
 * selections instead: a 1-byte mask ([base]) of how much each pixel is selected, with [path]
 * traced around it for the marching ants. Combining with a pixel selection gives a pixel selection.
 */
class Selection private constructor(outline: Path, val feather: Float, private val base: TileGrid?, tight: RectF?) {

    constructor(path: Path, feather: Float = 0f) : this(path, feather, null, null)

    val path: Path = Path(outline)

    /** Memory a pixel selection's mask takes (0 for an outline). */
    val heldBytes: Long get() = base?.bytes() ?: 0L

    /** Made from pixels (wand, subject) rather than an outline. */
    val isPixelBased: Boolean get() = base != null

    /** The selected area (for a pixel selection: where it's at least half selected). */
    val bounds: RectF = tight ?: RectF().also { path.computeBounds(it, true) }

    val isEmpty: Boolean get() = (base == null && path.isEmpty) || bounds.width() < 0.5f || bounds.height() < 0.5f

    /** Area the soft edge (and a pixel selection's faint parts) can reach. */
    val reach: RectF
        get() {
            val r = RectF(bounds)
            base?.tileBounds()?.let { r.union(RectF(it)) }
            r.inset(-feather * 2f - 2f, -feather * 2f - 2f)
            return r
        }

    @Volatile
    private var cached: TileGrid? = null

    @Volatile
    private var cachedHard: TileGrid? = null

    /** How much of each canvas pixel is selected, as 1-byte tiles in canvas coordinates. Made once. */
    @Synchronized
    fun mask(): TileGrid {
        cached?.let { return it }
        val g = when {
            base == null -> rasterize(path, feather, reach)
            feather <= 0.5f -> base
            else -> blurredGrid(base, feather, reach)
        }
        cached = g
        return g
    }

    /** The selected amount without the soft edge (for combining selections). */
    @Synchronized
    fun hardMask(): TileGrid {
        base?.let { return it }
        cachedHard?.let { return it }
        return rasterize(path, 0f, RectF(bounds).apply { inset(-2f, -2f) }).also { cachedHard = it }
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

    fun withFeather(f: Float) = Selection(path, f.coerceIn(0f, 250f), base, if (base != null) bounds else null)

    /** Integer canvas bounds of the selected area (for cropping). */
    fun pixelBounds(): Rect = Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())

    companion object {
        /** A pixel selection from a 1-byte mask in canvas coordinates (taken over, not copied). Null when it's empty. */
        fun fromMask(base: TileGrid, feather: Float = 0f): Selection? {
            base.dropEmptyTiles()
            // Bounds from the full-size mask (exact, and thin selections aren't lost)
            val tight = Outline.tightBounds(base) ?: return null
            val outline = Outline.trace(base) ?: Path().apply { addRect(tight, Path.Direction.CW) }
            val s = Selection(outline, feather, base, tight)
            return if (s.isEmpty) null else s
        }

        /** Combines a new shape with [old] the way [mode] says. Returns null when nothing is left. */
        fun combine(old: Selection?, shape: Path, mode: SelectMode): Selection? {
            if (old != null && mode != SelectMode.NEW && old.isPixelBased) {
                val b = RectF().also { shape.computeBounds(it, true) }.apply { inset(-2f, -2f) }
                return combineMasks(old, rasterize(shape, 0f, b), mode)
            }
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

        /** Combines a pixel mask (canvas coordinates; taken over) with [old] the way [mode] says. */
        fun combineMask(old: Selection?, add: TileGrid, mode: SelectMode): Selection? {
            if (old == null || mode == SelectMode.NEW) return fromMask(add)
            return combineMasks(old, add, mode)
        }

        private fun combineMasks(old: Selection, add: TileGrid, mode: SelectMode): Selection? {
            val a = old.hardMask().copy()
            fun paint(m: PorterDuff.Mode) = Paint().apply { xfermode = PorterDuffXfermode(m) }
            when (mode) {
                SelectMode.ADD, SelectMode.NEW -> {
                    val p = paint(PorterDuff.Mode.SRC_OVER)
                    for ((k, t) in add.tiles) Canvas(a.getOrCreate(k)).drawBitmap(t, 0f, 0f, p)
                }
                SelectMode.SUBTRACT -> {
                    val p = paint(PorterDuff.Mode.DST_OUT)
                    for ((k, t) in a.tiles) add[k]?.let { Canvas(t).drawBitmap(it, 0f, 0f, p) }
                }
                SelectMode.INTERSECT -> {
                    val p = paint(PorterDuff.Mode.DST_IN)
                    val it = a.tiles.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        val m = add[e.key]
                        if (m == null) it.remove() else Canvas(e.value).drawBitmap(m, 0f, 0f, p)
                    }
                }
            }
            return fromMask(a, old.feather)
        }

        fun all(w: Int, h: Int) = Selection(Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW) })

        fun inverse(old: Selection, w: Int, h: Int): Selection? {
            // Reaches past the canvas, so a soft edge doesn't fade the canvas's own edges
            val m = old.feather * 3f + 2f
            if (old.base != null) {
                val area = RectF(-m, -m, w + m, h + m)
                val inv = TileGrid(alphaOnly = true)
                val out = paintOf(PorterDuff.Mode.DST_OUT)
                for (k in inv.keysIn(area)) {
                    val t = inv.getOrCreate(k)
                    val c = Canvas(t)
                    c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
                    c.drawRect(area, Paint().apply { color = Color.BLACK })
                    old.base[k]?.let { c.drawBitmap(it, (TileGrid.tx(k) * TileGrid.SIZE).toFloat(), (TileGrid.ty(k) * TileGrid.SIZE).toFloat(), out) }
                }
                return fromMask(inv, old.feather)
            }
            val p = Path().apply { addRect(-m, -m, w + m, h + m, Path.Direction.CW) }
            p.op(old.path, Path.Op.DIFFERENCE)
            val s = Selection(p, old.feather)
            return if (s.isEmpty) null else s
        }

        private fun paintOf(m: PorterDuff.Mode) = Paint().apply { xfermode = PorterDuffXfermode(m) }

        /** An outline drawn into 1-byte tiles covering [area] (canvas pixels). */
        private fun rasterize(path: Path, feather: Float, area: RectF): TileGrid {
            val g = TileGrid(alphaOnly = true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                if (feather > 0.5f) maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
            }
            for (k in g.keysIn(area)) {
                val t = TileGrid.newTile(alphaOnly = true)
                val c = Canvas(t)
                c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
                c.drawPath(path, paint)
                g.tiles[k] = t
            }
            return g
        }

        /** A pixel selection with a soft edge: its mask blurred by [feather]. */
        private fun blurredGrid(base: TileGrid, feather: Float, area: RectF): TileGrid {
            val r = Rect(floor(area.left).toInt(), floor(area.top).toInt(), ceil(area.right).toInt(), ceil(area.bottom).toInt())
            val bmp = Bitmap.createBitmap(r.width().coerceAtLeast(1), r.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            base.drawTo(Canvas(bmp), -r.left.toFloat(), -r.top.toFloat(), Paint().apply { color = Color.BLACK })
            val soft = try {
                Gpu.blurred(bmp, feather)
            } finally {
                bmp.recycle()
            }
            return TileGrid.fromBitmap(soft, r.left, r.top, alphaOnly = true).also { soft.recycle() }
        }

        fun rect(r: RectF) = Path().apply { addRect(r, Path.Direction.CW) }
        fun oval(r: RectF) = Path().apply { addOval(r, Path.Direction.CW) }
        fun polygon(points: List<android.graphics.PointF>) = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
    }
}

/** Traces the outline of a pixel selection (for the marching ants and its bounds). */
object Outline {
    /** Longest side the outline is traced at (bigger masks are traced at a reduced size). */
    private const val MAX_TRACE = 1600

    /**
     * Exact bounds of a mask's at-least-half-selected pixels (or, if it's all faint, of any selected
     * pixel), in canvas pixels. Null when nothing at all is selected.
     */
    fun tightBounds(mask: TileGrid): RectF? {
        val n = TileGrid.SIZE
        val buf = ByteArray(n * n)
        val strong = IntArray(4).also { it[0] = Int.MAX_VALUE; it[1] = Int.MAX_VALUE; it[2] = Int.MIN_VALUE; it[3] = Int.MIN_VALUE }
        val faint = strong.copyOf()
        for ((k, t) in mask.tiles) {
            if (t.config != Bitmap.Config.ALPHA_8 || t.width != n || t.height != n) continue
            t.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buf))
            val ox = TileGrid.tx(k) * n
            val oy = TileGrid.ty(k) * n
            for (y in 0 until n) {
                val row = y * n
                for (x in 0 until n) {
                    val a = buf[row + x].toInt() and 0xFF
                    if (a == 0) continue
                    val b = if (a >= 128) strong else faint
                    val cx = ox + x
                    val cy = oy + y
                    if (cx < b[0]) b[0] = cx
                    if (cy < b[1]) b[1] = cy
                    if (cx > b[2]) b[2] = cx
                    if (cy > b[3]) b[3] = cy
                }
            }
        }
        for (b in listOf(strong, faint)) {
            if (b[2] >= b[0]) return RectF(b[0].toFloat(), b[1].toFloat(), (b[2] + 1).toFloat(), (b[3] + 1).toFloat())
        }
        return null
    }

    /**
     * The edge around a mask's selected pixels (at least half selected; when traced at a reduced
     * size, anything noticeably selected so thin parts stay), in canvas pixels. Null when empty.
     */
    fun trace(mask: TileGrid): Path? {
        val region = mask.tileBounds() ?: return null
        val s = min(1f, MAX_TRACE.toFloat() / max(region.width(), region.height()))
        val w = (region.width() * s).roundToInt().coerceAtLeast(1)
        val h = (region.height() * s).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(small).apply {
            scale(w / region.width().toFloat(), h / region.height().toFloat())
            translate(-region.left.toFloat(), -region.top.toFloat())
            mask.drawTo(this, 0f, 0f, Paint().apply { isFilterBitmap = true })
        }
        val bytes = ByteArray(w * h)
        small.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
        small.recycle()
        val threshold = if (s < 1f) 32 else 128
        val inside = BooleanArray(w * h) { (bytes[it].toInt() and 0xFF) >= threshold }
        fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && inside[y * w + x]

        if (inside.none { it }) return null

        // Pixel edges between selected and not, each pointing so the selected side is on its right;
        // they join up into closed loops (holes run the other way)
        val vw = w + 1
        val out1 = IntArray(vw * (h + 1)) { -1 }
        val out2 = IntArray(vw * (h + 1)) { -1 }
        fun v(x: Int, y: Int) = y * vw + x
        fun edge(a: Int, b: Int) {
            if (out1[a] < 0) out1[a] = b else out2[a] = b
        }
        for (y in 0..h) for (x in 0 until w) {
            val above = at(x, y - 1)
            val below = at(x, y)
            if (below && !above) edge(v(x, y), v(x + 1, y))
            if (above && !below) edge(v(x + 1, y), v(x, y))
        }
        for (x in 0..w) for (y in 0 until h) {
            val left = at(x - 1, y)
            val right = at(x, y)
            if (right && !left) edge(v(x, y + 1), v(x, y))
            if (left && !right) edge(v(x, y), v(x, y + 1))
        }
        val path = Path()
        val sx = region.width().toFloat() / w
        val sy = region.height().toFloat() / h
        fun px(i: Int) = region.left + (i % vw) * sx
        fun py(i: Int) = region.top + (i / vw) * sy
        for (start in out1.indices) {
            while (out1[start] >= 0) {
                path.moveTo(px(start), py(start))
                var cur = start
                var lastDx = 0
                var lastDy = 0
                var guard = 0
                while (guard++ < out1.size) {
                    val nxt: Int
                    if (out2[cur] >= 0) {
                        // Two ways on (pixels touching at a corner): take the second, keep the first for later
                        nxt = out2[cur]
                        out2[cur] = -1
                    } else {
                        nxt = out1[cur]
                        out1[cur] = -1
                    }
                    if (nxt < 0) break
                    val dx = (nxt % vw) - (cur % vw)
                    val dy = (nxt / vw) - (cur / vw)
                    // Only corners become points
                    if (dx != lastDx || dy != lastDy) path.lineTo(px(cur), py(cur))
                    lastDx = dx
                    lastDy = dy
                    cur = nxt
                    if (cur == start) break
                }
                path.close()
            }
        }
        path.fillType = Path.FillType.WINDING
        return path
    }
}
