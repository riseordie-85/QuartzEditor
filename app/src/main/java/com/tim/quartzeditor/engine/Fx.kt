package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// Adjustments and filters (shared with Quartz Gallery's editor)

enum class Adjust(val label: String, val bipolar: Boolean) {
    EXPOSURE("Exposure", true),
    BRIGHTNESS("Brightness", true),
    CONTRAST("Contrast", true),
    HIGHLIGHTS("Highlights", true),
    SHADOWS("Shadows", true),
    SATURATION("Saturation", true),
    VIBRANCE("Vibrance", true),
    WARMTH("Warmth", true),
    TINT("Tint", true),
    SHARPNESS("Sharpness", false),
    VIGNETTE("Vignette", false),
    GRAIN("Grain", false),
}

/**
 * Filter looks: a color matrix (rows for R, G, B: r, g, b coefficients + offset in 0..1)
 * plus optional vignette and grain. [duotone] maps brightness onto two chosen colors instead.
 */
enum class FilterPreset(
    val label: String,
    val matrix: FloatArray,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val duotone: Boolean = false,
) {
    NONE("Original", IDENTITY),
    VIVID("Vivid", cm(saturation(1.4f), contrast(1.1f))),
    WARM("Warm", cm(scale(1.08f, 1.0f, 0.88f), saturation(1.1f))),
    COOL("Cool", cm(scale(0.9f, 1.0f, 1.1f), saturation(1.05f))),
    GOLDEN("Golden", cm(scale(1.1f, 1.02f, 0.82f), contrast(1.05f), offset(0.03f, 0.01f, -0.02f))),
    FADE("Fade", cm(contrast(0.82f), saturation(0.8f), offset(0.05f, 0.05f, 0.05f))),
    VINTAGE("Vintage", cm(sepiaMix(0.45f), contrast(0.9f), offset(0.04f, 0.03f, 0.0f)), vignette = 0.35f, grain = 0.3f),
    DRAMATIC("Dramatic", cm(contrast(1.35f), saturation(0.85f)), vignette = 0.35f),
    SEPIA("Sepia", cm(sepiaMix(1f))),
    BW("B&W", cm(saturation(0f), contrast(1.1f))),
    NOIR("Noir", cm(saturation(0f), contrast(1.45f)), vignette = 0.45f, grain = 0.25f),
    INVERT("Invert", INVERT_MATRIX),
    DUOTONE("Duotone", IDENTITY, duotone = true),
}

/** Ready-made duotone pairs: shadows color to highlights color. */
val DUOTONE_PRESETS: List<Pair<Int, Int>> = listOf(
    0xFF1B1F4B.toInt() to 0xFFFFB86B.toInt(), // navy / peach
    0xFF2D0B59.toInt() to 0xFF2EE6D6.toInt(), // purple / aqua
    0xFF0B3D2E.toInt() to 0xFFE8F5A3.toInt(), // forest / lime
    0xFF3A0CA3.toInt() to 0xFFF72585.toInt(), // indigo / magenta
    0xFF101820.toInt() to 0xFFFEE715.toInt(), // black / yellow
    0xFF5C1A1B.toInt() to 0xFFF4D6CC.toInt(), // oxblood / blush
    0xFF00324A.toInt() to 0xFFF2F2F2.toInt(), // deep teal / white
)

/**
 * Adjustment sliders plus a filter look, applied together. Adjust values are -1..1
 * (Sharpness, Vignette and Grain 0..1).
 */
data class FxParams(
    val adjust: Map<Adjust, Float> = emptyMap(),
    val filter: FilterPreset = FilterPreset.NONE,
    val filterStrength: Float = 1f,
    val duoShadow: Int = DUOTONE_PRESETS[0].first,
    val duoHighlight: Int = DUOTONE_PRESETS[0].second,
) {
    fun adjust(a: Adjust): Float = adjust[a] ?: 0f

    val isIdentity: Boolean
        get() = filter == FilterPreset.NONE && adjust.values.all { it == 0f }

    /** Vignette strength 0..1 (slider plus the filter's own). */
    val vignette: Float
        get() = (adjust(Adjust.VIGNETTE) + filter.vignette * (if (filter == FilterPreset.NONE) 0f else filterStrength)).coerceIn(0f, 1f)

    fun toJson(): org.json.JSONObject {
        val a = org.json.JSONObject()
        for ((k, v) in adjust) if (v != 0f) a.put(k.name, v.toDouble())
        return org.json.JSONObject()
            .put("adjust", a)
            .put("filter", filter.name)
            .put("strength", filterStrength.toDouble())
            .put("duoShadow", duoShadow)
            .put("duoHighlight", duoHighlight)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject?): FxParams? {
            if (o == null) return null
            val a = o.optJSONObject("adjust")
            val map = HashMap<Adjust, Float>()
            if (a != null) for (k in Adjust.entries) if (a.has(k.name)) map[k] = a.optDouble(k.name).toFloat()
            return FxParams(
                adjust = map,
                filter = FilterPreset.entries.firstOrNull { it.name == o.optString("filter") } ?: FilterPreset.NONE,
                filterStrength = o.optDouble("strength", 1.0).toFloat(),
                duoShadow = o.optInt("duoShadow", DUOTONE_PRESETS[0].first),
                duoHighlight = o.optInt("duoHighlight", DUOTONE_PRESETS[0].second),
            )
        }
    }
}

/** GPU work that runs off-screen and comes back as a normal bitmap. */
object Gpu {
    /** Applies [fx] to [src] at full size. [docW]/[docH] and [left]/[top] place it on the canvas for the vignette. */
    fun applyFx(src: Bitmap, fx: FxParams, left: Float, top: Float, docW: Float, docH: Float): Bitmap =
        renderOffscreen(src.width, src.height) { canvas ->
            val cs = ColorShader()
            cs.bind(fx, src)
            canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), cs.paint)
            drawVignette(canvas, fx.vignette, -left, -top, docW, docH)
        }

    /**
     * Darkens toward the canvas corners, only where there are already pixels.
     * ([ox], [oy]) is where the canvas's top-left corner is in [canvas]'s coordinates.
     */
    fun drawVignette(canvas: Canvas, v: Float, ox: Float, oy: Float, docW: Float, docH: Float) {
        if (v <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        p.shader = RadialGradient(
            ox + docW / 2f,
            oy + docH / 2f,
            hypot(docW, docH) / 2f,
            intArrayOf(0, 0, Color.argb((v * 235).roundToInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPaint(p)
    }

    /** A blurred copy. */
    fun blurred(src: Bitmap, radius: Float): Bitmap =
        renderOffscreen(src.width, src.height, RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)) {
            it.drawBitmap(src, 0f, 0f, null)
        }

    fun renderOffscreen(
        width: Int,
        height: Int,
        effect: RenderEffect? = null,
        draw: (Canvas) -> Unit,
    ): Bitmap {
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.isOpaque = false
            val node = RenderNode("edit")
            node.setPosition(0, 0, width, height)
            if (effect != null) node.setRenderEffect(effect)
            val canvas = node.beginRecording()
            draw(canvas)
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            var image = reader.acquireLatestImage()
            var tries = 0
            while (image == null && tries < 50) {
                Thread.sleep(10)
                image = reader.acquireLatestImage()
                tries++
            }
            if (image == null) throw IllegalStateException("GPU render produced no image")
            image.use { img ->
                val buffer = img.hardwareBuffer ?: throw IllegalStateException("No hardware buffer")
                buffer.use { hb ->
                    val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: throw IllegalStateException("Couldn't wrap GPU buffer")
                    val copy = hw.copy(Bitmap.Config.ARGB_8888, true)
                    hw.recycle()
                    return copy
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}

/**
 * Color adjustments and filters as an Android 13+ GPU shader (AGSL). One instance per
 * place that draws (preview, each filter thumbnail, export).
 */
class ColorShader {
    private val shader = RuntimeShader(AGSL)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bound: Bitmap? = null

    fun bind(state: FxParams, src: Bitmap) {
        if (src !== bound) {
            val input = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("image", input)
            bound = src
        }
        // Sample spacing that scales with the image, so preview and full size look the same
        setUniforms(state, max(1f, max(src.width, src.height) / 1500f))
        paint.shader = shader
    }

    /**
     * The shader as a [RenderEffect] input for adjustment layers on screen: the effect supplies the
     * picture below as "image". [unit] is the sample spacing in screen pixels.
     */
    fun effect(state: FxParams, unit: Float): android.graphics.RenderEffect {
        setUniforms(state, unit)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "image")
    }

    private fun setUniforms(state: FxParams, unit: Float) {
        val strength = if (state.filter == FilterPreset.NONE) 0f else state.filterStrength
        shader.setFloatUniform("unit", unit)
        shader.setFloatUniform("exposure", state.adjust(Adjust.EXPOSURE))
        shader.setFloatUniform("brightness", state.adjust(Adjust.BRIGHTNESS))
        shader.setFloatUniform("contrastAmt", state.adjust(Adjust.CONTRAST))
        shader.setFloatUniform("highlights", state.adjust(Adjust.HIGHLIGHTS))
        shader.setFloatUniform("shadows", state.adjust(Adjust.SHADOWS))
        shader.setFloatUniform("saturationAmt", state.adjust(Adjust.SATURATION))
        shader.setFloatUniform("vibrance", state.adjust(Adjust.VIBRANCE))
        shader.setFloatUniform("warmth", state.adjust(Adjust.WARMTH))
        shader.setFloatUniform("tintAmt", state.adjust(Adjust.TINT))
        shader.setFloatUniform("sharpness", state.adjust(Adjust.SHARPNESS))
        shader.setFloatUniform(
            "grain",
            (state.adjust(Adjust.GRAIN) + state.filter.grain * strength).coerceIn(0f, 1f),
        )
        val m = state.filter.matrix
        shader.setFloatUniform("fr", m[0], m[1], m[2], m[3])
        shader.setFloatUniform("fg", m[4], m[5], m[6], m[7])
        shader.setFloatUniform("fb", m[8], m[9], m[10], m[11])
        shader.setFloatUniform("fstrength", strength)
        val duo = state.filter.duotone
        shader.setFloatUniform("duo", if (duo) 1f else 0f)
        shader.setFloatUniform("duoA", Color.red(state.duoShadow) / 255f, Color.green(state.duoShadow) / 255f, Color.blue(state.duoShadow) / 255f)
        shader.setFloatUniform("duoB", Color.red(state.duoHighlight) / 255f, Color.green(state.duoHighlight) / 255f, Color.blue(state.duoHighlight) / 255f)
    }

    companion object {
        private const val AGSL = """
uniform shader image;
uniform float unit;
uniform float exposure;
uniform float brightness;
uniform float contrastAmt;
uniform float highlights;
uniform float shadows;
uniform float saturationAmt;
uniform float vibrance;
uniform float warmth;
uniform float tintAmt;
uniform float sharpness;
uniform float grain;
uniform float4 fr;
uniform float4 fg;
uniform float4 fb;
uniform float fstrength;
uniform float duo;
uniform float3 duoA;
uniform float3 duoB;

float3 straight(float4 c) {
    return c.a > 0.0 ? c.rgb / c.a : float3(0.0);
}

float luma(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 p) {
    float4 src = float4(image.eval(p));
    float a = src.a;
    float3 c = straight(src);

    if (sharpness > 0.0) {
        float3 n = straight(float4(image.eval(p + float2(unit, 0.0))))
                 + straight(float4(image.eval(p - float2(unit, 0.0))))
                 + straight(float4(image.eval(p + float2(0.0, unit))))
                 + straight(float4(image.eval(p - float2(0.0, unit))));
        c = c + (c - n * 0.25) * sharpness * 1.5;
    }

    c = c * exp2(exposure * 2.0);
    c = c + brightness * 0.25;

    float k = contrastAmt >= 0.0 ? 1.0 + contrastAmt : 1.0 + contrastAmt * 0.7;
    c = (c - 0.5) * k + 0.5;

    float l = clamp(luma(c), 0.0, 1.0);
    float ws = 1.0 - smoothstep(0.0, 0.6, l);
    float wh = smoothstep(0.4, 1.0, l);
    c = c + shadows * 0.35 * ws + highlights * 0.35 * wh;

    c.r = c.r + warmth * 0.08;
    c.b = c.b - warmth * 0.08;
    c.g = c.g - tintAmt * 0.06;
    c.r = c.r + tintAmt * 0.03;
    c.b = c.b + tintAmt * 0.03;

    float l2 = luma(c);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float vib = vibrance * (1.0 - clamp(sat, 0.0, 1.0));
    c = mix(float3(l2), c, 1.0 + saturationAmt + vib);
    c = clamp(c, 0.0, 1.0);

    float3 f = float3(dot(fr.rgb, c) + fr.a, dot(fg.rgb, c) + fg.a, dot(fb.rgb, c) + fb.a);
    if (duo > 0.5) {
        f = mix(duoA, duoB, smoothstep(0.0, 1.0, clamp(luma(c), 0.0, 1.0)));
    }
    c = mix(c, f, fstrength);

    if (grain > 0.0) {
        float g = hash(floor(p / unit)) - 0.5;
        c = c + g * grain * 0.18;
    }

    c = clamp(c, 0.0, 1.0);
    return half4(half3(c * a), half(a));
}
"""
    }
}

// ---------------------------------------------------------------- color matrix helpers

private val IDENTITY = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
)

private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 1f,
    0f, -1f, 0f, 1f,
    0f, 0f, -1f, 1f,
)

/** Combines matrices (applied in order) into 3 rows of [r, g, b, offset(0..1)]. */
private fun cm(vararg steps: ColorMatrix): FloatArray {
    val m = ColorMatrix()
    for (s in steps) m.postConcat(s)
    val a = m.array
    return floatArrayOf(
        a[0], a[1], a[2], a[4] / 255f,
        a[5], a[6], a[7], a[9] / 255f,
        a[10], a[11], a[12], a[14] / 255f,
    )
}

private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

private fun contrast(c: Float): ColorMatrix {
    val t = (1f - c) * 127.5f
    return ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

private fun offset(r: Float, g: Float, b: Float) = ColorMatrix(
    floatArrayOf(
        1f, 0f, 0f, 0f, r * 255f,
        0f, 1f, 0f, 0f, g * 255f,
        0f, 0f, 1f, 0f, b * 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

/** Classic sepia, blended with the original by [amount]. */
private fun sepiaMix(amount: Float): ColorMatrix {
    val s = floatArrayOf(
        0.393f, 0.769f, 0.189f,
        0.349f, 0.686f, 0.168f,
        0.272f, 0.534f, 0.131f,
    )
    val out = FloatArray(20)
    for (row in 0..2) {
        for (col in 0..2) {
            val id = if (row == col) 1f else 0f
            out[row * 5 + col] = id + (s[row * 3 + col] - id) * amount
        }
    }
    out[18] = 1f
    return ColorMatrix(out)
}
