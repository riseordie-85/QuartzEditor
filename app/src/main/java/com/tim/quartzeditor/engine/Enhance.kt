package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ready-made skies for Replace sky: colors from the top down to the horizon, and a glow where the sun is. */
enum class SkyPreset(val label: String, val colors: IntArray, val positions: FloatArray?, val glow: Int?) {
    CLEAR("Clear blue", intArrayOf(0xFF2E6FD6.toInt(), 0xFF7FB2EE.toInt(), 0xFFCFE8FF.toInt()), floatArrayOf(0f, 0.55f, 1f), null),
    DEEP("Deep blue", intArrayOf(0xFF0B3D91.toInt(), 0xFF3F7BC8.toInt(), 0xFF9CC7EE.toInt()), floatArrayOf(0f, 0.6f, 1f), null),
    GOLDEN("Golden hour", intArrayOf(0xFF4A6FA5.toInt(), 0xFFB9A7A0.toInt(), 0xFFF9C46B.toInt()), floatArrayOf(0f, 0.55f, 1f), 0xFFFFE2A0.toInt()),
    SUNSET("Sunset", intArrayOf(0xFF2B1B4D.toInt(), 0xFFB04A6B.toInt(), 0xFFFF7E5F.toInt(), 0xFFFEB47B.toInt()), floatArrayOf(0f, 0.45f, 0.75f, 1f), 0xFFFFD08A.toInt()),
    DUSK("Dusk", intArrayOf(0xFF0F1C3F.toInt(), 0xFF5B4B8A.toInt(), 0xFFE89A6B.toInt()), floatArrayOf(0f, 0.6f, 1f), null),
    OVERCAST("Overcast", intArrayOf(0xFF8E9AA6.toInt(), 0xFFB8C0C8.toInt(), 0xFFDDE1E5.toInt()), floatArrayOf(0f, 0.5f, 1f), null),
    STORM("Storm", intArrayOf(0xFF2F3540.toInt(), 0xFF4B5361.toInt(), 0xFF7D8694.toInt()), floatArrayOf(0f, 0.55f, 1f), null),
}

/** A light for Relight: where it is (canvas pixels), how high, strong, warm or cool, how far it reaches. */
data class LightSettings(
    val x: Float,
    val y: Float,
    /** Height above the picture, as a share of its long side. */
    val height: Float = 0.35f,
    val strength: Float = 0.6f,
    val color: Int = 0xFFFFE6C4.toInt(),
    /** How far the light reaches before it falls off, as a share of the long side. */
    val spread: Float = 0.6f,
    /** How much the parts facing away from it darken. */
    val shadows: Float = 0.35f,
)

/**
 * The picture-changing work behind Depth blur, Depth fog, Relight, Replace sky, Denoise, Sharpen
 * and Auto enhance. Everything is worked out at whatever size it's given: small for the live
 * preview, full size when applied.
 */
object EnhanceArt {

    /** A depth map (0 far … 1 near) as a 1-byte bitmap, for the GPU. */
    fun depthBitmap(depth: FloatArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(ByteArray(w * h) { i -> (depth[i].coerceIn(0f, 1f) * 255f).roundToInt().toByte() }))
        }

    /** [b] drawn stretched over [w]×[h] (a shader for a smaller map, smoothly scaled). */
    private fun stretched(b: Bitmap, w: Int, h: Int): BitmapShader =
        BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / b.width.toFloat(), h / b.height.toFloat()) })
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }

    private fun shaded(w: Int, h: Int, sh: RuntimeShader): Bitmap = Gpu.renderOffscreen(w, h) { c ->
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sh })
    }

    private fun alphaOf(src: Bitmap): Bitmap {
        val a = src.copy(Bitmap.Config.ALPHA_8, true)
        if (a !== src) src.recycle()
        return a
    }

    // ------------------------------------------------------------ depth blur

    /**
     * Depth blur: [src] blurred by [radius] px everywhere out of focus, without the sharp parts
     * bleeding into it, and a "how hidden" mask that keeps the in-focus parts (near [focus] ±
     * [range] in depth) showing the sharp picture below.
     */
    fun depthBlur(src: Bitmap, depth: Bitmap, focus: Float, range: Float, radius: Float): Pair<Bitmap, Bitmap> {
        val w = src.width
        val h = src.height
        val soft = 0.06f + range * 0.5f
        fun shader(code: String) = RuntimeShader(code).apply {
            setInputShader("depth", stretched(depth, w, h))
            setFloatUniform("focus", focus)
            setFloatUniform("range", range)
            setFloatUniform("soft", soft)
        }
        // Only what's out of focus feeds the blur (so a sharp subject doesn't smear into it)
        val weighted = shaded(w, h, shader(BLUR_WEIGHT).apply { setInputShader("img", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)) })
        val blurred = if (radius >= 0.5f) Gpu.blurred(weighted, radius, Shader.TileMode.CLAMP).also { weighted.recycle() } else weighted
        val pixels = shaded(w, h, RuntimeShader(BLUR_COMBINE).apply {
            setInputShader("blurred", BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("img", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        })
        blurred.recycle()
        val mask = alphaOf(shaded(w, h, shader(BLUR_MASK)))
        return pixels to mask
    }

    private const val BLUR_WEIGHT = """
uniform shader img;
uniform shader depth;
uniform float focus;
uniform float range;
uniform float soft;
half4 main(float2 p) {
    float d = float(depth.eval(p).a);
    float b = smoothstep(range, range + soft, abs(d - focus));
    half4 c = img.eval(p);
    return c * half(b);
}
"""

    private const val BLUR_COMBINE = """
uniform shader blurred;
uniform shader img;
half4 main(float2 p) {
    half4 b = blurred.eval(p);
    half4 c = img.eval(p);
    // Normalized: the blur of what fed it (or the picture itself where nothing did)
    half3 rgb = b.a > 0.003 ? b.rgb / b.a : (c.a > 0.0 ? c.rgb / c.a : half3(0.0));
    return half4(rgb * c.a, c.a);
}
"""

    private const val BLUR_MASK = """
uniform shader depth;
uniform float focus;
uniform float range;
uniform float soft;
half4 main(float2 p) {
    float d = float(depth.eval(p).a);
    float b = smoothstep(range, range + soft, abs(d - focus));
    float hidden = 1.0 - b;
    return half4(0.0, 0.0, 0.0, half(hidden));
}
"""

    // ------------------------------------------------------------ depth fog

    /** Depth fog's "how hidden" mask: the fog shows from [start] (0 near … 1 far) over [softness], at [density]. */
    fun fogMask(depth: Bitmap, w: Int, h: Int, start: Float, softness: Float, density: Float): Bitmap =
        alphaOf(shaded(w, h, RuntimeShader(FOG_MASK).apply {
            setInputShader("depth", stretched(depth, w, h))
            setFloatUniform("start", start)
            setFloatUniform("soft", softness.coerceAtLeast(0.01f))
            setFloatUniform("density", density)
        }))

    private const val FOG_MASK = """
uniform shader depth;
uniform float start;
uniform float soft;
uniform float density;
half4 main(float2 p) {
    float far = 1.0 - float(depth.eval(p).a);
    float v = density * smoothstep(start, start + soft, far);
    return half4(0.0, 0.0, 0.0, half(1.0 - v));
}
"""

    // ------------------------------------------------------------ relight

    /** Which way each spot faces (from the depth's slopes), packed as colors. [relief]: how deep the depth is taken to be. */
    fun normals(depth: FloatArray, w: Int, h: Int, relief: Float): Bitmap {
        // A little smoothing first: the slopes of a noisy depth are noisier still
        val sm = FloatArray(w * h)
        val r = max(1, (max(w, h) / 400f).roundToInt())
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var n = 0
            for (dy in -r..r) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -r..r) {
                    sum += depth[yy * w + (x + dx).coerceIn(0, w - 1)]
                    n++
                }
            }
            sm[y * w + x] = sum / n
        }
        val k = relief * max(w, h)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = (sm[y * w + min(w - 1, x + 1)] - sm[y * w + max(0, x - 1)]) * 0.5f * k
            val gy = (sm[min(h - 1, y + 1) * w + x] - sm[max(0, y - 1) * w + x]) * 0.5f * k
            // Nearer is up out of the picture: a slope towards near faces that way
            var nx = -gx
            var ny = -gy
            var nz = 1f
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= len; ny /= len; nz /= len
            fun b(v: Float) = ((v * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (b(nx) shl 16) or (b(ny) shl 8) or b(nz)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The light as a picture to blend over the photo (Soft Light): 50% gray changes nothing,
     * lighter brightens (in the light's color), darker shades. ([ox], [oy]) is the canvas point at
     * the bitmap's corner and [scale] bitmap pixels per canvas pixel; [diag] the picture's long side.
     */
    fun light(normals: Bitmap, w: Int, h: Int, l: LightSettings, ox: Float, oy: Float, scale: Float, longSide: Float): Bitmap {
        val sh = RuntimeShader(LIGHT).apply {
            setInputShader("normals", stretched(normals, w, h))
            setFloatUniform("lightPos", (l.x - ox) * scale, (l.y - oy) * scale)
            setFloatUniform("height", (l.height * longSide * scale).coerceAtLeast(1f))
            setFloatUniform("radius", (l.spread * longSide * scale).coerceAtLeast(1f))
            setFloatUniform("strength", l.strength)
            setFloatUniform("shadows", l.shadows)
            setFloatUniform("color", Color.red(l.color) / 255f, Color.green(l.color) / 255f, Color.blue(l.color) / 255f)
        }
        return shaded(w, h, sh)
    }

    private const val LIGHT = """
uniform shader normals;
uniform float2 lightPos;
uniform float height;
uniform float radius;
uniform float strength;
uniform float shadows;
uniform float3 color;
half4 main(float2 p) {
    float3 n = normalize(float3(normals.eval(p).rgb) * 2.0 - 1.0);
    float2 d = lightPos - p;
    float dist = length(d);
    float3 L = normalize(float3(d, height));
    float diff = max(dot(n, L), 0.0);
    float att = 1.0 / (1.0 + (dist * dist) / (radius * radius));
    float lit = diff * att;
    float3 v = 0.5 + 0.5 * strength * (2.0 * lit * color - shadows * (1.0 - lit));
    return half4(half3(clamp(v, 0.0, 1.0)), 1.0);
}
"""

    // ------------------------------------------------------------ sky

    /**
     * Where the sky is (0..1), from the depth: the farthest parts ([threshold] and beyond) that
     * reach the top edge, pockets of sky the clouds cut off, and a short way on into the sky's own
     * colors (see [SkyFinder]), with edges following the picture. Null when there's no sky.
     */
    fun skyMask(src: Bitmap, depth: FloatArray, w: Int, h: Int, threshold: Float): FloatArray? {
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(n) { i ->
            val c = px[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }
        val grown = SkyFinder.find(px, depth, w, h, threshold) ?: return null
        val p = FloatArray(n) { if (grown[it]) 1f else 0f }
        val f = Cutout.guidedFilter(gray, p, w, h, max(2, (max(w, h) / 250f).roundToInt()), 1e-3f)
        for (i in f.indices) {
            // Leaning towards sky where it's unsure (a soft edge shows the new sky, not a halo of the old)
            val t = ((f[i] - 0.15f) / 0.5f).coerceIn(0f, 1f)
            f[i] = t * t * (3f - 2f * t)
        }
        return f
    }

    /** The lowest row the sky reaches (0..1 of the height), for where the new sky's horizon goes. */
    fun skyBottom(mask: FloatArray, w: Int, h: Int): Float {
        for (y in h - 1 downTo 0) {
            var n = 0
            for (x in 0 until w) if (mask[y * w + x] > 0.5f) n++
            if (n > w * 0.02f) return (y + 1f) / h
        }
        return 0.5f
    }

    /**
     * A ready-made sky [w]×[h], its horizon at [horizon] (0..1 of the height), with [clouds]
     * (0..1) drifting across it.
     */
    fun skyImage(preset: SkyPreset, w: Int, h: Int, horizon: Float, clouds: Float, seed: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val hy = (horizon.coerceIn(0.15f, 1f) * h)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, hy, preset.colors, preset.positions, Shader.TileMode.CLAMP)
        })
        preset.glow?.let { g ->
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = RadialGradient(w * 0.7f, hy, max(w, h) * 0.45f, intArrayOf(g, g and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            })
        }
        if (clouds > 0.01f) {
            val sh = RuntimeShader(CLOUDS).apply {
                setFloatUniform("size", w.toFloat(), hy)
                setFloatUniform("amount", clouds)
                setFloatUniform("seed", seed)
                // Sunlit tops and shaded undersides, in the sky's own light
                val (tint, shade) = when (preset) {
                    SkyPreset.SUNSET, SkyPreset.GOLDEN -> 0xFFFFE0C8.toInt() to 0xFF6B5272.toInt()
                    SkyPreset.DUSK -> 0xFFF2D2D8.toInt() to 0xFF4A3D66.toInt()
                    SkyPreset.STORM -> 0xFFC4C8CE.toInt() to 0xFF464C56.toInt()
                    SkyPreset.OVERCAST -> 0xFFE4E6EA.toInt() to 0xFF8C929C.toInt()
                    else -> Color.WHITE to 0xFFB3BFD6.toInt()
                }
                setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
                setFloatUniform("shade", Color.red(shade) / 255f, Color.green(shade) / 255f, Color.blue(shade) / 255f)
            }
            // (Shaders only run on the GPU: the clouds are made there, then laid over the sky)
            val cl = shaded(w, h, sh)
            c.drawBitmap(cl, 0f, 0f, null)
            cl.recycle()
        }
        return out
    }

    private const val CLOUDS = """
uniform float2 size;
uniform float amount;
uniform float seed;
uniform float3 tint;
uniform float3 shade;
// A hash without sin(): sin of big numbers is too coarse on phone GPUs (it made blocks)
float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int o = 0; o < 6; o++) {
        v += a * noise(p);
        p = float2(1.6 * p.x + 1.2 * p.y, -1.2 * p.x + 1.6 * p.y) + float2(3.1, 1.7);
        a *= 0.5;
    }
    return v;
}
half4 main(float2 p) {
    float t = clamp(p.y / max(size.y, 1.0), 0.0, 1.0);
    // Flatter towards the horizon (they're farther away); only up-down is squeezed, so nothing shears
    float2 uv = p / size.x;
    float2 q = float2(uv.x * 4.0, uv.y * 6.0 + t * t * 3.5) + float2(seed * 1.37, seed * 0.71);
    float2 warp = float2(fbm(q + float2(1.7, 9.2)), fbm(q + float2(8.3, 2.8)));
    float2 r = q + 0.3 * warp;
    float d = fbm(r);
    float cover = mix(0.66, 0.36, amount);
    float dens = smoothstep(cover, cover + 0.16, d);
    // Lit from above: thicker just above means more shadow below
    float up = fbm(r - float2(0.0, 0.12));
    float light = clamp(0.7 + (d - up) * 4.0 - smoothstep(cover, cover + 0.45, d) * 0.45, 0.0, 1.0);
    float3 col = mix(shade, tint, light);
    float a = dens * (1.0 - t * 0.55) * 0.97;
    return half4(half3(col * a), half(a));
}
"""

    // ------------------------------------------------------------ denoise and sharpen

    /** Denoise: grain smoothed out while edges stay crisp; color speckles go first. [scale]: picture pixels per full-size pixel. */
    fun denoise(src: Bitmap, strength: Float, colorStrength: Float, scale: Float): Bitmap {
        val sh = RuntimeShader(DENOISE).apply {
            setInputShader("img", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("strength", strength.coerceIn(0f, 1f))
            setFloatUniform("chroma", colorStrength.coerceIn(0f, 1f))
            setFloatUniform("stepPx", ((1f + strength * 1.5f) * scale).coerceAtLeast(0.5f))
        }
        return shaded(src.width, src.height, sh)
    }

    private const val DENOISE = """
uniform shader img;
uniform float strength;
uniform float chroma;
uniform float stepPx;
float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
half4 main(float2 p) {
    float4 c0 = float4(img.eval(p));
    if (c0.a <= 0.0) return half4(0.0);
    float3 c = c0.rgb / c0.a;
    float sigma = 0.015 + 0.12 * strength;
    float3 sum = float3(0.0);
    float wsum = 0.0;
    // Edge-keeping smoothing (bilateral) over a 5×5 neighborhood
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            float4 s = float4(img.eval(p + float2(float(x), float(y)) * stepPx));
            float3 sc = s.a > 0.0 ? s.rgb / s.a : c;
            float3 dc = sc - c;
            float w = exp(-dot(dc, dc) / (2.0 * sigma * sigma)) * exp(-float(x * x + y * y) / 8.0);
            sum += sc * w;
            wsum += w;
        }
    }
    float3 smooth = sum / wsum;
    // Color speckles: the color (not the brightness) evened out over a wider area
    float l0 = luma(smooth);
    float2 cc = float2(0.0);
    float cw = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            float4 s = float4(img.eval(p + float2(float(x), float(y)) * stepPx * 2.5));
            float3 sc = s.a > 0.0 ? s.rgb / s.a : c;
            float ls = luma(sc);
            float w = exp(-(ls - l0) * (ls - l0) / 0.01);
            cc += float2(sc.b - ls, sc.r - ls) * w;
            cw += w;
        }
    }
    cc /= cw;
    float2 own = float2(smooth.b - l0, smooth.r - l0);
    float2 ch = mix(own, cc, chroma);
    float r = l0 + ch.y;
    float b = l0 + ch.x;
    float g = (l0 - 0.299 * r - 0.114 * b) / 0.587;
    float3 outc = mix(c, clamp(float3(r, g, b), 0.0, 1.0), max(strength, chroma));
    return half4(half3(outc * c0.a), half(c0.a));
}
"""

    /** Sharpen (unsharp mask on brightness only, so colors don't fringe): [amount] 0..3, [radius] px, small differences under [threshold] left alone. */
    fun sharpen(src: Bitmap, amount: Float, radius: Float, threshold: Float): Bitmap {
        val blurred = Gpu.blurred(src, radius.coerceAtLeast(0.3f), Shader.TileMode.CLAMP)
        val sh = RuntimeShader(SHARPEN).apply {
            setInputShader("img", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setInputShader("blurred", BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            setFloatUniform("amount", amount)
            setFloatUniform("threshold", threshold)
        }
        return shaded(src.width, src.height, sh).also { blurred.recycle() }
    }

    private const val SHARPEN = """
uniform shader img;
uniform shader blurred;
uniform float amount;
uniform float threshold;
float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
half4 main(float2 p) {
    float4 c0 = float4(img.eval(p));
    if (c0.a <= 0.0) return half4(0.0);
    float4 b0 = float4(blurred.eval(p));
    float3 c = c0.rgb / c0.a;
    float3 b = b0.a > 0.0 ? b0.rgb / b0.a : c;
    float d = luma(c) - luma(b);
    // Below the threshold: left as it is (grain isn't sharpened); above it, fading in
    float k = smoothstep(threshold, threshold * 2.0 + 0.002, abs(d));
    float3 outc = clamp(c + amount * d * k, 0.0, 1.0);
    return half4(half3(outc * c0.a), half(c0.a));
}
"""

    // ------------------------------------------------------------ auto enhance

    /**
     * Auto enhance's Levels for [pic]: each channel stretched between its darkest and lightest
     * 0.3% (which also evens out a color cast), and midtones lifted or lowered towards a natural
     * brightness.
     */
    fun autoLevels(pic: Bitmap): Levels {
        val w = pic.width
        val h = pic.height
        val px = IntArray(w * h)
        pic.getPixels(px, 0, w, 0, 0, w, h)
        val hist = Array(3) { IntArray(256) }
        var count = 0
        for (c in px) {
            if ((c ushr 24) < 128) continue
            hist[0][(c shr 16) and 0xFF]++
            hist[1][(c shr 8) and 0xFF]++
            hist[2][c and 0xFF]++
            count++
        }
        if (count == 0) return Levels()
        val clip = (count * 0.003f).toInt()
        fun low(hh: IntArray): Int {
            var s = 0
            for (i in 0..255) { s += hh[i]; if (s > clip) return i }
            return 0
        }
        fun high(hh: IntArray): Int {
            var s = 0
            for (i in 255 downTo 0) { s += hh[i]; if (s > clip) return i }
            return 255
        }
        val chans = (0 until 3).map { ch ->
            val lo = low(hist[ch])
            val hi = max(lo + 10, high(hist[ch]))
            // Never stretch too hard (a nearly flat picture would turn harsh)
            val loC = min(lo, 60)
            val hiC = max(hi, 195)
            loC to hiC
        }
        // Brightness after the stretch: midtones nudged towards ~46%
        var sum = 0.0
        for (c in px) {
            if ((c ushr 24) < 128) continue
            var l = 0.0
            for ((i, sh) in listOf(16, 8, 0).withIndex()) {
                val (lo, hi) = chans[i]
                val v = (((c shr sh) and 0xFF) - lo).toDouble() / (hi - lo)
                l += v.coerceIn(0.0, 1.0) * doubleArrayOf(0.299, 0.587, 0.114)[i]
            }
            sum += l
        }
        val mean = (sum / count).coerceIn(0.05, 0.95)
        val gamma = (kotlin.math.ln(mean) / kotlin.math.ln(0.46)).toFloat().coerceIn(0.7f, 1.5f)
        return Levels(listOf(
            LevelsChannel(gamma = gamma),
            LevelsChannel(chans[0].first / 255f, chans[0].second / 255f),
            LevelsChannel(chans[1].first / 255f, chans[1].second / 255f),
            LevelsChannel(chans[2].first / 255f, chans[2].second / 255f),
        ))
    }

    /** A copy of [src] cropped to [r] (in its pixels). */
    fun crop(src: Bitmap, r: Rect): Bitmap = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
}
