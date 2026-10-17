package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tim.quartzeditor.engine.DocRenderer
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private enum class GestureMode { PENDING, STROKE, BLOCKED, MOVE, PAN, NAV, EYEDROP, TRANSFORM, SELECTING, GRADIENT, CROP, SHAPING, PEN, PEN_EDIT, WARP, GUIDE, DONE }

/** What a finger grabbed on the transform box. */
private sealed class TransformHit {
    data class Corner(val i: Int) : TransformHit()
    data class Edge(val i: Int) : TransformHit()
    object Move : TransformHit()
    object Rotate : TransformHit()
}

/** Screen position of the rotate handle: beyond the middle of the top edge. */
private fun rotateHandle(q: List<Offset>, dist: Float): Offset {
    val mid = Offset((q[0].x + q[1].x) / 2f, (q[0].y + q[1].y) / 2f)
    val c = Offset((q[0].x + q[1].x + q[2].x + q[3].x) / 4f, (q[0].y + q[1].y + q[2].y + q[3].y) / 4f)
    val d = mid - c
    val len = d.getDistance().coerceAtLeast(0.001f)
    return mid + d / len * dist
}

private fun insideQuad(q: List<Offset>, p: Offset): Boolean {
    var inside = false
    var j = 3
    for (i in 0 until 4) {
        val a = q[i]
        val b = q[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** Workspace colors offered by a long press beside the canvas. */
private val CANVAS_COLORS = listOf(
    0xFF222222.toInt(), 0xFF111111.toInt(), 0xFF3A3A3A.toInt(), 0xFF5E5E5E.toInt(),
    0xFF9E9E9E.toInt(), 0xFFE0E0E0.toInt(), 0xFF1B2440.toInt(), 0xFF2D1F33.toInt(),
)

/**
 * The picture and everything you do on it with your fingers.
 *
 * One finger: the tool (paint, erase, move the layer, pick a color; with Pan, move the view).
 * Two fingers: move and pinch-zoom the view. A quick pinch inward fits the canvas to the screen.
 * Two-finger tap: undo. Three-finger tap: redo. Long press beside the canvas: workspace color.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditorCanvas(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var canvasMenuAt by remember { mutableStateOf<Offset?>(null) }
    var loupe by remember { mutableStateOf<Pair<Offset, Int?>?>(null) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    // A guide being moved with the Move tool: (vertical, which one)
    var movingGuide by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    val checker = remember { checkerShader(with(density) { 8.dp.toPx() }.roundToInt().coerceAtLeast(4)) }

    fun animateFit() {
        val d = vm.doc ?: return
        val fromS = vm.scale
        val fromX = vm.offsetX
        val fromY = vm.offsetY
        val toS = vm.fitScale()
        val toX = (vm.viewW - d.width * toS) / 2f
        val toY = (vm.viewH - d.height * toS) / 2f
        scope.launch {
            animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                vm.scale = fromS + (toS - fromS) * t
                vm.offsetX = fromX + (toX - fromX) * t
                vm.offsetY = fromY + (toY - fromY) * t
            }
            vm.fit()
        }
    }

    Box(modifier.onSizeChanged { vm.setViewSize(it.width.toFloat(), it.height.toFloat()) }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPress = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val t0 = down.uptimeMillis
                        val start = down.position
                        var mode = GestureMode.PENDING
                        var maxPointers = 1
                        var moved = false
                        var navTravel = 0f
                        var totalZoom = 1f
                        var lastPos = start
                        var longPressDone = false
                        var endTime = t0
                        // Space held on a keyboard pans with any tool
                        val tool = if (vm.tempPan) Tool.PAN else vm.tool

                        if (vm.doc == null) return@awaitEachGesture
                        vm.brushPickerFor = null
                        var hit: TransformHit? = null
                        val tStart = vm.transform?.state
                        val (startCx, startCy) = vm.screenToCanvas(start.x, start.y)
                        if (tool == Tool.TRANSFORM && tStart != null) {
                            val q = tStart.corners().map { Offset(it.x * vm.scale + vm.offsetX, it.y * vm.scale + vm.offsetY) }
                            val r = 30.dp.toPx()
                            hit = when {
                                !tStart.distort && (rotateHandle(q, 40.dp.toPx()) - start).getDistance() < r -> TransformHit.Rotate
                                q.indexOfFirst { (it - start).getDistance() < r } >= 0 -> TransformHit.Corner(q.indexOfFirst { (it - start).getDistance() < r })
                                (0 until 4).firstOrNull { i -> (Offset((q[i].x + q[(i + 1) % 4].x) / 2f, (q[i].y + q[(i + 1) % 4].y) / 2f) - start).getDistance() < r } != null ->
                                    TransformHit.Edge((0 until 4).first { i -> (Offset((q[i].x + q[(i + 1) % 4].x) / 2f, (q[i].y + q[(i + 1) % 4].y) / 2f) - start).getDistance() < r })
                                insideQuad(q, start) -> TransformHit.Move
                                else -> TransformHit.Rotate
                            }
                            mode = GestureMode.TRANSFORM
                        }
                        // Warp, Puppet Warp, Liquify: a handle, pin or brush under the finger
                        var warpMoved = false
                        if (tool == Tool.WARP && vm.warp != null && vm.warpDown(startCx, startCy, 26.dp.toPx() / vm.scale)) {
                            mode = GestureMode.WARP
                        }
                        var prevCx = startCx
                        var prevCy = startCy
                        // Crop box handles: 0..3 corners, 4..7 edges, -1 inside
                        var cropHandle = -2
                        val cropStart = vm.cropBox?.let { android.graphics.RectF(it) }
                        if (tool == Tool.CROP && cropStart != null) {
                            val r = 30.dp.toPx()
                            fun sc(x: Float, y: Float) = Offset(x * vm.scale + vm.offsetX, y * vm.scale + vm.offsetY)
                            val c = listOf(
                                sc(cropStart.left, cropStart.top), sc(cropStart.right, cropStart.top),
                                sc(cropStart.right, cropStart.bottom), sc(cropStart.left, cropStart.bottom),
                            )
                            val e = listOf(
                                sc(cropStart.centerX(), cropStart.top), sc(cropStart.right, cropStart.centerY()),
                                sc(cropStart.centerX(), cropStart.bottom), sc(cropStart.left, cropStart.centerY()),
                            )
                            val ci = c.indexOfFirst { (it - start).getDistance() < r }
                            val ei = e.indexOfFirst { (it - start).getDistance() < r }
                            cropHandle = when {
                                ci >= 0 -> ci
                                ei >= 0 -> 4 + ei
                                cropStart.contains(startCx, startCy) -> -1
                                else -> -2
                            }
                            if (cropHandle != -2) mode = GestureMode.CROP
                        }
                        // Pen point editing: a point or handle under the finger is dragged
                        var penHit: PenHit? = null
                        var penMoved = false
                        if (tool == Tool.PEN && vm.penEditTarget != null) {
                            penHit = vm.penHit(startCx, startCy, 24.dp.toPx() / vm.scale)
                            penHit?.let {
                                vm.penEditBegin(it)
                                mode = GestureMode.PEN_EDIT
                            }
                        }
                        // Move tool on a guide: drag it (off the picture removes it)
                        var guidePos = 0f
                        if (tool == Tool.MOVE && vm.showGuides && !vm.guidesLocked && mode == GestureMode.PENDING) {
                            val d0 = vm.doc
                            val r = 14.dp.toPx()
                            var best = r
                            // Only over the picture, where guides are drawn
                            if (d0 != null && vm.isOnCanvas(start.x, start.y)) {
                                d0.guidesX.forEachIndexed { i, gx ->
                                    val dd = abs(gx * vm.scale + vm.offsetX - start.x)
                                    if (dd < best) { best = dd; movingGuide = true to i; guidePos = gx }
                                }
                                d0.guidesY.forEachIndexed { i, gy ->
                                    val dd = abs(gy * vm.scale + vm.offsetY - start.y)
                                    if (dd < best) { best = dd; movingGuide = false to i; guidePos = gy }
                                }
                            }
                            movingGuide?.let { (v, _) ->
                                mode = GestureMode.GUIDE
                                vm.guideDraft = v to guidePos
                            }
                        }
                        // Lasso outline being drawn
                        val lasso = android.graphics.Path().apply { moveTo(startCx, startCy) }
                        // Where the stroke starts (Shift keeps it on a straight line from here)
                        var strokeOx = startCx
                        var strokeOy = startCy
                        // Shift: the straight line's direction, fixed once the finger has moved a little
                        var lineAngle: Double? = null
                        // Clone Stamp: Alt + tap (or Pick source, then a tap) sets where it copies from
                        if (tool == Tool.RETOUCH && vm.retouchKind == com.tim.quartzeditor.engine.RetouchKind.CLONE &&
                            (vm.altOn || vm.pickingCloneSource) && vm.isOnCanvas(start.x, start.y)
                        ) {
                            vm.setCloneSource(startCx, startCy)
                            mode = GestureMode.DONE
                        }
                        if (mode == GestureMode.PENDING) when (tool) {
                            Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> if (vm.isOnCanvas(start.x, start.y)) {
                                val (cx, cy) = vm.screenToCanvas(start.x, start.y)
                                // Shift and a tap: a straight line from where the last stroke ended (like Photoshop)
                                val join = vm.lastStrokeEnd?.takeIf { vm.shiftOn && it.first == vm.doc?.activeId }
                                val bx = join?.second ?: cx
                                val by = join?.third ?: cy
                                // Can't paint here: wait and see whether it becomes a pinch or stays a touch
                                mode = if (vm.strokeBlockReason() == null && vm.beginStroke(bx, by)) GestureMode.STROKE else GestureMode.BLOCKED
                                if (mode == GestureMode.STROKE) {
                                    cursor = start
                                    if (join != null) vm.strokeTo(cx, cy)
                                    // A drag after the tap stays straight from where the finger is
                                    strokeOx = cx
                                    strokeOy = cy
                                }
                            }
                            Tool.EYEDROPPER -> if (vm.isOnCanvas(start.x, start.y)) {
                                val (cx, cy) = vm.screenToCanvas(start.x, start.y)
                                loupe = start to vm.sampleAt(cx, cy, commit = false)
                                mode = GestureMode.EYEDROP
                            }
                            else -> Unit
                        }
                        down.consume()

                        while (true) {
                            val waitLong = mode == GestureMode.PENDING && !moved && !longPressDone
                            val ev: PointerEvent? = if (waitLong) {
                                val left = longPress - (SystemClock.uptimeMillis() - t0)
                                withTimeoutOrNull(max(1L, left)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (ev == null) {
                                // Long press without moving
                                longPressDone = true
                                if (!vm.isOnCanvas(start.x, start.y)) {
                                    canvasMenuAt = start
                                    mode = GestureMode.DONE
                                }
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            maxPointers = max(maxPointers, pressed.size)
                            if (pressed.isEmpty()) {
                                endTime = ev.changes.first().uptimeMillis
                                ev.changes.forEach { it.consume() }
                                break
                            }
                            val now = ev.changes.first().uptimeMillis
                            // The finger that started the gesture (null once it's lifted)
                            val main = ev.changes.firstOrNull { it.id == down.id && it.pressed }

                            // A second finger turns most things into view navigation
                            if (pressed.size >= 2 && mode != GestureMode.NAV && mode != GestureMode.DONE) {
                                when (mode) {
                                    GestureMode.STROKE -> if (now - t0 < 260) {
                                        vm.cancelStroke()
                                        cursor = null
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.MOVE -> {
                                        vm.cancelMove()
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.EYEDROP -> {
                                        loupe = null
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.TRANSFORM -> {
                                        if (tStart != null) vm.updateTransform { tStart.copy(keepAspect = it.keepAspect) }
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.SELECTING -> {
                                        vm.selectDraft = null
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.GRADIENT -> {
                                        vm.cancelGradient()
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.CROP -> {
                                        if (cropStart != null) vm.cropBox = cropStart
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.SHAPING -> {
                                        vm.cancelShape()
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.PEN -> {
                                        vm.penPreview(null)
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.PEN_EDIT -> {
                                        vm.penEditCancel()
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.WARP -> {
                                        vm.warpCancelGesture()
                                        mode = GestureMode.NAV
                                    }
                                    GestureMode.GUIDE -> {
                                        vm.guideDraft = null
                                        movingGuide = null
                                        mode = GestureMode.NAV
                                    }
                                    else -> mode = GestureMode.NAV
                                }
                            }

                            when (mode) {
                                GestureMode.NAV -> {
                                    val zoom = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    val c = ev.calculateCentroid(useCurrent = true)
                                    if (c != Offset.Unspecified) vm.transformView(zoom, c.x, c.y, pan.x, pan.y)
                                    totalZoom *= zoom
                                    navTravel += pan.getDistance()
                                    if (navTravel > slop || abs(totalZoom - 1f) > 0.06f) moved = true
                                }
                                GestureMode.STROKE -> {
                                    // Only the finger that started the stroke paints
                                    if (main == null) {
                                        vm.endStroke()
                                        cursor = null
                                        mode = GestureMode.DONE
                                    } else if (vm.shiftOn) {
                                        // Straight: along 0°, 45° or 90° from where it started
                                        val (x, y) = vm.screenToCanvas(main.position.x, main.position.y)
                                        if (lineAngle == null && kotlin.math.hypot(x - strokeOx, y - strokeOy) * vm.scale > slop) {
                                            lineAngle = Math.round(kotlin.math.atan2((y - strokeOy).toDouble(), (x - strokeOx).toDouble()) / (Math.PI / 4)) * (Math.PI / 4)
                                        }
                                        val a = lineAngle
                                        if (a != null) {
                                            val ux = kotlin.math.cos(a).toFloat()
                                            val uy = kotlin.math.sin(a).toFloat()
                                            val len = (x - strokeOx) * ux + (y - strokeOy) * uy
                                            vm.strokeTo(strokeOx + ux * len, strokeOy + uy * len)
                                        }
                                        cursor = main.position
                                    } else {
                                        for (h in main.historical) {
                                            val (x, y) = vm.screenToCanvas(h.position.x, h.position.y)
                                            vm.strokeTo(x, y)
                                        }
                                        val (x, y) = vm.screenToCanvas(main.position.x, main.position.y)
                                        vm.strokeTo(x, y)
                                        cursor = main.position
                                    }
                                }
                                GestureMode.MOVE -> if (main != null) {
                                    val d = axisLock(vm, main.position - start)
                                    vm.moveTo(d.x / vm.scale, d.y / vm.scale, 10.dp.toPx() / vm.scale, lockedAxis(vm, main.position - start))
                                }
                                GestureMode.PAN -> {
                                    val d = pressed.first().positionChange()
                                    vm.transformView(1f, 0f, 0f, d.x, d.y)
                                }
                                GestureMode.EYEDROP -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    val c = vm.sampleAt(cx, cy, commit = false)
                                    loupe = main.position to (c ?: loupe?.second)
                                    lastPos = main.position
                                }
                                GestureMode.PENDING -> if (main != null && (main.position - start).getDistance() > slop) {
                                    moved = true
                                    mode = when (tool) {
                                        Tool.MOVE -> {
                                            // Alt: move a copy, leaving the original where it was
                                            if (vm.altOn && !vm.isMultiSelecting) vm.duplicateLayer()
                                            if (vm.beginMove()) GestureMode.MOVE else GestureMode.PAN
                                        }
                                        Tool.PAN, Tool.EYEDROPPER, Tool.TRANSFORM, Tool.BUCKET, Tool.CROP, Tool.WARP -> GestureMode.PAN
                                        // Polygonal lasso and magic wand work by taps: a drag moves the view
                                        Tool.SELECT -> if (vm.selectShape == com.tim.quartzeditor.engine.SelectShape.POLYGON || vm.selectShape == com.tim.quartzeditor.engine.SelectShape.WAND) GestureMode.PAN else GestureMode.SELECTING
                                        Tool.GRADIENT -> if (vm.beginGradient(startCx, startCy)) GestureMode.GRADIENT else GestureMode.DONE
                                        Tool.SHAPE -> GestureMode.SHAPING
                                        // Dragging while placing a point pulls out a curve handle
                                        Tool.PEN -> if (vm.penEditTarget != null) GestureMode.PAN else GestureMode.PEN
                                        // With the Text tool, dragging moves the text layer you're on
                                        Tool.TEXT -> if (vm.doc?.active?.isText == true && vm.beginMove()) GestureMode.MOVE else GestureMode.PAN
                                        Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> {
                                            // Started beside the canvas: paint from there
                                            val (sx, sy) = vm.screenToCanvas(start.x, start.y)
                                            if (vm.strokeBlockReason() != null) {
                                                GestureMode.BLOCKED
                                            } else if (vm.beginStroke(sx, sy)) {
                                                strokeOx = sx
                                                strokeOy = sy
                                                val (x, y) = vm.screenToCanvas(main.position.x, main.position.y)
                                                vm.strokeTo(x, y)
                                                cursor = main.position
                                                GestureMode.STROKE
                                            } else {
                                                GestureMode.DONE
                                            }
                                        }
                                    }
                                    if (mode == GestureMode.MOVE) {
                                        val d = axisLock(vm, main.position - start)
                                        vm.moveTo(d.x / vm.scale, d.y / vm.scale, 10.dp.toPx() / vm.scale, lockedAxis(vm, main.position - start))
                                    }
                                    if (mode == GestureMode.SHAPING || mode == GestureMode.PEN) {
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        if (mode == GestureMode.SHAPING) dragShapeSnapped(vm, startCx, startCy, cx, cy, 10.dp.toPx() / vm.scale)
                                        else vm.snapPoint(startCx, startCy, 10.dp.toPx() / vm.scale).let { (px, py) ->
                                            vm.penPreview(com.tim.quartzeditor.engine.PenPoint(px, py, cx - px, cy - py))
                                        }
                                    }
                                }
                                GestureMode.TRANSFORM -> if (main != null && tStart != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    val p = android.graphics.PointF(cx, cy)
                                    // Handles move by how far the finger moved (no jump to the finger)
                                    val ddx = cx - startCx
                                    val ddy = cy - startCy
                                    val q0 = tStart.corners()
                                    when (val h = hit) {
                                        is TransformHit.Corner -> vm.updateTransform { cur ->
                                            // Shift flips keep-proportions just for this drag (the setting stays as it is)
                                            val ka = cur.keepAspect
                                            // Alt scales from the middle: the opposite corner moves the other way
                                            val k = if (vm.altOn) 2f else 1f
                                            val target = android.graphics.PointF(q0[h.i].x + ddx * k, q0[h.i].y + ddy * k)
                                            // Skew / perspective / distort work from where the gesture started (a move that
                                            // would fold the box over leaves it where it last was)
                                            if (tStart.distort) tStart.dragCornerBy(h.i, ddx, ddy) ?: cur
                                            else centered(vm, tStart, tStart.copy(keepAspect = ka != vm.shiftOn).dragCorner(h.i, target)).copy(keepAspect = ka)
                                        }
                                        is TransformHit.Edge -> vm.updateTransform { cur ->
                                            if (tStart.distort) {
                                                tStart.dragEdgeBy(h.i, ddx, ddy) ?: cur
                                            } else {
                                                val a = q0[h.i]
                                                val b = q0[(h.i + 1) % 4]
                                                val k = if (vm.altOn) 2f else 1f
                                                val target = android.graphics.PointF((a.x + b.x) / 2f + ddx * k, (a.y + b.y) / 2f + ddy * k)
                                                centered(vm, tStart, tStart.copy(keepAspect = cur.keepAspect != vm.shiftOn).dragEdge(h.i, target, target)).copy(keepAspect = cur.keepAspect)
                                            }
                                        }
                                        TransformHit.Move -> {
                                            // Snaps the box's edges and middle to the canvas's
                                            val box = android.graphics.RectF(q0.minOf { it.x }, q0.minOf { it.y }, q0.maxOf { it.x }, q0.maxOf { it.y })
                                            val locked = axisLock(vm, Offset(ddx, ddy))
                                            val (sx, sy) = vm.snapDelta(box, locked.x, locked.y, 10.dp.toPx() / vm.scale, lockedAxis(vm, Offset(ddx, ddy)))
                                            vm.updateTransform { cur ->
                                                // Whole pixels, so a plain move stays sharp
                                                tStart.copy(keepAspect = cur.keepAspect).moved(sx.roundToInt().toFloat(), sy.roundToInt().toFloat())
                                            }
                                        }
                                        TransformHit.Rotate -> {
                                            val c = tStart.center
                                            val a0 = Math.toDegrees(kotlin.math.atan2((startCy - c.y).toDouble(), (startCx - c.x).toDouble()))
                                            val a1 = Math.toDegrees(kotlin.math.atan2((cy - c.y).toDouble(), (cx - c.x).toDouble()))
                                            var deg = (a1 - a0).toFloat()
                                            if (!tStart.distort) {
                                                // Snap to straight angles (Shift: every 15°)
                                                val target = tStart.angle + deg
                                                if (vm.shiftOn) {
                                                    deg = Math.round(target / 15f) * 15f - tStart.angle
                                                } else {
                                                    val nearest = Math.round(target / 90f) * 90f
                                                    if (abs(target - nearest) < 2.5f) deg = nearest - tStart.angle
                                                }
                                            }
                                            vm.updateTransform { cur -> tStart.copy(keepAspect = cur.keepAspect).rotated(deg) }
                                        }
                                        null -> Unit
                                    }
                                    prevCx = cx
                                    prevCy = cy
                                }
                                GestureMode.SELECTING -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    // Shift: a square or circle; Alt: drawn from the middle
                                    val r = if (vm.selectShape == com.tim.quartzeditor.engine.SelectShape.OBJECT) android.graphics.RectF(minOf(startCx, cx), minOf(startCy, cy), maxOf(startCx, cx), maxOf(startCy, cy))
                                    else constrainedBox(vm, startCx, startCy, cx, cy)
                                    vm.selectDraft = when (vm.selectShape) {
                                        com.tim.quartzeditor.engine.SelectShape.RECT, com.tim.quartzeditor.engine.SelectShape.OBJECT -> com.tim.quartzeditor.engine.Selection.rect(r)
                                        com.tim.quartzeditor.engine.SelectShape.ELLIPSE -> com.tim.quartzeditor.engine.Selection.oval(r)
                                        else -> {
                                            for (h in main.historical) {
                                                val (hx, hy) = vm.screenToCanvas(h.position.x, h.position.y)
                                                lasso.lineTo(hx, hy)
                                            }
                                            lasso.lineTo(cx, cy)
                                            android.graphics.Path(lasso)
                                        }
                                    }
                                }
                                GestureMode.GRADIENT -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    // Shift: straight across, down or at 45°
                                    val (gx, gy) = if (vm.shiftOn) snap45(startCx, startCy, cx, cy) else cx to cy
                                    vm.gradientTo(gx, gy)
                                }
                                GestureMode.CROP -> if (main != null && cropStart != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    vm.dragCrop(cropStart, cropHandle, cx - startCx, cy - startCy, keepShape = vm.shiftOn, fromCenter = vm.altOn)
                                }
                                GestureMode.SHAPING -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    dragShapeSnapped(vm, startCx, startCy, cx, cy, 10.dp.toPx() / vm.scale)
                                }
                                GestureMode.PEN -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    val (px, py) = vm.snapPoint(startCx, startCy, 10.dp.toPx() / vm.scale)
                                    vm.penPreview(com.tim.quartzeditor.engine.PenPoint(px, py, cx - px, cy - py))
                                }
                                GestureMode.PEN_EDIT -> if (main != null && penHit != null) {
                                    if (penMoved || (main.position - start).getDistance() > slop) {
                                        penMoved = true
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        vm.penEditDrag(penHit, cx, cy, startCx, startCy, 10.dp.toPx() / vm.scale)
                                    }
                                }
                                GestureMode.WARP -> if (main != null) {
                                    if (!warpMoved && (main.position - start).getDistance() > slop) warpMoved = true
                                    if (warpMoved) {
                                        // Every in-between point, so Liquify strokes stay smooth
                                        for (h in main.historical) {
                                            val (hx, hy) = vm.screenToCanvas(h.position.x, h.position.y)
                                            vm.warpMove(hx, hy, show = false)
                                        }
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        vm.warpMove(cx, cy)
                                    }
                                }
                                GestureMode.GUIDE -> if (main != null) {
                                    val g = movingGuide
                                    if (g != null) {
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        var v = if (g.first) cx else cy
                                        val d0 = vm.doc
                                        if (d0 != null && vm.snapOn) {
                                            val size = if (g.first) d0.width.toFloat() else d0.height.toFloat()
                                            val r = 10.dp.toPx() / vm.scale
                                            for (t in listOf(0f, size / 2f, size)) if (abs(v - t) <= r) v = t
                                        }
                                        guidePos = v.roundToInt().toFloat()
                                        vm.guideDraft = g.first to guidePos
                                    }
                                }
                                GestureMode.BLOCKED, GestureMode.DONE -> Unit
                            }
                            ev.changes.forEach { it.consume() }
                        }

                        // All fingers up
                        val duration = endTime - t0
                        cursor = null
                        vm.clearSnapGuides()
                        when (mode) {
                            GestureMode.STROKE -> vm.endStroke()
                            GestureMode.BLOCKED -> vm.onBlockedStroke()
                            GestureMode.MOVE -> vm.endMove()
                            GestureMode.EYEDROP -> {
                                val (cx, cy) = vm.screenToCanvas(lastPos.x, lastPos.y)
                                vm.sampleAt(cx, cy, commit = true)
                                loupe = null
                            }
                            GestureMode.PENDING -> if (!longPressDone) {
                                when (tool) {
                                    Tool.MOVE -> if (vm.isOnCanvas(start.x, start.y)) vm.selectLayerAt(startCx, startCy)
                                    Tool.TRANSFORM -> if (vm.transform == null) vm.beginTransform()
                                    Tool.WARP -> if (vm.warp == null) vm.beginWarp() else vm.warpTap(startCx, startCy, 26.dp.toPx() / vm.scale)
                                    Tool.BUCKET -> if (vm.isOnCanvas(start.x, start.y)) vm.bucketFill(startCx, startCy)
                                    Tool.SELECT -> when (vm.selectShape) {
                                        com.tim.quartzeditor.engine.SelectShape.POLYGON -> vm.polygonTap(startCx, startCy, 24.dp.toPx() / vm.scale)
                                        com.tim.quartzeditor.engine.SelectShape.WAND -> withSelectMode(vm) { vm.magicWand(startCx, startCy) }
                                        // Object: tap one and the AI outlines it
                                        com.tim.quartzeditor.engine.SelectShape.OBJECT -> if (vm.isOnCanvas(start.x, start.y)) withSelectMode(vm) { vm.selectObjectAt(startCx, startCy) }
                                        else -> vm.selectTap()
                                    }
                                    // A tap picks the shape under your finger (to change it in Properties)
                                    Tool.SHAPE -> if (vm.isOnCanvas(start.x, start.y)) vm.selectLayerAt(startCx, startCy)
                                    Tool.PEN -> if (vm.penEditTarget != null) {
                                        vm.penEditTap(startCx, startCy, 24.dp.toPx() / vm.scale)
                                    } else {
                                        val (px, py) = vm.snapPoint(startCx, startCy, 10.dp.toPx() / vm.scale)
                                        vm.penAdd(com.tim.quartzeditor.engine.PenPoint(px, py), 24.dp.toPx() / vm.scale)
                                    }
                                    Tool.TEXT -> vm.textTap(startCx, startCy)
                                    else -> Unit
                                }
                            }
                            GestureMode.SELECTING -> {
                                val draft = vm.selectDraft
                                if (draft != null && vm.selectShape == com.tim.quartzeditor.engine.SelectShape.OBJECT) {
                                    // A box around the object: the AI outlines what's in it
                                    val b = android.graphics.RectF().also { draft.computeBounds(it, true) }
                                    vm.selectDraft = null
                                    withSelectMode(vm) { vm.selectObjectIn(b) }
                                } else if (draft != null) {
                                    if (vm.selectShape == com.tim.quartzeditor.engine.SelectShape.LASSO) {
                                        draft.close()
                                        // Shift adds the lasso to the selection, Alt takes it away
                                        withSelectMode(vm) { vm.commitSelectionShape(draft) }
                                    } else {
                                        vm.commitSelectionShape(draft)
                                    }
                                }
                                vm.selectDraft = null
                            }
                            GestureMode.GRADIENT -> vm.endGradient()
                            GestureMode.WARP -> vm.warpUp(warpMoved)
                            GestureMode.GUIDE -> {
                                val g = movingGuide
                                vm.guideDraft = null
                                movingGuide = null
                                if (g != null) vm.moveGuide(g.first, g.second, guidePos)
                            }
                            GestureMode.SHAPING -> vm.finishShape()
                            GestureMode.PEN -> vm.penDrag?.let { vm.penAdd(it, 24.dp.toPx() / vm.scale) }
                            GestureMode.PEN_EDIT -> {
                                vm.penEditEnd(penMoved)
                                // A tap on a point picks it
                                if (!penMoved) penHit?.let { vm.penSelected = it.index }
                            }
                            GestureMode.NAV -> when {
                                !moved && duration < 350 && maxPointers == 2 -> vm.undo()
                                !moved && duration < 350 && maxPointers >= 3 -> vm.redo()
                                duration < 450 && totalZoom < 0.72f -> animateFit()
                            }
                            else -> Unit
                        }
                    }
                }
        ) {
            val d = vm.doc ?: return@Canvas
            // Read so the canvas redraws when the picture changes
            @Suppress("UNUSED_VARIABLE") val v = d.renderVersion
            val s = vm.scale
            val ox = vm.offsetX
            val oy = vm.offsetY
            drawRect(Color(vm.canvasColor))
            val left = ox
            val top = oy
            val right = ox + d.width * s
            val bottom = oy + d.height * s
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                // Soft edge so the canvas stands out from the workspace
                val shadow = Paint().apply { color = android.graphics.Color.argb(90, 0, 0, 0) }
                nc.drawRect(left + 3f, top + 3f, right + 3f, bottom + 3f, shadow)
                if (vm.showChecker) {
                    checker.setLocalMatrix(Matrix().apply { setTranslate(left, top) })
                    nc.drawRect(left, top, right, bottom, Paint().apply { shader = checker })
                } else {
                    nc.drawRect(left, top, right, bottom, Paint().apply { color = android.graphics.Color.WHITE })
                }
                nc.save()
                nc.translate(ox, oy)
                nc.scale(s, s)
                val visible = RectF(-ox / s, -oy / s, (size.width - ox) / s, (size.height - oy) / s)
                DocRenderer.draw(
                    nc, d, visible, vm.stroke, vm.preview, smooth = s < 2f, effects = vm.effectsLookup,
                    view = Matrix().apply { setScale(s, s); postTranslate(ox, oy) },
                    viewW = size.width.roundToInt(), viewH = size.height.roundToInt(),
                )
                nc.restore()
            }
            // Alignment grid
            drawGrid(vm, s, ox, oy, d.width, d.height)
            // Guides
            @Suppress("UNUSED_VARIABLE") val gv = d.guidesX.size + d.guidesY.size
            drawGuides(vm, s, ox, oy, d.width, d.height, movingGuide)

            // Crop box: dim what's cut off, thirds, handles
            vm.cropBox?.let { b ->
                val l = b.left * s + ox
                val t = b.top * s + oy
                val r = b.right * s + ox
                val bt = b.bottom * s + oy
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, t.coerceAtLeast(0f)))
                drawRect(dim, Offset(0f, bt), androidx.compose.ui.geometry.Size(size.width, (size.height - bt).coerceAtLeast(0f)))
                drawRect(dim, Offset(0f, t), androidx.compose.ui.geometry.Size(l.coerceAtLeast(0f), (bt - t).coerceAtLeast(0f)))
                drawRect(dim, Offset(r, t), androidx.compose.ui.geometry.Size((size.width - r).coerceAtLeast(0f), (bt - t).coerceAtLeast(0f)))
                val line = 1.dp.toPx()
                for (k in 1..2) {
                    val x = l + (r - l) * k / 3f
                    val y = t + (bt - t) * k / 3f
                    drawLine(Color.White.copy(alpha = 0.5f), Offset(x, t), Offset(x, bt), line)
                    drawLine(Color.White.copy(alpha = 0.5f), Offset(l, y), Offset(r, y), line)
                }
                drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, bt - t), style = Stroke(line * 1.5f))
                val hl = 18.dp.toPx()
                val hw = 4.dp.toPx()
                for ((cx, cy) in listOf(l to t, r to t, r to bt, l to bt)) {
                    val sx = if (cx == l) 1f else -1f
                    val sy = if (cy == t) 1f else -1f
                    drawLine(Color.White, Offset(cx, cy), Offset(cx + sx * hl, cy), hw)
                    drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + sy * hl), hw)
                }
                for (m in listOf(Offset((l + r) / 2f, t), Offset((l + r) / 2f, bt))) drawLine(Color.White, m - Offset(hl / 2f, 0f), m + Offset(hl / 2f, 0f), hw)
                for (m in listOf(Offset(l, (t + bt) / 2f), Offset(r, (t + bt) / 2f))) drawLine(Color.White, m - Offset(0f, hl / 2f), m + Offset(0f, hl / 2f), hw)
            }

            // Transform box
            vm.transform?.let { t ->
                val st = t.state
                val q = st.corners().map { Offset(it.x * s + ox, it.y * s + oy) }
                val line = 1.5.dp.toPx()
                for (i in 0 until 4) {
                    drawLine(Color.Black.copy(alpha = 0.6f), q[i], q[(i + 1) % 4], line * 2.2f)
                    drawLine(Color.White, q[i], q[(i + 1) % 4], line)
                }
                val hs = 7.dp.toPx()
                if (!st.distort) {
                    val rh = rotateHandle(q, 40.dp.toPx())
                    val mid = Offset((q[0].x + q[1].x) / 2f, (q[0].y + q[1].y) / 2f)
                    drawLine(Color.White, mid, rh, line)
                    drawCircle(Color.White, hs, rh)
                    drawCircle(Q.accent, hs - line, rh)
                }
                for (i in 0 until 4) {
                    val m = Offset((q[i].x + q[(i + 1) % 4].x) / 2f, (q[i].y + q[(i + 1) % 4].y) / 2f)
                    drawCircle(Color.Black.copy(alpha = 0.6f), hs * 0.8f + line, m)
                    drawCircle(Color.White, hs * 0.8f, m)
                }
                for (p in q) {
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(p.x - hs - line, p.y - hs - line), androidx.compose.ui.geometry.Size(2 * (hs + line), 2 * (hs + line)))
                    drawRect(Color.White, Offset(p.x - hs, p.y - hs), androidx.compose.ui.geometry.Size(2 * hs, 2 * hs))
                }
            }

            // Warp grid, puppet pins, Liquify brush
            vm.warp?.let { w -> drawWarpOverlay(vm, w, s, ox, oy) }

            // Clone Stamp: where it copies from
            if (vm.tool == Tool.RETOUCH && vm.retouchKind == com.tim.quartzeditor.engine.RetouchKind.CLONE) {
                val at = vm.retouch?.at
                val off = vm.cloneOffset
                val p = if (at != null && off != null) android.graphics.PointF(at.x + off.x, at.y + off.y) else vm.cloneSource
                if (p != null) {
                    val c = Offset(p.x * s + ox, p.y * s + oy)
                    val r = (vm.retouchBrush.size / 2f * s).coerceAtLeast(6.dp.toPx())
                    drawCircle(Color.Black.copy(alpha = 0.6f), r, c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, r, c, style = Stroke(1.5.dp.toPx()))
                    val k = 7.dp.toPx()
                    drawLine(Color.White, c - Offset(k, 0f), c + Offset(k, 0f), 1.5.dp.toPx())
                    drawLine(Color.White, c - Offset(0f, k), c + Offset(0f, k), 1.5.dp.toPx())
                }
            }

            // Snap lines
            if (vm.snapGuidesX.isNotEmpty() || vm.snapGuidesY.isNotEmpty()) {
                val guide = Color(0xFFFF2DAA)
                val w = 1.5.dp.toPx()
                for (x in vm.snapGuidesX) {
                    val sx = x * s + ox
                    drawLine(guide, Offset(sx, top), Offset(sx, bottom), w)
                }
                for (y in vm.snapGuidesY) {
                    val sy = y * s + oy
                    drawLine(guide, Offset(left, sy), Offset(right, sy), w)
                }
            }

            // Shape being dragged out
            vm.shapeDraft?.let { spec ->
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(ox, oy)
                    nc.scale(s, s)
                    com.tim.quartzeditor.engine.VectorArt.drawShape(nc, spec)
                    nc.restore()
                }
            }

            // Shape pen: the outline so far, its points and curve handles
            if (vm.tool == Tool.PEN && (vm.penPoints.isNotEmpty() || vm.penDrag != null)) {
                val pts = vm.penPoints.toList() + listOfNotNull(vm.penDrag)
                fun sp(x: Float, y: Float) = Offset(x * s + ox, y * s + oy)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val path = com.tim.quartzeditor.engine.ShapeSpec.penPath(pts, closed = false)
                    path.transform(Matrix().apply { setScale(s, s); postTranslate(ox, oy) })
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3.dp.toPx()
                        color = android.graphics.Color.argb(150, 0, 0, 0)
                    }
                    nc.drawPath(path, p)
                    p.strokeWidth = 1.5.dp.toPx()
                    p.color = android.graphics.Color.WHITE
                    nc.drawPath(path, p)
                }
                val hr = 4.dp.toPx()
                pts.forEachIndexed { i, pt ->
                    val o = sp(pt.x, pt.y)
                    if (pt.smooth) {
                        val a = sp(pt.x + pt.ix, pt.y + pt.iy)
                        val b = sp(pt.x + pt.hx, pt.y + pt.hy)
                        drawLine(Q.accent, a, b, 1.5.dp.toPx())
                        drawCircle(Q.accent, hr * 0.8f, a)
                        drawCircle(Q.accent, hr * 0.8f, b)
                    }
                    // The first point is bigger: tap it to close the shape
                    val r = if (i == 0) hr * 1.6f else hr
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(o.x - r - 1.5f, o.y - r - 1.5f), androidx.compose.ui.geometry.Size(2 * r + 3f, 2 * r + 3f))
                    drawRect(Color.White, Offset(o.x - r, o.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                }
            }

            // Pen point editing: the outline, its points and curve handles
            vm.penEditTarget?.let { l ->
                val lay = vm.penLayout(l) ?: return@let
                fun sp(p: android.graphics.PointF) = Offset(p.x * s + ox, p.y * s + oy)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val path = com.tim.quartzeditor.engine.VectorArt.shapeOutline(l.props.shape!!)
                    path.transform(Matrix().apply { setTranslate(l.x.toFloat(), l.y.toFloat()); postScale(s, s); postTranslate(ox, oy) })
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3.dp.toPx()
                        color = android.graphics.Color.argb(150, 0, 0, 0)
                    }
                    nc.drawPath(path, p)
                    p.strokeWidth = 1.5.dp.toPx()
                    p.color = Q.accent.toArgb()
                    nc.drawPath(path, p)
                }
                val hr = 4.dp.toPx()
                lay.anchors.forEachIndexed { i, a ->
                    val o = sp(a)
                    for (h in listOfNotNull(lay.outs[i], lay.ins[i])) {
                        val ho = sp(h)
                        drawLine(Q.accent, o, ho, 1.5.dp.toPx())
                        drawCircle(Color.White, hr + 1.5f, ho)
                        drawCircle(Q.accent, hr, ho)
                    }
                }
                lay.anchors.forEachIndexed { i, a ->
                    val o = sp(a)
                    val sel = i == vm.penSelected
                    val r = if (sel) hr * 1.6f else hr * 1.3f
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(o.x - r - 1.5f, o.y - r - 1.5f), androidx.compose.ui.geometry.Size(2 * r + 3f, 2 * r + 3f))
                    drawRect(if (sel) Q.accent else Color.White, Offset(o.x - r, o.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r))
                }
            }

            // Text being typed: a box around it
            vm.editingText?.let { l ->
                com.tim.quartzeditor.engine.VectorArt.bounds(l.props)?.let { b ->
                    b.offset(l.x.toFloat(), l.y.toFloat())
                    val tl = Offset(b.left * s + ox, b.top * s + oy)
                    val sz = androidx.compose.ui.geometry.Size(b.width() * s, b.height() * s)
                    drawRect(Color.Black.copy(alpha = 0.5f), tl, sz, style = Stroke(3.dp.toPx()))
                    drawRect(Q.accent, tl, sz, style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                }
            }

            // Brush preview while a brush slider moves
            if (vm.brushPreview) {
                val b = vm.currentBrush
                val px = (b.size * s).coerceAtLeast(2f)
                val center = Offset(size.width / 2f, size.height / 2f)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val dab = com.tim.quartzeditor.engine.Dabs.get(b.tip, b.size.roundToInt().coerceAtLeast(1), b.softness)
                    val col = if (vm.tool == Tool.ERASER) android.graphics.Color.WHITE else vm.foreground
                    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        color = col or 0xFF000000.toInt()
                        alpha = (b.opacity * 255).roundToInt()
                    }
                    nc.drawBitmap(dab, null, RectF(center.x - px / 2f, center.y - px / 2f, center.x + px / 2f, center.y + px / 2f), p)
                }
                drawCircle(Color.Black.copy(alpha = 0.5f), px / 2f + 1.5f, center, style = Stroke(1.5f))
                drawCircle(Color.White.copy(alpha = 0.8f), px / 2f, center, style = Stroke(1f))
            }

            // Brush outline while painting
            cursor?.let { p ->
                val r = vm.currentBrush.size * s / 2f
                if (r > 3f) {
                    drawCircle(Color.Black.copy(alpha = 0.5f), r + 1f, p, style = Stroke(1.5f))
                    drawCircle(Color.White.copy(alpha = 0.8f), r, p, style = Stroke(1f))
                }
            }
        }

        // Selection outline ("marching ants"); its own layer so only it animates
        val d0 = vm.doc
        val sel = d0?.selection
        val draft = vm.selectDraft
        if (sel != null || draft != null) {
            MarchingAnts(vm, sel?.path, draft, if (vm.tool == Tool.SELECT) vm.polygonPoints.toList() else emptyList())
        }

        // Eyedropper loupe
        loupe?.let { (p, c) ->
            val sizePx = with(density) { 64.dp.toPx() }
            val lift = with(density) { 90.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((p.x - sizePx / 2).roundToInt(), (p.y - lift - sizePx / 2).coerceAtLeast(0f).roundToInt()) }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (c != null) Color(c) else Color.Transparent)
                    .border(3.dp, Color.White, CircleShape)
            )
        }

        // Long press beside the canvas: workspace color
        canvasMenuAt?.let { p ->
            Box(Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { canvasMenuAt = null }) {
                    Text("Workspace color", color = Q.textDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CANVAS_COLORS.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { col ->
                                    Box(
                                        Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(col))
                                            .border(if (col == vm.canvasColor) 3.dp else 1.dp, if (col == vm.canvasColor) Q.accent else Q.divider, CircleShape)
                                            .clickable {
                                                vm.changeCanvasColor(col)
                                                canvasMenuAt = null
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Drags out a shape with both corners snapped to the grid (when it's on). */
private fun dragShapeSnapped(vm: EditorViewModel, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
    val (ax, ay) = vm.snapPoint(x0, y0, radius)
    val (bx, by) = vm.snapPoint(x1, y1, radius)
    // Shift: square, circle or 45° lines; Alt: from the middle
    vm.dragShape(ax, ay, bx, by, square = vm.shapeSquare || vm.shiftOn, fromCenter = vm.altOn)
}

/** ([x], [y]) moved onto the nearest 0°, 45° or 90° line through ([ox], [oy]) (Shift). */
internal fun snap45(ox: Float, oy: Float, x: Float, y: Float): Pair<Float, Float> {
    val dx = x - ox
    val dy = y - oy
    if (dx == 0f && dy == 0f) return x to y
    val a = Math.round(kotlin.math.atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4)
    val ux = kotlin.math.cos(a).toFloat()
    val uy = kotlin.math.sin(a).toFloat()
    val len = dx * ux + dy * uy
    return (ox + ux * len) to (oy + uy * len)
}

/** With Shift: which axis is held still (0 = none, 1 = x stays, 2 = y stays), so snapping leaves it alone. */
private fun lockedAxis(vm: EditorViewModel, d: Offset): Int =
    if (!vm.shiftOn) 0 else if (abs(d.x) >= abs(d.y)) 2 else 1

/** Shift: only along the way it moved most (straight across or straight up and down). */
private fun axisLock(vm: EditorViewModel, d: Offset): Offset =
    if (!vm.shiftOn) d else if (abs(d.x) >= abs(d.y)) Offset(d.x, 0f) else Offset(0f, d.y)

/** The box dragged from ([x0], [y0]) to ([x1], [y1]): square with Shift, from its middle with Alt. */
private fun constrainedBox(vm: EditorViewModel, x0: Float, y0: Float, x1: Float, y1: Float): android.graphics.RectF {
    var w = x1 - x0
    var h = y1 - y0
    if (vm.shiftOn) {
        val m = max(abs(w), abs(h))
        w = if (w < 0) -m else m
        h = if (h < 0) -m else m
    }
    return if (vm.altOn) android.graphics.RectF(x0 - abs(w), y0 - abs(h), x0 + abs(w), y0 + abs(h))
    else android.graphics.RectF(minOf(x0, x0 + w), minOf(y0, y0 + h), maxOf(x0, x0 + w), maxOf(y0, y0 + h))
}

/** With Alt, a resized transform box is kept centered where it started. */
private fun centered(vm: EditorViewModel, start: com.tim.quartzeditor.engine.TransformState, t: com.tim.quartzeditor.engine.TransformState): com.tim.quartzeditor.engine.TransformState {
    if (!vm.altOn) return t
    val c0 = start.center
    val c1 = t.center
    return t.moved(c0.x - c1.x, c0.y - c1.y)
}

/** Runs [action] with Shift as Add and Alt as Subtract (both: Intersect) for the selection mode. */
private fun withSelectMode(vm: EditorViewModel, action: () -> Unit) {
    val old = vm.selectMode
    val m = when {
        vm.shiftOn && vm.altOn -> com.tim.quartzeditor.engine.SelectMode.INTERSECT
        vm.altOn -> com.tim.quartzeditor.engine.SelectMode.SUBTRACT
        vm.shiftOn -> com.tim.quartzeditor.engine.SelectMode.ADD
        else -> old
    }
    vm.selectMode = m
    try {
        action()
    } finally {
        vm.selectMode = old
    }
}

private fun checkerShader(cell: Int): BitmapShader {
    val b = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(0xFFFFFFFF.toInt())
    val p = Paint().apply { color = 0xFFCCCCCC.toInt() }
    c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
    c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
    return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}

/** Animated dashed outline of the selection (and of a shape being drawn), in screen space. */
@Composable
private fun MarchingAnts(vm: EditorViewModel, path: android.graphics.Path?, draft: android.graphics.Path?, corners: List<android.graphics.PointF>) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "ants")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 16f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween<Float>(700, easing = androidx.compose.animation.core.LinearEasing)),
        label = "phase",
    )
    Canvas(Modifier.fillMaxSize()) {
        val m = Matrix().apply {
            setScale(vm.scale, vm.scale)
            postTranslate(vm.offsetX, vm.offsetY)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2.dp.toPx()
            color = android.graphics.Color.WHITE
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2.dp.toPx()
            color = android.graphics.Color.BLACK
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), phase * density / 2f)
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for (p in listOfNotNull(path, draft)) {
                val sp = android.graphics.Path()
                p.transform(m, sp)
                nc.drawPath(sp, white)
                nc.drawPath(sp, black)
            }
        }
        corners.forEachIndexed { i, p ->
            val o = Offset(p.x * vm.scale + vm.offsetX, p.y * vm.scale + vm.offsetY)
            drawCircle(Color.White, (if (i == 0) 7 else 5).dp.toPx(), o)
            drawCircle(Q.accent, (if (i == 0) 5 else 3).dp.toPx(), o)
        }
    }
}
