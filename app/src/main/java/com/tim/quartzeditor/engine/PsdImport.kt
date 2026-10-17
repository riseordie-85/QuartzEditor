package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a Photoshop file into a document: pixel layers, folders (any depth), masks, blend modes,
 * opacity and fill, clipping, hidden layers, names, the layer styles we have, and fill and shape
 * layers (drawn from their color, gradient and outline). Text and smart objects come in as their
 * pixels. Adjustment layers and effects we don't have are listed in [Result.notes].
 */
object PsdImport {

    class Result(val doc: Doc, val notes: List<String>)

    /** Reads [file] (a .psd or .psb copied somewhere readable). Runs in the background. */
    fun read(file: File, name: String): Result {
        val raf = RandomAccessFile(file, "r")
        val buf = try {
            if (raf.length() > Int.MAX_VALUE) throw PsdException("This file is over 2 GB, too big to open here.")
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        } finally {
            raf.close()
        }
        val f = PsdReader.read(buf)
        return Builder(f, name).build()
    }

    private class Builder(val f: PsdFile, name: String) {
        val notes = ArrayList<String>()
        /** Shrinks pictures bigger than the app's limit. */
        val s: Float = min(1f, Doc.MAX_SIDE.toFloat() / max(f.width, f.height))
        val doc = Doc((f.width * s).roundToInt().coerceIn(1, Doc.MAX_SIDE), (f.height * s).roundToInt().coerceIn(1, Doc.MAX_SIDE), name)

        // What to tell the person afterwards
        val skippedAdjustments = ArrayList<String>()
        val unsupportedFx = LinkedHashSet<String>()
        val approxBlends = LinkedHashSet<String>()
        var asPixels = 0
        var groupFx = 0

        /** How the layer clipped to it should be treated, per folder: what its base was. */
        private enum class Base { LAYER, FOLDER, SKIPPED }
        private val lastBase = HashMap<Long, Base>()
        var clippedToFolder = 0

        fun build(): Result {
            if (s < 1f) notes.add("Shrunk from ${f.width} × ${f.height} to ${doc.width} × ${doc.height} px (the biggest the app works with).")
            if (f.layers.isNotEmpty()) layers()
            // No layers (or only ones we can't show, like adjustment layers): the flattened picture
            if (doc.layers.none { !it.isGroup }) {
                doc.layers.clear()
                if (f.layers.isNotEmpty()) {
                    // The flattened picture already has the adjustments in it
                    skippedAdjustments.clear()
                    notes.add("Its layers couldn't be shown, so the flattened picture opened as one layer.")
                }
                compositeOnly()
            }
            if (doc.layers.isEmpty()) throw PsdException("There's nothing in this Photoshop file that can be shown.")
            doc.activeId = doc.layers.lastOrNull { !it.isGroup }?.id ?: doc.layers.last().id
            if (skippedAdjustments.isNotEmpty()) {
                val kinds = skippedAdjustments.groupingBy { it }.eachCount().entries.joinToString { (k, n) -> if (n > 1) "$k ×$n" else k }
                notes.add("Adjustment layers aren't brought in yet, so the colors may look different: $kinds.")
            }
            if (unsupportedFx.isNotEmpty()) notes.add("Layer effects not brought in: ${unsupportedFx.joinToString()}.")
            if (groupFx > 0) notes.add("Layer effects on folders aren't supported ($groupFx folder${if (groupFx > 1) "s" else ""}).")
            if (clippedToFolder > 0) notes.add("Clipping to a folder isn't supported: $clippedToFolder layer${if (clippedToFolder > 1) "s show" else " shows"} unclipped.")
            if (approxBlends.isNotEmpty()) notes.add("Blend modes shown as the nearest one we have: ${approxBlends.joinToString()}.")
            if (unsupportedColors > 0) notes.add("Some colors were saved as book (Pantone) colors and came in as black.")
            if (asPixels > 0) notes.add("$asPixels text or smart object layer${if (asPixels > 1) "s" else ""} came in as pixels (they look the same but can't be retyped or replaced).")
            return Result(doc, notes)
        }

        /** A file saved without layers: its flattened picture as one layer. */
        fun compositeOnly() {
            val rows = PsdReader.ChannelRows.composite(f)
            if (rows.isEmpty()) return
            val colors = colorChannels().size
            val color = rows.take(colors)
            // Extra channels are saved selections, unless the file says the first is transparency
            val alpha = if (f.mergedAlpha && rows.size > colors) rows[colors] else null
            val grid = decode(color, alpha, 0, 0, f.width, f.height)
            rows.forEach { it.close() }
            doc.layers.add(Layer(doc.newId(), LayerProps("Background"), grid))
        }

        fun colorChannels(): List<Int> = when (f.mode) {
            1 -> listOf(0)
            4 -> listOf(0, 1, 2, 3)
            else -> listOf(0, 1, 2)
        }

        fun layers() {
            // Folders: their end marker comes first (below their contents), the folder itself after
            val open = ArrayList<Long>()
            for (pl in f.layers) {
                val parent = open.lastOrNull() ?: 0L
                when {
                    pl.isGroupEnd -> open.add(doc.newId())
                    pl.isGroup -> {
                        val id = if (open.isNotEmpty()) open.removeAt(open.size - 1) else doc.newId()
                        val outer = open.lastOrNull() ?: 0L
                        if (pl.clipping != 1) lastBase[outer] = Base.FOLDER
                        val pass = pl.blendKey == "pass" || pl.sectionBlend == "pass"
                        if (pl.effects != null && hasEnabledFx(pl.effects!!)) groupFx++
                        val props = LayerProps(
                            name = pl.name.ifBlank { "Folder" },
                            visible = !pl.hidden,
                            opacity = pl.opacity / 255f,
                            blend = if (pass) Blend.NORMAL else blend(pl.sectionBlend ?: pl.blendKey),
                            parent = outer,
                            passThrough = pass,
                        )
                        val mask = pixelMask(pl, isFolder = true)
                        val g = Layer(id, props.copy(maskDisabled = mask?.second ?: false), TileGrid(alphaOnly = false), mask = mask?.first, kind = LayerKind.GROUP)
                        g.collapsed = pl.section == 2
                        doc.layers.add(g)
                    }
                    pl.adjustment != null -> {
                        skippedAdjustments.add(pl.adjustment!!)
                        if (pl.clipping != 1) lastBase[parent] = Base.SKIPPED
                    }
                    else -> {
                        // Clipped to something we can't use as a base: shown as it is
                        var clip = pl.clipping == 1
                        if (clip) {
                            when (lastBase[parent]) {
                                Base.SKIPPED -> clip = false
                                Base.FOLDER -> {
                                    clip = false
                                    clippedToFolder++
                                }
                                else -> Unit
                            }
                        } else {
                            lastBase[parent] = Base.LAYER
                        }
                        layer(pl, parent, clip)?.let { doc.layers.add(it) }
                    }
                }
            }
            // Folders whose own record was missing: their contents go to the top level
            if (open.isNotEmpty()) {
                val gone = open.toSet()
                for (l in doc.layers) if (l.props.parent in gone) l.props = l.props.copy(parent = 0L)
            }
        }

        fun layer(pl: PsdLayer, parent: Long, clip: Boolean): Layer? {
            if (pl.isText || pl.isSmart) asPixels++
            val vector = pl.vectorMask?.takeIf { !it.disabled && it.paths.isNotEmpty() }
            val grid: TileGrid = when {
                pl.hasPixels -> pixels(pl)
                pl.solidColor != null || pl.gradientFill != null -> synthesizedFill(pl, vector)
                else -> TileGrid(alphaOnly = false)
            }
            // The pixel mask, and for a pixel layer its vector mask too (a shape's outline is already in its pixels)
            var mask = pixelMask(pl, isFolder = false)
            val usedReal = pl.vectorMask != null && pl.realMask != null && pl.channels.any { it.id == -3 }
            if (vector != null && !pl.isFill && pl.hasPixels && (usedReal || pl.channels.none { it.id == -2 })) {
                mask = combine(mask, vectorMaskGrid(vector, pl))
            }
            val style = style(pl)
            val props = LayerProps(
                name = pl.name.ifBlank { "Layer" },
                visible = !pl.hidden,
                opacity = pl.opacity / 255f,
                fill = pl.fill / 255f,
                blend = blend(pl.blendKey),
                lockAlpha = pl.protection and 1 != 0,
                maskDisabled = mask?.second ?: false,
                style = if (s < 1f) style.scaled(s) else style,
                parent = parent,
                clip = clip,
            )
            return Layer(doc.newId(), props, grid, mask = mask?.first)
        }

        // ------------------------------------------------------------ pixels

        /** Photoshop pixels kept: the canvas and as much again around it (more would only use up memory). */
        val keep = Rect(-f.width, -f.height, 2 * f.width, 2 * f.height)

        /** A layer's stored pixels, as tiles at canvas position (shrunk when the file is too big). */
        fun pixels(pl: PsdLayer): TileGrid {
            val w = pl.width
            val h = pl.height
            val color = colorChannels().map { id -> pl.channels.firstOrNull { it.id == id }?.let { PsdReader.ChannelRows.layer(f, it, w, h) } }
            val alpha = pl.channels.firstOrNull { it.id == -1 }?.let { PsdReader.ChannelRows.layer(f, it, w, h) }
            val grid = decode(color, alpha, pl.left, pl.top, w, h)
            color.forEach { it?.close() }
            alpha?.close()
            return grid
        }

        /** Rows of [color] (and [alpha]) at Photoshop canvas ([left], [top]) into tiles. */
        fun decode(color: List<PsdReader.ChannelRows?>, alpha: PsdReader.ChannelRows?, left: Int, top: Int, w: Int, h: Int): TileGrid {
            val grid = TileGrid(alphaOnly = false)
            val sink = RowSink(grid)
            val rows = color.map { ByteArray(w) }
            val a = ByteArray(w)
            val argb = IntArray(w)
            val x0 = max(left, keep.left)
            val x1 = min(left + w, keep.right)
            for (y in 0 until h) {
                for (i in color.indices) color[i]?.next(rows[i]) ?: java.util.Arrays.fill(rows[i], 0)
                if (alpha != null) alpha.next(a) else java.util.Arrays.fill(a, -1)
                val cy = top + y
                if (cy < keep.top || cy >= keep.bottom || x1 <= x0) continue
                when (f.mode) {
                    1 -> for (x in 0 until w) {
                        val g = rows[0][x].toInt() and 0xFF
                        argb[x] = ((a[x].toInt() and 0xFF) shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    4 -> for (x in 0 until w) {
                        // Stored as 255 − ink; a simple conversion (no color profile)
                        val k = rows[3][x].toInt() and 0xFF
                        val r = (rows[0][x].toInt() and 0xFF) * k / 255
                        val g = (rows[1][x].toInt() and 0xFF) * k / 255
                        val b = (rows[2][x].toInt() and 0xFF) * k / 255
                        argb[x] = ((a[x].toInt() and 0xFF) shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    else -> for (x in 0 until w) {
                        argb[x] = ((a[x].toInt() and 0xFF) shl 24) or ((rows[0][x].toInt() and 0xFF) shl 16) or
                            ((rows[1][x].toInt() and 0xFF) shl 8) or (rows[2][x].toInt() and 0xFF)
                    }
                }
                sink.row(argb, left, x0, x1, cy)
            }
            sink.finish()
            grid.dropEmptyTiles()
            return grid
        }

        /**
         * Takes Photoshop rows (top to bottom) and writes them into [grid]: as they are, or shrunk
         * to the document's size by averaging the pixels that fall on each one (so even a huge
         * file never needs more than a row or two at full size).
         */
        inner class RowSink(private val grid: TileGrid) {
            private var curY = Int.MIN_VALUE
            private var minX = 0
            private var sa = DoubleArray(0)
            private var sr = DoubleArray(0)
            private var sg = DoubleArray(0)
            private var sb = DoubleArray(0)
            private var n = IntArray(0)
            private var out = IntArray(0)

            fun row(argb: IntArray, left: Int, x0: Int, x1: Int, cy: Int) {
                if (s >= 1f) {
                    writeRow(grid, argb, left, x0, x1, cy)
                    return
                }
                val ty = floor(cy * s).toInt()
                if (ty != curY) {
                    flush()
                    curY = ty
                    minX = floor(x0 * s).toInt()
                    val width = floor((x1 - 1) * s).toInt() - minX + 1
                    if (sa.size < width) {
                        sa = DoubleArray(width); sr = DoubleArray(width); sg = DoubleArray(width); sb = DoubleArray(width)
                        n = IntArray(width); out = IntArray(width)
                    }
                    java.util.Arrays.fill(sa, 0.0); java.util.Arrays.fill(sr, 0.0); java.util.Arrays.fill(sg, 0.0); java.util.Arrays.fill(sb, 0.0)
                    java.util.Arrays.fill(n, 0)
                }
                for (x in x0 until x1) {
                    val tx = floor(x * s).toInt() - minX
                    if (tx < 0 || tx >= n.size) continue
                    val p = argb[x - left]
                    val alpha = (p ushr 24).toDouble()
                    // Weighted by alpha, so see-through pixels don't darken the edges
                    sa[tx] += alpha
                    sr[tx] += ((p shr 16) and 0xFF) * alpha
                    sg[tx] += ((p shr 8) and 0xFF) * alpha
                    sb[tx] += (p and 0xFF) * alpha
                    n[tx]++
                }
            }

            private fun flush() {
                if (curY == Int.MIN_VALUE) return
                var last = -1
                for (i in n.indices) {
                    if (n[i] == 0) {
                        out[i] = 0
                        continue
                    }
                    last = i
                    val a = sa[i] / n[i]
                    if (sa[i] <= 0.0) {
                        out[i] = 0
                    } else {
                        val r = (sr[i] / sa[i]).roundToInt().coerceIn(0, 255)
                        val g = (sg[i] / sa[i]).roundToInt().coerceIn(0, 255)
                        val b = (sb[i] / sa[i]).roundToInt().coerceIn(0, 255)
                        out[i] = (a.roundToInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                if (last >= 0) writeRow(grid, out, minX, minX, minX + last + 1, curY)
            }

            fun finish() {
                flush()
                curY = Int.MIN_VALUE
            }
        }

        /** Puts canvas pixels [x0] until [x1] of row [cy] (from [row], which starts at canvas x [left]) into [grid]'s tiles. */
        fun writeRow(grid: TileGrid, row: IntArray, left: Int, x0: Int, x1: Int, cy: Int) {
            val n = TileGrid.SIZE
            val ty = Math.floorDiv(cy, n)
            val py = Math.floorMod(cy, n)
            var x = x0
            while (x < x1) {
                val tx = Math.floorDiv(x, n)
                val px = Math.floorMod(x, n)
                val len = min(n - px, x1 - x)
                val tile = grid.getOrCreate(TileGrid.key(tx, ty))
                tile.setPixels(row, x - left, len, px, py, len, 1)
                x += len
            }
        }

        // ------------------------------------------------------------ masks

        /** A Photoshop-canvas area in document pixels (after any shrinking), kept within [keep]. */
        fun docArea(r: Rect): RectF {
            val k = Rect(r)
            if (!k.intersect(keep)) return RectF()
            return RectF(k.left * s, k.top * s, k.right * s, k.bottom * s)
        }

        /**
         * The layer's pixel mask as "how hidden" tiles (and whether it's switched off). With a
         * vector mask, channel -2 is the outline drawn as pixels (already in a shape's pixels), so
         * only the separate pixel mask (-3) is used then.
         */
        fun pixelMask(pl: PsdLayer, isFolder: Boolean): Pair<TileGrid, Boolean>? {
            val vm = pl.vectorMask
            val (m, id) = when {
                vm != null && pl.realMask != null && pl.channels.any { it.id == -3 } -> pl.realMask!! to -3
                vm != null && (vm.disabled || pl.isFill || pl.channels.none { it.id == -2 }) -> return null
                pl.mask != null && pl.channels.any { it.id == -2 } -> pl.mask!! to -2
                else -> return null
            }
            val ch = pl.channels.first { it.id == id }
            val grid = TileGrid(alphaOnly = true)
            // Outside its area the mask is all one value: hidden everywhere else when that's black
            if (m.defaultColor < 128) {
                val area = Rect(0, 0, f.width, f.height)
                if (!isFolder && pl.width > 0 && pl.height > 0) area.union(Rect(pl.left, pl.top, pl.right, pl.bottom))
                if (m.width > 0 && m.height > 0) area.union(Rect(m.left, m.top, m.right, m.bottom))
                for (k in grid.keysIn(docArea(area))) grid.getOrCreate(k).eraseColor(Color.BLACK)
            }
            if (m.width > 0 && m.height > 0) {
                val rows = PsdReader.ChannelRows.layer(f, ch, m.width, m.height)
                if (rows != null) {
                    val sink = RowSink(grid)
                    val v = ByteArray(m.width)
                    val argb = IntArray(m.width)
                    val x0 = max(m.left, keep.left)
                    val x1 = min(m.right, keep.right)
                    for (y in 0 until m.height) {
                        rows.next(v)
                        val cy = m.top + y
                        if (cy < keep.top || cy >= keep.bottom || x1 <= x0) continue
                        for (x in 0 until m.width) argb[x] = (255 - (v[x].toInt() and 0xFF)) shl 24
                        sink.row(argb, m.left, x0, x1, cy)
                    }
                    sink.finish()
                    rows.close()
                }
            }
            if (grid.isEmpty) return null
            return grid to m.disabled
        }

        /** The outline of a vector mask, in document pixels. */
        fun path(vm: PsdVectorMask): Path {
            val w = f.width * s
            val h = f.height * s
            val p = Path()
            for (sp in vm.paths) {
                val k = sp.knots
                if (k.isEmpty()) continue
                p.moveTo((k[0].x * w).toFloat(), (k[0].y * h).toFloat())
                for (i in 1 until k.size) {
                    val a = k[i - 1]
                    val b = k[i]
                    p.cubicTo((a.outX * w).toFloat(), (a.outY * h).toFloat(), (b.inX * w).toFloat(), (b.inY * h).toFloat(), (b.x * w).toFloat(), (b.y * h).toFloat())
                }
                if (sp.closed) {
                    val a = k.last()
                    val b = k[0]
                    p.cubicTo((a.outX * w).toFloat(), (a.outY * h).toFloat(), (b.inX * w).toFloat(), (b.inY * h).toFloat(), (b.x * w).toFloat(), (b.y * h).toFloat())
                    p.close()
                }
            }
            // Shapes cut out of others (like the hole in an O)
            p.fillType = if (vm.invert) Path.FillType.INVERSE_EVEN_ODD else Path.FillType.EVEN_ODD
            return p
        }

        /** A vector mask as a "how hidden" mask: hidden outside the outline. */
        fun vectorMaskGrid(vm: PsdVectorMask, pl: PsdLayer): TileGrid {
            val grid = TileGrid(alphaOnly = true)
            val area = Rect(0, 0, f.width, f.height).apply { union(Rect(pl.left, pl.top, pl.right, pl.bottom)) }
            val p = path(vm)
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            for (k in grid.keysIn(docArea(area))) {
                val t = grid.getOrCreate(k)
                t.eraseColor(Color.BLACK)
                val c = Canvas(t)
                c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
                c.drawPath(p, clear)
            }
            return grid
        }

        /** Both masks: hidden where either hides. */
        fun combine(a: Pair<TileGrid, Boolean>?, b: TileGrid): Pair<TileGrid, Boolean> {
            if (a == null) return b to false
            val out = a.first
            val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN) }
            for ((k, t) in b.tiles) {
                val dst = out.tiles[k]
                if (dst == null) out.tiles[k] = t else Canvas(dst).drawBitmap(t, 0f, 0f, paint)
            }
            return out to a.second
        }

        // ------------------------------------------------------------ fill and shape layers

        /** A fill or shape layer saved without pixels: drawn from its color or gradient, inside its outline. */
        fun synthesizedFill(pl: PsdLayer, vector: PsdVectorMask?): TileGrid {
            val grid = TileGrid(alphaOnly = false)
            val outline = vector?.let { path(it) }
            val canvasArea = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
            val area = RectF(canvasArea)
            val bounds = RectF().also { b -> if (outline != null) outline.computeBounds(b, true) else b.set(area) }
            if (outline != null && vector?.invert != true) area.set(bounds)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val solid = pl.solidColor
            if (solid != null) {
                paint.color = color(solid["Clr "]) or 0xFF000000.toInt()
            } else {
                val g = pl.gradientFill!!
                val fx = gradient(g, g["Opct"]?.let { opacity(it) } ?: 1f, Blend.NORMAL)
                paint.shader = shader(fx, if (g["Algn"] == false) canvasArea else bounds)
            }
            if (area.isEmpty) return grid
            for (k in grid.keysIn(area)) {
                val t = grid.getOrCreate(k)
                val c = Canvas(t)
                c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
                if (outline != null) c.drawPath(outline, paint) else c.drawRect(area, paint)
            }
            grid.dropEmptyTiles()
            return grid
        }

        fun shader(fx: GradientFx, area: RectF): Shader {
            val colors = (if (fx.reverse) fx.colors.reversed() else fx.colors).toIntArray()
            val pos = fx.positions?.let { p -> (if (fx.reverse) p.reversed().map { 1f - it } else p).toFloatArray() }
            val cx = area.centerX()
            val cy = area.centerY()
            val a = fx.angle * PI / 180.0
            val ux = cos(a).toFloat()
            val uy = -sin(a).toFloat()
            // Half the length across the area in that direction, times the scale
            val half = ((abs(ux) * area.width() + abs(uy) * area.height()) / 2f * fx.scale).coerceAtLeast(1f)
            return when (fx.type) {
                GradientType.RADIAL -> RadialGradient(cx, cy, (hypot(area.width(), area.height()) / 2f * fx.scale).coerceAtLeast(1f), colors, pos, Shader.TileMode.CLAMP)
                GradientType.REFLECTED -> {
                    val mirrored = (colors.reversed() + colors.drop(1)).toIntArray()
                    val mpos = pos?.let { p -> (p.reversed().map { (1f - it) / 2f } + p.drop(1).map { 0.5f + it / 2f }).toFloatArray() }
                    LinearGradient(cx - ux * half, cy - uy * half, cx + ux * half, cy + uy * half, mirrored, mpos, Shader.TileMode.CLAMP)
                }
                else -> LinearGradient(cx - ux * half, cy - uy * half, cx + ux * half, cy + uy * half, colors, pos, Shader.TileMode.CLAMP)
            }
        }

        // ------------------------------------------------------------ settings

        fun blend(key: String): Blend = when (key) {
            "norm", "pass" -> Blend.NORMAL
            "dark" -> Blend.DARKEN
            "mul " -> Blend.MULTIPLY
            "idiv" -> Blend.COLOR_BURN
            "lite" -> Blend.LIGHTEN
            "scrn" -> Blend.SCREEN
            "div " -> Blend.COLOR_DODGE
            "lddg" -> Blend.LINEAR_DODGE
            "over" -> Blend.OVERLAY
            "sLit" -> Blend.SOFT_LIGHT
            "hLit" -> Blend.HARD_LIGHT
            "diff" -> Blend.DIFFERENCE
            "smud" -> Blend.EXCLUSION
            "hue " -> Blend.HUE
            "sat " -> Blend.SATURATION
            "colr" -> Blend.COLOR
            "lum " -> Blend.LUMINOSITY
            else -> {
                val (name, near) = when (key) {
                    "diss" -> "Dissolve" to Blend.NORMAL
                    "lbrn" -> "Linear Burn" to Blend.MULTIPLY
                    "dkCl" -> "Darker Color" to Blend.DARKEN
                    "lgCl" -> "Lighter Color" to Blend.LIGHTEN
                    "vLit" -> "Vivid Light" to Blend.HARD_LIGHT
                    "lLit" -> "Linear Light" to Blend.HARD_LIGHT
                    "pLit" -> "Pin Light" to Blend.HARD_LIGHT
                    "hMix" -> "Hard Mix" to Blend.HARD_LIGHT
                    "fsub" -> "Subtract" to Blend.DIFFERENCE
                    "fdiv" -> "Divide" to Blend.COLOR_DODGE
                    else -> key.trim() to Blend.NORMAL
                }
                approxBlends.add("$name → ${near.label}")
                near
            }
        }

        /** A blend mode from a layer style's descriptor ("Md  "). */
        fun fxBlend(v: Any?): Blend = when ((v as? PsdEnum)?.value) {
            null, "Nrml" -> Blend.NORMAL
            "Drkn" -> Blend.DARKEN
            "Mltp" -> Blend.MULTIPLY
            "CBrn" -> Blend.COLOR_BURN
            "Lghn" -> Blend.LIGHTEN
            "Scrn" -> Blend.SCREEN
            "CDdg" -> Blend.COLOR_DODGE
            "linearDodge" -> Blend.LINEAR_DODGE
            "Ovrl" -> Blend.OVERLAY
            "SftL" -> Blend.SOFT_LIGHT
            "HrdL" -> Blend.HARD_LIGHT
            "Dfrn" -> Blend.DIFFERENCE
            "Xclu" -> Blend.EXCLUSION
            "H   " -> Blend.HUE
            "Strt" -> Blend.SATURATION
            "Clr " -> Blend.COLOR
            "Lmns" -> Blend.LUMINOSITY
            "linearBurn" -> Blend.MULTIPLY
            else -> Blend.NORMAL
        }

        var unsupportedColors = 0

        fun color(v: Any?): Int {
            val m = v as? Map<*, *> ?: return Color.BLACK
            fun d(vararg keys: String): Double? = keys.firstNotNullOfOrNull { (m[it] as? Number)?.toDouble() ?: (m[it] as? PsdUnit)?.value }
            fun c255(x: Double) = (x * 255).roundToInt().coerceIn(0, 255)
            return when (m["_class"]) {
                "Grsc" -> {
                    // Gray as a percent of black
                    val g = (255 - (d("Gry ") ?: 0.0) * 2.55).roundToInt().coerceIn(0, 255)
                    Color.rgb(g, g, g)
                }
                "HSBC" -> Color.HSVToColor(floatArrayOf((d("H   ") ?: 0.0).toFloat(), ((d("Strt") ?: 0.0) / 100).toFloat(), ((d("Brgh") ?: 0.0) / 100).toFloat()))
                "CMYC" -> {
                    // Percent of each ink (a simple conversion, no color profile)
                    val c = (d("Cyn ") ?: 0.0) / 100
                    val mg = (d("Mgnt") ?: 0.0) / 100
                    val y = (d("Ylw ") ?: 0.0) / 100
                    val k = (d("Blck") ?: 0.0) / 100
                    Color.rgb(c255((1 - c) * (1 - k)), c255((1 - mg) * (1 - k)), c255((1 - y) * (1 - k)))
                }
                "LbCl" -> lab(d("Lmnc") ?: 0.0, d("A   ") ?: 0.0, d("B   ") ?: 0.0)
                "BkCl" -> {
                    unsupportedColors++
                    Color.BLACK
                }
                else -> {
                    val r = d("Rd  ", "redFloat")
                    val g = d("Grn ", "greenFloat")
                    val b = d("Bl  ", "blueFloat")
                    // Newer files: 0..1 floats; older: 0..255
                    val scale = if (m.containsKey("redFloat")) 255.0 else 1.0
                    Color.rgb(((r ?: 0.0) * scale).roundToInt().coerceIn(0, 255), ((g ?: 0.0) * scale).roundToInt().coerceIn(0, 255), ((b ?: 0.0) * scale).roundToInt().coerceIn(0, 255))
                }
            }
        }

        /** CIE Lab (D50, like Photoshop) to sRGB. */
        fun lab(l: Double, a: Double, b: Double): Int {
            val fy = (l + 16) / 116
            val fx = fy + a / 500
            val fz = fy - b / 200
            fun inv(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116) / 7.787
            // D50 white
            val x = 0.9642 * inv(fx)
            val y = 1.0 * inv(fy)
            val z = 0.8249 * inv(fz)
            // D50 XYZ to linear sRGB (Bradford-adapted)
            val rl = 3.1339 * x - 1.6169 * y - 0.4906 * z
            val gl = -0.9788 * x + 1.9161 * y + 0.0335 * z
            val bl = 0.0719 * x - 0.2290 * y + 1.4052 * z
            fun gamma(c: Double): Int {
                val v = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
                return (v * 255).roundToInt().coerceIn(0, 255)
            }
            return Color.rgb(gamma(rl), gamma(gl), gamma(bl))
        }

        fun opacity(v: Any?): Float = (((v as? PsdUnit)?.value ?: (v as? Number)?.toDouble() ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)

        fun px(v: Any?, k: Double): Float = (((v as? PsdUnit)?.value ?: (v as? Number)?.toDouble() ?: 0.0) * k).toFloat()

        fun num(v: Any?, def: Double): Double = (v as? PsdUnit)?.value ?: (v as? Number)?.toDouble() ?: def

        fun hasEnabledFx(fx: Map<String, Any?>): Boolean =
            fx["masterFXSwitch"] != false && fx.values.any { (it as? Map<*, *>)?.get("enab") == true || (it as? List<*>)?.any { e -> (e as? Map<*, *>)?.get("enab") == true } == true }

        /** A gradient descriptor ("Grad" inside a gradient overlay, stroke or fill layer) as ours. */
        fun gradient(d: Map<String, Any?>, opacity: Float, blend: Blend): GradientFx {
            val g = d["Grad"] as? Map<*, *>
            val stops = (g?.get("Clrs") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            val trans = (g?.get("Trns") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            val type = when ((d["Type"] as? PsdEnum)?.value) {
                "Rdl " -> GradientType.RADIAL
                "Rflc" -> GradientType.REFLECTED
                "Lnr " -> GradientType.LINEAR
                null -> GradientType.LINEAR
                else -> {
                    unsupportedFx.add("angle and diamond gradients (shown as linear)")
                    GradientType.LINEAR
                }
            }
            if (stops.size < 2) {
                if (g != null && g["GrdF"] is PsdEnum && (g["GrdF"] as PsdEnum).value == "ClNs") unsupportedFx.add("noise gradients")
                return GradientFx(on = true, type = type, opacity = opacity, blend = blend)
            }
            fun loc(m: Map<*, *>): Float = ((m["Lctn"] as? Number)?.toDouble() ?: 0.0).toFloat() / 4096f
            // Opacity along the gradient, from its transparency stops
            val tl = trans.map { loc(it) to opacity(it["Opct"]) }.sortedBy { it.first }
            fun alphaAt(p: Float): Float {
                if (tl.isEmpty()) return 1f
                if (p <= tl.first().first) return tl.first().second
                if (p >= tl.last().first) return tl.last().second
                for (i in 1 until tl.size) {
                    val (p1, a1) = tl[i]
                    val (p0, a0) = tl[i - 1]
                    if (p <= p1) return if (p1 - p0 < 1e-6f) a1 else a0 + (a1 - a0) * (p - p0) / (p1 - p0)
                }
                return 1f
            }
            val sorted = stops.sortedBy { loc(it) }
            val colors = sorted.map { st ->
                val c = color(st["Clr "])
                val a = (alphaAt(loc(st)) * 255).roundToInt().coerceIn(0, 255)
                (a shl 24) or (c and 0xFFFFFF)
            }
            return GradientFx(
                on = true,
                colors = colors,
                type = type,
                // Photoshop's gradient overlay angle is where it points to; ours where it runs (same: 90 = bottom to top)
                angle = num(d["Angl"], 90.0).toFloat(),
                scale = (num(d["Scl "], 100.0) / 100.0).toFloat(),
                reverse = d["Rvrs"] == true,
                opacity = opacity,
                positions = sorted.map { loc(it) },
                blend = blend,
            )
        }

        /** Photoshop's layer style, as far as ours goes. */
        fun style(pl: PsdLayer): LayerStyle {
            val single = pl.effects
            val multi = pl.multiEffects
            val fx = single ?: multi ?: return LayerStyle()
            if (fx["masterFXSwitch"] == false) return LayerStyle()
            // (Photoshop also stores a "Scl " percentage, but draws the values as they are)
            val k = 1.0
            // Photoshop's Size is how far the blur reaches; ours is the blur's radius, which reaches further
            val blurK = k * 0.7
            fun pick(key: String, multiKey: String): Map<String, Any?>? {
                @Suppress("UNCHECKED_CAST")
                val one = (single?.get(key) ?: multi?.get(key)) as? Map<String, Any?>
                if (one != null) return one
                val list = multi?.get(multiKey) as? List<*> ?: return null
                @Suppress("UNCHECKED_CAST")
                val on = list.mapNotNull { it as? Map<String, Any?> }.filter { it["enab"] == true }
                if (on.size > 1) unsupportedFx.add("more than one of the same effect (the first is used)")
                return on.firstOrNull() ?: list.firstOrNull() as? Map<String, Any?>
            }
            fun angle(d: Map<String, Any?>): Float = (if (d["uglg"] == true) f.globalAngle else num(d["lagl"], f.globalAngle)).toFloat()
            var st = LayerStyle()
            pick("DrSh", "dropShadowMulti")?.let { d ->
                st = st.copy(dropShadow = ShadowFx(
                    on = d["enab"] == true, color = color(d["Clr "]), opacity = opacity(d["Opct"]), angle = angle(d),
                    distance = px(d["Dstn"], k), size = px(d["blur"], blurK), blend = fxBlend(d["Md  "]),
                ))
            }
            pick("IrSh", "innerShadowMulti")?.let { d ->
                st = st.copy(innerShadow = ShadowFx(
                    on = d["enab"] == true, color = color(d["Clr "]), opacity = opacity(d["Opct"]), angle = angle(d),
                    distance = px(d["Dstn"], k), size = px(d["blur"], blurK), blend = fxBlend(d["Md  "]),
                ))
            }
            pick("OrGl", "outerGlowMulti")?.let { d ->
                val c = if (d["Clr "] != null) color(d["Clr "]) else {
                    (d["Grad"] as? Map<*, *>)?.let { unsupportedFx.add("gradient glows (the first color is used)") }
                    gradient(d, 1f, Blend.NORMAL).colors.firstOrNull() ?: Color.WHITE
                }
                st = st.copy(outerGlow = GlowFx(on = d["enab"] == true, color = c, opacity = opacity(d["Opct"]), size = px(d["blur"], blurK), blend = fxBlend(d["Md  "])))
            }
            pick("FrFX", "frameFXMulti")?.let { d ->
                val pos = when ((d["Styl"] as? PsdEnum)?.value) {
                    "InsF" -> StrokePosition.INSIDE
                    "CtrF" -> StrokePosition.CENTER
                    else -> StrokePosition.OUTSIDE
                }
                val fill = (d["PntT"] as? PsdEnum)?.value
                if (fill == "Ptrn" && d["enab"] == true) unsupportedFx.add("pattern strokes (shown in black)")
                st = st.copy(stroke = StrokeFx(
                    on = d["enab"] == true, size = px(d["Sz  "], k).coerceAtLeast(1f), position = pos,
                    color = if (d["Clr "] != null) color(d["Clr "]) else Color.BLACK, opacity = opacity(d["Opct"]),
                    useGradient = fill == "GrFl", gradient = if (fill == "GrFl") gradient(d, 1f, Blend.NORMAL) else GradientFx(on = true),
                    blend = fxBlend(d["Md  "]),
                ))
            }
            pick("SoFi", "solidFillMulti")?.let { d ->
                st = st.copy(colorOverlay = OverlayFx(on = d["enab"] == true, color = color(d["Clr "]), opacity = opacity(d["Opct"]), blend = fxBlend(d["Md  "])))
            }
            pick("GrFl", "gradientFillMulti")?.let { d ->
                st = st.copy(gradientFill = gradient(d, opacity(d["Opct"]), fxBlend(d["Md  "])).copy(on = d["enab"] == true))
            }
            pick("ebbl", "bevelEmbossMulti")?.let { d ->
                val style = when ((d["bvlS"] as? PsdEnum)?.value) {
                    "OtrB" -> BevelStyle.OUTER
                    "Embs" -> BevelStyle.EMBOSS
                    "PlEb" -> BevelStyle.PILLOW
                    else -> BevelStyle.INNER
                }
                st = st.copy(bevel = BevelFx(
                    on = d["enab"] == true, style = style, depth = (num(d["srgR"], 100.0) / 100.0).toFloat().coerceIn(0.1f, 5f),
                    up = (d["bvlD"] as? PsdEnum)?.value != "Out ", size = px(d["blur"], k), soften = px(d["Sftn"], k),
                    angle = angle(d), altitude = (if (d["uglg"] == true) f.globalAltitude else num(d["Lald"], 30.0)).toFloat(),
                    highlight = color(d["hglC"]), highlightOpacity = opacity(d["hglO"]), shadow = color(d["sdwC"]), shadowOpacity = opacity(d["sdwO"]),
                    highlightBlend = fxBlend(d["hglM"]), shadowBlend = fxBlend(d["sdwM"]),
                ))
            }
            // Effects we don't have
            for ((key, label) in listOf("IrGl" to "Inner Glow", "ChFX" to "Satin", "patternFill" to "Pattern Overlay")) {
                val d = (single?.get(key) ?: multi?.get(key)) as? Map<*, *>
                if (d?.get("enab") == true) unsupportedFx.add(label)
            }
            return st
        }
    }
}
