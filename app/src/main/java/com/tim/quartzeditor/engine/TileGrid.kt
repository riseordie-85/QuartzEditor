package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.floor

/**
 * A layer's pixels, stored as square tiles that only exist where something has been painted.
 * An empty layer costs no memory, a layer can hold pixels past the canvas edge (moving it
 * back brings them back), and only the tiles a brush touches have to be re-sent to the GPU.
 *
 * Tile (tx, ty) covers layer-local pixels tx*SIZE .. (tx+1)*SIZE.
 *
 * [alphaOnly] grids (masks, brush strokes in progress) use 1 byte per pixel.
 */
class TileGrid(val alphaOnly: Boolean) {

    val tiles = HashMap<Long, Bitmap>()

    val isEmpty: Boolean get() = tiles.isEmpty()

    operator fun get(key: Long): Bitmap? = tiles[key]

    fun getOrCreate(key: Long): Bitmap = tiles.getOrPut(key) { newTile(alphaOnly) }

    /** Puts [bitmap] at [key] (or removes the tile for null) and returns what was there. */
    fun swap(key: Long, bitmap: Bitmap?): Bitmap? {
        val old = tiles[key]
        if (bitmap == null) tiles.remove(key) else tiles[key] = bitmap
        return old
    }

    /** A fresh tile to draw into: a copy of [existing], or an empty one. */
    fun editableCopy(existing: Bitmap?): Bitmap {
        if (existing == null) return newTile(alphaOnly)
        val c = existing.copy(existing.config ?: Bitmap.Config.ARGB_8888, true)
        if (!alphaOnly) c.setHasMipMap(true)
        return c
    }

    fun copy(): TileGrid {
        val g = TileGrid(alphaOnly)
        for ((k, b) in tiles) g.tiles[k] = b.copy(b.config ?: Bitmap.Config.ARGB_8888, true)
        return g
    }

    fun bytes(): Long = tiles.values.sumOf { it.allocationByteCount.toLong() }

    /** Tile keys touched by a layer-local rectangle. */
    fun keysIn(r: RectF): List<Long> {
        if (r.isEmpty) return emptyList()
        val x0 = floor(r.left / SIZE).toInt()
        val y0 = floor(r.top / SIZE).toInt()
        val x1 = floor((r.right - 0.001f) / SIZE).toInt()
        val y1 = floor((r.bottom - 0.001f) / SIZE).toInt()
        val out = ArrayList<Long>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (ty in y0..y1) for (tx in x0..x1) out.add(key(tx, ty))
        return out
    }

    /** Layer-local bounds of every tile that exists (whole tiles), or null when empty. */
    fun tileBounds(): Rect? {
        if (tiles.isEmpty()) return null
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (k in tiles.keys) {
            val x = tx(k)
            val y = ty(k)
            if (x < l) l = x
            if (y < t) t = y
            if (x > r) r = x
            if (y > b) b = y
        }
        return Rect(l * SIZE, t * SIZE, (r + 1) * SIZE, (b + 1) * SIZE)
    }

    /**
     * Draws every tile with its top-left at (dx + tx*SIZE, dy + ty*SIZE). Tiles outside [visible]
     * (in the same coordinates as the canvas) are skipped.
     */
    fun drawTo(canvas: Canvas, dx: Float, dy: Float, paint: Paint?, visible: RectF? = null) {
        // Tile edges must not be anti-aliased: at in-between zooms the two soft edges of
        // neighboring tiles don't add up to solid, which shows as faint lines across the picture.
        // (New Paints are anti-aliased by default.)
        val p = if (paint == null) PLAIN else if (paint.isAntiAlias) Paint(paint).apply { isAntiAlias = false } else paint
        // Zoomed out on screen: smaller copies of the tiles (the GPU holds far less, so big
        // pictures with many layers stay smooth)
        val level = TileLod.level()
        var scaled: Paint? = null
        val dst = if (level > 0) RectF() else null
        for ((k, bmp) in tiles) {
            val x = dx + tx(k) * SIZE
            val y = dy + ty(k) * SIZE
            if (visible != null && (x > visible.right || y > visible.bottom || x + SIZE < visible.left || y + SIZE < visible.top)) continue
            val small = if (level > 0) TileLod.get(bmp, level) else null
            if (small != null && dst != null) {
                val sp = scaled ?: (if (p.isFilterBitmap) p else Paint(p).apply { isFilterBitmap = true }).also { scaled = it }
                dst.set(x, y, x + SIZE, y + SIZE)
                canvas.drawBitmap(small, null, dst, sp)
            } else {
                canvas.drawBitmap(bmp, x, y, p)
            }
        }
    }

    /** Removes tiles that are completely see-through (keeps saved projects and memory small). */
    fun dropEmptyTiles() {
        val it = tiles.entries.iterator()
        while (it.hasNext()) {
            if (isBlank(it.next().value)) it.remove()
        }
    }

    companion object {
        const val SIZE = 512

        private val PLAIN = Paint().apply { isAntiAlias = false }

        fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        fun tx(key: Long): Int = (key shr 32).toInt()
        fun ty(key: Long): Int = key.toInt()

        fun newTile(alphaOnly: Boolean): Bitmap =
            if (alphaOnly) {
                Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
            } else {
                Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
                    it.setHasAlpha(true)
                    // Smooth when zoomed far out
                    it.setHasMipMap(true)
                }
            }

        /** True when every pixel is fully transparent. */
        fun isBlank(b: Bitmap): Boolean {
            if (b.config == Bitmap.Config.ALPHA_8) {
                val buf = java.nio.ByteBuffer.allocate(b.byteCount)
                b.copyPixelsToBuffer(buf)
                val a = buf.array()
                for (v in a) if (v.toInt() != 0) return false
                return true
            }
            val row = IntArray(b.width)
            for (y in 0 until b.height) {
                b.getPixels(row, 0, b.width, 0, y, b.width, 1)
                for (p in row) if ((p ushr 24) != 0) return false
            }
            return true
        }

        /**
         * Cuts a bitmap that sits at layer-local ([left], [top]) into a new grid. Fully transparent
         * tiles are skipped. [alphaOnly] grids take the bitmap's alpha.
         */
        fun fromBitmap(src: Bitmap, left: Int, top: Int, alphaOnly: Boolean): TileGrid {
            val g = TileGrid(alphaOnly)
            g.paste(src, left, top)
            return g
        }
    }

    /** Draws [src] (at layer-local [left], [top]) into this grid, replacing what was there. */
    fun paste(src: Bitmap, left: Int, top: Int) {
        val r = RectF(left.toFloat(), top.toFloat(), (left + src.width).toFloat(), (top + src.height).toFloat())
        val paint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
        for (k in keysIn(r)) {
            val tile = newTile(alphaOnly)
            val c = Canvas(tile)
            c.drawBitmap(src, (left - tx(k) * SIZE).toFloat(), (top - ty(k) * SIZE).toFloat(), paint)
            if (isBlank(tile)) {
                tiles.remove(k)
                tile.recycle()
            } else {
                tiles[k] = tile
            }
        }
    }
}

/**
 * Half- and quarter-size copies of tiles, drawn instead of the full tiles when the picture is
 * zoomed out on screen. A 3000×4000 layer is 48 MB of full-size tiles; at the half size it's 12 MB,
 * so many layers fit in the GPU's memory at once instead of being sent again every frame.
 *
 * Only the screen's draw pass uses them ([drawing] sets the zoom for the main thread); exports,
 * merges and everything else always draw the full tiles. Copies follow their tile's pixels
 * ([Bitmap.getGenerationId]) and go away with it.
 */
object TileLod {
    private val zoom = ThreadLocal<Float?>()

    /** Copies made in this frame so far, and whether some were left for the next one. */
    private var madeThisFrame = 0
    private var leftOver = false

    /** At most this many copies are made in one frame, so zooming out never stalls (the rest follow). */
    private const val PER_FRAME = 24

    private class Copy(val generation: Int, val bitmap: Bitmap)

    // Main thread only (the only thread that sets a zoom)
    private val copies = java.util.WeakHashMap<Bitmap, Array<Copy?>>()

    /** Runs the screen's draw with [scale] screen pixels per canvas pixel. Returns true when some copies are still to come (draw again). */
    fun drawing(scale: Float, block: () -> Unit): Boolean {
        val old = zoom.get()
        zoom.set(scale)
        madeThisFrame = 0
        leftOver = false
        try {
            block()
        } finally {
            zoom.set(old)
        }
        return leftOver
    }

    /** 0 = full tiles, 1 = half size, 2 = quarter size. */
    fun level(): Int {
        val s = zoom.get() ?: return 0
        return when {
            s <= 0.3f -> 2
            s <= 0.6f -> 1
            else -> 0
        }
    }

    /** [tile]'s copy at [level] (1 or 2), or null when it isn't made yet (draw the tile itself). */
    fun get(tile: Bitmap, level: Int): Bitmap? {
        if (tile.isRecycled) return null
        val slots = copies.getOrPut(tile) { arrayOfNulls(2) }
        val gen = tile.generationId
        slots[level - 1]?.let { if (it.generation == gen && !it.bitmap.isRecycled) return it.bitmap }
        // The quarter size comes from the half size
        val from = if (level == 1) tile else (get(tile, 1) ?: return null)
        if (madeThisFrame >= PER_FRAME) {
            leftOver = true
            return null
        }
        madeThisFrame++
        val size = from.width / 2
        if (size < 1) return null
        val small = try {
            val config = if (tile.config == Bitmap.Config.ALPHA_8) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
            Bitmap.createBitmap(size, size, config).also { b ->
                if (config != Bitmap.Config.ALPHA_8) {
                    b.setHasAlpha(true)
                    b.setHasMipMap(true)
                }
                Canvas(b).drawBitmap(from, null, Rect(0, 0, size, size), FILTER)
            }
        } catch (e: OutOfMemoryError) {
            return null
        }
        slots[level - 1] = Copy(gen, small)
        return small
    }

    private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)
}
