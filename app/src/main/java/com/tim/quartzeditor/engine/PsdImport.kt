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
 * Turns a Photoshop file into a document: pixel layers, folders (any depth, with their styles),
 * masks, blend modes, opacity and fill, clipping (to layers and folders), hidden layers, names,
 * layer styles, adjustment layers, shape layers (as shapes), fill layers, text (with its styles)
 * and smart objects (linked copies stay linked). What doesn't come across exactly is listed in
 * [Result.notes].
 */
object PsdImport {

    class Result(val doc: Doc, val notes: List<String>)

    /** Reads [file] (a .psd or .psb, or a TIFF, copied somewhere readable). Runs in the background. */
    fun read(file: File, name: String): Result {
        val raf = RandomAccessFile(file, "r")
        val buf = try {
            if (raf.length() > Int.MAX_VALUE) throw PsdException("This file is over 2 GB, too big to open here.")
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        } finally {
            raf.close()
        }
        val head = ByteArray(4)
        buf.duplicate().get(head)
        return if (TiffReader.isTiff(head)) fromTiff(buf, name, 0) else fromBuffer(buf, name, 0)
    }

    /** A Photoshop file held in [buf] ([depth]: how many smart objects deep this file is). */
    private fun fromBuffer(buf: java.nio.ByteBuffer, name: String, depth: Int): Result {
        val f = PsdReader.read(buf)
        return Builder(f, name, depth).build()
    }

    /** JPEG pieces of a TIFF, decoded by Android. */
    val androidJpeg = TiffJpeg { data, w, h ->
        val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return@TiffJpeg null
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, min(w, bmp.width), min(h, bmp.height))
        bmp.recycle()
        out
    }

    /** A TIFF's picture, decoded straight from the file. */
    class TiffSource(val buf: java.nio.ByteBuffer, val info: TiffInfo)

    /**
     * A TIFF held in [buf]: with Photoshop's layers when it has them (a layered TIFF), otherwise
     * its picture as one layer.
     */
    private fun fromTiff(buf: java.nio.ByteBuffer, name: String, depth: Int): Result {
        val info = try {
            TiffReader.info(buf)
        } catch (e: TiffRawException) {
            throw e
        } catch (e: TiffException) {
            throw PsdException(e.message ?: "This TIFF couldn't be read.")
        } catch (e: IndexOutOfBoundsException) {
            throw PsdException("This TIFF file looks damaged.")
        }
        val mode = when (info.photometric) {
            0, 1 -> 1
            5 -> 4
            8, 9 -> 9
            else -> 3
        }
        // Lab layers aren't read; their flattened picture is
        val layered = info.layerData?.takeIf { mode != 9 }?.let { at ->
            try {
                PsdReader.readTiff(buf.duplicate().order(info.order), at, info.resources, info.width, info.height, info.bits, mode, info.samples)
            } catch (e: Exception) {
                null
            }
        }
        val readable = TiffReader.unsupported(info)
        if (layered == null && readable != null) throw PsdException(readable)
        // Turned a quarter (a camera's portrait shot): the picture is as tall as the file is wide
        val turned = layered == null && info.orientation in 5..8
        val f = layered ?: PsdFile(buf, 1, if (turned) info.height else info.width, if (turned) info.width else info.height, 8, 3, info.samples, emptyList(), -1L, 120.0, 30.0)
        return Builder(f, name, depth, if (readable == null) TiffSource(buf, info) else null).build()
    }

    private class Builder(val f: PsdFile, name: String, val depth: Int = 0, val tiff: TiffSource? = null) {
        val notes = ArrayList<String>()
        /** Shrinks pictures bigger than the app's limit. */
        val s: Float = Doc.fitScale(f.width, f.height)
        val doc = Doc.fitted(f.width, f.height).let { (w, h) -> Doc(w, h, name) }

        // What to tell the person afterwards
        val skippedAdjustments = ArrayList<String>()
        val unsupportedFx = LinkedHashSet<String>()
        val approxBlends = LinkedHashSet<String>()

        /** How the layer clipped to it should be treated, per folder: what its base was. */
        private enum class Base { LAYER, FOLDER, SKIPPED }
        private val lastBase = HashMap<Long, Base>()

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
                notes.add("Adjustment layers we don't have were left out, so the colors may look different: $kinds.")
            }
            if (unsupportedFx.isNotEmpty()) notes.add("Layer effects not brought in: ${unsupportedFx.joinToString()}.")
            if (approxBlends.isNotEmpty()) notes.add("Blend modes shown as the nearest one we have: ${approxBlends.joinToString()}.")
            if (unsupportedColors > 0) notes.add("Some layer effect colors were saved as book (Pantone) colors and came in as black (fill colors are read from the layers' pixels).")
            if (textAsPixels > 0) notes.add("$textAsPixels text layer${if (textAsPixels > 1) "s" else ""} without text came in as pixels.")
            if (warpedText > 0) notes.add("$warpedText warped text layer${if (warpedText > 1) "s keep" else " keeps"} Photoshop's look until edited; after that our warp of the same name is a close match.")
            if (mixedText > 0) notes.add("$mixedText text layer${if (mixedText > 1) "s have" else " has"} several styles that couldn't be read separately: the first one is used for all of it.")
            if (approxAdjust.isNotEmpty()) notes.add("Adjustment settings shown as close as we can: ${approxAdjust.joinToString()}.")
            if (shapesAsPixels > 0) notes.add("$shapesAsPixels shape${if (shapesAsPixels > 1) "s" else ""} with a gradient or pattern outline (or an inverted outline) came in as pixels.")
            if (smartFromPixels.isNotEmpty()) notes.add("${smartFromPixels.size} smart object${if (smartFromPixels.size > 1) "s" else ""} kept as smart layers, using what you see as their contents (${smartFromPixels.distinct().joinToString()}). Replace Contents can put the original file back in.")
            if (smartWarped > 0) notes.add("$smartWarped smart layer${if (smartWarped > 1) "s keep their warps" else " keeps its warp"}; Photoshop's look stays until the layer is redrawn, then our warp of the same shape is a close match.")
            if (smartFiltered > 0) notes.add("$smartFiltered smart filter${if (smartFiltered > 1) "s" else ""} kept, still editable.")
            if (f.mode == 8) notes.add("A Duotone file: shown in gray (its inks aren't read).")
            if (f.mode == 9) notes.add("A Lab color file: its colors were turned into RGB; adjustment layers made for Lab may look different.")
            if (f.mode == 0) notes.add("A Bitmap (black and white) file: opened as gray.")
            if (fonts.isNotEmpty()) notes.add("Fonts not on this device are shown as the original pixels until you edit the text; then a close match is used: ${fonts.joinToString()}.")
            return Result(doc, notes)
        }

        /** A file saved without layers: its flattened picture as one layer. */
        fun compositeOnly() {
            if (tiff != null && tiff.info.orientation != 1 && f.layers.isEmpty()) {
                // A TIFF saved sideways or mirrored: read (at the document's size), then turned upright
                val i = tiff.info
                val w = (i.width * s).roundToInt().coerceAtLeast(1)
                val h = (i.height * s).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                if (w == i.width && h == i.height) {
                    TiffReader.rows(tiff.buf, i, androidJpeg) { y, row -> bmp.setPixels(row, 0, w, 0, y, w, 1) }
                } else {
                    val shrink = RowShrinker(i.width, i.height, w, h) { y, row -> bmp.setPixels(row, 0, w, 0, y, w, 1) }
                    TiffReader.rows(tiff.buf, i, androidJpeg) { y, row -> shrink.row(y, row) }
                    shrink.finish()
                }
                val m = Matrix()
                when (i.orientation) {
                    2 -> m.postScale(-1f, 1f)
                    3 -> m.postRotate(180f)
                    4 -> m.postScale(1f, -1f)
                    5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                    6 -> m.postRotate(90f)
                    7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                    8 -> m.postRotate(270f)
                }
                val upright = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
                if (upright !== bmp) bmp.recycle()
                val grid = TileGrid.fromBitmap(upright, 0, 0, alphaOnly = false).also { it.dropEmptyTiles() }
                upright.recycle()
                doc.layers.add(Layer(doc.newId(), LayerProps("Background"), grid))
                return
            }
            if (tiff != null) {
                // A TIFF: its own picture
                val grid = TileGrid(alphaOnly = false)
                val sink = RowSink(grid)
                val x0 = max(0, keep.left)
                val x1 = min(f.width, keep.right)
                TiffReader.rows(tiff.buf, tiff.info, androidJpeg) { y, row ->
                    if (y >= keep.top && y < keep.bottom) sink.row(row, 0, x0, x1, y)
                }
                sink.finish()
                grid.dropEmptyTiles()
                doc.layers.add(Layer(doc.newId(), LayerProps("Background"), grid))
                return
            }
            if (f.compositeOffset < 0) return
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
            // Bitmap, Grayscale, Indexed and Duotone: one channel
            0, 1, 2, 8 -> listOf(0)
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
                        val style = style(pl)
                        val props = LayerProps(
                            name = pl.name.ifBlank { "Folder" },
                            visible = !pl.hidden,
                            opacity = pl.opacity / 255f,
                            blend = if (pass) Blend.NORMAL else blend(pl.sectionBlend ?: pl.blendKey),
                            parent = outer,
                            passThrough = pass,
                            style = if (s < 1f) style.scaled(s) else style,
                            blendIf = blendIf(pl),
                        )
                        val mask = pixelMask(pl, isFolder = true)
                        val g = Layer(id, props.copy(maskDisabled = mask?.second ?: false), TileGrid(alphaOnly = false), mask = mask?.first, kind = LayerKind.GROUP)
                        g.collapsed = pl.section == 2
                        doc.layers.add(g)
                    }
                    pl.adjustment != null -> {
                        // Clipped to something we can't use as a base: shown as it is
                        var clip = pl.clipping == 1
                        if (clip && lastBase[parent] == Base.SKIPPED) clip = false
                        // An adjustment layer can't be a clipping base here
                        if (pl.clipping != 1) lastBase[parent] = Base.SKIPPED
                        adjustment(pl, parent, clip)?.let { doc.layers.add(it) } ?: skippedAdjustments.add(pl.adjustment!!)
                    }
                    else -> {
                        // Clipped to something we can't use as a base: shown as it is
                        var clip = pl.clipping == 1
                        if (clip) {
                            if (lastBase[parent] == Base.SKIPPED) clip = false
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

        /** Smart objects' contents already read, by their file's id. */
        val contentsCache = HashMap<String, Doc>()
        var textAsPixels = 0
        var warpedText = 0
        var mixedText = 0
        val smartFromPixels = ArrayList<String>()
        var smartWarped = 0
        var smartFiltered = 0
        val fonts = LinkedHashSet<String>()

        fun layer(pl: PsdLayer, parent: Long, clip: Boolean): Layer? {
            // A book (Pantone) color: its RGB isn't in the file, but the layer's own pixels show it
            bookFallback = if ((pl.solidColor?.get("Clr ") as? Map<*, *>)?.get("_class") == "BkCl" && pl.hasPixels) averageColor(pixels(pl)) else null
            val vector = pl.vectorMask?.takeIf { !it.disabled && it.paths.isNotEmpty() }
            // A shape layer stays a shape (drawn here from its outline)
            val shape = if (vector != null && pl.isFill) shapeSpec(pl, vector) else null
            val grid: TileGrid = when {
                shape != null -> VectorArt.rasterize(LayerProps("", shape = shape), VectorArt.clipFor(0, 0, doc.width, doc.height))
                pl.hasPixels -> pixels(pl)
                pl.solidColor != null || pl.gradientFill != null || pl.patternFill != null -> synthesizedFill(pl, vector)
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
                blendIf = blendIf(pl),
            )
            // Text stays text, and smart objects stay smart (showing Photoshop's pixels until changed)
            pl.text?.let { t -> textProps(pl, t, props)?.let { return Layer(doc.newId(), it, grid, mask = mask?.first, kind = LayerKind.TEXT) } }
            if (pl.isSmart) smartProps(pl, props, grid)?.let { return Layer(doc.newId(), it, grid, mask = mask?.first, kind = LayerKind.SMART) }
            if (shape != null) return Layer(doc.newId(), props.copy(shape = shape), grid, mask = mask?.first, kind = LayerKind.SHAPE)
            return Layer(doc.newId(), props, grid, mask = mask?.first)
        }

        // ------------------------------------------------------------ shape layers

        var shapesAsPixels = 0

        /** A vector mask's outlines in document pixels, as points (Photoshop keeps them as fractions of the canvas). */
        fun parts(vm: PsdVectorMask): List<ShapePart> {
            val w = f.width * s
            val h = f.height * s
            return vm.paths.filter { it.knots.isNotEmpty() }.map { sp ->
                ShapePart(sp.knots.map { k ->
                    val x = (k.x * w).toFloat()
                    val y = (k.y * h).toFloat()
                    PenPoint(x, y, (k.outX * w).toFloat() - x, (k.outY * h).toFloat() - y, (k.inX * w).toFloat() - x, (k.inY * h).toFloat() - y)
                }, sp.closed, sp.op)
            }
        }

        /**
         * A Photoshop shape layer (a color fill inside a vector outline) as our shape: a rectangle,
         * rounded rectangle or ellipse when it was drawn as one, its points when it's one outline,
         * or a custom shape for several (joined like Photoshop's path operations). Null keeps it as
         * pixels (gradient and pattern fills, dashed outlines).
         */
        fun shapeSpec(pl: PsdLayer, vm: PsdVectorMask): ShapeSpec? {
            val solid = pl.solidColor
            val stroke = pl.vectorStroke
            val strokeOn = stroke?.get("strokeEnabled") == true
            val fillOn = stroke?.get("fillEnabled") != false
            // Gradient and pattern fills stay live too
            val gradFill = pl.gradientFill?.takeIf { solid == null }?.let { g -> gradient(g, g["Opct"]?.let { opacity(it) } ?: 1f, Blend.NORMAL) }
            val patFill = pl.patternFill?.takeIf { solid == null && gradFill == null }?.let { pf ->
                patternKey(pf["Ptrn"] as? Map<*, *>)?.let { key -> PatternFx(true, key, Color.BLACK, Color.WHITE, (num(pf["Scl "], 100.0) / 100.0).toFloat() * s) }
            }
            if ((solid == null && gradFill == null && patFill == null && fillOn) || vm.invert) {
                shapesAsPixels++
                return null
            }
            // The outline: a color only (gradient and pattern outlines stay pixels), solid lines only
            var strokeColor: Int? = null
            var strokeWidth = 0f
            var align = StrokeAlign.CENTER
            var dash: List<Float>? = null
            if (strokeOn && stroke != null) {
                val content = stroke["strokeStyleContent"] as? Map<*, *>
                if (content?.get("Clr ") == null) {
                    shapesAsPixels++
                    return null
                }
                // Dashes: lengths in outline widths, like ours
                dash = (stroke["strokeStyleLineDashSet"] as? List<*>)?.mapNotNull { v ->
                    ((v as? Number)?.toDouble() ?: (v as? PsdUnit)?.value)?.toFloat()
                }?.takeIf { it.isNotEmpty() }
                val op = opacity(stroke["strokeStyleOpacity"])
                strokeWidth = px(stroke["strokeStyleLineWidth"], s.toDouble())
                // A zero-width outline draws nothing
                if (strokeWidth > 0.01f) strokeColor = (color(content["Clr "]) and 0xFFFFFF) or ((op * 255).roundToInt() shl 24)
                align = when ((stroke["strokeStyleLineAlignment"] as? PsdEnum)?.value) {
                    "strokeStyleAlignInside" -> StrokeAlign.INSIDE
                    "strokeStyleAlignOutside" -> StrokeAlign.OUTSIDE
                    else -> StrokeAlign.CENTER
                }
            }
            val fill = if (fillOn && solid != null) color(solid["Clr "], bookFallback) or 0xFF000000.toInt() else if (fillOn) Color.BLACK else null
            val ps = parts(vm)
            if (ps.isEmpty()) return null
            val all = ShapeSpec.customPath(ps, evenOdd = true)
            val b = RectF().also { all.computeBounds(it, true) }
            if (b.width() <= 0f && b.height() <= 0f) return null
            val base = ShapeSpec(
                ShapeKind.RECT, fill = fill, stroke = strokeColor, strokeWidth = if (strokeColor != null) strokeWidth else 6f, strokeAlign = align,
                fillGradient = gradFill.takeIf { fillOn }, fillPattern = patFill.takeIf { fillOn }, dash = dash,
            )
            // Drawn as a rectangle, rounded rectangle or ellipse (and not turned): kept as one
            val origin = (pl.vectorOrigin?.get("keyDescriptorList") as? List<*>)?.singleOrNull() as? Map<*, *>
            val type = (origin?.get("keyOriginType") as? Number)?.toInt()
            if (ps.size == 1 && ps[0].closed && ps[0].op != 2 && type != null) {
                val m = translation(b.left, b.top)
                val pts = ps[0].points
                when (type) {
                    1 -> if (pts.size == 4 && pts.all { onEdges(it, b, 0f, 0f) }) {
                        return base.copy(kind = ShapeKind.RECT, w = b.width(), h = b.height(), matrix = m)
                    }
                    2 -> {
                        val radii = (origin["keyOriginRRectRadii"] as? Map<*, *>)?.let { r ->
                            listOf("topRight", "topLeft", "bottomLeft", "bottomRight").map { px(r[it], s.toDouble()) }
                        }
                        if (radii != null && radii.max() - radii.min() < 0.5f) {
                            val r = radii.first().coerceIn(0f, min(b.width(), b.height()) / 2f)
                            if (pts.all { onEdges(it, b, r, 0f) || onEdges(it, b, 0f, r) }) {
                                return base.copy(kind = ShapeKind.RECT, w = b.width(), h = b.height(), radius = r, matrix = m)
                            }
                        }
                    }
                    5 -> if (pts.size == 4 && pts.all { onEdges(it, b, b.width() / 2f, 0f) || onEdges(it, b, 0f, b.height() / 2f) }) {
                        return base.copy(kind = ShapeKind.ELLIPSE, w = b.width(), h = b.height(), matrix = m)
                    }
                }
            }
            val left = b.left
            val top = b.top
            fun local(pts: List<PenPoint>) = pts.map { it.copy(x = it.x - left, y = it.y - top) }
            // One outline: its points can be edited with the pen
            if (ps.size == 1 && ps[0].op != 2) {
                return base.copy(kind = ShapeKind.PATH, w = b.width(), h = b.height(), path = local(ps[0].points), closed = ps[0].closed, matrix = translation(left, top))
            }
            // Several: a custom shape in its own box. Subtracting first starts from the whole canvas.
            val firstOp = ps.firstOrNull { it.op >= 0 }?.op ?: 1
            val box = if (firstOp == 2) RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat()) else b
            return base.copy(
                kind = ShapeKind.CUSTOM, w = box.width(), h = box.height(), viewW = box.width(), viewH = box.height(),
                parts = ps.map { it.copy(points = it.points.map { p -> p.copy(x = p.x - box.left, y = p.y - box.top) }) },
                evenOdd = true, shapeName = pl.name, matrix = translation(box.left, box.top),
            )
        }

        /**
         * Whether point [p] is where an upright shape in box [b] has its points: on a left or right
         * edge [inY] in from a corner (vertically), and on a top or bottom edge [inX] in from one
         * (a rectangle's corners are 0 in; a rounded one's are the radius in; an ellipse's are halfway).
         */
        fun onEdges(p: PenPoint, b: RectF, inX: Float, inY: Float): Boolean {
            val tol = max(0.75f, max(b.width(), b.height()) * 0.003f)
            fun near(a: Float, v: Float) = abs(a - v) < tol
            val xOk = near(p.x, b.left + inX) || near(p.x, b.right - inX)
            val yOk = near(p.y, b.top + inY) || near(p.y, b.bottom - inY)
            return xOk && yOk
        }

        // ------------------------------------------------------------ adjustment layers

        val approxAdjust = LinkedHashSet<String>()

        /** A Photoshop adjustment layer as ours, or null for kinds we don't have. */
        fun adjustment(pl: PsdLayer, parent: Long, clip: Boolean): Layer? {
            val a = PsdAdjust.parse(pl) ?: return null
            val fx: FxParams = when (a) {
                is PsdAdjust.Levels -> {
                    fun ch(i: Int): LevelsChannel {
                        val r = a.records.getOrNull(i) ?: return LevelsChannel()
                        return LevelsChannel(r[0] / 255f, r[1].coerceAtLeast(r[0] + 1) / 255f, (r[4] / 100f).coerceIn(0.1f, 9.99f), r[2] / 255f, r[3] / 255f)
                    }
                    FxParams(kind = FxKind.LEVELS, levels = if (f.mode != 3) Levels(listOf(ch(0), LevelsChannel(), LevelsChannel(), LevelsChannel())) else Levels(List(4) { ch(it) }))
                }
                is PsdAdjust.Curves -> {
                    val chans = List(4) { i ->
                        a.channels[i]?.map { (x, y) -> CurvePoint(x / 255f, y / 255f) }?.sortedBy { it.x }?.takeIf { it.size >= 2 } ?: Curves.LINE
                    }
                    FxParams(kind = FxKind.CURVES, curves = Curves(if (f.mode != 3) listOf(chans[0], Curves.LINE, Curves.LINE, Curves.LINE) else chans))
                }
                is PsdAdjust.BrightnessContrast -> {
                    if (a.legacy) approxAdjust.add("legacy Brightness/Contrast")
                    FxParams(adjust = mapOf(
                        Adjust.BRIGHTNESS to (a.brightness / 100f).coerceIn(-1f, 1f),
                        Adjust.CONTRAST to (a.contrast / 100f).coerceIn(-1f, 1f),
                    ))
                }
                is PsdAdjust.Exposure -> {
                    if (abs(a.offset) > 0.005f || abs(a.gamma - 1f) > 0.01f) approxAdjust.add("Exposure's offset and gamma")
                    FxParams(adjust = mapOf(Adjust.EXPOSURE to (a.exposure / 2f).coerceIn(-1f, 1f)))
                }
                is PsdAdjust.Vibrance -> FxParams(kind = FxKind.VIBRANCE, adjust = mapOf(
                    Adjust.VIBRANCE to (a.vibrance / 100f).coerceIn(-1f, 1f),
                    Adjust.SATURATION to (a.saturation / 100f).coerceIn(-1f, 1f),
                ))
                is PsdAdjust.HueSat -> {
                    fun shift(v: IntArray, off: Int) = HueShift((v[off] / 180f).coerceIn(-1f, 1f), (v[off + 1] / 100f).coerceIn(-1f, 1f), (v[off + 2] / 100f).coerceIn(-1f, 1f))
                    val defaults = listOf(intArrayOf(315, 345, 15, 45), intArrayOf(15, 45, 75, 105), intArrayOf(75, 105, 135, 165), intArrayOf(135, 165, 195, 225), intArrayOf(195, 225, 255, 285), intArrayOf(255, 285, 315, 345))
                    if (a.ranges.withIndex().any { (i, r) -> !(0 until 4).all { k -> r[k] == defaults[i][k] } && (r[4] != 0 || r[5] != 0 || r[6] != 0) }) approxAdjust.add("Hue/Saturation color ranges (the standard ranges are used)")
                    FxParams(kind = FxKind.HUE_SAT, hueSat = HueSat(
                        master = if (a.colorize) HueShift(0f, 0f, (a.colorization[2] / 100f).coerceIn(-1f, 1f)) else shift(a.master, 0),
                        bands = a.ranges.map { shift(it, 4) },
                        colorize = a.colorize,
                        colorHue = ((a.colorization[0] % 360 + 360) % 360).toFloat(),
                        colorSat = (a.colorization[1] / 100f).coerceIn(0f, 1f),
                    ))
                }
                is PsdAdjust.ColorBalance -> {
                    fun cs(v: IntArray) = ColorShift(v[0] / 100f, v[1] / 100f, v[2] / 100f)
                    FxParams(kind = FxKind.COLOR_BALANCE, colorBalance = ColorBalance(cs(a.shadows), cs(a.midtones), cs(a.highlights), a.preserve))
                }
                is PsdAdjust.BlackWhite -> FxParams(kind = FxKind.BLACK_WHITE, blackWhite = BlackWhite(
                    weights = a.weights.map { it / 100f },
                    tint = a.tint,
                    tintColor = a.tintColor?.let { color(it) } ?: 0xFFE1D3B3.toInt(),
                ))
                is PsdAdjust.PhotoFilter -> {
                    val c = a.xyz?.let { xyz(it) } ?: a.components?.let { comps ->
                        when (a.space) {
                            0 -> Color.rgb(comps[0] shr 8, comps[1] shr 8, comps[2] shr 8)
                            1 -> Color.HSVToColor(floatArrayOf(comps[0] * 360f / 65536f, comps[1] / 65535f, comps[2] / 65535f))
                            7 -> lab(comps[0] / 100.0, comps[1].toShort() / 100.0, comps[2].toShort() / 100.0)
                            else -> 0xFFEC8A00.toInt()
                        }
                    } ?: 0xFFEC8A00.toInt()
                    FxParams(kind = FxKind.PHOTO_FILTER, photoFilter = PhotoFilter(c or 0xFF000000.toInt(), (a.density / 100f).coerceIn(0f, 1f), a.preserve))
                }
                is PsdAdjust.GradientMap -> {
                    if (a.stops.size < 2) return null
                    val sorted = a.stops.sortedBy { it.first }
                    FxParams(kind = FxKind.GRADIENT_MAP, gradientMap = GradientMap(
                        colors = sorted.map { (_, c) -> Color.rgb(c[0] shr 8, c[1] shr 8, c[2] shr 8) },
                        positions = sorted.map { (it.first / 4096f).coerceIn(0f, 1f) },
                        reverse = a.reversed,
                    ))
                }
                is PsdAdjust.Invert -> FxParams(kind = FxKind.CURVES, curves = Curves(listOf(listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f)), Curves.LINE, Curves.LINE, Curves.LINE)))
                is PsdAdjust.Posterize -> FxParams(kind = FxKind.POSTERIZE, posterize = a.levels.coerceIn(2, 255))
                is PsdAdjust.Threshold -> FxParams(kind = FxKind.THRESHOLD, threshold = a.level.coerceIn(1, 255) / 255f)
                is PsdAdjust.ChannelMixer -> {
                    fun row(r: IntArray?, d: List<Float>): List<Float> =
                        if (r == null) d else listOf(r[0] / 100f, r[1] / 100f, r[2] / 100f, r[4] / 100f)
                    val def = ChannelMixer()
                    val recs = a.channels
                    if (f.mode != 3) approxAdjust.add("Channel Mixer in a gray or CMYK file")
                    FxParams(kind = FxKind.CHANNEL_MIXER, channelMixer = ChannelMixer(
                        red = row(recs.getOrNull(0), def.red),
                        green = row(recs.getOrNull(1), def.green),
                        blue = row(recs.getOrNull(2), def.blue),
                        gray = row(recs.getOrNull(0), def.gray),
                        mono = a.monochrome,
                    ))
                }
                is PsdAdjust.SelectiveColor -> FxParams(kind = FxKind.SELECTIVE_COLOR, selectiveColor = SelectiveColor(
                    List(9) { i -> a.ranges.getOrNull(i)?.map { (it / 100f).coerceIn(-1f, 1f) } ?: listOf(0f, 0f, 0f, 0f) },
                    relative = !a.absolute,
                ))
                is PsdAdjust.ColorLookup -> {
                    // A lookup table file kept in the layer; Photoshop's own looks are color profiles we can't read
                    val lut = a.data?.let { ColorLookup.parse(String(it, Charsets.ISO_8859_1), a.name) } ?: return null
                    FxParams(kind = FxKind.COLOR_LOOKUP, colorLookup = lut)
                }
                else -> return null
            }
            val mask = pixelMask(pl, isFolder = false)
            val props = LayerProps(
                name = pl.name.ifBlank { pl.adjustment ?: "Adjustment" },
                visible = !pl.hidden,
                opacity = pl.opacity / 255f,
                blend = exact(blend(pl.blendKey), "an adjustment layer"),
                maskDisabled = mask?.second ?: false,
                parent = parent,
                clip = clip,
                adjust = fx,
            )
            return Layer(doc.newId(), props, TileGrid(alphaOnly = false), mask = mask?.first ?: TileGrid(alphaOnly = true), kind = LayerKind.ADJUSTMENT)
        }

        /** CIE XYZ (Y 0..1, or percent) to sRGB. */
        fun xyz(v: DoubleArray): Int {
            val k = if (v[1] > 2.0) 100.0 else 1.0
            val x = v[0] / k
            val y = v[1] / k
            val z = v[2] / k
            val rl = 3.2406 * x - 1.5372 * y - 0.4986 * z
            val gl = -0.9689 * x + 1.8758 * y + 0.0415 * z
            val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
            fun gamma(c: Double): Int {
                val g = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
                return (g * 255).roundToInt().coerceIn(0, 255)
            }
            return Color.rgb(gamma(rl), gamma(gl), gamma(bl))
        }

        // ------------------------------------------------------------ patterns

        /** A pattern used by an effect or fill ("Ptrn" with its id), added to your patterns; its key. */
        fun patternKey(ptrn: Map<*, *>?): String? {
            val id = (ptrn?.get("Idnt") as? String)?.trim('\u0000') ?: return null
            val p = f.patterns[id] ?: f.patterns.entries.firstOrNull { it.key.trim('\u0000') == id }?.value ?: return null
            return Patterns.imported(id, p.name) {
                val (w, h, px) = PsdReader.patternPixels(f, p) ?: return@imported null
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            }
        }

        // ------------------------------------------------------------ text and smart objects

        /**
         * Whether a smart object's warp bends it: a named warp with any bend, or a custom one whose
         * mesh isn't the plain even grid Photoshop keeps for unwarped smart objects.
         */
        fun warped(w: Map<*, *>?): Boolean {
            w ?: return false
            fun n(v: Any?): Double = (v as? Number)?.toDouble() ?: (v as? PsdUnit)?.value ?: 0.0
            return when ((w["warpStyle"] as? PsdEnum)?.value) {
                null, "warpNone" -> false
                "warpCustom" -> {
                    val b = w["bounds"] as? Map<*, *> ?: return false
                    val left = n(b["Left"]); val top = n(b["Top "]); val right = n(b["Rght"]); val bottom = n(b["Btom"])
                    val mesh = (w["customEnvelopeWarp"] as? Map<*, *>)?.get("meshPoints") as? Map<*, *> ?: return false
                    val hs = (mesh["Hrzn"] as? List<*>)?.map { n(it) } ?: return false
                    val vs = (mesh["Vrtc"] as? List<*>)?.map { n(it) } ?: return false
                    val u = ((w["uOrder"] as? Number)?.toInt() ?: 4).coerceAtLeast(2)
                    val v = ((w["vOrder"] as? Number)?.toInt() ?: 4).coerceAtLeast(2)
                    if (hs.size != u * v || vs.size != u * v) return true
                    val tol = maxOf(1.0, (right - left) * 0.002)
                    (0 until u * v).any { i ->
                        val ex = left + (i % u) * (right - left) / (u - 1)
                        val ey = top + (i / u) * (bottom - top) / (v - 1)
                        abs(hs[i] - ex) > tol || abs(vs[i] - ey) > tol
                    }
                }
                else -> abs(n(w["warpValue"])) > 0.01 || abs(n(w["warpPerspective"])) > 0.01 || abs(n(w["warpPerspectiveOther"])) > 0.01
            }
        }

        /** A Photoshop text layer as ours, or null to keep it as pixels (warped, empty). */
        fun textProps(pl: PsdLayer, t: PsdText, props: LayerProps): LayerProps? {
            if (t.warped || t.text.isBlank()) {
                textAsPixels++
                return null
            }
            if (t.mixed && t.runs.isEmpty()) mixedText++
            if (t.warpStyle != null) warpedText++
            val font = PsdFonts.map(t.fontName)
            val tf = t.transform
            val m = Matrix()
            m.setValues(floatArrayOf(tf[0].toFloat(), tf[2].toFloat(), tf[4].toFloat(), tf[1].toFloat(), tf[3].toFloat(), tf[5].toFloat(), 0f, 0f, 1f))
            val box = t.box
            if (box != null) m.preTranslate(box[0].toFloat(), box[1].toFloat())
            if (s < 1f) m.postScale(s, s)
            // Photoshop's auto leading is 120% of the size, about a font's own line height
            val leading = if (t.autoLeading || t.leading <= 0.0) 1f else (t.leading / (t.fontSize * 1.2)).toFloat().coerceIn(0.5f, 3f)
            if (!font.exact) t.fontName?.let { fonts.add("${it} → ${font.label}") }
            val spec = TextSpec(
                text = t.text,
                fontKey = font.key,
                size = t.fontSize.toFloat().coerceIn(1f, 4000f),
                color = t.color,
                align = when (t.justification) {
                    1 -> TextAlignOption.RIGHT
                    2 -> TextAlignOption.CENTER
                    else -> TextAlignOption.LEFT
                },
                tracking = (t.tracking / 1000.0).toFloat(),
                leading = leading,
                fauxBold = t.fauxBold,
                fauxItalic = t.fauxItalic,
                underline = t.underline,
                allCaps = t.allCaps,
                curve = t.arc.toFloat(),
                matrix = m.toAffine(),
                warp = t.warpStyle?.let { st -> WarpStyle.entries.firstOrNull { it.psd == st } }?.let { st ->
                    TextWarp(st, (t.warpBend / 100.0).toFloat().coerceIn(-1f, 1f), (t.warpHorizontal / 100.0).toFloat().coerceIn(-1f, 1f),
                        (t.warpVertical / 100.0).toFloat().coerceIn(-1f, 1f), t.warpVerticalOrientation)
                }?.takeIf { it.active },
                boxWidth = if (box != null) (box[2] - box[0]).toFloat().coerceAtLeast(1f) else 0f,
                psdFont = t.fontName ?: "",
            )
            return props.copy(text = spec.copy(runs = textRuns(t, spec)))
        }

        /** The parts of [t] styled differently from its first style (kept as runs on [base]). */
        fun textRuns(t: PsdText, base: TextSpec): List<TextRun> {
            if (t.runs.size < 2) return emptyList()
            val first = t.runs.first()
            val out = ArrayList<TextRun>()
            var at = 0
            for (r in t.runs) {
                val start = at
                at += r.length
                val end = min(at, base.text.length)
                if (end <= start) continue
                val font = if (r.fontName != first.fontName) PsdFonts.map(r.fontName).also { f ->
                    if (!f.exact) r.fontName?.let { fonts.add("$it → ${f.label}") }
                }.key else null
                val run = TextRun(
                    start, end,
                    fontKey = font,
                    size = if (r.fontSize != first.fontSize) r.fontSize.toFloat().coerceIn(1f, 4000f) else null,
                    color = if (r.color != first.color) r.color else null,
                    fauxBold = if (r.fauxBold != first.fauxBold) r.fauxBold else null,
                    fauxItalic = if (r.fauxItalic != first.fauxItalic) r.fauxItalic else null,
                    underline = if (r.underline != first.underline) r.underline else null,
                )
                if (run.fontKey == null && run.size == null && run.color == null && run.fauxBold == null && run.fauxItalic == null && run.underline == null) continue
                // Next to an identical one: one run
                val last = out.lastOrNull()
                if (last != null && last.end == start && last.copy(start = 0, end = 0) == run.copy(start = 0, end = 0)) out[out.lastIndex] = last.copy(end = end)
                else out.add(run)
            }
            return out
        }

        /**
         * A Photoshop smart object's warp as ours (in its contents' space): first, whether there's
         * none; then the warp, or null when it can't be read.
         */
        fun smartWarp(so: Map<*, *>?): Pair<Boolean, SmartWarp?> {
            fun n(v: Any?): Double = (v as? Number)?.toDouble() ?: (v as? PsdUnit)?.value ?: 0.0
            fun nums(v: Any?): List<Double>? = when (v) {
                is List<*> -> v.map { n(it) }
                // An object array: its one list
                is Map<*, *> -> v.values.firstOrNull { it is List<*> }?.let { nums(it) }
                else -> null
            }
            // A split ("quilt") warp is kept next to a plain one; it's the one that counts
            val w = (so?.get("quiltWarp") as? Map<*, *>)?.takeIf { (it["warpStyle"] as? PsdEnum)?.value == "warpCustom" }
                ?: so?.get("warp") as? Map<*, *> ?: return true to null
            val style = (w["warpStyle"] as? PsdEnum)?.value
            if (!warped(w) && w !== so?.get("quiltWarp")) return true to null
            val b = w["bounds"] as? Map<*, *>
            val left = n(b?.get("Left")); val top = n(b?.get("Top ")); val right = n(b?.get("Rght")); val bottom = n(b?.get("Btom"))
            if (style != "warpCustom") {
                val named = WarpStyle.entries.firstOrNull { it.psd == style } ?: return false to null
                return false to SmartWarp(style = TextWarp(
                    named,
                    (n(w["warpValue"]) / 100.0).toFloat().coerceIn(-1f, 1f),
                    (n(w["warpPerspective"]) / 100.0).toFloat().coerceIn(-1f, 1f),
                    (n(w["warpPerspectiveOther"]) / 100.0).toFloat().coerceIn(-1f, 1f),
                    (w["warpRotate"] as? PsdEnum)?.value == "Vrtc",
                ))
            }
            if (right - left < 1.0 || bottom - top < 1.0) return false to null
            val env = w["customEnvelopeWarp"] as? Map<*, *> ?: return false to null
            val mesh = env["meshPoints"] as? Map<*, *> ?: return false to null
            val hs = nums(mesh["Hrzn"]) ?: return false to null
            val vs = nums(mesh["Vrtc"]) ?: return false to null
            if (hs.size != vs.size || hs.size < 16) return false to null
            // How many controls across and down: 4, 7, 10… (patches meet every third)
            val sx = nums(env["quiltSliceX"])?.takeIf { it.size >= 2 }
            val sy = nums(env["quiltSliceY"])?.takeIf { it.size >= 2 }
            val across = listOfNotNull(
                sx?.let { (it.size - 1) * 3 + 1 },
                (w["deformNumCols"] as? Number)?.toInt()?.takeIf { it >= 4 },
                (w["uOrder"] as? Number)?.toInt()?.takeIf { it >= 4 },
                4,
            ).firstOrNull { it % 3 == 1 && hs.size % it == 0 && (hs.size / it) % 3 == 1 } ?: return false to null
            val down = hs.size / across
            val cols = (across - 1) / 3
            val rows = (down - 1) / 3
            if (cols > 16 || rows > 16) return false to null
            val ww = right - left
            val hh = bottom - top
            val ctrl = FloatArray(hs.size * 2) { k -> if (k % 2 == 0) ((hs[k / 2] - left) / ww).toFloat() else ((vs[k / 2] - top) / hh).toFloat() }
            fun slices(v: List<Double>?, n: Int, o: Double, size: Double): FloatArray? = v?.takeIf { it.size == n + 1 }?.map { ((it - o) / size).toFloat() }?.toFloatArray()
            return false to SmartWarp(ctrl = ctrl, cols = cols, rows = rows, sliceU = slices(sx, cols, left, ww), sliceV = slices(sy, rows, top, hh))
        }

        /**
         * A Photoshop smart object's smart filters as ours: the filters, and the name of the first one
         * we don't have (then none are used).
         */
        fun smartFilters(so: Map<*, *>?): Pair<List<SmartFilter>, String?> {
            val fx = so?.get("filterFX") as? Map<*, *> ?: return emptyList<SmartFilter>() to null
            if (fx["enab"] == false) return emptyList<SmartFilter>() to null
            val list = fx["filterFXList"] as? List<*> ?: return emptyList<SmartFilter>() to null
            fun n(v: Any?): Double = (v as? Number)?.toDouble() ?: (v as? PsdUnit)?.value ?: 0.0
            val out = ArrayList<SmartFilter>()
            for (item in list) {
                val m = item as? Map<*, *> ?: continue
                val name = ((m["Nm  "] as? String) ?: "Filter").trim().removeSuffix("...").removeSuffix("…").trim()
                val blend = m["blendOptions"] as? Map<*, *>
                val mode = (blend?.get("Md  ") as? PsdEnum)?.value
                if (mode != null && mode != "Nrml") return emptyList<SmartFilter>() to "$name (${mode.trim()} blending)"
                val opacity = (blend?.get("Opct")?.let { n(it) } ?: 100.0).toFloat() / 100f
                val f = m["Fltr"] as? Map<*, *>
                val id = (f?.get("_class") as? String) ?: (m["filterID"] as? Number)?.toInt()?.let { code ->
                    String(byteArrayOf((code shr 24).toByte(), (code shr 16).toByte(), (code shr 8).toByte(), code.toByte()), Charsets.ISO_8859_1)
                }
                // Photoshop's radius is about one standard deviation; ours is Android's blur radius
                fun blurOf(r: Double) = ((r - 0.5) / 0.57735).toFloat().coerceIn(0f, 500f)
                val p: FxParams = when (id) {
                    "GsnB", "boxblur" -> {
                        if (id == "boxblur") approxAdjust.add("Box Blur (a Gaussian blur is used)")
                        FxParams(kind = FxKind.BLUR, blur = blurOf(n(f?.get("Rds ")).coerceAtLeast(0.6)))
                    }
                    "Blr " -> FxParams(kind = FxKind.BLUR, blur = blurOf(1.0))
                    "BlrM" -> FxParams(kind = FxKind.BLUR, blur = blurOf(2.0))
                    "UnsM", "smartSharpen" -> {
                        approxAdjust.add("${if (id == "UnsM") "Unsharp Mask" else "Smart Sharpen"}'s radius and threshold")
                        FxParams(adjust = mapOf(Adjust.SHARPNESS to (n(f?.get("Amnt")) / 150.0).toFloat().coerceIn(0f, 1f)))
                    }
                    "Shrp" -> FxParams(adjust = mapOf(Adjust.SHARPNESS to 0.25f))
                    "ShrM" -> FxParams(adjust = mapOf(Adjust.SHARPNESS to 0.6f))
                    "ShrE" -> FxParams(adjust = mapOf(Adjust.SHARPNESS to 0.2f))
                    "AdNs" -> {
                        approxAdjust.add("Add Noise (shown as grain)")
                        FxParams(adjust = mapOf(Adjust.GRAIN to (n(f?.get("Nose")) / 25.0).toFloat().coerceIn(0f, 1f)))
                    }
                    else -> return emptyList<SmartFilter>() to name
                }
                out.add(SmartFilter(p, name, enabled = m["enab"] != false, opacity = opacity.coerceIn(0f, 1f)))
            }
            return out to null
        }

        /**
         * A Photoshop smart object as our smart layer: what's inside it (an embedded picture or
         * Photoshop file) placed where it was, with its warp and smart filters. When that can't be
         * read (a file kept outside the .psd, a filter we don't have), what you see becomes its contents.
         */
        fun smartProps(pl: PsdLayer, props: LayerProps, grid: TileGrid): LayerProps? {
            val so = pl.smart
            val id = ((so?.get("Idnt") as? String) ?: pl.placedId)?.trim('\u0000', ' ')
            fun quadFrom(v: Any?): FloatArray? = (v as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == 8 }?.toFloatArray()
            val quad = quadFrom(so?.get("nonAffineTransform")) ?: quadFrom(so?.get("Trnf")) ?: pl.placedQuad?.map { it.toFloat() }?.toFloatArray()
            val (filters, badFilter) = smartFilters(so)
            val (flat, warp) = smartWarp(so)
            // Without a warp (or a spreading filter) the contents stay inside their corners: pixels
            // reaching past them mean a bend we haven't read (inside is fine: contents often have
            // see-through space around them)
            val outside = flat && filters.none { it.enabled && it.fx.blur > 0f } && quad != null && pl.width > 0 && run {
                val xs = floatArrayOf(quad[0], quad[2], quad[4], quad[6])
                val ys = floatArrayOf(quad[1], quad[3], quad[5], quad[7])
                pl.left < xs.min() - 3 || pl.right > xs.max() + 3 || pl.top < ys.min() - 3 || pl.bottom > ys.max() + 3
            }
            val found = id?.let { key -> f.linked[key] ?: f.linked.entries.firstOrNull { it.key.trim('\u0000', ' ') == key }?.value }
            val linked = found?.takeIf { !it.external }
            var why: String? = when {
                badFilter != null -> "the $badFilter smart filter"
                outside || (!flat && warp == null) -> "a warp"
                found?.external == true -> "linked to ${found.name.trim().ifBlank { "a file" }}"
                linked == null -> "a linked file"
                else -> null
            }
            var contents: Doc? = null
            if (why == null && quad != null && linked != null) {
                // The contents' own width ÷ height, as Photoshop has it
                val size = so?.get("Sz  ") as? Map<*, *>
                val expect = (size?.get("Wdth") as? Number)?.toFloat()?.let { w -> (size["Hght"] as? Number)?.toFloat()?.takeIf { it > 0f }?.let { w / it } }
                // The same smart object used more than once: read once, and the copies stay linked
                // (they share their contents, so editing one changes them all, like Photoshop)
                contents = contentsCache[linked.id] ?: try {
                    embedded(linked, expect)?.also { contentsCache[linked.id] = it }
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    null
                }
                if (contents == null) why = linked.type.trim().ifEmpty { "file" }.uppercase()
            }
            if (contents != null && quad != null) {
                val q = FloatArray(8) { quad[it] * s }
                if (warp != null) smartWarped++
                if (filters.isNotEmpty()) smartFiltered += filters.size
                // Sizes in the filters follow the picture's scale
                val scaled = if (s == 1f) filters else filters.map { it.copy(fx = it.fx.copy(blur = it.fx.blur * s)) }
                return props.copy(smart = SmartSpec(contents, q, warp?.takeIf { it.active }, scaled))
            }
            // What you see becomes the contents (still a smart layer)
            val b = grid.tileBounds() ?: return null
            val r = Transformer.contentBounds(grid.tiles)?.let { Rect(it) } ?: b
            // No bigger than the canvas (pixels far past it would make the contents huge)
            if (!r.intersect(0, 0, doc.width, doc.height) || r.isEmpty) return null
            val inner = Doc(r.width(), r.height(), props.name)
            val il = Layer(inner.newId(), LayerProps(props.name), TileGrid(alphaOnly = false).also { it.tiles.putAll(grid.tiles) }, -r.left, -r.top)
            inner.layers.add(il)
            inner.activeId = il.id
            smartFromPixels.add(why ?: "unreadable")
            return props.copy(smart = SmartSpec(inner, SmartSpec.quadOf(RectF(r))))
        }

        /** A smart object's embedded file as a document: a picture Android can read, or a Photoshop file. */
        fun embedded(l: PsdLinked, expect: Float?): Doc? {
            val buf = f.buffer.duplicate()
            buf.position(l.offset.toInt())
            buf.limit((l.offset + l.length).toInt())
            val data = buf.slice()
            val head = ByteArray(4)
            data.duplicate().get(head)
            val name = l.name.trim().substringBeforeLast('.').ifBlank { "Smart object" }
            // Photoshop inside Photoshop
            if (head[0] == '8'.code.toByte() && head[1] == 'B'.code.toByte() && head[2] == 'P'.code.toByte() && head[3] == 'S'.code.toByte()) {
                if (depth >= 2) return null
                return fromBuffer(data, name, depth + 1).doc
            }
            // A TIFF (layered or not)
            if (TiffReader.isTiff(head)) {
                if (depth >= 2) return null
                val d = fromTiff(data, name, depth + 1).doc
                if (expect != null && expect > 0f && abs(d.width.toFloat() / d.height - expect) / expect > 0.04f) return null
                return d
            }
            // A picture: decoded straight from the file (never copied whole), no bigger than the app's limit
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(BufferStream(data.duplicate()), null, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (!Doc.fits(bounds.outWidth / sample, bounds.outHeight / sample)) sample *= 2
            var bmp = android.graphics.BitmapFactory.decodeStream(BufferStream(data.duplicate()), null, android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            // A photo saved sideways: turned upright, like Photoshop shows it
            val orientation = try {
                androidx.exifinterface.media.ExifInterface(BufferStream(data.duplicate()))
                    .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            }
            val turn = Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> turn.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> turn.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> turn.postRotate(270f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> turn.postScale(-1f, 1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> turn.postScale(1f, -1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { turn.postRotate(90f); turn.postScale(-1f, 1f) }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { turn.postRotate(270f); turn.postScale(-1f, 1f) }
            }
            if (!turn.isIdentity) {
                val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, turn, true)
                if (upright !== bmp) bmp.recycle()
                bmp = upright
            }
            // Exactly within the limit (the decoder rounds sizes up)
            if (!Doc.fits(bmp.width, bmp.height)) {
                val (fw, fh) = Doc.fitted(bmp.width, bmp.height)
                val fit = Bitmap.createScaledBitmap(bmp, fw, fh, true)
                if (fit !== bmp) bmp.recycle()
                bmp = fit
            }
            // Its shape should match what Photoshop says the contents are; otherwise it's not what we think
            if (expect != null && expect > 0f) {
                val ratio = bmp.width.toFloat() / bmp.height
                if (abs(ratio - expect) / expect > 0.04f) {
                    bmp.recycle()
                    return null
                }
            }
            val inner = Doc(bmp.width.coerceAtMost(Doc.MAX_SIDE), bmp.height.coerceAtMost(Doc.MAX_SIDE), name)
            val il = Layer(inner.newId(), LayerProps(name), TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false).also { it.dropEmptyTiles() })
            bmp.recycle()
            inner.layers.add(il)
            inner.activeId = il.id
            return inner
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
                    // Duotone is shown as the gray it's made from
                    0, 1, 8 -> for (x in 0 until w) {
                        val g = rows[0][x].toInt() and 0xFF
                        argb[x] = ((a[x].toInt() and 0xFF) shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    2 -> {
                        val pal = f.palette
                        for (x in 0 until w) {
                            val k = rows[0][x].toInt() and 0xFF
                            val c = pal?.get(k) ?: ((0xFF shl 24) or (k shl 16) or (k shl 8) or k)
                            val alpha = if (k == f.transparentIndex) 0 else (a[x].toInt() and 0xFF)
                            argb[x] = (alpha shl 24) or (c and 0xFFFFFF)
                        }
                    }
                    9 -> for (x in 0 until w) {
                        // Lab: lightness 0..255 → 0..100, a and b around 128
                        val l = (rows[0][x].toInt() and 0xFF) * (100f / 255f)
                        val aa = (rows[1][x].toInt() and 0xFF) - 128f
                        val bb = (rows[2][x].toInt() and 0xFF) - 128f
                        argb[x] = ((a[x].toInt() and 0xFF) shl 24) or ColorMath.labToRgb(l, aa, bb)
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

        /**
         * The outline of a vector mask, in document pixels: its outlines joined by their path
         * operations (older files: holes by the even-odd rule, like the hole in an O).
         */
        fun path(vm: PsdVectorMask): Path {
            val p = ShapeSpec.customPath(parts(vm), evenOdd = true, full = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat()))
            if (vm.invert) p.toggleInverseFillType()
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
            val pattern = pl.patternFill
            if (solid != null) {
                paint.color = color(solid["Clr "], bookFallback) or 0xFF000000.toInt()
            } else if (pattern != null) {
                val bmp = patternKey(pattern["Ptrn"] as? Map<*, *>)?.let { Patterns.bitmapFor(it, Color.BLACK, Color.WHITE) } ?: return grid
                val k = (num(pattern["Scl "], 100.0) / 100.0).toFloat() * s
                paint.shader = android.graphics.BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                    setLocalMatrix(Matrix().apply { setScale(k, k) })
                }
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
                GradientType.ANGLE, GradientType.DIAMOND -> GradientPainter.shader(
                    GradientSpec(fx.colors, fx.type, 1f, fx.reverse, fx.positions),
                    android.graphics.PointF(cx, cy), android.graphics.PointF(cx + ux * half, cy + uy * half),
                )
                else -> LinearGradient(cx - ux * half, cy - uy * half, cx + ux * half, cy + uy * half, colors, pos, Shader.TileMode.CLAMP)
            }
        }

        // ------------------------------------------------------------ settings

        fun blend(key: String): Blend = when (key) {
            "norm", "pass" -> Blend.NORMAL
            "diss" -> Blend.DISSOLVE
            "dark" -> Blend.DARKEN
            "mul " -> Blend.MULTIPLY
            "idiv" -> Blend.COLOR_BURN
            "lbrn" -> Blend.LINEAR_BURN
            "dkCl" -> Blend.DARKER_COLOR
            "lite" -> Blend.LIGHTEN
            "scrn" -> Blend.SCREEN
            "div " -> Blend.COLOR_DODGE
            "lddg" -> Blend.LINEAR_DODGE
            "lgCl" -> Blend.LIGHTER_COLOR
            "over" -> Blend.OVERLAY
            "sLit" -> Blend.SOFT_LIGHT
            "hLit" -> Blend.HARD_LIGHT
            "vLit" -> Blend.VIVID_LIGHT
            "lLit" -> Blend.LINEAR_LIGHT
            "pLit" -> Blend.PIN_LIGHT
            "hMix" -> Blend.HARD_MIX
            "diff" -> Blend.DIFFERENCE
            "smud" -> Blend.EXCLUSION
            "fsub" -> Blend.SUBTRACT
            "fdiv" -> Blend.DIVIDE
            "hue " -> Blend.HUE
            "sat " -> Blend.SATURATION
            "colr" -> Blend.COLOR
            "lum " -> Blend.LUMINOSITY
            else -> {
                approxBlends.add("${key.trim()} → Normal")
                Blend.NORMAL
            }
        }

        /** A blend mode for an effect or adjustment layer: those use Android's own, so others become the nearest. */
        fun exact(b: Blend, where: String): Blend {
            if (b.exact) return b
            val near = Blend.entries.first { it.exact && it.mode == b.mode }
            approxBlends.add("${b.label} on $where → ${near.label}")
            return near
        }

        /** A blend mode from a layer style's descriptor ("Md  "). */
        fun fxBlend(v: Any?): Blend = exact(when ((v as? PsdEnum)?.value) {
            null, "Nrml" -> Blend.NORMAL
            "Dslv" -> Blend.DISSOLVE
            "Drkn" -> Blend.DARKEN
            "Mltp" -> Blend.MULTIPLY
            "CBrn" -> Blend.COLOR_BURN
            "linearBurn" -> Blend.LINEAR_BURN
            "darkerColor" -> Blend.DARKER_COLOR
            "Lghn" -> Blend.LIGHTEN
            "Scrn" -> Blend.SCREEN
            "CDdg" -> Blend.COLOR_DODGE
            "linearDodge" -> Blend.LINEAR_DODGE
            "lighterColor" -> Blend.LIGHTER_COLOR
            "Ovrl" -> Blend.OVERLAY
            "SftL" -> Blend.SOFT_LIGHT
            "HrdL" -> Blend.HARD_LIGHT
            "vividLight" -> Blend.VIVID_LIGHT
            "linearLight" -> Blend.LINEAR_LIGHT
            "pinLight" -> Blend.PIN_LIGHT
            "hardMix" -> Blend.HARD_MIX
            "Dfrn" -> Blend.DIFFERENCE
            "Xclu" -> Blend.EXCLUSION
            "blendSubtraction" -> Blend.SUBTRACT
            "blendDivide" -> Blend.DIVIDE
            "H   " -> Blend.HUE
            "Strt" -> Blend.SATURATION
            "Clr " -> Blend.COLOR
            "Lmns" -> Blend.LUMINOSITY
            else -> Blend.NORMAL
        }, "an effect")

        var unsupportedColors = 0
        /** The fill color a book color stands for, read from the layer being brought in (see [layer]); only its fill uses it. */
        var bookFallback: Int? = null

        /** The average color of [g]'s solid pixels (edges and see-through parts left out). */
        fun averageColor(g: TileGrid): Int? {
            var r = 0L
            var gr = 0L
            var b = 0L
            var n = 0L
            val row = IntArray(TileGrid.SIZE)
            for (t in g.tiles.values) {
                for (y in 0 until t.height step 4) {
                    t.getPixels(row, 0, t.width, 0, y, t.width, 1)
                    for (x in 0 until t.width step 4) {
                        val p = row[x]
                        if ((p ushr 24) < 250) continue
                        r += (p shr 16) and 0xFF; gr += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                }
            }
            if (n == 0L) return null
            return Color.rgb((r / n).toInt(), (gr / n).toInt(), (b / n).toInt())
        }

        /** [v] as ARGB; a book color is [book] when known (read from the layer's pixels), otherwise black. */
        fun color(v: Any?, book: Int? = null): Int {
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
                "BkCl" -> book ?: run {
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
                "Angl" -> GradientType.ANGLE
                "Dmnd" -> GradientType.DIAMOND
                else -> GradientType.LINEAR
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

        /** Blend If (gray), from the layer's blending ranges: this layer's black and white pairs, then the underlying layer's. */
        fun blendIf(pl: PsdLayer): BlendIf {
            val r = pl.blendRanges ?: return BlendIf()
            return BlendIf(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]).sane()
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
            pick("IrGl", "innerGlowMulti")?.let { d ->
                val c = if (d["Clr "] != null) color(d["Clr "]) else {
                    (d["Grad"] as? Map<*, *>)?.let { unsupportedFx.add("gradient glows (the first color is used)") }
                    gradient(d, 1f, Blend.NORMAL).colors.firstOrNull() ?: Color.WHITE
                }
                st = st.copy(innerGlow = InnerGlowFx(
                    on = d["enab"] == true, color = c, opacity = opacity(d["Opct"]), size = px(d["blur"], blurK),
                    choke = (num(d["Ckmt"], 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
                    center = (d["glwS"] as? PsdEnum)?.value == "SrcC", blend = fxBlend(d["Md  "]),
                ))
            }
            pick("ChFX", "satinMulti")?.let { d ->
                st = st.copy(satin = SatinFx(
                    on = d["enab"] == true, color = color(d["Clr "]), opacity = opacity(d["Opct"]),
                    angle = num(d["lagl"], 19.0).toFloat(), distance = px(d["Dstn"], k), size = px(d["blur"], blurK),
                    invert = d["Invr"] != false, blend = fxBlend(d["Md  "]),
                ))
            }
            pick("patternFill", "patternFillMulti")?.let { d ->
                val key = patternKey(d["Ptrn"] as? Map<*, *>)
                if (key == null) {
                    if (d["enab"] == true) unsupportedFx.add("Pattern Overlay (its pattern isn't saved in the file)")
                } else {
                    st = st.copy(patternOverlay = PatternFx(
                        on = d["enab"] == true, key = key, scale = (num(d["Scl "], 100.0) / 100.0).toFloat().coerceIn(0.05f, 8f),
                        opacity = opacity(d["Opct"]), blend = fxBlend(d["Md  "]),
                    ))
                }
            }
            return st
        }
    }
}

/** Reads a [java.nio.ByteBuffer] as a stream (for decoding an embedded file in place). */
private class BufferStream(private val b: java.nio.ByteBuffer) : java.io.InputStream() {
    override fun read(): Int = if (b.hasRemaining()) b.get().toInt() and 0xFF else -1

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (!b.hasRemaining()) return -1
        val n = minOf(len, b.remaining())
        b.get(dst, off, n)
        return n
    }

    override fun available(): Int = b.remaining()

    override fun skip(n: Long): Long {
        val k = minOf(n, b.remaining().toLong()).toInt()
        b.position(b.position() + k)
        return k.toLong()
    }
}
