package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.tim.quartzeditor.engine.Cutout
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.FloodFill
import com.tim.quartzeditor.engine.Selection
import com.tim.quartzeditor.engine.TileGrid
import kotlin.math.roundToInt

// Pixel selections: the magic wand, Select Subject and Select Background. They combine with the
// selection that's there by the selection mode (New / Add / Subtract / Intersect).

/** Magic wand tap at canvas point ([x], [y]): selects the pixels that match its color. */
fun EditorViewModel.magicWand(x: Float, y: Float) {
    val d = doc ?: return
    // Look at the pixels after anything pending is applied
    if (pendingFirst { magicWand(x, y) }) return
    val px = x.toInt()
    val py = y.toInt()
    if (px !in 0 until d.width || py !in 0 until d.height) return
    val l = d.active
    // Folders and adjustment layers have no pixels to look at: use the picture
    val allLayers = wandAllLayers || l == null || !l.hasContent
    val copy = if (allLayers) d.shallowCopy() else null
    val tiles = if (allLayers) null else HashMap(l!!.tiles.tiles)
    val lx = l?.x ?: 0
    val ly = l?.y ?: 0
    val w = d.width
    val h = d.height
    val tol = (wandTolerance * 255).roundToInt()
    val contiguous = wandContiguous
    val old = d.selection
    val mode = selectMode
    setSelectionComputed(if (mode == com.tim.quartzeditor.engine.SelectMode.NEW) "Magic wand" else "Change selection") {
        val src = if (copy != null) {
            DocRenderer.flatten(copy)
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles!!) }.drawTo(Canvas(b), lx.toFloat(), ly.toFloat(), null)
            }
        }
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        src.recycle()
        val cov = FloodFill.coverage(pixels, w, h, px, py, tol, contiguous)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(cov))
        val grid = TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = true)
        bmp.recycle()
        Selection.combineMask(old, grid, mode)
    }
}

/**
 * Selects the main subject of the picture (or, with [background], everything else), found on
 * the device. Soft edges are kept.
 */
fun EditorViewModel.selectSubject(background: Boolean) {
    val d = doc ?: return
    if (pendingFirst { selectSubject(background) }) return
    val copy = d.shallowCopy()
    val w = d.width
    val h = d.height
    val old = d.selection
    val mode = selectMode
    val label = if (background) "Select background" else "Select subject"
    setSelectionComputed(label, busyLabel = "Finding the subject…") {
        val pic = DocRenderer.flatten(copy)
        // How much of each pixel is background
        val hidden = try {
            Cutout.backgroundMask(pic)
        } finally {
            pic.recycle()
        }
        val selected = if (background) {
            hidden
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { inv ->
                val c = Canvas(inv)
                c.drawColor(Color.BLACK)
                c.drawBitmap(hidden, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                hidden.recycle()
            }
        }
        val grid = TileGrid.fromBitmap(selected, 0, 0, alphaOnly = true)
        selected.recycle()
        Selection.combineMask(old, grid, mode)
    }
}
