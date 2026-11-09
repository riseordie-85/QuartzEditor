package com.tim.quartzeditor.ui

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import com.tim.quartzeditor.engine.Adjust
import com.tim.quartzeditor.engine.FilterPreset
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.Selection
import com.tim.quartzeditor.engine.SelectMode
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.TransformMode
import com.tim.quartzeditor.engine.solidGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// What Photoshop's keyboard shortcuts do that had no button of their own here: layer order, merge
// and stamp visible, ungroup, lock, reselect, invert / desaturate, canvas size, screen modes,
// flow, text size, solo and view-mask clicks, transform again, Quick Mask, and the view tools.

/** R and Z: drags turn the view (R) or zoom it (Z); a click with Z zooms in (Alt: out). */
enum class ViewTool(val label: String) { ROTATE("Rotate view"), ZOOM("Zoom") }

/** R or Z: that view tool on, or off again (pressing it twice, or Esc). */
fun EditorViewModel.toggleViewTool(t: ViewTool) {
    viewTool = if (viewTool == t) null else t
}

// ===================================================================== layer order

/**
 * Moves the active layer within its folder: [pick] gets its place (0 = the top) and how many share
 * the folder, and gives the new place.
 */
private fun EditorViewModel.reorderActive(label: String, pick: (Int, Int) -> Int) {
    if (pendingFirst { reorderActive(label, pick) }) return
    val d = doc ?: return
    val l = d.active ?: return
    // Children of each folder, top first
    val kids = HashMap<Long, MutableList<Layer>>()
    for (x in d.layers.asReversed()) kids.getOrPut(x.props.parent) { mutableListOf() }.add(x)
    val list = kids[l.props.parent] ?: return
    val i = list.indexOf(l)
    if (i < 0) return
    val j = pick(i, list.size).coerceIn(0, list.size - 1)
    if (i == j) return
    list.removeAt(i)
    list.add(j, l)
    val order = ArrayList<Layer>(d.layers.size)
    fun emit(parent: Long) {
        for (x in (kids[parent] ?: return).asReversed()) {
            if (x.isGroup) emit(x.id)
            order.add(x)
        }
    }
    emit(0L)
    if (order.size != d.layers.size) return
    stackEdit(label) {
        d.layers.clear()
        d.layers.addAll(order)
    }
}

/** Ctrl+] / Ctrl+[: the active layer up or down one place in its folder. */
fun EditorViewModel.moveLayerStep(up: Boolean) = reorderActive(if (up) "Bring forward" else "Send backward") { i, _ -> if (up) i - 1 else i + 1 }

/** Ctrl+Shift+] / Ctrl+Shift+[: to the top or bottom of its folder. */
fun EditorViewModel.moveLayerToEdge(top: Boolean) = reorderActive(if (top) "Bring to front" else "Send to back") { _, n -> if (top) 0 else n - 1 }

/** Alt+] / Alt+[: picks the layer above or below in the list (open folders' layers too). */
fun EditorViewModel.selectLayerStep(up: Boolean) {
    val d = doc ?: return
    val rows = ArrayList<Layer>()
    fun add(parent: Long) {
        for (l in d.children(parent).asReversed()) {
            rows.add(l)
            if (l.isGroup && !l.collapsed) add(l.id)
        }
    }
    add(0L)
    val i = rows.indexOfFirst { it.id == d.activeId }
    val next = rows.getOrNull(if (i < 0) 0 else if (up) i - 1 else i + 1) ?: return
    selectLayer(next.id)
}

// ===================================================================== merge, stamp, ungroup, lock

/** The layers on top (not in folders) that show. */
private fun EditorViewModel.visibleTops(): List<Layer> {
    val d = doc ?: return emptyList()
    return d.children(0L).filter { d.effectivelyVisible(it) && !it.props.quickMask }
}

/** Ctrl+Shift+E: every visible layer merged into one (hidden layers stay as they are). */
fun EditorViewModel.mergeVisible() {
    val d = doc ?: return
    if (pendingFirst { mergeVisible() }) return
    val tops = visibleTops()
    if (tops.size < 2) {
        toast("There's only one visible layer")
        return
    }
    if (tops.any { it.props.locked }) {
        toast("Unlock the visible layers to merge them")
        return
    }
    runBusy("Merging…") {
        val merged = withContext(Dispatchers.Default) {
            val fx = fullEffects(d.layers.filter { d.effectivelyVisible(it) })
            val bmp = com.tim.quartzeditor.engine.DocRenderer.renderLayers(tops, Rect(0, 0, d.width, d.height), d.width, d.height, effects = { fx[it.id] }, doc = d)
            TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false).also { bmp.recycle() }
        }
        stackEdit("Merge visible") {
            // They and everything in them go; the merged layer takes the topmost one's place
            val within = d.layers.filter { x -> tops.any { t -> t === x || d.isInside(x, t) } }
            // (What's hidden inside them wasn't merged: it stays, out of the folder that goes)
            val gone = within.filter { x -> d.effectivelyVisible(x) }.toSet()
            for (x in within) if (x !in gone && d.layers.none { it.id == x.props.parent && it !in gone }) x.props = x.props.copy(parent = 0L)
            val topIndex = d.layers.indexOfLast { it in gone }
            val kept = d.layers.filter { it !in gone }
            val at = d.layers.subList(0, topIndex + 1).count { it !in gone }
            val l = Layer(d.newId(), LayerProps(d.uniqueName("Merged")), merged)
            d.layers.clear()
            d.layers.addAll(kept)
            d.layers.add(at.coerceIn(0, d.layers.size), l)
            d.activeId = l.id
            d.editMask = false
        }
    }
}

/** Ctrl+Shift+Alt+E: everything you see as a new layer above the active one (the rest stays). */
fun EditorViewModel.stampVisible() {
    val d = doc ?: return
    if (pendingFirst { stampVisible() }) return
    val tops = visibleTops()
    if (tops.isEmpty()) return
    runBusy("Stamping…") {
        val grid = withContext(Dispatchers.Default) {
            val fx = fullEffects(d.layers.filter { d.effectivelyVisible(it) })
            val bmp = com.tim.quartzeditor.engine.DocRenderer.renderLayers(tops, Rect(0, 0, d.width, d.height), d.width, d.height, effects = { fx[it.id] }, doc = d)
            TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false).also { bmp.recycle() }
        }
        stackEdit("Stamp visible") {
            val (at, parent) = insertPoint(d)
            val l = Layer(d.newId(), LayerProps(d.uniqueName("Stamp"), parent = parent), grid)
            d.layers.add(at, l)
            d.activeId = l.id
            d.editMask = false
        }
    }
}

/** Ctrl+Shift+G: the active folder goes; what was in it stays where it was. */
fun EditorViewModel.ungroupActive() {
    val d = doc ?: return
    if (pendingFirst { ungroupActive() }) return
    val g = d.active ?: return
    if (!g.isGroup) {
        toast("Pick a folder to ungroup")
        return
    }
    stackEdit("Ungroup") {
        val inside = d.layers.filter { it.props.parent == g.id }
        for (x in inside) x.props = x.props.copy(parent = g.props.parent)
        d.layers.remove(g)
        d.activeId = inside.lastOrNull()?.id ?: d.layers.lastOrNull()?.id ?: -1L
        d.editMask = false
    }
}

/** Ctrl+/: the active layer locked, or unlocked. */
fun EditorViewModel.toggleLayerLock() {
    val l = doc?.active ?: return
    setProps(l.id, if (l.props.locked) "Unlock layer" else "Lock layer") { it.copy(locked = !it.locked) }
}

// ===================================================================== selections

/** Ctrl+Shift+D: the selection from before the last Deselect. */
fun EditorViewModel.reselect() {
    val s = lastSelection ?: run {
        toast("Nothing to reselect")
        return
    }
    setSelection("Reselect", s)
}

/** Ctrl+Alt+R: Select and mask for the active layer's mask (made from the selection first, if it has none). */
fun EditorViewModel.selectAndMaskShortcut() {
    val d = doc ?: return
    val l = d.active ?: return
    when {
        l.mask != null -> openMaskRefine()
        d.selection != null -> {
            maskFromSelection()
            // (The mask is made in the background; Select and mask opens once it's there)
            if (busy != null || l.mask != null) {
                maskRefineFor = null
                pendingMaskRefine = l.id
            }
        }
        else -> toast("Select something, or add a mask, first")
    }
}

// ===================================================================== image

/** An adjustment applied straight away to the active layer (a smart filter on a smart layer). */
fun EditorViewModel.quickFx(fx: FxParams) {
    if (pendingFirst { quickFx(fx) }) return
    val l = doc?.active ?: return
    if (l.needsRaster && !l.isSmart) {
        askRasterize(l) { quickFx(fx) }
        return
    }
    updateFx(fx)
    if (pendingFx != null) applyFx { popup = null }
}

/** Ctrl+I: the active layer's colors inverted. */
fun EditorViewModel.invertLayer() = quickFx(FxParams(filter = FilterPreset.INVERT))

/** Ctrl+Shift+U: the active layer in grays. */
fun EditorViewModel.desaturateLayer() = quickFx(FxParams(adjust = mapOf(Adjust.SATURATION to -1f)))

/**
 * Image › Canvas size: the canvas becomes [w]×[h], the picture staying where [ax], [ay] say (0 left
 * or top, 0.5 the middle, 1 right or bottom); new room is see-through.
 */
fun EditorViewModel.resizeCanvas(w: Int, h: Int, ax: Float, ay: Float) {
    val d = doc ?: return
    val (nw, nh) = com.tim.quartzeditor.engine.Doc.fitted(w.coerceAtLeast(1), h.coerceAtLeast(1))
    if (nw == d.width && nh == d.height) return
    val dx = ((nw - d.width) * ax).roundToInt().toFloat()
    val dy = ((nh - d.height) * ay).roundToInt().toFloat()
    canvasTransform("Canvas size", nw, nh) { m -> m.postTranslate(dx, dy) }
}

// ===================================================================== view, brushes, text

/** F: everything → panels hidden → only the picture → everything. */
/**
 * F, like Photoshop: full screen (Android's status and navigation bars hidden), then full screen
 * with nothing but the picture on black, then back to normal.
 */
fun EditorViewModel.cycleScreenMode() {
    screenMode = (screenMode + 1) % 3
    toast(when (screenMode) {
        1 -> "Full screen (F again: only the picture)"
        2 -> "Only the picture (F again: normal)"
        else -> "Normal screen"
    })
}

/** View → Full screen: Android's bars hidden (or back), the editor as it is. */
fun EditorViewModel.toggleFullScreen() {
    screenMode = if (screenMode == 0) 1 else 0
}

/** Shift+1 … 0: the brush's flow 10% … 100%. */
fun EditorViewModel.setBrushFlow(flow: Float) {
    val t = sliderTool
    updateBrush(t) { it.copy(style = it.style.copy(flow = flow)) }
    toast("Flow ${(flow * 100).roundToInt()}%")
}

/** Ctrl+Shift+> / <: the text a size bigger or smaller (the text being typed, the text layer picked, or new text). */
fun EditorViewModel.changeTextSize(bigger: Boolean) {
    val l = editingText ?: doc?.active?.takeIf { it.isText }
    val now = l?.props?.text?.size ?: textStyle.size.takeIf { it > 0f } ?: 96f
    val next = (if (bigger) now * 1.1f + 1f else now / 1.1f - 1f).coerceIn(1f, 4000f).roundToInt().toFloat()
    textStyle = textStyle.copy(size = next)
    saveVectorPrefs()
    if (l != null) editVector(l.id, "Text size") { p -> p.copy(text = p.text?.copy(size = next)) }
    toast("Text size ${next.roundToInt()} px")
}

// ===================================================================== clicks with keys

/** Alt-click on an eye: only that layer shows; Alt-click again puts the others back. */
fun EditorViewModel.toggleSolo(id: Long) {
    val d = doc ?: return
    val r = soloRestore
    soloRestore = null
    // (Only while the solo is still as it was left: same file, nothing shown or hidden since, not undone)
    if (r != null && r.doc === d && d.layers.filter { it.props.visible }.map { it.id }.toSet() == r.shown) {
        stackEdit("Show all layers") {
            for (x in d.layers) r.before[x.id]?.let { v -> if (x.props.visible != v) x.props = x.props.copy(visible = v) }
        }
        return
    }
    val l = d.layer(id) ?: return
    val before = d.layers.associate { it.id to it.props.visible }
    stackEdit("Show only this layer") {
        for (x in d.layers) {
            // It, the folders it's in, and what's in it (if it's a folder) stay as they are
            val keep = x === l || d.isInside(l, x) || d.isInside(x, l)
            val v = if (keep) (if (x === l) true else x.props.visible) else false
            if (x.props.visible != v) x.props = x.props.copy(visible = v)
        }
    }
    soloRestore = SoloRestore(d, before, d.layers.filter { it.props.visible }.map { it.id }.toSet())
}

/** Alt-click on a mask: the picture shows the mask (Alt-click again, or Esc, to go back). */
fun EditorViewModel.toggleViewMask(id: Long) {
    viewMaskOf = if (viewMaskOf == id) null else id
    doc?.invalidate()
}

// ===================================================================== transform again

/** Ctrl+Shift+T: the last transform done again, on the active layer. */
fun EditorViewModel.transformAgain() {
    val m = lastTransform ?: run {
        toast("Transform something first")
        return
    }
    if (pendingFirst { transformAgain() }) return
    // (The layer is prepared in the background first)
    if (transform == null) {
        beginTransform { transformAgainNow(m) }
        return
    }
    transformAgainNow(m)
}

private fun EditorViewModel.transformAgainNow(m: Matrix) {
    val t = transform ?: return
    val c = t.start.corners()
    val pts = FloatArray(8)
    c.forEachIndexed { i, p ->
        pts[i * 2] = p.x
        pts[i * 2 + 1] = p.y
    }
    m.mapPoints(pts)
    updateTransform { it.copy(quad = List(4) { i -> PointF(pts[i * 2], pts[i * 2 + 1]) }, mode = TransformMode.DISTORT) }
    applyTransform()
}

/** Remembers [t] (just applied) for Transform again: from where it started to where it ended up. */
internal fun EditorViewModel.rememberTransform(t: TransformSession) {
    if (t.state == t.start) return
    val inv = Matrix()
    if (!t.start.matrix().invert(inv)) return
    lastTransform = Matrix(inv).apply { postConcat(t.state.matrix()) }
}

// ===================================================================== Quick Mask

/**
 * Q: Quick Mask on, the selection shown as a red layer whose mask you paint (white selects, black
 * takes away, like Photoshop); Q again turns what's painted into the selection.
 */
fun EditorViewModel.toggleQuickMask() {
    val d = doc ?: return
    if (pendingFirst { toggleQuickMask() }) return
    val id = quickMaskId
    if (id != null) {
        leaveQuickMask()
        return
    }
    val sel = d.selection
    val prevActive = d.activeId
    runBusy("Quick Mask…") {
        val mask = withContext(Dispatchers.Default) {
            // Hidden where selected (no selection: everything, so nothing is red)
            val m = (sel ?: Selection.all(d.width, d.height)).mask()
            TileGrid(alphaOnly = true).also { g -> m.tiles.forEach { (k, b) -> g.tiles[k] = b.copy(android.graphics.Bitmap.Config.ALPHA_8, true) } }
        }
        val red = withContext(Dispatchers.Default) { solidGrid(d.width, d.height, 0xFFFF1E1E.toInt()) }
        stackEdit("Quick Mask") {
            val l = Layer(d.newId(), LayerProps("Quick Mask", opacity = 0.5f, quickMask = true), red, 0, 0, mask)
            d.layers.add(l)
            d.activeId = l.id
            d.editMask = true
            d.selection = null
            quickMaskPrev = prevActive
        }
        if (tool != Tool.BRUSH) onToolButton(Tool.BRUSH)
        toast("Quick Mask: paint white to select, black to take away (red isn't selected). Q again to finish")
    }
}

/** Quick Mask off: what was painted becomes the selection, and the red layer goes. */
internal fun EditorViewModel.leaveQuickMask() {
    val d = doc ?: return
    val id = quickMaskId ?: return
    if (busy != null) return
    val l = d.layer(id) ?: return
    val m = l.mask ?: TileGrid(alphaOnly = true)
    val prev = quickMaskPrev
    runBusy("Selecting…") {
        val sel = withContext(Dispatchers.Default) {
            if (m.isEmpty) null else Selection.combineMask(null, TileGrid(alphaOnly = true).also { it.tiles.putAll(m.tiles) }, SelectMode.NEW)
        }
        stackEdit("Selection from Quick Mask") {
            d.layers.remove(l)
            d.selection = sel
            d.editMask = false
            d.activeId = prev?.takeIf { p -> d.layer(p) != null } ?: d.layers.lastOrNull()?.id ?: -1L
        }
    }
}

// ===================================================================== files

/** Ctrl+Tab / Ctrl+Shift+Tab: the next or previous open file. */
fun EditorViewModel.nextFile(forward: Boolean) {
    val n = files.size
    if (n < 2) return
    switchFile(((fileIndex + if (forward) 1 else -1) % n + n) % n)
}
