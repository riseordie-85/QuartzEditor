package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.Gpu
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.ui.EditorViewModel.Companion.PREVIEW_PX
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Adjustments and filters on pixel layers, and adjustment layers. (Moved out of EditorViewModel.kt; same code, as extension functions.)

/** What the Adjustments and Filters panels show. */
fun EditorViewModel.currentFx(): FxParams = fxTarget?.props?.adjust ?: pendingFx ?: FxParams()

/** Panel change: edits an adjustment layer live, or tries the change on a pixel layer. */
fun EditorViewModel.editFx(fx: FxParams) {
    val a = fxTarget
    if (a != null) setPropsLive(a.id) { it.copy(adjust = fx) } else updateFx(fx)
}

/** A slider was let go (one undo step for adjustment layers). */
fun EditorViewModel.editFxDone() {
    if (fxTarget != null) commitProps("Adjustment layer")
}

/** Tries [fx] on the active layer (live preview; nothing changes until [applyFx]). */
fun EditorViewModel.updateFx(fx: FxParams) {
    val d = doc ?: return
    val l = d.active ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    if (l.needsRaster) {
        askRasterize(l) { updateFx(fx) }
        return
    }
    if (!l.isPixels) {
        toast("Pick a pixel layer (or add an adjustment layer)")
        return
    }
    pendingFx = fx
    val p = preview
    if (p?.fx != null && p.layerId == l.id) {
        preview = LayerPreview(l.id, fx = fx, proxy = p.proxy, proxyBounds = p.proxyBounds, selection = d.selection)
    } else {
        val b = l.tiles.tileBounds() ?: run {
            pendingFx = null
            toast("This layer is empty")
            return
        }
        val s = min(1f, PREVIEW_PX / max(b.width(), b.height()).toFloat())
        val (proxy, bounds) = DocRenderer.layerBitmap(l, s) ?: return
        preview = LayerPreview(l.id, fx = fx, proxy = proxy, proxyBounds = bounds, selection = d.selection)
    }
    d.invalidate()
}

/** Small copy of the active layer, or of the whole picture for an adjustment layer (filter thumbnails). */
fun EditorViewModel.ensureFxThumb() {
    val d = doc ?: return
    val l = d.active ?: return
    val key = l.id to (if (l.hasContent) l.pixelVersion else -1)
    if (fxThumb != null && key == fxThumbKey) return
    fxThumbKey = key
    fxThumb = if (l.hasContent) {
        val b = l.bounds() ?: return
        DocRenderer.layerBitmap(l, min(1f, 160f / max(b.width(), b.height())))?.first
    } else {
        DocRenderer.thumbnail(d.shallowCopy().also { c -> c.layers.removeAll { x -> x.isAdjustment } }, 160)
    }
}

fun EditorViewModel.cancelFx() {
    pendingFx = null
    if (preview?.fx != null) preview = null
    doc?.invalidate()
}

/**
 * Applies the adjustments/filter being tried to the layer at full size, then runs [onDone].
 * The busy overlay blocks other edits while it runs.
 */
fun EditorViewModel.applyFx(onDone: () -> Unit = {}) {
    val d = doc ?: return
    val fx = pendingFx ?: return
    if (busy != null) return
    val l = d.layer(preview?.layerId ?: d.activeId) ?: return
    if (fx.isIdentity || l.tiles.isEmpty) {
        cancelFx()
        onDone()
        return
    }
    val source = l.tiles
    // The selection the preview showed
    val sel = preview?.takeIf { it.fx != null }?.selection
    runBusy("Applying…", onDone) {
        val grid = withContext(Dispatchers.Default) {
            val (raw, rawBounds) = DocRenderer.layerBitmap(l) ?: throw IllegalStateException("The layer is empty")
            // A blur spreads past the pixels: give it room
            val pad = if (fx.blur > 0f) Gpu.blurReach(fx.blur) else 0
            val src = if (pad > 0) Gpu.padded(raw, pad).also { raw.recycle() } else raw
            val bounds = RectF(rawBounds).apply { inset(-pad.toFloat(), -pad.toFloat()) }
            val ox = l.x + bounds.left
            val oy = l.y + bounds.top
            var out = Gpu.applyFx(src, fx, ox, oy, d.width.toFloat(), d.height.toFloat())
            // A layer covering the canvas (like a photo) blurs with its edges carrying on past the
            // canvas edge, so they don't fade (the part past the canvas keeps the soft version)
            if (fx.blur > 0f && ox <= -pad && oy <= -pad && ox + src.width >= d.width + pad && oy + src.height >= d.height + pad) {
                val cx = (-ox).roundToInt()
                val cy = (-oy).roundToInt()
                val crop = Bitmap.createBitmap(src, cx, cy, d.width, d.height)
                val edged = Gpu.applyFx(crop, fx, 0f, 0f, d.width.toFloat(), d.height.toFloat(), clampEdges = true)
                crop.recycle()
                Canvas(out).drawBitmap(edged, cx.toFloat(), cy.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
                edged.recycle()
            }
            if (sel != null) {
                // Only the selected part changes: original × (1 − selected) + adjusted × selected
                sel.keepInside(Canvas(out), ox, oy)
                val mixed = src.copy(Bitmap.Config.ARGB_8888, true)
                val mc = Canvas(mixed)
                sel.mask().drawTo(mc, -ox, -oy, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                mc.drawBitmap(out, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) })
                out.recycle()
                out = mixed
            }
            src.recycle()
            TileGrid.fromBitmap(out, bounds.left.toInt(), bounds.top.toInt(), alphaOnly = false).also { out.recycle() }
        }
        pendingFx = null
        preview = null
        if (doc === d && l.tiles === source) {
            val label = when {
                fx.filter != com.tim.quartzeditor.engine.FilterPreset.NONE -> fx.filter.label
                fx.kind == com.tim.quartzeditor.engine.FxKind.LEVELS -> "Levels"
                fx.kind == com.tim.quartzeditor.engine.FxKind.CURVES -> "Curves"
                fx.kind == com.tim.quartzeditor.engine.FxKind.BLUR -> "Gaussian Blur"
                fx.kind != com.tim.quartzeditor.engine.FxKind.ADJUST -> PopupKind.forKind(fx.kind).title
                else -> "Adjustments"
            }
            stackEdit(label) { l.tiles = grid }
        }
        fxThumb = null
    }
}

/**
 * Anything else you do first applies the adjustments you were trying (like switching tools in
 * Procreate). Returns true when it started that; [then] runs once they're applied.
 */
internal fun EditorViewModel.pendingFirst(then: () -> Unit): Boolean {
    if (textEdit != null) finishText()
    if (cropBox != null) {
        applyCrop(onDone = then)
        return true
    }
    if (pendingFx != null) {
        applyFx(onDone = then)
        return true
    }
    if (transform != null) {
        applyTransform(onDone = then)
        return true
    }
    if (warp != null) {
        applyWarp(onDone = then)
        return true
    }
    return false
}
