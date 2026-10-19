package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
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
import kotlin.math.sqrt

// ===================================================================== settings

enum class StrokePosition(val label: String) { OUTSIDE("Outside"), INSIDE("Inside"), CENTER("Center") }

/** Outline around the layer's pixels. [size] in canvas pixels. */
data class StrokeFx(
    val on: Boolean = false,
    val size: Float = 6f,
    val position: StrokePosition = StrokePosition.OUTSIDE,
    val color: Int = Color.BLACK,
    val opacity: Float = 1f,
    /** Filled with [gradient] (across the layer, like Gradient Fill) instead of [color]. */
    val useGradient: Boolean = false,
    val gradient: GradientFx = GradientFx(on = true),
    val blend: Blend = Blend.NORMAL,
)

/**
 * Pattern Overlay: the layer's pixels filled with a pattern ([key] from [Patterns]; ready-made
 * ones in [colorA] on [colorB]), [scale] its size. It moves with the layer.
 */
data class PatternFx(
    val on: Boolean = false,
    val key: String = "b:checker",
    val colorA: Int = Color.BLACK,
    val colorB: Int = Color.WHITE,
    val scale: Float = 1f,
    val opacity: Float = 1f,
    val blend: Blend = Blend.NORMAL,
)

/** Bevel & Emboss gloss contour: how the light and shade are spread across the bevel. */
enum class GlossContour(val label: String) {
    LINEAR("Linear"), CONE("Cone"), CONE_INVERTED("Cone inverted"), GAUSSIAN("Gaussian"),
    HALF_ROUND("Half round"), RING("Ring"), STEPS("Rounded steps"),
}

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
    val blend: Blend = Blend.NORMAL,
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
    val blend: Blend = Blend.NORMAL,
)

/**
 * Inner Glow: light along the inside of the layer's edge, or from its middle outward ([center]).
 * [size] in canvas pixels; [choke] 0..1 makes it stronger and harder-edged.
 */
data class InnerGlowFx(
    val on: Boolean = false,
    val color: Int = 0xFFFFFFBE.toInt(),
    val opacity: Float = 0.75f,
    val size: Float = 10f,
    val choke: Float = 0f,
    val center: Boolean = false,
    val blend: Blend = Blend.SCREEN,
)

/**
 * Satin: soft shading inside the layer, from two copies of its shape moved apart along [angle] by
 * [distance] and blurred by [size] (like Photoshop's). [invert] swaps light and dark.
 */
data class SatinFx(
    val on: Boolean = false,
    val color: Int = Color.BLACK,
    val opacity: Float = 0.5f,
    val angle: Float = 19f,
    val distance: Float = 11f,
    val size: Float = 14f,
    val invert: Boolean = true,
    val blend: Blend = Blend.MULTIPLY,
) {
    val dx: Float get() = cos(angle * PI / 180.0).toFloat() * distance
    val dy: Float get() = -sin(angle * PI / 180.0).toFloat() * distance
}

data class OverlayFx(
    val on: Boolean = false,
    val color: Int = 0xFFE53935.toInt(),
    val opacity: Float = 1f,
    val blend: Blend = Blend.NORMAL,
)

enum class BevelStyle(val label: String) {
    INNER("Inner Bevel"), OUTER("Outer Bevel"), EMBOSS("Emboss"), PILLOW("Pillow Emboss");

    /** Reaches past the layer's edge. */
    val outside: Boolean get() = this != INNER
}

/**
 * Bevel & Emboss: lights the layer as if it were raised (or pressed in, when not [up]). [size]
 * and [soften] in canvas pixels; [depth] 0.1 … 5 (1 = 100%); [angle] where the light comes from
 * (degrees, 90 = above) and [altitude] how high it is (0 … 90).
 */
data class BevelFx(
    val on: Boolean = false,
    val style: BevelStyle = BevelStyle.INNER,
    val depth: Float = 1f,
    val up: Boolean = true,
    val size: Float = 10f,
    val soften: Float = 0f,
    val angle: Float = 120f,
    val altitude: Float = 30f,
    val highlight: Int = Color.WHITE,
    val highlightOpacity: Float = 0.75f,
    val shadow: Int = Color.BLACK,
    val shadowOpacity: Float = 0.75f,
    val gloss: GlossContour = GlossContour.LINEAR,
    /** How the light and shade are blended (Photoshop's Highlight Mode and Shadow Mode). */
    val highlightBlend: Blend = Blend.SCREEN,
    val shadowBlend: Blend = Blend.MULTIPLY,
)

/**
 * Gradient Fill (Photoshop's Gradient Overlay): the layer's pixels colored with a gradient across
 * them. [angle] which way it runs (degrees, 90 = bottom to top), [scale] how much of the layer it
 * spans (1 = all of it).
 */
data class GradientFx(
    val on: Boolean = false,
    val colors: List<Int> = listOf(Color.BLACK, Color.WHITE),
    val type: GradientType = GradientType.LINEAR,
    val angle: Float = 90f,
    val scale: Float = 1f,
    val reverse: Boolean = false,
    val opacity: Float = 1f,
    /** Where each color sits, 0..1 (null: evenly spaced). */
    val positions: List<Float>? = null,
    /** As Gradient Fill: how it blends with the layer (a stroke's gradient uses the stroke's). */
    val blend: Blend = Blend.NORMAL,
)

/**
 * Long Shadow: the layer's shape dragged out in one direction (flat design style). [angle] is
 * where the light comes from (like Drop Shadow), [length] in canvas pixels; [fade] fades it out
 * towards its far end.
 */
data class LongShadowFx(
    val on: Boolean = false,
    val color: Int = Color.BLACK,
    val opacity: Float = 0.35f,
    val angle: Float = 135f,
    val length: Float = 200f,
    val fade: Boolean = true,
    val blend: Blend = Blend.NORMAL,
) {
    val ux: Float get() = -cos(angle * PI / 180.0).toFloat()
    val uy: Float get() = sin(angle * PI / 180.0).toFloat()
}

/** A layer's effects. They don't change its pixels and can be changed or switched off any time. */
data class LayerStyle(
    val stroke: StrokeFx = StrokeFx(),
    val dropShadow: ShadowFx = ShadowFx(),
    val innerShadow: ShadowFx = ShadowFx(color = Color.BLACK, opacity = 0.5f, distance = 6f, size = 10f),
    val outerGlow: GlowFx = GlowFx(),
    val colorOverlay: OverlayFx = OverlayFx(),
    val bevel: BevelFx = BevelFx(),
    val gradientFill: GradientFx = GradientFx(),
    val longShadow: LongShadowFx = LongShadowFx(),
    val patternOverlay: PatternFx = PatternFx(),
    val innerGlow: InnerGlowFx = InnerGlowFx(),
    val satin: SatinFx = SatinFx(),
) {
    /**
     * An effect behind the layer (drop shadow, outer glow, long shadow) has a blend mode of its own:
     * it's drawn straight onto what's below the layer.
     */
    val anyBlend: Boolean
        get() = (dropShadow.on && dropShadow.blend != Blend.NORMAL) || (outerGlow.on && outerGlow.blend != Blend.NORMAL) ||
            (longShadow.on && longShadow.blend != Blend.NORMAL)

    /** The same look on a picture resized by [k]: widths, distances, sizes and the pattern's size scale with it. */
    fun scaled(k: Float): LayerStyle = copy(
        stroke = stroke.copy(size = stroke.size * k),
        dropShadow = dropShadow.copy(distance = dropShadow.distance * k, size = dropShadow.size * k),
        innerShadow = innerShadow.copy(distance = innerShadow.distance * k, size = innerShadow.size * k),
        outerGlow = outerGlow.copy(size = outerGlow.size * k),
        bevel = bevel.copy(size = bevel.size * k, soften = bevel.soften * k),
        longShadow = longShadow.copy(length = longShadow.length * k),
        patternOverlay = patternOverlay.copy(scale = patternOverlay.scale * k),
        innerGlow = innerGlow.copy(size = innerGlow.size * k),
        satin = satin.copy(distance = satin.distance * k, size = satin.size * k),
    )

    val any: Boolean get() = stroke.on || dropShadow.on || innerShadow.on || outerGlow.on || colorOverlay.on || bevel.on || gradientFill.on || longShadow.on || patternOverlay.on ||
        innerGlow.on || satin.on

    /**
     * Effects that need to be prepared in the background (everything but the color and pattern
     * overlays; the gradient fill needs to know where the layer's pixels are).
     */
    val needsBuild: Boolean get() = stroke.on || dropShadow.on || innerShadow.on || outerGlow.on || bevel.on || gradientFill.on || longShadow.on ||
        innerGlow.on || satin.on

    /** How far the effects reach past the pixels (canvas px), in every direction (see [effectRegion] for the exact area). */
    val reach: Float
        get() = max(reachAround, if (longShadow.on) longShadow.length + 2f else 0f)

    /** How far the effects other than the long shadow reach, all the way round. */
    private val reachAround: Float
        get() = max(
            max(if (stroke.on && stroke.position != StrokePosition.INSIDE) stroke.size else 0f, if (outerGlow.on) outerGlow.size * 2f else 0f),
            max(
                max(
                    max(if (dropShadow.on) dropShadow.distance + dropShadow.size * 1.5f else 0f, if (innerShadow.on) innerShadow.size * 1.5f else 0f),
                    max(if (innerGlow.on) innerGlow.size * 1.5f else 0f, if (satin.on) satin.distance + satin.size * 1.5f else 0f),
                ),
                if (bevel.on) (if (bevel.style.outside) bevel.size * 1.6f else bevel.size * 0.8f) + bevel.soften * 2f else 0f,
            ),
        )

    /**
     * The canvas area the effects of pixels within [content] can reach: [reach] all round, except
     * the long shadow, which only goes one way.
     */
    fun effectRegion(content: Rect): Rect {
        val pad = ceil(reachAround + 4f).toInt()
        val r = Rect(content.left - pad, content.top - pad, content.right + pad, content.bottom + pad)
        if (longShadow.on) {
            val ls = longShadow
            val ex = ceil(ls.length * kotlin.math.abs(ls.ux) + 2f).toInt()
            val ey = ceil(ls.length * kotlin.math.abs(ls.uy) + 2f).toInt()
            if (ls.ux > 0f) r.right = max(r.right, content.right + ex) else r.left = min(r.left, content.left - ex)
            if (ls.uy > 0f) r.bottom = max(r.bottom, content.bottom + ey) else r.top = min(r.top, content.top - ey)
        }
        return r
    }

    fun toJson(): JSONObject = JSONObject()
        .put("stroke", JSONObject().put("on", stroke.on).put("size", stroke.size.toDouble()).put("position", stroke.position.name).put("color", stroke.color).put("opacity", stroke.opacity.toDouble())
            .put("useGradient", stroke.useGradient).put("gradient", gradientJson(stroke.gradient)).put("blend", stroke.blend.name))
        .put("dropShadow", shadowJson(dropShadow))
        .put("innerShadow", shadowJson(innerShadow))
        .put("outerGlow", JSONObject().put("on", outerGlow.on).put("color", outerGlow.color).put("opacity", outerGlow.opacity.toDouble()).put("size", outerGlow.size.toDouble()).put("blend", outerGlow.blend.name))
        .put("colorOverlay", JSONObject().put("on", colorOverlay.on).put("color", colorOverlay.color).put("opacity", colorOverlay.opacity.toDouble()).put("blend", colorOverlay.blend.name))
        .put("bevel", JSONObject().put("on", bevel.on).put("style", bevel.style.name).put("depth", bevel.depth.toDouble()).put("up", bevel.up)
            .put("size", bevel.size.toDouble()).put("soften", bevel.soften.toDouble()).put("angle", bevel.angle.toDouble()).put("altitude", bevel.altitude.toDouble())
            .put("highlight", bevel.highlight).put("highlightOpacity", bevel.highlightOpacity.toDouble())
            .put("shadow", bevel.shadow).put("shadowOpacity", bevel.shadowOpacity.toDouble()).put("gloss", bevel.gloss.name)
            .put("highlightBlend", bevel.highlightBlend.name).put("shadowBlend", bevel.shadowBlend.name))
        .put("gradientFill", gradientJson(gradientFill))
        .put("patternOverlay", JSONObject().put("on", patternOverlay.on).put("key", patternOverlay.key)
            .put("colorA", patternOverlay.colorA).put("colorB", patternOverlay.colorB)
            .put("scale", patternOverlay.scale.toDouble()).put("opacity", patternOverlay.opacity.toDouble()).put("blend", patternOverlay.blend.name))
        .put("longShadow", JSONObject().put("on", longShadow.on).put("color", longShadow.color).put("opacity", longShadow.opacity.toDouble())
            .put("angle", longShadow.angle.toDouble()).put("length", longShadow.length.toDouble()).put("fade", longShadow.fade).put("blend", longShadow.blend.name))
        .put("innerGlow", JSONObject().put("on", innerGlow.on).put("color", innerGlow.color).put("opacity", innerGlow.opacity.toDouble()).put("size", innerGlow.size.toDouble())
            .put("choke", innerGlow.choke.toDouble()).put("center", innerGlow.center).put("blend", innerGlow.blend.name))
        .put("satin", JSONObject().put("on", satin.on).put("color", satin.color).put("opacity", satin.opacity.toDouble()).put("angle", satin.angle.toDouble())
            .put("distance", satin.distance.toDouble()).put("size", satin.size.toDouble()).put("invert", satin.invert).put("blend", satin.blend.name))

    companion object {
        internal fun gradientJson(g: GradientFx) = JSONObject().put("on", g.on)
            .put("colors", org.json.JSONArray().also { a -> g.colors.forEach { a.put(it.toLong() and 0xFFFFFFFFL) } })
            .put("type", g.type.name).put("angle", g.angle.toDouble()).put("scale", g.scale.toDouble())
            .put("reverse", g.reverse).put("opacity", g.opacity.toDouble()).put("blend", g.blend.name)
            .also { o -> g.positions?.let { ps -> o.put("positions", org.json.JSONArray().also { a -> ps.forEach { a.put(it.toDouble()) } }) } }

        internal fun gradientFrom(gf: JSONObject?, dg: GradientFx): GradientFx = if (gf == null) dg else GradientFx(
            gf.optBoolean("on"),
            gf.optJSONArray("colors")?.let { a -> List(a.length()) { a.optLong(it).toInt() } }?.takeIf { it.size >= 2 } ?: dg.colors,
            GradientType.entries.firstOrNull { it.name == gf.optString("type") } ?: GradientType.LINEAR,
            gf.f("angle", dg.angle), gf.f("scale", dg.scale), gf.optBoolean("reverse"), gf.f("opacity", dg.opacity),
            gf.optJSONArray("positions")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } },
            blendOf(gf, dg.blend),
        ).let { g -> if (g.positions != null && g.positions.size != g.colors.size) g.copy(positions = null) else g }

        private fun shadowJson(s: ShadowFx) = JSONObject().put("on", s.on).put("color", s.color).put("opacity", s.opacity.toDouble())
            .put("angle", s.angle.toDouble()).put("distance", s.distance.toDouble()).put("size", s.size.toDouble()).put("blend", s.blend.name)

        /** A saved blend mode (or [def] for files from before they could be set). */
        private fun blendOf(o: JSONObject, def: Blend): Blend = if (o.has("blend")) Blend.byName(o.optString("blend")) else def

        private fun JSONObject.f(name: String, def: Float) = optDouble(name, def.toDouble()).toFloat()

        private fun shadowFrom(o: JSONObject?, def: ShadowFx): ShadowFx = if (o == null) def else ShadowFx(
            o.optBoolean("on"), o.optInt("color", def.color), o.f("opacity", def.opacity), o.f("angle", def.angle), o.f("distance", def.distance), o.f("size", def.size),
            blendOf(o, def.blend),
        )

        fun fromJson(o: JSONObject?): LayerStyle {
            if (o == null) return LayerStyle()
            val d = LayerStyle()
            val st = o.optJSONObject("stroke")
            val gl = o.optJSONObject("outerGlow")
            val ov = o.optJSONObject("colorOverlay")
            val bv = o.optJSONObject("bevel")
            val db = d.bevel
            val gf = o.optJSONObject("gradientFill")
            val dg = d.gradientFill
            val lsh = o.optJSONObject("longShadow")
            val dl = d.longShadow
            return LayerStyle(
                stroke = if (st == null) d.stroke else StrokeFx(
                    st.optBoolean("on"), st.f("size", d.stroke.size),
                    StrokePosition.entries.firstOrNull { it.name == st.optString("position") } ?: StrokePosition.OUTSIDE,
                    st.optInt("color", d.stroke.color), st.f("opacity", d.stroke.opacity),
                    st.optBoolean("useGradient"), gradientFrom(st.optJSONObject("gradient"), d.stroke.gradient),
                    blendOf(st, d.stroke.blend),
                ),
                dropShadow = shadowFrom(o.optJSONObject("dropShadow"), d.dropShadow),
                innerShadow = shadowFrom(o.optJSONObject("innerShadow"), d.innerShadow),
                outerGlow = if (gl == null) d.outerGlow else GlowFx(gl.optBoolean("on"), gl.optInt("color", d.outerGlow.color), gl.f("opacity", d.outerGlow.opacity), gl.f("size", d.outerGlow.size), blendOf(gl, d.outerGlow.blend)),
                colorOverlay = if (ov == null) d.colorOverlay else OverlayFx(ov.optBoolean("on"), ov.optInt("color", d.colorOverlay.color), ov.f("opacity", d.colorOverlay.opacity), blendOf(ov, d.colorOverlay.blend)),
                bevel = if (bv == null) db else BevelFx(
                    bv.optBoolean("on"),
                    BevelStyle.entries.firstOrNull { it.name == bv.optString("style") } ?: BevelStyle.INNER,
                    bv.f("depth", db.depth), bv.optBoolean("up", true), bv.f("size", db.size), bv.f("soften", db.soften),
                    bv.f("angle", db.angle), bv.f("altitude", db.altitude),
                    bv.optInt("highlight", db.highlight), bv.f("highlightOpacity", db.highlightOpacity),
                    bv.optInt("shadow", db.shadow), bv.f("shadowOpacity", db.shadowOpacity),
                    GlossContour.entries.firstOrNull { it.name == bv.optString("gloss") } ?: GlossContour.LINEAR,
                    if (bv.has("highlightBlend")) Blend.byName(bv.optString("highlightBlend")) else db.highlightBlend,
                    if (bv.has("shadowBlend")) Blend.byName(bv.optString("shadowBlend")) else db.shadowBlend,
                ),
                gradientFill = gradientFrom(gf, dg),
                patternOverlay = o.optJSONObject("patternOverlay")?.let { po ->
                    val dp = d.patternOverlay
                    PatternFx(
                        po.optBoolean("on"), po.optString("key", dp.key), po.optInt("colorA", dp.colorA), po.optInt("colorB", dp.colorB),
                        po.f("scale", dp.scale), po.f("opacity", dp.opacity), blendOf(po, dp.blend),
                    )
                } ?: d.patternOverlay,
                longShadow = if (lsh == null) dl else LongShadowFx(
                    lsh.optBoolean("on"), lsh.optInt("color", dl.color), lsh.f("opacity", dl.opacity),
                    lsh.f("angle", dl.angle), lsh.f("length", dl.length), lsh.optBoolean("fade", true), blendOf(lsh, dl.blend),
                ),
                innerGlow = o.optJSONObject("innerGlow")?.let { ig ->
                    val di = d.innerGlow
                    InnerGlowFx(
                        ig.optBoolean("on"), ig.optInt("color", di.color), ig.f("opacity", di.opacity), ig.f("size", di.size),
                        ig.f("choke", di.choke), ig.optBoolean("center"), blendOf(ig, di.blend),
                    )
                } ?: d.innerGlow,
                satin = o.optJSONObject("satin")?.let { sa ->
                    val ds = d.satin
                    SatinFx(
                        sa.optBoolean("on"), sa.optInt("color", ds.color), sa.f("opacity", ds.opacity), sa.f("angle", ds.angle),
                        sa.f("distance", ds.distance), sa.f("size", ds.size), sa.optBoolean("invert", true), blendOf(sa, ds.blend),
                    )
                } ?: d.satin,
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
    /** Bevel & Emboss: where it's lit, and where it's shaded. */
    val bevelLight: Bitmap? = null,
    val bevelShade: Bitmap? = null,
    val longShadow: Bitmap? = null,
    /** Where the layer's visible pixels are (layer-local), for the gradient fill. */
    val content: RectF? = null,
    val innerGlow: Bitmap? = null,
    val satin: Bitmap? = null,
)

object Effects {

    /** What the effects of [layer] depend on; when it changes they have to be rebuilt. */
    fun keyOf(layer: Layer): Any = listOf(
        System.identityHashCode(layer.tiles), System.identityHashCode(layer.mask), layer.pixelVersion,
        // The overlays (and a stroke's colors) are drawn as they are (only whether the gradient is on needs a build)
        layer.props.style.let { st ->
            st.copy(
                colorOverlay = OverlayFx(), gradientFill = GradientFx(on = st.gradientFill.on), patternOverlay = PatternFx(),
                stroke = st.stroke.copy(color = Color.BLACK, opacity = 1f, gradient = GradientFx(on = true), blend = Blend.NORMAL),
                // Blend modes only change how the images are drawn
                dropShadow = st.dropShadow.copy(blend = Blend.NORMAL), innerShadow = st.innerShadow.copy(blend = Blend.NORMAL),
                outerGlow = st.outerGlow.copy(blend = Blend.NORMAL), longShadow = st.longShadow.copy(blend = Blend.NORMAL),
                innerGlow = st.innerGlow.copy(color = Color.BLACK, opacity = 1f, blend = Blend.NORMAL),
                satin = st.satin.copy(color = Color.BLACK, opacity = 1f, blend = Blend.NORMAL),
                bevel = st.bevel.copy(highlightBlend = Blend.SCREEN, shadowBlend = Blend.MULTIPLY),
            )
        }, layer.props.maskDisabled,
    )

    /**
     * Builds the effect images. [tiles] and [mask] should be copies of the layer's tile maps taken on
     * the main thread (this runs in the background). Images are at most [maxSide] pixels across.
     */
    fun build(key: Any, style: LayerStyle, tiles: Map<Long, Bitmap>, mask: Map<Long, Bitmap>?, maxSide: Int, tilesId: Int = 0): BuiltEffects? {
        if (!style.needsBuild || tiles.isEmpty()) return null
        val grid = TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }
        val content = grid.tileBounds() ?: return null
        val exact = if (style.gradientFill.on || (style.stroke.on && style.stroke.useGradient)) Transformer.contentBounds(tiles)?.let { RectF(it) } else null
        // Only the gradient fill: it just needs to know where the pixels are
        if (!(style.stroke.on || style.dropShadow.on || style.innerShadow.on || style.outerGlow.on || style.bevel.on || style.longShadow.on || style.innerGlow.on || style.satin.on)) {
            return BuiltEffects(key, tilesId, RectF(content), 1f, null, null, null, null, content = exact ?: RectF(content))
        }
        val region = style.effectRegion(content)
        var s = min(1f, maxSide.toFloat() / max(region.width(), region.height()))
        // The long shadow keeps three images of this size alive at once: keep each under ~24 million pixels
        if (style.longShadow.on) s = min(s, sqrt(24_000_000.0 / (region.width().toDouble() * region.height())).toFloat())
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
        var bevelLight: Bitmap? = null
        var bevelShade: Bitmap? = null
        var long: Bitmap? = null
        var innerGlow: Bitmap? = null
        var satin: Bitmap? = null
        try {
            if (style.longShadow.on && style.longShadow.length > 0f) {
                long = longShadow(shape, style.longShadow, s, w, h, region, exact ?: Transformer.contentBounds(tiles)?.let { RectF(it) })
            }
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
            if (style.innerGlow.on) {
                val ig = style.innerGlow
                val r = ig.size / 2f * s
                val effect = if (r >= 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null
                // From the edge: what's outside, blurred in. From the center: the shape itself, blurred (strongest in the middle).
                val b = Gpu.renderOffscreen(w, h, effect) { c ->
                    if (ig.center) {
                        c.drawBitmap(shape, 0f, 0f, Paint().apply { isFilterBitmap = true })
                    } else {
                        c.drawColor(Color.WHITE)
                        c.drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                    }
                }
                // Choke: stronger, with a harder edge. From the center the blurred shape is about
                // half strength at the edge: stretched so the edge is clear and the middle full.
                val k = ig.choke.coerceIn(0f, 1f)
                val boost = if (ig.center) 2f * (1f + k * 2f) else 1.3f + k * 4f
                val lift = if (ig.center) -255f * (boost / 2f) else 0f
                val strong = Gpu.renderOffscreen(w, h) { c ->
                    c.drawBitmap(b, 0f, 0f, Paint().apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                            0f, 0f, 0f, 0f, 255f,
                            0f, 0f, 0f, 0f, 255f,
                            0f, 0f, 0f, 0f, 255f,
                            0f, 0f, 0f, boost, lift,
                        )))
                    })
                    c.drawBitmap(shape, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                }
                b.recycle()
                innerGlow = toAlpha(strong)
            }
            if (style.satin.on) {
                val sa = style.satin
                val a = blurred(shape, sa.dx, sa.dy, sa.size / 2f)
                val b = blurred(shape, -sa.dx, -sa.dy, sa.size / 2f)
                val sh = RuntimeShader(SATIN_AGSL)
                sh.setInputShader("a", BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                sh.setInputShader("b", BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                sh.setInputShader("shape", BitmapShader(shape, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                sh.setFloatUniform("inv", if (sa.invert) 1f else 0f)
                satin = toAlpha(Gpu.renderOffscreen(w, h) { c -> c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sh }) })
                a.recycle()
                b.recycle()
            }
            if (style.bevel.on && style.bevel.size > 0f) {
                // If the GPU can't make it, the layer keeps its other effects
                runCatching { bevel(shape, style.bevel, s, w, h) }.getOrNull()?.let { (lit, shade) ->
                    bevelLight = lit
                    bevelShade = shade
                }
            }
        } finally {
            shape.recycle()
            inverse?.recycle()
        }
        return BuiltEffects(key, tilesId, RectF(region), s, drop, glow, stroke, inner, bevelLight, bevelShade, long, exact ?: RectF(content), innerGlow, satin)
    }

    /**
     * Long Shadow: the [shape] (white, effect scale [s], covering [region]) stamped again and again
     * along the shadow's direction, doubling how far it reaches each pass.
     */
    private fun longShadow(shape: Bitmap, ls: LongShadowFx, s: Float, w: Int, h: Int, region: Rect, content: RectF?): Bitmap {
        val total = ls.length * s
        var cur = shape.copy(Bitmap.Config.ARGB_8888, true)
        var done = 0f
        val p = Paint().apply { isFilterBitmap = true }
        while (done < total - 0.01f) {
            val step = min(total - done, max(1f, done))
            val next = cur.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(next).drawBitmap(cur, ls.ux * step, ls.uy * step, p)
            cur.recycle()
            cur = next
            done += step
        }
        if (ls.fade) {
            // Strong at the shape, gone at the far end
            val c = content ?: RectF(region)
            val cx = (c.centerX() - region.left) * s
            val cy = (c.centerY() - region.top) * s
            val half = (kotlin.math.abs(c.width() * ls.ux) + kotlin.math.abs(c.height() * ls.uy)) / 2f * s
            val g = android.graphics.LinearGradient(
                cx, cy, cx + ls.ux * (half + total), cy + ls.uy * (half + total),
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT), floatArrayOf(0f, (half / (half + total)).coerceIn(0f, 1f), 1f),
                Shader.TileMode.CLAMP,
            )
            Canvas(cur).drawPaint(Paint().apply {
                shader = g
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        }
        return toAlpha(cur)
    }

    /**
     * Paints the gradient fill over what's in the current layer of [canvas] (call inside a
     * saveLayer holding the layer's pixels). [area] is where the layer's pixels are (canvas pixels).
     */
    fun drawGradientFill(canvas: Canvas, g: GradientFx, area: RectF) {
        canvas.drawPaint(Paint().apply {
            shader = gradientShader(g, area)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        })
    }

    /**
     * Bevel & Emboss from the layer's [shape] (white, at effect scale [s]): its edge, softened, is
     * used as a height map; each spot is lit by how its slope faces the light.
     */
    private fun bevel(shape: Bitmap, b: BevelFx, s: Float, w: Int, h: Int): Pair<Bitmap, Bitmap> {
        val sizePx = (b.size * s).coerceAtLeast(0.5f)
        // Height: the shape blurred, so it slopes over about [size] at the edge
        val height = Gpu.renderOffscreen(w, h, RenderEffect.createBlurEffect(sizePx * 0.6f, sizePx * 0.6f, Shader.TileMode.DECAL)) {
            it.drawBitmap(shape, 0f, 0f, Paint().apply { isFilterBitmap = true })
        }
        val a = Math.toRadians(b.angle.toDouble())
        val alt = Math.toRadians(b.altitude.coerceIn(0f, 90f).toDouble())
        val lx = (cos(a) * cos(alt)).toFloat()
        val ly = (-sin(a) * cos(alt)).toFloat()
        val lz = sin(alt).toFloat()
        // Steeper with more depth; a ramp over the bevel's size is about 45° at 100%
        val k = b.depth.coerceIn(0.05f, 10f) * sizePx * 1.2f * (if (b.up) 1f else -1f)
        val step = max(1f, sizePx * 0.12f)
        val soft = b.soften * s
        fun pass(mode: Float): Bitmap {
            val sh = RuntimeShader(BEVEL_AGSL)
            sh.setInputShader("height", BitmapShader(height, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            sh.setInputShader("shape", BitmapShader(shape, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            sh.setFloatUniform("light", lx, ly, lz)
            sh.setFloatUniform("k", k)
            sh.setFloatUniform("stepPx", step)
            sh.setFloatUniform("style", b.style.ordinal.toFloat())
            sh.setFloatUniform("mode", mode)
            sh.setFloatUniform("gloss", b.gloss.ordinal.toFloat())
            val effect = if (soft >= 0.5f) RenderEffect.createBlurEffect(soft, soft, Shader.TileMode.DECAL) else null
            return toAlpha(Gpu.renderOffscreen(w, h, effect) { c ->
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sh })
            })
        }
        try {
            return pass(0f) to pass(1f)
        } finally {
            height.recycle()
        }
    }

    /** Satin: where the two moved, blurred copies differ (or agree, inverted), inside the shape. */
    private const val SATIN_AGSL = """
uniform shader a;
uniform shader b;
uniform shader shape;
uniform float inv;
half4 main(float2 p) {
    float d = abs(float(a.eval(p).a) - float(b.eval(p).a));
    if (inv > 0.5) d = 1.0 - d;
    float v = d * float(shape.eval(p).a);
    return half4(half(v));
}
"""

    private const val BEVEL_AGSL = """
uniform shader height;
uniform shader shape;
uniform float3 light;
uniform float k;
uniform float stepPx;
uniform float style;
uniform float mode;
uniform float gloss;

float H(float2 p) { return float(height.eval(p).a); }

// Gloss contour: reshapes the light across the slope (t: 0 darkest .. 1 lightest)
float contour(float t) {
    if (gloss < 0.5) return t;                                   // linear
    if (gloss < 1.5) return 1.0 - abs(2.0 * t - 1.0);            // cone
    if (gloss < 2.5) return abs(2.0 * t - 1.0);                  // cone inverted
    if (gloss < 3.5) return t * t * (3.0 - 2.0 * t);             // gaussian
    if (gloss < 4.5) return sqrt(max(0.0, 1.0 - (1.0 - t) * (1.0 - t)));   // half round
    if (gloss < 5.5) return 0.5 - 0.5 * cos(t * 12.566371);      // ring
    float s = t * 4.0;                                           // rounded steps
    float f = fract(s);
    return (floor(s) + f * f * (3.0 - 2.0 * f)) / 4.0;
}

half4 main(float2 p) {
    float inside = float(shape.eval(p).a);
    float gx = (H(p + float2(stepPx, 0.0)) - H(p - float2(stepPx, 0.0))) / (2.0 * stepPx);
    float gy = (H(p + float2(0.0, stepPx)) - H(p - float2(0.0, stepPx))) / (2.0 * stepPx);
    // Pillow emboss: the outside slopes the other way
    float kk = (style > 2.5 && inside < 0.5) ? -k : k;
    float3 n = normalize(float3(-gx * kk, -gy * kk, 1.0));
    float shade = (dot(n, light) - light.z) / max(0.05, 1.0 - light.z * 0.5);
    float m;
    if (style < 0.5) m = inside;               // inner bevel
    else if (style < 1.5) m = 1.0 - inside;    // outer bevel
    else m = 1.0;                              // emboss, pillow
    float s = clamp(shade * 1.6, -1.0, 1.0);
    // The contour only reshapes the slopes (flat parts keep no light or shade)
    float slope = clamp(length(float2(gx, gy)) * abs(kk) * 4.0, 0.0, 1.0);
    s = mix(s, contour(s * 0.5 + 0.5) * 2.0 - 1.0, slope);
    float v = mode < 0.5 ? max(s, 0.0) : max(-s, 0.0);
    v = v * m;
    return half4(half(v), half(v), half(v), half(v));
}
"""

    private fun toAlpha(src: Bitmap): Bitmap {
        val a = src.copy(Bitmap.Config.ALPHA_8, true)
        if (a !== src) src.recycle()
        return a
    }

    /** The gradient fill's shader over [area] (canvas pixels). */
    fun gradientShader(g: GradientFx, area: RectF): Shader {
        val a = Math.toRadians(g.angle.toDouble())
        val ux = cos(a).toFloat()
        val uy = -sin(a).toFloat()
        val extent = (kotlin.math.abs(area.width() * ux) + kotlin.math.abs(area.height() * uy)) * g.scale.coerceIn(0.05f, 5f)
        val cx = area.centerX()
        val cy = area.centerY()
        val spec = GradientSpec(g.colors, g.type, 1f, g.reverse, g.positions)
        val (start, end) = when (g.type) {
            GradientType.LINEAR -> android.graphics.PointF(cx - ux * extent / 2f, cy - uy * extent / 2f) to android.graphics.PointF(cx + ux * extent / 2f, cy + uy * extent / 2f)
            else -> android.graphics.PointF(cx, cy) to android.graphics.PointF(cx + ux * extent / 2f, cy + uy * extent / 2f)
        }
        return GradientPainter.shader(spec, start, end)
    }

    /** The stroke, in its color or its gradient (across [area], where the layer's pixels are). */
    fun drawStroke(canvas: Canvas, fx: BuiltEffects, s: StrokeFx, dx: Float, dy: Float, area: RectF?) {
        val img = fx.stroke ?: return
        if (!s.useGradient || area == null) {
            drawImage(canvas, fx, img, dx, dy, s.color, s.opacity, s.blend.mode)
            return
        }
        val a = fx.area
        val dst = RectF(dx + a.left, dy + a.top, dx + a.right, dy + a.bottom)
        canvas.saveLayer(dst, Paint().apply {
            alpha = (s.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            blendMode = s.blend.mode
        })
        canvas.drawBitmap(img, null, dst, Paint().apply { isFilterBitmap = true; color = Color.WHITE })
        canvas.drawRect(dst, Paint().apply {
            shader = gradientShader(s.gradient, area)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        })
        canvas.restore()
    }

    /**
     * Paints the pattern overlay over what's in the current layer of [canvas] (call inside a
     * saveLayer holding the layer's pixels). ([dx], [dy]) is where the layer is, so the pattern
     * moves with it.
     */
    fun drawPatternOverlay(canvas: Canvas, p: PatternFx, dx: Float, dy: Float) {
        val bmp = Patterns.bitmapFor(p.key, p.colorA, p.colorB) ?: return
        canvas.drawPaint(Patterns.paint(bmp, p.scale.coerceIn(0.05f, 8f), dx, dy).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        })
    }

    /** Draws one prepared effect image at its place, in [color] and [opacity]. */
    fun drawImage(canvas: Canvas, fx: BuiltEffects, img: Bitmap?, dx: Float, dy: Float, color: Int, opacity: Float, blend: android.graphics.BlendMode? = null) {
        img ?: return
        val p = Paint().apply {
            isFilterBitmap = true
            this.color = color or 0xFF000000.toInt()
            alpha = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
            if (blend != null) blendMode = blend
        }
        val a = fx.area
        canvas.drawBitmap(img, null, RectF(dx + a.left, dy + a.top, dx + a.right, dy + a.bottom), p)
    }
}
