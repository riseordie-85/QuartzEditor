package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.tim.quartzeditor.engine.toMatrix
import androidx.compose.ui.graphics.asComposePath
import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tim.quartzeditor.engine.DocRenderer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Degrees two fingers turn before the canvas starts turning (so pinching doesn't turn it), and how near straight snaps. */
private const val ROTATE_START = 10f
private const val ROTATE_SNAP = 5f

private enum class GestureMode { VIEW, PENDING, STROKE, BLOCKED, MOVE, PAN, NAV, EYEDROP, TRANSFORM, SELECTING, GRADIENT, CROP, SHAPING, PEN, PEN_EDIT, PEN_MOVE, WARP, GUIDE, DEPTH, DONE }

/** A touch starting a gesture, and the ones already down then (a resting hand), which it leaves out. */
private class TouchStart(val down: PointerInputChange, val resting: Set<PointerId>)

/**
 * Like awaitEachGesture, but the next gesture starts as soon as a new finger or pen touches, even
 * while another touch stays down (a hand resting on the screen mustn't stop the pen). [block] gets
 * the touch to start with when the last gesture handed one over (a pen that came down mid-gesture),
 * and returns one to hand over to the next. [onEnd] runs after each gesture, however it ended.
 */
private suspend fun PointerInputScope.awaitEachTouch(onEnd: () -> Unit, block: suspend AwaitPointerEventScope.(carried: TouchStart?) -> TouchStart?) {
    val ctx = currentCoroutineContext()
    awaitPointerEventScope {
        var carried: TouchStart? = null
        while (ctx.isActive) {
            carried = try {
                block(carried)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!ctx.isActive) throw e
                null
            } finally {
                onEnd()
            }
        }
    }
}

/** A new touch in [e] (a pen first, if both come at once), with what else is already down. */
private fun touchIn(e: PointerEvent): TouchStart? {
    val downs = e.changes.filter { it.changedToDownIgnoreConsumed() }
    if (downs.isEmpty()) return null
    val d = downs.firstOrNull { it.isPen } ?: downs.first()
    return TouchStart(d, e.changes.filter { it.pressed && it.id != d.id && !it.changedToDownIgnoreConsumed() }.map { it.id }.toSet())
}

/** The next finger or pen to touch down. */
private suspend fun AwaitPointerEventScope.awaitNewDown(): TouchStart {
    while (true) touchIn(awaitPointerEvent())?.let { return it }
}

/**
 * Compose's stand-in for a cancelled touch (the system took it back, or the touch device changed):
 * everything up at once, already consumed. Real lifts arrive unconsumed.
 */
private fun PointerEvent.isCancel(): Boolean = changes.isNotEmpty() && changes.none { it.pressed } && changes.all { it.isConsumed }

/** No tool is in the middle of something the pen's eraser end shouldn't interrupt. */
private fun EditorViewModel.penCanErase(): Boolean =
    transform == null && warp == null && cropBox == null && cropQuad == null && pendingFx == null && enhance == null

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
    // Move › Show transform controls: the box, worked out again when what it's around changes
    androidx.compose.runtime.LaunchedEffect(vm.doc, vm.tool, vm.showTransformControls, vm.history.version, vm.selectedLayerIds(), vm.transform == null, vm.busy == null) {
        if (vm.transform == null && vm.busy == null) vm.refreshMoveBox()
    }
    var loupe by remember { mutableStateOf<Pair<Offset, Int?>?>(null) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    // Where the pen hovers above the screen (null when it isn't)
    var hover by remember { mutableStateOf<Offset?>(null) }
    // It's a mouse hovering (not the pen)
    var hoverMouse by remember { mutableStateOf(false) }
    // A right-click on the canvas: its menu, there
    var canvasContext by remember { mutableStateOf<Offset?>(null) }
    // A guide being moved with the Move tool: (vertical, which one)
    var movingGuide by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    // Typing on the picture: the cursor blinks, and the words' layout is kept while they don't change
    val textBuilt = remember { arrayOfNulls<Pair<com.tim.quartzeditor.engine.TextSpec, com.tim.quartzeditor.engine.TextBuilt>>(1) }
    // (Read only where it's drawn, so a blink redraws the overlay and nothing else; shown while typing)
    val caretState = remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(vm.textEdit, vm.textValue) {
        caretState.value = true
        if (vm.textEdit == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(530)
            caretState.value = !caretState.value
        }
    }
    val checker = remember { checkerShader(with(density) { 8.dp.toPx() }.roundToInt().coerceAtLeast(4)) }

    fun animateFit() {
        val d = vm.doc ?: return
        val fromS = vm.scale
        val fromX = vm.offsetX
        val fromY = vm.offsetY
        // Turns back to straight the short way
        val fromR = vm.normalizedRotation()
        val toS = vm.fitScale()
        val toX = (vm.viewW - d.width * toS) / 2f
        val toY = (vm.viewH - d.height * toS) / 2f
        scope.launch {
            animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                vm.scale = fromS + (toS - fromS) * t
                vm.offsetX = fromX + (toX - fromX) * t
                vm.offsetY = fromY + (toY - fromY) * t
                vm.rotation = fromR * (1f - t)
            }
            vm.fit()
        }
    }

    // The view: a square bigger than the screen (its diagonal), so turned it still covers it. It's
    // turned as a whole, so everything in it (and every touch) works in its own straight coordinates.
    // ColorDrop: the view's coordinates and the part of it that shows (the view is bigger than the screen)
    val viewCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Color(vm.canvasColor)).onGloballyPositioned { viewCoords[1] = it }) {
    val sw = constraints.maxWidth.toFloat()
    val sh = constraints.maxHeight.toFloat()
    val side = kotlin.math.ceil(kotlin.math.hypot(sw, sh)).toInt().coerceAtLeast(1)
    androidx.compose.runtime.SideEffect { vm.setViewSize(vm.viewW, vm.viewH, sw, sh) }
    // ColorDrop: where a color let go of over the screen lands in the view (turned with it)
    androidx.compose.runtime.DisposableEffect(vm) {
        vm.canvasViewFromWindow = { w ->
            val shown = viewCoords[1]?.takeIf { it.isAttached }?.boundsInWindow()
            if (shown == null || !shown.contains(w)) null else viewCoords[0]?.takeIf { it.isAttached }?.windowToLocal(w)
        }
        onDispose { vm.canvasViewFromWindow = null }
    }
    Box(
        Modifier
            .requiredSize(with(density) { side.toDp() })
            .graphicsLayer { rotationZ = vm.rotation }
            .onSizeChanged { vm.setViewSize(it.width.toFloat(), it.height.toFloat(), sw, sh) }
            .onGloballyPositioned { viewCoords[0] = it }
    ) {
        // The picture, in a layer of its own that keeps what it drew: when anything else on the
        // screen moves (the layers list scrolling, a panel sliding), it's shown as it is instead of
        // drawing every layer again. It's drawn again only when the picture or the view changes.
        // (Straight, it only needs the screen's size; turned, the whole view)
        val turned by remember { derivedStateOf { vm.rotation % 360f != 0f } }
        androidx.compose.foundation.layout.Spacer(
            Modifier
                .align(Alignment.Center)
                .then(if (turned) Modifier.fillMaxSize() else Modifier.requiredSize(with(density) { sw.toDp() }, with(density) { sh.toDp() }))
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawBehind {
            val d = vm.doc ?: return@drawBehind
            // Read so the canvas redraws when the picture changes
            @Suppress("UNUSED_VARIABLE") val v = d.renderVersion
            val s = vm.scale
            // Where this layer starts in the view's coordinates (it's centered in the view)
            val px0 = (vm.viewW - size.width) / 2f
            val py0 = (vm.viewH - size.height) / 2f
            val ox = vm.offsetX - px0
            val oy = vm.offsetY - py0
            // The part of it the screen shows (its bounding box, when turned): only that is drawn
            val th = Math.toRadians(vm.rotation.toDouble())
            val ac = abs(kotlin.math.cos(th)).toFloat()
            val asn = abs(kotlin.math.sin(th)).toFloat()
            val hx = ac * vm.screenW / 2f + asn * vm.screenH / 2f + 2f
            val hy = asn * vm.screenW / 2f + ac * vm.screenH / 2f + 2f
            val vis = android.graphics.Rect(
                kotlin.math.floor(size.width / 2f - hx).toInt().coerceAtLeast(0), kotlin.math.floor(size.height / 2f - hy).toInt().coerceAtLeast(0),
                kotlin.math.ceil(size.width / 2f + hx).toInt().coerceAtMost(size.width.roundToInt()), kotlin.math.ceil(size.height / 2f + hy).toInt().coerceAtMost(size.height.roundToInt()),
            )
            if (vis.width() <= 0 || vis.height() <= 0) return@drawBehind
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
                nc.clipRect(vis)
                nc.translate(ox, oy)
                nc.scale(s, s)
                val visible = RectF((vis.left - ox) / s, (vis.top - oy) / s, (vis.right - ox) / s, (vis.bottom - oy) / s)
                // Zoomed out, smaller copies of the tiles are drawn; any not made yet follow in the next frames
                val more = com.tim.quartzeditor.engine.TileLod.drawing(s) {
                    DocRenderer.draw(
                        nc, d, visible, vm.stroke, vm.preview, smooth = s < 2f, effects = vm.effectsLookup,
                        // (Recorded only for the part shown: its own pixels start at its corner)
                        view = Matrix().apply { setScale(s, s); postTranslate(ox - vis.left, oy - vis.top) },
                        viewW = vis.width(), viewH = vis.height(),
                    )
                }
                if (more) lodHandler.post { if (vm.doc === d) d.invalidate() }
                nc.restore()
            }
                }
        )
        // Holding Space (or with Pan): a hand, so it's clear dragging moves the view
        val handIcon = vm.tempPan || vm.tool == Tool.PAN
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val grabIcon = remember { PointerIcon(android.view.PointerIcon.getSystemIcon(ctx, android.view.PointerIcon.TYPE_GRAB)) }
        Canvas(
            Modifier
                .fillMaxSize()
                // (Brushes: a crosshair, with the brush's outline around it)
                .pointerHoverIcon(
                    when {
                        handIcon -> grabIcon
                        vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER || vm.tool == Tool.RETOUCH || vm.tool == Tool.EYEDROPPER || vm.tool == Tool.BUCKET -> PointerIcon.Crosshair
                        vm.tool == Tool.TEXT -> PointerIcon.Text
                        else -> PointerIcon.Default
                    }
                )
                // The mouse wheel: up and down moves the view up and down; with Ctrl, left and right
                // (Ctrl+Shift: faster); a sideways wheel or trackpad, sideways; Alt: zoom
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Scroll) continue
                            val c = e.changes.firstOrNull() ?: continue
                            val delta = c.scrollDelta
                            val mods = e.keyboardModifiers
                            val ctrl = mods.isCtrlPressed || mods.isMetaPressed
                            val step = 72.dp.toPx() * (if (ctrl && mods.isShiftPressed) 4f else 1f)
                            if (mods.isAltPressed) {
                                if (delta.y != 0f) vm.transformView(if (delta.y < 0f) 1.15f else 1f / 1.15f, c.position.x, c.position.y, 0f, 0f)
                            } else {
                                // On the screen: down the screen, or across it with Ctrl (the view may be turned).
                                // (With Ctrl, whichever way the wheel turned: Shift may make it a sideways scroll)
                                val main = if (abs(delta.y) >= abs(delta.x)) delta.y else delta.x
                                val sx = if (ctrl) -main * step else -delta.x * step
                                val sy = if (ctrl) 0f else -delta.y * step
                                val th = Math.toRadians(-vm.rotation.toDouble())
                                val dx = (sx * kotlin.math.cos(th) - sy * kotlin.math.sin(th)).toFloat()
                                val dy = (sx * kotlin.math.sin(th) + sy * kotlin.math.cos(th)).toFloat()
                                vm.transformView(1f, 0f, 0f, dx, dy)
                            }
                            c.consume()
                        }
                    }
                }
                // The pen hovering: the brush outline follows it
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            // The pen or a mouse over the canvas: where the brush would paint
                            val c = e.changes.firstOrNull { it.isPen || it.type == PointerType.Mouse }
                            hover = if (c != null && !c.pressed && e.type != PointerEventType.Exit) c.position else null
                            hoverMouse = c?.type == PointerType.Mouse
                            // The pen went off the canvas (its leaving is told to whatever it went over)
                            if (c != null && c.isPen) {
                                if (e.type == PointerEventType.Exit) vm.pen.left() else vm.pen.onCanvas(c.uptimeMillis)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPress = viewConfiguration.longPressTimeoutMillis
                    awaitEachTouch(onEnd = {
                        vm.penOverride = null
                        vm.penGesture = false
                    }) { carried ->
                        val first = carried ?: awaitNewDown()
                        var down = first.down
                        var resting = first.resting
                        // A mouse is its own thing: never a resting hand, never a finger moving the view.
                        // Right button: the canvas menu; middle button: moves the view.
                        val byMouse = down.type == PointerType.Mouse
                        val mouseButtons = if (byMouse) currentEvent.buttons else null
                        if (byMouse && mouseButtons?.isSecondaryPressed == true) {
                            canvasContext = down.position
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            return@awaitEachTouch null
                        }
                        val middlePan = byMouse && mouseButtons?.isTertiaryPressed == true
                        // A finger while the pen is near is the hand holding it: ignored, until the pen touches
                        if (!down.isPen && !byMouse && vm.pen.near(down.uptimeMillis)) {
                            var penDown: TouchStart? = null
                            while (penDown == null) {
                                val e = awaitPointerEvent()
                                penDown = touchIn(e)?.takeIf { it.down.isPen }
                                e.changes.forEach { it.consume() }
                                if (penDown == null && e.changes.none { it.pressed && it.id !in resting }) return@awaitEachTouch null
                            }
                            down = penDown.down
                            resting = penDown.resting
                        }
                        val byPen = down.isPen
                        vm.penGesture = true
                        // With a pen around, fingers move the view instead of painting (when that's on)
                        val fingerNav = !byPen && !byMouse && vm.fingersNavigate
                        // The pen's side button set to undo: a touch with it held does nothing (it undid when pressed)
                        if (byPen && vm.penButton == PenButton.UNDO && vm.pen.buttonHeld) {
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.id == down.id && it.pressed })
                            return@awaitEachTouch null
                        }
                        // A touch the next gesture should start with (a pen coming down during a finger gesture)
                        var handoff: TouchStart? = null
                        // The system took the touch back (it won't lift normally)
                        var cancelled = false
                        val t0 = down.uptimeMillis
                        val start = down.position
                        var mode = if (middlePan) GestureMode.PAN else GestureMode.PENDING
                        var maxPointers = 1
                        var moved = false
                        var navTravel = 0f
                        var totalZoom = 1f
                        // Turning the canvas: how far the fingers have turned, and whether it's turning yet
                        val rot0 = vm.rotation
                        var rotTotal = 0f
                        var rotating = false
                        var rotSign = 1f
                        var lastPos = start
                        var longPressDone = false
                        var endTime = t0
                        if (vm.doc == null) return@awaitEachTouch null
                        // The pen's eraser end (or its button, when set to) erases; its button can pick a color
                        val penErases = byPen && !vm.pickingForRange && vm.penCanErase() &&
                            ((vm.penEraserEnd && (down.type == PointerType.Eraser || vm.pen.eraserEnd)) || (vm.penButton == PenButton.ERASER && vm.pen.buttonHeld))
                        val penPicks = byPen && !penErases && vm.penButton == PenButton.EYEDROPPER && vm.pen.buttonHeld
                        if (penErases) vm.penOverride = Tool.ERASER
                        // Space held on a keyboard pans with any tool
                        val tool = when {
                            vm.tempPan -> Tool.PAN
                            penErases -> Tool.ERASER
                            penPicks -> Tool.EYEDROPPER
                            // Color Range's color, picked from the picture
                            vm.pickingForRange -> Tool.EYEDROPPER
                            else -> vm.tool
                        }
                        val downPen = vm.penSample(down, 0, 1, down.uptimeMillis)
                        vm.brushPickerFor = null
                        var hit: TransformHit? = null
                        val tStart = vm.transform?.state
                        // A Move box handle grabbed: Free Transform's box, once it's ready
                        var tLate: com.tim.quartzeditor.engine.TransformState? = null
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
                        // Enhance: tap what stays sharp / where fog starts, drag the light
                        val enhanceKind = vm.enhance?.kind?.takeIf { it == EnhanceKind.DEPTH_BLUR || it == EnhanceKind.FOG || it == EnhanceKind.RELIGHT }
                        var depthMoved = false
                        if (mode == GestureMode.PENDING && enhanceKind != null) {
                            mode = GestureMode.DEPTH
                            if (enhanceKind == EnhanceKind.RELIGHT) vm.enhanceTouch(startCx, startCy)
                        } else if (mode == GestureMode.PENDING && vm.enhance != null) {
                            // The other Enhance effects: nothing to touch, so one finger moves the view (no painting under a preview)
                            mode = GestureMode.PAN
                        }
                        var prevCx = startCx
                        var prevCy = startCy
                        // Crop box handles: 0..3 corners, 4..7 edges, -1 inside
                        var cropHandle = -2
                        val cropStart = vm.cropBox?.let { android.graphics.RectF(it) }
                        val quadStart = vm.cropQuad?.map { android.graphics.PointF(it.x, it.y) }
                        if (tool == Tool.CROP && quadStart != null) {
                            // Perspective crop: a corner, an edge's middle, or inside
                            val r = 30.dp.toPx()
                            fun sc(p: android.graphics.PointF) = Offset(p.x * vm.scale + vm.offsetX, p.y * vm.scale + vm.offsetY)
                            val c = quadStart.map { sc(it) }
                            val e = (0 until 4).map { i -> (c[i] + c[(i + 1) % 4]) / 2f }
                            val ci = c.indexOfFirst { (it - start).getDistance() < r }
                            val ei = e.indexOfFirst { (it - start).getDistance() < r }
                            cropHandle = when {
                                ci >= 0 -> ci
                                ei >= 0 -> 4 + ei
                                pointInQuad(quadStart, startCx, startCy) -> -1
                                else -> -2
                            }
                            if (cropHandle != -2) mode = GestureMode.CROP
                        } else if (tool == Tool.CROP && cropStart != null) {
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
                        // Drawing with a pen: a point already placed under the finger is dragged around
                        var penGrab = -1
                        var penGrabStart: com.tim.quartzeditor.engine.PenPoint? = null
                        if (tool == Tool.PEN && vm.penEditTarget == null && vm.penPoints.isNotEmpty()) {
                            penGrab = vm.penPointable(vm.penPointAt(startCx, startCy, 12.dp.toPx() / vm.scale))
                            // (With the Pen, dragging on the first point closes the shape with a curve there)
                            if (!vm.penCurvature && penGrab == 0 && vm.penPoints.size >= 2 && vm.penContinue == null) penGrab = -1
                            penGrabStart = vm.penPoints.getOrNull(penGrab)
                        }
                        // Move or Pan tool on a guide: drag it (off the picture removes it)
                        var guidePos = 0f
                        // (vm.tool, not tool: holding Space pans with any tool and shouldn't grab guides)
                        if ((vm.tool == Tool.MOVE || vm.tool == Tool.PAN) && !penErases && !penPicks && vm.showGuides && !vm.guidesLocked && mode == GestureMode.PENDING) {
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
                                vm.prepareGuideSnap()
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
                        // QuickShape: where and since when the stroke's been held still (and whether it was tried there)
                        var holdPos = start
                        var holdT = t0
                        var holdTried = false
                        val holdSlop = 6.dp.toPx()
                        // Touches seen before the stroke snapped (a hand settling on the screen): only a finger put down after makes it perfect
                        val beforeSnap = HashSet<PointerId>()
                        // Clone Stamp: Shift + tap (or Pick source, then a tap) sets where it copies from, and
                        // Alt keeps the stroke straight; every other brush is straight with Shift
                        val cloning = tool == Tool.RETOUCH && vm.retouchKind == com.tim.quartzeditor.engine.RetouchKind.CLONE
                        fun straightOn() = if (cloning) vm.altOn else vm.shiftOn
                        if (cloning && (vm.shiftOn || vm.pickingCloneSource) && vm.isOnCanvas(start.x, start.y)) {
                            vm.setCloneSource(startCx, startCy)
                            mode = GestureMode.DONE
                        }
                        // R / Z: the view tools take the drag (or the click)
                        if (mode == GestureMode.PENDING && vm.viewTool != null) mode = GestureMode.VIEW
                        // Ctrl-click: the layer under the pointer is picked, whatever the tool (like Photoshop)
                        if (mode == GestureMode.PENDING && vm.keyCtrl && vm.textEdit == null && vm.isOnCanvas(start.x, start.y)) {
                            vm.selectLayerAt(startCx, startCy)
                            mode = GestureMode.DONE
                        }
                        // Move with transform controls: a handle of the box switches to Free Transform, with this drag
                        if (mode == GestureMode.PENDING && tool == Tool.MOVE && vm.showTransformControls && vm.transform == null && !vm.altOn) {
                            vm.moveBox?.let { b ->
                                val q = listOf(Offset(b.left, b.top), Offset(b.right, b.top), Offset(b.right, b.bottom), Offset(b.left, b.bottom))
                                    .map { Offset(it.x * vm.scale + vm.offsetX, it.y * vm.scale + vm.offsetY) }
                                val r = 22.dp.toPx()
                                // A small box: inside it always moves (handles only from outside, no edge handles)
                                val small = minOf(b.width(), b.height()) * vm.scale < 4 * r
                                val inside = insideQuad(q, start)
                                val ci = if (small && inside) -1 else q.indexOfFirst { (it - start).getDistance() < r }
                                val ei = if (small) null else (0 until 4).firstOrNull { i -> ((q[i] + q[(i + 1) % 4]) / 2f - start).getDistance() < r }
                                val h = when {
                                    ci >= 0 -> TransformHit.Corner(ci)
                                    ei != null -> TransformHit.Edge(ei)
                                    // Just outside a corner: turns it (like Photoshop)
                                    !inside && q.any { (it - start).getDistance() < r * 2.2f } -> TransformHit.Rotate
                                    else -> null
                                }
                                if (h != null) {
                                    hit = h
                                    mode = if (vm.transformFromMoveBox()) GestureMode.TRANSFORM else GestureMode.DONE
                                }
                            }
                        }
                        // The view tool's turn: where the pointer started, around the middle of the view, on the screen
                        fun screenAngle(p: Offset): Double {
                            val th = Math.toRadians(vm.rotation.toDouble())
                            val vx = p.x - vm.viewW / 2f
                            val vy = p.y - vm.viewH / 2f
                            return Math.toDegrees(kotlin.math.atan2(vx * kotlin.math.sin(th) + vy * kotlin.math.cos(th), vx * kotlin.math.cos(th) - vy * kotlin.math.sin(th)))
                        }
                        val viewAngle0 = screenAngle(start)
                        if (mode == GestureMode.PENDING) when (tool) {
                            // Alt with a brush: pick a color (like Photoshop), except the clone stamp, whose Alt draws straight
                            Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> if (vm.altOn && !cloning && tool != Tool.ERASER && vm.isOnCanvas(start.x, start.y)) {
                                loupe = start to vm.sampleAt(startCx, startCy, commit = false)
                                mode = GestureMode.EYEDROP
                            } else if (vm.isOnCanvas(start.x, start.y) && !fingerNav) {
                                val (cx, cy) = vm.screenToCanvas(start.x, start.y)
                                // Shift and a tap: a straight line from where the last stroke ended (like Photoshop)
                                val join = vm.lastStrokeEnd?.takeIf { straightOn() && it.first == vm.doc?.activeId }
                                val bx = join?.second ?: cx
                                val by = join?.third ?: cy
                                // Can't paint here: wait and see whether it becomes a pinch or stays a touch
                                mode = if (vm.strokeBlockReason() == null && vm.beginStroke(bx, by, downPen)) GestureMode.STROKE else GestureMode.BLOCKED
                                if (mode == GestureMode.STROKE) {
                                    cursor = start
                                    if (join != null) vm.strokeTo(cx, cy, downPen)
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
                            // (Holding a mouse button still isn't a long press: the right button has the menu)
                            val waitLong = mode == GestureMode.PENDING && !moved && !longPressDone && !byMouse
                            // A stroke held still long enough snaps to a shape, even when the pen sends nothing
                            val waitHold = mode == GestureMode.STROKE && !holdTried && vm.quickShapeReady && !straightOn()
                            val ev: PointerEvent? = if (waitLong) {
                                val left = longPress - (SystemClock.uptimeMillis() - t0)
                                withTimeoutOrNull(max(1L, left)) { awaitPointerEvent() }
                            } else if (waitHold) {
                                val left = holdT + vm.quickShapeDelay - SystemClock.uptimeMillis()
                                withTimeoutOrNull(max(1L, left)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (ev == null && mode == GestureMode.STROKE) {
                                holdTried = true
                                vm.quickShapeSnap(perfect = false)
                                continue
                            }
                            if (ev == null) {
                                // Long press without moving
                                longPressDone = true
                                // A finger held on the picture while fingers don't paint: pick the color there
                                // (Not right after the pen was used: then it's likely the hand holding it)
                                if (fingerNav && (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.RETOUCH) && vm.isOnCanvas(start.x, start.y) &&
                                    vm.pen.idleFor(SystemClock.uptimeMillis()) > 2500
                                ) {
                                    loupe = start to vm.sampleAt(startCx, startCy, commit = false)
                                    lastPos = start
                                    mode = GestureMode.EYEDROP
                                    continue
                                }
                                // (Holding a pen point is the start of dragging it, not the menu)
                                if (!vm.isOnCanvas(start.x, start.y) && penGrab < 0) {
                                    canvasMenuAt = start
                                    mode = GestureMode.DONE
                                }
                                continue
                            }
                            // A pen coming down during a finger gesture ends it and starts its own
                            if (!byPen) {
                                val pd = touchIn(ev)?.takeIf { it.down.isPen }
                                if (pd != null) {
                                    handoff = pd
                                    endTime = pd.down.uptimeMillis
                                    break
                                }
                            }
                            if (ev.isCancel()) {
                                cancelled = true
                                vm.pen.left()
                                endTime = ev.changes.first().uptimeMillis
                                break
                            }
                            // With the pen, only the pen counts; touches already down when it started (a resting hand) never do
                            val pressed = ev.changes.filter { it.pressed && (if (byPen) it.id == down.id else it.id !in resting) }
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
                                        (tStart ?: tLate)?.let { ts -> vm.updateTransform { ts.copy(keepAspect = it.keepAspect) } }
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
                                        if (quadStart != null) vm.cropQuad = quadStart
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
                                    GestureMode.PEN_MOVE -> {
                                        penGrabStart?.let { vm.penPutPoint(penGrab, it) }
                                        // Put back, so there's nothing to undo
                                        vm.penHistory.removeLastOrNull()
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
                                GestureMode.VIEW -> if (main != null) {
                                    if ((main.position - start).getDistance() > slop) moved = true
                                    if (moved) when (vm.viewTool) {
                                        ViewTool.ROTATE -> {
                                            var target = rot0 + (screenAngle(main.position) - viewAngle0).toFloat()
                                            // Shift: 15° steps
                                            if (vm.shiftOn) target = Math.round(target / 15f) * 15f
                                            vm.rotateView(target - vm.rotation, vm.viewW / 2f, vm.viewH / 2f)
                                        }
                                        // Right zooms in, left out, around where it started
                                        ViewTool.ZOOM -> vm.transformView(kotlin.math.exp((main.position.x - lastPos.x) / 220f), start.x, start.y, 0f, 0f)
                                        null -> Unit
                                    }
                                    lastPos = main.position
                                }
                                GestureMode.NAV -> {
                                    val zoom = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    val c = ev.calculateCentroid(useCurrent = true)
                                    if (c != Offset.Unspecified) vm.transformView(zoom, c.x, c.y, pan.x, pan.y)
                                    // Twisting turns the canvas (once it's clearly a twist), snapping to straight angles
                                    if (!vm.rotationLocked && c != Offset.Unspecified) {
                                        rotTotal += ev.calculateRotation()
                                        // (Only when it's mostly a twist, so a long pinch doesn't start turning it)
                                        if (!rotating && abs(rotTotal) > ROTATE_START && abs(rotTotal) > abs(kotlin.math.ln(totalZoom)) * 60f) {
                                            rotating = true
                                            rotSign = if (rotTotal > 0f) 1f else -1f
                                            moved = true
                                        }
                                        if (rotating) {
                                            val raw = rot0 + rotTotal - rotSign * ROTATE_START
                                            val straight = Math.round(raw / 90f) * 90f
                                            val target = if (abs(raw - straight) < ROTATE_SNAP) straight else raw
                                            vm.rotateView(target - vm.rotation, c.x, c.y)
                                        }
                                    }
                                    totalZoom *= zoom
                                    navTravel += pan.getDistance()
                                    if (navTravel > slop || abs(totalZoom - 1f) > 0.06f) moved = true
                                }
                                GestureMode.STROKE -> {
                                    // Another finger put on the screen after it snapped (not a resting hand): the shape goes perfect
                                    if (vm.quickShape == null) ev.changes.forEach { if (it.pressed) beforeSnap.add(it.id) }
                                    val extraFinger = vm.quickShape != null && ev.changes.any { it.pressed && it.id != down.id && it.id !in resting && it.id !in beforeSnap && !it.isPen }
                                    // Held still (pens jiggle a little, so "still" is within a few pixels)
                                    if (main != null && (main.position - holdPos).getDistance() > holdSlop) {
                                        holdPos = main.position
                                        holdT = now
                                        holdTried = false
                                    }
                                    // Only the finger that started the stroke paints
                                    if (main == null) {
                                        vm.endStroke()
                                        cursor = null
                                        mode = GestureMode.DONE
                                    } else if (vm.quickShape != null) {
                                        // A snapped shape: moving sizes and turns it
                                        val (x, y) = vm.screenToCanvas(main.position.x, main.position.y)
                                        vm.quickShapeMove(x, y, perfect = vm.shiftOn || extraFinger)
                                        cursor = main.position
                                    } else if (!holdTried && now - holdT >= vm.quickShapeDelay && vm.quickShapeReady && !straightOn()) {
                                        holdTried = true
                                        vm.quickShapeSnap(perfect = false)
                                        cursor = main.position
                                    } else if (straightOn()) {
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
                                            vm.strokeTo(strokeOx + ux * len, strokeOy + uy * len, vm.penSample(main, 0, 1, main.uptimeMillis))
                                        }
                                        cursor = main.position
                                    } else {
                                        // Every in-between point, each with the pen's pressure and tilt there
                                        val hs = main.historical
                                        val m = hs.size + 1
                                        hs.forEachIndexed { k, h ->
                                            val (x, y) = vm.screenToCanvas(h.position.x, h.position.y)
                                            vm.strokeTo(x, y, vm.penSample(main, k, m, main.uptimeMillis))
                                        }
                                        val (x, y) = vm.screenToCanvas(main.position.x, main.position.y)
                                        vm.strokeTo(x, y, vm.penSample(main, m - 1, m, main.uptimeMillis))
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
                                        Tool.PEN -> if (vm.penEditTarget != null) GestureMode.PAN else if (penGrab >= 0) GestureMode.PEN_MOVE else GestureMode.PEN
                                        // With the Text tool, dragging moves the text layer you're on
                                        Tool.TEXT -> if (vm.doc?.active?.isText == true && vm.beginMove()) GestureMode.MOVE else GestureMode.PAN
                                        // Fingers move the view when the pen paints
                                        Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> if (fingerNav) GestureMode.PAN else {
                                            // Started beside the canvas: paint from there
                                            val (sx, sy) = vm.screenToCanvas(start.x, start.y)
                                            if (vm.strokeBlockReason() != null) {
                                                GestureMode.BLOCKED
                                            } else if (vm.beginStroke(sx, sy, downPen)) {
                                                strokeOx = sx
                                                strokeOy = sy
                                                val (x, y) = vm.screenToCanvas(main.position.x, main.position.y)
                                                vm.strokeTo(x, y, vm.penSample(main, 0, 1, main.uptimeMillis))
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
                                    if (mode == GestureMode.PEN_MOVE) {
                                        vm.penRemember()
                                        vm.penLastTap = -1
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        penGrabStart?.let { p0 ->
                                            val (px, py) = vm.snapPoint(p0.x + (cx - startCx), p0.y + (cy - startCy), 10.dp.toPx() / vm.scale)
                                            vm.penPutPoint(penGrab, p0.copy(x = px, y = py))
                                        }
                                    }
                                    if (mode == GestureMode.SHAPING || mode == GestureMode.PEN) {
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        if (mode == GestureMode.SHAPING) dragShapeSnapped(vm, startCx, startCy, cx, cy, 10.dp.toPx() / vm.scale)
                                        else vm.snapPoint(startCx, startCy, 10.dp.toPx() / vm.scale).let { (px, py) ->
                                            vm.penPreview(com.tim.quartzeditor.engine.PenPoint(px, py, cx - px, cy - py))
                                        }
                                    }
                                }
                                GestureMode.TRANSFORM -> if (main != null && (tStart ?: tLate ?: vm.transform?.state?.also { tLate = it }) != null) {
                                    @Suppress("NAME_SHADOWING")
                                    val tStart = (tStart ?: tLate)!!
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
                                            else centered(vm, tStart, tStart.copy(keepAspect = ka || vm.shiftOn).dragCorner(h.i, target)).copy(keepAspect = ka)
                                        }
                                        is TransformHit.Edge -> vm.updateTransform { cur ->
                                            if (tStart.distort) {
                                                tStart.dragEdgeBy(h.i, ddx, ddy) ?: cur
                                            } else {
                                                val a = q0[h.i]
                                                val b = q0[(h.i + 1) % 4]
                                                val k = if (vm.altOn) 2f else 1f
                                                val target = android.graphics.PointF((a.x + b.x) / 2f + ddx * k, (a.y + b.y) / 2f + ddy * k)
                                                centered(vm, tStart, tStart.copy(keepAspect = cur.keepAspect || vm.shiftOn).dragEdge(h.i, target, target)).copy(keepAspect = cur.keepAspect)
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
                                GestureMode.CROP -> if (main != null && quadStart != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    vm.dragCropQuad(quadStart, cropHandle, cx - startCx, cy - startCy)
                                } else if (main != null && cropStart != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    vm.dragCrop(cropStart, cropHandle, cx - startCx, cy - startCy, keepShape = vm.shiftOn, fromCenter = vm.altOn)
                                }
                                GestureMode.SHAPING -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    dragShapeSnapped(vm, startCx, startCy, cx, cy, 10.dp.toPx() / vm.scale)
                                }
                                GestureMode.PEN_MOVE -> if (main != null) {
                                    val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                    val p0 = penGrabStart
                                    if (p0 != null) {
                                        // By how far the finger moved (no jump to the finger); it snaps like a new point
                                        val (px, py) = vm.snapPoint(p0.x + (cx - startCx), p0.y + (cy - startCy), 10.dp.toPx() / vm.scale)
                                        vm.penPutPoint(penGrab, p0.copy(x = px, y = py))
                                    }
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
                                GestureMode.DEPTH -> if (main != null) {
                                    if (!depthMoved && (main.position - start).getDistance() > slop) depthMoved = true
                                    if (depthMoved || enhanceKind == EnhanceKind.RELIGHT) {
                                        val (cx, cy) = vm.screenToCanvas(main.position.x, main.position.y)
                                        vm.enhanceTouch(cx, cy)
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
                                        // Snaps to the canvas's and the active layer's edges and middle
                                        guidePos = vm.snapGuide(g.first, if (g.first) cx else cy, 10.dp.toPx() / vm.scale)
                                        vm.guideDraft = g.first to guidePos
                                    }
                                }
                                GestureMode.BLOCKED, GestureMode.DONE -> Unit
                            }
                            ev.changes.forEach { it.consume() }
                        }

                        // All fingers up. A pen took over, or the touch was taken back: no taps, nothing undone,
                        // and what a finger (probably a resting hand) started is dropped. (A pen's stroke is kept.)
                        if (handoff != null || cancelled) {
                            if (mode == GestureMode.PENDING) mode = GestureMode.DONE
                            moved = true
                        }
                        if (handoff != null || (cancelled && !byPen)) {
                            when (mode) {
                                GestureMode.STROKE -> vm.cancelStroke()
                                GestureMode.MOVE -> vm.cancelMove()
                                GestureMode.EYEDROP -> loupe = null
                                GestureMode.TRANSFORM -> (tStart ?: tLate)?.let { ts -> vm.updateTransform { ts.copy(keepAspect = it.keepAspect) } }
                                GestureMode.SELECTING -> vm.selectDraft = null
                                GestureMode.GRADIENT -> vm.cancelGradient()
                                GestureMode.CROP -> {
                                    if (quadStart != null) vm.cropQuad = quadStart
                                    if (cropStart != null) vm.cropBox = cropStart
                                }
                                GestureMode.SHAPING -> vm.cancelShape()
                                GestureMode.PEN -> vm.penPreview(null)
                                GestureMode.PEN_MOVE -> {
                                    penGrabStart?.let { vm.penPutPoint(penGrab, it) }
                                    vm.penHistory.removeLastOrNull()
                                }
                                GestureMode.PEN_EDIT -> vm.penEditCancel()
                                GestureMode.WARP -> vm.warpCancelGesture()
                                GestureMode.GUIDE -> {
                                    vm.guideDraft = null
                                    movingGuide = null
                                }
                                else -> Unit
                            }
                            mode = GestureMode.DONE
                        }
                        val duration = endTime - t0
                        cursor = null
                        vm.clearSnapGuides()
                        when (mode) {
                            // Zoom: a click zooms in there (Alt: out)
                            GestureMode.VIEW -> if (!moved && vm.viewTool == ViewTool.ZOOM) vm.transformView(if (vm.altOn) 1f / 1.6f else 1.6f, start.x, start.y, 0f, 0f)
                            GestureMode.STROKE -> vm.endStroke()
                            GestureMode.BLOCKED -> vm.onBlockedStroke()
                            GestureMode.MOVE -> vm.endMove()
                            GestureMode.EYEDROP -> {
                                val (cx, cy) = vm.screenToCanvas(lastPos.x, lastPos.y)
                                if (vm.pickingForRange) vm.rangeColorPicked(vm.sampleAt(cx, cy, commit = false, radius = 0, allLayers = vm.colorRange?.allLayers))
                                else vm.sampleAt(cx, cy, commit = true)
                                loupe = null
                            }
                            GestureMode.PENDING -> if (vm.pickingForRange) {
                                // Picking Color Range's color but not on the picture: stop picking
                                vm.cancelRangePick()
                            } else if (!longPressDone) {
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
                                        vm.penAdd(com.tim.quartzeditor.engine.PenPoint(px, py), 24.dp.toPx() / vm.scale, startCx, startCy)
                                    }
                                    Tool.TEXT -> vm.textTap(startCx, startCy)
                                    // Fingers don't paint (the pen does), but a tap still applies an adjustment or transform being tried
                                    Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> if (fingerNav && (vm.pendingFx != null || vm.transform != null)) vm.onBlockedStroke()
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
                            GestureMode.DEPTH -> if (!depthMoved && enhanceKind != EnhanceKind.RELIGHT) vm.enhanceTouch(startCx, startCy)
                            GestureMode.GUIDE -> {
                                val g = movingGuide
                                vm.guideDraft = null
                                movingGuide = null
                                if (g != null) vm.moveGuide(g.first, g.second, guidePos)
                            }
                            GestureMode.SHAPING -> vm.finishShape()
                            GestureMode.PEN -> vm.penDrag?.let { vm.penAdd(it, 24.dp.toPx() / vm.scale, startCx, startCy) }
                            GestureMode.PEN_EDIT -> {
                                vm.penEditEnd(penMoved)
                                // A tap on a point picks it; a second tap on it soon after makes it a corner or a curve
                                if (!penMoved) penHit?.let { vm.penEditTapPoint(it) }
                            }
                            GestureMode.NAV -> when {
                                !moved && duration < 350 && maxPointers == 2 -> vm.undo()
                                !moved && duration < 350 && maxPointers >= 3 -> vm.redo()
                                // A quick pinch in: the whole canvas, straight
                                handoff == null && duration < 450 && totalZoom < 0.72f -> animateFit()
                                rotating -> vm.rotation = vm.normalizedRotation()
                            }
                            else -> Unit
                        }
                        vm.penOverride = null
                        handoff
                    }
                }
        ) {
            val d = vm.doc ?: return@Canvas
            // (The picture itself is drawn by the layer underneath; this draws what goes over it)
            val s = vm.scale
            val ox = vm.offsetX
            val oy = vm.offsetY
            // Alignment grid
            drawGrid(vm, s, ox, oy, d.width, d.height)
            // Guides
            @Suppress("UNUSED_VARIABLE") val gv = d.guidesX.size + d.guidesY.size
            drawGuides(vm, s, ox, oy, d.width, d.height, movingGuide)

            // Perspective crop: the four corners, a grid that follows them, and what's cut off dimmed
            val quad = vm.cropQuad
            if (quad != null) drawCropQuad(quad, s, ox, oy)
            // Crop box: dim what's cut off, thirds, handles
            if (quad == null) vm.cropBox?.let { b ->
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

            // Move › Show transform controls: the box around the layer (following a move in progress)
            val mb = vm.moveBox
            if (mb != null && vm.tool == Tool.MOVE && vm.showTransformControls && vm.transform == null) {
                val pv = vm.preview?.takeIf { vm.moveLayer != null }
                val dx = (pv?.dragX ?: 0f) * s
                val dy = (pv?.dragY ?: 0f) * s
                val q = listOf(Offset(mb.left, mb.top), Offset(mb.right, mb.top), Offset(mb.right, mb.bottom), Offset(mb.left, mb.bottom))
                    .map { Offset(it.x * s + ox + dx, it.y * s + oy + dy) }
                val line = 1.dp.toPx()
                val col = if (vm.moveBoxLocked) Color.White.copy(alpha = 0.45f) else Color.White
                for (i in 0 until 4) {
                    drawLine(Color.Black.copy(alpha = 0.5f), q[i], q[(i + 1) % 4], line * 2.5f)
                    drawLine(col, q[i], q[(i + 1) % 4], line)
                }
                val hs = 5.dp.toPx()
                val handles = q + (0 until 4).map { i -> (q[i] + q[(i + 1) % 4]) / 2f }
                for (p in handles) {
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(p.x - hs - line, p.y - hs - line), androidx.compose.ui.geometry.Size(2 * (hs + line), 2 * (hs + line)))
                    drawRect(col, Offset(p.x - hs, p.y - hs), androidx.compose.ui.geometry.Size(2 * hs, 2 * hs))
                }
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

            // Enhance: the focus point (Depth blur) or the light (Relight)
            vm.enhance?.let { e ->
                val ep = vm.enhanceParams
                val line = 1.5.dp.toPx()
                if (e.kind == EnhanceKind.DEPTH_BLUR) ep.focusPoint?.let { f ->
                    val c = Offset(f.x * s + ox, f.y * s + oy)
                    val r = 16.dp.toPx()
                    val k = 6.dp.toPx()
                    for (col in listOf(Color.Black.copy(alpha = 0.6f) to 3.dp.toPx(), Color.White to line)) {
                        drawCircle(col.first, r, c, style = Stroke(col.second))
                        drawLine(col.first, c - Offset(r + k, 0f), c - Offset(r - k, 0f), col.second)
                        drawLine(col.first, c + Offset(r - k, 0f), c + Offset(r + k, 0f), col.second)
                        drawLine(col.first, c - Offset(0f, r + k), c - Offset(0f, r - k), col.second)
                        drawLine(col.first, c + Offset(0f, r - k), c + Offset(0f, r + k), col.second)
                    }
                }
                if (e.kind == EnhanceKind.RELIGHT) ep.light?.let { l ->
                    val c = Offset(l.x * s + ox, l.y * s + oy)
                    val r = 11.dp.toPx()
                    // A little sun, in the light's color
                    for (i in 0 until 8) {
                        val a = i * Math.PI.toFloat() / 4f
                        val d = Offset(kotlin.math.cos(a), kotlin.math.sin(a))
                        drawLine(Color.Black.copy(alpha = 0.6f), c + d * (r + 3.dp.toPx()), c + d * (r + 10.dp.toPx()), 4.dp.toPx())
                        drawLine(Color.White, c + d * (r + 3.dp.toPx()), c + d * (r + 10.dp.toPx()), 2.dp.toPx())
                    }
                    drawCircle(Color.Black.copy(alpha = 0.6f), r + line, c)
                    drawCircle(Color.White, r, c)
                    drawCircle(Color(l.color or 0xFF000000.toInt()), r - 2.5f * line, c)
                }
            }

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
                val top = oy
                val bottom = oy + d.height * s
                val left = ox
                val right = ox + d.width * s
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
                @Suppress("UNUSED_VARIABLE") val watch = vm.penCorners.toList()
                val pts = vm.penShown(vm.penDrag)
                val curvature = vm.penCurvature
                val continuing = vm.penContinue != null
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
                    // (The Curvature Pen works its handles out itself: only its points show)
                    if (pt.smooth && !curvature) {
                        val a = sp(pt.x + pt.ix, pt.y + pt.iy)
                        val b = sp(pt.x + pt.hx, pt.y + pt.hy)
                        drawLine(Q.accent, a, b, 1.5.dp.toPx())
                        drawCircle(Q.accent, hr * 0.8f, a)
                        drawCircle(Q.accent, hr * 0.8f, b)
                    }
                    // The first point is bigger: tap it to close the shape (carrying a shape on, its other end closes it)
                    val r = if (i == 0 && !continuing) hr * 1.6f else hr
                    if (curvature && vm.penCorners.getOrElse(i) { false }) {
                        // A sharp corner: a diamond
                        val dp = androidx.compose.ui.graphics.Path().apply {
                            moveTo(o.x, o.y - r * 1.4f); lineTo(o.x + r * 1.4f, o.y); lineTo(o.x, o.y + r * 1.4f); lineTo(o.x - r * 1.4f, o.y); close()
                        }
                        drawPath(dp, Color.White)
                        drawPath(dp, Color.Black.copy(alpha = 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                        return@forEachIndexed
                    }
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

            // Alt-click on a mask: the mask itself (white shows, black hides) over the picture
            vm.viewMaskOf?.let { id ->
                val l = vm.doc?.layer(id)
                val m = l?.mask
                val dd = vm.doc
                if (l != null && m != null && dd != null) drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(ox, oy)
                    nc.scale(s, s)
                    nc.clipRect(0f, 0f, dd.width.toFloat(), dd.height.toFloat())
                    nc.drawColor(android.graphics.Color.WHITE)
                    m.drawTo(nc, l.x.toFloat(), l.y.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.BLACK })
                    nc.restore()
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
                // The cursor (and what's selected), on the words themselves: straight text only
                val spec = l.props.text
                if (spec != null) {
                    val built = textBuilt[0]?.takeIf { it.first === spec }?.second
                        ?: com.tim.quartzeditor.engine.VectorArt.buildText(spec).also { textBuilt[0] = spec to it }
                    val layout = built.layout
                    if (layout != null && built.warp == null) {
                        val m = Matrix(spec.matrix.toMatrix()).apply {
                            postTranslate(l.x.toFloat(), l.y.toFloat())
                            postScale(s, s)
                            postTranslate(ox, oy)
                        }
                        val sel = vm.textValue.selection
                        val n = layout.text.length
                        if (!sel.collapsed) {
                            val path = android.graphics.Path()
                            layout.getSelectionPath(sel.min.coerceIn(0, n), sel.max.coerceIn(0, n), path)
                            path.offset(built.left, built.top)
                            path.transform(m)
                            drawPath(path.asComposePath(), Q.accent.copy(alpha = 0.35f))
                        }
                        if (caretState.value) {
                            val i = sel.end.coerceIn(0, if (spec.text.isEmpty()) 0 else n)
                            val line = layout.getLineForOffset(i)
                            val x = layout.getPrimaryHorizontal(i) + built.left
                            val pts = floatArrayOf(x, layout.getLineTop(line) + built.top, x, layout.getLineBottom(line) + built.top)
                            m.mapPoints(pts)
                            val a = Offset(pts[0], pts[1])
                            val b = Offset(pts[2], pts[3])
                            drawLine(Color.Black.copy(alpha = 0.6f), a, b, 4.dp.toPx())
                            drawLine(Q.accent, a, b, 2.dp.toPx())
                        }
                    }
                }
            }

            // Brush preview while a brush slider moves
            if (vm.brushPreview && vm.tool == Tool.WARP) {
                // Liquify's brush: its circle
                val r = (vm.liquifySize * s / 2f).coerceAtLeast(2f)
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Color.Black.copy(alpha = 0.5f), r + 1.5f, center, style = Stroke(1.5f))
                drawCircle(Color.White.copy(alpha = 0.8f), r, center, style = Stroke(1f))
            } else if (vm.brushPreview) {
                val b = vm.currentBrush
                val px = (b.size * s).coerceAtLeast(2f)
                val center = Offset(size.width / 2f, size.height / 2f)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val dab = com.tim.quartzeditor.engine.Dabs.get(b.tip, b.size.roundToInt().coerceAtLeast(1), b.softness, b.customTip)
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
            // (Caps Lock: a small crosshair instead of the outline)
            fun crosshair(p: Offset) {
                val a = 7.dp.toPx()
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(p.x - a, p.y), Offset(p.x + a, p.y), 3f)
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(p.x, p.y - a), Offset(p.x, p.y + a), 3f)
                drawLine(Color.White, Offset(p.x - a, p.y), Offset(p.x + a, p.y), 1.2f)
                drawLine(Color.White, Offset(p.x, p.y - a), Offset(p.x, p.y + a), 1.2f)
            }
            cursor?.let { p ->
                val r = vm.currentBrush.size * s / 2f
                if (vm.preciseCursor) crosshair(p)
                else if (r > 3f) {
                    drawCircle(Color.Black.copy(alpha = 0.5f), r + 1f, p, style = Stroke(1.5f))
                    drawCircle(Color.White.copy(alpha = 0.8f), r, p, style = Stroke(1f))
                }
            }
            // The pen hovering: where its brush (or its eraser end's) would paint, with a dot in the middle
            val hp = hover
            if (cursor == null && hp != null && (vm.penHover || hoverMouse)) {
                val eraserEnd = !hoverMouse && vm.penEraserEnd && vm.pen.eraserEnd && vm.penCanErase()
                val painting = vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER || vm.tool == Tool.RETOUCH
                if ((eraserEnd || painting) && vm.preciseCursor) {
                    crosshair(hp)
                } else if (eraserEnd || painting) {
                    val r = (if (eraserEnd) vm.eraser.size else vm.currentBrush.size) * s / 2f
                    if (r > 3f) {
                        drawCircle(Color.Black.copy(alpha = 0.45f), r + 1f, hp, style = Stroke(1.5f))
                        drawCircle(Color.White.copy(alpha = 0.75f), r, hp, style = Stroke(1f))
                    }
                    drawCircle(Color.Black.copy(alpha = 0.5f), 2.5f, hp)
                    drawCircle(Color.White, 1.5f, hp)
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

        // Long press beside the canvas: workspace color
        // Right-click on the canvas: the things most often done there
        canvasContext?.let { p ->
            Box(Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }) {
                CanvasContextMenu(vm, onDismiss = { canvasContext = null }, onWorkspaceColor = { canvasMenuAt = p })
            }
        }

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

    // Eyedropper loupe: on the screen, straight and above the finger however the view is turned
    loupe?.let { (p, c) ->
        val sizePx = with(density) { 64.dp.toPx() }
        val lift = with(density) { 90.dp.toPx() }
        val th = Math.toRadians(vm.rotation.toDouble())
        val cs = kotlin.math.cos(th).toFloat()
        val sn = kotlin.math.sin(th).toFloat()
        val vx = p.x - vm.viewW / 2f
        val vy = p.y - vm.viewH / 2f
        val qx = sw / 2f + cs * vx - sn * vy
        val qy = sh / 2f + sn * vx + cs * vy
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        (qx - sizePx / 2).coerceIn(0f, (sw - sizePx).coerceAtLeast(0f)).roundToInt(),
                        (qy - lift - sizePx / 2).coerceIn(0f, (sh - sizePx).coerceAtLeast(0f)).roundToInt(),
                    )
                }
                .size(64.dp)
                .clip(CircleShape)
                .background(if (c != null) Color(c) else Color.Transparent)
                .border(3.dp, Color.White, CircleShape)
        )
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

/** True when canvas point ([x], [y]) is inside the four-cornered shape [q]. */
private fun pointInQuad(q: List<android.graphics.PointF>, x: Float, y: Float): Boolean {
    var inside = false
    var j = q.size - 1
    for (i in q.indices) {
        val a = q[i]
        val b = q[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** The perspective crop on screen: outside dimmed, a 3×3 grid in perspective, corner and edge handles. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropQuad(q: List<android.graphics.PointF>, s: Float, ox: Float, oy: Float) {
    val p = q.map { Offset(it.x * s + ox, it.y * s + oy) }
    val outline = androidx.compose.ui.graphics.Path().apply {
        moveTo(p[0].x, p[0].y)
        for (i in 1 until 4) lineTo(p[i].x, p[i].y)
        close()
    }
    val dim = androidx.compose.ui.graphics.Path().apply {
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        addPath(outline)
    }
    drawPath(dim, Color.Black.copy(alpha = 0.55f))
    val line = 1.dp.toPx()
    // The grid follows the perspective: map the thirds of a square through the corners
    val m = android.graphics.Matrix()
    if (m.setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0, floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0, 4)) {
        for (k in 1..2) {
            val t = k / 3f
            val a = floatArrayOf(t, 0f, t, 1f, 0f, t, 1f, t)
            m.mapPoints(a)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(a[0], a[1]), Offset(a[2], a[3]), line)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(a[4], a[5]), Offset(a[6], a[7]), line)
        }
    }
    drawPath(outline, Color.Black.copy(alpha = 0.6f), style = Stroke(line * 3.5f))
    drawPath(outline, Color.White, style = Stroke(line * 1.5f))
    val hs = 8.dp.toPx()
    for (i in 0 until 4) {
        val mid = (p[i] + p[(i + 1) % 4]) / 2f
        drawCircle(Color.Black.copy(alpha = 0.6f), hs * 0.7f + line, mid)
        drawCircle(Color.White, hs * 0.7f, mid)
    }
    for (c in p) {
        drawCircle(Color.Black.copy(alpha = 0.6f), hs + line, c)
        drawCircle(Color.White, hs, c)
        drawCircle(Q.accent, hs - 2.5f * line, c)
    }
}

/** Asks for another frame (after this one is drawn) while zoomed-out tile copies are still being made. */
private val lodHandler = android.os.Handler(android.os.Looper.getMainLooper())


/** The canvas's right-click menu. */
@Composable
private fun CanvasContextMenu(vm: EditorViewModel, onDismiss: () -> Unit, onWorkspaceColor: () -> Unit) {
    val sel = vm.doc?.selection?.isEmpty == false
    val h = vm.history
    @Suppress("UNUSED_VARIABLE") val v = h.version
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        @Composable
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = {
                onDismiss()
                action()
            })
        }
        item("Undo", enabled = h.canUndo) { vm.undo() }
        item("Redo", enabled = h.canRedo) { vm.redo() }
        androidx.compose.material3.HorizontalDivider()
        if (sel) {
            item("Deselect") { vm.deselect() }
            item("Select inverse") { vm.invertSelection() }
            item("Layer via copy") { vm.selectionToLayer(cut = false) }
            item("Layer via cut") { vm.selectionToLayer(cut = true) }
        } else {
            item("Select all") { vm.selectAll() }
        }
        item("Free transform") { vm.onToolButton(Tool.TRANSFORM) }
        androidx.compose.material3.HorizontalDivider()
        item("Copy") { vm.copyPixels() }
        item("Copy merged") { vm.copyPixels(merged = true) }
        item("Paste") { vm.pastePixels() }
        androidx.compose.material3.HorizontalDivider()
        item("Fill with foreground color") { vm.fillLayer(vm.foreground) }
        item("Workspace color…") { onWorkspaceColor() }
    }
}
