package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.PointF
import android.graphics.Rect
import com.tim.quartzeditor.engine.CloneSource
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Heal
import com.tim.quartzeditor.engine.RetouchKind
import com.tim.quartzeditor.engine.RetouchSession
import com.tim.quartzeditor.engine.StrokeSession
import com.tim.quartzeditor.engine.StrokeTarget
import com.tim.quartzeditor.engine.TilePatch
import com.tim.quartzeditor.engine.TileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The retouch brushes (one toolbar button, hold for the list): Spot Healing, Clone Stamp, Dodge,
// Burn, Smudge, Blur and Sharpen.

fun EditorViewModel.chooseRetouch(k: RetouchKind) {
    retouchKind = k
    prefs.edit().putString("retouchKind", k.name).apply()
    if (tool != Tool.RETOUCH) onToolButton(Tool.RETOUCH)
    if (k == RetouchKind.CLONE && cloneSource == null) toast("Tap Pick source, then tap where to copy from (or hold ⇧ out to Alt and tap)")
}

/** Sets where the Clone Stamp copies from. */
fun EditorViewModel.setCloneSource(x: Float, y: Float) {
    cloneSource = PointF(x, y)
    cloneOffset = null
    pickingCloneSource = false
    toast("Copying from here: now paint where it should go")
}

/** Starts a retouch stroke at canvas point ([x], [y]). False when it can't. */
internal fun EditorViewModel.beginRetouch(x: Float, y: Float): Boolean {
    val d = doc ?: return false
    val layer = d.active
    if (blockPaint(layer)) return false
    layer!!
    if (layer.isGroup || layer.isAdjustment) {
        toast("Pick a pixel layer to retouch")
        return false
    }
    if (d.editMask) {
        toast("The retouch brushes change pixels: tap the layer's picture (not its mask)")
        return false
    }
    val b = retouchBrush
    when (val k = retouchKind) {
        RetouchKind.SPOT_HEAL -> {
            // Paint where to heal (shown in pink); it heals when you lift your finger
            val s = StrokeSession(layer, StrokeTarget.PIXELS, false, 0xFFFF2DAA.toInt(), background, 0.45f, com.tim.quartzeditor.engine.BrushTip.ROUND, b.size, b.softness, d.selection)
            s.addPoint(x, y)
            stroke = s
        }
        RetouchKind.CLONE -> {
            val src = cloneSource ?: run {
                toast("Pick where to copy from first: tap Pick source (or hold ⇧ out to Alt and tap)")
                return false
            }
            val off = (if (cloneAligned) cloneOffset else null) ?: PointF(src.x - x, src.y - y)
            cloneOffset = off
            val source = if (retouchAllLayers) {
                val pic = DocRenderer.flatten(d.shallowCopy())
                CloneSource(TileGrid.fromBitmap(pic, 0, 0, alphaOnly = false).also { pic.recycle() }, 0, 0)
            } else {
                CloneSource(TileGrid(alphaOnly = false).also { it.tiles.putAll(layer.tiles.tiles) }, layer.x, layer.y)
            }
            val r = RetouchSession(layer, k, b.size, b.softness, b.opacity, d.selection, source = source, offX = off.x, offY = off.y)
            r.addPoint(x, y)
            retouch = r
        }
        else -> {
            val r = RetouchSession(layer, k, b.size, b.softness, b.opacity, d.selection, range = dodgeRange)
            r.addPoint(x, y)
            retouch = r
        }
    }
    d.invalidate()
    return true
}

/** The finger lifted: the retouch stroke becomes one undo step. */
internal fun EditorViewModel.endRetouch() {
    val r = retouch ?: return
    val d = doc ?: return
    retouch = null
    r.at?.let { lastStrokeEnd = Triple(r.layer.id, it.x, it.y) }
    val change = r.commit()
    if (change != null) {
        history.record(change)
        d.touched(r.layer)
    } else {
        d.invalidate()
    }
}

internal fun EditorViewModel.cancelRetouch() {
    retouch?.cancel()
    retouch = null
    doc?.invalidate()
}

/** Spot Healing: heals where the stroke painted, in the background. */
internal fun EditorViewModel.finishSpotHeal(s: StrokeSession) {
    val d = doc ?: return
    stroke = null
    d.invalidate()
    val layer = s.layer
    val buf = s.buffer
    val tb = buf.tileBounds() ?: return
    val grid = layer.tiles
    val tiles = HashMap(grid.tiles)
    val lx = layer.x
    val ly = layer.y
    val copy = if (retouchAllLayers) d.shallowCopy() else null
    val sel = d.selection
    val lockAlpha = layer.props.lockAlpha
    runBusy("Healing…") {
        val res = withContext(Dispatchers.Default) {
            // Where it was painted (layer-local), exactly
            val cov = RetouchSession.readMask(buf, tb)
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var y1 = Int.MIN_VALUE
            for (y in 0 until tb.height()) for (x in 0 until tb.width()) {
                if ((cov[y * tb.width() + x].toInt() and 0xFF) > 8) {
                    x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
                }
            }
            if (x1 < x0) return@withContext null
            val hole = Rect(tb.left + x0, tb.top + y0, tb.left + x1 + 1, tb.top + y1 + 1)
            val span = max(hole.width(), hole.height())
            if (span > 700) throw IllegalStateException("That's a big area for spot healing: try AI Remove (or smaller strokes)")
            // Room around it to find a patch that matches
            val margin = (span * 3.4f + 24f).roundToInt()
            val region = Rect(hole).apply { inset(-margin, -margin) }
            val w = region.width()
            val h = region.height()
            val src = TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }
            val layerPx = RetouchSession.readPatch(src, region, 0, 0)
            val context = if (copy != null) {
                // The picture as you see it (in canvas pixels)
                val canvasRegion = Rect(region).apply { offset(lx, ly) }
                val bmp = DocRenderer.renderLayers(copy.children(0L), canvasRegion, copy.width, copy.height, doc = copy)
                IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h); bmp.recycle() }
            } else layerPx
            val holeBytes = RetouchSession.readMask(buf, region)
            if (sel != null) {
                val sm = RetouchSession.readMask(sel.mask(), Rect(region).apply { offset(lx, ly) })
                for (i in holeBytes.indices) holeBytes[i] = ((holeBytes[i].toInt() and 0xFF) * (sm[i].toInt() and 0xFF) / 255).toByte()
            }
            val healed = Heal.heal(context, holeBytes, w, h)
            val out = IntArray(w * h) { i ->
                val c = (holeBytes[i].toInt() and 0xFF) / 255f
                if (c <= 0f) layerPx[i] else RetouchSession.mix(layerPx[i], healed[i], c, lockAlpha)
            }
            region to out
        } ?: return@runBusy
        if (doc !== d || layer.tiles !== grid) return@runBusy
        val (region, out) = res
        val bmp = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, region.width(), 0, 0, region.width(), region.height())
        val saved = HashMap<Long, Bitmap?>()
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        for (k in grid.keysIn(android.graphics.RectF(region))) {
            val existing = grid[k]
            val t = grid.editableCopy(existing)
            Canvas(t).drawBitmap(bmp, (region.left - TileGrid.tx(k) * TileGrid.SIZE).toFloat(), (region.top - TileGrid.ty(k) * TileGrid.SIZE).toFloat(), p)
            // Don't keep new tiles that stayed empty
            if (existing == null && TileGrid.isBlank(t)) continue
            saved[k] = grid.swap(k, t)
        }
        bmp.recycle()
        if (saved.isEmpty()) return@runBusy
        history.record(TilePatch(layer, grid, saved, "Spot healing"))
        d.touched(layer)
    }
}
