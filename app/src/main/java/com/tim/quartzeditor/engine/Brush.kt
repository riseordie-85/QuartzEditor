package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Brush tip shapes. [spacing] is the distance between dabs as a fraction of the size. */
enum class BrushTip(val label: String, val spacing: Float, val randomRotation: Boolean) {
    ROUND("Round", 0.1f, false),
    SQUARE("Square", 0.1f, false),
    TRIANGLE("Triangle", 0.1f, false),
    GRUNGE("Grunge", 0.22f, true),
    ;

    companion object {
        fun byName(n: String?): BrushTip = entries.firstOrNull { it.name == n } ?: ROUND
    }
}

/** Builds the 1-byte coverage image for one dab of a brush. */
object Dabs {
    private data class Key(val tip: BrushTip, val size: Int, val softness: Int)

    private var lastKey: Key? = null
    private var last: Bitmap? = null

    /** A [size]×[size] coverage image. [softness] 0 = hard edge, 1 = fully soft. Cached for the last settings. */
    @Synchronized
    fun get(tip: BrushTip, size: Int, softness: Float): Bitmap {
        val key = Key(tip, size.coerceIn(1, MAX_SIZE), (softness.coerceIn(0f, 1f) * 100).roundToInt())
        last?.let { if (key == lastKey && !it.isRecycled) return it }
        val bmp = make(key.tip, key.size, key.softness / 100f)
        lastKey = key
        last = bmp
        return bmp
    }

    const val MAX_SIZE = 1000

    fun make(tip: BrushTip, size: Int, softness: Float): Bitmap {
        val d = size.coerceAtLeast(1)
        val out = ByteArray(d * d)
        val r = d / 2f
        val c = (d - 1) / 2f
        when (tip) {
            BrushTip.ROUND, BrushTip.GRUNGE -> {
                // Edge fade width: at least 1px so hard brushes are still smooth
                val w = max(1f, softness * r)
                for (y in 0 until d) for (x in 0 until d) {
                    val dist = hypot(x - c, y - c)
                    var a = edge((r - dist) / w)
                    if (tip == BrushTip.GRUNGE && a > 0f) a *= grunge(x.toFloat() / d, y.toFloat() / d)
                    out[y * d + x] = (a * 255f).roundToInt().toByte()
                }
            }
            BrushTip.SQUARE -> {
                val w = max(1f, softness * r)
                for (y in 0 until d) for (x in 0 until d) {
                    val dist = max(abs(x - c), abs(y - c))
                    out[y * d + x] = (edge((r - dist) / w) * 255f).roundToInt().toByte()
                }
            }
            BrushTip.TRIANGLE -> {
                // Equilateral, pointing up, corners on the dab's circle
                val inR = r / 2f
                val w = max(1f, softness * inR * 1.5f)
                val s3 = sqrt(3f) / 2f
                for (y in 0 until d) for (x in 0 until d) {
                    val px = x - c
                    val py = y - c + inR * 0.25f // nudge down so it looks centered
                    val e1 = py // bottom edge, normal (0, 1)
                    val e2 = -s3 * px - 0.5f * py // upper-left edge
                    val e3 = s3 * px - 0.5f * py // upper-right edge
                    val sd = max(e1, max(e2, e3)) - inR
                    out[y * d + x] = (edge(-sd / w) * 255f).roundToInt().toByte()
                }
            }
        }
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        return bmp
    }

    /** 0 outside, 1 inside, smooth between. */
    private fun edge(t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    // ------------------------------------------------------------ grunge texture

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun noise(x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0) + (hash(x0 + 1, y0) - hash(x0, y0)) * sx
        val b = hash(x0, y0 + 1) + (hash(x0 + 1, y0 + 1) - hash(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }

    /** Rough, speckled coverage (same pattern at every size). */
    private fun grunge(u: Float, v: Float): Float {
        val n = noise(u * 6f, v * 6f) * 0.5f + noise(u * 14f + 7f, v * 14f + 3f) * 0.3f + noise(u * 34f + 1f, v * 34f + 9f) * 0.2f
        return ((n - 0.38f) * 3.2f).coerceIn(0f, 1f)
    }
}

/** What a stroke changes. */
enum class StrokeTarget { PIXELS, MASK }

/**
 * One brush or eraser stroke in progress. Dabs build up in a separate coverage buffer; the canvas
 * shows the buffer on top of the layer (so the stroke's opacity is even, the way Photoshop does
 * it), and [commit] applies it to the layer in one go.
 *
 * Coordinates passed in are canvas pixels; the buffer is in the layer's own coordinates.
 */
class StrokeSession(
    val layer: Layer,
    val target: StrokeTarget,
    val erase: Boolean,
    /** Paint color (pixels) or the gray level to paint into the mask. */
    val color: Int,
    /** Background color: the eraser paints it on layers with locked transparency. */
    val backgroundColor: Int,
    val opacity: Float,
    val tip: BrushTip,
    val size: Float,
    val softness: Float,
    /** Painting only reaches inside this (null = everywhere). */
    val selection: Selection? = null,
) {
    val buffer = TileGrid(alphaOnly = true)
    private val canvases = HashMap<Long, Canvas>()
    private val dab = Dabs.get(tip, size.roundToInt().coerceAtLeast(1), softness)
    private val dabPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    /** Distance still to travel before the next dab. */
    private var carry = 0f
    private val spacing = max(1f, size * tip.spacing)
    private val rng = java.util.Random(size.toLong() * 31 + tip.ordinal)

    /** Canvas-space area the stroke has touched so far. */
    val dirty = RectF()

    var dabCount = 0
        private set

    /** The last point painted (canvas pixels), or null before the first. */
    val lastPoint: android.graphics.PointF?
        get() = if (lastX.isNaN()) null else android.graphics.PointF(lastX + layer.x, lastY + layer.y)

    val lockAlpha: Boolean get() = target == StrokeTarget.PIXELS && layer.props.lockAlpha

    /** Adds the next touch point (canvas pixels). */
    fun addPoint(cx: Float, cy: Float) {
        val x = cx - layer.x
        val y = cy - layer.y
        if (lastX.isNaN()) {
            stamp(x, y)
            lastX = x
            lastY = y
            carry = spacing
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        if (len <= 0f) return
        var t = carry
        while (t <= len) {
            stamp(lastX + dx * t / len, lastY + dy * t / len)
            t += spacing
        }
        carry = t - len
        lastX = x
        lastY = y
    }

    private fun stamp(x: Float, y: Float) {
        val half = dab.width / 2f
        val rotate = tip.randomRotation
        val reach = if (rotate) half * 1.42f else half
        val r = RectF(x - reach, y - reach, x + reach, y + reach)
        // One angle per dab, the same in every tile it touches
        val angle = if (rotate) rng.nextFloat() * 360f else 0f
        for (k in buffer.keysIn(r)) {
            val tile = buffer.getOrCreate(k)
            val c = canvases.getOrPut(k) { Canvas(tile) }
            val ox = (TileGrid.tx(k) * TileGrid.SIZE).toFloat()
            val oy = (TileGrid.ty(k) * TileGrid.SIZE).toFloat()
            c.save()
            c.translate(-ox, -oy)
            if (rotate) c.rotate(angle, x, y)
            c.drawBitmap(dab, x - half, y - half, dabPaint)
            c.restore()
        }
        dirty.union(r.left + layer.x, r.top + layer.y, r.right + layer.x, r.bottom + layer.y)
        dabCount++
    }

    // ------------------------------------------------------------ how the buffer is applied

    /** Gray level → how much the mask hides (mask stores "hidden"). Eraser on a mask reveals. */
    private val maskHidden: Float
        get() = if (erase) 0f else 1f - grayLevel(color)

    /**
     * Draws the buffer onto pixels that are already on [canvas] (the layer's tiles), exactly the way
     * [commit] will apply it. Used for both the live preview and the final result.
     */
    fun applyToPixels(canvas: Canvas, dx: Float, dy: Float, visible: RectF?, buf: TileGrid = buffer, smooth: Boolean = true) {
        val p = Paint().apply { isFilterBitmap = smooth }
        p.alpha = (opacity * 255).roundToInt()
        when {
            erase && lockAlpha -> {
                p.color = backgroundColor or 0xFF000000.toInt()
                p.alpha = (opacity * 255).roundToInt()
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            }
            erase -> p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            else -> {
                p.color = color or 0xFF000000.toInt()
                p.alpha = (opacity * 255).roundToInt()
                p.xfermode = PorterDuffXfermode(if (lockAlpha) PorterDuff.Mode.SRC_ATOP else PorterDuff.Mode.SRC_OVER)
            }
        }
        drawBuffer(canvas, dx, dy, visible, buf, p)
    }

    /**
     * Draws the coverage buffer with [paint]. With a selection, the stroke is first drawn at full
     * strength into its own layer, cut to the selection, then applied with the paint's mode and
     * strength — so soft selection edges fade the stroke exactly once.
     */
    private fun drawBuffer(canvas: Canvas, dx: Float, dy: Float, visible: RectF?, buf: TileGrid, paint: Paint) {
        val sel = selection
        if (sel == null) {
            buf.drawTo(canvas, dx, dy, paint, visible)
            return
        }
        val apply = Paint().apply {
            alpha = paint.alpha
            xfermode = paint.xfermode
        }
        canvas.saveLayer(visible, apply)
        val full = Paint(paint).apply {
            alpha = 255
            xfermode = null
        }
        buf.drawTo(canvas, dx, dy, full, visible)
        sel.keepInside(canvas, layer.x - dx, layer.y - dy, visible)
        canvas.restore()
    }

    /** Same idea for a mask: pull the hidden amount toward the target level where the stroke covers. */
    fun applyToMask(canvas: Canvas, dx: Float, dy: Float, visible: RectF?, buf: TileGrid = buffer, smooth: Boolean = true) {
        val a = (opacity * 255).roundToInt()
        val out = Paint().apply {
            isFilterBitmap = smooth
            alpha = a
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        drawBuffer(canvas, dx, dy, visible, buf, out)
        val v = maskHidden
        if (v > 0.002f) {
            val add = Paint().apply {
                isFilterBitmap = smooth
                color = Color.BLACK
                alpha = (opacity * v * 255).roundToInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            }
            drawBuffer(canvas, dx, dy, visible, buf, add)
        }
    }

    /** Applies the stroke to the layer. Returns the undo step, or null if nothing changed. */
    fun commit(): Change? {
        if (dabCount == 0 && buffer.isEmpty) return null
        val grid: TileGrid = when (target) {
            StrokeTarget.PIXELS -> layer.tiles
            StrokeTarget.MASK -> layer.mask ?: return null
        }
        val saved = HashMap<Long, Bitmap?>()
        for (k in buffer.tiles.keys) {
            val existing = grid[k]
            // Nothing to change: erasing empty pixels, painting into locked transparency,
            // or revealing an area of the mask that's already fully shown
            if (existing == null) {
                val skip = when (target) {
                    StrokeTarget.PIXELS -> erase || lockAlpha
                    StrokeTarget.MASK -> maskHidden <= 0.002f
                }
                if (skip) continue
            }
            // Copy-on-write: the old tile stays untouched (for undo, and for a save that may be reading it)
            val tile = grid.editableCopy(existing)
            saved[k] = existing
            grid.tiles[k] = tile
            val c = Canvas(tile)
            val ox = -(TileGrid.tx(k) * TileGrid.SIZE).toFloat()
            val oy = -(TileGrid.ty(k) * TileGrid.SIZE).toFloat()
            c.translate(ox, oy)
            // Only this one buffer tile, so neighbors don't get applied twice
            val one = TileGrid(alphaOnly = true)
            one.tiles[k] = buffer.tiles.getValue(k)
            if (target == StrokeTarget.PIXELS) applyToPixels(c, 0f, 0f, null, one) else applyToMask(c, 0f, 0f, null, one)
        }
        if (saved.isEmpty()) return null
        val label = when {
            target == StrokeTarget.MASK -> "Mask stroke"
            erase -> "Eraser"
            else -> "Brush"
        }
        return TilePatch(layer, grid, saved, label)
    }
}

/** Size slider position (0..1) ↔ brush size in pixels, finer at the small end. */
object BrushSizeScale {
    const val MIN = 1f
    const val MAX = Dabs.MAX_SIZE.toFloat()
    fun toPx(t: Float): Float = MIN + (MAX - MIN) * t.coerceIn(0f, 1f).let { it * it * it }
    fun toT(px: Float): Float = ((px - MIN) / (MAX - MIN)).coerceIn(0f, 1f).let { Math.cbrt(it.toDouble()).toFloat() }
}

/** Gray level of a color as people see it (sRGB, 0 = black, 1 = white), for masks. */
fun grayLevel(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
