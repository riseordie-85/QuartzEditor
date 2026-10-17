package com.tim.quartzeditor.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A shape in the library: ready-made ([builtIn]) or one you imported from an .svg. */
class LibraryShape(val key: String, val name: String, val shape: SvgShape, val builtIn: Boolean)

/**
 * The ready-made custom shapes (drawn with the Shape tool's Custom shape). Written as SVG path
 * data in a 100 × 100 box; a few (bursts, cogs) are worked out. Plain Kotlin, no Android.
 */
object ShapeLibrary {

    /** Name and path data. Several outlines in one entry are joined: later ones add, "-" ones cut out. */
    private val PATHS: List<Pair<String, List<String>>> = listOf(
        "Heart" to listOf("M50 90 C20 68 4 52 4 31 C4 16 15 6 29 6 C39 6 46 12 50 20 C54 12 61 6 71 6 C85 6 96 16 96 31 C96 52 80 68 50 90 Z"),
        "Star" to listOf("M50 3 L61.8 37.6 L97.6 38.2 L69 59.8 L79.4 94 L50 73.6 L20.6 94 L31 59.8 L2.4 38.2 L38.2 37.6 Z"),
        "Speech bubble" to listOf("M14 8 H86 Q96 8 96 18 V58 Q96 68 86 68 H46 L24 92 L28 68 H14 Q4 68 4 58 V18 Q4 8 14 8 Z"),
        "Round bubble" to listOf("M50 6 C75 6 96 21 96 40 C96 59 75 74 50 74 C45 74 40.5 73.5 36 72.5 L14 92 L20 66 C10 59 4 50 4 40 C4 21 25 6 50 6 Z"),
        "Thought bubble" to listOf(
            "M30 64 C16 64 6 56 6 45 C6 36 12 29 21 27 C23 16 34 8 47 8 C56 8 64 12 68 18 C72 16 76 15 80 15 C90 15 98 24 98 35 C98 45 92 52 84 54 C83 61 76 66 68 66 C63 66 59 64 56 62 C52 66 46 68 40 68 C36 68 33 66 30 64 Z",
            "M28 78 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 Z",
            "M14 92 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0 Z",
        ),
        "Cloud" to listOf("M26 80 C12 80 4 70 4 59 C4 48 12 40 23 39 C25 25 36 16 50 16 C62 16 72 24 75 35 C87 35 96 45 96 57 C96 70 86 80 73 80 Z"),
        "Arrow" to listOf("M4 36 H56 V14 L96 50 L56 86 V64 H4 Z"),
        "Double arrow" to listOf("M4 50 L30 20 V37 H70 V20 L96 50 L70 80 V63 H30 V80 Z"),
        "Chevron" to listOf("M10 4 H50 L90 50 L50 96 H10 L50 50 Z"),
        "U-turn arrow" to listOf("M18 96 V44 C18 23 33 8 54 8 C75 8 90 23 90 44 V54 H98 L80 82 L62 54 H70 V44 C70 34 63 27 54 27 C45 27 38 34 38 44 V96 Z"),
        "Check mark" to listOf("M6 52 L20 38 L38 56 L80 14 L94 28 L38 84 Z"),
        "Cross" to listOf("M18 4 L50 36 L82 4 L96 18 L64 50 L96 82 L82 96 L50 64 L18 96 L4 82 L36 50 L4 18 Z"),
        "Plus" to listOf("M38 4 H62 V38 H96 V62 H62 V96 H38 V62 H4 V38 H38 Z"),
        "Ring" to listOf("M50 50 m-46 0 a46 46 0 1 0 92 0 a46 46 0 1 0 -92 0 Z", "-M50 50 m-28 0 a28 28 0 1 0 56 0 a28 28 0 1 0 -56 0 Z"),
        "Frame" to listOf("M4 4 H96 V96 H4 Z", "-M18 18 H82 V82 H18 Z"),
        "Moon" to listOf("M60 4 C34 9 16 29 16 53 C16 78 37 96 63 96 C77 96 89 90 96 80 C88 85 79 87 70 87 C46 87 28 69 28 46 C28 28 40 12 60 4 Z"),
        "Lightning" to listOf("M58 2 L14 56 H44 L36 98 L86 38 H54 Z"),
        "Drop" to listOf("M50 4 C50 4 16 44 16 64 C16 83 31 96 50 96 C69 96 84 83 84 64 C84 44 50 4 50 4 Z"),
        "Leaf" to listOf("M92 6 C42 6 8 30 8 66 C8 76 12 86 18 94 C22 80 32 64 50 50 C36 64 28 78 26 92 C34 95 42 96 50 96 C82 96 96 62 92 6 Z"),
        "Location pin" to listOf("M50 2 C30 2 14 17 14 37 C14 62 50 98 50 98 C50 98 86 62 86 37 C86 17 70 2 50 2 Z", "-M50 37 m-13 0 a13 13 0 1 0 26 0 a13 13 0 1 0 -26 0 Z"),
        "House" to listOf("M50 6 L96 46 H82 V94 H60 V66 H40 V94 H18 V46 H4 Z"),
        "Shield" to listOf("M50 4 L90 18 V46 C90 70 74 88 50 96 C26 88 10 70 10 46 V18 Z"),
        "Ribbon" to listOf("M2 32 H26 V76 H2 L12 54 Z", "M98 32 H74 V76 H98 L88 54 Z", "M20 22 H80 V66 H20 Z"),
        "Price tag" to listOf("M56 4 H92 Q96 4 96 8 V44 L50 90 Q46 94 42 90 L10 58 Q6 54 10 50 Z", "-M80 20 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0 Z"),
        "Bookmark" to listOf("M20 4 H80 V96 L50 74 L20 96 Z"),
        "Flag" to listOf("M12 4 H22 V96 H12 Z", "M22 8 C40 0 54 18 74 10 C82 7 88 6 92 8 V52 C80 48 70 60 54 58 C40 56 34 46 22 52 Z"),
        "Crown" to listOf("M8 28 L30 52 L50 14 L70 52 L92 28 L84 84 H16 Z"),
        "Gem" to listOf("M26 8 H74 L96 36 L50 94 L4 36 Z"),
        "Warning" to listOf("M50 6 L96 90 H4 Z", "-M45 36 H55 L53 64 H47 Z", "-M50 76 m-5 0 a5 5 0 1 0 10 0 a5 5 0 1 0 -10 0 Z"),
        "Music note" to listOf("M38 14 L88 4 V68 C88 78 80 84 70 84 C60 84 54 78 54 70 C54 62 61 56 70 56 C73 56 76 57 78 58 V24 L48 30 V78 C48 88 40 94 30 94 C20 94 14 88 14 80 C14 72 21 66 30 66 C33 66 36 67 38 68 Z"),
        "Paw" to listOf(
            "M50 52 C64 52 78 66 78 80 C78 90 70 94 62 92 C57 91 54 89 50 89 C46 89 43 91 38 92 C30 94 22 90 22 80 C22 66 36 52 50 52 Z",
            "M32 18 m-9 0 a9 12 0 1 0 18 0 a9 12 0 1 0 -18 0 Z",
            "M68 18 m-9 0 a9 12 0 1 0 18 0 a9 12 0 1 0 -18 0 Z",
            "M14 42 m-8 0 a8 10 0 1 0 16 0 a8 10 0 1 0 -16 0 Z",
            "M86 42 m-8 0 a8 10 0 1 0 16 0 a8 10 0 1 0 -16 0 Z",
        ),
    )

    private val cache: List<LibraryShape> by lazy {
        val out = ArrayList<LibraryShape>()
        for ((name, paths) in PATHS) {
            runCatching { out.add(LibraryShape("lib:$name", name, joined(paths), true)) }
        }
        // Worked out: badges and cogs
        out.add(LibraryShape("lib:Burst", "Burst", star(24, 0.18, 0.0), true))
        out.add(LibraryShape("lib:Seal", "Seal", star(40, 0.07, 0.0), true))
        out.add(LibraryShape("lib:Sun", "Sun", sun(), true))
        out.add(LibraryShape("lib:Cog", "Cog", cog(10, 0.22, 0.3), true))
        out.add(LibraryShape("lib:Gear", "Gear", cog(16, 0.14, 0.32), true))
        out
    }

    fun builtIns(): List<LibraryShape> = cache

    /** Several outlines as one shape: the first, then each added (or cut out when it starts with "-"). */
    private fun joined(paths: List<String>): SvgShape {
        // Each path read on its own would get its own box: read them together so they line up
        val combined = paths.joinToString(" ") { it.removePrefix("-") }
        val all = Svg.path(combined)
        // Which outline belongs to which entry (to set how it joins)
        var idx = 0
        val ops = ArrayList<Int>()
        for ((i, p) in paths.withIndex()) {
            val n = Svg.path(p.removePrefix("-")).parts.size
            repeat(n) { k -> ops.add(if (k > 0) -1 else if (i == 0) 1 else if (p.startsWith("-")) 2 else 1) }
            idx += n
        }
        val parts = all.parts.mapIndexed { i, part -> part.copy(op = ops.getOrElse(i) { -1 }) }
        return SvgShape(parts, all.w, all.h, false)
    }

    /** A starburst with [n] points, valleys [depth] in (0..1). */
    private fun star(n: Int, depth: Double, rot: Double): SvgShape {
        val pts = ArrayList<PenPoint>()
        for (i in 0 until n * 2) {
            val r = if (i % 2 == 0) 50.0 else 50.0 * (1 - depth)
            val a = -PI / 2 + rot + i * PI / n
            pts.add(PenPoint((50 + cos(a) * r).toFloat(), (50 + sin(a) * r).toFloat()))
        }
        return boxed(listOf(ShapePart(pts, true, 1)), false)
    }

    /** A sun: a circle with rays around it. */
    private fun sun(): SvgShape {
        val parts = ArrayList<ShapePart>()
        parts.add(circle(50.0, 50.0, 26.0, 1))
        val rays = 12
        for (i in 0 until rays) {
            val a = i * 2 * PI / rays
            val w = 0.12
            fun p(r: Double, da: Double) = PenPoint((50 + cos(a + da) * r).toFloat(), (50 + sin(a + da) * r).toFloat())
            parts.add(ShapePart(listOf(p(32.0, -w), p(50.0, 0.0), p(32.0, w)), true, 1))
        }
        return boxed(parts, false)
    }

    /** A cog: [n] square-ish teeth [depth] deep, with a hole [hole] × its size across. */
    private fun cog(n: Int, depth: Double, hole: Double): SvgShape {
        val pts = ArrayList<PenPoint>()
        val half = PI / n
        for (i in 0 until n * 2) {
            val r = if (i % 2 == 0) 50.0 else 50.0 * (1 - depth)
            val mid = -PI / 2 + i * half
            // Teeth a little narrower at the tip, like a gear
            val spread = if (i % 2 == 0) half * 0.36 else half * 0.5
            for (a in doubleArrayOf(mid - spread, mid + spread)) pts.add(PenPoint((50 + cos(a) * r).toFloat(), (50 + sin(a) * r).toFloat()))
        }
        return boxed(listOf(ShapePart(pts, true, 1), circle(50.0, 50.0, 50.0 * hole, 2)), false)
    }

    private fun circle(cx: Double, cy: Double, r: Double, op: Int): ShapePart {
        val k = (0.5522847498 * r).toFloat()
        val x = cx.toFloat()
        val y = cy.toFloat()
        val rr = r.toFloat()
        return ShapePart(listOf(
            PenPoint(x + rr, y, 0f, k, 0f, -k),
            PenPoint(x, y + rr, -k, 0f, k, 0f),
            PenPoint(x - rr, y, 0f, -k, 0f, k),
            PenPoint(x, y - rr, k, 0f, -k, 0f),
        ), true, op)
    }

    /** Moves [parts] into a box starting at (0, 0) just big enough for their points. */
    private fun boxed(parts: List<ShapePart>, evenOdd: Boolean): SvgShape {
        val xs = parts.flatMap { p -> p.points.map { it.x } }
        val ys = parts.flatMap { p -> p.points.map { it.y } }
        val minX = xs.min()
        val minY = ys.min()
        val moved = parts.map { p -> p.copy(points = p.points.map { it.copy(x = it.x - minX, y = it.y - minY) }) }
        return SvgShape(moved, xs.max() - minX, ys.max() - minY, evenOdd)
    }
}
