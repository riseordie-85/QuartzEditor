package com.tim.quartzeditor.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Color swatches: ready-made sets and your own (saved colors with names, in groups), and reading
// Adobe swatch files (.aco from Photoshop's Swatches panel, .ase from Illustrator, InDesign and
// Adobe Color). Plain Kotlin, no Android.

/** One saved color. */
data class Swatch(val color: Int, val name: String = "")

/** A named set of swatches; [mine]: yours (you can add to it and remove from it). */
data class SwatchGroup(val name: String, val swatches: List<Swatch>, val mine: Boolean = false)

object Swatches {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hex(name: String, vararg hexes: String) = SwatchGroup(name, hexes.map { h ->
        val (n, c) = h.split('=').let { if (it.size == 2) it[0] to it[1] else "" to it[0] }
        Swatch((0xFF000000.toInt()) or c.toInt(16), n)
    })

    /** The sets that come with the app. */
    val BUILT_IN: List<SwatchGroup> = listOf(
        hex(
            "Basics",
            "Black=000000", "White=FFFFFF", "Red=FF0000", "Green=00FF00", "Blue=0000FF", "Cyan=00FFFF", "Magenta=FF00FF", "Yellow=FFFF00",
            "Orange=FF8000", "Lime=80FF00", "Spring green=00FF80", "Azure=0080FF", "Violet=8000FF", "Rose=FF0080",
        ),
        hex(
            "Grays",
            "White=FFFFFF", "Gray 10%=E6E6E6", "Gray 20%=CCCCCC", "Gray 30%=B3B3B3", "Gray 40%=999999", "Gray 50%=808080",
            "Gray 60%=666666", "Gray 70%=4D4D4D", "Gray 80%=333333", "Gray 90%=1A1A1A", "Black=000000",
        ),
        hex(
            "Vivid",
            "E53935", "D81B60", "8E24AA", "5E35B1", "3949AB", "1E88E5", "039BE5", "00ACC1", "00897B", "43A047", "7CB342",
            "C0CA33", "FDD835", "FFB300", "FB8C00", "F4511E",
        ),
        hex(
            "Pastels",
            "FFCDD2", "F8BBD0", "E1BEE7", "D1C4E9", "C5CAE9", "BBDEFB", "B3E5FC", "B2EBF2", "B2DFDB", "C8E6C9", "DCEDC8",
            "F0F4C3", "FFF9C4", "FFECB3", "FFE0B2", "FFCCBC",
        ),
        hex(
            "Earth tones",
            "3E2723", "5D4037", "795548", "8D6E63", "A1887F", "6D4C41", "8B5A2B", "A0522D", "CD853F", "D2B48C", "C2B280",
            "556B2F", "6B8E23", "808000", "2F4F4F", "708090",
        ),
        hex(
            "Skin tones",
            "FFDFC4", "F0D5BE", "EECEB3", "E1B899", "E5C298", "FFDCB2", "E5B887", "E5A073", "DB9065", "CE967C", "C67856",
            "BA6C49", "A57257", "8D5524", "7C4A2D", "5C3836", "3D2210",
        ),
        hex(
            "Web",
            "Navy=000080", "Teal=008080", "Olive=808000", "Maroon=800000", "Purple=800080", "Silver=C0C0C0", "Coral=FF7F50",
            "Gold=FFD700", "Salmon=FA8072", "Khaki=F0E68C", "Plum=DDA0DD", "Orchid=DA70D6", "Turquoise=40E0D0", "Indigo=4B0082",
            "Crimson=DC143C", "Chocolate=D2691E",
        ),
    )

    // ------------------------------------------------------------ files

    /** The groups in an .aco or .ase file (null when it's neither). Colors are converted to RGB. */
    fun parse(bytes: ByteArray, fileName: String): List<SwatchGroup>? {
        if (bytes.size >= 4 && bytes[0] == 'A'.code.toByte() && bytes[1] == 'S'.code.toByte() && bytes[2] == 'E'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            return parseAse(bytes, fileName)
        }
        return parseAco(bytes, fileName)?.let { listOf(it) }
    }

    /** Photoshop's .aco: version 1 (colors only), usually followed by version 2 (with names). */
    private fun parseAco(bytes: ByteArray, fileName: String): SwatchGroup? = try {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var best: List<Swatch>? = null
        while (b.remaining() >= 4) {
            val version = b.short.toInt() and 0xFFFF
            val count = b.short.toInt() and 0xFFFF
            if (version != 1 && version != 2) break
            val list = ArrayList<Swatch>(count)
            for (i in 0 until count) {
                val space = b.short.toInt() and 0xFFFF
                val w = b.short.toInt() and 0xFFFF
                val x = b.short.toInt() and 0xFFFF
                val y = b.short.toInt() and 0xFFFF
                val z = b.short.toInt() and 0xFFFF
                var name = ""
                if (version == 2) {
                    val len = b.int
                    val sb = StringBuilder()
                    for (k in 0 until len) {
                        val ch = b.char
                        if (ch != '\u0000') sb.append(ch)
                    }
                    name = sb.toString()
                }
                acoColor(space, w, x, y, z)?.let { list.add(Swatch(it, name)) }
            }
            best = list
            if (version == 2) break
        }
        best?.takeIf { it.isNotEmpty() }?.let { SwatchGroup(fileName, it) }
    } catch (e: Exception) {
        null
    }

    private fun acoColor(space: Int, w: Int, x: Int, y: Int, z: Int): Int? = when (space) {
        0 -> rgb(w shr 8, x shr 8, y shr 8)
        1 -> android8(hsbToRgb(w / 65535f * 360f, x / 65535f, y / 65535f))
        // CMYK: 0 is all ink, 65535 none
        2 -> {
            val c = 1f - w / 65535f
            val m = 1f - x / 65535f
            val ye = 1f - y / 65535f
            val k = 1f - z / 65535f
            rgb(to8((1 - c) * (1 - k)), to8((1 - m) * (1 - k)), to8((1 - ye) * (1 - k)))
        }
        7 -> (0xFF shl 24) or ColorMath.labToRgb(w / 100f, x.toShort() / 100f, y.toShort() / 100f)
        // Grayscale: 0 white … 10000 black
        8 -> {
            val g = to8(1f - (w / 10000f).coerceIn(0f, 1f))
            rgb(g, g, g)
        }
        else -> null
    }

    /** Adobe's .ase: groups of named colors (RGB, CMYK, Lab or gray, as floats). */
    private fun parseAse(bytes: ByteArray, fileName: String): List<SwatchGroup>? = try {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.position(8)
        val blocks = b.int
        val groups = ArrayList<SwatchGroup>()
        var current: String? = null
        var list = ArrayList<Swatch>()
        val loose = ArrayList<Swatch>()
        fun name(): String {
            val n = b.short.toInt() and 0xFFFF
            val sb = StringBuilder()
            for (k in 0 until n) {
                val ch = b.char
                if (ch != '\u0000') sb.append(ch)
            }
            return sb.toString()
        }
        for (i in 0 until blocks) {
            if (b.remaining() < 6) break
            val type = b.short.toInt() and 0xFFFF
            val len = b.int
            val end = b.position() + len
            when (type) {
                0xC001 -> {
                    current = name()
                    list = ArrayList()
                }
                0xC002 -> {
                    if (current != null && list.isNotEmpty()) groups.add(SwatchGroup(current, list))
                    current = null
                }
                0x0001 -> {
                    val n = name()
                    val model = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
                    val color = when (model) {
                        "RGB " -> rgb(to8(b.float), to8(b.float), to8(b.float))
                        "CMYK" -> {
                            val c = b.float; val m = b.float; val y = b.float; val k = b.float
                            rgb(to8((1 - c) * (1 - k)), to8((1 - m) * (1 - k)), to8((1 - y) * (1 - k)))
                        }
                        "LAB " -> (0xFF shl 24) or ColorMath.labToRgb(b.float * 100f, b.float, b.float)
                        "Gray" -> {
                            val g = to8(b.float)
                            rgb(g, g, g)
                        }
                        else -> null
                    }
                    if (color != null) (if (current != null) list else loose).add(Swatch(color, n))
                }
            }
            b.position(end)
        }
        if (loose.isNotEmpty()) groups.add(0, SwatchGroup(fileName, loose))
        groups.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun to8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private fun android8(c: Int) = c

    private fun hsbToRgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return rgb(to8(r), to8(g), to8(b))
    }

    // ------------------------------------------------------------ saving yours

    fun toJson(groups: List<SwatchGroup>): String {
        val a = org.json.JSONArray()
        for (g in groups) {
            a.put(org.json.JSONObject().put("name", g.name).put("mine", g.mine).put("colors", org.json.JSONArray().also { c ->
                g.swatches.forEach { s -> c.put(org.json.JSONObject().put("c", s.color.toLong() and 0xFFFFFFFFL).put("n", s.name)) }
            }))
        }
        return a.toString()
    }

    fun fromJson(text: String): List<SwatchGroup> = try {
        val a = org.json.JSONArray(text)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val c = o.optJSONArray("colors")
            SwatchGroup(
                o.optString("name", "Swatches"),
                List(c?.length() ?: 0) { k -> c!!.getJSONObject(k).let { s -> Swatch(s.optLong("c").toInt() or 0xFF000000.toInt(), s.optString("n")) } },
                o.optBoolean("mine"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
