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
        for ((k, bmp) in tiles) {
            val x = dx + tx(k) * SIZE
            val y = dy + ty(k) * SIZE
            if (visible != null && (x > visible.right || y > visible.bottom || x + SIZE < visible.left || y + SIZE < visible.top)) continue
            canvas.drawBitmap(bmp, x, y, p)
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
