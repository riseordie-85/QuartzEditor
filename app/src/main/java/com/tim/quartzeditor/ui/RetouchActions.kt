package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.PointF
import android.graphics.RectF
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
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The retouch brushes (one toolbar button, hold for the list): Spot Healing, Clone Stamp, Dodge,
// Burn, Smudge, Blur and Sharpen.

fun EditorViewModel.chooseRetouch(k: RetouchKind) {
    retouchKind = k
    prefs.edit().putString("retouchKind", k.name).putString(if (k.heals) "lastHeal" else "lastTone", k.name).apply()
    if (tool != Tool.RETOUCH) onToolButton(Tool.RETOUCH)
    if (k == RetouchKind.CLONE && cloneSource == null) toast("Hold ⇧ and tap where to copy from (or tap Pick source)")
}

/** Sets where the Clone Stamp copies from. */
fun EditorViewModel.setCloneSource(x: Float, y: Float) {
    cloneSource = PointF(x, y)
    cloneOffset = null
    pickingCloneSource = false
    toast("Copying from here: now paint where it should go")
    // With all layers: the picture as it is now, ready before the first stroke
    cloneFlat = null
    val d = doc ?: return
    if (retouchAllLayers) {
        val copy = d.shallowCopy()
        viewModelScope.launch {
            val src = withContext(Dispatchers.Default) {
                runCatching {
                    val pic = DocRenderer.flatten(copy)
                    CloneSource(TileGrid.fromBitmap(pic, 0, 0, alphaOnly = false).also { pic.recycle() }, 0, 0)
                }.getOrNull()
            }
            if (doc === d && cloneSource?.x == x && cloneSource?.y == y) cloneFlat = src
        }
    }
}

/** Biggest retouch brush (they work dab by dab as you paint). */
private const val MAX_RETOUCH = 300f

/** The biggest area Spot Healing works on (across, canvas pixels); bigger is for AI Remove. */
internal const val SPOT_HEAL_MAX = 250

/** Spot healing: says (once a stroke) when it's grown past what it can heal. */
internal fun EditorViewModel.checkHealReach(x: Float, y: Float) {
    val r = healReach ?: return
    r.union(x, y)
    if (healWarned) return
    val span = max(r.width(), r.height()) + retouchBrush.size.coerceAtMost(MAX_RETOUCH)
    if (span > SPOT_HEAL_MAX) {
        healWarned = true
        toast("Past $SPOT_HEAL_MAX px: too big for spot healing. Lift and use smaller strokes, or AI Remove")
    }
}

/**
 * Starts a retouch stroke at canvas point ([x], [y]). False when it can't. [pressure] is the pen's
 * (on its curve; 1 for a finger): it sets the strength when the retouch brush has that turned on.
 */
internal fun EditorViewModel.beginRetouch(x: Float, y: Float, pressure: Float = 1f): Boolean {
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
    // Retouch work happens as you paint: bigger than this would make the screen stutter
    val b = retouchBrush.let { if (it.size > MAX_RETOUCH) it.copy(size = MAX_RETOUCH) else it }
    strokeHeals = null
    when (val k = retouchKind) {
        RetouchKind.SPOT_HEAL, RetouchKind.AI_REMOVE -> {
            strokeHeals = k
            // Paint where to heal (shown in pink); it heals when you lift your finger
            val s = StrokeSession(layer, StrokeTarget.PIXELS, false, 0xFFFF2DAA.toInt(), background, 0.45f, com.tim.quartzeditor.engine.BrushTip.ROUND, b.size, b.softness, d.selection)
            s.addPoint(x, y)
            stroke = s
            healReach = if (k == RetouchKind.SPOT_HEAL) RectF(x, y, x, y) else null
            healWarned = false
        }
        RetouchKind.CLONE -> {
            val src = cloneSource ?: run {
                toast("Pick where to copy from first: hold ⇧ and tap there (or tap Pick source)")
                return false
            }
            val off = (if (cloneAligned) cloneOffset else null) ?: PointF(src.x - x, src.y - y)
            cloneOffset = off
            val source = if (retouchAllLayers) {
                // The picture as it was when the source was picked (worked out then)
                cloneFlat ?: run {
                    val pic = DocRenderer.flatten(d.shallowCopy())
                    CloneSource(TileGrid.fromBitmap(pic, 0, 0, alphaOnly = false).also { pic.recycle() }, 0, 0).also { cloneFlat = it }
                }
            } else {
                CloneSource(TileGrid(alphaOnly = false).also { it.tiles.putAll(layer.tiles.tiles) }, layer.x, layer.y)
            }
            val r = RetouchSession(layer, k, b.size, b.softness, b.opacity, d.selection, source = source, offX = off.x, offY = off.y)
            r.addPoint(x, y, retouchPressure(pressure))
            retouch = r
        }
        else -> {
            val r = RetouchSession(layer, k, b.size, b.softness, b.opacity, d.selection, range = dodgeRange)
            r.addPoint(x, y, retouchPressure(pressure))
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

/** Spot Healing or AI Remove: works on where the stroke painted, in the background. */
internal fun EditorViewModel.finishSpotHeal(s: StrokeSession, ai: Boolean) {
    val d = doc ?: return
    stroke = null
    d.invalidate()
    val buf = s.buffer
    val tb = buf.tileBounds() ?: return
    healArea(s.layer, tb, { r -> RetouchSession.readMask(buf, r) }, ai)
}

/** AI Remove of what's selected (on the layer you're on). */
fun EditorViewModel.removeSelected() {
    val d = doc ?: return
    if (pendingFirst { removeSelected() }) return
    val sel = d.selection ?: run {
        toast("Select what to remove first")
        return
    }
    val layer = d.active
    if (blockPaint(layer)) return
    layer!!
    if (layer.isGroup || layer.isAdjustment) {
        toast("Pick a pixel layer")
        return
    }
    val m = sel.mask()
    val lx = layer.x
    val ly = layer.y
    val b = sel.reach.let { Rect(kotlin.math.floor(it.left).toInt(), kotlin.math.floor(it.top).toInt(), kotlin.math.ceil(it.right).toInt(), kotlin.math.ceil(it.bottom).toInt()) }
    if (!b.intersect(0, 0, d.width, d.height)) return
    b.offset(-lx, -ly)
    healArea(layer, b, { r -> RetouchSession.readMask(m, Rect(r).apply { offset(lx, ly) }) }, ai = true, useSelection = false)
}

/**
 * Heals (or, with [ai], removes with the AI model) where [holeAt] says, around [bounds] (layer-local),
 * on [layer]. [holeAt] gives how much of each pixel of a layer-local area to fill.
 */
private fun EditorViewModel.healArea(
    layer: com.tim.quartzeditor.engine.Layer,
    bounds: Rect,
    holeAt: (Rect) -> ByteArray,
    ai: Boolean,
    useSelection: Boolean = true,
) {
    val d = doc ?: return
    if (ai && !com.tim.quartzeditor.engine.Models.has(ctx, com.tim.quartzeditor.engine.Models.MIGAN)) {
        toast("This build doesn't include the AI Remove model. Build once with internet on so Android Studio downloads it")
        return
    }
    val grid = layer.tiles
    val tiles = HashMap(grid.tiles)
    val lx = layer.x
    val ly = layer.y
    // AI Remove always looks at the picture as you see it (it needs real pixels around the hole)
    val copy = if (retouchAllLayers || ai) d.shallowCopy() else null
    val sel = if (useSelection) d.selection else null
    val lockAlpha = layer.props.lockAlpha
    val label = if (ai) "AI Remove" else "Spot healing"
    runBusy(if (ai) "Removing…" else "Healing…") {
        val res = withContext(Dispatchers.Default) {
            // Where to work (layer-local), exactly
            val cov = holeAt(bounds)
            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE
            var y1 = Int.MIN_VALUE
            for (y in 0 until bounds.height()) for (x in 0 until bounds.width()) {
                if ((cov[y * bounds.width() + x].toInt() and 0xFF) > 8) {
                    x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
                }
            }
            if (x1 < x0) return@withContext null
            val hole = Rect(bounds.left + x0, bounds.top + y0, bounds.left + x1 + 1, bounds.top + y1 + 1)
            val span = max(hole.width(), hole.height())
            if (!ai && span > SPOT_HEAL_MAX) throw IllegalStateException("That's a big area for spot healing: try AI Remove (or smaller strokes)")
            // Room around it: to find a matching patch (healing) or to see what's there (AI)
            val margin = if (ai) max(96, (span * 0.6f).roundToInt()) else (span * 3.4f + 24f).roundToInt()
            val region = Rect(hole).apply { inset(-margin, -margin) }
            if (ai) {
                // The model needs real pixels around the hole: keep it on the picture
                val canvas = Rect(-lx, -ly, d.width - lx, d.height - ly)
                if (!region.intersect(canvas)) return@withContext null
            }
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
            val holeBytes = holeAt(region)
            if (sel != null) {
                val sm = RetouchSession.readMask(sel.mask(), Rect(region).apply { offset(lx, ly) })
                for (i in holeBytes.indices) holeBytes[i] = ((holeBytes[i].toInt() and 0xFF) * (sm[i].toInt() and 0xFF) / 255).toByte()
            }
            val filled = if (ai) {
                // A little past the painted edge, so no outline of the old thing is left
                val grown = grow(holeBytes, w, h, 3)
                val out = com.tim.quartzeditor.engine.MiGan.fill(ctx, context, grown, w, h)
                for (i in holeBytes.indices) holeBytes[i] = grown[i]
                out
            } else Heal.heal(context, holeBytes, w, h)
            val out = IntArray(w * h) { i ->
                val c = (holeBytes[i].toInt() and 0xFF) / 255f
                if (c <= 0f) layerPx[i] else RetouchSession.mix(layerPx[i], filled[i], c, lockAlpha)
            }
            // Only what changed (the hole and a little around it) goes back into the layer
            val write = Rect(hole).apply { inset(-6, -6) }
            if (!write.intersect(region)) return@withContext null
            val ww = write.width()
            val wh = write.height()
            val part = IntArray(ww * wh)
            for (y in 0 until wh) System.arraycopy(out, (write.top - region.top + y) * w + (write.left - region.left), part, y * ww, ww)
            write to part
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
        history.record(TilePatch(layer, grid, saved, label))
        d.touched(layer)
    }
}

/** [m] (0..255 per pixel) with everything set grown by [r] pixels, at full strength. */
private fun grow(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
    val on = BooleanArray(w * h) { (m[it].toInt() and 0xFF) > 8 }
    val tmp = BooleanArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        if (!on[y * w + x]) continue
        for (dx in -r..r) { val xx = x + dx; if (xx in 0 until w) tmp[y * w + xx] = true }
    }
    val out = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        if (!tmp[y * w + x]) continue
        for (dy in -r..r) { val yy = y + dy; if (yy in 0 until h) out[yy * w + x] = 255.toByte() }
    }
    // Keep the soft painted edge where it's stronger
    for (i in out.indices) if ((m[i].toInt() and 0xFF) > (out[i].toInt() and 0xFF)) out[i] = m[i]
    return out
}
