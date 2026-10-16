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

    /** The contents as one picture at full size, with layer styles. */
    @Synchronized
    fun flatten(contents: Doc): Bitmap {
        val f = flat
        if (f != null && !f.isRecycled && flatFor === contents && flatVersion == contents.renderVersion) return f
        val fx = DocRenderer.buildAllEffects(contents, max(contents.width, contents.height))
        val bmp = DocRenderer.flatten(contents, effects = { fx[it.id] })
        bmp.setHasMipMap(true)
        flat?.takeIf { it !== bmp }?.recycle()
        flat = bmp
        flatFor = contents
        flatVersion = contents.renderVersion
        return bmp
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
     * (layer-local; keeps memory sane for placements far past the canvas).
     */
    fun rasterize(spec: SmartSpec, clip: Rect): TileGrid {
        val b = spec.bounds()
        val area = Rect(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
        if (!area.intersect(clip) || area.isEmpty) return TileGrid(alphaOnly = false)
        val out = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        synchronized(this) {
            val src = flatten(spec.contents)
            val c = Canvas(out)
            c.translate(-area.left.toFloat(), -area.top.toFloat())
            c.concat(spec.matrix())
            c.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return TileGrid.fromBitmap(out, area.left, area.top, alphaOnly = false).also {
            out.recycle()
            it.dropEmptyTiles()
        }
    }
}

/**
 * A smart layer's contents were edited (in their own tab): the contents as they were and are, and
 * the layer's pixels drawn from each.
 */
class SmartContentsChange(
    private val layer: Layer,
    val contents: Doc,
    private val before: DocSnap,
    private val after: DocSnap,
    private val beforeTiles: TileGrid,
    private val afterTiles: TileGrid,
) : Change {
    override val label = "Edit smart layer"
    override val cost: Long = afterTiles.bytes()

    override fun undo(doc: Doc) {
        contents.restore(before)
        layer.tiles = beforeTiles
        doc.touched(layer)
    }

    override fun redo(doc: Doc) {
        contents.restore(after)
        layer.tiles = afterTiles
        doc.touched(layer)
    }
}
