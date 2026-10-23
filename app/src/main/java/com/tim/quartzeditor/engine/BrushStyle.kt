package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** What tilting the pen does to a brush. */
enum class TiltMode(val label: String) {
    NONE("Nothing"),
    /** The dab stretches along the way the pen leans (a marker or a pen on its side). */
    STRETCH("Stretch"),
    /** Bigger and lighter, like shading with the side of a pencil. */
    SHADE("Shade");

    companion object {
        fun byName(n: String?): TiltMode = entries.firstOrNull { it.name == n } ?: STRETCH
    }
}

/** A paper texture the stroke shows through (it stays put on the canvas, like real paper's tooth). */
enum class Grain(val label: String) {
    PAPER("Paper"), CANVAS("Canvas"), CHARCOAL("Charcoal"), NOISE("Noise");

    companion object {
        fun byName(n: String?): Grain? = entries.firstOrNull { it.name == n }
    }
}

/**
 * How a brush puts its dabs down, beyond its tip, size, softness and opacity: how far apart they
 * are, how much each one adds ([flow]), how they vary (size, angle, scatter), which way they point
 * (an [angle], squashed by [roundness], or turned along the stroke), the paper [grain], and what
 * tilt and pressure do.
 */
data class BrushStyle(
    /** Distance between dabs as a fraction of the size (null = the tip's own). */
    val spacing: Float? = null,
    /** How much each dab adds, 0..1 (low builds up within a stroke, never past its opacity). */
    val flow: Float = 1f,
    /** Each dab up to this much smaller (0..1, at random). */
    val sizeJitter: Float = 0f,
    /** Each dab turned at random, up to this fraction of a full turn (0..1). */
    val angleJitter: Float = 0f,
    /** Each dab moved at random, up to this many times the size (0..2). */
    val scatter: Float = 0f,
    /** The tip's angle (degrees). */
    val angle: Float = 0f,
    /** The tip's height to width (1 = as drawn, smaller = flatter, like a chisel marker). */
    val roundness: Float = 1f,
    /** The tip turns with the stroke's direction (on top of [angle]). */
    val followStroke: Boolean = false,
    val grain: Grain? = null,
    /** The grain's size (1 = as made, about 2 px across its finest detail). */
    val grainScale: Float = 1f,
    /** How much the grain shows (0..1). */
    val grainDepth: Float = 0.6f,
    val tiltMode: TiltMode = TiltMode.STRETCH,
    /** A pen's pressure changes the flow. */
    val pressureFlow: Boolean = false,
) {
    /** Nothing beyond a plain brush (dabs drawn the simple way). */
    val plain: Boolean get() = sizeJitter == 0f && angleJitter == 0f && scatter == 0f && angle == 0f && roundness >= 0.999f && !followStroke

    fun toJson(): JSONObject = JSONObject().apply {
        spacing?.let { put("spacing", it.toDouble()) }
        put("flow", flow.toDouble())
        put("sizeJitter", sizeJitter.toDouble())
        put("angleJitter", angleJitter.toDouble())
        put("scatter", scatter.toDouble())
        put("angle", angle.toDouble())
        put("roundness", roundness.toDouble())
        put("follow", followStroke)
        grain?.let { put("grain", it.name) }
        put("grainScale", grainScale.toDouble())
        put("grainDepth", grainDepth.toDouble())
        put("tilt", tiltMode.name)
        put("pFlow", pressureFlow)
    }

    companion object {
        val PLAIN = BrushStyle()

        fun fromJson(o: JSONObject?): BrushStyle {
            if (o == null) return PLAIN
            return BrushStyle(
                spacing = if (o.has("spacing")) o.optDouble("spacing").toFloat() else null,
                flow = o.optDouble("flow", 1.0).toFloat(),
                sizeJitter = o.optDouble("sizeJitter", 0.0).toFloat(),
                angleJitter = o.optDouble("angleJitter", 0.0).toFloat(),
                scatter = o.optDouble("scatter", 0.0).toFloat(),
                angle = o.optDouble("angle", 0.0).toFloat(),
                roundness = o.optDouble("roundness", 1.0).toFloat(),
                followStroke = o.optBoolean("follow", false),
                grain = Grain.byName(o.optString("grain", "")),
                grainScale = o.optDouble("grainScale", 1.0).toFloat(),
                grainDepth = o.optDouble("grainDepth", 0.6).toFloat(),
                tiltMode = TiltMode.byName(o.optString("tilt", "")),
                pressureFlow = o.optBoolean("pFlow", false),
            )
        }
    }
}

/** The grain textures: seamless tiles, made once, kept at each depth asked for. */
object Grains {
    const val TILE = 256
    private val cache = HashMap<Pair<Grain, Int>, Bitmap>()

    /**
     * A paint that multiplies what's under it (DST_IN) by [grain] at [depth] (0..1), [scale]d, in
     * canvas pixels: where the canvas's (0, 0) is drawn at ([ox], [oy]) in the canvas being drawn on.
     */
    fun paint(grain: Grain, depth: Float, scale: Float, ox: Float, oy: Float): Paint {
        val bmp = tile(grain, (depth.coerceIn(0f, 1f) * 20).roundToInt())
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(ox, oy)
        })
        return Paint(Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
    }

    /** The tile at depth [d20] / 20: how much of a stroke stays at each pixel. */
    @Synchronized
    fun tile(grain: Grain, d20: Int): Bitmap {
        val key = grain to d20
        cache[key]?.let { if (!it.isRecycled) return it }
        val depth = d20 / 20f
        val g = values(grain)
        val out = ByteArray(TILE * TILE) { i -> ((1f - depth + depth * g[i]).coerceIn(0f, 1f) * 255f).roundToInt().toByte() }
        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out))
        cache[key] = bmp
        return bmp
    }

    private val made = HashMap<Grain, FloatArray>()

    /** The texture itself, 0 (a dip the stroke skips) .. 1 (a bump it catches). */
    @Synchronized
    fun values(grain: Grain): FloatArray = made.getOrPut(grain) {
        val n = TILE
        FloatArray(n * n) { i ->
            val x = (i % n).toFloat()
            val y = (i / n).toFloat()
            when (grain) {
                // Fine tooth with a few larger dips (cell sizes divide the tile, so it's seamless)
                Grain.PAPER -> contrast(noise(x / 2f, y / 2f, n / 2f) * 0.55f + noise(x / 8f, y / 8f, n / 8f) * 0.3f + noise(x / 16f, y / 16f, n / 16f) * 0.15f, 0.5f, 2.4f)
                // A weave: threads over and under, a little uneven
                Grain.CANVAS -> {
                    val wx = (sin(x / n * 2 * Math.PI * 32) * 0.5 + 0.5).toFloat()
                    val wy = (sin(y / n * 2 * Math.PI * 32) * 0.5 + 0.5).toFloat()
                    contrast(maxOf(wx, wy) * 0.75f + noise(x / 4f, y / 4f, n / 4f) * 0.25f, 0.55f, 2f)
                }
                // Streaky, fibrous
                Grain.CHARCOAL -> contrast(noise(x / 16f, y / 2f, n / 16f, n / 2f) * 0.6f + noise(x / 4f, y / 4f, n / 4f) * 0.4f, 0.5f, 3f)
                Grain.NOISE -> contrast(noise(x, y, n.toFloat()), 0.5f, 2f)
            }
        }
    }

    private fun contrast(v: Float, mid: Float, k: Float): Float = ((v - mid) * k + 0.5f).coerceIn(0f, 1f)

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** Value noise that repeats every [px] × [py] cells (so the tile is seamless). */
    private fun noise(x: Float, y: Float, px: Float, py: Float = px): Float {
        val periodX = px.roundToInt().coerceAtLeast(1)
        val periodY = py.roundToInt().coerceAtLeast(1)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        fun h(a: Int, b: Int) = hash(Math.floorMod(a, periodX), Math.floorMod(b, periodY))
        val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * sx
        val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }
}
