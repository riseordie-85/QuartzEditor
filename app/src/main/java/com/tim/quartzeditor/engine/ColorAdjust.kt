package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject

// The color adjustments that work like Photoshop's: Hue/Saturation (with color ranges), Color
// Balance, Black & White, Photo Filter and Gradient Map. They're applied by the adjustments shader
// along with everything else in [FxParams].

/** One Hue/Saturation range's changes: [hue] -1..1 (= ±180°), [sat] and [light] -1..1 (= ±100). */
data class HueShift(val hue: Float = 0f, val sat: Float = 0f, val light: Float = 0f) {
    val isIdentity: Boolean get() = hue == 0f && sat == 0f && light == 0f
    fun toJson(): JSONArray = JSONArray().put(hue.toDouble()).put(sat.toDouble()).put(light.toDouble())

    companion object {
        fun fromJson(a: JSONArray?): HueShift = if (a == null || a.length() < 3) HueShift() else
            HueShift(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())
    }
}

/** What Hue/Saturation changes: everything (Master), or one range of colors. */
enum class HueBand(val label: String, val color: Int) {
    MASTER("Master", Color.GRAY),
    REDS("Reds", 0xFFE53935.toInt()),
    YELLOWS("Yellows", 0xFFFDD835.toInt()),
    GREENS("Greens", 0xFF43A047.toInt()),
    CYANS("Cyans", 0xFF00ACC1.toInt()),
    BLUES("Blues", 0xFF1E88E5.toInt()),
    MAGENTAS("Magentas", 0xFFD81B60.toInt()),
}

/**
 * Hue/Saturation: [master] changes every color, [bands] (Reds, Yellows, Greens, Cyans, Blues,
 * Magentas) only theirs. [colorize] turns the picture one color instead ([colorHue] 0..360,
 * [colorSat] 0..1), with [master]'s lightness.
 */
data class HueSat(
    val master: HueShift = HueShift(),
    val bands: List<HueShift> = List(6) { HueShift() },
    val colorize: Boolean = false,
    val colorHue: Float = 30f,
    val colorSat: Float = 0.25f,
) {
    operator fun get(b: HueBand): HueShift = if (b == HueBand.MASTER) master else bands.getOrElse(b.ordinal - 1) { HueShift() }
    fun with(b: HueBand, s: HueShift): HueSat =
        if (b == HueBand.MASTER) copy(master = s) else copy(bands = bands.mapIndexed { i, x -> if (i == b.ordinal - 1) s else x })

    val isIdentity: Boolean get() = !colorize && master.isIdentity && bands.all { it.isIdentity }

    fun toJson(): JSONObject = JSONObject().put("master", master.toJson())
        .put("bands", JSONArray().also { a -> bands.forEach { a.put(it.toJson()) } })
        .put("colorize", colorize).put("colorHue", colorHue.toDouble()).put("colorSat", colorSat.toDouble())

    companion object {
        fun fromJson(o: JSONObject?): HueSat? = o?.let {
            val b = it.optJSONArray("bands")
            HueSat(
                HueShift.fromJson(it.optJSONArray("master")),
                List(6) { i -> HueShift.fromJson(b?.optJSONArray(i)) },
                it.optBoolean("colorize"), it.optDouble("colorHue", 30.0).toFloat(), it.optDouble("colorSat", 0.25).toFloat(),
            )
        }
    }
}

/** One tonal range in Color Balance: Cyan–Red, Magenta–Green, Yellow–Blue, each -1..1 (= ±100). */
data class ColorShift(val cyanRed: Float = 0f, val magentaGreen: Float = 0f, val yellowBlue: Float = 0f) {
    val isIdentity: Boolean get() = cyanRed == 0f && magentaGreen == 0f && yellowBlue == 0f
    fun toJson(): JSONArray = JSONArray().put(cyanRed.toDouble()).put(magentaGreen.toDouble()).put(yellowBlue.toDouble())

    companion object {
        fun fromJson(a: JSONArray?): ColorShift = if (a == null || a.length() < 3) ColorShift() else
            ColorShift(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())
    }
}

enum class ToneRange(val label: String) { SHADOWS("Shadows"), MIDTONES("Midtones"), HIGHLIGHTS("Highlights") }

data class ColorBalance(
    val shadows: ColorShift = ColorShift(),
    val midtones: ColorShift = ColorShift(),
    val highlights: ColorShift = ColorShift(),
    val preserveLuminosity: Boolean = true,
) {
    operator fun get(r: ToneRange): ColorShift = when (r) {
        ToneRange.SHADOWS -> shadows
        ToneRange.MIDTONES -> midtones
        ToneRange.HIGHLIGHTS -> highlights
    }

    fun with(r: ToneRange, s: ColorShift): ColorBalance = when (r) {
        ToneRange.SHADOWS -> copy(shadows = s)
        ToneRange.MIDTONES -> copy(midtones = s)
        ToneRange.HIGHLIGHTS -> copy(highlights = s)
    }

    val isIdentity: Boolean get() = shadows.isIdentity && midtones.isIdentity && highlights.isIdentity

    fun toJson(): JSONObject = JSONObject().put("shadows", shadows.toJson()).put("midtones", midtones.toJson())
        .put("highlights", highlights.toJson()).put("preserve", preserveLuminosity)

    companion object {
        fun fromJson(o: JSONObject?): ColorBalance? = o?.let {
            ColorBalance(
                ColorShift.fromJson(it.optJSONArray("shadows")), ColorShift.fromJson(it.optJSONArray("midtones")),
                ColorShift.fromJson(it.optJSONArray("highlights")), it.optBoolean("preserve", true),
            )
        }
    }
}

/**
 * Black & White: how bright each kind of color becomes ([weights] for Reds, Yellows, Greens,
 * Cyans, Blues, Magentas; 1 = 100%, -2 … 3 like Photoshop), optionally tinted with [tintColor].
 */
data class BlackWhite(
    val weights: List<Float> = DEFAULT_WEIGHTS,
    val tint: Boolean = false,
    val tintColor: Int = 0xFFE1D3B3.toInt(),
) {
    fun toJson(): JSONObject = JSONObject().put("weights", JSONArray().also { a -> weights.forEach { a.put(it.toDouble()) } })
        .put("tint", tint).put("tintColor", tintColor)

    companion object {
        /** Photoshop's default mix. */
        val DEFAULT_WEIGHTS = listOf(0.4f, 0.6f, 0.4f, 0.6f, 0.2f, 0.8f)
        val NAMES = listOf("Reds", "Yellows", "Greens", "Cyans", "Blues", "Magentas")

        /** Photoshop's ready-made mixes. */
        val PRESETS: List<Pair<String, List<Float>>> = listOf(
            "Default" to DEFAULT_WEIGHTS,
            "High contrast red" to listOf(1.2f, 1.1f, -0.1f, -0.5f, -0.5f, 1.2f),
            "High contrast blue" to listOf(-0.5f, -0.5f, -0.1f, 1.1f, 1.2f, 0.8f),
            "Infrared" to listOf(-0.4f, 2.35f, 1.44f, -0.68f, -0.03f, -1.07f),
            "Darker" to listOf(0.2f, 0.3f, 0.2f, 0.3f, 0.1f, 0.4f),
            "Lighter" to listOf(0.6f, 0.8f, 0.6f, 0.8f, 0.4f, 0.9f),
            "Neutral" to listOf(0.2f, 0.4f, 0.3f, 0.4f, 0.2f, 0.4f),
            "Red filter" to listOf(1.2f, 1.1f, -0.1f, -0.5f, -0.5f, 1.2f),
            "Green filter" to listOf(0.4f, 1.2f, 1.2f, 0.4f, 0.2f, 0.4f),
            "Yellow filter" to listOf(1.2f, 1.1f, 0.4f, 0.6f, 0.1f, 0.5f),
        )

        fun fromJson(o: JSONObject?): BlackWhite? = o?.let {
            val w = it.optJSONArray("weights")
            BlackWhite(
                List(6) { i -> w?.optDouble(i, DEFAULT_WEIGHTS[i].toDouble())?.toFloat() ?: DEFAULT_WEIGHTS[i] },
                it.optBoolean("tint"), it.optInt("tintColor", 0xFFE1D3B3.toInt()),
            )
        }
    }
}

/** Photo Filter: the picture seen through colored glass ([density] 0..1). */
data class PhotoFilter(
    val color: Int = 0xFFEC8A00.toInt(),
    val density: Float = 0.25f,
    val preserveLuminosity: Boolean = true,
) {
    val isIdentity: Boolean get() = density <= 0f

    fun toJson(): JSONObject = JSONObject().put("color", color).put("density", density.toDouble()).put("preserve", preserveLuminosity)

    companion object {
        /** Photoshop's filters. */
        val PRESETS: List<Pair<String, Int>> = listOf(
            "Warming (85)" to 0xFFEC8A00.toInt(),
            "Warming (LBA)" to 0xFFFA9600.toInt(),
            "Warming (81)" to 0xFFEBB113.toInt(),
            "Cooling (80)" to 0xFF006DFF.toInt(),
            "Cooling (LBB)" to 0xFF005DFF.toInt(),
            "Cooling (82)" to 0xFF00B5FF.toInt(),
            "Red" to 0xFFEA1A1A.toInt(),
            "Orange" to 0xFFF28F1A.toInt(),
            "Yellow" to 0xFFF9E31C.toInt(),
            "Green" to 0xFF19C919.toInt(),
            "Cyan" to 0xFF1DCBEA.toInt(),
            "Blue" to 0xFF1D35EA.toInt(),
            "Violet" to 0xFF9B1DEA.toInt(),
            "Magenta" to 0xFFE318E3.toInt(),
            "Sepia" to 0xFFAC7A33.toInt(),
            "Deep red" to 0xFFFF0000.toInt(),
            "Deep blue" to 0xFF0022CD.toInt(),
            "Deep emerald" to 0xFF008C00.toInt(),
            "Deep yellow" to 0xFFFFD500.toInt(),
            "Underwater" to 0xFF00C1B1.toInt(),
        )

        fun fromJson(o: JSONObject?): PhotoFilter? = o?.let {
            PhotoFilter(it.optInt("color", 0xFFEC8A00.toInt()), it.optDouble("density", 0.25).toFloat(), it.optBoolean("preserve", true))
        }
    }
}

/** Gradient Map: each brightness becomes a color along a gradient (dark → [colors] first). */
data class GradientMap(
    val colors: List<Int> = listOf(Color.BLACK, Color.WHITE),
    val positions: List<Float>? = null,
    val reverse: Boolean = false,
) {
    /** The gradient as a 256 × 1 lookup table. */
    fun lut(): Bitmap {
        val bmp = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        val cols = (if (reverse) colors.reversed() else colors).map { it or 0xFF000000.toInt() }.toIntArray()
        val pos = positions?.takeIf { it.size == colors.size && cols.size >= 2 }?.let { p -> (if (reverse) p.reversed().map { 1f - it } else p).toFloatArray() }
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 256f, 0f, if (cols.size >= 2) cols else intArrayOf(cols.firstOrNull() ?: Color.BLACK, cols.firstOrNull() ?: Color.WHITE), pos, Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 256f, 1f, p)
        return bmp
    }

    fun toJson(): JSONObject = JSONObject()
        .put("colors", JSONArray().also { a -> colors.forEach { a.put(it.toLong() and 0xFFFFFFFFL) } })
        .put("reverse", reverse)
        .also { o -> positions?.let { ps -> o.put("positions", JSONArray().also { a -> ps.forEach { a.put(it.toDouble()) } }) } }

    companion object {
        fun fromJson(o: JSONObject?): GradientMap? = o?.let {
            val c = it.optJSONArray("colors")?.let { a -> List(a.length()) { i -> a.optLong(i).toInt() } }?.takeIf { l -> l.size >= 2 } ?: listOf(Color.BLACK, Color.WHITE)
            val p = it.optJSONArray("positions")?.let { a -> List(a.length()) { i -> a.optDouble(i).toFloat() } }?.takeIf { l -> l.size == c.size }
            GradientMap(c, p, it.optBoolean("reverse"))
        }
    }
}
