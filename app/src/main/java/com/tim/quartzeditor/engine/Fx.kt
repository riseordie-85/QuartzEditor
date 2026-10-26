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
    /** What this mainly is (decides which window an adjustment layer opens and its icon). */
    val kind: FxKind = FxKind.ADJUST,
    val levels: Levels? = null,
    val curves: Curves? = null,
    /** Gaussian blur radius in canvas pixels (0 = none). */
    val blur: Float = 0f,
    val hueSat: HueSat? = null,
    val colorBalance: ColorBalance? = null,
    val blackWhite: BlackWhite? = null,
    val photoFilter: PhotoFilter? = null,
    val gradientMap: GradientMap? = null,
    val channelMixer: ChannelMixer? = null,
    val selectiveColor: SelectiveColor? = null,
    val colorLookup: ColorLookup? = null,
    /** Posterize: how many levels each channel keeps (0 = off, 2..255). */
    val posterize: Int = 0,
    /** Threshold: brighter than this (0..1) is white, the rest black (null = off). */
    val threshold: Float? = null,
) {
    fun adjust(a: Adjust): Float = adjust[a] ?: 0f

    /** Levels or Curves change something (they need the lookup table). */
    val hasTone: Boolean get() = (levels?.isIdentity == false) || (curves?.isIdentity == false)

    val isIdentity: Boolean
        get() = filter == FilterPreset.NONE && adjust.values.all { it == 0f } && !hasTone && blur <= 0f && !hasColor

    /** One of the Photoshop-style color adjustments changes something. */
    val hasColor: Boolean
        get() = (hueSat?.isIdentity == false) || (colorBalance?.isIdentity == false) || blackWhite != null ||
            (photoFilter?.isIdentity == false) || gradientMap != null || (channelMixer?.isIdentity == false) ||
            (selectiveColor?.isIdentity == false) || colorLookup != null || posterize >= 2 || threshold != null

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
            .put("kind", kind.name)
            .put("blur", blur.toDouble())
            .also { o -> levels?.let { o.put("levels", it.toJson()) } }
            .also { o -> curves?.let { o.put("curves", it.toJson()) } }
            .also { o -> hueSat?.let { o.put("hueSat", it.toJson()) } }
            .also { o -> colorBalance?.let { o.put("colorBalance", it.toJson()) } }
            .also { o -> blackWhite?.let { o.put("blackWhite", it.toJson()) } }
            .also { o -> photoFilter?.let { o.put("photoFilter", it.toJson()) } }
            .also { o -> gradientMap?.let { o.put("gradientMap", it.toJson()) } }
            .also { o -> channelMixer?.let { o.put("channelMixer", it.toJson()) } }
            .also { o -> selectiveColor?.let { o.put("selectiveColor", it.toJson()) } }
            .also { o -> colorLookup?.let { o.put("colorLookup", it.toJson()) } }
            .also { o -> if (posterize > 0) o.put("posterize", posterize) }
            .also { o -> threshold?.let { o.put("threshold", it.toDouble()) } }
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
                kind = FxKind.entries.firstOrNull { it.name == o.optString("kind") } ?: FxKind.ADJUST,
                levels = runCatching { Levels.fromJson(o.optJSONArray("levels")) }.getOrNull(),
                curves = runCatching { Curves.fromJson(o.optJSONArray("curves")) }.getOrNull(),
                blur = o.optDouble("blur", 0.0).toFloat(),
                hueSat = runCatching { HueSat.fromJson(o.optJSONObject("hueSat")) }.getOrNull(),
                colorBalance = runCatching { ColorBalance.fromJson(o.optJSONObject("colorBalance")) }.getOrNull(),
                blackWhite = runCatching { BlackWhite.fromJson(o.optJSONObject("blackWhite")) }.getOrNull(),
                photoFilter = runCatching { PhotoFilter.fromJson(o.optJSONObject("photoFilter")) }.getOrNull(),
                gradientMap = runCatching { GradientMap.fromJson(o.optJSONObject("gradientMap")) }.getOrNull(),
                channelMixer = runCatching { ChannelMixer.fromJson(o.optJSONObject("channelMixer")) }.getOrNull(),
                selectiveColor = runCatching { SelectiveColor.fromJson(o.optJSONObject("selectiveColor")) }.getOrNull(),
                colorLookup = runCatching { ColorLookup.fromJson(o.optJSONObject("colorLookup")) }.getOrNull(),
                posterize = o.optInt("posterize", 0),
                threshold = if (o.has("threshold")) o.optDouble("threshold").toFloat() else null,
            )
        }
    }
}

/** GPU work that runs off-screen and comes back as a normal bitmap. */
object Gpu {
    /** The GPU's biggest texture side (asked once; 4096 when it can't be found out). */
    val maxTexture: Int by lazy { queryMaxTexture().coerceIn(4096, 16384) }

    private fun queryMaxTexture(): Int = runCatching {
        val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching 4096
        try {
            val attribs = intArrayOf(
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return@runCatching 4096
            val config = configs[0]
            val ctx = android.opengl.EGL14.eglCreateContext(display, config, android.opengl.EGL14.EGL_NO_CONTEXT, intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
            val surface = android.opengl.EGL14.eglCreatePbufferSurface(display, config, intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE), 0)
            try {
                android.opengl.EGL14.eglMakeCurrent(display, surface, surface, ctx)
                val out = IntArray(1)
                android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
                if (out[0] > 0) out[0] else 4096
            } finally {
                android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
                android.opengl.EGL14.eglDestroySurface(display, surface)
                android.opengl.EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            android.opengl.EGL14.eglTerminate(display)
        }
    }.getOrDefault(4096)

    /**
     * Applies [fx] to [src] at full size. [docW]/[docH] and [left]/[top] place it on the canvas for
     * the vignette. [pixelScale] is [src]'s pixels per canvas pixel (for the blur radius).
     * [clampEdges] blurs as if the edge pixels went on (for a whole picture); otherwise past the
     * edges is see-through (for a layer, which should have room around it).
     */
    fun applyFx(
        src: Bitmap,
        fx: FxParams,
        left: Float,
        top: Float,
        docW: Float,
        docH: Float,
        pixelScale: Float = 1f,
        clampEdges: Boolean = false,
    ): Bitmap {
        val r = fx.blur * pixelScale
        val input = if (r > 0.3f) blurred(src, r, if (clampEdges) Shader.TileMode.CLAMP else Shader.TileMode.DECAL) else src
        try {
            if (fx.copy(blur = 0f).isIdentity) return if (input === src) src.copy(Bitmap.Config.ARGB_8888, true) else input
            return renderOffscreen(input.width, input.height) { canvas ->
                val cs = ColorShader()
                cs.bind(fx, input)
                canvas.drawRect(0f, 0f, input.width.toFloat(), input.height.toFloat(), cs.paint)
                drawVignette(canvas, fx.vignette, -left, -top, docW, docH)
            }
        } finally {
            if (input !== src && !fx.copy(blur = 0f).isIdentity) input.recycle()
        }
    }

    /**
     * What [fx]'s colour changes do to every colour, as an [n]³ table (RGB bytes, red changing
     * fastest, then green, then blue), e.g. for a .cube file. Sharpening and grain are left out.
     */
    fun colorTable(fx: FxParams, n: Int): ByteArray? {
        val src = Bitmap.createBitmap(n, n * n, Bitmap.Config.ARGB_8888)
        try {
            val px = IntArray(n * n * n)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                fun v(k: Int) = (k * 255 + (n - 1) / 2) / (n - 1)
                px[(b * n + g) * n + r] = Color.rgb(v(r), v(g), v(b))
            }
            src.setPixels(px, 0, n, 0, 0, n, n * n)
            val out = renderOffscreen(n, n * n) { canvas ->
                val cs = ColorShader().also { it.noGrain = true }
                cs.bind(fx.copy(adjust = fx.adjust - Adjust.SHARPNESS - Adjust.GRAIN - Adjust.VIGNETTE, blur = 0f), src)
                canvas.drawRect(0f, 0f, n.toFloat(), (n * n).toFloat(), cs.paint)
            }
            try {
                out.getPixels(px, 0, n, 0, 0, n, n * n)
            } finally {
                out.recycle()
            }
            return ByteArray(px.size * 3).also { a ->
                for (i in px.indices) {
                    a[i * 3] = (px[i] shr 16).toByte()
                    a[i * 3 + 1] = (px[i] shr 8).toByte()
                    a[i * 3 + 2] = px[i].toByte()
                }
            }
        } catch (e: Exception) {
            return null
        } finally {
            src.recycle()
        }
    }

    /** Gaussian blur of the given radius (pixels) as an effect. */
    fun blurEffect(radius: Float, edges: Shader.TileMode = Shader.TileMode.DECAL): RenderEffect =
        RenderEffect.createBlurEffect(radius, radius, edges)

    /** A copy of [src] with [pad] see-through pixels added on every side (room for a blur to spread). */
    fun padded(src: Bitmap, pad: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    /** How far a blur of [radius] reaches (pixels). */
    fun blurReach(radius: Float): Int = kotlin.math.ceil(radius * 2f + 4f).toInt()

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
    fun blurred(src: Bitmap, radius: Float, edges: Shader.TileMode = Shader.TileMode.DECAL): Bitmap =
        renderOffscreen(src.width, src.height, RenderEffect.createBlurEffect(radius, radius, edges)) {
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
    /** Leaves out grain (it depends on where a pixel is, not its color): for color tables. */
    var noGrain = false

    private val shader: RuntimeShader
    /** Whether this device's GPU took the full shader (with the color adjustments). */
    private val full: Boolean

    init {
        var ok = colorSupported
        shader = try {
            if (!ok) RuntimeShader(AGSL_BASIC) else RuntimeShader(AGSL)
        } catch (e: Exception) {
            // Never lose the basic adjustments over the newer ones: fall back, and say why in the log
            android.util.Log.e("QuartzShader", "Color adjustments shader failed: ${e.message}")
            ok = false
            colorSupported = false
            RuntimeShader(AGSL_BASIC)
        }
        full = ok
    }
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

    private var lutKey: Any? = Unit

    /** Levels and Curves as a lookup table (an unchanged one when there are none). */
    private fun bindLut(state: FxParams) {
        val key: Any? = if (state.hasTone) state.levels to state.curves else null
        if (key == lutKey) return
        lutKey = key
        val bmp = if (key == null) IDENTITY_LUT else Tone.lut(state.levels, state.curves)
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader("lut", s)
    }

    private var gmapKey: Any? = Unit

    /** The gradient map's lookup table (a plain one when there's none). */
    private fun bindGradientMap(state: FxParams) {
        val key: Any? = state.gradientMap
        if (key == gmapKey) return
        gmapKey = key
        val bmp = state.gradientMap?.lut() ?: IDENTITY_LUT
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader("gmap", s)
    }

    private fun setColorUniforms(state: FxParams) {
        if (!full) return
        bindGradientMap(state)
        val hs = state.hueSat?.takeUnless { it.isIdentity }
        shader.setFloatUniform("useHS", if (hs != null) 1f else 0f)
        val m = hs?.master ?: HueShift()
        shader.setFloatUniform("hsMaster", m.hue, m.sat, m.light)
        for (i in 0 until 6) {
            val b = hs?.bands?.getOrNull(i) ?: HueShift()
            shader.setFloatUniform("hsBand$i", b.hue, b.sat, b.light)
        }
        shader.setFloatUniform("hsColorize", if (hs?.colorize == true) 1f else 0f, (hs?.colorHue ?: 0f) / 360f, hs?.colorSat ?: 0f)
        val cb = state.colorBalance?.takeUnless { it.isIdentity }
        shader.setFloatUniform("useCB", if (cb != null) 1f else 0f)
        val cbs = cb?.shadows ?: ColorShift()
        val cbm = cb?.midtones ?: ColorShift()
        val cbh = cb?.highlights ?: ColorShift()
        shader.setFloatUniform("cbS", cbs.cyanRed, cbs.magentaGreen, cbs.yellowBlue)
        shader.setFloatUniform("cbM", cbm.cyanRed, cbm.magentaGreen, cbm.yellowBlue)
        shader.setFloatUniform("cbH", cbh.cyanRed, cbh.magentaGreen, cbh.yellowBlue)
        shader.setFloatUniform("cbPreserve", if (cb?.preserveLuminosity == true) 1f else 0f)
        val bw = state.blackWhite
        shader.setFloatUniform("useBW", if (bw != null) 1f else 0f)
        val w = bw?.weights ?: BlackWhite.DEFAULT_WEIGHTS
        shader.setFloatUniform("bwA", w[0], w[1], w[2])
        shader.setFloatUniform("bwB", w[3], w[4], w[5])
        val tc = bw?.tintColor ?: Color.WHITE
        shader.setFloatUniform("bwTint", if (bw?.tint == true) 1f else 0f)
        shader.setFloatUniform("bwTintColor", Color.red(tc) / 255f, Color.green(tc) / 255f, Color.blue(tc) / 255f)
        val pf = state.photoFilter?.takeUnless { it.isIdentity }
        val pc = pf?.color ?: Color.WHITE
        shader.setFloatUniform("pfColor", Color.red(pc) / 255f, Color.green(pc) / 255f, Color.blue(pc) / 255f)
        shader.setFloatUniform("pfDensity", pf?.density ?: 0f)
        shader.setFloatUniform("pfPreserve", if (pf?.preserveLuminosity == true) 1f else 0f)
        shader.setFloatUniform("useGM", if (state.gradientMap != null) 1f else 0f)
        val cm = state.channelMixer?.takeUnless { it.isIdentity }
        shader.setFloatUniform("useCM", if (cm != null) 1f else 0f)
        val mix = cm ?: ChannelMixer()
        shader.setFloatUniform("cmR", mix.red[0], mix.red[1], mix.red[2], mix.red[3])
        shader.setFloatUniform("cmG", mix.green[0], mix.green[1], mix.green[2], mix.green[3])
        shader.setFloatUniform("cmB", mix.blue[0], mix.blue[1], mix.blue[2], mix.blue[3])
        shader.setFloatUniform("cmGray", mix.gray[0], mix.gray[1], mix.gray[2], mix.gray[3])
        shader.setFloatUniform("cmMono", if (mix.mono) 1f else 0f)
        val sc = state.selectiveColor?.takeUnless { it.isIdentity }
        shader.setFloatUniform("useSC", if (sc != null) 1f else 0f)
        shader.setFloatUniform("scRel", if (sc?.relative != false) 1f else 0f)
        for (i in 0 until 9) {
            val r = sc?.ranges?.getOrNull(i) ?: listOf(0f, 0f, 0f, 0f)
            shader.setFloatUniform("sc$i", r[0], r[1], r[2], r[3])
        }
        bindLookup(state)
        shader.setFloatUniform("posterize", if (state.posterize >= 2) state.posterize.toFloat() else 0f)
        shader.setFloatUniform("threshold", state.threshold ?: -1f)
    }

    private var lookupKey: Any? = Unit

    /** Color Lookup's table as a picture (a plain one when there's none). */
    private fun bindLookup(state: FxParams) {
        val l = state.colorLookup
        shader.setFloatUniform("useCL", if (l != null) 1f else 0f)
        shader.setFloatUniform("clSize", (l?.size ?: 2).toFloat())
        if (l == lookupKey) return
        lookupKey = l
        val bmp = l?.bitmap ?: IDENTITY_LOOKUP.bitmap
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader("clut", s)
    }

    private fun setUniforms(state: FxParams, unit: Float) {
        bindLut(state)
        setColorUniforms(state)
        shader.setFloatUniform("useLut", if (state.hasTone) 1f else 0f)
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
            if (noGrain) 0f else (state.adjust(Adjust.GRAIN) + state.filter.grain * strength).coerceIn(0f, 1f),
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
        /** False when this device couldn't run the color adjustments (they then change nothing). */
        @Volatile
        var colorSupported = true
            private set

        /** Lookup table that changes nothing. */
        private val IDENTITY_LUT: Bitmap by lazy { Tone.lut(null, null) }

        /** A 3D lookup table that changes nothing (2 per side). */
        private val IDENTITY_LOOKUP: ColorLookup by lazy {
            ColorLookup("None", 2, ByteArray(24) { i -> val idx = i / 3; val k = i % 3; val v = when (k) { 0 -> idx and 1; 1 -> (idx shr 1) and 1; else -> (idx shr 2) and 1 }; (v * 255).toByte() })
        }

        private const val AGSL_BASIC = """
uniform shader image;
uniform shader lut;
uniform float useLut;
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

    // Levels and Curves
    if (useLut > 0.5) {
        c = clamp(c, 0.0, 1.0);
        c = float3(
            lut.eval(float2(c.r * 255.0 + 0.5, 0.5)).r,
            lut.eval(float2(c.g * 255.0 + 0.5, 0.5)).g,
            lut.eval(float2(c.b * 255.0 + 0.5, 0.5)).b
        );
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

        private const val AGSL = """
uniform shader image;
uniform shader lut;
uniform float useLut;
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
uniform shader gmap;
uniform float useHS;
uniform float3 hsMaster;
uniform float3 hsBand0;
uniform float3 hsBand1;
uniform float3 hsBand2;
uniform float3 hsBand3;
uniform float3 hsBand4;
uniform float3 hsBand5;
uniform float3 hsColorize;
uniform float useCB;
uniform float3 cbS;
uniform float3 cbM;
uniform float3 cbH;
uniform float cbPreserve;
uniform float useBW;
uniform float3 bwA;
uniform float3 bwB;
uniform float bwTint;
uniform float3 bwTintColor;
uniform float3 pfColor;
uniform float pfDensity;
uniform float pfPreserve;
uniform float useGM;
uniform float useCM;
uniform float4 cmR;
uniform float4 cmG;
uniform float4 cmB;
uniform float4 cmGray;
uniform float cmMono;
uniform float useSC;
uniform float scRel;
uniform float4 sc0;
uniform float4 sc1;
uniform float4 sc2;
uniform float4 sc3;
uniform float4 sc4;
uniform float4 sc5;
uniform float4 sc6;
uniform float4 sc7;
uniform float4 sc8;
uniform float useCL;
uniform float clSize;
uniform shader clut;
uniform float posterize;
uniform float threshold;

float3 straight(float4 c) {
    return c.a > 0.0 ? c.rgb / c.a : float3(0.0);
}

float luma(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

// Hue 0..1, saturation, lightness
float3 rgb2hsl(float3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float l = (mx + mn) * 0.5;
    float d = mx - mn;
    if (d < 0.00001) return float3(0.0, 0.0, l);
    float s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
    float h = 0.0;
    if (mx == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return float3(h / 6.0, s, l);
}

float hue2rgb(float p, float q, float t) {
    t = fract(t);
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

float3 hsl2rgb(float3 hsl) {
    float s = hsl.y;
    float l = hsl.z;
    if (s <= 0.0) return float3(l);
    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return float3(hue2rgb(p, q, hsl.x + 1.0 / 3.0), hue2rgb(p, q, hsl.x), hue2rgb(p, q, hsl.x - 1.0 / 3.0));
}

// How much a hue (degrees) belongs to the range centered at [center]: all of it within 15°, none past 45°
float bandWeight(float h, float center) {
    float d = abs(mod(h - center + 540.0, 360.0) - 180.0);
    return 1.0 - smoothstep(15.0, 45.0, d);
}

// Photoshop's non-separable blend helpers (Color / Luminosity)
float lum3(float3 c) { return dot(c, float3(0.3, 0.59, 0.11)); }

float3 clipColor(float3 c) {
    float l = lum3(c);
    float n = min(c.r, min(c.g, c.b));
    float x = max(c.r, max(c.g, c.b));
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 0.00001);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 0.00001);
    return c;
}

float3 setLum(float3 c, float l) {
    return clipColor(c + (l - lum3(c)));
}

float3 hueSat(float3 c) {
    float3 hsl = rgb2hsl(c);
    if (hsColorize.x > 0.5) {
        float3 o = hsl2rgb(float3(hsColorize.y, hsColorize.z, hsl.z));
        float L = hsMaster.z;
        return L >= 0.0 ? mix(o, float3(1.0), L) : o * (1.0 + L);
    }
    float hd = hsl.x * 360.0;
    // Grays have no hue: the color ranges leave them alone
    float colorful = clamp(hsl.y * 4.0, 0.0, 1.0);
    float w0 = bandWeight(hd, 0.0) * colorful;
    float w1 = bandWeight(hd, 60.0) * colorful;
    float w2 = bandWeight(hd, 120.0) * colorful;
    float w3 = bandWeight(hd, 180.0) * colorful;
    float w4 = bandWeight(hd, 240.0) * colorful;
    float w5 = bandWeight(hd, 300.0) * colorful;
    float3 sh = hsMaster + hsBand0 * w0 + hsBand1 * w1 + hsBand2 * w2 + hsBand3 * w3 + hsBand4 * w4 + hsBand5 * w5;
    float h = fract(hsl.x + sh.x * 0.5 + 1.0);
    float s = clamp(sh.y >= 0.0 ? hsl.y * (1.0 + sh.y * 1.5) : hsl.y * (1.0 + sh.y), 0.0, 1.0);
    float3 o = hsl2rgb(float3(h, s, hsl.z));
    float L = sh.z;
    return L >= 0.0 ? mix(o, float3(1.0), L) : o * (1.0 + L);
}

float3 colorBalance(float3 c) {
    float l = clamp(luma(c), 0.0, 1.0);
    float ws = 1.0 - smoothstep(0.0, 0.55, l);
    float wh = smoothstep(0.45, 1.0, l);
    float wm = clamp(1.0 - ws - wh, 0.0, 1.0);
    float3 d = (cbS * ws + cbM * wm + cbH * wh) * 0.3;
    // Toward one color is away from its opposite: keep the overall brightness about the same
    float3 o = c + d - (d.r + d.g + d.b) / 3.0 * 0.5;
    if (cbPreserve > 0.5) o = o + (luma(c) - luma(o));
    return clamp(o, 0.0, 1.0);
}

float3 blackWhite(float3 c) {
    float r = c.r;
    float g = c.g;
    float b = c.b;
    float mx = max(r, max(g, b));
    float mn = min(r, min(g, b));
    float md = r + g + b - mx - mn;
    // Each color is white + a two-channel color (yellow, cyan or magenta) + a one-channel color
    float wp = 0.0;
    float wsec = 0.0;
    if (mx == r) {
        wp = bwA.x;
        wsec = (md == g) ? bwA.y : bwB.z;
    } else if (mx == g) {
        wp = bwA.z;
        wsec = (md == r) ? bwA.y : bwB.x;
    } else {
        wp = bwB.y;
        wsec = (md == g) ? bwB.x : bwB.z;
    }
    float gray = clamp(mn + (md - mn) * wsec + (mx - md) * wp, 0.0, 1.0);
    if (bwTint > 0.5) return setLum(bwTintColor, gray);
    return float3(gray);
}

float3 channelMixer(float3 c) {
    if (cmMono > 0.5) {
        float g = dot(cmGray.rgb, c) + cmGray.a;
        return clamp(float3(g), 0.0, 1.0);
    }
    return clamp(float3(dot(cmR.rgb, c) + cmR.a, dot(cmG.rgb, c) + cmG.a, dot(cmB.rgb, c) + cmB.a), 0.0, 1.0);
}

// One Selective Color range's inks (cyan, magenta, yellow, black) on a color
float3 inks(float3 c, float4 a) {
    float3 ink = 1.0 - c;
    float3 d = scRel > 0.5 ? a.xyz * ink : a.xyz;
    float3 o = c - d;
    float mx = max(o.r, max(o.g, o.b));
    float k = 1.0 - mx;
    float dk = scRel > 0.5 ? a.w * (a.w > 0.0 ? 1.0 - k : k) : a.w;
    // More black darkens everything by the same share; less brightens toward white
    o = dk >= 0.0 ? o * (1.0 - dk) : o + (1.0 - o) * (-dk);
    return o;
}

float3 selectiveColor(float3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float md = c.r + c.g + c.b - mx - mn;
    float3 o = c;
    // Each range counts as much as the color is of it (pure colors fully, grays not at all)
    if (mx == c.r) o = mix(o, inks(o, sc0), mx - md);
    if (mn == c.b) o = mix(o, inks(o, sc1), md - mn);
    if (mx == c.g) o = mix(o, inks(o, sc2), mx - md);
    if (mn == c.r) o = mix(o, inks(o, sc3), md - mn);
    if (mx == c.b) o = mix(o, inks(o, sc4), mx - md);
    if (mn == c.g) o = mix(o, inks(o, sc5), md - mn);
    float ww = clamp((mn - 0.5) * 2.0, 0.0, 1.0);
    float wk = clamp((0.5 - mx) * 2.0, 0.0, 1.0);
    float wn = clamp(1.0 - abs(mx - 0.5) - abs(mn - 0.5), 0.0, 1.0);
    o = mix(o, inks(o, sc6), ww);
    o = mix(o, inks(o, sc7), wn);
    o = mix(o, inks(o, sc8), wk);
    return clamp(o, 0.0, 1.0);
}

// A color looked up in the 3D table (slices of blue side by side), smoothly between its steps
float3 lookup(float3 c) {
    float n = clSize - 1.0;
    float3 s = clamp(c, 0.0, 1.0) * n;
    float b0 = floor(s.b);
    float b1 = min(b0 + 1.0, n);
    float t = s.b - b0;
    float3 lo = float3(clut.eval(float2(s.r + b0 * clSize + 0.5, s.g + 0.5)).rgb);
    float3 hi = float3(clut.eval(float2(s.r + b1 * clSize + 0.5, s.g + 0.5)).rgb);
    return mix(lo, hi, t);
}

float3 photoFilter(float3 c) {
    float3 o = mix(c, c * pfColor * 1.6, pfDensity);
    if (pfPreserve > 0.5) o = o + (luma(c) - luma(o));
    return clamp(o, 0.0, 1.0);
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

    // Levels and Curves
    if (useLut > 0.5) {
        c = clamp(c, 0.0, 1.0);
        c = float3(
            lut.eval(float2(c.r * 255.0 + 0.5, 0.5)).r,
            lut.eval(float2(c.g * 255.0 + 0.5, 0.5)).g,
            lut.eval(float2(c.b * 255.0 + 0.5, 0.5)).b
        );
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

    if (useHS > 0.5) c = clamp(hueSat(c), 0.0, 1.0);
    if (useCB > 0.5) c = colorBalance(c);
    if (useCM > 0.5) c = channelMixer(c);
    if (useSC > 0.5) c = selectiveColor(c);
    if (pfDensity > 0.0) c = photoFilter(c);
    if (useBW > 0.5) c = blackWhite(c);
    if (useGM > 0.5) c = float3(gmap.eval(float2(clamp(luma(c), 0.0, 1.0) * 255.0 + 0.5, 0.5)).rgb);
    if (useCL > 0.5) c = lookup(c);
    if (posterize >= 2.0) c = clamp(floor(clamp(c, 0.0, 1.0) * posterize) / (posterize - 1.0), 0.0, 1.0);
    if (threshold >= 0.0) c = float3(lum3(c) >= threshold ? 1.0 : 0.0);

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
