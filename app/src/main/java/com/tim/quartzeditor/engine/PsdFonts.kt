package com.tim.quartzeditor.engine

// Photoshop names fonts by their PostScript name ("Montserrat-ExtraBold", "Arial-BoldMT"). These
// become our font keys: a Google Font of that family and weight (downloaded when it's needed), or
// for common fonts that aren't Google Fonts, a close free one (Arial → Arimo, Gotham → Montserrat).

object PsdFonts {

    /** A font key for [postScript], and whether it's that font itself (not a stand-in). */
    class Pick(val key: String, val exact: Boolean, val label: String)

    /** Fonts that aren't Google Fonts, and the free ones that look most like them. */
    private val STAND_INS = mapOf(
        "arial" to "Arimo", "helvetica" to "Arimo", "helveticaneue" to "Inter", "univers" to "Arimo",
        "timesnewroman" to "Tinos", "times" to "Tinos", "couriernew" to "Cousine", "courier" to "Cousine",
        "proximanova" to "Montserrat", "gotham" to "Montserrat", "gothamrounded" to "Nunito", "futura" to "Jost",
        "futurapt" to "Jost", "myriadpro" to "PT Sans", "myriad" to "PT Sans", "avenir" to "Nunito Sans",
        "avenirnext" to "Nunito Sans", "impact" to "Anton", "franklingothic" to "Libre Franklin",
        "itcfranklingothic" to "Libre Franklin", "georgia" to "Gelasio", "verdana" to "Noto Sans",
        "tahoma" to "Noto Sans", "segoeui" to "Noto Sans", "calibri" to "Carlito", "cambria" to "Caladea",
        "garamond" to "EB Garamond", "adobegaramond" to "EB Garamond", "adobegaramondpro" to "EB Garamond",
        "din" to "Barlow", "dinpro" to "Barlow", "dinnextltpro" to "Barlow", "brandongrotesque" to "Raleway",
        "trajanpro" to "Cinzel", "trajan" to "Cinzel", "bodoni" to "Libre Bodoni", "minionpro" to "Crimson Pro",
        "minion" to "Crimson Pro", "sfprodisplay" to "Inter", "sfprotext" to "Inter", "sfpro" to "Inter",
        "fordantenna" to "Arimo", "antenna" to "Arimo", "vwheadoffice" to "Arimo", "vwtext" to "Arimo",
        "vwhead" to "Arimo", "gmsansui" to "Arimo", "lucidagrande" to "Noto Sans", "trebuchetms" to "Fira Sans",
        "century gothic" to "Questrial", "centurygothic" to "Questrial", "gillsans" to "Lato", "optima" to "Marcellus",
        "rockwell" to "Arvo", "copperplate" to "Cinzel", "brushscriptmt" to "Dancing Script", "brushscript" to "Dancing Script",
    )

    fun map(postScript: String?): Pick {
        val ps = postScript?.trim()?.takeIf { it.isNotEmpty() } ?: return Pick(TextFont.SANS.key, false, "Sans")
        // A font you imported with that name ("Gotham-Bold.otf" for Gotham-Bold)
        fun squash(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        Fonts.imported.value.firstOrNull { squash(it.label) == squash(ps) }?.let { return Pick(it.key, true, it.label) }
        var base = ps.substringBefore('-')
        var style = ps.substringAfter('-', "")
        // Arial's PostScript names end in MT ("ArialMT", "Arial-BoldMT"), Times' in PSMT
        fun stripTail(x: String) = x.removeSuffix("PSMT").removeSuffix("MT").removeSuffix("PS")
        base = stripTail(base)
        style = stripTail(style)
        // "MontserratBold" style names without a dash
        if (style.isEmpty()) {
            val m = Regex("(.*?)(Thin|ExtraLight|UltraLight|Light|Regular|Book|Medium|SemiBold|DemiBold|ExtraBold|UltraBold|Bold|Heavy|Black)(Italic|Oblique|It)?$").find(base)
            if (m != null && m.groupValues[1].isNotEmpty()) {
                base = m.groupValues[1]
                style = m.groupValues[2] + m.groupValues[3]
            }
        }
        val s = style.lowercase()
        val italic = "italic" in s || "oblique" in s || s.endsWith("it")
        val weight = when {
            "thin" in s || "hairline" in s -> 100
            "extralight" in s || "ultralight" in s -> 200
            "semibold" in s || "demibold" in s || "demi" in s -> 600
            "extrabold" in s || "ultrabold" in s || "heavy" in s -> 800
            "black" in s -> 900
            "light" in s -> 300
            "medium" in s -> 500
            "bold" in s -> 700
            else -> 400
        }
        val squashed = base.lowercase().replace(" ", "").removeSuffix("std").removeSuffix("ltstd").removeSuffix("lt")
        val standIn = STAND_INS[squashed] ?: STAND_INS[squashed.removeSuffix("pro")]
        val family = standIn ?: spaced(base)
        val styleKey = if (weight != 400 || italic) "@$weight" + (if (italic) "i" else "") else ""
        return Pick("g:$family$styleKey", standIn == null, family)
    }

    /** "BebasNeue" → "Bebas Neue", "PTSans" → "PT Sans". */
    fun spaced(name: String): String =
        name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ").replace('_', ' ').trim()
}
