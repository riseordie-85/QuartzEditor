package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.FaceAware
import com.tim.quartzeditor.engine.FaceFeatures
import com.tim.quartzeditor.engine.FaceGroup
import com.tim.quartzeditor.engine.FaceSlider
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.LiquifyTool
import com.tim.quartzeditor.engine.MeshPreview
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.Transformer
import com.tim.quartzeditor.engine.WarpKind
import com.tim.quartzeditor.engine.WarpMath
import com.tim.quartzeditor.engine.WarpMesh
import com.tim.quartzeditor.engine.WarpPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Warp (a 4×4 curve grid, with ready-made shapes), Puppet Warp (pins) and Liquify (brushes) on the
// active pixel layer. Like Transform, it's shown on a smaller copy while you work and applied at
// full size at the end, as one undo step. Undo while warping steps back one change at a time.

/** The layer being warped and everything about the warp so far. */
class WarpSession(
    val kind: WarpKind,
    val layer: Layer,
    val tiles: Map<Long, Bitmap>,
    val mask: Map<Long, Bitmap>?,
    val sourceGrid: TileGrid,
    /** Layer-local area the mesh started over. */
    val src: Rect,
    /** Where the layer was (its tiles' origin on the canvas). */
    val lx: Int,
    val ly: Int,
    /** A smaller copy of [src] (mask applied) to show. */
    val proxy: Bitmap,
    val mesh: WarpMesh,
) {
    /** Bumped on every change (redraws the handles). */
    var version by mutableIntStateOf(0)

    // Warp: the 16 curve controls (canvas pixels), and the ready-made shape they came from
    var ctrl: FloatArray = WarpMath.restControls(mesh.area)
    var preset by mutableStateOf(WarpPreset.CUSTOM)
    var bend by mutableFloatStateOf(0.5f)

    // Puppet Warp: where each pin started (on the unwarped picture) and where it is now
    val pinFrom = ArrayList<PointF>()
    val pinTo = ArrayList<PointF>()
    var pinSelected by mutableIntStateOf(-1)
    var stiffness by mutableFloatStateOf(1f)

    // Face-aware Liquify: the faces found, the one being changed, and each face's slider values
    var faces by mutableStateOf<List<FaceFeatures>>(emptyList())
    var facesLooked by mutableStateOf(false)
    var faceIndex by mutableIntStateOf(0)
    var faceGroup by mutableStateOf(FaceGroup.EYES)
    internal var faceValues: List<MutableMap<FaceSlider, Float>> = emptyList()
    private val edge by lazy { FaceAware.edgeOf(mesh) }

    val hasFaceEdits: Boolean get() = faceValues.any { m -> m.values.any { it != 0f } }

    /**
     * Makes the face sliders' changes part of the mesh itself and sets them back to 0, moving the
     * faces' feature points along, so brushes (and "Look again") work on what you see.
     */
    internal fun bakeFaces() {
        if (!hasFaceEdits) return
        // Feature points go through the same changes as the mesh (without fading at its edge)
        val marks = faces.map { it.points() }
        val flat = FloatArray(marks.sumOf { it.size } * 2)
        var n = 0
        for (list in marks) for (p in list) {
            flat[n++] = p.x
            flat[n++] = p.y
        }
        val noEdge = FloatArray(flat.size / 2) { Float.MAX_VALUE }
        outputInto(mesh.pts)
        faces.forEachIndexed { i, f -> faceValues.getOrNull(i)?.takeIf { m -> m.values.any { it != 0f } }?.let { FaceAware.apply(flat, noEdge, f, it) } }
        mesh.refreshShift()
        n = 0
        faces = faces.mapIndexed { i, f ->
            f.withPoints(marks[i].map { PointF(flat[n++], flat[n++]) })
        }
        faceValues = faces.map { HashMap() }
    }

    /** Where the mesh points end up: Liquify's brushes, then the face sliders (a new array). */
    fun output(): FloatArray = FloatArray(mesh.pts.size).also { outputInto(it) }

    fun outputInto(out: FloatArray) {
        if (out !== mesh.pts) mesh.pts.copyInto(out)
        faces.forEachIndexed { i, f -> faceValues.getOrNull(i)?.takeIf { m -> m.values.any { it != 0f } }?.let { FaceAware.apply(out, edge, f, it) } }
    }

    // Two arrays for what's on screen, used in turn (drawing copies the points it's given, so the
    // one from the frame before can be reused)
    private val shown = arrayOf(FloatArray(mesh.pts.size), FloatArray(mesh.pts.size))
    private var shownIndex = 0
    internal fun nextShown(): FloatArray {
        shownIndex = 1 - shownIndex
        return shown[shownIndex]
    }

    /** Something changed during the gesture under way (otherwise it isn't kept as a step). */
    internal var changed = false

    /** Earlier states, for undo within the warp. */
    internal val steps = ArrayDeque<Snap>()

    internal class Snap(
        val pts: FloatArray,
        val ctrl: FloatArray,
        val preset: WarpPreset,
        val bend: Float,
        val pinFrom: List<PointF>,
        val pinTo: List<PointF>,
        val faceValues: List<Map<FaceSlider, Float>>,
        val stiffness: Float,
        val faces: List<FaceFeatures>,
        val facesLooked: Boolean,
    )

    internal fun snap() = Snap(
        mesh.pts.copyOf(), ctrl.copyOf(), preset, bend, pinFrom.map { PointF(it.x, it.y) }, pinTo.map { PointF(it.x, it.y) },
        faceValues.map { HashMap(it) }, stiffness, faces, facesLooked,
    )

    internal fun restore(s: Snap) {
        faces = s.faces
        facesLooked = s.facesLooked
        if (faceIndex >= faces.size) faceIndex = 0
        faceValues = faces.indices.map { HashMap(s.faceValues.getOrNull(it) ?: emptyMap()) }
        s.pts.copyInto(mesh.pts)
        mesh.refreshShift()
        stiffness = s.stiffness
        ctrl = s.ctrl.copyOf()
        preset = s.preset
        bend = s.bend
        pinFrom.clear(); pinFrom.addAll(s.pinFrom.map { PointF(it.x, it.y) })
        pinTo.clear(); pinTo.addAll(s.pinTo.map { PointF(it.x, it.y) })
        if (pinSelected >= pinTo.size) pinSelected = pinTo.size - 1
    }

    internal fun pushStep() {
        steps.addLast(snap())
        while (steps.size > 40) steps.removeFirst()
    }

    // The gesture under way
    internal var dragCtrl = -1
    internal var dragUV: Pair<Float, Float>? = null
    internal var dragStartCtrl: FloatArray? = null
    internal var dragPin = -1
    internal var dragStartPin: PointF? = null
    internal var startX = 0f
    internal var startY = 0f
    internal var lastX = 0f
    internal var lastY = 0f
}

// ===================================================================== starting and ending

/** Picks Warp, Puppet Warp or Liquify (from the Transform button's long-press list or a shortcut). */
fun EditorViewModel.chooseWarp(kind: WarpKind) {
    // Already open: nothing to do (and nothing gets applied by accident)
    if (warp?.kind == kind) return
    if (pendingFirst { chooseWarp(kind) }) return
    warpKind = kind
    prefs.edit().putInt("warpKind", kind.ordinal).apply()
    if (tool != Tool.WARP) onToolButton(Tool.WARP) else beginWarp()
}

/** Starts warping the active layer the way [EditorViewModel.warpKind] says. */
fun EditorViewModel.beginWarp() {
    val d = doc ?: return
    if (warp != null) return
    val l = d.active ?: return
    val kind = warpKind
    when {
        l.isGroup || l.isAdjustment -> {
            toast("${kind.label} works on a pixel layer")
            return
        }
        l.props.locked -> {
            toast("\"${l.props.name}\" is locked")
            return
        }
        !l.props.visible -> {
            toast("\"${l.props.name}\" is hidden")
            return
        }
        l.needsRaster -> {
            askRasterize(l) { beginWarp() }
            return
        }
        l.tiles.isEmpty -> {
            toast("This layer is empty")
            return
        }
    }
    val tiles = HashMap(l.tiles.tiles)
    val mask = l.mask?.let { HashMap(it.tiles) }
    val maskOn = l.maskActive
    val source = l.tiles
    val lx = l.x
    val ly = l.y
    val canvasLocal = Rect(-lx, -ly, d.width - lx, d.height - ly)
    runBusy("Preparing…") {
        val made = withContext(Dispatchers.Default) {
            val content = Transformer.contentBounds(tiles) ?: return@withContext null
            val src = when (kind) {
                // Room around the picture to push into, but never past the canvas (an edge-to-edge
                // photo keeps its edges where they are)
                WarpKind.LIQUIFY -> {
                    val m = max(32, (max(content.width(), content.height()) * 0.15f).roundToInt())
                    val grown = Rect(content).apply { inset(-m, -m) }
                    val allowed = Rect(canvasLocal).apply { union(content) }
                    if (!grown.intersect(allowed)) Rect(content) else grown
                }
                // A little see-through edge, so the outline stays smooth when it bends
                else -> Rect(content).apply { inset(-2, -2) }
            }
            val ps = min(1f, EditorViewModel.PREVIEW_PX / max(src.width(), src.height()).toFloat())
            val w = (src.width() * ps).roundToInt().coerceAtLeast(1)
            val h = (src.height() * ps).roundToInt().coerceAtLeast(1)
            val proxy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(proxy).apply {
                scale(w / src.width().toFloat(), h / src.height().toFloat())
                translate(-src.left.toFloat(), -src.top.toFloat())
                saveLayer(null, null)
                val p = Paint().apply { isFilterBitmap = true }
                TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }.drawTo(this, 0f, 0f, p)
                if (mask != null && maskOn) {
                    TileGrid(alphaOnly = true).also { it.tiles.putAll(mask) }.drawTo(this, 0f, 0f, Paint().apply {
                        isFilterBitmap = true
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    })
                }
                restore()
            }
            val area = RectF(src).apply { offset(lx.toFloat(), ly.toFloat()) }
            val side = max(area.width(), area.height())
            val mesh = when (kind) {
                WarpKind.WARP -> WarpMesh.over(area, side / 40f, 48)
                WarpKind.PUPPET -> WarpMesh.over(area, side / 64f, 72)
                // Fine enough for small brushes, but never finer than 3 pixels
                WarpKind.LIQUIFY -> WarpMesh.over(area, max(3f, side / 180f), 200)
            }
            Triple(src, proxy, mesh)
        } ?: run {
            toast("This layer is empty")
            return@runBusy
        }
        if (doc !== d || l.tiles !== source || tool != Tool.WARP) return@runBusy
        val (src, proxy, mesh) = made
        val s = WarpSession(kind, l, tiles, mask, source, src, lx, ly, proxy, mesh)
        warp = s
        showWarp(s)
        if (kind == WarpKind.PUPPET) toast("Tap to add pins, then drag them")
    }
}

internal fun EditorViewModel.showWarp(s: WarpSession) {
    s.version++
    val pts = s.nextShown()
    s.outputInto(pts)
    preview = LayerPreview(s.layer.id, mesh = MeshPreview(s.proxy, s.mesh.cols, s.mesh.rows, pts))
    doc?.invalidate()
}

fun EditorViewModel.cancelWarp() {
    if (warp == null) return
    liquifyJob?.cancel()
    warp = null
    warpCursor = null
    preview = null
    tool = Tool.MOVE
    doc?.invalidate()
}

/** Applies the warp at full size (pixels and mask), as one undo step, then runs [onDone]. */
fun EditorViewModel.applyWarp(onDone: () -> Unit = {}) {
    val d = doc ?: return
    val s = warp ?: return
    if (busy != null) return
    liquifyJob?.cancel()
    warpCursor = null
    val pts = s.output().copyOf()
    if (WarpMath.nearlyUnchanged(pts, s.mesh.rest)) {
        cancelWarp()
        onDone()
        return
    }
    val cols = s.mesh.cols
    val rows = s.mesh.rows
    runBusy("${s.kind.label}…", onDone) {
        val (grid, maskGrid) = withContext(Dispatchers.Default) {
            val limit = Transformer.limitFor(d.width, d.height)
            val g = WarpMath.render(s.tiles, false, s.src, pts, cols, rows, limit)
                ?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() } }
                ?: TileGrid(alphaOnly = false)
            val m = s.mask?.let { mk ->
                WarpMath.renderMask(mk, s.src, s.lx, s.ly, pts, cols, rows, limit)
                    ?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = true).also { bmp.recycle() } }
                    ?: TileGrid(alphaOnly = true)
            }
            g to m
        }
        warp = null
        preview = null
        val l = s.layer
        if (doc === d && l.tiles === s.sourceGrid) {
            stackEdit(s.kind.label) {
                l.tiles = grid
                l.x = 0
                l.y = 0
                if (l.mask != null && maskGrid != null) l.mask = maskGrid
            }
        }
        tool = Tool.MOVE
    }
}

/** Steps back one change within the warp. False when there's nothing to step back to. */
fun EditorViewModel.warpUndo(): Boolean {
    val s = warp ?: return false
    val last = s.steps.removeLastOrNull() ?: return false
    s.restore(last)
    showWarp(s)
    return true
}

/** Back to how the layer was (the warp stays open). */
fun EditorViewModel.resetWarp() {
    val s = warp ?: return
    if (WarpMath.nearlyUnchanged(s.output(), s.mesh.rest) && s.pinTo.isEmpty()) return
    s.pushStep()
    s.mesh.rest.copyInto(s.mesh.pts)
    s.mesh.maxShift = 0f
    s.faceValues = s.faces.map { HashMap() }
    s.ctrl = WarpMath.restControls(s.mesh.area)
    s.preset = WarpPreset.CUSTOM
    s.pinFrom.clear()
    s.pinTo.clear()
    s.pinSelected = -1
    showWarp(s)
}

// ===================================================================== touches

/**
 * A finger went down at canvas point ([x], [y]); [reach] is how close (canvas pixels) counts as
 * touching a handle. True when the warp takes this gesture (otherwise it may pan, or be a tap).
 */
fun EditorViewModel.warpDown(x: Float, y: Float, reach: Float): Boolean {
    val s = warp ?: return false
    s.startX = x
    s.startY = y
    s.lastX = x
    s.lastY = y
    when (s.kind) {
        WarpKind.WARP -> {
            var best = -1
            var bd = reach
            for (k in 0 until 16) {
                val dd = hypot(s.ctrl[k * 2] - x, s.ctrl[k * 2 + 1] - y)
                if (dd < bd) {
                    bd = dd
                    best = k
                }
            }
            s.dragCtrl = best
            s.dragUV = null
            if (best < 0) {
                // On the picture: drag its surface
                val (i, j, dist) = s.mesh.nearest(x, y)
                if (dist > max(reach, s.mesh.cell * 1.5f)) return false
                s.dragUV = i / s.mesh.cols.toFloat() to j / s.mesh.rows.toFloat()
            }
            s.dragStartCtrl = s.ctrl.copyOf()
            s.pushStep()
            return true
        }
        WarpKind.PUPPET -> {
            val i = nearestPin(s, x, y, reach)
            if (i < 0) return false
            s.pinSelected = i
            s.dragPin = i
            s.dragStartPin = PointF(s.pinTo[i].x, s.pinTo[i].y)
            s.pushStep()
            s.version++
            return true
        }
        WarpKind.LIQUIFY -> {
            s.pushStep()
            s.changed = false
            // Brushes work on what you see: the face sliders' changes become part of the mesh first
            val r = liquifyRadius(s)
            val nearFace = s.faces.any { f ->
                val m = r + 0.3f * max(f.faceW, f.faceH)
                x > f.box.left - m && x < f.box.right + m && y > f.box.top - m && y < f.box.bottom + m
            }
            if (s.hasFaceEdits && nearFace) {
                s.bakeFaces()
                s.changed = true
                s.version++
            }
            warpCursor = PointF(x, y)
            val t = liquifyTool
            if (t.continuous) {
                liquifyJob?.cancel()
                liquifyJob = viewModelScope.launch {
                    while (isActive && warp === s) {
                        val c = warpCursor ?: break
                        // On the main thread, like every other change to the mesh (it's quick)
                        if (WarpMath.liquify(s.mesh, t, c.x, c.y, liquifyRadius(s), liquifyStrength)) {
                            s.changed = true
                            showWarp(s)
                        }
                        delay(16)
                    }
                }
            }
            return true
        }
    }
}

/** The brush radius Liquify really uses: never smaller than the mesh's cells (or it would catch no points). */
fun EditorViewModel.liquifyRadius(s: WarpSession): Float = max(liquifySize / 2f, s.mesh.cell * 1.25f)

/**
 * The finger that started the gesture is at ([x], [y]) now. [show]: redraw (false for the
 * in-between points of one touch event; the last one redraws).
 */
fun EditorViewModel.warpMove(x: Float, y: Float, show: Boolean = true) {
    val s = warp ?: return
    val dx = x - s.startX
    val dy = y - s.startY
    when (s.kind) {
        WarpKind.WARP -> {
            val start = s.dragStartCtrl ?: return
            s.ctrl = if (s.dragCtrl >= 0) {
                start.copyOf().also {
                    it[s.dragCtrl * 2] += dx
                    it[s.dragCtrl * 2 + 1] += dy
                }
            } else {
                val (u, v) = s.dragUV ?: return
                WarpMath.dragSurface(start, u, v, dx, dy)
            }
            s.preset = WarpPreset.CUSTOM
            WarpMath.applyPatch(s.ctrl, s.mesh)
            if (show) showWarp(s)
        }
        WarpKind.PUPPET -> {
            val p0 = s.dragStartPin ?: return
            val i = s.dragPin.takeIf { it in s.pinTo.indices } ?: return
            s.pinTo[i] = PointF(p0.x + dx, p0.y + dy)
            if (show) updatePuppet(s)
        }
        WarpKind.LIQUIFY -> {
            if (show) warpCursor = PointF(x, y) else warpCursor?.set(x, y)
            if (!liquifyTool.continuous && WarpMath.push(s.mesh, s.lastX, s.lastY, x, y, liquifyRadius(s), liquifyStrength)) s.changed = true
            if (show && s.changed) showWarp(s)
        }
    }
    s.lastX = x
    s.lastY = y
}

/** All fingers up. [moved]: the finger moved enough to count as a drag. */
fun EditorViewModel.warpUp(moved: Boolean) {
    val s = warp ?: return
    liquifyJob?.cancel()
    liquifyJob = null
    warpCursor = null
    // A touch that changed nothing isn't a step to undo
    if (if (s.kind == WarpKind.LIQUIFY) !s.changed else !moved) s.steps.removeLastOrNull()
    s.changed = false
    s.dragCtrl = -1
    s.dragUV = null
    s.dragStartCtrl = null
    s.dragPin = -1
    s.dragStartPin = null
    s.version++
}

/** A second finger came down: the gesture is undone and becomes looking around. */
fun EditorViewModel.warpCancelGesture() {
    val s = warp ?: return
    liquifyJob?.cancel()
    liquifyJob = null
    warpCursor = null
    s.steps.removeLastOrNull()?.let { s.restore(it) }
    s.dragCtrl = -1
    s.dragUV = null
    s.dragPin = -1
    showWarp(s)
}

/** A tap at ([x], [y]): Puppet Warp adds a pin there (or picks the one there). */
fun EditorViewModel.warpTap(x: Float, y: Float, reach: Float) {
    val s = warp ?: return
    if (s.kind != WarpKind.PUPPET) return
    val near = nearestPin(s, x, y, reach)
    if (near >= 0) {
        s.pinSelected = near
        s.version++
        return
    }
    // Only on the picture being warped
    val (i, j, dist) = s.mesh.nearest(x, y)
    if (dist > s.mesh.cell * 1.5f) return
    s.pushStep()
    // Where that spot was before warping (found in the warped cell holding it)
    val from = s.mesh.restOf(x, y) ?: run {
        val k = (j * s.mesh.stride + i) * 2
        PointF(s.mesh.rest[k] + (x - s.mesh.pts[k]), s.mesh.rest[k + 1] + (y - s.mesh.pts[k + 1]))
    }
    s.pinFrom.add(from)
    s.pinTo.add(PointF(x, y))
    s.pinSelected = s.pinTo.lastIndex
    updatePuppet(s)
}

fun EditorViewModel.deletePin() {
    val s = warp ?: return
    val i = s.pinSelected.takeIf { it in s.pinTo.indices } ?: return
    s.pushStep()
    s.pinFrom.removeAt(i)
    s.pinTo.removeAt(i)
    s.pinSelected = s.pinTo.lastIndex
    updatePuppet(s)
}

/** Stiffness slider moving. Call [beginPuppetStiffness] when it starts. */
fun EditorViewModel.setPuppetStiffness(v: Float) {
    val s = warp ?: return
    s.stiffness = v
    updatePuppet(s)
}

fun EditorViewModel.beginPuppetStiffness() {
    warp?.pushStep()
}

private fun nearestPin(s: WarpSession, x: Float, y: Float, reach: Float): Int {
    var best = -1
    var bd = reach
    s.pinTo.forEachIndexed { i, p ->
        val dd = hypot(p.x - x, p.y - y)
        if (dd < bd) {
            bd = dd
            best = i
        }
    }
    return best
}

private fun EditorViewModel.updatePuppet(s: WarpSession) {
    val from = FloatArray(s.pinFrom.size * 2) { if (it % 2 == 0) s.pinFrom[it / 2].x else s.pinFrom[it / 2].y }
    val to = FloatArray(s.pinTo.size * 2) { if (it % 2 == 0) s.pinTo[it / 2].x else s.pinTo[it / 2].y }
    WarpMath.puppet(s.mesh, from, to, s.stiffness)
    showWarp(s)
}

// ===================================================================== Warp's ready-made shapes

/** Picks a ready-made shape (Custom keeps what you've made). */
fun EditorViewModel.setWarpPreset(p: WarpPreset) {
    val s = warp ?: return
    if (s.preset == p) return
    // Custom just keeps the shape there is: nothing to undo
    if (p != WarpPreset.CUSTOM) s.pushStep()
    s.preset = p
    if (p != WarpPreset.CUSTOM) {
        s.ctrl = WarpMath.preset(p, s.bend, s.mesh.area)
        WarpMath.applyPatch(s.ctrl, s.mesh)
    }
    showWarp(s)
}

/** Bend slider moving (−1..1). Call [beginWarpBend] when it starts. */
fun EditorViewModel.setWarpBend(b: Float) {
    val s = warp ?: return
    s.bend = b
    if (s.preset != WarpPreset.CUSTOM) {
        s.ctrl = WarpMath.preset(s.preset, b, s.mesh.area)
        WarpMath.applyPatch(s.ctrl, s.mesh)
        showWarp(s)
    } else {
        s.version++
    }
}

fun EditorViewModel.beginWarpBend() {
    warp?.pushStep()
}

// ===================================================================== Liquify settings

fun EditorViewModel.chooseLiquifyTool(t: LiquifyTool) {
    liquifyTool = t
    prefs.edit().putInt("liquifyTool", t.ordinal).apply()
}

fun EditorViewModel.saveLiquifyPrefs() {
    prefs.edit().putFloat("liquifySize", liquifySize).putFloat("liquifyStrength", liquifyStrength).apply()
}

// ===================================================================== face-aware Liquify

/** Looks for faces in the layer as it is now (on the device). */
fun EditorViewModel.findFaces() {
    val s = warp?.takeIf { it.kind == WarpKind.LIQUIFY } ?: return
    // Slider changes so far become part of the picture, so they aren't lost
    s.pushStep()
    s.bakeFaces()
    val pts = s.output()
    runBusy("Looking for faces…") {
        val found = withContext(Dispatchers.Default) {
            // The picture as it looks now, at the size it's shown while warping
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            var k = 0
            while (k < pts.size) {
                l = min(l, pts[k]); r = max(r, pts[k])
                t = min(t, pts[k + 1]); b = max(b, pts[k + 1])
                k += 2
            }
            val scale = s.proxy.width / s.src.width().toFloat()
            val w = ((r - l) * scale).roundToInt().coerceIn(1, 4096)
            val h = ((b - t) * scale).roundToInt().coerceIn(1, 4096)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                // Faces are easier to find on white than on see-through
                drawColor(android.graphics.Color.WHITE)
                scale(scale, scale)
                translate(-l, -t)
                WarpMath.draw(this, s.proxy, s.mesh.cols, s.mesh.rows, pts)
            }
            try {
                FaceAware.find(bmp, 1f / scale, l, t)
            } finally {
                bmp.recycle()
            }
        }
        if (warp !== s) return@runBusy
        s.faces = found
        s.faceValues = found.map { HashMap() }
        s.faceIndex = 0
        s.facesLooked = true
        showWarp(s)
        toast(
            when (found.size) {
                0 -> "No faces found"
                1 -> "Found a face"
                else -> "Found ${found.size} faces"
            }
        )
    }
}

/** A face slider moving (−1..1) for the face being changed. Call [beginFaceSlide] when it starts. */
fun EditorViewModel.setFaceValue(slider: FaceSlider, value: Float) {
    val s = warp ?: return
    val m = s.faceValues.getOrNull(s.faceIndex) ?: return
    m[slider] = value
    showWarp(s)
}

fun EditorViewModel.beginFaceSlide() {
    warp?.pushStep()
}

/** Back to how the face being changed was before its sliders moved. */
fun EditorViewModel.resetFace() {
    val s = warp ?: return
    val m = s.faceValues.getOrNull(s.faceIndex) ?: return
    if (m.values.all { it == 0f }) return
    s.pushStep()
    m.clear()
    showWarp(s)
}
