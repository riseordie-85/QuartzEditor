package com.tim.quartzeditor.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// ===================================================================== gradients

enum class GradientType(val label: String) { LINEAR("Linear"), RADIAL("Radial"), REFLECTED("Reflected") }

/**
 * A gradient to pick: ready-made ([dynamic] ones use your foreground/background colors), or one
 * you made ([custom]), whose colors can sit anywhere along it ([positions] 0..1; null = evenly).
 */
data class GradientPreset(
    val name: String,
    val colors: List<Int>,
    val dynamic: Dynamic = Dynamic.NONE,
    val positions: List<Float>? = null,
    val custom: Boolean = false,
) {
    enum class Dynamic { NONE, FG_BG, FG_TRANSPARENT }

    fun resolve(fg: Int, bg: Int): List<Int> = when (dynamic) {
        Dynamic.NONE -> colors
        Dynamic.FG_BG -> listOf(fg, bg)
        Dynamic.FG_TRANSPARENT -> listOf(fg, fg and 0x00FFFFFF)
    }

    companion object {
        val ALL = listOf(
            GradientPreset("Foreground to background", emptyList(), Dynamic.FG_BG),
            GradientPreset("Foreground to transparent", emptyList(), Dynamic.FG_TRANSPARENT),
            GradientPreset("Black to white", listOf(Color.BLACK, Color.WHITE)),
            GradientPreset("Sunset", listOf(0xFF2B1055.toInt(), 0xFFD53A9D.toInt(), 0xFFFF8C42.toInt(), 0xFFFFD166.toInt())),
            GradientPreset("Ocean", listOf(0xFF03045E.toInt(), 0xFF0077B6.toInt(), 0xFF00B4D8.toInt(), 0xFF90E0EF.toInt())),
            GradientPreset("Fire", listOf(0xFF3A0000.toInt(), 0xFFB80000.toInt(), 0xFFFF6A00.toInt(), 0xFFFFE259.toInt())),
            GradientPreset("Forest", listOf(0xFF0B3D2E.toInt(), 0xFF2D6A4F.toInt(), 0xFF95D5B2.toInt())),
            GradientPreset("Violet", listOf(0xFF3A0CA3.toInt(), 0xFF7209B7.toInt(), 0xFFF72585.toInt())),
            GradientPreset("Rainbow", listOf(0xFFFF0000.toInt(), 0xFFFF9900.toInt(), 0xFFFFEE00.toInt(), 0xFF33CC33.toInt(), 0xFF0099FF.toInt(), 0xFF6633CC.toInt())),
            GradientPreset("Chrome", listOf(0xFF2B2B2B.toInt(), 0xFFF5F5F5.toInt(), 0xFF6E6E6E.toInt(), 0xFFE0E0E0.toInt(), 0xFF3C3C3C.toInt())),
            GradientPreset("Gold", listOf(0xFF7A4E0B.toInt(), 0xFFF6D365.toInt(), 0xFFB8860B.toInt(), 0xFFFFF1A8.toInt())),
            GradientPreset("Pastel", listOf(0xFFFFC8DD.toInt(), 0xFFBDE0FE.toInt(), 0xFFCDF5D0.toInt())),
            GradientPreset("Neon", listOf(0xFF00F5D4.toInt(), 0xFF9B5DE5.toInt(), 0xFFF15BB5.toInt())),
            GradientPreset("Ice", listOf(0xFFFFFFFF.toInt(), 0xFFCAF0F8.toInt(), 0xFF48CAE4.toInt())),
            GradientPreset("Night sky", listOf(0xFF000814.toInt(), 0xFF001D3D.toInt(), 0xFF003566.toInt(), 0xFF5A189A.toInt())),
            GradientPreset("Peach", listOf(0xFFFFE5D9.toInt(), 0xFFFFB4A2.toInt(), 0xFFE5989B.toInt())),
        )
    }
}

/** What the Gradient tool paints. Colors run from where you start dragging to where you stop. */
data class GradientSpec(
    val colors: List<Int>,
    val type: GradientType = GradientType.LINEAR,
    val opacity: Float = 1f,
    val reverse: Boolean = false,
    /** Where each color sits, 0..1 (null: evenly spaced). */
    val positions: List<Float>? = null,
)

object GradientPainter {

    fun shader(spec: GradientSpec, start: PointF, end: PointF, map: (Int) -> Int = { it }): Shader {
        var cols = spec.colors.map(map)
        var pos = spec.positions?.takeIf { it.size == cols.size }
        if (cols.size < 2) {
            cols = listOf(cols.firstOrNull() ?: Color.BLACK, cols.firstOrNull() ?: Color.BLACK)
            pos = null
        }
        if (spec.reverse) {
            cols = cols.reversed()
            pos = pos?.reversed()?.map { 1f - it }
        }
        // Android wants the positions in order
        pos = pos?.let { p -> p.runningReduce { a, b -> max(a, b) }.map { it.coerceIn(0f, 1f) } }
        val arr = cols.toIntArray()
        val len = max(1f, hypot(end.x - start.x, end.y - start.y))
        return when (spec.type) {
            GradientType.LINEAR -> LinearGradient(start.x, start.y, end.x, end.y, arr, pos?.toFloatArray(), Shader.TileMode.CLAMP)
            GradientType.RADIAL -> RadialGradient(start.x, start.y, len, arr, pos?.toFloatArray(), Shader.TileMode.CLAMP)
            GradientType.REFLECTED -> {
                // Same colors both ways from the start point
                if (pos == null) {
                    val mirrored = (cols.reversed() + cols.drop(1)).toIntArray()
                    LinearGradient(2 * start.x - end.x, 2 * start.y - end.y, end.x, end.y, mirrored, null, Shader.TileMode.CLAMP)
                } else {
                    // Both halves in full: the first color stays flat across the middle when it doesn't start at 0
                    val mirrored = (cols.reversed() + cols).toIntArray()
                    val mp = (pos.reversed().map { 0.5f - it / 2f } + pos.map { 0.5f + it / 2f }).toFloatArray()
                    LinearGradient(2 * start.x - end.x, 2 * start.y - end.y, end.x, end.y, mirrored, mp, Shader.TileMode.CLAMP)
                }
            }
        }
    }

    /**
     * Paints the gradient over pixels already on [canvas] inside [area]. [offX]/[offY]: canvas
     * pixel (x, y) is at (x - offX, y - offY) in [canvas] — used to line up the selection.
     */
    fun paintPixels(canvas: Canvas, area: RectF, spec: GradientSpec, start: PointF, end: PointF, selection: Selection?, offX: Float, offY: Float, lockAlpha: Boolean) {
        canvas.saveLayer(area, Paint().apply {
            alpha = (spec.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            xfermode = PorterDuffXfermode(if (lockAlpha) PorterDuff.Mode.SRC_ATOP else PorterDuff.Mode.SRC_OVER)
        })
        canvas.drawRect(area, Paint().apply { shader = shader(spec, start, end) })
        selection?.keepInside(canvas, offX, offY, area)
        canvas.restore()
    }

    /** Same for a mask (which stores how much is hidden): lighter gradient colors show more. */
    fun paintMask(canvas: Canvas, area: RectF, spec: GradientSpec, start: PointF, end: PointF, selection: Selection?, offX: Float, offY: Float) {
        val op = spec.opacity.coerceIn(0f, 1f)
        // 1) take away what's there, by how solid the gradient is
        canvas.saveLayer(area, Paint().apply {
            alpha = (op * 255).roundToInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        })
        canvas.drawRect(area, Paint().apply { shader = shader(spec, start, end) { c -> Color.argb(Color.alpha(c), 0, 0, 0) } })
        selection?.keepInside(canvas, offX, offY, area)
        canvas.restore()
        // 2) add the new hidden amount: dark colors hide
        canvas.saveLayer(area, Paint().apply {
            alpha = (op * 255).roundToInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        })
        canvas.drawRect(area, Paint().apply {
            shader = shader(spec, start, end) { c ->
                Color.argb(((1f - grayLevel(c)) * Color.alpha(c)).roundToInt(), 0, 0, 0)
            }
        })
        selection?.keepInside(canvas, offX, offY, area)
        canvas.restore()
    }
}

/** A gradient being dragged out, shown before it's applied. */
data class GradientDrag(val start: PointF, val end: PointF, val spec: GradientSpec, val toMask: Boolean, val selection: Selection?)

// ===================================================================== paint bucket

object FloodFill {
    /**
     * Which pixels the bucket fills, starting at ([sx], [sy]) in [px] (unpremultiplied ARGB, [w]×[h]).
     * [tolerance] 0..255 per channel. Returns 255 for filled pixels, 0 otherwise.
     */
    fun coverage(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Int, contiguous: Boolean): ByteArray {
        val out = ByteArray(w * h)
        if (sx !in 0 until w || sy !in 0 until h) return out
        val target = px[sy * w + sx]
        val ta = target ushr 24
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        fun match(c: Int): Boolean {
            val a = c ushr 24
            // Fully see-through pixels match each other whatever their (meaningless) color
            if (a == 0 && ta == 0) return true
            if (abs(a - ta) > tolerance) return false
            if (abs(((c shr 16) and 0xFF) - tr) > tolerance) return false
            if (abs(((c shr 8) and 0xFF) - tg) > tolerance) return false
            return abs((c and 0xFF) - tb) <= tolerance
        }
        if (!contiguous) {
            for (i in px.indices) if (match(px[i])) out[i] = -1
            return out
        }
        // Scanline fill with an explicit stack
        var stack = IntArray(1024)
        var sp = 0
        fun push(x: Int, y: Int) {
            if (sp + 2 > stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = x
            stack[sp++] = y
        }
        push(sx, sy)
        while (sp > 0) {
            val y = stack[--sp]
            var x = stack[--sp]
            val row = y * w
            if (out[row + x] != 0.toByte() || !match(px[row + x])) continue
            while (x > 0 && out[row + x - 1] == 0.toByte() && match(px[row + x - 1])) x--
            var up = false
            var down = false
            while (x < w && out[row + x] == 0.toByte() && match(px[row + x])) {
                out[row + x] = -1
                if (y > 0) {
                    val m = out[row - w + x] == 0.toByte() && match(px[row - w + x])
                    if (m && !up) push(x, y - 1)
                    up = m
                }
                if (y < h - 1) {
                    val m = out[row + w + x] == 0.toByte() && match(px[row + w + x])
                    if (m && !down) push(x, y + 1)
                    down = m
                }
                x++
            }
        }
        return out
    }
}
