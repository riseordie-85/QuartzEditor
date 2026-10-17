package com.tim.quartzeditor.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A shape read from an .svg (or written as SVG path data for the shape library): its outlines in
 * a box [w] × [h] starting at (0, 0). [strokeWidth] > 0: it was drawn as lines (no fill), this wide
 * in the box's units.
 */
class SvgShape(val parts: List<ShapePart>, val w: Float, val h: Float, val evenOdd: Boolean, val strokeWidth: Float = 0f)

class SvgException(message: String) : Exception(message)

/**
 * Reads the outlines of an SVG file: paths, rectangles, circles, ellipses, polygons and polylines,
 * with their transforms (and their groups'). Colors are left out: the shape takes the colors you
 * pick. Only plain Kotlin (no Android), so it can be checked anywhere.
 */
object Svg {

    private const val MAX_POINTS = 40_000

    /** An affine transform: x' = a x + c y + e, y' = b x + d y + f (like SVG's matrix()). */
    private data class Tf(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
        fun then(o: Tf): Tf = Tf(
            a * o.a + c * o.b, b * o.a + d * o.b,
            a * o.c + c * o.d, b * o.c + d * o.d,
            a * o.e + c * o.f + e, b * o.e + d * o.f + f,
        )

        fun x(px: Double, py: Double) = a * px + c * py + e
        fun y(px: Double, py: Double) = b * px + d * py + f
        val scale: Double get() = sqrt(abs(a * d - b * c))
    }

    /** One outline being built: points with their curve handles (absolute), in file units. */
    private class Sub {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        /** Incoming and outgoing handles, absolute (same as the point when there's none). */
        val ix = ArrayList<Double>()
        val iy = ArrayList<Double>()
        val ox = ArrayList<Double>()
        val oy = ArrayList<Double>()
        var closed = false

        fun moveTo(x: Double, y: Double) {
            xs.add(x); ys.add(y); ix.add(x); iy.add(y); ox.add(x); oy.add(y)
        }

        fun cubicTo(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double) {
            ox[ox.lastIndex] = c1x
            oy[oy.lastIndex] = c1y
            xs.add(x); ys.add(y); ix.add(c2x); iy.add(c2y); ox.add(x); oy.add(y)
        }

        fun lineTo(x: Double, y: Double) = cubicTo(xs.last(), ys.last(), x, y, x, y)

        val size: Int get() = xs.size
    }

    private class Style(
        val tf: Tf = Tf(),
        val fillNone: Boolean = false,
        val evenOdd: Boolean = false,
        val stroked: Boolean = false,
        val strokeWidth: Double = 1.0,
        val hidden: Boolean = false,
    )

    /** Parts in file units, with how each element's fill was set. */
    private class Collected {
        val parts = ArrayList<Pair<Sub, Tf>>()
        /** Per element: index of its first part, filled (vs lines only), even-odd. */
        val elements = ArrayList<Triple<Int, Boolean, Boolean>>()
        var lineWidths = ArrayList<Double>()
        var points = 0
    }

    /** Reads an SVG file's text. */
    fun parse(xml: String): SvgShape {
        val text = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<!\\[CDATA\\[.*?]]>", RegexOption.DOT_MATCHES_ALL), "")
        if (!text.contains("<svg")) throw SvgException("This isn't an SVG file.")
        val col = Collected()
        val stack = ArrayList<Style>()
        stack.add(Style())
        var skipDepth = 0
        var viewBox: DoubleArray? = null
        var docW = 0.0
        var docH = 0.0
        val tag = Regex("<(/?)([A-Za-z][\\w:.-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>")
        for (m in tag.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].substringAfter(':').lowercase()
            val attrs = attributes(m.groupValues[3])
            val selfClosing = m.groupValues[4] == "/"
            // Things that are never drawn as they are: their contents are skipped
            val hiddenKinds = setOf("defs", "clippath", "mask", "symbol", "marker", "pattern", "lineargradient", "radialgradient", "filter", "style", "title", "desc", "metadata", "text", "foreignobject")
            if (name in hiddenKinds) {
                if (!selfClosing) skipDepth += if (closing) -1 else 1
                if (skipDepth < 0) skipDepth = 0
                continue
            }
            if (skipDepth > 0) continue
            if (closing) {
                if ((name == "g" || name == "svg" || name == "a") && stack.size > 1) stack.removeAt(stack.lastIndex)
                continue
            }
            val parent = stack.last()
            val style = styleOf(attrs, parent)
            when (name) {
                "svg" -> {
                    if (viewBox == null && docW == 0.0) {
                        viewBox = attrs["viewbox"]?.let { numbers(it) }?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }?.toDoubleArray()
                        docW = length(attrs["width"])
                        docH = length(attrs["height"])
                    }
                    if (!selfClosing) stack.add(style)
                }
                "g", "a" -> if (!selfClosing) stack.add(style)
                "path" -> if (!style.hidden) attrs["d"]?.let { add(col, pathData(it), style) }
                "rect" -> if (!style.hidden) {
                    val x = length(attrs["x"]); val y = length(attrs["y"])
                    val w = length(attrs["width"]); val h = length(attrs["height"])
                    var rx = attrs["rx"]?.let { length(it) } ?: -1.0
                    var ry = attrs["ry"]?.let { length(it) } ?: -1.0
                    if (rx < 0) rx = max(ry, 0.0)
                    if (ry < 0) ry = rx
                    if (w > 0 && h > 0) add(col, listOf(rect(x, y, w, h, min(rx, w / 2), min(ry, h / 2))), style)
                }
                "circle" -> if (!style.hidden) {
                    val r = length(attrs["r"])
                    if (r > 0) add(col, listOf(ellipse(length(attrs["cx"]), length(attrs["cy"]), r, r)), style)
                }
                "ellipse" -> if (!style.hidden) {
                    val rx = length(attrs["rx"]); val ry = length(attrs["ry"])
                    if (rx > 0 && ry > 0) add(col, listOf(ellipse(length(attrs["cx"]), length(attrs["cy"]), rx, ry)), style)
                }
                "polygon", "polyline" -> if (!style.hidden) {
                    val n = numbers(attrs["points"] ?: "")
                    if (n.size >= 4) {
                        val s = Sub()
                        s.moveTo(n[0], n[1])
                        var i = 2
                        while (i + 1 < n.size) {
                            s.lineTo(n[i], n[i + 1])
                            i += 2
                        }
                        s.closed = name == "polygon"
                        add(col, listOf(s), style)
                    }
                }
            }
            if (col.points > MAX_POINTS) throw SvgException("This SVG is too detailed to use as a shape.")
        }
        if (col.parts.isEmpty()) throw SvgException("There are no shapes in this SVG (text and pictures inside it can't be used).")
        return finish(col, viewBox, docW, docH)
    }

    /** SVG path data ("M10 10 L20 20 Z") as outlines (used for the shape library too). */
    fun path(d: String, evenOdd: Boolean = false): SvgShape {
        val col = Collected()
        add(col, pathData(d), Style(evenOdd = evenOdd))
        return finish(col, null, 0.0, 0.0)
    }

    private fun add(col: Collected, subs: List<Sub>, style: Style) {
        val usable = subs.filter { it.size >= 2 }
        if (usable.isEmpty()) return
        val filled = !style.fillNone
        if (!filled && !style.stroked) return
        col.elements.add(Triple(col.parts.size, filled, style.evenOdd))
        if (!filled) col.lineWidths.add(style.strokeWidth * style.tf.scale)
        for (s in usable) {
            col.parts.add(s to style.tf)
            col.points += s.size
        }
    }

    /** Puts the outlines in a box starting at (0, 0) that's just big enough for them. */
    private fun finish(col: Collected, viewBox: DoubleArray?, docW: Double, docH: Double): SvgShape {
        // Drawn only with lines: kept as lines (its width in the box's units)
        val anyFilled = col.elements.any { it.second }
        val keep = col.elements.withIndex().filter { (_, e) -> e.second == anyFilled }.map { it.value }
        // Each outline, how it joins (1: starts an element, -1: part of the one before), and its element's fill rule
        val keptParts = ArrayList<Triple<Pair<Sub, Tf>, Int, Boolean>>()
        for ((ei, e) in col.elements.withIndex()) {
            if (e.second != anyFilled) continue
            val end = col.elements.getOrNull(ei + 1)?.first ?: col.parts.size
            for (pi in e.first until end) keptParts.add(Triple(col.parts[pi], if (pi == e.first) 1 else -1, e.third))
        }
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        // The box the curves really reach (their handles can stick out further)
        fun include(px: Double, py: Double, tf: Tf) {
            val x = tf.x(px, py); val y = tf.y(px, py)
            minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
        }
        for ((st, _, _) in keptParts) {
            val (s, tf) = st
            val n = s.size
            val segs = if (s.closed) n else n - 1
            for (i in 0 until n) include(s.xs[i], s.ys[i], tf)
            for (i in 0 until segs) {
                val j = (i + 1) % n
                for (k in 1 until 8) {
                    val t = k / 8.0
                    val u = 1 - t
                    val bx = u * u * u * s.xs[i] + 3 * u * u * t * s.ox[i] + 3 * u * t * t * s.ix[j] + t * t * t * s.xs[j]
                    val by = u * u * u * s.ys[i] + 3 * u * u * t * s.oy[i] + 3 * u * t * t * s.iy[j] + t * t * t * s.ys[j]
                    include(bx, by, tf)
                }
            }
        }
        val lineW = if (!anyFilled) (col.lineWidths.maxOrNull() ?: 1.0) else 0.0
        // Lines reach half their width past their middle
        if (lineW > 0) {
            minX -= lineW / 2; minY -= lineW / 2; maxX += lineW / 2; maxY += lineW / 2
        }
        if (maxX - minX < 1e-6 && maxY - minY < 1e-6) throw SvgException("The shapes in this SVG have no size.")
        val w = max(maxX - minX, 1e-3)
        val h = max(maxY - minY, 1e-3)
        // Filled shapes: each element adds to the ones before it, with its own fill rule (holes
        // inside it come from that). Lines only: just drawn together (no joining).
        val evenOdd = keep.count { it.third } * 2 > keep.size
        val parts = keptParts.map { (st, op, eo) ->
            val (s, tf) = st
            val pts = (0 until s.size).map { i ->
                val x = tf.x(s.xs[i], s.ys[i]) - minX
                val y = tf.y(s.xs[i], s.ys[i]) - minY
                PenPoint(
                    x.toFloat(), y.toFloat(),
                    (tf.x(s.ox[i], s.oy[i]) - minX - x).toFloat(), (tf.y(s.ox[i], s.oy[i]) - minY - y).toFloat(),
                    (tf.x(s.ix[i], s.iy[i]) - minX - x).toFloat(), (tf.y(s.ix[i], s.iy[i]) - minY - y).toFloat(),
                )
            }
            ShapePart(pts, s.closed || anyFilled, if (anyFilled) op else -1, if (op == 1 && eo != evenOdd) eo else null)
        }
        return SvgShape(parts, w.toFloat(), h.toFloat(), evenOdd, lineW.toFloat())
    }

    // ------------------------------------------------------------ attributes and styles

    private fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in Regex("([A-Za-z_:][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").findAll(s)) {
            val key = m.groupValues[1].substringAfter(':').lowercase().let { if (m.groupValues[1].startsWith("xlink:")) "xlink-$it" else it }
            out[key] = m.groupValues[3].ifEmpty { m.groupValues[4] }
        }
        // style="fill:none; fill-rule:evenodd" counts like attributes
        out["style"]?.split(';')?.forEach { decl ->
            val k = decl.substringBefore(':').trim().lowercase()
            val v = decl.substringAfter(':', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun styleOf(a: Map<String, String>, parent: Style): Style {
        val tf = a["transform"]?.let { parent.tf.then(transform(it)) } ?: parent.tf
        val fill = a["fill"]?.trim()?.lowercase()
        val stroke = a["stroke"]?.trim()?.lowercase()
        return Style(
            tf = tf,
            fillNone = if (fill != null) fill == "none" || fill == "transparent" else parent.fillNone,
            evenOdd = a["fill-rule"]?.trim()?.lowercase()?.let { it == "evenodd" } ?: parent.evenOdd,
            stroked = if (stroke != null) stroke != "none" && stroke != "transparent" else parent.stroked,
            strokeWidth = a["stroke-width"]?.let { length(it) }?.takeIf { it > 0 } ?: parent.strokeWidth,
            hidden = parent.hidden || a["display"]?.trim() == "none" || a["visibility"]?.trim() == "hidden" ||
                a["opacity"]?.trim()?.toDoubleOrNull() == 0.0,
        )
    }

    private fun transform(s: String): Tf {
        var t = Tf()
        for (m in Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)").findAll(s)) {
            val n = numbers(m.groupValues[2])
            val next = when (m.groupValues[1]) {
                "matrix" -> if (n.size >= 6) Tf(n[0], n[1], n[2], n[3], n[4], n[5]) else Tf()
                "translate" -> Tf(e = n.getOrElse(0) { 0.0 }, f = n.getOrElse(1) { 0.0 })
                "scale" -> Tf(a = n.getOrElse(0) { 1.0 }, d = n.getOrElse(1) { n.getOrElse(0) { 1.0 } })
                "rotate" -> {
                    val r = n.getOrElse(0) { 0.0 } * PI / 180
                    val rot = Tf(cos(r), sin(r), -sin(r), cos(r))
                    if (n.size >= 3) Tf(e = n[1], f = n[2]).then(rot).then(Tf(e = -n[1], f = -n[2])) else rot
                }
                "skewX" -> Tf(c = tan(n.getOrElse(0) { 0.0 } * PI / 180))
                "skewY" -> Tf(b = tan(n.getOrElse(0) { 0.0 } * PI / 180))
                else -> Tf()
            }
            t = t.then(next)
        }
        return t
    }

    private fun numbers(s: String): List<Double> =
        Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(s).mapNotNull { it.value.toDoubleOrNull() }.toList()

    /** A length ("12", "12px", "1.5em" taken as px); percentages count as 0 (unknown size). */
    private fun length(s: String?): Double {
        if (s == null) return 0.0
        val t = s.trim()
        if (t.endsWith("%")) return 0.0
        return Regex("^[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").find(t)?.value?.toDoubleOrNull() ?: 0.0
    }

    // ------------------------------------------------------------ basic shapes

    /** How far along a quarter circle its handles go, so a cubic curve follows it. */
    private const val K = 0.5522847498

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): Sub {
        val s = Sub()
        s.moveTo(cx + rx, cy)
        s.cubicTo(cx + rx, cy + ry * K, cx + rx * K, cy + ry, cx, cy + ry)
        s.cubicTo(cx - rx * K, cy + ry, cx - rx, cy + ry * K, cx - rx, cy)
        s.cubicTo(cx - rx, cy - ry * K, cx - rx * K, cy - ry, cx, cy - ry)
        s.cubicTo(cx + rx * K, cy - ry, cx + rx, cy - ry * K, cx + rx, cy)
        joinEnds(s)
        s.closed = true
        return s
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double, rx: Double, ry: Double): Sub {
        val s = Sub()
        if (rx <= 0 || ry <= 0) {
            s.moveTo(x, y); s.lineTo(x + w, y); s.lineTo(x + w, y + h); s.lineTo(x, y + h)
            s.closed = true
            return s
        }
        s.moveTo(x + rx, y)
        s.lineTo(x + w - rx, y)
        s.cubicTo(x + w - rx + rx * K, y, x + w, y + ry - ry * K, x + w, y + ry)
        s.lineTo(x + w, y + h - ry)
        s.cubicTo(x + w, y + h - ry + ry * K, x + w - rx + rx * K, y + h, x + w - rx, y + h)
        s.lineTo(x + rx, y + h)
        s.cubicTo(x + rx - rx * K, y + h, x, y + h - ry + ry * K, x, y + h - ry)
        s.lineTo(x, y + ry)
        s.cubicTo(x, y + ry - ry * K, x + rx - rx * K, y, x + rx, y)
        joinEnds(s)
        s.closed = true
        return s
    }

    /** A closed outline whose last point is back on its first: one point there (with both handles). */
    private fun joinEnds(s: Sub) {
        if (s.size < 3) return
        val n = s.size - 1
        if (abs(s.xs[n] - s.xs[0]) < 1e-9 && abs(s.ys[n] - s.ys[0]) < 1e-9) {
            s.ix[0] = s.ix[n]
            s.iy[0] = s.iy[n]
            s.xs.removeAt(n); s.ys.removeAt(n); s.ix.removeAt(n); s.iy.removeAt(n); s.ox.removeAt(n); s.oy.removeAt(n)
        }
    }

    // ------------------------------------------------------------ path data

    private class Scanner(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        fun command(): Char? {
            skip()
            return if (i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E') s[i++] else null
        }

        fun hasNumber(): Boolean {
            skip()
            return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.')
        }

        fun number(): Double {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length && (s[i].isDigit() || (s[i] == '.' && !dot))) {
                if (s[i] == '.') dot = true
                i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                if (i < s.length && s[i].isDigit()) { while (i < s.length && s[i].isDigit()) i++ } else i = save
            }
            return s.substring(start, i).toDoubleOrNull() ?: throw SvgException("Unreadable path in this SVG.")
        }

        /** An arc's flag: one character, 0 or 1 (they're often written without spaces). */
        fun flag(): Boolean {
            skip()
            if (i >= s.length) throw SvgException("Unreadable path in this SVG.")
            val c = s[i++]
            return c == '1'
        }
    }

    private fun pathData(d: String): List<Sub> {
        val out = ArrayList<Sub>()
        val sc = Scanner(d)
        var cur: Sub? = null
        var x = 0.0
        var y = 0.0
        var sx = 0.0
        var sy = 0.0
        // The last curve's second handle (for S and T), and which kind it was
        var lcx = 0.0
        var lcy = 0.0
        var last = ' '
        var cmd: Char? = null
        fun start(nx: Double, ny: Double) {
            cur?.let { if (it.size >= 2) out.add(it) }
            cur = Sub().also { it.moveTo(nx, ny) }
            x = nx; y = ny; sx = nx; sy = ny
        }
        fun sub(): Sub = cur ?: Sub().also { it.moveTo(x, y); cur = it }
        var guard = 0
        while (true) {
            if (++guard > MAX_POINTS * 4) throw SvgException("This SVG is too detailed to use as a shape.")
            val c = sc.command()
            if (c != null) cmd = c else if (!sc.hasNumber()) break
            val k = cmd ?: break
            val rel = k.isLowerCase()
            when (k.uppercaseChar()) {
                'M' -> {
                    val nx = sc.number() + (if (rel) x else 0.0)
                    val ny = sc.number() + (if (rel) y else 0.0)
                    start(nx, ny)
                    // Numbers after a move are lines
                    cmd = if (rel) 'l' else 'L'
                    last = 'M'
                }
                'L' -> {
                    val nx = sc.number() + (if (rel) x else 0.0)
                    val ny = sc.number() + (if (rel) y else 0.0)
                    sub().lineTo(nx, ny)
                    x = nx; y = ny; last = 'L'
                }
                'H' -> {
                    val nx = sc.number() + (if (rel) x else 0.0)
                    sub().lineTo(nx, y)
                    x = nx; last = 'L'
                }
                'V' -> {
                    val ny = sc.number() + (if (rel) y else 0.0)
                    sub().lineTo(x, ny)
                    y = ny; last = 'L'
                }
                'C' -> {
                    val ox = if (rel) x else 0.0
                    val oy = if (rel) y else 0.0
                    val c1x = sc.number() + ox; val c1y = sc.number() + oy
                    val c2x = sc.number() + ox; val c2y = sc.number() + oy
                    val nx = sc.number() + ox; val ny = sc.number() + oy
                    sub().cubicTo(c1x, c1y, c2x, c2y, nx, ny)
                    lcx = c2x; lcy = c2y; x = nx; y = ny; last = 'C'
                }
                'S' -> {
                    val ox = if (rel) x else 0.0
                    val oy = if (rel) y else 0.0
                    val c1x = if (last == 'C') 2 * x - lcx else x
                    val c1y = if (last == 'C') 2 * y - lcy else y
                    val c2x = sc.number() + ox; val c2y = sc.number() + oy
                    val nx = sc.number() + ox; val ny = sc.number() + oy
                    sub().cubicTo(c1x, c1y, c2x, c2y, nx, ny)
                    lcx = c2x; lcy = c2y; x = nx; y = ny; last = 'C'
                }
                'Q' -> {
                    val ox = if (rel) x else 0.0
                    val oy = if (rel) y else 0.0
                    val qx = sc.number() + ox; val qy = sc.number() + oy
                    val nx = sc.number() + ox; val ny = sc.number() + oy
                    sub().cubicTo(x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), nx + 2.0 / 3 * (qx - nx), ny + 2.0 / 3 * (qy - ny), nx, ny)
                    lcx = qx; lcy = qy; x = nx; y = ny; last = 'Q'
                }
                'T' -> {
                    val ox = if (rel) x else 0.0
                    val oy = if (rel) y else 0.0
                    val qx = if (last == 'Q') 2 * x - lcx else x
                    val qy = if (last == 'Q') 2 * y - lcy else y
                    val nx = sc.number() + ox; val ny = sc.number() + oy
                    sub().cubicTo(x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), nx + 2.0 / 3 * (qx - nx), ny + 2.0 / 3 * (qy - ny), nx, ny)
                    lcx = qx; lcy = qy; x = nx; y = ny; last = 'Q'
                }
                'A' -> {
                    val rx = abs(sc.number()); val ry = abs(sc.number())
                    val rot = sc.number()
                    val large = sc.flag(); val sweep = sc.flag()
                    val nx = sc.number() + (if (rel) x else 0.0)
                    val ny = sc.number() + (if (rel) y else 0.0)
                    arc(sub(), x, y, rx, ry, rot, large, sweep, nx, ny)
                    x = nx; y = ny; last = 'A'
                }
                'Z' -> {
                    cur?.let { s ->
                        if (s.size >= 2 && (abs(x - sx) > 1e-9 || abs(y - sy) > 1e-9)) s.lineTo(sx, sy)
                        joinEnds(s)
                        s.closed = true
                        if (s.size >= 2) out.add(s)
                    }
                    cur = null
                    x = sx; y = sy; last = 'Z'
                    // A command must follow
                    cmd = null
                }
                else -> throw SvgException("Unreadable path in this SVG.")
            }
        }
        cur?.let { if (it.size >= 2) out.add(it) }
        return out
    }

    /** An elliptical arc as cubic curves (SVG's endpoint form, at most a quarter turn per curve). */
    private fun arc(s: Sub, x1: Double, y1: Double, rx0: Double, ry0: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
        if (rx0 == 0.0 || ry0 == 0.0 || (x1 == x2 && y1 == y2)) {
            s.lineTo(x2, y2)
            return
        }
        val phi = rotDeg * PI / 180
        val cp = cos(phi)
        val sp = sin(phi)
        val dx = (x1 - x2) / 2
        val dy = (y1 - y2) / 2
        val x1p = cp * dx + sp * dy
        val y1p = -sp * dx + cp * dy
        var rx = rx0
        var ry = ry0
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            rx *= sqrt(lambda); ry *= sqrt(lambda)
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2
        val cy = sp * cxp + cp * cyp + (y1 + y2) / 2
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI
        if (sweep && dt < 0) dt += 2 * PI
        val n = ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / n
        val alpha = 4.0 / 3 * tan(step / 4)
        var t = t1
        for (i in 0 until n) {
            val c0 = cos(t); val s0 = sin(t)
            val c1 = cos(t + step); val s1 = sin(t + step)
            fun px(c: Double, s0_: Double) = cx + rx * c * cp - ry * s0_ * sp
            fun py(c: Double, s0_: Double) = cy + rx * c * sp + ry * s0_ * cp
            val e0x = px(c0, s0); val e0y = py(c0, s0)
            val e1x = px(c1, s1); val e1y = py(c1, s1)
            // Derivatives at both ends
            val d0x = -rx * s0 * cp - ry * c0 * sp
            val d0y = -rx * s0 * sp + ry * c0 * cp
            val d1x = -rx * s1 * cp - ry * c1 * sp
            val d1y = -rx * s1 * sp + ry * c1 * cp
            val endX = if (i == n - 1) x2 else e1x
            val endY = if (i == n - 1) y2 else e1y
            s.cubicTo(e0x + alpha * d0x, e0y + alpha * d0y, e1x - alpha * d1x, e1y - alpha * d1y, endX, endY)
            t += step
        }
    }
}
