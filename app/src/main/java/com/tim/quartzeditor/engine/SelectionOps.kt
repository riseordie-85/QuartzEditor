package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Growing and shrinking selections (Select ▸ Modify ▸ Expand / Contract in Photoshop). */
object SelectionOps {
    /**
     * [sel] made [px] pixels bigger (or smaller, for a negative [px]), with round corners like
     * Photoshop. The soft edge (feather) stays as it was. Null when nothing is left.
     */
    fun grown(sel: Selection, px: Float, docW: Int, docH: Int): Selection? {
        if (px == 0f) return sel
        val r = kotlin.math.abs(px)
        val hard = sel.hardMask()
        // The area to work in: the selection plus room to grow, kept to the canvas (and a little past it)
        val b = RectF(sel.bounds).apply { inset(-r - 2f, -r - 2f) }
        if (!b.intersect(-2f, -2f, docW + 2f, docH + 2f)) return null
        val area = Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
        val w = area.width()
        val h = area.height()
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(bmp).also { c ->
            c.translate(-area.left.toFloat(), -area.top.toFloat())
            hard.drawTo(c, 0f, 0f, null)
        }
        val a = ByteArray(w * h)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(a))
        bmp.recycle()
        val inside = BooleanArray(w * h) { (a[it].toInt() and 0xFF) >= 128 }
        // Distance from each pixel to the nearest pixel on the other side of the edge
        val grow = px > 0f
        val d2 = distanceSquared(w, h) { i -> if (grow) inside[i] else !inside[i] }
        val out = ByteArray(w * h)
        var any = false
        for (i in 0 until w * h) {
            val d = sqrt(d2[i])
            // A one-pixel soft edge so the new outline isn't jagged
            val cov = if (grow) (r - d + 1f) else (d - r)
            val v = (cov.coerceIn(0f, 1f) * 255f).toInt()
            out[i] = v.toByte()
            if (v >= 128) any = true
        }
        if (!any) return null
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        res.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out))
        val grid = TileGrid.fromBitmap(res, area.left, area.top, alphaOnly = true)
        res.recycle()
        return Selection.fromMask(grid, sel.feather)
    }

    /**
     * Squared distance from every pixel of a [w]×[h] grid to the nearest pixel where [feature] is
     * true (0 on those), exact (Felzenszwalb & Huttenlocher). Outside the grid counts as not a feature.
     */
    private fun distanceSquared(w: Int, h: Int, feature: (Int) -> Boolean): FloatArray {
        val inf = 1e20f
        val f = FloatArray(w * h) { if (feature(it)) 0f else inf }
        val n = maxOf(w, h)
        val line = FloatArray(n)
        val outLine = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        // Columns, then rows
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = f[y * w + x]
            edt1d(line, h, outLine, v, z)
            for (y in 0 until h) f[y * w + x] = outLine[y]
        }
        for (y in 0 until h) {
            val o = y * w
            System.arraycopy(f, o, line, 0, w)
            edt1d(line, w, outLine, v, z)
            System.arraycopy(outLine, 0, f, o, w)
        }
        return f
    }

    private fun edt1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        val inf = 1e20f
        // Where parabola q (rooted at f[q]) overtakes parabola p; the big "no feature" values are
        // subtracted first so the squares aren't lost in them
        fun cross(q: Int, p: Int): Float = ((f[q] - f[p]) + (q.toFloat() * q - p.toFloat() * p)) / (2f * (q - p))
        var k = 0
        v[0] = 0
        z[0] = -inf
        z[1] = inf
        for (q in 1 until n) {
            var s = cross(q, v[k])
            while (s <= z[k] && k > 0) {
                k--
                s = cross(q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = inf
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toFloat() * (q - p) + f[p]
        }
    }
}
