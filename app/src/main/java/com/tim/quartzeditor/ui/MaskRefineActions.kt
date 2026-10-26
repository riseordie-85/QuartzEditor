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
    val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    Canvas(mb).apply {
        scale(s, s)
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
            scale(s, s)
            l.tiles.drawTo(this, l.x.toFloat(), l.y.toFloat(), paint)
        }
    } else {
        val flat = DocRenderer.flatten(d.shallowCopy())
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
            // The change, laid over the full-size mask (where nothing changed, it stays exactly as it was)
            val diff = FloatArray(refined.size) { refined[it] - work.mask[it] }
            val fw = d.width
            val fh = d.height
            val full = Bitmap.createBitmap(fw, fh, Bitmap.Config.ALPHA_8)
            Canvas(full).let { c -> old.drawTo(c, l.x.toFloat(), l.y.toFloat(), null) }
            val bytes = ByteArray(fw * fh)
            full.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
            val sx = work.w / fw.toFloat()
            val sy = work.h / fh.toFloat()
            for (y in 0 until fh) {
                val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (work.h - 1).toFloat())
                val iy = fy.toInt()
                val iy1 = min(iy + 1, work.h - 1)
                val ty = fy - iy
                val row = y * fw
                for (x in 0 until fw) {
                    val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (work.w - 1).toFloat())
                    val ix = fx.toInt()
                    val ix1 = min(ix + 1, work.w - 1)
                    val tx = fx - ix
                    val a = diff[iy * work.w + ix] * (1 - tx) + diff[iy * work.w + ix1] * tx
                    val b = diff[iy1 * work.w + ix] * (1 - tx) + diff[iy1 * work.w + ix1] * tx
                    val dv = a * (1 - ty) + b * ty
                    if (dv > 0.002f || dv < -0.002f) {
                        val v = (bytes[row + x].toInt() and 0xFF) + dv * 255f
                        bytes[row + x] = v.roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
            full.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            // Back into the layer's own pixels: the canvas's tiles replaced, the rest kept
            val fresh = TileGrid.fromBitmap(full, -l.x, -l.y, alphaOnly = true)
            full.recycle()
            val out = TileGrid(alphaOnly = true)
            out.tiles.putAll(old.tiles)
            val area = RectF(-l.x.toFloat(), -l.y.toFloat(), (fw - l.x).toFloat(), (fh - l.y).toFloat())
            for (k in out.keysIn(area)) out.tiles.remove(k)
            out.tiles.putAll(fresh.tiles)
            out
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
