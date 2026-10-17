package com.tim.quartzeditor.ui

import android.graphics.Paint
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// Rulers along the top and left of the picture, in canvas pixels. Drag out of one to make a guide
// (top ruler: a horizontal guide; left ruler: a vertical one); things snap to guides. With the
// Move or Pan tool, drag a guide to move it, or drag it off the picture to remove it; Edit guides
// lists them with typed positions.

val RULER = 20.dp

@Composable
fun Rulers(vm: EditorViewModel, modifier: Modifier = Modifier) {
    if (!vm.showRulers || vm.doc == null) return
    val density = LocalDensity.current
    val thick = with(density) { RULER.toPx() }
    Box(modifier.fillMaxSize()) {
        // Top: horizontal guides come out of it
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(RULER)
                .background(Q.toolbar.copy(alpha = 0.94f))
                .pointerInput(Unit) { guideDrag(vm, vertical = false, baseX = 0f, baseY = 0f, thick = thick) },
        ) { drawRuler(vm, horizontal = true, offsetPx = 0f, startPx = thick) }
        // Left: vertical guides
        Canvas(
            Modifier
                .padding(top = RULER)
                .width(RULER)
                .fillMaxHeight()
                .background(Q.toolbar.copy(alpha = 0.94f))
                .pointerInput(Unit) { guideDrag(vm, vertical = true, baseX = 0f, baseY = thick, thick = thick) },
        ) { drawRuler(vm, horizontal = false, offsetPx = thick, startPx = 0f) }
        // The corner
        Box(Modifier.size(RULER).background(Q.toolbar))
    }
}

/** Dragging out of a ruler: a new guide follows the finger and is placed where it's let go on the picture. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.guideDrag(vm: EditorViewModel, vertical: Boolean, baseX: Float, baseY: Float, thick: Float) {
    var last = Offset.Zero
    fun canvasPos(p: Offset): Float = if (vertical) (p.x + baseX - vm.offsetX) / vm.scale else (p.y + baseY - vm.offsetY) / vm.scale
    detectDragGestures(
        onDragStart = { p ->
            last = p
            vm.prepareGuideSnap()
            vm.guideDraft = vertical to canvasPos(p).roundToInt().toFloat()
        },
        onDragEnd = {
            val d = vm.doc
            val draft = vm.guideDraft
            vm.guideDraft = null
            // Let go back over the ruler: no guide
            val overRuler = if (vertical) last.x + baseX < thick else last.y + baseY < thick
            if (d != null && draft != null && !overRuler) vm.addGuide(draft.first, draft.second)
        },
        onDragCancel = { vm.guideDraft = null },
    ) { change, _ ->
        change.consume()
        last = change.position
        // Snaps to the canvas's and the active layer's edges and middle
        val v = vm.snapGuide(vertical, canvasPos(change.position), 10.dp.toPx() / vm.scale)
        vm.guideDraft = vertical to v
    }
}

/** Tick marks and numbers (canvas pixels) along a ruler. */
private fun DrawScope.drawRuler(vm: EditorViewModel, horizontal: Boolean, offsetPx: Float, startPx: Float) {
    val s = vm.scale
    if (s <= 0f) return
    val origin = if (horizontal) vm.offsetX else vm.offsetY - offsetPx
    val length = if (horizontal) size.width else size.height
    // A labeled tick every 1, 2 or 5 × 10ⁿ canvas pixels, at least ~64 dp apart
    val minGap = 64.dp.toPx() / s
    val pow10 = 10.0.pow(floor(log10(minGap.toDouble()))).toFloat()
    val step = listOf(1f, 2f, 5f, 10f).map { it * pow10 }.first { it >= minGap }
    val minor = step / (if (step * s / 10f >= 5.dp.toPx()) 10f else if (step * s / 5f >= 5.dp.toPx()) 5f else 2f)
    val color = Q.textDim
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Q.text.toArgb()
        textSize = 9.sp.toPx()
    }
    val first = floor((startPx - origin) / s / minor).toLong()
    val last = ceil((length - origin) / s / minor).toLong()
    if (last - first > 4000) return
    val thick = if (horizontal) size.height else size.width
    for (i in first..last) {
        val v = i * minor
        val p = origin + v * s
        if (p < startPx) continue
        val major = kotlin.math.abs(v / step - (v / step).roundToInt()) < 1e-3f
        val tick = if (major) thick * 0.6f else thick * 0.25f
        if (horizontal) drawLine(color, Offset(p, thick - tick), Offset(p, thick), 1f)
        else drawLine(color, Offset(thick - tick, p), Offset(thick, p), 1f)
        if (major) {
            val label = v.roundToInt().toString()
            drawContext.canvas.nativeCanvas.apply {
                if (horizontal) {
                    drawText(label, p + 2.dp.toPx(), 9.sp.toPx(), text)
                } else {
                    save()
                    rotate(-90f, 9.sp.toPx(), p - 2.dp.toPx())
                    drawText(label, 9.sp.toPx(), p - 2.dp.toPx(), text)
                    restore()
                }
            }
        }
    }
    // Where the guide being dragged is
    vm.guideDraft?.let { (vertical, at) ->
        if (vertical == !horizontal) {
            val p = origin + at * s
            drawLine(androidx.compose.ui.graphics.Color(GUIDE_COLOR), if (horizontal) Offset(p, 0f) else Offset(0f, p), if (horizontal) Offset(p, thick) else Offset(thick, p), 2f)
        }
    }
}

/** Guides' color (Photoshop's cyan). */
const val GUIDE_COLOR = 0xFF00E5FF

/** Works out (in the background) the active layer's box, for a guide being dragged to snap to. */
fun EditorViewModel.prepareGuideSnap() {
    guideSnapJob?.cancel()
    guideSnapBox = null
    val l = doc?.active?.takeIf { it.hasContent && it.props.visible } ?: return
    val tiles = HashMap(l.tiles.tiles)
    val x = l.x
    val y = l.y
    guideSnapJob = viewModelScope.launch {
        val b = withContext(Dispatchers.Default) { com.tim.quartzeditor.engine.Transformer.contentBounds(tiles) }
        guideSnapBox = b?.let { android.graphics.RectF(it).apply { offset(x.toFloat(), y.toFloat()) } }
    }
}

/**
 * Where a guide dragged to [v] (canvas pixels) goes: onto the canvas's edges or middle, or the
 * active layer's, when within [radius]; otherwise the nearest whole pixel. Snapping off: no snapping.
 */
fun EditorViewModel.snapGuide(vertical: Boolean, v: Float, radius: Float): Float {
    val d = doc ?: return v.roundToInt().toFloat()
    if (!snapOn) return v.roundToInt().toFloat()
    val size = if (vertical) d.width.toFloat() else d.height.toFloat()
    val targets = arrayListOf(0f, size / 2f, size)
    guideSnapBox?.let { b ->
        if (vertical) targets.addAll(listOf(b.left, b.centerX(), b.right)) else targets.addAll(listOf(b.top, b.centerY(), b.bottom))
    }
    var best = v.roundToInt().toFloat()
    var bestD = radius
    for (t in targets) {
        val dd = kotlin.math.abs(v - t)
        if (dd <= bestD) {
            bestD = dd
            best = t
        }
    }
    return best
}

/** Adds a guide (vertical at x, or horizontal at y), as one undo step. */
fun EditorViewModel.addGuide(vertical: Boolean, at: Float) {
    val d = doc ?: return
    val size = if (vertical) d.width else d.height
    if (at < 0f || at > size) return
    stackEdit("New guide") {
        if (vertical) d.guidesX.add(at) else d.guidesY.add(at)
    }
    if (!showGuides) {
        showGuides = true
        prefs.edit().putBoolean("guides", true).apply()
    }
    // The first few times: how to change it later
    val told = prefs.getInt("guideHint", 0)
    if (told < 3) {
        prefs.edit().putInt("guideHint", told + 1).apply()
        toast(if (guidesLocked) "Guides are locked (unlock them in Menu › View › Rulers and guides)" else "Move or Pan tool: drag a guide to move it, or off the picture to remove it. Menu › View › Rulers and guides › Edit guides to type positions")
    }
}

/** Sets guide [index] to [to] exactly (from Edit guides), as one undo step. */
fun EditorViewModel.setGuide(vertical: Boolean, index: Int, to: Float) {
    val d = doc ?: return
    val list = if (vertical) d.guidesX else d.guidesY
    if (index !in list.indices) return
    val size = if (vertical) d.width else d.height
    val v = to.coerceIn(0f, size.toFloat())
    if (list[index] == v) return
    stackEdit("Move guide") { list[index] = v }
}

/** Removes guide [index], as one undo step. */
fun EditorViewModel.removeGuide(vertical: Boolean, index: Int) {
    val d = doc ?: return
    val list = if (vertical) d.guidesX else d.guidesY
    if (index !in list.indices) return
    stackEdit("Remove guide") { list.removeAt(index) }
}

/** Moves guide [index] (or removes it when [to] is off the canvas). */
fun EditorViewModel.moveGuide(vertical: Boolean, index: Int, to: Float) {
    val d = doc ?: return
    val list = if (vertical) d.guidesX else d.guidesY
    if (index !in list.indices) return
    val size = if (vertical) d.width else d.height
    if (to < 0f || to > size) {
        stackEdit("Remove guide") { list.removeAt(index) }
        return
    }
    if (list[index] == to) return
    stackEdit("Move guide") { list[index] = to }
}

fun EditorViewModel.clearGuides() {
    val d = doc ?: return
    if (d.guidesX.isEmpty() && d.guidesY.isEmpty()) return
    stackEdit("Clear guides") {
        d.guidesX.clear()
        d.guidesY.clear()
    }
}

fun EditorViewModel.toggleRulers() {
    showRulers = !showRulers
    guideDraft = null
    prefs.edit().putBoolean("rulers", showRulers).apply()
}

fun EditorViewModel.toggleGuides() {
    showGuides = !showGuides
    prefs.edit().putBoolean("guides", showGuides).apply()
}

fun EditorViewModel.toggleGuidesLocked() {
    guidesLocked = !guidesLocked
    prefs.edit().putBoolean("guidesLocked", guidesLocked).apply()
    toast(if (guidesLocked) "Guides locked" else "Guides unlocked")
}

/** Draws the guides (and one being dragged) over the canvas (screen space). */
fun DrawScope.drawGuides(vm: EditorViewModel, s: Float, ox: Float, oy: Float, docW: Int, docH: Int, moving: Pair<Boolean, Int>?) {
    val d = vm.doc ?: return
    val c = androidx.compose.ui.graphics.Color(GUIDE_COLOR)
    val w = 1.dp.toPx()
    val left = ox
    val top = oy
    val right = ox + docW * s
    val bottom = oy + docH * s
    if (vm.showGuides) {
        d.guidesX.forEachIndexed { i, x ->
            if (moving?.first == true && moving.second == i) return@forEachIndexed
            val p = floor(ox + x * s) + 0.5f
            drawLine(c, Offset(p, top), Offset(p, bottom), w)
        }
        d.guidesY.forEachIndexed { i, y ->
            if (moving?.first == false && moving.second == i) return@forEachIndexed
            val p = floor(oy + y * s) + 0.5f
            drawLine(c, Offset(left, p), Offset(right, p), w)
        }
    }
    vm.guideDraft?.let { (vertical, at) ->
        val p = floor((if (vertical) ox else oy) + at * s) + 0.5f
        if (vertical) drawLine(c, Offset(p, 0f), Offset(p, size.height), w * 1.5f)
        else drawLine(c, Offset(0f, p), Offset(size.width, p), w * 1.5f)
        val label = "${at.roundToInt()} px"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 12.sp.toPx()
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        }
        drawContext.canvas.nativeCanvas.drawText(label, if (vertical) p + 6.dp.toPx() else 30.dp.toPx(), if (vertical) 40.dp.toPx() else p - 6.dp.toPx(), paint)
    }
}

/** New guide at a typed position. */
@Composable
fun NewGuideDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val d = vm.doc ?: return
    var vertical by remember { mutableStateOf(true) }
    // What the box starts with (changes with the direction), and what's typed
    var initial by remember { mutableStateOf("${d.width / 2}") }
    var at by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(18.dp),
        ) {
            Text("New guide", color = Q.text, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            ChipRow(listOf("Vertical", "Horizontal"), if (vertical) 0 else 1) { i ->
                vertical = i == 0
                initial = "${(if (vertical) d.width else d.height) / 2}"
                at = initial
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (vertical) "From the left" else "From the top", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                androidx.compose.runtime.key(vertical) {
                    PanelField(initial, onCommit = { at = it }, numeric = false, modifier = Modifier.width(90.dp), onType = { at = it })
                }
                Text(" px", color = Q.textDim, fontSize = 13.sp)
            }
            Text("Or a percent, like 50%", color = Q.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Row {
                Spacer(Modifier.weight(1f))
                SmallButton("Cancel") { onDismiss() }
                Spacer(Modifier.width(8.dp))
                SmallButton("Add", primary = true) {
                    val size = if (vertical) d.width else d.height
                    val t = at.trim()
                    val v = if (t.endsWith("%")) t.dropLast(1).trim().toFloatOrNull()?.let { it / 100f * size } else t.toFloatOrNull()
                    if (v != null && v.isFinite()) vm.addGuide(vertical, v.roundToInt().toFloat().coerceIn(0f, size.toFloat()))
                    onDismiss()
                }
            }
        }
    }
}

/** Every guide with its position: type a new one, or remove it. */
@Composable
fun EditGuidesDialog(vm: EditorViewModel, onDismiss: () -> Unit, onNew: () -> Unit) {
    val d = vm.doc ?: return
    @Suppress("UNUSED_VARIABLE") val v = vm.history.version
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(18.dp),
        ) {
            Text("Guides", color = Q.text, fontSize = 18.sp)
            if (vm.guidesLocked) Text("Locked: they can't be dragged on the picture (typing here still works).", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (d.guidesX.isEmpty() && d.guidesY.isEmpty()) {
                    Text("No guides yet. Drag one out of a ruler, or tap New guide.", color = Q.textDim, fontSize = 13.sp)
                }
                for (vertical in listOf(true, false)) {
                    val list = if (vertical) d.guidesX else d.guidesY
                    list.forEachIndexed { i, at ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(if (vertical) "Vertical, from the left" else "Horizontal, from the top", color = Q.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            // A new box when the guide changes (moved, undone, removed above it)
                            androidx.compose.runtime.key(vertical, i, at) {
                                PanelField("${at.roundToInt()}", onCommit = { t ->
                                    t.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.let { vm.setGuide(vertical, i, it.roundToInt().toFloat()) }
                                }, modifier = Modifier.width(80.dp))
                            }
                            Text(" px", color = Q.textDim, fontSize = 13.sp)
                            androidx.compose.material3.IconButton(onClick = { vm.removeGuide(vertical, i) }) {
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Delete, contentDescription = "Remove guide", tint = Q.textDim)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Clear all") { vm.clearGuides() }
                Spacer(Modifier.weight(1f))
                SmallButton("New guide") { onNew() }
                Spacer(Modifier.width(8.dp))
                SmallButton("Done", primary = true) { onDismiss() }
            }
        }
    }
}
