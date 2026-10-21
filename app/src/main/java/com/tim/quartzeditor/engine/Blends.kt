package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader

/**
 * The blend modes Android can't draw by itself ([Blend.custom] > 0): Linear Burn, Vivid Light,
 * Hard Mix, Divide and the rest. A layer with one of these is drawn on its own, then mixed with
 * everything below it by a GPU shader, using the same formulas as Photoshop.
 */
object BlendShader {

    internal const val COMMON = """
uniform float mode;
// Canvas pixel = position × docMap.xy + docMap.zw (Dissolve's pattern stays put on the picture)
uniform float4 docMap;

float burn(float b, float s) { if (b >= 1.0) return 1.0; if (s <= 0.0) return 0.0; return 1.0 - min(1.0, (1.0 - b) / s); }
float dodge(float b, float s) { if (b <= 0.0) return 0.0; if (s >= 1.0) return 1.0; return min(1.0, b / (1.0 - s)); }

float ch(float b, float s) {
    if (mode < 2.5) return max(0.0, b + s - 1.0);                                   // Linear Burn
    if (mode < 5.5) return s <= 0.5 ? burn(b, 2.0 * s) : dodge(b, 2.0 * s - 1.0);  // Vivid Light
    if (mode < 6.5) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);                     // Linear Light
    if (mode < 7.5) return s <= 0.5 ? min(b, 2.0 * s) : max(b, 2.0 * s - 1.0);    // Pin Light
    if (mode < 8.5) return b + s >= 1.0 ? 1.0 : 0.0;                               // Hard Mix
    if (mode < 9.5) return max(0.0, b - s);                                         // Subtract
    if (s <= 0.0) return b <= 0.0 ? 0.0 : 1.0;                                      // Divide
    return min(1.0, b / s);
}

float lum(float3 c) { return dot(c, float3(0.3, 0.59, 0.11)); }

float4 mixPx(float4 pb, float4 ps, float2 p) {
    float ba = pb.a;
    float sa = ps.a;
    if (sa <= 0.0) return pb;
    float3 cs = ps.rgb / sa;
    if (mode < 1.5) {
        // Dissolve: each canvas pixel is either fully the layer or not at all, by chance
        float2 d = floor(p * docMap.xy + docMap.zw);
        float r = fract(sin(dot(d, float2(12.9898, 78.233))) * 43758.5453);
        float a = r < sa ? 1.0 : 0.0;
        return float4(cs * a + pb.rgb * (1.0 - a), a + ba * (1.0 - a));
    }
    float3 cb = ba > 0.0 ? pb.rgb / ba : float3(0.0);
    float3 B = float3(ch(cb.r, cs.r), ch(cb.g, cs.g), ch(cb.b, cs.b));
    if (mode > 2.5 && mode < 3.5) B = lum(cs) < lum(cb) ? cs : cb;        // Darker Color
    if (mode > 3.5 && mode < 4.5) B = lum(cs) > lum(cb) ? cs : cb;        // Lighter Color
    // Where only one of the two has pixels it shows as it is; where both do, the mix
    float3 co = ps.rgb * (1.0 - ba) + pb.rgb * (1.0 - sa) + sa * ba * B;
    return float4(co, sa + ba - sa * ba);
}
"""

    /** Two pictures of the same size: what's below and the layer. */
    private const val TWO = COMMON + """
uniform shader below;
uniform shader layer;
half4 main(float2 p) { return half4(mixPx(float4(below.eval(p)), float4(layer.eval(p)), p)); }
"""

    /** One picture holding both: what's below on the left, the layer [shift] pixels to the right of it. */
    private const val PAIR = COMMON + """
uniform shader content;
uniform float shift;
half4 main(float2 p) {
    if (p.x >= shift) return half4(0.0);
    return half4(mixPx(float4(content.eval(p)), float4(content.eval(p + float2(shift, 0.0))), p));
}
"""

    /**
     * A render effect for a RenderNode whose left [width] pixels are what's below and whose next
     * [width] pixels are the layer: the left half becomes the two mixed (the right half is cleared).
     * Canvas pixel = node pixel × ([sx], [sy]) + ([tx], [ty]).
     */
    /** On screen: one shader per layer, kept (compiling one every frame would stutter). Main thread only. */
    private val screen = HashMap<Long, RuntimeShader>()

    /** [layerId]: whose shader to reuse (each layer keeps its own, since several can be on screen at once). */
    fun pairEffect(layerId: Long, blend: Blend, width: Int, sx: Float, sy: Float, tx: Float, ty: Float): RenderEffect {
        if (screen.size > 64) screen.clear()
        val sh = screen.getOrPut(layerId) { RuntimeShader(PAIR) }
        sh.setFloatUniform("mode", blend.custom.toFloat())
        sh.setFloatUniform("docMap", sx, sy, tx, ty)
        sh.setFloatUniform("shift", width.toFloat())
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    /**
     * [below] and [layer] (the same size) mixed by [blend], as a new bitmap. Canvas pixel = bitmap
     * pixel × ([sx], [sy]) + ([tx], [ty]).
     */
    fun mix(below: Bitmap, layer: Bitmap, blend: Blend, sx: Float, sy: Float, tx: Float, ty: Float): Bitmap {
        val sh = RuntimeShader(TWO)
        sh.setFloatUniform("mode", blend.custom.toFloat())
        sh.setFloatUniform("docMap", sx, sy, tx, ty)
        sh.setInputShader("below", BitmapShader(below, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        sh.setInputShader("layer", BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        return Gpu.renderOffscreen(below.width, below.height) { c ->
            c.drawRect(0f, 0f, below.width.toFloat(), below.height.toFloat(), Paint().apply { shader = sh })
        }
    }
}

/**
 * Blend If (Layer Style → Blending Options): the layer shows only where its own gray, and the gray
 * of what's below it, are in their ranges. The layer is drawn once on its own (for its gray) and once
 * blended onto what's below (Android's blend modes); the result is what's below, moved toward that
 * blend by how much shows. (A layer's blend is a straight mix in its opacity, so this is the same as
 * changing its opacity pixel by pixel.) Blend modes Android lacks are mixed here as [BlendShader] does.
 */
object BlendIfShader {
    private const val WEIGHT = """
uniform float4 thisR;
uniform float4 underR;
uniform float useComp;

float ramp(float v, float4 r) {
    float lo = v >= r.y ? 1.0 : (v < r.x ? 0.0 : (v - r.x) / max(r.y - r.x, 0.0001));
    float hi = v <= r.z ? 1.0 : (v > r.w ? 0.0 : (r.w - v) / max(r.w - r.z, 0.0001));
    return lo * hi;
}

// useComp: 0 a blend mode mixed here (the layer's alpha scaled by how much shows, which also keeps
// Dissolve speckled), 1 Android's blend in pc (moved toward by how much shows), 2 Add (clamped)
float4 blendIf(float4 pb, float4 ps, float4 pc, float2 p) {
    if (ps.a <= 0.0) return pb;
    float ws = ramp(lum(ps.rgb / ps.a) * 255.0, thisR);
    // Over nothing there's nothing to compare with: the layer shows
    float wu = pb.a > 0.0 ? mix(1.0, ramp(lum(pb.rgb / pb.a) * 255.0, underR), pb.a) : 1.0;
    float w = ws * wu;
    if (useComp < 0.5) return mixPx(pb, ps * w, p);
    if (useComp > 1.5) return min(pb + ps * w, float4(1.0));
    return pb + (pc - pb) * w;
}
"""

    private const val COMMON = BlendShader.COMMON + WEIGHT

    /**
     * One picture, a 2 × 2 grid of [size]-sized panels: what's below (top left), the layer alone (top
     * right) and the layer blended onto what's below (bottom left).
     */
    private const val TRIPLE = COMMON + """
uniform shader content;
uniform float2 size;
half4 main(float2 p) {
    if (p.x >= size.x || p.y >= size.y) return half4(0.0);
    return half4(blendIf(float4(content.eval(p)), float4(content.eval(p + float2(size.x, 0.0))), float4(content.eval(p + float2(0.0, size.y))), p));
}
"""

    private const val THREE = COMMON + """
uniform shader below;
uniform shader layer;
uniform shader comp;
half4 main(float2 p) { return half4(blendIf(float4(below.eval(p)), float4(layer.eval(p)), float4(comp.eval(p)), p)); }
"""

    private fun RuntimeShader.setUp(blend: Blend, b: BlendIf, sx: Float, sy: Float, tx: Float, ty: Float) {
        setFloatUniform("mode", blend.custom.toFloat())
        setFloatUniform("docMap", sx, sy, tx, ty)
        setFloatUniform("thisR", b.thisLo0.toFloat(), b.thisLo1.toFloat(), b.thisHi0.toFloat(), b.thisHi1.toFloat())
        setFloatUniform("underR", b.underLo0.toFloat(), b.underLo1.toFloat(), b.underHi0.toFloat(), b.underHi1.toFloat())
        setFloatUniform("useComp", if (blend.custom != 0) 0f else if (blend == Blend.LINEAR_DODGE) 2f else 1f)
    }

    private val screen = HashMap<Long, RuntimeShader>()

    /** On screen: a node twice [width] × [height] (below, alone; blended), its top-left panel becomes the result. Main thread only. */
    fun tripleEffect(layerId: Long, blend: Blend, b: BlendIf, width: Int, height: Int, sx: Float, sy: Float, tx: Float, ty: Float): RenderEffect {
        if (screen.size > 64) screen.clear()
        val sh = screen.getOrPut(layerId) { RuntimeShader(TRIPLE) }
        sh.setUp(blend, b, sx, sy, tx, ty)
        sh.setFloatUniform("size", width.toFloat(), height.toFloat())
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    /** Off screen: [below], the [layer] alone and [comp] (the layer blended onto below), all the same size. */
    fun mix(below: Bitmap, layer: Bitmap, comp: Bitmap, blend: Blend, b: BlendIf, sx: Float, sy: Float, tx: Float, ty: Float): Bitmap {
        val sh = RuntimeShader(THREE)
        sh.setUp(blend, b, sx, sy, tx, ty)
        sh.setInputShader("below", BitmapShader(below, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        sh.setInputShader("layer", BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        sh.setInputShader("comp", BitmapShader(comp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        return Gpu.renderOffscreen(below.width, below.height) { c ->
            c.drawRect(0f, 0f, below.width.toFloat(), below.height.toFloat(), Paint().apply { shader = sh })
        }
    }
}

