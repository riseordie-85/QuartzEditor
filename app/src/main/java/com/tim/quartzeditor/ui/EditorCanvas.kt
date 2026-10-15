package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.animation.core.animate
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

private enum class GestureMode { PENDING, STROKE, BLOCKED, MOVE, PAN, NAV, EYEDROP, TRANSFORM, DONE }

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
                        val tool = vm.tool

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
                                    vm.moveTo(d.x / vm.scale, d.y / vm.scale)
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
                                        Tool.PAN, Tool.EYEDROPPER, Tool.TRANSFORM -> GestureMode.PAN
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
                                        vm.moveTo((main.position.x - start.x) / vm.scale, (main.position.y - start.y) / vm.scale)
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
                                            if (cur.distort) cur.dragCorner(h.i, target) else tStart.copy(keepAspect = cur.keepAspect).dragCorner(h.i, target)
                                        }
                                        is TransformHit.Edge -> vm.updateTransform { cur ->
                                            if (cur.distort) {
                                                cur.dragEdge(h.i, p, android.graphics.PointF(prevCx, prevCy))
                                            } else {
                                                val a = q0[h.i]
                                                val b = q0[(h.i + 1) % 4]
                                                val target = android.graphics.PointF((a.x + b.x) / 2f + ddx, (a.y + b.y) / 2f + ddy)
                                                tStart.copy(keepAspect = cur.keepAspect).dragEdge(h.i, target, target)
                                            }
                                        }
                                        TransformHit.Move -> vm.updateTransform { cur ->
                                            // Whole pixels, so a plain move stays sharp
                                            tStart.copy(keepAspect = cur.keepAspect).moved(ddx.roundToInt().toFloat(), ddy.roundToInt().toFloat())
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
                                GestureMode.BLOCKED, GestureMode.DONE -> Unit
                            }
                            ev.changes.forEach { it.consume() }
                        }

                        // All fingers up
                        val duration = endTime - t0
                        cursor = null
                        when (mode) {
                            GestureMode.STROKE -> vm.endStroke()
                            GestureMode.BLOCKED -> vm.onBlockedStroke()
                            GestureMode.MOVE -> vm.endMove()
                            GestureMode.EYEDROP -> {
                                val (cx, cy) = vm.screenToCanvas(lastPos.x, lastPos.y)
                                vm.sampleAt(cx, cy, commit = true)
                                loupe = null
                            }
                            GestureMode.PENDING -> if (!longPressDone && tool == Tool.MOVE && vm.isOnCanvas(start.x, start.y)) {
                                val (cx, cy) = vm.screenToCanvas(start.x, start.y)
                                vm.selectLayerAt(cx, cy)
                            } else if (!longPressDone && tool == Tool.TRANSFORM && vm.transform == null) {
                                vm.beginTransform()
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
                checker.setLocalMatrix(Matrix().apply { setTranslate(left, top) })
                nc.drawRect(left, top, right, bottom, Paint().apply { shader = checker })
                nc.save()
                nc.translate(ox, oy)
                nc.scale(s, s)
                val visible = RectF(-ox / s, -oy / s, (size.width - ox) / s, (size.height - oy) / s)
                DocRenderer.draw(nc, d, visible, vm.stroke, vm.preview, smooth = s < 2f, effects = vm.effectsLookup)
                nc.restore()
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

private fun checkerShader(cell: Int): BitmapShader {
    val b = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(0xFFFFFFFF.toInt())
    val p = Paint().apply { color = 0xFFCCCCCC.toInt() }
    c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
    c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
    return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}
