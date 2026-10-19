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
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.SmartArt
import com.tim.quartzeditor.engine.SmartFilter
import com.tim.quartzeditor.engine.SmartSpec
import com.tim.quartzeditor.engine.Transformer
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.ui.EditorViewModel.Companion.PREVIEW_PX
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

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
    // A smart layer keeps it as a smart filter (still editable)
    if (l.isSmart && l.props.smart != null) {
        trySmartFilter(l, fx)
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
    smartFilterIndex = -1
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
    if (l.isSmart && l.props.smart != null) {
        applySmartFilter(l, fx, onDone)
        return
    }
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
        if (doc === d && l.tiles === source) stackEdit(fxLabel(fx)) { l.tiles = grid }
        fxThumb = null
    }
}

/** What [fx] is called (for undo and the Layers panel). */
internal fun fxLabel(fx: FxParams): String = when {
    fx.filter != com.tim.quartzeditor.engine.FilterPreset.NONE -> fx.filter.label
    fx.kind == com.tim.quartzeditor.engine.FxKind.LEVELS -> "Levels"
    fx.kind == com.tim.quartzeditor.engine.FxKind.CURVES -> "Curves"
    fx.kind == com.tim.quartzeditor.engine.FxKind.BLUR -> "Gaussian Blur"
    fx.kind != com.tim.quartzeditor.engine.FxKind.ADJUST -> PopupKind.forKind(fx.kind).title
    fx.adjust.size == 1 -> fx.adjust.keys.first().label
    else -> "Adjustments"
}

// ===================================================================== smart filters

/**
 * Tries [fx] as a smart filter on smart layer [l]: a new one at the top of its list, or the one
 * picked from the Layers panel ([editSmartFilter]). Shown on a small copy until applied.
 */
private fun EditorViewModel.trySmartFilter(l: Layer, fx: FxParams) {
    val d = doc ?: return
    val spec = l.props.smart ?: return
    if (smartFilterLayer != l.id || smartFilterIndex !in 0..spec.filters.size) {
        smartFilterLayer = l.id
        smartFilterIndex = spec.filters.size
        toast("Added as a smart filter (it stays editable)")
    }
    pendingFx = fx
    val p = preview
    if (p?.fx != null && p.layerId == l.id) {
        preview = LayerPreview(l.id, fx = fx, proxy = p.proxy, proxyBounds = p.proxyBounds)
    } else {
        val b = l.tiles.tileBounds() ?: run {
            pendingFx = null
            smartFilterIndex = -1
            toast("This layer is empty")
            return
        }
        val s = min(1f, PREVIEW_PX / max(b.width(), b.height()).toFloat())
        val (proxy, bounds) = DocRenderer.layerBitmap(l, s) ?: return
        preview = LayerPreview(l.id, fx = fx, proxy = proxy, proxyBounds = bounds)
    }
    d.invalidate()
}

/** Puts the smart filter being tried into the layer (or changes the one being edited), as one undo step. */
private fun EditorViewModel.applySmartFilter(l: Layer, fx: FxParams, onDone: () -> Unit) {
    val spec = l.props.smart ?: return
    val i = if (smartFilterLayer == l.id) smartFilterIndex else -1
    val list = spec.filters.toMutableList()
    val existing = i in list.indices
    if (!existing && fx.isIdentity) {
        cancelFx()
        onDone()
        return
    }
    when {
        // Set back to nothing: the filter goes
        existing && fx.isIdentity -> list.removeAt(i)
        existing -> list[i] = list[i].copy(fx = fx)
        else -> list.add(SmartFilter(fx, fxLabel(fx)))
    }
    changeSmartFilters(l, list, if (existing) "Edit smart filter" else "Smart filter", onDone)
}

/**
 * Gives smart layer [l] the smart filters [list] and draws it again, as one undo step named
 * [label]; whatever filter was being tried is put away.
 */
internal fun EditorViewModel.changeSmartFilters(l: Layer, list: List<SmartFilter>, label: String, onDone: () -> Unit = {}) {
    val d = doc ?: return
    val spec = l.props.smart ?: return
    if (busy != null) return
    val newSpec = spec.withFilters(list)
    val before = l.props
    runBusy("Applying…", onDone) {
        val clip = Transformer.limitFor(d.width, d.height).apply { offset(-l.x, -l.y) }
        val grid = withContext(Dispatchers.Default) { SmartArt.rasterize(newSpec, clip) }
        pendingFx = null
        smartFilterIndex = -1
        if (preview?.layerId == l.id) preview = null
        if (doc === d && l.props === before) {
            stackEdit(label) {
                l.props = l.props.copy(smart = newSpec)
                l.tiles = grid
            }
        }
        fxThumb = null
    }
}

/** Opens smart filter [i] of smart layer [layerId] in its panel, to change it. */
fun EditorViewModel.editSmartFilter(layerId: Long, i: Int) {
    val d = doc ?: return
    if (pendingFirst { editSmartFilter(layerId, i) }) return
    val l = d.layer(layerId) ?: return
    val spec = l.props.smart ?: return
    val f = spec.filters.getOrNull(i) ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    selectLayer(layerId, mask = false)
    runBusy("Preparing…") {
        // The layer as it is before this filter (the filter itself is what's shown changing)
        val made = withContext(Dispatchers.Default) { smartProxy(spec, spec.filters.take(i).filter { it.enabled }) }
            ?: return@runBusy toast("This layer is empty")
        if (doc !== d || l.props.smart !== spec) return@runBusy
        smartFilterLayer = l.id
        smartFilterIndex = i
        pendingFx = f.fx
        preview = LayerPreview(l.id, fx = f.fx, proxy = made.first, proxyBounds = made.second)
        popup = if (f.fx.kind == com.tim.quartzeditor.engine.FxKind.ADJUST && f.fx.filter != com.tim.quartzeditor.engine.FilterPreset.NONE) PopupKind.FILTERS
        else PopupKind.forKind(f.fx.kind)
        fxThumb = null
        d.invalidate()
    }
}

/** Shows or hides smart filter [i] (or, with [i] −1, all of them). */
fun EditorViewModel.toggleSmartFilter(layerId: Long, i: Int) {
    val l = doc?.layer(layerId) ?: return
    val list = l.props.smart?.filters ?: return
    if (pendingFirst { toggleSmartFilter(layerId, i) }) return
    val on = if (i < 0) list.none { it.enabled } else !(list.getOrNull(i)?.enabled ?: return)
    changeSmartFilters(l, list.mapIndexed { k, f -> if (i < 0 || k == i) f.copy(enabled = on) else f }, if (on) "Show smart filter" else "Hide smart filter")
}

/** Deletes smart filter [i] (or, with [i] −1, all of them). */
fun EditorViewModel.deleteSmartFilter(layerId: Long, i: Int) {
    val l = doc?.layer(layerId) ?: return
    val list = l.props.smart?.filters ?: return
    if (pendingFirst { deleteSmartFilter(layerId, i) }) return
    changeSmartFilters(l, if (i < 0) emptyList() else list.filterIndexed { k, _ -> k != i }, if (i < 0) "Delete smart filters" else "Delete smart filter")
}

/** Smart filter [i]'s strength (0..1), as one undo step. */
fun EditorViewModel.setSmartFilterOpacity(layerId: Long, i: Int, opacity: Float) {
    val l = doc?.layer(layerId) ?: return
    val list = l.props.smart?.filters ?: return
    if (i !in list.indices) return
    changeSmartFilters(l, list.mapIndexed { k, f -> if (k == i) f.copy(opacity = opacity.coerceIn(0f, 1f)) else f }, "Smart filter opacity")
}

/**
 * A small copy of smart layer [spec] drawn with only [before] of its smart filters (background
 * thread), and the layer-local area it covers.
 */
internal fun smartProxy(spec: SmartSpec, before: List<SmartFilter>): Pair<Bitmap, RectF>? {
    val full = spec.withFilters(before).bounds()
    val s = min(1f, PREVIEW_PX / max(full.width(), full.height()).coerceAtLeast(1f))
    val m = android.graphics.Matrix().apply { setScale(s, s) }
    val small = spec.withQuad(SmartSpec.mapped(spec.quad, m)).withFilters(before.map { it.copy(fx = it.fx.copy(blur = it.fx.blur * s)) })
    val grid = SmartArt.rasterize(small, android.graphics.Rect(-1_000_000, -1_000_000, 1_000_000, 1_000_000))
    val b = grid.tileBounds() ?: return null
    val bmp = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
    grid.drawTo(Canvas(bmp), -b.left.toFloat(), -b.top.toFloat(), Paint())
    return bmp to RectF(b.left / s, b.top / s, b.right / s, b.bottom / s)
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
    if (enhance != null) {
        applyEnhance(onDone = then)
        return true
    }
    return false
}

/** Reads a lookup table file (.cube or .3dl) for Color Lookup; [then] gets it. */
fun EditorViewModel.loadColorLookup(uri: android.net.Uri, then: (com.tim.quartzeditor.engine.ColorLookup) -> Unit) {
    viewModelScope.launch {
        val lut = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val name = com.tim.quartzeditor.data.Media.displayName(ctx, uri).take(40)
                val text = ctx.contentResolver.openInputStream(uri)?.use { s ->
                    val bytes = s.readBytes()
                    if (bytes.size > 40 * 1024 * 1024) null else String(bytes, Charsets.ISO_8859_1)
                }
                text?.let { com.tim.quartzeditor.engine.ColorLookup.parse(it, name) }
            }.getOrNull()
        }
        if (lut == null) toast("That isn't a lookup table this can read (.cube or .3dl)") else then(lut)
    }
}
