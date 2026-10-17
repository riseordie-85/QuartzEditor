package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A smart layer: a whole picture of its own ([contents], at its own full size, with its own
 * layers), placed on the canvas by the four corners of [quad] (x, y each; top-left, top-right,
 * bottom-right, bottom-left; layer-local pixels). The layer's tiles are drawn from the contents
 * whenever the placement or the contents change, so scaling it down and back up loses nothing.
 */
class SmartSpec(val contents: Doc, val quad: FloatArray) {
    fun withQuad(q: FloatArray) = SmartSpec(contents, q)

    /** Contents pixels → layer-local pixels. */
    fun matrix(): Matrix = Matrix().apply {
        val w = contents.width.toFloat()
        val h = contents.height.toFloat()
        setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, quad, 0, 4)
    }

    /** Where it reaches (layer-local). */
    fun bounds(): RectF {
        val xs = floatArrayOf(quad[0], quad[2], quad[4], quad[6])
        val ys = floatArrayOf(quad[1], quad[3], quad[5], quad[7])
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("quad", org.json.JSONArray().also { a -> quad.forEach { a.put(it.toDouble()) } })

    companion object {
        /** A placement that fits [w]×[h] inside a [docW]×[docH] canvas (never bigger than it is), centered. */
        fun fitted(w: Int, h: Int, docW: Int, docH: Int): FloatArray {
            val s = min(1f, min(docW.toFloat() / w, docH.toFloat() / h))
            val pw = w * s
            val ph = h * s
            val l = (docW - pw) / 2f
            val t = (docH - ph) / 2f
            return floatArrayOf(l, t, l + pw, t, l + pw, t + ph, l, t + ph)
        }

        fun quadOf(r: RectF) = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)

        /** Maps [quad] through [m]. */
        fun mapped(quad: FloatArray, m: Matrix): FloatArray = quad.copyOf().also { m.mapPoints(it) }
    }
}

object SmartArt {
    /** The last contents drawn flat, kept while its picture doesn't change (transforms redraw from it). */
    private var flatFor: Doc? = null
    private var flatVersion = -1
    private var flat: Bitmap? = null

    /**
     * [contents] as one picture at full size, with layer styles. [source] is what's drawn (a copy of
     * [contents] made on the main thread when they may be edited meanwhile) and [version] is
     * [contents]' render version when that copy was made.
     */
    @Synchronized
    fun flatten(contents: Doc, source: Doc = contents, version: Int = contents.renderVersion): Bitmap {
        val f = flat
        if (f != null && !f.isRecycled && flatFor === contents && flatVersion == version) return f
        val bmp = draw(source)
        flat?.takeIf { it !== bmp }?.recycle()
        flat = bmp
        flatFor = contents
        flatVersion = version
        return bmp
    }

    private fun draw(d: Doc): Bitmap {
        val fx = DocRenderer.buildAllEffects(d, max(d.width, d.height))
        return DocRenderer.flatten(d, effects = { fx[it.id] }).also { it.setHasMipMap(true) }
    }

    /** Forgets the cached picture (it's big). */
    @Synchronized
    fun release() {
        flat?.recycle()
        flat = null
        flatFor = null
    }

    /**
     * The layer's tiles for [spec]: the contents drawn through its placement, within [clip]
     * (layer-local; keeps memory sane for placements far past the canvas). [source] and [version]
     * as for [flatten]. With [keep] false (saving a copy), the cached picture is left alone.
     */
    fun rasterize(
        spec: SmartSpec,
        clip: Rect,
        source: Doc = spec.contents,
        version: Int = spec.contents.renderVersion,
        keep: Boolean = true,
    ): TileGrid {
        val b = spec.bounds()
        val area = Rect(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
        if (!area.intersect(clip) || area.isEmpty) return TileGrid(alphaOnly = false)
        val out = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        fun drawFrom(src: Bitmap) {
            val c = Canvas(out)
            c.translate(-area.left.toFloat(), -area.top.toFloat())
            c.concat(spec.matrix())
            c.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        try {
            if (keep) {
                synchronized(this) { drawFrom(flatten(spec.contents, source, version)) }
            } else {
                val src = draw(source)
                try {
                    drawFrom(src)
                } finally {
                    src.recycle()
                }
            }
            return TileGrid.fromBitmap(out, area.left, area.top, alphaOnly = false).also { it.dropEmptyTiles() }
        } finally {
            out.recycle()
        }
    }
}

/**
 * A smart layer's contents as they are now, kept for undo. Unlike [DocSnap] it keeps each layer's
 * tiles as they are now (tiles are never drawn into once in a layer, only replaced, so holding the
 * same bitmaps is enough), and the same for smart layers inside it.
 */
class ContentsSnap private constructor(
    private val doc: DocSnap,
    private val pixels: List<Pair<HashMap<Long, Bitmap>, HashMap<Long, Bitmap>?>>,
    private val inner: List<Pair<Doc, ContentsSnap>>,
) {
    val width: Int get() = doc.width
    val height: Int get() = doc.height

    /** Puts [into] (the same contents this was taken from) back as it was. */
    fun restore(into: Doc) {
        into.restore(doc)
        for ((i, ls) in doc.layers.withIndex()) {
            val l = ls.layer
            val (t, m) = pixels[i]
            // Fresh grids, so later edits don't change the ones kept here
            l.tiles = TileGrid(alphaOnly = false).also { it.tiles.putAll(t) }
            l.mask = m?.let { src -> TileGrid(alphaOnly = true).also { it.tiles.putAll(src) } }
            l.pixelVersion++
        }
        for ((d, s) in inner) s.restore(d)
        into.renderVersion++
    }

    /** Every bitmap this keeps (to count what undo holds). */
    fun bitmaps(into: MutableSet<Bitmap> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())): MutableSet<Bitmap> {
        for ((t, m) in pixels) {
            into.addAll(t.values)
            m?.let { into.addAll(it.values) }
        }
        for ((_, s) in inner) s.bitmaps(into)
        return into
    }

    /**
     * Where the contents' corner went if they were cropped since (in the old contents' pixels), when
     * a layer that's still there unchanged shows it; null when none does.
     */
    fun shiftIn(now: Doc): Pair<Int, Int>? {
        // The shift most unchanged layers agree on (one that was also moved by hand doesn't count)
        val votes = HashMap<Pair<Int, Int>, Int>()
        for ((i, ls) in doc.layers.withIndex()) {
            val l = now.layer(ls.layer.id) ?: continue
            if (l !== ls.layer) continue
            val t = pixels[i].first
            if (t.isEmpty() || t.size != l.tiles.tiles.size) continue
            if (t.any { (k, b) -> l.tiles.tiles[k] !== b }) continue
            val s = (ls.x - l.x) to (ls.y - l.y)
            votes[s] = (votes[s] ?: 0) + 1
        }
        return votes.maxByOrNull { it.value }?.key
    }

    companion object {
        fun of(d: Doc): ContentsSnap {
            val snap = d.snapshot()
            val pixels = snap.layers.map { ls -> HashMap(ls.tiles.tiles) to ls.mask?.let { HashMap(it.tiles) } }
            val inner = snap.layers.mapNotNull { ls -> ls.props.smart?.let { it.contents to of(it.contents) } }
            return ContentsSnap(snap, pixels, inner)
        }
    }
}

/**
 * Where a smart layer's contents go once they've changed size (cropped, turned, resized): the same
 * scale as before, lined up where they were (or centered). [quad] is the placement, [before] the
 * contents as they were placed.
 */
fun placedAfterResize(quad: FloatArray, before: ContentsSnap, now: Doc): FloatArray {
    val w = now.width.toFloat()
    val h = now.height.toFloat()
    if (now.width == before.width && now.height == before.height) return quad
    val shift = before.shiftIn(now)
    val sx = shift?.first?.toFloat() ?: ((before.width - w) / 2f)
    val sy = shift?.second?.toFloat() ?: ((before.height - h) / 2f)
    // The new contents' corners, in the old contents' pixels, then placed as the old ones were
    val corners = floatArrayOf(sx, sy, sx + w, sy, sx + w, sy + h, sx, sy + h)
    val old = Matrix().apply {
        val bw = before.width.toFloat()
        val bh = before.height.toFloat()
        setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, quad, 0, 4)
    }
    old.mapPoints(corners)
    return corners
}

/**
 * A smart layer's contents were edited (in their own tab): the contents as they were and are, the
 * layer's settings (its placement changes when the contents change size), and its pixels from each.
 */
class SmartContentsChange(
    private val layer: Layer,
    val contents: Doc,
    private val before: ContentsSnap,
    private val after: ContentsSnap,
    private val beforeProps: LayerProps,
    private val afterProps: LayerProps,
    private val beforeTiles: TileGrid,
    private val afterTiles: TileGrid,
    /** Linked copies (the same contents) drawn again along with it. */
    private val others: List<LinkedRedraw> = emptyList(),
) : Change {
    /** A linked copy's settings and pixels before and after. */
    class LinkedRedraw(val layer: Layer, val beforeProps: LayerProps, val afterProps: LayerProps, val beforeTiles: TileGrid, val afterTiles: TileGrid)

    override val label = "Edit smart layer"
    override val cost: Long = run {
        val a = before.bitmaps()
        val b = after.bitmaps()
        a.filter { it !in b }.sumOf { it.allocationByteCount.toLong() } +
            b.filter { it !in a }.sumOf { it.allocationByteCount.toLong() } +
            beforeTiles.bytes() + afterTiles.bytes() + others.sumOf { it.beforeTiles.bytes() + it.afterTiles.bytes() }
    }

    override fun undo(doc: Doc) {
        before.restore(contents)
        layer.props = beforeProps
        layer.tiles = beforeTiles
        doc.touched(layer)
        for (o in others) {
            o.layer.props = o.beforeProps
            o.layer.tiles = o.beforeTiles
            doc.touched(o.layer)
        }
    }

    override fun redo(doc: Doc) {
        after.restore(contents)
        layer.props = afterProps
        layer.tiles = afterTiles
        doc.touched(layer)
        for (o in others) {
            o.layer.props = o.afterProps
            o.layer.tiles = o.afterTiles
            doc.touched(o.layer)
        }
    }
}

/** Smart layers in [doc] showing the same contents as [l] (linked copies), not counting [l]. */
fun linkedCopies(doc: Doc, l: Layer): List<Layer> {
    val c = l.props.smart?.contents ?: return emptyList()
    return doc.layers.filter { it !== l && it.props.smart?.contents === c }
}
