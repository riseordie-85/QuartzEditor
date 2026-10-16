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

    /** Earlier states, for undo within the warp. */
    internal val steps = ArrayDeque<Snap>()

    internal class Snap(
        val pts: FloatArray,
        val ctrl: FloatArray,
        val preset: WarpPreset,
        val bend: Float,
        val pinFrom: List<PointF>,
        val pinTo: List<PointF>,
    )

    internal fun snap() = Snap(mesh.pts.copyOf(), ctrl.copyOf(), preset, bend, pinFrom.map { PointF(it.x, it.y) }, pinTo.map { PointF(it.x, it.y) })

    internal fun restore(s: Snap) {
        s.pts.copyInto(mesh.pts)
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
        l.isVector -> {
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
        val s = WarpSession(kind, l, tiles, mask, source, src, proxy, mesh)
        warp = s
        showWarp(s)
        if (kind == WarpKind.PUPPET) toast("Tap to add pins, then drag them")
    }
}

internal fun EditorViewModel.showWarp(s: WarpSession) {
    s.version++
    preview = LayerPreview(s.layer.id, mesh = MeshPreview(s.proxy, s.mesh.cols, s.mesh.rows, s.mesh.pts.copyOf()))
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
    if (WarpMath.nearlyUnchanged(s.mesh)) {
        cancelWarp()
        onDone()
        return
    }
    val pts = s.mesh.pts.copyOf()
    val cols = s.mesh.cols
    val rows = s.mesh.rows
    runBusy("${s.kind.label}…", onDone) {
        val (grid, maskGrid) = withContext(Dispatchers.Default) {
            val limit = Transformer.limitFor(d.width, d.height)
            val g = WarpMath.render(s.tiles, false, s.src, pts, cols, rows, limit)
                ?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() } }
                ?: TileGrid(alphaOnly = false)
            val m = s.mask?.let { mk ->
                WarpMath.render(mk, true, s.src, pts, cols, rows, limit)
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
    if (WarpMath.nearlyUnchanged(s.mesh) && s.pinTo.isEmpty()) return
    s.pushStep()
    s.mesh.rest.copyInto(s.mesh.pts)
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
            warpCursor = PointF(x, y)
            val t = liquifyTool
            if (t.continuous) {
                liquifyJob?.cancel()
                liquifyJob = viewModelScope.launch {
                    while (isActive && warp === s) {
                        val c = warpCursor ?: break
                        // On the main thread, like every other change to the mesh (it's quick)
                        if (WarpMath.liquify(s.mesh, t, c.x, c.y, liquifySize / 2f, liquifyStrength)) showWarp(s)
                        delay(16)
                    }
                }
            }
            return true
        }
    }
}

/** The finger that started the gesture is at ([x], [y]) now. */
fun EditorViewModel.warpMove(x: Float, y: Float) {
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
            showWarp(s)
        }
        WarpKind.PUPPET -> {
            val p0 = s.dragStartPin ?: return
            val i = s.dragPin.takeIf { it in s.pinTo.indices } ?: return
            s.pinTo[i] = PointF(p0.x + dx, p0.y + dy)
            updatePuppet(s)
        }
        WarpKind.LIQUIFY -> {
            warpCursor = PointF(x, y)
            if (!liquifyTool.continuous) {
                if (WarpMath.push(s.mesh, s.lastX, s.lastY, x, y, liquifySize / 2f, liquifyStrength)) showWarp(s)
            }
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
    if (!moved && s.kind != WarpKind.LIQUIFY) s.steps.removeLastOrNull()
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
    // Where that spot was before warping: near enough, the nearest mesh point's start plus the same offset
    val k = (j * s.mesh.stride + i) * 2
    s.pinFrom.add(PointF(s.mesh.rest[k] + (x - s.mesh.pts[k]), s.mesh.rest[k + 1] + (y - s.mesh.pts[k + 1])))
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

fun EditorViewModel.setPuppetStiffness(v: Float) {
    val s = warp ?: return
    s.stiffness = v
    updatePuppet(s)
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
    s.pushStep()
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
