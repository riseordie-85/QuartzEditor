package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.FloodFill
import com.tim.quartzeditor.engine.GradientDrag
import com.tim.quartzeditor.engine.GradientPainter
import com.tim.quartzeditor.engine.GradientPreset
import com.tim.quartzeditor.engine.GradientSpec
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.PatternPainter
import com.tim.quartzeditor.engine.SelectMode
import com.tim.quartzeditor.engine.Selection
import com.tim.quartzeditor.engine.StrokeSession
import com.tim.quartzeditor.engine.StrokeTarget
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.TilePatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import kotlin.math.roundToInt

// Phase 2 tools: selections, crop, paint bucket, gradients. They share the view model's undo,
// busy overlay and "apply what's pending first" rules.

/** Where paint goes on [l]: its mask (folders, adjustment layers, or when the mask is picked) or its pixels. */
internal fun Doc.paintsMask(l: Layer): Boolean = l.mask != null && (editMask || l.isGroup || l.isAdjustment)

/**
 * Changes tiles of [l] (pixels or mask) covering canvas area [area] in the background, copy-on-write,
 * as one undo step. [draw] gets a canvas in the layer's own coordinates and the area in those coordinates.
 */
internal fun EditorViewModel.paintTiles(
    l: Layer,
    toMask: Boolean,
    area: RectF,
    label: String,
    skipMissing: Boolean,
    draw: (Canvas, RectF) -> Unit,
) {
    val d = doc ?: return
    runBusy("$label…") { paintTilesNow(d, l, toMask, area, label, skipMissing, null, draw) }
}

/**
 * The work of [paintTiles], for code already running with the busy overlay up. [onlyKeys] limits
 * it to those tiles. Returns false when nothing changed (or the picture moved on meanwhile).
 */
internal suspend fun EditorViewModel.paintTilesNow(
    d: Doc,
    l: Layer,
    toMask: Boolean,
    area: RectF,
    label: String,
    skipMissing: Boolean,
    onlyKeys: Set<Long>?,
    draw: (Canvas, RectF) -> Unit,
): Boolean {
    val grid = (if (toMask) l.mask else l.tiles) ?: return false
    val local = RectF(area).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
    val keys = grid.keysIn(local).let { k -> if (onlyKeys == null) k else k.filter { it in onlyKeys } }
    val existing = keys.associateWith { grid[it] }
    val made = withContext(Dispatchers.Default) {
        keys.mapNotNull { k ->
            val e = existing[k]
            if (e == null && skipMissing) return@mapNotNull null
            val tile = grid.editableCopy(e)
            val c = Canvas(tile)
            c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
            draw(c, local)
            k to tile
        }
    }
    val now = if (toMask) l.mask else l.tiles
    if (doc !== d || now !== grid || made.isEmpty()) return false
    val saved = HashMap<Long, Bitmap?>()
    for ((k, t) in made) saved[k] = grid.swap(k, t)
    history.record(TilePatch(l, grid, saved, label))
    d.touched(l)
    return true
}

// ===================================================================== fills and gradients

fun EditorViewModel.gradientSpec(): GradientSpec {
    val preset = allGradients.getOrElse(gradientPreset) { GradientPreset.ALL[0] }
    return GradientSpec(preset.resolve(foreground, background), gradientType, gradientOpacity, gradientReverse, preset.positions)
}

fun EditorViewModel.saveGradientPrefs() {
    prefs.edit()
        .putInt("gradPreset", gradientPreset)
        .putInt("gradType", gradientType.ordinal)
        .putBoolean("gradReverse", gradientReverse)
        .putFloat("gradOpacity", gradientOpacity)
        .apply()
}

/** The canvas area a fill or gradient may change: the canvas, or the selected part of it. */
private fun Doc.fillArea(): RectF {
    val a = RectF(0f, 0f, width.toFloat(), height.toFloat())
    selection?.let { if (!a.intersect(it.reach)) a.setEmpty() }
    return a
}

/** Fills the selection (or the whole canvas) of the active layer or its mask with [color]. */
fun EditorViewModel.fillWithColor(color: Int) {
    val d = doc ?: return
    if (pendingFirst { fillWithColor(color) }) return
    val l = d.active ?: return
    if (blockPaint(l) { fillWithColor(color) }) return
    val toMask = d.paintsMask(l)
    val area = d.fillArea()
    if (area.isEmpty) return
    val spec = GradientSpec(listOf(color or 0xFF000000.toInt(), color or 0xFF000000.toInt()))
    val sel = d.selection
    val lock = !toMask && l.props.lockAlpha
    paintTiles(l, toMask, area, if (toMask) "Fill mask" else "Fill", skipMissing = lock) { c, local ->
        val p0 = PointF(local.left, local.top)
        val p1 = PointF(local.right, local.bottom)
        if (toMask) GradientPainter.paintMask(c, local, spec, p0, p1, sel, l.x.toFloat(), l.y.toFloat())
        else GradientPainter.paintPixels(c, local, spec, p0, p1, sel, l.x.toFloat(), l.y.toFloat(), lock)
    }
}

fun EditorViewModel.beginGradient(x: Float, y: Float): Boolean {
    val d = doc ?: return false
    val l = d.active ?: return false
    if (pendingFirst { }) return false
    if (blockPaint(l)) return false
    previewGradient(l, PointF(x, y), PointF(x, y))
    return true
}

internal fun EditorViewModel.setPreview(p: LayerPreview?) {
    preview = p
    doc?.invalidate()
}

private fun EditorViewModel.previewGradient(l: Layer, start: PointF, end: PointF) {
    val d = doc ?: return
    setPreview(LayerPreview(l.id, gradient = GradientDrag(start, end, gradientSpec(), d.paintsMask(l), d.selection)))
}

fun EditorViewModel.gradientTo(x: Float, y: Float) {
    val g = preview?.gradient ?: return
    val l = doc?.layer(preview?.layerId ?: return) ?: return
    previewGradient(l, g.start, PointF(x, y))
}

fun EditorViewModel.endGradient() {
    val d = doc ?: return
    val p = preview ?: return
    val g = p.gradient ?: return
    val l = d.layer(p.layerId) ?: return
    setPreview(null)
    if (abs(g.end.x - g.start.x) + abs(g.end.y - g.start.y) < 2f) return
    val area = d.fillArea()
    if (area.isEmpty) return
    val lock = !g.toMask && l.props.lockAlpha
    paintTiles(l, g.toMask, area, "Gradient", skipMissing = lock) { c, local ->
        val s = PointF(g.start.x - l.x, g.start.y - l.y)
        val e = PointF(g.end.x - l.x, g.end.y - l.y)
        if (g.toMask) GradientPainter.paintMask(c, local, g.spec, s, e, g.selection, l.x.toFloat(), l.y.toFloat())
        else GradientPainter.paintPixels(c, local, g.spec, s, e, g.selection, l.x.toFloat(), l.y.toFloat(), lock)
    }
}

fun EditorViewModel.cancelGradient() {
    if (preview?.gradient != null) setPreview(null)
}

// ===================================================================== paint bucket

/** Fills the area around canvas point ([x], [y]) that matches its color (within the tolerance), with the foreground color or the pattern. */
fun EditorViewModel.bucketFill(x: Float, y: Float) {
    val d = doc ?: return
    if (pendingFirst { bucketFill(x, y) }) return
    val l = d.active ?: return
    if (blockPaint(l) { bucketFill(x, y) }) return
    val px = x.toInt()
    val py = y.toInt()
    if (px !in 0 until d.width || py !in 0 until d.height) return
    val toMask = d.paintsMask(l)
    val allLayers = bucketAllLayers || !l.hasContent
    val tol = (bucketTolerance * 255).roundToInt()
    val contiguous = bucketContiguous
    val sel = d.selection
    val copy = if (allLayers) d.shallowCopy() else null
    val tiles = HashMap(l.tiles.tiles)
    val lx = l.x
    val ly = l.y
    val w = d.width
    val h = d.height
    val loadPattern = if (bucketPattern) patternLoader() else null
    val scale = patternScale
    runBusy("Filling…") {
        val coverage = withContext(Dispatchers.Default) {
            // What the bucket looks at: this layer, or everything you see
            val src = if (copy != null) {
                com.tim.quartzeditor.engine.DocRenderer.flatten(copy)
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }.drawTo(Canvas(b), lx.toFloat(), ly.toFloat(), null)
                }
            }
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            src.recycle()
            val cov = FloodFill.coverage(pixels, w, h, px, py, tol, contiguous)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(cov))
            sel?.keepInside(Canvas(bmp), 0f, 0f)
            TileGrid.fromBitmap(bmp, -lx, -ly, alphaOnly = true).also { bmp.recycle() }
        }
        if (doc !== d || coverage.isEmpty) return@runBusy
        if (loadPattern != null) {
            val pattern = withContext(Dispatchers.IO) { loadPattern() } ?: run {
                toast("That pattern is gone. Pick another")
                return@runBusy
            }
            // Pattern fill: the pattern, kept to the bucket's area (coverage is in the layer's coordinates)
            val keepPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            val keep: (Canvas) -> Unit = { c ->
                c.saveLayer(null, keepPaint)
                coverage.drawTo(c, 0f, 0f, null)
                c.restore()
            }
            val lock = !toMask && l.props.lockAlpha
            val area = RectF(0f, 0f, w.toFloat(), h.toFloat())
            paintTilesNow(d, l, toMask, area, "Pattern fill", skipMissing = lock, onlyKeys = coverage.tiles.keys.toSet()) { c, local ->
                if (toMask) PatternPainter.paintMask(c, local, pattern, scale, lx.toFloat(), ly.toFloat(), keep)
                else PatternPainter.paintPixels(c, local, pattern, scale, lx.toFloat(), ly.toFloat(), lock, keep)
            }
            return@runBusy
        }
        // Applied like a brush stroke at full strength, so locks and masks behave the same
        val s = StrokeSession(
            l, if (toMask) StrokeTarget.MASK else StrokeTarget.PIXELS, false, foreground, background, 1f,
            BrushTip.ROUND, 1f, 0f, null,
        )
        s.buffer.tiles.putAll(coverage.tiles)
        s.commit()?.let {
            history.record(it)
            d.touched(l)
        }
    }
}

// ===================================================================== selections

internal fun EditorViewModel.setSelection(label: String, sel: Selection?) {
    val d = doc ?: return
    if (sel === d.selection) return
    // Adjustments being tried are applied to the old selection first
    if (pendingFirst { setSelection(label, sel) }) return
    stackEdit(label) { d.selection = sel }
    // Prepare its pixel mask in the background, so the first brush stroke doesn't wait for it
    if (sel != null) viewModelScope.launch(Dispatchers.Default) { runCatching { sel.mask() } }
}

/** A finished rectangle, ellipse or lasso shape (canvas pixels). */
fun EditorViewModel.commitSelectionShape(shape: Path) {
    val d = doc ?: return
    selectDraft = null
    val old = d.selection
    val mode = selectMode
    val label = if (mode == SelectMode.NEW) "Select" else "Change selection"
    // Combining with a pixel selection works on pixels: done in the background
    if (old != null && old.isPixelBased && mode != SelectMode.NEW) setSelectionComputed(label) { Selection.combine(old, shape, mode) }
    else setSelection(label, Selection.combine(old, shape, mode))
}

/** Works out a selection in the background (with the busy spinner), then makes it the selection. */
internal fun EditorViewModel.setSelectionComputed(label: String, busyLabel: String = "Selecting…", compute: suspend () -> Selection?) {
    val d = doc ?: return
    if (pendingFirst { setSelectionComputed(label, busyLabel, compute) }) return
    runBusy(busyLabel) {
        val sel = withContext(Dispatchers.Default) { compute() }
        if (doc !== d) return@runBusy
        if (sel == null && d.selection == null) {
            toast("Nothing was selected")
            return@runBusy
        }
        setSelection(label, sel)
    }
}

/** Tapped without dragging: a new selection mode deselects (like Photoshop). */
fun EditorViewModel.selectTap() {
    if (selectMode == SelectMode.NEW) deselect()
}

fun EditorViewModel.deselect() {
    val d = doc ?: return
    if (d.selection == null) return
    setSelection("Deselect", null)
}

fun EditorViewModel.selectAll() {
    val d = doc ?: return
    setSelection("Select all", Selection.all(d.width, d.height))
}

fun EditorViewModel.invertSelection() {
    val d = doc ?: return
    val s = d.selection ?: run {
        toast("Nothing is selected")
        return
    }
    if (s.isPixelBased) {
        val w = d.width
        val h = d.height
        setSelectionComputed("Invert selection") { Selection.inverse(s, w, h) }
    } else {
        setSelection("Invert selection", Selection.inverse(s, d.width, d.height))
    }
}

fun EditorViewModel.featherSelection(px: Float) {
    val d = doc ?: return
    val s = d.selection ?: run {
        toast("Select something first")
        return
    }
    setSelection("Feather", s.withFeather(px))
}

/** Makes the selection [px] pixels bigger (or smaller, when negative). */
fun EditorViewModel.growSelection(px: Float) {
    val d = doc ?: return
    val s = d.selection ?: run {
        toast("Select something first")
        return
    }
    val w = d.width
    val h = d.height
    setSelectionComputed(if (px > 0f) "Expand selection" else "Shrink selection") {
        com.tim.quartzeditor.engine.SelectionOps.grown(s, px, w, h)
    }
}

/** Polygonal lasso: adds a corner; tapping near the first one closes the shape. */
fun EditorViewModel.polygonTap(x: Float, y: Float, closeRadius: Float) {
    val first = polygonPoints.firstOrNull()
    if (first != null && polygonPoints.size >= 3 && abs(first.x - x) + abs(first.y - y) < closeRadius) {
        finishPolygon()
        return
    }
    polygonPoints.add(PointF(x, y))
    updatePolygonDraft()
}

fun EditorViewModel.updatePolygonDraft() {
    selectDraft = if (polygonPoints.isEmpty()) null else Path().apply {
        polygonPoints.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    }
}

fun EditorViewModel.finishPolygon() {
    if (polygonPoints.size < 3) {
        polygonPoints.clear()
        updatePolygonDraft()
        return
    }
    val path = Selection.polygon(polygonPoints.toList())
    polygonPoints.clear()
    commitSelectionShape(path)
}

fun EditorViewModel.cancelPolygon() {
    polygonPoints.clear()
    updatePolygonDraft()
}

/** The selected pixels of the active layer as a new layer; [cut] also removes them from the original. */
fun EditorViewModel.selectionToLayer(cut: Boolean) {
    val d = doc ?: return
    if (pendingFirst { selectionToLayer(cut) }) return
    val l = d.active ?: return
    val sel = d.selection ?: run {
        toast("Select something first")
        return
    }
    if (!l.hasContent) {
        toast("Pick a pixel layer")
        return
    }
    if (cut && l.needsRaster) {
        askRasterize(l) { selectionToLayer(cut) }
        return
    }
    if (cut && l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    val tiles = HashMap(l.tiles.tiles)
    val source = l.tiles
    runBusy(if (cut) "Cutting…" else "Copying…") {
        val (copyGrid, rest) = withContext(Dispatchers.Default) {
            val copy = TileGrid(alphaOnly = false)
            val remain = TileGrid(alphaOnly = false)
            val local = RectF(sel.reach).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
            val touched = TileGrid(alphaOnly = false).keysIn(local).toSet()
            for ((k, t) in tiles) {
                if (k !in touched) {
                    remain.tiles[k] = t
                    continue
                }
                val ox = (TileGrid.tx(k) * TileGrid.SIZE).toFloat()
                val oy = (TileGrid.ty(k) * TileGrid.SIZE).toFloat()
                val inside = t.copy(Bitmap.Config.ARGB_8888, true).also { it.setHasMipMap(true) }
                sel.keepInside(Canvas(inside), l.x + ox, l.y + oy)
                copy.tiles[k] = inside
                if (cut) {
                    val outside = t.copy(Bitmap.Config.ARGB_8888, true).also { it.setHasMipMap(true) }
                    val c = Canvas(outside)
                    c.translate(-ox, -oy)
                    sel.mask().drawTo(c, -l.x.toFloat(), -l.y.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                    remain.tiles[k] = outside
                } else {
                    remain.tiles[k] = t
                }
            }
            copy.dropEmptyTiles()
            copy to remain
        }
        if (doc !== d || l.tiles !== source) return@runBusy
        if (copyGrid.isEmpty) {
            toast("Nothing there to ${if (cut) "cut" else "copy"}")
            return@runBusy
        }
        stackEdit(if (cut) "Layer via cut" else "Layer via copy") {
            if (cut) l.tiles = rest
            val n = Layer(d.newId(), LayerProps(d.uniqueName("Layer"), parent = l.props.parent), copyGrid, l.x, l.y)
            d.layers.add(d.indexOf(l.id) + 1, n)
            d.joinClipGroup(n)
            d.activeId = n.id
            d.editMask = false
            d.selection = null
        }
    }
}

/** Erases the selected pixels of the active layer. */
fun EditorViewModel.clearSelected() {
    val d = doc ?: return
    if (pendingFirst { clearSelected() }) return
    val l = d.active ?: return
    val sel = d.selection ?: run {
        toast("Select something first")
        return
    }
    if (blockPaint(l) { clearSelected() }) return
    if (d.paintsMask(l)) {
        fillWithColor(Color.WHITE)
        return
    }
    val area = RectF(sel.reach)
    paintTiles(l, false, area, "Delete", skipMissing = true) { c, _ ->
        sel.mask().drawTo(c, -l.x.toFloat(), -l.y.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    }
}

/** A mask on the active layer that shows only the selected part. */
fun EditorViewModel.maskFromSelection() {
    val d = doc ?: return
    if (pendingFirst { maskFromSelection() }) return
    val l = editableActive() ?: return
    val sel = d.selection ?: run {
        toast("Select something first")
        return
    }
    runBusy("Making mask…") {
        val mask = withContext(Dispatchers.Default) { selectionMask(sel, l, d) }
        stackEdit("Mask from selection") {
            l.mask = mask
            l.props = l.props.copy(maskDisabled = false)
            d.editMask = true
            d.selection = null
        }
    }
}

// ===================================================================== crop

fun EditorViewModel.beginCrop() {
    val d = doc ?: return
    cropBox = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat())
    if (cropPerspective) {
        // Start a little inside the edges, so the corners are easy to grab
        val ix = d.width * 0.1f
        val iy = d.height * 0.1f
        setCropQuad(listOf(
            android.graphics.PointF(ix, iy), android.graphics.PointF(d.width - ix, iy),
            android.graphics.PointF(d.width - ix, d.height - iy), android.graphics.PointF(ix, d.height - iy),
        ))
        return
    }
    cropQuad = null
    if (cropRatio != CropRatio.FREE) applyCropRatio(cropRatio)
}

/** Crop or Perspective crop, from the toolbar. */
fun EditorViewModel.chooseCrop(perspective: Boolean) {
    if (cropPerspective != perspective) {
        cropPerspective = perspective
        prefs.edit().putBoolean("cropPersp", perspective).apply()
        // Already cropping: start again the other way
        if (tool == Tool.CROP && cropBox != null) {
            beginCrop()
            return
        }
    }
    onToolButton(Tool.CROP)
}

/** Sets the perspective crop's corners and keeps [cropBox] at their bounds. */
private fun EditorViewModel.setCropQuad(q: List<android.graphics.PointF>) {
    cropQuad = q
    cropBox = RectF(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
}

/**
 * Drags the perspective crop. [handle]: 0..3 a corner (TL, TR, BR, BL), 4..7 an edge (top, right,
 * bottom, left: both its corners move), -1 all of it. [start] is the corners when the drag began.
 */
fun EditorViewModel.dragCropQuad(start: List<android.graphics.PointF>, handle: Int, dx: Float, dy: Float) {
    val moved = start.mapIndexed { i, p ->
        val moves = when (handle) {
            -1 -> true
            in 0..3 -> i == handle
            else -> i == handle - 4 || i == (handle - 4 + 1) % 4
        }
        if (moves) android.graphics.PointF(p.x + dx, p.y + dy) else android.graphics.PointF(p.x, p.y)
    }
    setCropQuad(moved)
}

/** True when the four corners make a shape that can be straightened (no crossing, no dents). */
internal fun quadIsConvex(q: List<android.graphics.PointF>): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val a = q[i]
        val b = q[(i + 1) % 4]
        val c = q[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (kotlin.math.abs(cross) < 1e-3f) return false
        val sg = if (cross > 0) 1 else -1
        if (sign == 0) sign = sg else if (sg != sign) return false
    }
    // Clockwise on screen (TL, TR, BR, BL); the other way round would come out mirrored
    return sign > 0
}

/** Straightens what's inside the four corners into a new, rectangular canvas. */
private fun EditorViewModel.applyPerspectiveCrop(q: List<android.graphics.PointF>, onDone: () -> Unit) {
    if (!quadIsConvex(q)) {
        toast("The corners cross over. Drag them back into a four-sided shape (top left, top right, bottom right, bottom left)")
        return
    }
    // The new size: the average length of opposite sides
    val (w, h) = quadOutputSize(q)
    val poly = android.graphics.Matrix()
    val ok = poly.setPolyToPoly(
        floatArrayOf(q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y), 0,
        floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4,
    )
    if (!ok) {
        toast("Couldn't straighten that shape")
        return
    }
    cropBox = null
    cropQuad = null
    tool = Tool.MOVE
    canvasTransform("Perspective crop", w, h, perspective = true, onDone = onDone) { m -> m.postConcat(poly) }
}

/** The canvas's width ÷ height for "Original". */
private fun EditorViewModel.ratioValue(r: CropRatio): Float? {
    val d = doc ?: return null
    return when (r.ratio) {
        null -> null
        -1f -> d.width.toFloat() / d.height
        else -> r.ratio
    }
}

/** Reshapes the crop box to [r], keeping its center and fitting inside it. */
fun EditorViewModel.applyCropRatio(r: CropRatio) {
    cropRatio = r
    val b = cropBox ?: return
    val ratio = ratioValue(r) ?: return
    val cx = b.centerX()
    val cy = b.centerY()
    var w = b.width()
    var h = w / ratio
    if (h > b.height()) {
        h = b.height()
        w = h * ratio
    }
    cropBox = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/**
 * Drags a crop handle. [handle]: 0..3 corners (TL, TR, BR, BL), 4..7 edges (top, right, bottom, left),
 * -1 = the whole box. [start] is the box when the drag began; ([dx], [dy]) how far the finger moved.
 */
fun EditorViewModel.dragCrop(start: RectF, handle: Int, dx: Float, dy: Float, keepShape: Boolean = false, fromCenter: Boolean = false) {
    var l = start.left
    var t = start.top
    var r = start.right
    var b = start.bottom
    when (handle) {
        -1 -> { l += dx; r += dx; t += dy; b += dy }
        0 -> { l += dx; t += dy }
        1 -> { r += dx; t += dy }
        2 -> { r += dx; b += dy }
        3 -> { l += dx; b += dy }
        4 -> t += dy
        5 -> r += dx
        6 -> b += dy
        7 -> l += dx
    }
    // From the center (Alt): the opposite side moves the other way
    if (fromCenter && handle >= 0) {
        when (handle) {
            0 -> { r -= dx; b -= dy }
            1 -> { l -= dx; b -= dy }
            2 -> { l -= dx; t -= dy }
            3 -> { r -= dx; t -= dy }
            4 -> b -= dy
            5 -> l -= dx
            6 -> t -= dy
            7 -> r -= dx
        }
    }
    val min = 8f
    if (r - l < min) { if (handle == 0 || handle == 3 || handle == 7) l = r - min else r = l + min }
    if (b - t < min) { if (handle == 0 || handle == 1 || handle == 4) t = b - min else b = t + min }
    // Shift keeps the box's shape as it was
    val ratio = ratioValue(cropRatio) ?: if (keepShape && start.height() > 0f) start.width() / start.height() else null
    if (ratio != null && handle >= 0) {
        // Keep the shape: the dragged side decides, the other follows
        val w = r - l
        val h = b - t
        if (handle == 4 || handle == 6) {
            val nw = h * ratio
            val cx = (l + r) / 2f
            l = cx - nw / 2f
            r = cx + nw / 2f
        } else if (handle == 5 || handle == 7) {
            val nh = w / ratio
            val cy = (t + b) / 2f
            t = cy - nh / 2f
            b = cy + nh / 2f
        } else {
            val nh = w / ratio
            if (handle == 0 || handle == 1) t = b - nh else b = t + nh
        }
    }
    if (fromCenter && handle >= 0) {
        // Keep it centered where it was
        val ox = start.centerX() - (l + r) / 2f
        val oy = start.centerY() - (t + b) / 2f
        l += ox; r += ox; t += oy; b += oy
    }
    val maxSide = Doc.MAX_SIDE.toFloat()
    if (r - l > maxSide) r = l + maxSide
    if (b - t > maxSide) b = t + maxSide
    cropBox = RectF(l, t, r, b)
}

fun EditorViewModel.cancelCrop() {
    if (cropBox == null) return
    cropBox = null
    cropQuad = null
    tool = Tool.MOVE
}

/** Crops the canvas to the box. Pixels outside are kept (move a layer to bring them back). */
fun EditorViewModel.applyCrop(onDone: () -> Unit = {}) {
    val d = doc ?: return
    val box = cropBox ?: return
    // (It finishes in the background, then runs onDone; a shape it can't straighten stays to fix)
    cropQuad?.let {
        applyPerspectiveCrop(it, onDone)
        return
    }
    val r = Rect(box.left.roundToInt(), box.top.roundToInt(), box.right.roundToInt(), box.bottom.roundToInt())
    cropBox = null
    cropQuad = null
    tool = Tool.MOVE
    if (r.left == 0 && r.top == 0 && r.width() == d.width && r.height() == d.height) {
        onDone()
        return
    }
    cropTo(r)
    onDone()
}

/** Makes the canvas cover [r] (canvas pixels) by moving every layer. */
internal fun EditorViewModel.cropTo(r: Rect) {
    val d = doc ?: return
    val w = r.width().coerceIn(1, Doc.MAX_SIDE)
    val h = r.height().coerceIn(1, Doc.MAX_SIDE)
    stackEdit("Crop") {
        val oldW = d.width
        val oldH = d.height
        d.width = w
        d.height = h
        for (l in d.layers) {
            val clipped = vectorClipped(l, l.x, l.y, oldW, oldH)
            l.x -= r.left
            l.y -= r.top
            // Shapes reaching far past the old canvas: draw the rest
            if (clipped) redrawVectorNow(l)
        }
        // Guides stay where they were on the picture (those cut off go)
        val gx = d.guidesX.map { it - r.left }.filter { it in 0f..w.toFloat() }
        val gy = d.guidesY.map { it - r.top }.filter { it in 0f..h.toFloat() }
        d.guidesX.clear()
        d.guidesX.addAll(gx)
        d.guidesY.clear()
        d.guidesY.addAll(gy)
        d.selection = null
    }
    fit()
}

fun EditorViewModel.cropToSelection() {
    val d = doc ?: return
    if (pendingFirst { cropToSelection() }) return
    val s = d.selection ?: run {
        toast("Select something first")
        return
    }
    val b = s.pixelBounds()
    if (!b.intersect(0, 0, d.width, d.height)) return
    cropTo(b)
}

/**
 * A mask for [l] that shows what [sel] selects and hides the rest. Covers the canvas and wherever
 * the layer has pixels, so moving it later doesn't uncover unmasked parts. (Runs in the background.)
 */
internal fun selectionMask(sel: com.tim.quartzeditor.engine.Selection, l: com.tim.quartzeditor.engine.Layer, d: Doc): TileGrid {
    val g = TileGrid(alphaOnly = true)
    val local = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat()).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
    l.tiles.tileBounds()?.let { local.union(RectF(it)) }
    for (k in g.keysIn(local)) {
        val t = TileGrid.newTile(alphaOnly = true)
        val c = Canvas(t)
        c.drawColor(Color.BLACK)
        c.translate(-(TileGrid.tx(k) * TileGrid.SIZE).toFloat(), -(TileGrid.ty(k) * TileGrid.SIZE).toFloat())
        sel.mask().drawTo(c, -l.x.toFloat(), -l.y.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        g.tiles[k] = t
    }
    return g
}

/**
 * Layer [id]'s pixels as a selection (Photoshop's Ctrl-click on a layer's thumbnail): selected as
 * much as the layer shows there, mask included. With Shift held it adds to the selection, with Alt
 * it takes away.
 */
fun EditorViewModel.selectLayerPixels(id: Long) {
    val mode = when {
        shiftOn -> com.tim.quartzeditor.engine.SelectMode.ADD
        altOn -> com.tim.quartzeditor.engine.SelectMode.SUBTRACT
        else -> com.tim.quartzeditor.engine.SelectMode.NEW
    }
    selectLayerPixels(id, mode)
}

internal fun EditorViewModel.selectLayerPixels(id: Long, mode: com.tim.quartzeditor.engine.SelectMode) {
    // What's pending (a transform, say) goes in first, so the layer is selected as it ends up
    if (pendingFirst { selectLayerPixels(id, mode) }) return
    val d = doc ?: return
    val l = d.layer(id) ?: return
    if (l.isAdjustment) {
        toast("Adjustment layers have no pixels to select")
        return
    }
    val region = (if (l.isGroup) Rect(0, 0, d.width, d.height) else l.bounds())?.takeIf { it.intersect(com.tim.quartzeditor.engine.Transformer.limitFor(d.width, d.height)) }
    if (region == null || region.isEmpty) {
        toast("\"${l.props.name}\" is empty")
        return
    }
    val alone = com.tim.quartzeditor.engine.Layer(
        l.id, l.props.copy(opacity = 1f, fill = 1f, blend = com.tim.quartzeditor.engine.Blend.NORMAL, clip = false, visible = true, style = com.tim.quartzeditor.engine.LayerStyle(), parent = 0L, blendIf = com.tim.quartzeditor.engine.BlendIf()),
        l.tiles, l.x, l.y, l.mask, if (l.isGroup) com.tim.quartzeditor.engine.LayerKind.GROUP else com.tim.quartzeditor.engine.LayerKind.PIXELS,
    )
    val old = d.selection
    setSelectionComputed("Select layer pixels") {
        val bmp = com.tim.quartzeditor.engine.DocRenderer.renderLayers(listOf(alone), region, d.width, d.height, doc = d)
        val grid = TileGrid.fromBitmap(bmp, region.left, region.top, alphaOnly = true)
        bmp.recycle()
        com.tim.quartzeditor.engine.Selection.combineMask(old, grid, mode)
    }
}
