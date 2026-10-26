package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.MaskRefine
import com.tim.quartzeditor.engine.TileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Select and mask (the mask's menu): smooth, feather, contrast, shift edge and edge detection for
// a layer mask, with a preview; and Enhance edge, edge detection in one tap.

/** Worked out at most this big (the result is laid over the full-size mask as a change, so nothing else softens). */
private const val REFINE_MAX = 2048

/** The mask and picture at a working size: hidden amount and brightness (0..1), the picture's colors, and its scale. */
internal class MaskWork(val mask: FloatArray, val gray: FloatArray, val color: Bitmap, val w: Int, val h: Int, val scale: Float)

/** Opens Select and mask for the active layer's mask. */
fun EditorViewModel.openMaskRefine() {
    if (pendingFirst { openMaskRefine() }) return
    val l = doc?.active ?: return
    if (l.mask == null) {
        toast("This layer has no mask")
        return
    }
    maskRefineFor = l.id
}

/**
 * The mask of [l] (in [d]) and what it hides, as the canvas shows them, at most [maxSide] across.
 * The guide is the layer's own pixels (everything you see, for a folder or adjustment layer).
 */
internal fun maskWork(d: Doc, l: Layer, maxSide: Int): MaskWork {
    val s = min(1f, maxSide.toFloat() / max(d.width, d.height))
    val w = (d.width * s).roundToInt().coerceAtLeast(1)
    val h = (d.height * s).roundToInt().coerceAtLeast(1)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    // The mask (canvas pixels: the layer's own, moved by where it is)
    // (Scaled to exactly this size, so it lines up with the full-size mask)
    val sx = w / d.width.toFloat()
    val sy = h / d.height.toFloat()
    val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    Canvas(mb).apply {
        scale(sx, sy)
        l.mask?.drawTo(this, l.x.toFloat(), l.y.toFloat(), paint)
    }
    val mbytes = ByteArray(w * h)
    mb.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(mbytes))
    mb.recycle()
    val mask = FloatArray(w * h) { (mbytes[it].toInt() and 0xFF) / 255f }
    // The picture it masks
    val color = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (l.hasContent && !l.isGroup && !l.isAdjustment) {
        Canvas(color).apply {
            scale(sx, sy)
            l.tiles.drawTo(this, l.x.toFloat(), l.y.toFloat(), paint)
        }
    } else {
        // (Only as big as it's needed: a huge picture flattened whole wouldn't fit in memory)
        val flat = DocRenderer.thumbnail(d.shallowCopy(), max(w, h))
        Canvas(color).drawBitmap(flat, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        flat.recycle()
    }
    val px = IntArray(w * h)
    color.getPixels(px, 0, w, 0, 0, w, h)
    val gray = FloatArray(w * h) { i ->
        val c = px[i]
        ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
    }
    return MaskWork(mask, gray, color, w, h, s)
}

/** Refines the mask of layer [id] as [p] says, at full size, as one undo step. */
fun EditorViewModel.applyMaskRefine(id: Long, p: MaskRefine.Params, label: String = "Select and mask") {
    val d = doc ?: return
    if (p.isEmpty) return
    if (pendingFirst { applyMaskRefine(id, p, label) }) return
    val l = d.layer(id) ?: return
    val old = l.mask ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    runBusy(if (label == "Enhance edge") "Enhancing the edge…" else "Refining the mask…") {
        val grid = withContext(Dispatchers.Default) {
            val work = maskWork(d, l, REFINE_MAX)
            val refined = MaskRefine.apply(work.mask, work.gray, work.w, work.h, p, work.scale)
            work.color.recycle()
            refinedMask(d, l, old, work, refined)
        }
        if (doc !== d || d.layer(id) !== l) return@runBusy
        stackEdit(label) { l.mask = grid }
    }
}

/** Enhance edge: the active layer's mask edge snaps to the picture's own edges (hair, fur, leaves). */
fun EditorViewModel.enhanceMaskEdge() {
    val d = doc ?: return
    val l = d.active ?: return
    if (l.mask == null) {
        toast("This layer has no mask")
        return
    }
    applyMaskRefine(l.id, MaskRefine.Params(radius = MaskRefine.autoRadius(d.width, d.height)), "Enhance edge")
}

/**
 * [old] (layer [l]'s mask) with [refined] (worked out at [work]'s size) laid over the canvas part,
 * tile by tile: where it changed, the refined edge (smoothly enlarged); where it didn't, the mask
 * exactly as it was; and off the canvas, untouched.
 */
private fun refinedMask(d: Doc, l: Layer, old: TileGrid, work: MaskWork, refined: FloatArray): TileGrid {
    val ww = work.w
    val wh = work.h
    val fx = ww / d.width.toFloat()
    val fy = wh / d.height.toFloat()
    // The refined mask at canvas pixel ([cx], [cy]), and how much it changed there
    fun sample(arr: FloatArray, cx: Int, cy: Int): Float {
        val x = ((cx + 0.5f) * fx - 0.5f).coerceIn(0f, (ww - 1).toFloat())
        val y = ((cy + 0.5f) * fy - 0.5f).coerceIn(0f, (wh - 1).toFloat())
        val ix = x.toInt()
        val iy = y.toInt()
        val ix1 = min(ix + 1, ww - 1)
        val iy1 = min(iy + 1, wh - 1)
        val tx = x - ix
        val ty = y - iy
        val a = arr[iy * ww + ix] * (1 - tx) + arr[iy * ww + ix1] * tx
        val b = arr[iy1 * ww + ix] * (1 - tx) + arr[iy1 * ww + ix1] * tx
        return a * (1 - ty) + b * ty
    }
    val diff = FloatArray(refined.size) { refined[it] - work.mask[it] }
    val out = TileGrid(alphaOnly = true)
    out.tiles.putAll(old.tiles)
    val size = TileGrid.SIZE
    // The canvas, in the layer's own pixels
    val area = RectF(-l.x.toFloat(), -l.y.toFloat(), (d.width - l.x).toFloat(), (d.height - l.y).toFloat())
    for (k in out.keysIn(area)) {
        val tx0 = TileGrid.tx(k) * size
        val ty0 = TileGrid.ty(k) * size
        // The tile's pixels on the canvas
        val x0 = max(0, area.left.toInt() - tx0)
        val y0 = max(0, area.top.toInt() - ty0)
        val x1 = min(size, area.right.toInt() - tx0)
        val y1 = min(size, area.bottom.toInt() - ty0)
        if (x0 >= x1 || y0 >= y1) continue
        val had = old.tiles[k]
        val tile = had?.copy(Bitmap.Config.ALPHA_8, true) ?: Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val bytes = ByteArray(size * size)
        tile.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
        var changed = false
        var any = false
        for (y in y0 until y1) {
            val cy = ty0 + y + l.y
            for (x in x0 until x1) {
                val cx = tx0 + x + l.x
                if (abs(sample(diff, cx, cy)) < 0.004f) continue
                val v = (sample(refined, cx, cy) * 255f).roundToInt().coerceIn(0, 255).toByte()
                if (bytes[y * size + x] != v) {
                    bytes[y * size + x] = v
                    changed = true
                }
            }
        }
        if (!changed) {
            tile.recycle()
            continue
        }
        for (b in bytes) if (b.toInt() != 0) {
            any = true
            break
        }
        if (!any) {
            tile.recycle()
            out.tiles.remove(k)
            continue
        }
        tile.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        out.tiles[k] = tile
    }
    return out
}
