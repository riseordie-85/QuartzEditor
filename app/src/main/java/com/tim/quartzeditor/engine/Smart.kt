package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A smart layer: a whole picture of its own ([contents], at its own full size, with its own
 * layers), placed on the canvas by the four corners of [quad] (x, y each; top-left, top-right,
 * bottom-right, bottom-left; layer-local pixels). The layer's tiles are drawn from the contents
 * whenever the placement or the contents change, so scaling it down and back up loses nothing.
 * [warp] bends the contents before they're placed; [filters] (smart filters) are applied to the
 * placed picture, first to last, and stay editable.
 */
class SmartSpec(
    val contents: Doc,
    val quad: FloatArray,
    val warp: SmartWarp? = null,
    val filters: List<SmartFilter> = emptyList(),
) {
    fun withQuad(q: FloatArray) = SmartSpec(contents, q, warp, filters)

    /** The same placement, warp and filters with other contents (a copy of them, say). */
    fun withContents(c: Doc, q: FloatArray = quad.copyOf()) = SmartSpec(c, q, warp, filters)

    fun withWarp(w: SmartWarp?) = SmartSpec(contents, quad, w?.takeIf { it.active }, filters)

    fun withFilters(f: List<SmartFilter>) = SmartSpec(contents, quad, warp, f)

    /** Smart filters that do something. */
    val activeFilters: List<SmartFilter> get() = filters.filter { it.enabled && !it.fx.isIdentity && it.opacity > 0.001f }

    /** How far the smart filters spread past the picture (a blur does). */
    val filterReach: Int get() = activeFilters.sumOf { if (it.fx.blur > 0f) Gpu.blurReach(it.fx.blur) else 0 }

    /** Contents pixels → layer-local points (the warp, then the placement), [n]×[n] cells, x then y each. */
    fun meshPoints(n: Int): FloatArray {
        val w = contents.width.toFloat()
        val h = contents.height.toFloat()
        val out = FloatArray((n + 1) * (n + 1) * 2)
        val o = FloatArray(2)
        var k = 0
        for (j in 0..n) for (i in 0..n) {
            val u = i / n.toFloat()
            val v = j / n.toFloat()
            if (warp != null) warp.map(u, v, w, h, o) else { o[0] = u * w; o[1] = v * h }
            out[k++] = o[0]
            out[k++] = o[1]
        }
        matrix().mapPoints(out)
        return out
    }

    /** Contents pixels → layer-local pixels. */
    fun matrix(): Matrix = Matrix().apply {
        val w = contents.width.toFloat()
        val h = contents.height.toFloat()
        setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, quad, 0, 4)
    }

    /** Where it reaches (layer-local), its warp and smart filters included. */
    fun bounds(): RectF {
        val r = placedBounds()
        val e = filterReach.toFloat()
        r.inset(-e, -e)
        return r
    }

    /** Where the warped, placed picture reaches (layer-local), before smart filters. */
    fun placedBounds(): RectF {
        if (warp != null) {
            // The same mesh the picture is drawn with (a coarser one could miss a wave's crest)
            val pts = meshPoints(MESH)
            val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var k = 0
            while (k < pts.size) {
                r.left = min(r.left, pts[k]); r.right = max(r.right, pts[k])
                r.top = min(r.top, pts[k + 1]); r.bottom = max(r.bottom, pts[k + 1])
                k += 2
            }
            r.inset(-1f, -1f)
            return r
        }
        val xs = floatArrayOf(quad[0], quad[2], quad[4], quad[6])
        val ys = floatArrayOf(quad[1], quad[3], quad[5], quad[7])
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("quad", org.json.JSONArray().also { a -> quad.forEach { a.put(it.toDouble()) } })
        .also { o ->
            warp?.let { o.put("warp", it.toJson()) }
            if (filters.isNotEmpty()) o.put("filters", org.json.JSONArray().also { a -> filters.forEach { a.put(it.toJson()) } })
        }

    companion object {
        /** Cells across and down of the mesh a warped smart layer is drawn with. */
        const val MESH = 48

        /** A placement that fits [w]×[h] inside a [docW]×[docH] canvas (never bigger than it is), centered. */
        fun fitted(w: Int, h: Int, docW: Int, docH: Int): FloatArray {
            val s = min(1f, min(docW.toFloat() / w, docH.toFloat() / h))
            val pw = w * s
            val ph = h * s
            val l = (docW - pw) / 2f
            val t = (docH - ph) / 2f
            return floatArrayOf(l, t, l + pw, t, l + pw, t + ph, l, t + ph)
        }

        /** The warp and smart filters saved by [toJson] (the contents and placement are read by the caller). */
        fun extrasFrom(o: org.json.JSONObject?): Pair<SmartWarp?, List<SmartFilter>> {
            val w = SmartWarp.fromJson(o?.optJSONObject("warp"))
            val a = o?.optJSONArray("filters")
            val f = if (a == null) emptyList() else List(a.length()) { SmartFilter.fromJson(a.optJSONObject(it)) }.filterNotNull()
            return w to f
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
        // Smart filters that spread (a blur) need the picture a little past what's kept
        val reach = spec.filterReach
        val keepArea = Rect(area)
        if (!keepArea.intersect(clip) || keepArea.isEmpty) return TileGrid(alphaOnly = false)
        area.set(keepArea.left - reach, keepArea.top - reach, keepArea.right + reach, keepArea.bottom + reach)
        area.intersect(b.left.toInt() - 2, b.top.toInt() - 2, ceil(b.right).toInt() + 2, ceil(b.bottom).toInt() + 2)
        var out = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        fun drawFrom(src: Bitmap) {
            val c = Canvas(out)
            c.translate(-area.left.toFloat(), -area.top.toFloat())
            drawPlaced(c, spec, src)
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
            val filtered = applyFilters(out, spec.activeFilters)
            if (filtered !== out) {
                out.recycle()
                out = filtered
            }
            // Only the part asked for is kept
            if (area != keepArea) {
                val cut = Bitmap.createBitmap(out, keepArea.left - area.left, keepArea.top - area.top, keepArea.width(), keepArea.height())
                if (cut !== out) {
                    out.recycle()
                    out = cut
                }
                area.set(keepArea)
            }
            return TileGrid.fromBitmap(out, area.left, area.top, alphaOnly = false).also { it.dropEmptyTiles() }
        } finally {
            out.recycle()
        }
    }

    /** Draws [src] (the contents, flat, at full size) warped and placed, into [c] (layer-local). */
    fun drawPlaced(c: Canvas, spec: SmartSpec, src: Bitmap) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val warp = spec.warp
        if (warp == null) {
            c.save()
            c.concat(spec.matrix())
            c.drawBitmap(src, 0f, 0f, p)
            c.restore()
            return
        }
        // The flat picture stretched over a fine mesh that follows the warp
        val n = SmartSpec.MESH
        val pts = spec.meshPoints(n)
        c.drawBitmapMesh(src, n, n, pts, 0, null, 0, p)
    }

    /**
     * [src] with [filters] applied one after another (each mixed with what came before by its
     * opacity). Returns [src] itself when there are none. Runs on the GPU.
     */
    fun applyFilters(src: Bitmap, filters: List<SmartFilter>): Bitmap {
        var cur = src
        for (f in filters) {
            val w = cur.width.toFloat()
            val h = cur.height.toFloat()
            // (a vignette in a smart filter darkens toward the layer's own corners)
            var next = Gpu.applyFx(cur, f.fx, 0f, 0f, w, h)
            if (f.opacity < 0.999f) {
                // before × (1 − opacity) + after × opacity
                val mixed = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(mixed)
                c.drawBitmap(cur, 0f, 0f, Paint().apply { alpha = ((1f - f.opacity) * 255f).toInt().coerceIn(0, 255) })
                c.drawBitmap(next, 0f, 0f, Paint().apply {
                    alpha = (f.opacity * 255f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                })
                next.recycle()
                next = mixed
            }
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }
}

/**
 * A smart filter: an adjustment or filter on a smart layer, applied to its placed picture and
 * kept editable. [name] is what the Layers panel shows.
 */
data class SmartFilter(val fx: FxParams, val name: String, val enabled: Boolean = true, val opacity: Float = 1f) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("fx", fx.toJson()).put("name", name).put("on", enabled).put("opacity", opacity.toDouble())

    companion object {
        fun fromJson(o: org.json.JSONObject?): SmartFilter? {
            o ?: return null
            val fx = FxParams.fromJson(o.optJSONObject("fx")) ?: return null
            return SmartFilter(fx, o.optString("name", "Filter"), o.optBoolean("on", true), o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f))
        }
    }
}

/**
 * A smart layer's warp, in its contents' own space: a named shape ([style], like Warp Text's,
 * bending the contents' box), or a grid of curve patches ([ctrl]: [cols]×[rows] bicubic patches,
 * (3·cols + 1) × (3·rows + 1) controls, x then y each, row by row, as fractions of the contents'
 * width and height). [sliceU] and [sliceV] (cols + 1 and rows + 1 values from 0 to 1) are where
 * the patches meet; null spreads them evenly.
 */
class SmartWarp(
    val style: TextWarp? = null,
    val ctrl: FloatArray? = null,
    val cols: Int = 1,
    val rows: Int = 1,
    val sliceU: FloatArray? = null,
    val sliceV: FloatArray? = null,
) {
    val isCustom: Boolean get() = style == null

    /** It bends something. */
    val active: Boolean get() = style?.active ?: (ctrl != null && !nearlyFlat())

    private fun nearlyFlat(): Boolean {
        val c = ctrl ?: return true
        val gw = cols * 3 + 1
        val gh = rows * 3 + 1
        for (j in 0 until gh) for (i in 0 until gw) {
            val k = (j * gw + i) * 2
            if (abs(c[k] - restU(i)) > 0.0005f || abs(c[k + 1] - restV(j)) > 0.0005f) return false
        }
        return true
    }

    /** Where control column [i] sits unwarped (0..1). */
    private fun restU(i: Int): Float {
        val a = i / 3
        val f = (i % 3) / 3f
        val s = sliceU
        if (s == null) return i / (cols * 3f)
        if (a >= cols) return s[cols]
        return s[a] + (s[a + 1] - s[a]) * f
    }

    private fun restV(j: Int): Float {
        val a = j / 3
        val f = (j % 3) / 3f
        val s = sliceV
        if (s == null) return j / (rows * 3f)
        if (a >= rows) return s[rows]
        return s[a] + (s[a + 1] - s[a]) * f
    }

    /** Where (u, v) of the contents (0..1 each) goes, in the contents' pixels ([w]×[h]). */
    fun map(u: Float, v: Float, w: Float, h: Float, out: FloatArray) {
        val st = style
        if (st != null) {
            st.map(RectF(0f, 0f, w, h), u * w, v * h, out)
            return
        }
        val c = ctrl ?: run { out[0] = u * w; out[1] = v * h; return }
        val (a, s) = patchOf(u, cols, sliceU)
        val (b, t) = patchOf(v, rows, sliceV)
        val gw = cols * 3 + 1
        var x = 0f
        var y = 0f
        for (j in 0..3) {
            val bv = bern(j, t)
            for (i in 0..3) {
                val wgt = bern(i, s) * bv
                val k = ((b * 3 + j) * gw + (a * 3 + i)) * 2
                x += c[k] * wgt
                y += c[k + 1] * wgt
            }
        }
        out[0] = x * w
        out[1] = y * h
    }

    /** Which patch [u] falls in and where in it (0..1). */
    private fun patchOf(u: Float, n: Int, slices: FloatArray?): Pair<Int, Float> {
        if (slices == null) {
            val p = (u * n).coerceIn(0f, n.toFloat())
            val a = min(n - 1, floor(p).toInt()).coerceAtLeast(0)
            return a to (p - a)
        }
        var a = 0
        while (a < n - 1 && u > slices[a + 1]) a++
        val span = (slices[a + 1] - slices[a]).takeIf { it > 1e-6f } ?: 1f
        return a to ((u - slices[a]) / span).coerceIn(0f, 1f)
    }

    /**
     * The warp as one 4×4 curve patch (fractions of the contents' size), the closest match when it's
     * a named shape or several patches: what the Warp tool starts from.
     */
    fun singlePatch(w: Float, h: Float): FloatArray {
        val c = ctrl
        if (style == null && c != null && cols == 1 && rows == 1) return c.copyOf()
        // Least squares: sample the warp and fit 16 controls (the same fit for x and y)
        val n = 12
        val ata = Array(16) { DoubleArray(16) }
        val atx = DoubleArray(16)
        val aty = DoubleArray(16)
        val o = FloatArray(2)
        val row = DoubleArray(16)
        for (j in 0..n) for (i in 0..n) {
            val u = i / n.toFloat()
            val v = j / n.toFloat()
            map(u, v, w, h, o)
            for (k in 0 until 16) row[k] = (bern(k % 4, u) * bern(k / 4, v)).toDouble()
            for (p in 0 until 16) {
                atx[p] += row[p] * o[0] / w
                aty[p] += row[p] * o[1] / h
                for (q in 0 until 16) ata[p][q] += row[p] * row[q]
            }
        }
        val x = solve(ata.map { it.copyOf() }.toTypedArray(), atx.copyOf()) ?: return rest()
        val y = solve(ata, aty) ?: return rest()
        return FloatArray(32) { k -> (if (k % 2 == 0) x[k / 2] else y[k / 2]).toFloat() }
    }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().also { o ->
        style?.let { o.put("style", it.toJson()) }
        ctrl?.let { c -> o.put("ctrl", org.json.JSONArray().also { a -> c.forEach { a.put(it.toDouble()) } }) }
        o.put("cols", cols).put("rows", rows)
        sliceU?.let { s -> o.put("su", org.json.JSONArray().also { a -> s.forEach { a.put(it.toDouble()) } }) }
        sliceV?.let { s -> o.put("sv", org.json.JSONArray().also { a -> s.forEach { a.put(it.toDouble()) } }) }
    }

    companion object {
        private fun bern(i: Int, t: Float): Float {
            val s = 1f - t
            return when (i) {
                0 -> s * s * s
                1 -> 3f * t * s * s
                2 -> 3f * t * t * s
                else -> t * t * t
            }
        }

        /** An unwarped 4×4 patch (fractions). */
        fun rest(): FloatArray = FloatArray(32) { k -> if (k % 2 == 0) ((k / 2) % 4) / 3f else ((k / 2) / 4) / 3f }

        /** Gaussian elimination with pivoting; null when it can't be solved. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (c in 0 until n) {
                var piv = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-12) return null
                if (piv != c) {
                    val t = a[c]; a[c] = a[piv]; a[piv] = t
                    val tb = b[c]; b[c] = b[piv]; b[piv] = tb
                }
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c until n) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (k in r + 1 until n) s -= a[r][k] * x[k]
                x[r] = s / a[r][r]
            }
            return x
        }

        fun fromJson(o: org.json.JSONObject?): SmartWarp? {
            o ?: return null
            fun floats(key: String): FloatArray? = o.optJSONArray(key)?.let { a -> FloatArray(a.length()) { a.optDouble(it).toFloat() } }
            val style = TextWarp.fromJson(o.optJSONObject("style"))
            val cols = o.optInt("cols", 1).coerceIn(1, 16)
            val rows = o.optInt("rows", 1).coerceIn(1, 16)
            val ctrl = floats("ctrl")?.takeIf { it.size == (cols * 3 + 1) * (rows * 3 + 1) * 2 }
            if (style == null && ctrl == null) return null
            return SmartWarp(style, if (style == null) ctrl else null, cols, rows, floats("su")?.takeIf { it.size == cols + 1 }, floats("sv")?.takeIf { it.size == rows + 1 })
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
