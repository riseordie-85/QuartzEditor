package com.tim.quartzeditor.ui

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.RectF
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocSnap
import com.tim.quartzeditor.engine.Fonts
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.PenPoint
import com.tim.quartzeditor.engine.ShapeKind
import com.tim.quartzeditor.engine.ShapeSpec
import com.tim.quartzeditor.engine.StackChange
import com.tim.quartzeditor.engine.TextSpec
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.VectorArt
import com.tim.quartzeditor.engine.translation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Phase 3: shape layers (Shape tool and shape pen) and text layers. They keep what they are, and
// their pixels are redrawn from that whenever it changes, so everything else (blend modes, masks,
// layer styles, merging, export) treats them like pixel layers.

/** [EditorViewModel.paintBlocker]'s answer for a shape or text layer: ask to rasterize it. */
internal const val NEEDS_RASTER = "\u0000rasterize"

/** "Rasterize this layer?" Answering yes rasterizes it and runs [then]. */
class RasterAsk(val layerId: Long, val name: String, val isText: Boolean, val then: (() -> Unit)?)

/** Text being typed: the layer, and the document as it was before (for Cancel and undo). */
class TextEditSession(val layerId: Long, val before: DocSnap, val isNew: Boolean)

// ===================================================================== settings for new shapes and text

internal fun loadShapeStyle(prefs: SharedPreferences): ShapeSpec =
    (prefs.getString("shapeStyle", null)?.let { runCatching { ShapeSpec.fromJson(JSONObject(it)) }.getOrNull() }
        ?: ShapeSpec(ShapeKind.RECT, fill = Color.BLACK, stroke = null, strokeWidth = 6f))
        // New shapes always start filled, without an outline
        .let { it.copy(fill = it.fill ?: Color.BLACK, stroke = null) }

/**
 * New shapes start filled with no outline. Colors, outline width, corners and star settings
 * carry over from the last shape; fill and outline switches don't.
 */
internal fun EditorViewModel.resetShapeSwitches() {
    val s = shapeStyle
    if (s.stroke != null) lastShapeStroke = s.stroke
    shapeStyle = s.copy(fill = s.fill ?: lastShapeFill ?: Color.BLACK, stroke = null)
    saveVectorPrefs()
}

internal fun loadTextStyle(prefs: SharedPreferences): TextSpec =
    prefs.getString("textStyle", null)?.let { runCatching { TextSpec.fromJson(JSONObject(it)) }.getOrNull() }
        ?: TextSpec(text = "", size = 0f)

fun EditorViewModel.saveVectorPrefs() {
    prefs.edit()
        .putInt("shapeKind", shapeKind.ordinal)
        .putBoolean("shapeSquare", shapeSquare)
        .putString("shapeStyle", shapeStyle.copy(path = emptyList(), w = 0f, h = 0f).toJson().toString())
        .putString("textStyle", textStyle.copy(text = "").toJson().toString())
        .apply()
}

// ===================================================================== rasterizing

/** Stops if [l] can't be painted, saying why (or asking to rasterize a shape or text layer, then [retry]). */
internal fun EditorViewModel.blockPaint(l: Layer?, retry: (() -> Unit)? = null): Boolean {
    val reason = paintBlocker(l) ?: return false
    if (reason == NEEDS_RASTER && l != null) askRasterize(l, retry) else toast(reason)
    return true
}

internal fun EditorViewModel.askRasterize(l: Layer, then: (() -> Unit)?) {
    rasterAsk = RasterAsk(l.id, l.props.name, l.isText, then)
}

fun EditorViewModel.answerRasterize(yes: Boolean) {
    val a = rasterAsk ?: return
    rasterAsk = null
    if (yes) rasterizeLayer(a.layerId, a.then)
}

/** Turns a shape or text layer into an ordinary pixel layer (same name, look and place). */
fun EditorViewModel.rasterizeLayer(id: Long, then: (() -> Unit)? = null) {
    val d = doc ?: return
    if (pendingFirst { rasterizeLayer(id, then) }) return
    val l = d.layer(id)?.takeIf { it.isVector } ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    stackEdit(if (l.isText) "Rasterize text" else "Rasterize shape") {
        // A new grid (same tile bitmaps; painting copies a tile before changing it)
        val grid = TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }
        val px = Layer(l.id, l.props.copy(shape = null, text = null), grid, l.x, l.y, l.mask, LayerKind.PIXELS)
        px.thumb = l.thumb
        px.maskThumb = l.maskThumb
        d.layers[d.indexOf(l.id)] = px
    }
    then?.invoke()
}

/** Where a shape or text layer is drawn (layer-local): the canvas and a margin around it. */
internal fun Doc.vectorClip(l: Layer): RectF = VectorArt.clipFor(l.x, l.y, width, height)

/**
 * True when part of [l] wasn't drawn because it reached far past a [w] × [h] canvas with the layer
 * at ([x], [y]), so it should be redrawn after moving. (Not while its font is still loading: it
 * keeps its pixels until then.)
 */
internal fun vectorClipped(l: Layer, x: Int, y: Int, w: Int, h: Int): Boolean {
    if (!l.isVector || !fontReady(l.props.text?.fontKey)) return false
    val b = VectorArt.bounds(l.props) ?: return false
    return !VectorArt.clipFor(x, y, w, h).contains(b)
}

/** Redraws a shape or text layer's pixels now. */
internal fun EditorViewModel.redrawVectorNow(l: Layer) {
    val d = doc ?: return
    if (!l.isVector) return
    if (vectorLiveLayer === l) vectorLiveLayer = null
    l.tiles = VectorArt.rasterize(l.props, d.vectorClip(l))
    d.touched(l)
}

/** Redraws a shape or text layer's pixels in the background (while a slider moves or you type). */
internal fun EditorViewModel.redrawVectorSoon(l: Layer) {
    vectorLiveLayer = l
    if (vectorJob?.isActive == true) return
    vectorJob = viewModelScope.launch {
        while (true) {
            val target = vectorLiveLayer ?: break
            vectorLiveLayer = null
            val d = doc ?: break
            val props = target.props
            val clip = d.vectorClip(target)
            val grid = withContext(Dispatchers.Default) { VectorArt.rasterize(props, clip) }
            if (doc === d && target.props === props) {
                target.tiles = grid
                d.touched(target)
            }
        }
    }
}

private fun EditorViewModel.stopLiveRedraw() {
    vectorJob?.cancel()
    vectorJob = null
    vectorLiveLayer = null
}

/** True when text in font [key] can be drawn as it should be (or the font can't be had at all). */
internal fun fontReady(key: String?): Boolean = key == null || Fonts.cached(key) != null || Fonts.isFailed(key)

/**
 * Runs [block] once font [key] is ready, loading it first if needed (with the busy spinner).
 * Text is never drawn with a stand-in while its font is still on its way, so what undo keeps is
 * always drawn in the right font.
 */
internal fun EditorViewModel.withFont(key: String?, block: () -> Unit) {
    if (fontReady(key)) {
        block()
        return
    }
    if (busy != null) {
        toast("One moment…")
        return
    }
    runBusy("Loading font…", onDone = block) {
        if (Fonts.load(ctx, key!!) == null) toast("Couldn't load ${Fonts.label(key)}. Using a stand-in")
    }
}

/** Like [withFont] for several fonts at once. */
internal fun EditorViewModel.withFonts(keys: Collection<String>, block: () -> Unit) {
    val missing = keys.filter { !fontReady(it) }
    if (missing.isEmpty()) {
        block()
        return
    }
    if (busy != null) {
        toast("One moment…")
        return
    }
    runBusy("Loading fonts…", onDone = block) {
        for (k in missing) Fonts.load(ctx, k)
    }
}

/** Starts loading font [key] in the background (for later). */
private fun EditorViewModel.fetchFont(key: String?) {
    if (fontReady(key)) return
    viewModelScope.launch { Fonts.load(ctx, key!!) }
}

/** Loads the fonts a document's text layers use, so editing them later doesn't wait. */
internal fun EditorViewModel.preloadFonts(d: Doc) {
    for (k in d.layers.mapNotNull { it.props.text?.fontKey }.toSet()) fetchFont(k)
}

// ===================================================================== editing shape and text layers

/** One undoable change to a shape or text layer (a button, a font, a color). */
fun EditorViewModel.editVector(id: Long, label: String, change: (LayerProps) -> LayerProps) {
    val d = doc ?: return
    val l = d.layer(id)?.takeIf { it.isVector } ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    val next = change(l.props)
    if (next == l.props) return
    withFont(next.text?.fontKey) {
        val cur = d.layer(id)?.takeIf { it.isVector && doc === d } ?: return@withFont
        // While typing, changes join the text edit (one undo step for all of it)
        if (textEdit?.layerId == id) {
            cur.props = change(cur.props)
            redrawVectorSoon(cur)
            return@withFont
        }
        stackEdit(label) {
            cur.props = change(cur.props)
            redrawVectorNow(cur)
        }
    }
}

/** A change while a slider moves; [commitVector] makes it one undo step when the finger lifts. */
fun EditorViewModel.editVectorLive(id: Long, change: (LayerProps) -> LayerProps) {
    val d = doc ?: return
    val l = d.layer(id)?.takeIf { it.isVector } ?: return
    if (l.props.locked) return
    // A font still on its way: wait for it (the slider stays put for that moment)
    val key = l.props.text?.fontKey
    if (!fontReady(key)) {
        fetchFont(key)
        return
    }
    if (vectorBefore != null && vectorBeforeId != id) commitVector("Change layer")
    if (textEdit?.layerId != id && vectorBefore == null) {
        vectorBefore = d.snapshot()
        vectorBeforeId = id
    }
    l.props = change(l.props)
    redrawVectorSoon(l)
    d.invalidate()
}

fun EditorViewModel.commitVector(label: String) {
    val d = doc ?: return
    val before = vectorBefore ?: return
    val l = d.layer(vectorBeforeId)
    vectorBefore = null
    stopLiveRedraw()
    if (l == null || !l.isVector) return
    redrawVectorNow(l)
    history.record(StackChange(before, d.snapshot(), label))
    d.invalidate()
}

// ===================================================================== Shape tool

/** Shape tool tapped again: the next shape. */
internal fun EditorViewModel.nextShapeKind() {
    shapeKind = shapeKind.next
    saveVectorPrefs()
    toast(shapeKind.label)
}

/** The shape being dragged out, from canvas point ([x0], [y0]) to ([x1], [y1]). */
fun EditorViewModel.dragShape(x0: Float, y0: Float, x1: Float, y1: Float) {
    var w = x1 - x0
    var h = y1 - y0
    if (shapeSquare) {
        val m = max(abs(w), abs(h))
        w = if (w < 0) -m else m
        h = if (h < 0) -m else m
    }
    val left = min(x0, x0 + w)
    val top = min(y0, y0 + h)
    shapeDraft = shapeStyle.copy(kind = shapeKind, w = abs(w), h = abs(h), path = emptyList(), closed = true, matrix = translation(left, top))
}

fun EditorViewModel.finishShape() {
    val s = shapeDraft ?: return
    shapeDraft = null
    // Too small to be on purpose
    if (s.w * scale < 6f && s.h * scale < 6f) return
    addShapeLayer(s, s.kind.label)
}

fun EditorViewModel.cancelShape() {
    shapeDraft = null
}

/** A new shape layer above the active layer. [spec] is in canvas pixels. */
internal fun EditorViewModel.addShapeLayer(spec: ShapeSpec, name: String) {
    val d = doc ?: return
    if (pendingFirst { addShapeLayer(spec, name) }) return
    stackEdit("New shape") {
        val (at, parent) = insertPoint(d)
        val props = LayerProps(d.uniqueName(name), parent = parent, shape = spec)
        val l = Layer(d.newId(), props, VectorArt.rasterize(props, VectorArt.clipFor(0, 0, d.width, d.height)), kind = LayerKind.SHAPE)
        d.layers.add(at, l)
        d.joinClipGroup(l)
        d.activeId = l.id
        d.editMask = false
    }
    resetShapeSwitches()
}

// ===================================================================== shape pen

/** Shows the point being placed (with its curve handle while you drag). */
fun EditorViewModel.penPreview(p: PenPoint?) {
    penDrag = p
}

/**
 * Places a pen point (canvas pixels). Tapping near the first point (within [closeRadius]) closes
 * the shape and makes the layer.
 */
fun EditorViewModel.penAdd(p: PenPoint, closeRadius: Float) {
    penDrag = null
    val first = penPoints.firstOrNull()
    if (first != null && penPoints.size >= 2 && hypot(p.x - first.x, p.y - first.y) <= closeRadius) {
        // Dragging on the first point reshapes its curve as it closes
        if (p.smooth) penPoints[0] = first.copy(hx = p.hx, hy = p.hy, ix = -p.hx, iy = -p.hy)
        finishPen(closed = true)
        return
    }
    penPoints.add(p)
}

fun EditorViewModel.undoPenPoint() {
    if (penPoints.isNotEmpty()) penPoints.removeAt(penPoints.lastIndex)
}

fun EditorViewModel.cancelPen() {
    penPoints.clear()
    penDrag = null
}

/** Makes a shape layer from the pen points: [closed] joins the last point to the first. */
fun EditorViewModel.finishPen(closed: Boolean) {
    val pts = penPoints.toList()
    penPoints.clear()
    penDrag = null
    if (pts.size < 2) {
        if (pts.isNotEmpty()) toast("Add at least two points")
        return
    }
    val left = pts.minOf { it.x }
    val top = pts.minOf { it.y }
    val local = pts.map { it.copy(x = it.x - left, y = it.y - top) }
    var spec = shapeStyle.copy(
        kind = ShapeKind.PATH,
        w = pts.maxOf { it.x } - left,
        h = pts.maxOf { it.y } - top,
        path = local,
        closed = closed && pts.size >= 3,
        matrix = translation(left, top),
    )
    // An open line has no inside to fill: it needs an outline to show
    if (!spec.closed && !spec.hasStroke) {
        spec = spec.copy(stroke = shapeStyle.fill ?: Color.BLACK, strokeWidth = max(spec.strokeWidth, 6f))
    }
    addShapeLayer(spec, "Shape")
    // Straight on to adjusting its points
    penEditing = true
    penSelected = -1
}

// ===================================================================== Text tool

/** Text tool tap at canvas point ([x], [y]): edits the text there, or starts new text. */
fun EditorViewModel.textTap(x: Float, y: Float) {
    val d = doc ?: return
    if (textEdit != null) {
        finishText()
        return
    }
    if (pendingFirst { textTap(x, y) }) return
    val hit = d.layers.asReversed().firstOrNull { l ->
        l.isText && d.effectivelyVisible(l) && VectorArt.bounds(l.props)?.apply { offset(l.x.toFloat(), l.y.toFloat()) }?.contains(x, y) == true
    }
    if (hit != null) {
        beginTextEdit(hit)
        return
    }
    withFont(textStyle.fontKey) { newText(x, y) }
}

/** New text: its first line starts at canvas point ([x], [y]) (the baseline). */
private fun EditorViewModel.newText(x: Float, y: Float) {
    val d = doc ?: return
    if (textEdit != null) return
    val size = if (textStyle.size > 0f) textStyle.size else (min(d.width, d.height) * 0.08f).coerceIn(12f, 400f)
    val before = d.snapshot()
    val (at, parent) = insertPoint(d)
    val spec = textStyle.copy(text = "", size = size, matrix = translation(x, y))
    val l = Layer(d.newId(), LayerProps("Text", parent = parent, text = spec), TileGrid(alphaOnly = false), kind = LayerKind.TEXT)
    d.layers.add(at, l)
    d.joinClipGroup(l)
    d.activeId = l.id
    d.editMask = false
    d.invalidate()
    textEdit = TextEditSession(l.id, before, isNew = true)
}

/** Opens a text layer for typing. */
fun EditorViewModel.beginTextEdit(l: Layer) {
    val d = doc ?: return
    if (!l.isText) return
    if (textEdit?.layerId == l.id) return
    if (textEdit != null) finishText()
    if (pendingFirst { beginTextEdit(l) }) return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    withFont(l.props.text?.fontKey) {
        if (doc !== d || d.layer(l.id) !== l || textEdit != null) return@withFont
        // Typing happens with the Text tool (its settings show in Properties)
        if (tool != Tool.TEXT) onToolButton(Tool.TEXT)
        if (d.activeId != l.id) selectLayer(l.id)
        textEdit = TextEditSession(l.id, d.snapshot(), isNew = false)
    }
}

/** The text being typed changed. */
fun EditorViewModel.typeText(s: String) {
    val t = textEdit ?: return
    val l = doc?.layer(t.layerId) ?: return
    val spec = l.props.text ?: return
    val next = spec.copy(text = s)
    // The layer is named after its text until you rename it
    val autoName = l.props.name == spec.title || l.props.name == "Text"
    l.props = l.props.copy(text = next, name = if (autoName) next.title else l.props.name)
    redrawVectorSoon(l)
    doc?.invalidate()
}

/** Done typing: one undo step for the new or changed text. Empty new text is dropped. */
fun EditorViewModel.finishText() {
    val t = textEdit ?: return
    textEdit = null
    stopLiveRedraw()
    val d = doc ?: return
    val l = d.layer(t.layerId)
    if (l == null || !l.isText) return
    if (l.props.text?.text.isNullOrBlank()) {
        d.restore(t.before)
        if (!t.isNew) {
            // Emptied existing text: delete the layer
            stackEdit("Delete text") {
                val i = d.indexOf(l.id)
                if (i >= 0) {
                    d.layers.removeAt(i)
                    d.activeId = d.layers.getOrNull((i - 1).coerceAtLeast(0))?.id ?: -1L
                }
            }
        }
        return
    }
    // Nothing changed: keep the pixels as they are (they may be in a font that can't be had now)
    val changed = t.isNew || t.before.layers.firstOrNull { it.layer === l }?.props != l.props
    if (!changed) return
    // The font was ready when typing started (and font changes wait for theirs)
    redrawVectorNow(l)
    history.record(StackChange(t.before, d.snapshot(), if (t.isNew) "New text" else "Edit text"))
    d.invalidate()
}

/** Throws away the typing (new text disappears; edited text goes back to how it was). */
fun EditorViewModel.cancelText() {
    val t = textEdit ?: return
    textEdit = null
    stopLiveRedraw()
    doc?.restore(t.before)
}

/** The text layer being typed into, if any. */
val EditorViewModel.editingText: Layer? get() = textEdit?.let { doc?.layer(it.layerId) }
