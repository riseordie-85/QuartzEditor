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

private enum class GestureMode { PENDING, STROKE, BLOCKED, MOVE, PAN, NAV, EYEDROP, TRANSFORM, SELECTING, GRADIENT, CROP, SHAPING, PEN, PEN_EDIT, DONE }

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
                        // Lasso outline being drawn
                        val lasso = android.graphics.Path().apply { moveTo(startCx, startCy) }
                        when (tool) {
                            Tool.BRUSH, Tool.ERASER -> if (vm.isOnCanvas(start.x, start.y)) {
                                val (cx, cy) = vm.screenToCanvas(start.x, start.y)
                                // Can't paint here: wait and see whether it becomes a pinch or stays a touch
                                mode = if (vm.strokeBlockReason() == null && vm.beginStroke(cx, cy)) GestureMode.STROKE else GestureMode.BLOCKED
                                if (mode == GestureMode.STROKE) cursor = start
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
                                    val d = main.position - start
                                    vm.moveTo(d.x / vm.scale, d.y / vm.scale, 10.dp.toPx() / vm.scale)
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
                                        Tool.MOVE -> if (vm.beginMove()) GestureMode.MOVE else GestureMode.PAN
                                        Tool.PAN, Tool.EYEDROPPER, Tool.TRANSFORM, Tool.BUCKET, Tool.CROP -> GestureMode.PAN
                                        // Polygonal lasso and magic wand work by taps: a drag moves the view
                                        Tool.SELECT -> if (vm.selectShape == com.tim.quartzeditor.engine.SelectShape.POLYGON || vm.selectShape == com.tim.quartzeditor.engine.SelectShape.WAND) GestureMode.PAN else GestureMode.SELECTING
                                        Tool.GRADIENT -> if (vm.beginGradient(startCx, startCy)) GestureMode.GRADIENT else GestureMode.DONE
                                        Tool.SHAPE -> GestureMode.SHAPING
                                        // Dragging while placing a point pulls out a curve handle
                                        Tool.PEN -> if (vm.penEditTarget != null) GestureMode.PAN else GestureMode.PEN
                                        // With the Text tool, dragging moves the text layer you're on
                                        Tool.TEXT -> if (vm.doc?.active?.isText == true && vm.beginMove()) GestureMode.MOVE else GestureMode.PAN
                                        Tool.BRUSH, Tool.ERASER -> {
                                            // Started beside the canvas: paint from there
                                            val (sx, sy) = vm.screenToCanvas(start.x, start.y)
                                            if (vm.strokeBlockReason() != null) {
                                                GestureMode.BLOCKED
                                            } else if (vm.beginStroke(sx, sy)) {
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
                                        vm.moveTo((main.position.x - start.x) / vm.scale, (main.position.y - start.y) / vm.scale, 10.dp.toPx() / vm.scale)
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
                                            val target = android.graphics.PointF(q0[h.i].x + ddx, q0[h.i].y + ddy)
                                            // Skew / perspective / distort work from where the gesture started (a move that
                                            // would fold the box over leaves it where it last was)
                                            if (tStart.distort) tStart.dragCornerBy(h.i, ddx, ddy) ?: cur else tStart.copy(keepAspect = cur.keepAspect).dragCorner(h.i, target)
                                        }
                                        is TransformHit.Edge -> vm.updateTransform { cur ->
                                            if (tStart.distort) {
                                                tStart.dragEdgeBy(h.i, ddx, ddy) ?: cur
                                            } else {
                                                val a = q0[h.i]
                                                val b = q0[(h.i + 1) % 4]
                                                val target = android.graphics.PointF((a.x + b.x) / 2f + ddx, (a.y + b.y) / 2f + ddy)
                                                tStart.copy(keepAspect = cur.keepAspect).dragEdge(h.i, target, target)
                                            }
                                        }
                                        TransformHit.Move -> {
                                            // Snaps the box's edges and middle to the canvas's
                                            val box = android.graphics.RectF(q0.minOf { it.x }, q0.minOf { it.y }, q0.maxOf { it.x }, q0.maxOf { it.y })
                                            val (sx, sy) = vm.snapDelta(box, ddx, ddy, 10.dp.toPx() / vm.scale)
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
                                                // Snap to straight angles
                                                val target = tStart.angle + deg
                                                val nearest = Math.round(target / 90f) * 90f
                                                if (abs(target - nearest) < 2.5f) deg = nearest - tStart.angle
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
                                    val r = android.graphics.RectF(minOf(startCx, cx), minOf(startCy, cy), maxOf(startCx, cx), maxOf(startCy, cy))
                                    vm.selectDraft = when (vm.selectShape) {
                                        com.tim.quartzeditor.engine.SelectShape.RECT -> com.tim.quartzeditor.engine.Selection.rect(r)
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
                                    vm.gradientTo(cx, cy)
                                }
                                GestureMode.CROP -> if (main != null && cropStart != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    vm.dragCrop(cropStart, cropHandle, cx - startCx, cy - startCy)
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
                                    Tool.BUCKET -> if (vm.isOnCanvas(start.x, start.y)) vm.bucketFill(startCx, startCy)
                                    Tool.SELECT -> when (vm.selectShape) {
                                        com.tim.quartzeditor.engine.SelectShape.POLYGON -> vm.polygonTap(startCx, startCy, 24.dp.toPx() / vm.scale)
                                        com.tim.quartzeditor.engine.SelectShape.WAND -> vm.magicWand(startCx, startCy)
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
                                if (draft != null) {
                                    if (vm.selectShape == com.tim.quartzeditor.engine.SelectShape.LASSO) draft.close()
                                    vm.commitSelectionShape(draft)
                                }
                                vm.selectDraft = null
                            }
                            GestureMode.GRADIENT -> vm.endGradient()
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
    vm.dragShape(ax, ay, bx, by)
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
