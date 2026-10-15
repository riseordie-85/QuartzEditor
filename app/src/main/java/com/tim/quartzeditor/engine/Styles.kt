package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ===================================================================== settings

enum class StrokePosition(val label: String) { OUTSIDE("Outside"), INSIDE("Inside"), CENTER("Center") }

/** Outline around the layer's pixels. [size] in canvas pixels. */
data class StrokeFx(
    val on: Boolean = false,
    val size: Float = 6f,
    val position: StrokePosition = StrokePosition.OUTSIDE,
    val color: Int = Color.BLACK,
    val opacity: Float = 1f,
)

/**
 * Drop shadow or inner shadow. [angle] is where the light comes from (degrees, 90 = above, like
 * Photoshop), [distance] and [size] (blur) in canvas pixels.
 */
data class ShadowFx(
    val on: Boolean = false,
    val color: Int = Color.BLACK,
    val opacity: Float = 0.6f,
    val angle: Float = 120f,
    val distance: Float = 12f,
    val size: Float = 16f,
) {
    /** Shadow offset: away from the light. */
    val dx: Float get() = -cos(angle * PI / 180.0).toFloat() * distance
    val dy: Float get() = sin(angle * PI / 180.0).toFloat() * distance
}

data class GlowFx(
    val on: Boolean = false,
    val color: Int = 0xFFFFE27A.toInt(),
    val opacity: Float = 0.8f,
    val size: Float = 24f,
)

data class OverlayFx(
    val on: Boolean = false,
    val color: Int = 0xFFE53935.toInt(),
    val opacity: Float = 1f,
)

/** A layer's effects. They don't change its pixels and can be changed or switched off any time. */
data class LayerStyle(
    val stroke: StrokeFx = StrokeFx(),
    val dropShadow: ShadowFx = ShadowFx(),
    val innerShadow: ShadowFx = ShadowFx(color = Color.BLACK, opacity = 0.5f, distance = 6f, size = 10f),
    val outerGlow: GlowFx = GlowFx(),
    val colorOverlay: OverlayFx = OverlayFx(),
) {
    val any: Boolean get() = stroke.on || dropShadow.on || innerShadow.on || outerGlow.on || colorOverlay.on

    /** Effects that need to be prepared as images (everything but the color overlay). */
    val needsBuild: Boolean get() = stroke.on || dropShadow.on || innerShadow.on || outerGlow.on

    /** How far the effects reach past the pixels (canvas px). */
    val reach: Float
        get() = max(
            max(if (stroke.on && stroke.position != StrokePosition.INSIDE) stroke.size else 0f, if (outerGlow.on) outerGlow.size * 2f else 0f),
            max(if (dropShadow.on) dropShadow.distance + dropShadow.size * 1.5f else 0f, if (innerShadow.on) innerShadow.size * 1.5f else 0f),
        )

    fun toJson(): JSONObject = JSONObject()
        .put("stroke", JSONObject().put("on", stroke.on).put("size", stroke.size.toDouble()).put("position", stroke.position.name).put("color", stroke.color).put("opacity", stroke.opacity.toDouble()))
        .put("dropShadow", shadowJson(dropShadow))
        .put("innerShadow", shadowJson(innerShadow))
        .put("outerGlow", JSONObject().put("on", outerGlow.on).put("color", outerGlow.color).put("opacity", outerGlow.opacity.toDouble()).put("size", outerGlow.size.toDouble()))
        .put("colorOverlay", JSONObject().put("on", colorOverlay.on).put("color", colorOverlay.color).put("opacity", colorOverlay.opacity.toDouble()))

    companion object {
        private fun shadowJson(s: ShadowFx) = JSONObject().put("on", s.on).put("color", s.color).put("opacity", s.opacity.toDouble())
            .put("angle", s.angle.toDouble()).put("distance", s.distance.toDouble()).put("size", s.size.toDouble())

        private fun JSONObject.f(name: String, def: Float) = optDouble(name, def.toDouble()).toFloat()

        private fun shadowFrom(o: JSONObject?, def: ShadowFx): ShadowFx = if (o == null) def else ShadowFx(
            o.optBoolean("on"), o.optInt("color", def.color), o.f("opacity", def.opacity), o.f("angle", def.angle), o.f("distance", def.distance), o.f("size", def.size),
        )

        fun fromJson(o: JSONObject?): LayerStyle {
            if (o == null) return LayerStyle()
            val d = LayerStyle()
            val st = o.optJSONObject("stroke")
            val gl = o.optJSONObject("outerGlow")
            val ov = o.optJSONObject("colorOverlay")
            return LayerStyle(
                stroke = if (st == null) d.stroke else StrokeFx(
                    st.optBoolean("on"), st.f("size", d.stroke.size),
                    StrokePosition.entries.firstOrNull { it.name == st.optString("position") } ?: StrokePosition.OUTSIDE,
                    st.optInt("color", d.stroke.color), st.f("opacity", d.stroke.opacity),
                ),
                dropShadow = shadowFrom(o.optJSONObject("dropShadow"), d.dropShadow),
                innerShadow = shadowFrom(o.optJSONObject("innerShadow"), d.innerShadow),
                outerGlow = if (gl == null) d.outerGlow else GlowFx(gl.optBoolean("on"), gl.optInt("color", d.outerGlow.color), gl.f("opacity", d.outerGlow.opacity), gl.f("size", d.outerGlow.size)),
                colorOverlay = if (ov == null) d.colorOverlay else OverlayFx(ov.optBoolean("on"), ov.optInt("color", d.colorOverlay.color), ov.f("opacity", d.colorOverlay.opacity)),
            )
        }
    }
}

// ===================================================================== prepared effect images

/**
 * A layer's effects as ready-to-draw 1-byte images (drawn with each effect's color). They all
 * cover the same area: [area] in the layer's own coordinates, at [scale] image pixels per canvas
 * pixel (smaller than 1 for big layers on screen; blur hides it).
 */
class BuiltEffects(
    val key: Any,
    /** Which set of pixels these were made from (identity of the layer's tile grid). */
    val tilesId: Int,
    val area: RectF,
    val scale: Float,
    val dropShadow: Bitmap?,
    val outerGlow: Bitmap?,
    val stroke: Bitmap?,
    val innerShadow: Bitmap?,
)

object Effects {

    /** What the effects of [layer] depend on; when it changes they have to be rebuilt. */
    fun keyOf(layer: Layer): Any = listOf(
        System.identityHashCode(layer.tiles), System.identityHashCode(layer.mask), layer.pixelVersion,
        layer.props.style.copy(colorOverlay = OverlayFx()), layer.props.maskDisabled,
    )

    /**
     * Builds the effect images. [tiles] and [mask] should be copies of the layer's tile maps taken on
     * the main thread (this runs in the background). Images are at most [maxSide] pixels across.
     */
    fun build(key: Any, style: LayerStyle, tiles: Map<Long, Bitmap>, mask: Map<Long, Bitmap>?, maxSide: Int, tilesId: Int = 0): BuiltEffects? {
        if (!style.needsBuild || tiles.isEmpty()) return null
        val grid = TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }
        val content = grid.tileBounds() ?: return null
        val pad = ceil(style.reach + 4f).toInt()
        val region = Rect(content.left - pad, content.top - pad, content.right + pad, content.bottom + pad)
        val s = min(1f, maxSide.toFloat() / max(region.width(), region.height()))
        val w = (region.width() * s).roundToInt().coerceAtLeast(1)
        val h = (region.height() * s).roundToInt().coerceAtLeast(1)

        // The layer's shape (pixels with the mask applied), white, at effect scale
        val shape = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(shape).apply {
            scale(s, s)
            translate(-region.left.toFloat(), -region.top.toFloat())
            saveLayer(null, null)
            grid.drawTo(this, 0f, 0f, Paint().apply { isFilterBitmap = true })
            if (mask != null) {
                val mg = TileGrid(alphaOnly = true).also { it.tiles.putAll(mask) }
                mg.drawTo(this, 0f, 0f, Paint().apply {
                    isFilterBitmap = true
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                })
            }
            restore()
        }

        val white = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)

        // Everything outside the shape (for inner effects)
        val inverse: Bitmap? = if (style.stroke.on && style.stroke.position != StrokePosition.OUTSIDE) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { inv ->
                val c = Canvas(inv)
                c.drawColor(Color.WHITE)
                c.drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
        } else {
            null
        }

        fun blurred(src: Bitmap, dx: Float, dy: Float, radius: Float, boost: Float = 1f): Bitmap {
            val r = radius * s
            var effect: RenderEffect? = if (r >= 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null
            if (boost != 1f) {
                val m = ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, boost, 0f,
                ))
                effect = if (effect != null) RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), effect) else RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m))
            }
            val out = Gpu.renderOffscreen(w, h, effect) { c ->
                c.drawBitmap(src, dx * s, dy * s, Paint().apply { colorFilter = white; isFilterBitmap = true })
            }
            return toAlpha(out)
        }

        /** One growth step: [src] stamped around a circle of [r] image pixels. */
        fun grow(src: Bitmap, r: Float): Bitmap = Gpu.renderOffscreen(w, h) { c ->
            val p = Paint().apply { colorFilter = white; isFilterBitmap = true }
            c.drawBitmap(src, 0f, 0f, p)
            if (r > 0f) {
                // 16 directions leave gaps narrower than the shape has already grown, so the result is solid
                val n = 16
                for (i in 0 until n) {
                    val a = 2 * PI * i / n
                    c.drawBitmap(src, (cos(a) * r).toFloat(), (sin(a) * r).toFloat(), p)
                }
            }
        }

        /**
         * [src]'s shape grown by [radius]. Grows in steps that at most double what's grown so far,
         * so even a hairline gets a solid, round outline.
         */
        fun dilated(src: Bitmap, radius: Float): Bitmap {
            val r = radius * s
            var cur = grow(src, 0f)
            var done = 0f
            while (done < r - 0.05f) {
                val step = min(r - done, max(1f, done))
                val next = grow(cur, step)
                cur.recycle()
                cur = next
                done += step
            }
            return cur
        }

        var drop: Bitmap? = null
        var glow: Bitmap? = null
        var stroke: Bitmap? = null
        var inner: Bitmap? = null
        try {
            if (style.dropShadow.on) {
                drop = blurred(shape, style.dropShadow.dx, style.dropShadow.dy, style.dropShadow.size / 2f)
            }
            if (style.outerGlow.on) {
                // Grow a little first so the glow is strong at the edge
                val grown = dilated(shape, style.outerGlow.size * 0.15f)
                glow = blurred(grown, 0f, 0f, style.outerGlow.size / 2f, boost = 1.6f)
                grown.recycle()
            }
            if (style.stroke.on && style.stroke.size > 0f) {
                val st = style.stroke
                val band: Bitmap = when (st.position) {
                    StrokePosition.OUTSIDE -> dilated(shape, st.size).also { d ->
                        Canvas(d).drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                    }
                    StrokePosition.INSIDE -> dilated(inverse!!, st.size).also { d ->
                        Canvas(d).drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                    }
                    StrokePosition.CENTER -> {
                        val outer = dilated(shape, st.size / 2f)
                        val innerD = dilated(inverse!!, st.size / 2f)
                        Canvas(outer).drawBitmap(innerD, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                        innerD.recycle()
                        outer
                    }
                }
                stroke = toAlpha(band)
            }
            if (style.innerShadow.on) {
                val sh = style.innerShadow
                // Everything except the shape moved by the shadow offset (so whatever slides in from the edge counts as outside)
                val r = sh.size / 2f * s
                val effect = if (r >= 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null
                val b = toAlpha(Gpu.renderOffscreen(w, h, effect) { c ->
                    c.drawColor(Color.WHITE)
                    c.drawBitmap(shape, sh.dx * s, sh.dy * s, Paint().apply {
                        isFilterBitmap = true
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    })
                })
                // Only inside the shape
                Canvas(b).drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                inner = b
            }
        } finally {
            shape.recycle()
            inverse?.recycle()
        }
        return BuiltEffects(key, tilesId, RectF(region), s, drop, glow, stroke, inner)
    }

    private fun toAlpha(src: Bitmap): Bitmap {
        val a = src.copy(Bitmap.Config.ALPHA_8, true)
        if (a !== src) src.recycle()
        return a
    }

    /** Draws one prepared effect image at its place, in [color] and [opacity]. */
    fun drawImage(canvas: Canvas, fx: BuiltEffects, img: Bitmap?, dx: Float, dy: Float, color: Int, opacity: Float, xfer: PorterDuff.Mode? = null) {
        img ?: return
        val p = Paint().apply {
            isFilterBitmap = true
            this.color = color or 0xFF000000.toInt()
            alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
            if (xfer != null) xfermode = PorterDuffXfermode(xfer)
        }
        val a = fx.area
        canvas.drawBitmap(img, null, RectF(dx + a.left, dy + a.top, dx + a.right, dy + a.bottom), p)
    }
}
