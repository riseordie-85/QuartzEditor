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

    private const val COMMON = """
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
    fun pairEffect(blend: Blend, width: Int, sx: Float, sy: Float, tx: Float, ty: Float): RenderEffect {
        val sh = RuntimeShader(PAIR)
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
