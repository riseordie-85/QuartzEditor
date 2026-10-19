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

/**
 * Channel Mixer: each color going out as amounts of the red, green and blue coming in (1 = 100%,
 * −2 … 2 like Photoshop) plus a constant (−1 … 1). [mono] makes one gray, from [gray].
 */
data class ChannelMixer(
    val red: List<Float> = listOf(1f, 0f, 0f, 0f),
    val green: List<Float> = listOf(0f, 1f, 0f, 0f),
    val blue: List<Float> = listOf(0f, 0f, 1f, 0f),
    val gray: List<Float> = listOf(0.4f, 0.4f, 0.2f, 0f),
    val mono: Boolean = false,
) {
    val isIdentity: Boolean get() = !mono && red == IDENTITY[0] && green == IDENTITY[1] && blue == IDENTITY[2]

    /** Output channel [i]: 0 red, 1 green, 2 blue, 3 gray (Monochrome). */
    operator fun get(i: Int): List<Float> = when (i) {
        0 -> red
        1 -> green
        2 -> blue
        else -> gray
    }

    fun with(i: Int, v: List<Float>): ChannelMixer = when (i) {
        0 -> copy(red = v)
        1 -> copy(green = v)
        2 -> copy(blue = v)
        else -> copy(gray = v)
    }

    fun toJson(): JSONObject = JSONObject().put("mono", mono).also { o ->
        listOf("red" to red, "green" to green, "blue" to blue, "gray" to gray).forEach { (k, v) ->
            o.put(k, JSONArray().also { a -> v.forEach { a.put(it.toDouble()) } })
        }
    }

    companion object {
        val IDENTITY = listOf(listOf(1f, 0f, 0f, 0f), listOf(0f, 1f, 0f, 0f), listOf(0f, 0f, 1f, 0f))
        val NAMES = listOf("Red", "Green", "Blue", "Gray")

        /** Photoshop's ready-made mixes (monochrome ones make the gray). */
        val PRESETS: List<Pair<String, ChannelMixer>> = listOf(
            "Default" to ChannelMixer(),
            "Black & white infrared (RGB)" to ChannelMixer(mono = true, gray = listOf(-0.7f, 2f, -0.3f, 0f)),
            "Black & white with blue filter" to ChannelMixer(mono = true, gray = listOf(0f, 0f, 1f, 0f)),
            "Black & white with green filter" to ChannelMixer(mono = true, gray = listOf(0f, 1f, 0f, 0f)),
            "Black & white with orange filter" to ChannelMixer(mono = true, gray = listOf(0.5f, 0.5f, 0f, 0f)),
            "Black & white with red filter" to ChannelMixer(mono = true, gray = listOf(1f, 0f, 0f, 0f)),
            "Black & white with yellow filter" to ChannelMixer(mono = true, gray = listOf(0.34f, 0.66f, 0f, 0f)),
            "Swap red and blue" to ChannelMixer(red = listOf(0f, 0f, 1f, 0f), blue = listOf(1f, 0f, 0f, 0f)),
        )

        fun fromJson(o: JSONObject?): ChannelMixer? = o?.let {
            fun row(k: String, d: List<Float>) = it.optJSONArray(k)?.let { a -> List(4) { i -> a.optDouble(i, d[i].toDouble()).toFloat() } } ?: d
            val def = ChannelMixer()
            ChannelMixer(row("red", def.red), row("green", def.green), row("blue", def.blue), row("gray", def.gray), it.optBoolean("mono"))
        }
    }
}

/** The colors Selective Color can change. */
enum class SelectiveRange(val label: String, val color: Int) {
    REDS("Reds", 0xFFE53935.toInt()),
    YELLOWS("Yellows", 0xFFFDD835.toInt()),
    GREENS("Greens", 0xFF43A047.toInt()),
    CYANS("Cyans", 0xFF00ACC1.toInt()),
    BLUES("Blues", 0xFF1E88E5.toInt()),
    MAGENTAS("Magentas", 0xFFD81B60.toInt()),
    WHITES("Whites", 0xFFFFFFFF.toInt()),
    NEUTRALS("Neutrals", 0xFF888888.toInt()),
    BLACKS("Blacks", 0xFF000000.toInt()),
}

/**
 * Selective Color: for each range of colors ([SelectiveRange]), more or less cyan, magenta,
 * yellow and black ink (each −1 … 1 = ±100%). [relative] changes ink by a share of what's there;
 * otherwise by an absolute amount.
 */
data class SelectiveColor(
    val ranges: List<List<Float>> = List(9) { listOf(0f, 0f, 0f, 0f) },
    val relative: Boolean = true,
) {
    val isIdentity: Boolean get() = ranges.all { r -> r.all { it == 0f } }

    operator fun get(r: SelectiveRange): List<Float> = ranges.getOrElse(r.ordinal) { listOf(0f, 0f, 0f, 0f) }
    fun with(r: SelectiveRange, v: List<Float>): SelectiveColor = copy(ranges = ranges.mapIndexed { i, x -> if (i == r.ordinal) v else x })

    fun toJson(): JSONObject = JSONObject().put("relative", relative)
        .put("ranges", JSONArray().also { a -> ranges.forEach { r -> a.put(JSONArray().also { b -> r.forEach { b.put(it.toDouble()) } }) } })

    companion object {
        val INKS = listOf("Cyan", "Magenta", "Yellow", "Black")

        fun fromJson(o: JSONObject?): SelectiveColor? = o?.let {
            val a = it.optJSONArray("ranges")
            SelectiveColor(List(9) { i -> List(4) { k -> a?.optJSONArray(i)?.optDouble(k, 0.0)?.toFloat() ?: 0f } }, it.optBoolean("relative", true))
        }
    }
}

/**
 * Color Lookup: a 3D lookup table ([size]³ colors, red changing fastest, then green, then blue;
 * 8 bits each) that every color is looked up in. Ready-made looks keep only their [preset] key.
 */
class ColorLookup(val name: String, val size: Int, val rgb: ByteArray, val preset: String? = null) {
    private val hash = 31 * (31 * name.hashCode() + size) + rgb.contentHashCode()
    override fun equals(other: Any?): Boolean = other is ColorLookup && other.hash == hash && other.size == size && other.name == name && other.rgb.contentEquals(rgb)
    override fun hashCode(): Int = hash

    /** As a picture for the GPU: [size] slices of [size]×[size] side by side (one per blue level). */
    val bitmap: Bitmap by lazy {
        val n = size
        val px = IntArray(n * n * n)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val i = ((b * n + g) * n + r) * 3
            px[g * n * n + b * n + r] = (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
        }
        Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888)
    }

    fun toJson(): JSONObject = JSONObject().put("name", name).also { o ->
        if (preset != null) o.put("preset", preset)
        else o.put("size", size).put("data", android.util.Base64.encodeToString(rgb, android.util.Base64.NO_WRAP))
    }

    companion object {
        private const val PRESET_SIZE = 25

        /** Ready-made looks: (key, name, what it does to a color in 0..1). */
        private val LOOKS: List<Triple<String, String, (FloatArray) -> Unit>> = listOf(
            Triple("warmfilm", "Warm film", { c -> curveAll(c, 0.04f, 0.97f); tint(c, 0.06f, 0.02f, -0.05f); desat(c, 0.12f) }),
            Triple("tealorange", "Teal & orange", { c -> split(c, floatArrayOf(-0.06f, 0.02f, 0.07f), floatArrayOf(0.07f, 0.02f, -0.06f)); sCurve(c, 0.15f) }),
            Triple("coolmorning", "Cool morning", { c -> tint(c, -0.04f, 0.0f, 0.06f); curveAll(c, 0.03f, 1f); desat(c, 0.08f) }),
            Triple("faded", "Faded", { c -> curveAll(c, 0.12f, 0.93f); desat(c, 0.2f) }),
            Triple("bleach", "Bleach bypass", { c -> desat(c, 0.45f); sCurve(c, 0.35f) }),
            Triple("vintage", "Vintage", { c -> split(c, floatArrayOf(0.03f, 0.0f, 0.08f), floatArrayOf(0.08f, 0.05f, -0.06f)); curveAll(c, 0.08f, 0.95f); desat(c, 0.25f) }),
            Triple("night", "Night", { c -> desat(c, 0.35f); tint(c, -0.08f, -0.02f, 0.1f); for (i in 0..2) c[i] *= 0.75f }),
            Triple("crisp", "Crisp", { c -> sCurve(c, 0.25f); desat(c, -0.15f) }),
            Triple("mono", "Rich black & white", { c -> val l = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]; c[0] = l; c[1] = l; c[2] = l; sCurve(c, 0.3f) }),
            Triple("sepia", "Sepia print", { c -> val l = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]; c[0] = l * 1.07f + 0.03f; c[1] = l * 0.95f + 0.02f; c[2] = l * 0.78f; curveAll(c, 0.05f, 0.96f) }),
        )

        val PRESETS: List<ColorLookup> by lazy { LOOKS.map { (k, n, f) -> build(n, k, f) } }

        fun preset(key: String): ColorLookup? = PRESETS.firstOrNull { it.preset == key }

        private fun build(name: String, key: String, f: (FloatArray) -> Unit): ColorLookup {
            val n = PRESET_SIZE
            val out = ByteArray(n * n * n * 3)
            val c = FloatArray(3)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                c[0] = r / (n - 1f); c[1] = g / (n - 1f); c[2] = b / (n - 1f)
                f(c)
                val i = ((b * n + g) * n + r) * 3
                for (k in 0..2) out[i + k] = (c[k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
            return ColorLookup(name, n, out, key)
        }

        private fun curveAll(c: FloatArray, black: Float, white: Float) {
            for (i in 0..2) c[i] = black + c[i] * (white - black)
        }

        private fun tint(c: FloatArray, r: Float, g: Float, b: Float) {
            c[0] += r; c[1] += g; c[2] += b
        }

        private fun desat(c: FloatArray, amount: Float) {
            val l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
            for (i in 0..2) c[i] = c[i] + (l - c[i]) * amount
        }

        private fun sCurve(c: FloatArray, amount: Float) {
            for (i in 0..2) {
                val v = c[i].coerceIn(0f, 1f)
                val s = v * v * (3f - 2f * v)
                c[i] = v + (s - v) * amount * 2f
            }
        }

        /** Shadows toward [shadow], highlights toward [highlight] (amounts added). */
        private fun split(c: FloatArray, shadow: FloatArray, highlight: FloatArray) {
            val l = (0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]).coerceIn(0f, 1f)
            val ws = (1f - l) * (1f - l)
            val wh = l * l
            for (i in 0..2) c[i] += shadow[i] * ws + highlight[i] * wh
        }

        /**
         * A lookup table file: .cube (Adobe/Resolve), or .3dl (Autodesk/Lustre). Null when it isn't
         * one we can read. Tables bigger than 64 are thinned to 64.
         */
        fun parse(text: String, name: String): ColorLookup? = parseCube(text, name) ?: parse3dl(text, name)

        private fun parseCube(text: String, name: String): ColorLookup? {
            var n = 0
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "LUT_3D_SIZE" -> n = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    "LUT_1D_SIZE" -> return null
                    "DOMAIN_MIN" -> min = FloatArray(3) { parts.getOrNull(it + 1)?.toFloatOrNull() ?: 0f }
                    "DOMAIN_MAX" -> max = FloatArray(3) { parts.getOrNull(it + 1)?.toFloatOrNull() ?: 1f }
                    "TITLE", "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> Unit
                    else -> {
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add(r); values.add(g); values.add(b)
                            }
                        }
                    }
                }
            }
            if (n < 2 || n > 256 || values.size < n * n * n * 3) return null
            val out = ByteArray(n * n * n * 3)
            for (i in 0 until n * n * n) for (k in 0..2) {
                val v = (values[i * 3 + k] - min[k]) / (max[k] - min[k]).let { if (it == 0f) 1f else it }
                out[i * 3 + k] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
            return thin(ColorLookup(name, n, out))
        }

        private fun parse3dl(text: String, name: String): ColorLookup? {
            val rows = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                .map { l -> l.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() } }.filter { it.isNotEmpty() }.toList()
            if (rows.isEmpty()) return null
            // The first line lists the input steps (as many as the table's size)
            val mesh = rows[0]
            val n = mesh.size
            val data = rows.drop(1).filter { it.size >= 3 }
            if (n < 2 || n > 256 || data.size < n * n * n) return null
            val top = data.maxOf { maxOf(it[0], it[1], it[2]) }
            var full = 1
            while (full < top) full = full * 2 + 1
            val out = ByteArray(n * n * n * 3)
            // Blue changes fastest in .3dl; ours has red fastest
            for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
                val row = data[(r * n + g) * n + b]
                val i = ((b * n + g) * n + r) * 3
                for (k in 0..2) out[i + k] = (row[k] * 255f / full + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            return thin(ColorLookup(name, n, out))
        }

        /** Tables over 64 per side (rare) thinned to 64: plenty with the smooth lookups in between. */
        private fun thin(l: ColorLookup): ColorLookup {
            if (l.size <= 64) return l
            val n = 64
            val s = l.size
            val out = ByteArray(n * n * n * 3)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                fun at(v: Int) = (v * (s - 1f) / (n - 1f)).roundToIntSafe().coerceIn(0, s - 1)
                val src = ((at(b) * s + at(g)) * s + at(r)) * 3
                val dst = ((b * n + g) * n + r) * 3
                for (k in 0..2) out[dst + k] = l.rgb[src + k]
            }
            return ColorLookup(l.name, n, out)
        }

        private fun Float.roundToIntSafe(): Int = (this + 0.5f).toInt()

        fun fromJson(o: JSONObject?): ColorLookup? = o?.let {
            it.optString("preset").takeIf { k -> k.isNotEmpty() }?.let { k -> return preset(k) }
            val n = it.optInt("size")
            val data = runCatching { android.util.Base64.decode(it.optString("data"), android.util.Base64.NO_WRAP) }.getOrNull() ?: return null
            if (n < 2 || data.size < n * n * n * 3) return null
            ColorLookup(it.optString("name", "Look"), n, data)
        }
    }
}
