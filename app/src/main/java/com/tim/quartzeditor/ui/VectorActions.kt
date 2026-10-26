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
import com.tim.quartzeditor.engine.toMatrix
import com.tim.quartzeditor.engine.translation
import com.tim.quartzeditor.engine.toAffine
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
class RasterAsk(val layerId: Long, val name: String, val isText: Boolean, val then: (() -> Unit)?, val isSmart: Boolean = false)

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
        .putInt("lastBasicShape", lastBasicShape.ordinal)
        .putBoolean("shapeSquare", shapeSquare)
        .putString("shapeStyle", shapeStyle.copy(path = emptyList(), parts = emptyList(), w = 0f, h = 0f).toJson().toString())
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
    rasterAsk = RasterAsk(l.id, l.props.name, l.isText, then, l.isSmart)
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
    val l = d.layer(id)?.takeIf { it.needsRaster } ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    stackEdit(if (l.isText) "Rasterize text" else if (l.isSmart) "Rasterize smart layer" else "Rasterize shape") {
        // A new grid (same tile bitmaps; painting copies a tile before changing it)
        val grid = TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }
        val px = Layer(l.id, l.props.copy(shape = null, text = null, smart = null), grid, l.x, l.y, l.mask, LayerKind.PIXELS)
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
    if (!l.isVector || l.props.text?.fonts?.all { fontReady(it) } == false) return false
    val b = VectorArt.bounds(l.props) ?: return false
    return !VectorArt.clipFor(x, y, w, h).contains(b)
}

/**
 * From Photoshop: text shows the file's own pixels until it's changed. This takes it off them
 * (returns [props] without [TextSpec.psdFont]) and, when the font stays [oldFontKey] and isn't the
 * original here, says so once.
 */
internal fun EditorViewModel.leavePsdPixels(props: LayerProps, oldFontKey: String?): LayerProps {
    val spec = props.text ?: return props
    val original = spec.psdFont ?: return props
    if (original.isNotEmpty() && spec.fontKey == oldFontKey) {
        val pick = com.tim.quartzeditor.engine.PsdFonts.map(original)
        val missing = com.tim.quartzeditor.engine.Fonts.cached(spec.fontKey) == null
        if (missing || !pick.exact) {
            val shown = if (missing) "the standard font" else com.tim.quartzeditor.engine.Fonts.label(spec.fontKey)
            toast("\"$original\" isn't on this device, so it's shown in $shown. Pick another font in Properties, or add it with Import font… there")
        }
    }
    return props.copy(text = spec.copy(psdFont = null))
}

/** Redraws a shape or text layer's pixels now (text from Photoshop keeps its own until it's edited). */
internal fun EditorViewModel.redrawVectorNow(l: Layer) {
    val d = doc ?: return
    if (!l.isVector) return
    if (l.props.text?.psdFont != null) return
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
    for (k in d.layers.flatMap { it.props.text?.fonts.orEmpty() }.toSet()) fetchFont(k)
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
    val next = withRuns(l.props, change(l.props))
    if (next == l.props) return
    withFonts(next.text?.fonts ?: emptySet()) {
        val cur = d.layer(id)?.takeIf { it.isVector && doc === d } ?: return@withFonts
        // While typing, changes join the text edit (one undo step for all of it)
        if (textEdit?.layerId == id) {
            cur.props = leavePsdPixels(withRuns(cur.props, change(cur.props)), cur.props.text?.fontKey)
            redrawVectorSoon(cur)
            return@withFonts
        }
        stackEdit(label) {
            cur.props = leavePsdPixels(withRuns(cur.props, change(cur.props)), cur.props.text?.fontKey)
            redrawVectorNow(cur)
        }
    }
}

/** [next] (a change to text layer settings [before]) with its differently styled parts following the change. */
private fun withRuns(before: LayerProps, next: LayerProps): LayerProps {
    val b = before.text ?: return next
    val n = next.text ?: return next
    if (n.runs.isEmpty()) return next
    val f = n.runsFollow(b)
    return if (f === n) next else next.copy(text = f)
}

/** A change while a slider moves; [commitVector] makes it one undo step when the finger lifts. */
fun EditorViewModel.editVectorLive(id: Long, change: (LayerProps) -> LayerProps) {
    val d = doc ?: return
    val l = d.layer(id)?.takeIf { it.isVector } ?: return
    if (l.props.locked) return
    // A font still on its way: wait for it (the slider stays put for that moment)
    val waiting = l.props.text?.fonts?.filter { !fontReady(it) }.orEmpty()
    if (waiting.isNotEmpty()) {
        waiting.forEach { fetchFont(it) }
        return
    }
    if (vectorBefore != null && vectorBeforeId != id) commitVector("Change layer")
    if (textEdit?.layerId != id && vectorBefore == null) {
        vectorBefore = d.snapshot()
        vectorBeforeId = id
    }
    l.props = leavePsdPixels(withRuns(l.props, change(l.props)), l.props.text?.fontKey)
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
    // From Custom shapes: back to the shape it was on
    shapeKind = if (shapeKind == ShapeKind.CUSTOM) lastBasicShape else shapeKind.next
    saveVectorPrefs()
    toast(shapeKind.label)
}

/** The shape being dragged out, from canvas point ([x0], [y0]) to ([x1], [y1]). */
fun EditorViewModel.dragShape(x0: Float, y0: Float, x1: Float, y1: Float, square: Boolean = shapeSquare, fromCenter: Boolean = false) {
    if (shapeKind == ShapeKind.ARROW) {
        // From the start of the drag to its end; "straight" snaps to 45° steps
        var a = kotlin.math.atan2(y1 - y0, x1 - x0)
        if (square) a = (Math.round(a / (Math.PI / 4)) * (Math.PI / 4)).toFloat()
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val h = (len * 0.35f).coerceAtLeast(12f)
        val m = android.graphics.Matrix().apply {
            setTranslate(0f, -h / 2f)
            postRotate(Math.toDegrees(a.toDouble()).toFloat())
            postTranslate(x0, y0)
        }
        shapeDraft = shapeStyle.copy(kind = ShapeKind.ARROW, w = len, h = h, path = emptyList(), closed = true, matrix = m.toAffine())
        return
    }
    var w = x1 - x0
    var h = y1 - y0
    val lib = if (shapeKind == ShapeKind.CUSTOM) currentLibraryShape else null
    if (square) {
        // Custom shapes keep their own proportions; the others are square or round
        val aspect = lib?.let { it.shape.h / it.shape.w.coerceAtLeast(0.001f) } ?: 1f
        val m = max(abs(w), abs(h) / aspect)
        w = if (w < 0) -m else m
        h = (if (y1 - y0 < 0) -m else m) * aspect
    }
    fun styled(sw: Float, sh: Float, m: com.tim.quartzeditor.engine.Affine): ShapeSpec {
        val base = shapeStyle.copy(kind = shapeKind, w = sw, h = sh, path = emptyList(), closed = true, matrix = m)
        return if (lib != null) withLibraryShape(base, lib, keepBox = true) else base
    }
    if (fromCenter) {
        // The drag start is the middle
        shapeDraft = styled(abs(w) * 2f, abs(h) * 2f, translation(x0 - abs(w), y0 - abs(h)))
        return
    }
    val left = min(x0, x0 + w)
    val top = min(y0, y0 + h)
    shapeDraft = styled(abs(w), abs(h), translation(left, top))
}

fun EditorViewModel.finishShape() {
    val s = shapeDraft ?: return
    shapeDraft = null
    // Too small to be on purpose
    if (s.w * scale < 6f && s.h * scale < 6f) return
    addShapeLayer(s, if (s.kind == ShapeKind.CUSTOM) s.shapeName.ifBlank { s.kind.label } else s.kind.label)
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
    penDrag = if (p != null && penCurvature) p.corner() else p
}

/** The open pen shape being carried on (see [EditorViewModel.penContinue]), if it's still there. */
private fun EditorViewModel.continuedLayer(): Layer? {
    val (id, _) = penContinue ?: return null
    return doc?.layer(id)?.takeIf { it.isShape && it.props.shape?.kind == ShapeKind.PATH && !it.props.locked }
}

/** Canvas positions of the continued shape's points, in drawing order (the end being carried on last). */
private fun EditorViewModel.continuedPath(): List<PenPoint>? {
    val l = continuedLayer() ?: return null
    val (_, atEnd) = penContinue ?: return null
    val m = l.pathMatrix()
    val pts = l.props.shape!!.path.map { p ->
        val a = m.map(p.x, p.y)
        val v = floatArrayOf(p.hx, p.hy, p.ix, p.iy)
        m.mapVectors(v)
        PenPoint(a.x, a.y, v[0], v[1], v[2], v[3])
    }
    // Carrying on from the first point: the shape is walked backwards (each point's handles swap)
    return if (atEnd) pts else pts.reversed().map { PenPoint(it.x, it.y, it.ix, it.iy, it.hx, it.hy) }
}

/**
 * The points as they'll be made (canvas pixels): the Curvature Pen's smoothed through the taps,
 * the Pen's as placed. [extra] (the point being placed) is added at the end.
 */
fun EditorViewModel.penShown(extra: PenPoint? = null, closed: Boolean = false): List<PenPoint> {
    val raw = penPoints.toList() + listOfNotNull(extra)
    if (!penCurvature) return raw
    val path = continuedPath()
    val before = path?.let { it.getOrNull(it.size - 2) }
    // Closing a shape carried on: the new part runs smoothly into its other end
    val after = if (closed && path != null) path.first() else null
    return PenPoint.curvature(raw, { i -> penCorners.getOrElse(i) { false } }, closed && path == null, before = before, after = after)
}

/**
 * Places a pen point (canvas pixels). Tapping near the first point (within [closeRadius]) closes
 * the shape and makes the layer. With nothing placed yet and a pen shape selected, a tap on its
 * outline adds a point to it, and a tap on an open shape's end carries that shape on.
 * Curvature Pen: tapping the last point again makes it a sharp corner (or smooth again).
 */
fun EditorViewModel.penAdd(p0: PenPoint, closeRadius: Float, rawX: Float = p0.x, rawY: Float = p0.y) {
    penDrag = null
    val p = if (penCurvature) p0.corner() else p0
    if (penPoints.isEmpty() && penStartOnShape(rawX, rawY, closeRadius)) return
    // Where the finger was (not where it snapped to) decides what it touched
    fun near(q: PenPoint) = hypot(rawX - q.x, rawY - q.y) <= closeRadius
    // Placed points are touched only close up (half the closing distance), so points can go near each other
    val hit = penPointable(penPointAt(rawX, rawY, closeRadius / 2f))
    val cont = continuedPath()
    if (penContinue != null && cont != null) {
        // Closing onto the continued shape's other end
        if (hit < 0 && penPoints.size >= 2 && cont.size >= 2 && near(cont.first())) {
            finishPen(closed = true)
            return
        }
    } else {
        val first = penPoints.firstOrNull()
        if (first != null && penPoints.size >= 2 && (hit == 0 || (hit < 0 && near(first)))) {
            // Dragging on the first point reshapes its curve as it closes
            if (p.smooth) penPoints[0] = first.copy(hx = p.hx, hy = p.hy, ix = -p.hx, iy = -p.hy)
            finishPen(closed = true)
            return
        }
    }
    // A tap on a point already placed doesn't add one: a double tap makes it a corner or a curve
    if (hit >= 0) {
        penTapPoint(hit)
        return
    }
    penLastTap = -1
    penRemember()
    penPoints.add(p)
    while (penCorners.size < penPoints.size) penCorners.add(false)
}

/** [i] if that placed point can be moved or switched (not a carried-on shape's own end, which belongs to that shape), else −1. */
fun EditorViewModel.penPointable(i: Int): Int = if (i == 0 && penContinue != null) -1 else i

/** Keeps the points as they are, so Undo while drawing takes back the next change (a point, a move, a switch). */
fun EditorViewModel.penRemember() {
    penHistory.add(penPoints.toList() to penCorners.toList())
    if (penHistory.size > 200) penHistory.removeAt(0)
}

/** The placed point nearest canvas point ([cx], [cy]) within [radius], or −1. */
fun EditorViewModel.penPointAt(cx: Float, cy: Float, radius: Float): Int {
    var best = -1
    var bestD = radius
    penPoints.forEachIndexed { i, q ->
        val d = hypot(q.x - cx, q.y - cy)
        if (d <= bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/** Puts placed point [i] at [p] (dragging it while drawing). */
fun EditorViewModel.penPutPoint(i: Int, p: PenPoint) {
    if (i in penPoints.indices) penPoints[i] = p
}

/** A tap on placed point [i]: the second of two quick taps switches it between a sharp corner and a smooth curve. */
private fun EditorViewModel.penTapPoint(i: Int) {
    val now = android.os.SystemClock.uptimeMillis()
    if (penLastTap != i || now - penLastTapAt > 450) {
        penLastTap = i
        penLastTapAt = now
        return
    }
    penLastTap = -1
    penRemember()
    if (penCurvature) {
        while (penCorners.size < penPoints.size) penCorners.add(false)
        penCorners[i] = !penCorners[i]
        toast(if (penCorners[i]) "Corner point" else "Smooth point")
        return
    }
    // The Pen: a curve point gets handles along the line from its neighbors; a corner loses them
    val q = penPoints[i]
    if (q.smooth) {
        penPoints[i] = q.corner()
        toast("Corner point")
        return
    }
    val prev = penPoints.getOrNull(i - 1) ?: q
    val next = penPoints.getOrNull(i + 1) ?: q
    var dx = (next.x - prev.x) * 0.2f
    var dy = (next.y - prev.y) * 0.2f
    if (dx == 0f && dy == 0f) {
        // On its own: a handle that looks the same size at any zoom
        dx = 20f / scale.coerceAtLeast(0.01f)
        dy = 0f
    }
    penPoints[i] = q.copy(hx = dx, hy = dy, ix = -dx, iy = -dy)
    toast("Curve point")
}

/**
 * The first tap with a pen shape selected: on one of an open shape's ends, carries that shape on;
 * on its outline, adds a point there (and goes on to editing points, so it can be dragged).
 */
private fun EditorViewModel.penStartOnShape(cx: Float, cy: Float, radius: Float): Boolean {
    val l = doc?.active?.takeIf { it.isShape && it.props.shape?.kind == ShapeKind.PATH && !it.props.locked && it.props.visible } ?: return false
    val s = l.props.shape ?: return false
    if (!s.closed && s.path.size >= 2) {
        val m = l.pathMatrix()
        val a = m.map(s.path.first().x, s.path.first().y)
        val z = m.map(s.path.last().x, s.path.last().y)
        val atEnd = when {
            hypot(z.x - cx, z.y - cy) <= radius -> true
            hypot(a.x - cx, a.y - cy) <= radius -> false
            else -> null
        }
        if (atEnd != null) {
            penContinue = l.id to atEnd
            val path = continuedPath() ?: return false.also { penContinue = null }
            penPoints.add(path.last())
            // The Curvature Pen smooths the end into the new part; the Pen keeps it as it was
            penCorners.add(false)
            toast("Carrying on \"${l.props.name}\": tap to add points, its other end to close it")
            return true
        }
    }
    val i = insertPenPoint(l, cx, cy, radius) ?: return false
    penEditing = true
    penSelected = i
    toast("Point added: drag it to reshape")
    return true
}

fun EditorViewModel.undoPenPoint() {
    if (penPoints.isEmpty()) return
    penLastTap = -1
    // The last change while drawing (a point added, moved or switched)
    penHistory.removeLastOrNull()?.let { (pts, corners) ->
        if (pts.isEmpty() || (penContinue != null && pts.size <= 1 && penPoints.size <= 1)) {
            cancelPen()
            return
        }
        penPoints.clear()
        penPoints.addAll(pts)
        penCorners.clear()
        penCorners.addAll(corners)
        return
    }
    // Carrying on a shape: its own end stays (taking it away stops carrying on)
    if (penContinue != null && penPoints.size == 1) {
        cancelPen()
        return
    }
    penPoints.removeAt(penPoints.lastIndex)
    while (penCorners.size > penPoints.size) penCorners.removeAt(penCorners.lastIndex)
    penLastTap = -1
}

fun EditorViewModel.cancelPen() {
    penLastTap = -1
    penHistory.clear()
    penPoints.clear()
    penCorners.clear()
    penDrag = null
    penContinue = null
}

/** Makes a shape layer from the pen points: [closed] joins the last point to the first. */
fun EditorViewModel.finishPen(closed: Boolean) {
    // Carrying on a shape that's since gone (deleted, locked, undone): the points become a shape of their own
    if (penContinue != null && continuedLayer() == null) {
        penContinue = null
        toast("The shape being carried on isn't there any more: this is a new shape")
    }
    val cont = penContinue
    val pts = penShown(closed = closed)
    penLastTap = -1
    penHistory.clear()
    penPoints.clear()
    penCorners.clear()
    penDrag = null
    penContinue = null
    if (cont != null) {
        finishContinued(cont, pts, closed)
        return
    }
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

/**
 * Puts the points drawn on from an open shape's end into that shape ([pts] in canvas pixels, the
 * first being the end it carried on from); [closed] joins it up.
 */
private fun EditorViewModel.finishContinued(cont: Pair<Long, Boolean>, pts: List<PenPoint>, closed: Boolean) {
    val d = doc ?: return
    val (id, atEnd) = cont
    val l = d.layer(id)?.takeIf { it.isShape && it.props.shape?.kind == ShapeKind.PATH } ?: return
    val s = l.props.shape ?: return
    if (pts.size < 2 && !closed) return
    val inv = android.graphics.Matrix()
    if (!l.pathMatrix().invert(inv)) return
    // Back into the shape's own space
    val local = pts.map { p ->
        val a = inv.map(p.x, p.y)
        val v = floatArrayOf(p.hx, p.hy, p.ix, p.iy)
        inv.mapVectors(v)
        PenPoint(a.x, a.y, v[0], v[1], v[2], v[3])
    }
    // The end it carried on from takes its handles from the drawing (the Curvature Pen smooths it);
    // the rest are new. Walking backwards (from the first point) is turned the right way round.
    val joined = if (atEnd) {
        s.path.dropLast(1) + local
    } else {
        local.reversed().map { PenPoint(it.x, it.y, it.ix, it.iy, it.hx, it.hy) } + s.path.drop(1)
    }
    editVector(id, if (closed) "Close shape" else "Add points") { it.copy(shape = it.shape?.copy(path = joined, closed = closed && joined.size >= 3)) }
    penEditing = true
    penSelected = -1
}

/**
 * Selection to shape: the selection's outline becomes a shape layer filled with the foreground color
 * (one outline: a pen shape whose points you can edit; several, or with holes: one shape of them all).
 */
fun EditorViewModel.selectionToShape() {
    val d = doc ?: return
    if (pendingFirst { selectionToShape() }) return
    val sel = d.selection?.takeIf { !it.isEmpty }
    if (sel == null) {
        toast("Select something first")
        return
    }
    val color = foreground
    runBusy("Making a shape…") {
        val loops = withContext(Dispatchers.Default) { com.tim.quartzeditor.engine.SelectionTrace.outlines(sel, d.width, d.height) }
        if (doc !== d) return@runBusy
        if (loops.isEmpty()) {
            toast("The selection is too faint or small to make a shape from")
            return@runBusy
        }
        val all = loops.flatten()
        val left = all.minOf { it.x }
        val top = all.minOf { it.y }
        val w = (all.maxOf { it.x } - left).coerceAtLeast(1f)
        val h = (all.maxOf { it.y } - top).coerceAtLeast(1f)
        fun local(pts: List<PenPoint>) = pts.map { it.copy(x = it.x - left, y = it.y - top) }
        val spec = if (loops.size == 1) {
            ShapeSpec(kind = ShapeKind.PATH, w = w, h = h, path = local(loops[0]), closed = true, fill = color, stroke = null, matrix = translation(left, top))
        } else {
            // Outlines inside outlines are holes (even-odd), like the selection
            ShapeSpec(
                kind = ShapeKind.CUSTOM, w = w, h = h, viewW = w, viewH = h, evenOdd = true,
                parts = loops.map { com.tim.quartzeditor.engine.ShapePart(local(it), closed = true) },
                fill = color, stroke = null, matrix = translation(left, top),
            )
        }
        addShapeLayer(spec, "Shape from selection")
        toast(if (loops.size == 1) "New shape layer: the Pen's Edit points works on it" else "New shape layer (${loops.size} outlines)")
    }
}

// ===================================================================== Text tool

/** Text tool tap at canvas point ([x], [y]): edits the text there, or starts new text. */
fun EditorViewModel.textTap(x: Float, y: Float) {
    val d = doc ?: return
    if (textEdit != null) {
        // In the text being typed: the cursor goes there; anywhere else: done
        val l = editingText
        val at = l?.let { textOffsetAt(it, x, y) }
        if (at != null) {
            textValue = textValue.copy(selection = androidx.compose.ui.text.TextRange(at))
            textFocusTick++
        } else finishText()
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
    textValue = androidx.compose.ui.text.input.TextFieldValue("")
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
    // From Photoshop: a font imported since it was opened can stand in for the original now
    val spec0 = l.props.text
    val swapTo = spec0?.psdFont?.takeIf { it.isNotEmpty() }?.let { com.tim.quartzeditor.engine.PsdFonts.map(it) }
        ?.takeIf { it.exact && it.key != spec0.fontKey }?.key
    withFonts(setOfNotNull(swapTo ?: l.props.text?.fontKey) + (l.props.text?.runs?.mapNotNull { it.fontKey } ?: emptyList())) {
        if (doc !== d || d.layer(l.id) !== l || textEdit != null) return@withFonts
        // Typing happens with the Text tool (its settings show in Properties)
        if (tool != Tool.TEXT) onToolButton(Tool.TEXT)
        if (d.activeId != l.id) selectLayer(l.id)
        val words = l.props.text?.text.orEmpty()
        textValue = androidx.compose.ui.text.input.TextFieldValue(words, androidx.compose.ui.text.TextRange(words.length))
        textEdit = TextEditSession(l.id, d.snapshot(), isNew = false)
        // From Photoshop: until now it showed Photoshop's pixels; from here it's drawn here
        if (l.props.text?.psdFont != null) {
            var p = l.props
            if (swapTo != null) p = p.copy(text = p.text!!.copy(fontKey = swapTo))
            l.props = leavePsdPixels(p, p.text?.fontKey)
            redrawVectorNow(l)
        }
    }
}

/** Typing on the picture: the words and where the cursor is. */
fun EditorViewModel.typeTextValue(v: androidx.compose.ui.text.input.TextFieldValue) {
    val changed = v.text != textValue.text
    textValue = v
    if (changed) typeText(v.text)
    else doc?.invalidate()
}

/**
 * Where in text layer [l]'s words canvas point ([x], [y]) is (an offset for the cursor), or null when
 * it's outside the text. Bent and arched text: the end.
 */
fun EditorViewModel.textOffsetAt(l: Layer, x: Float, y: Float): Int? {
    val spec = l.props.text ?: return null
    val box = VectorArt.bounds(l.props)?.apply { offset(l.x.toFloat(), l.y.toFloat()) } ?: return null
    if (!box.contains(x, y)) return null
    val b = VectorArt.buildText(spec)
    val layout = b.layout
    if (layout == null || b.warp != null) return spec.text.length
    val inv = android.graphics.Matrix()
    if (!spec.matrix.toMatrix().invert(inv)) return spec.text.length
    val p = floatArrayOf(x - l.x, y - l.y)
    inv.mapPoints(p)
    val line = layout.getLineForVertical((p[1] - b.top).toInt())
    return layout.getOffsetForHorizontal(line, p[0] - b.left).coerceIn(0, spec.text.length)
}

/** The text being typed changed. */
fun EditorViewModel.typeText(s: String) {
    val t = textEdit ?: return
    val l = doc?.layer(t.layerId) ?: return
    val spec = l.props.text ?: return
    val next = spec.copy(text = s, runs = com.tim.quartzeditor.engine.TextRun.adjust(spec.runs, spec.text, s))
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
    // Nothing changed: keep the pixels as they are (they may be in a font that can't be had now;
    // text from Photoshop gets its own pixels back)
    fun LayerProps.plain() = copy(text = text?.copy(psdFont = null))
    val changed = t.isNew || t.before.layers.firstOrNull { it.layer === l }?.props?.plain() != l.props.plain()
    if (!changed) {
        d.restore(t.before)
        return
    }
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
