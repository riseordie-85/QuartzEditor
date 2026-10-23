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

    private data class LevelKey(val tip: BrushTip, val size: Int, val softness: Int, val level: Int)
    private val levels = HashMap<LevelKey, Bitmap>()

    /**
     * The dab at 1/2^[level] of [size] (made the same way, not shrunk), so a pen pressing lightly
     * draws small dabs from a picture about their size instead of squeezing a big one. Kept for
     * the last brush only.
     */
    @Synchronized
    fun level(tip: BrushTip, size: Int, softness: Float, level: Int): Bitmap {
        if (level <= 0) return get(tip, size, softness)
        val s = size.coerceIn(1, MAX_SIZE)
        val soft = (softness.coerceIn(0f, 1f) * 100).roundToInt()
        val key = LevelKey(tip, s, soft, level)
        levels[key]?.let { if (!it.isRecycled) return it }
        if (levels.keys.any { it.tip != tip || it.size != s || it.softness != soft }) levels.clear()
        val bmp = make(tip, max(1, s shr level), soft / 100f)
        levels[key] = bmp
        return bmp
    }

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
 * How a pen changes each dab. Pressure (0..1, already on the pen's curve) can shrink the dab down
 * to [minSize] of the brush and/or lower how much it covers; tilting the pen stretches the dab
 * along the way the pen leans, like the side of a pencil.
 */
data class StrokeDynamics(
    /** Pressure changes the size, down to this fraction of it (null = it doesn't). */
    val minSize: Float? = null,
    /** Pressure changes how strongly it paints (the stroke's opacity). */
    val opacity: Boolean = false,
    /** Tilt stretches and turns the dab. */
    val tilt: Boolean = false,
    /** How far (canvas pixels) the stroke grows from thin to full size where it starts, and thins out where it ends. */
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
) {
    val any: Boolean get() = minSize != null || opacity || tilt || taperStart > 0f || taperEnd > 0f

    companion object {
        val NONE = StrokeDynamics()

        /** How much tilt (radians from upright) stretches the dab: none near upright, most when nearly flat. */
        fun tiltStretch(tilt: Float): Float {
            val t = ((tilt - 0.35f) / (1.2f - 0.35f)).coerceIn(0f, 1f)
            return 1f + 1.6f * t * t * (3f - 2f * t)
        }
    }
}

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
    /** What a pen's pressure and tilt do, and the tapers (fingers send full pressure and no tilt). */
    val dynamics: StrokeDynamics = StrokeDynamics.NONE,
) {
    val buffer = TileGrid(alphaOnly = true)
    private val canvases = HashMap<Long, Canvas>()
    private val dabSize = size.roundToInt().coerceAtLeast(1)
    private val dab = Dabs.get(tip, dabSize, softness)
    private val dabPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    /** Pressure, tilt and lean direction at the last point. */
    private var lastP = 1f
    private var lastTilt = 0f
    private var lastAz = 0f
    /** Distance still to travel before the next dab. */
    private var carry = 0f
    private val spacing = max(1f, size * tip.spacing)
    private val rng = java.util.Random(size.toLong() * 31 + tip.ordinal)
    /** How far the stroke has gone (layer pixels along the path). */
    private var travelled = 0f

    /** One dab as placed: redrawn thinner when the stroke ends with a taper. */
    private class DabAt(val x: Float, val y: Float, val p: Float, val tilt: Float, val az: Float, val along: Float, val angle: Float)

    /**
     * With an end taper: the stroke as it is without its last stretch of dabs ([tailDabs], still
     * drawn in [buffer] at full size), so the stretch can be drawn again thinning out when it ends.
     */
    private val base: TileGrid? = if (dynamics.taperEnd > 0f) TileGrid(alphaOnly = true) else null
    private val baseCanvases = HashMap<Long, Canvas>()
    private val tailDabs = ArrayDeque<DabAt>()
    private var baseDabs = 0

    /** Canvas-space area the stroke has touched so far. */
    val dirty = RectF()

    var dabCount = 0
        private set

    /** The last point painted (canvas pixels), or null before the first. */
    val lastPoint: android.graphics.PointF?
        get() = if (lastX.isNaN()) null else android.graphics.PointF(lastX + layer.x, lastY + layer.y)

    val lockAlpha: Boolean get() = target == StrokeTarget.PIXELS && layer.props.lockAlpha

    /**
     * Adds the next touch point (canvas pixels), with the pen's [pressure] (0..1, on its curve),
     * [tilt] (radians from upright) and [azimuth] (which way it leans: radians, 0 = toward the top
     * of the screen, clockwise). Fingers leave them at full pressure, upright.
     */
    fun addPoint(cx: Float, cy: Float, pressure: Float = 1f, tilt: Float = 0f, azimuth: Float = 0f) {
        val x = cx - layer.x
        val y = cy - layer.y
        val p = pressure.coerceIn(0f, 1f)
        if (lastX.isNaN()) {
            place(x, y, p, tilt, azimuth, 0f)
            lastX = x
            lastY = y
            lastP = p
            lastTilt = tilt
            lastAz = azimuth
            carry = spacingAt(p, 0f)
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        if (len <= 0f) {
            lastP = p
            lastTilt = tilt
            lastAz = azimuth
            return
        }
        // The lean direction takes the short way round between the two points
        var dAz = azimuth - lastAz
        if (dAz > Math.PI) dAz -= (2 * Math.PI).toFloat() else if (dAz < -Math.PI) dAz += (2 * Math.PI).toFloat()
        var t = carry
        while (t <= len) {
            val f = t / len
            val pf = lastP + (p - lastP) * f
            place(lastX + dx * f, lastY + dy * f, pf, lastTilt + (tilt - lastTilt) * f, lastAz + dAz * f, travelled + t)
            t += spacingAt(pf, travelled + t)
        }
        carry = t - len
        travelled += len
        lastX = x
        lastY = y
        lastP = p
        lastTilt = tilt
        lastAz = azimuth
    }

    /**
     * The stroke is over: its end thins out over the end taper (the last stretch is drawn again,
     * thinner toward the end). Called once, before [commit].
     */
    fun finish() {
        // A tap with a start taper: a dot at full size, not the taper's thin start
        if (dynamics.taperStart > 0f && travelled < max(1f, size * 0.25f) && !lastX.isNaN()) {
            stamp(buffer, canvases, lastX, lastY, lastP, lastTilt, lastAz, tailDabs.lastOrNull()?.angle ?: 0f, 1f)
            return
        }
        val b = base ?: return
        // A short stroke tapers over its second half
        val len = min(dynamics.taperEnd, travelled * 0.5f)
        if (tailDabs.isEmpty() || len < 1f) return
        // The buffer goes back to the stroke without its last stretch, which is drawn again tapering
        buffer.tiles.clear()
        buffer.tiles.putAll(b.tiles)
        canvases.clear()
        canvases.putAll(baseCanvases)
        dabCount = baseDabs
        val endAt = travelled
        fun factorAt(along: Float) = taperFactor(along) * ease((endAt - along) / len)
        val dabs = tailDabs.toList()
        for ((i, d) in dabs.withIndex()) {
            stamp(buffer, canvases, d.x, d.y, d.p, d.tilt, d.az, d.angle, factorAt(d.along))
            // As the dabs get smaller they need to be closer: more go in between (so the tip isn't beaded)
            val n = dabs.getOrNull(i + 1) ?: break
            val gap = n.along - d.along
            if (gap <= 0f) continue
            var t = max(1f, size * pressureFactor(d.p) * factorAt(d.along) * tip.spacing)
            while (t < gap - 0.5f) {
                val f = t / gap
                val along = d.along + gap * f
                val p = d.p + (n.p - d.p) * f
                stamp(buffer, canvases, d.x + (n.x - d.x) * f, d.y + (n.y - d.y) * f, p, d.tilt + (n.tilt - d.tilt) * f, d.az, d.angle, factorAt(along))
                t += max(1f, size * pressureFactor(p) * factorAt(along) * tip.spacing)
            }
        }
        tailDabs.clear()
    }

    /** 0 → the taper's thin end, 1 → full size; eased so the point looks drawn, not cut. */
    private fun ease(t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return 0.06f + 0.94f * (v * (2f - v))
    }

    /** How big a dab is at [along] pixels into the stroke, by the start taper alone (the end's is applied when it ends). */
    private fun taperFactor(along: Float): Float = if (dynamics.taperStart > 0f) ease(along / dynamics.taperStart) else 1f

    /** How big a dab is at pressure [p], as a fraction of the brush. */
    private fun pressureFactor(p: Float): Float {
        val m = dynamics.minSize ?: return 1f
        return m + (1f - m) * p
    }

    /** Dabs get closer together as they get smaller, so a light or tapered line stays a line. */
    private fun spacingAt(p: Float, along: Float): Float {
        val k = pressureFactor(p) * taperFactor(along)
        return if (k >= 0.999f) spacing else max(1f, size * k * tip.spacing)
    }

    /** A dab of the stroke at layer-local ([x], [y]), [along] pixels into it. */
    private fun place(x: Float, y: Float, p: Float, tilt: Float, az: Float, along: Float) {
        // One angle per dab, the same in every tile it touches (and when it's drawn again tapering)
        val angle = if (tip.randomRotation) rng.nextFloat() * 360f else 0f
        val k = taperFactor(along)
        stamp(buffer, canvases, x, y, p, tilt, az, angle, k)
        val b = base ?: return
        tailDabs.addLast(DabAt(x, y, p, tilt, az, along, angle))
        // Dabs further back than the end taper reaches are final: into the stroke without its tail
        while (tailDabs.isNotEmpty() && along - tailDabs.first().along > dynamics.taperEnd) {
            val d = tailDabs.removeFirst()
            stamp(b, baseCanvases, d.x, d.y, d.p, d.tilt, d.az, d.angle, taperFactor(d.along), counts = false)
            baseDabs++
        }
    }

    /**
     * Draws one dab into [grid]: [taper] (0..1) and the pressure shrink it, tilt stretches it, and with
     * pressure on opacity its strength follows the pressure. [counts]: it's one of the dabs on screen.
     */
    private fun stamp(
        grid: TileGrid, cs: HashMap<Long, Canvas>, x: Float, y: Float, p: Float, tilt: Float, azimuth: Float,
        randomAngle: Float, taper: Float, counts: Boolean = true,
    ) {
        val k = pressureFactor(p) * taper
        val drawn = size * k
        // Tilt stretches the dab along the way the pen leans (not for tips that turn at random)
        val stretch = if (dynamics.tilt && !tip.randomRotation) StrokeDynamics.tiltStretch(tilt) else 1f
        val half = if (k >= 0.999f && stretch == 1f) dab.width / 2f else max(0.5f, drawn / 2f)
        val rotate = tip.randomRotation
        val reach = when {
            rotate -> half * 1.42f
            stretch > 1f -> half * stretch * (if (tip == BrushTip.ROUND) 1f else 1.42f)
            else -> half
        }
        val r = RectF(x - reach, y - reach, x + reach, y + reach)
        val angle = when {
            rotate -> randomAngle
            stretch > 1f -> Math.toDegrees(azimuth.toDouble()).toFloat() - 90f
            else -> 0f
        }
        // Pressure on opacity: overlapping dabs build up to the pressure's strength, not past it (the
        // first dab on its own is the full strength, so a tap or a short stroke isn't faint)
        val first = (if (counts) dabCount else baseDabs) == 0
        val alpha = if (dynamics.opacity && p < 0.999f && first) p else if (dynamics.opacity && p < 0.999f) {
            val overlap = max(1f, drawn / max(1f, size * k * tip.spacing))
            (1f - Math.pow((1f - p).toDouble(), 1.0 / overlap).toFloat()).coerceIn(0f, 1f)
        } else 1f
        if (alpha <= 0.002f) return
        val paint = dabPaint
        paint.alpha = (alpha * 255f).roundToInt().coerceIn(1, 255)
        // A smaller dab comes from a picture about its size (each level is half the last)
        var level = 0
        if (k < 0.999f) while (level < 10 && (dabSize shr (level + 1)) >= max(2f, drawn)) level++
        val img = if (level == 0) dab else Dabs.level(tip, dabSize, softness, level)
        val plain = k >= 0.999f && stretch == 1f
        for (key in grid.keysIn(r)) {
            val tile = grid.getOrCreate(key)
            val c = cs.getOrPut(key) { Canvas(tile) }
            val ox = (TileGrid.tx(key) * TileGrid.SIZE).toFloat()
            val oy = (TileGrid.ty(key) * TileGrid.SIZE).toFloat()
            c.save()
            c.translate(-ox, -oy)
            if (plain) {
                if (rotate) c.rotate(angle, x, y)
                c.drawBitmap(img, x - half, y - half, paint)
            } else {
                c.translate(x, y)
                if (angle != 0f) c.rotate(angle)
                if (stretch != 1f) c.scale(stretch, 1f)
                c.drawBitmap(img, null, RectF(-half, -half, half, half), paint)
            }
            c.restore()
        }
        dirty.union(r.left + layer.x, r.top + layer.y, r.right + layer.x, r.bottom + layer.y)
        if (counts) dabCount++
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
