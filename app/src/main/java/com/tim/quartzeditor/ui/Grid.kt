package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// The alignment grid: evenly spaced lines over the canvas, starting at its top-left corner, with
// optional fainter subdivisions. Moving, shapes and pen points snap to it while it's shown.

private val GRID_COLORS = listOf(0xFF00B4FF.toInt(), 0xFFFF2DAA.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF7CFC00.toInt())
private val GRID_SPACINGS = listOf(16f, 25f, 32f, 50f, 64f, 100f, 128f, 200f, 256f)

private fun EditorViewModel.saveGrid() {
    prefs.edit()
        .putBoolean("grid", showGrid)
        .putFloat("gridSpacing", gridSpacing)
        .putInt("gridSubdiv", gridSubdivisions)
        .putInt("gridColor", gridColor)
        .putBoolean("gridSnap", gridSnap)
        .apply()
}

fun EditorViewModel.toggleGrid() {
    showGrid = !showGrid
    saveGrid()
}

/** The grid spacing between snap points (the subdivisions when there are some). */
private val EditorViewModel.gridStep: Float get() = gridSpacing / gridSubdivisions.coerceAtLeast(1)

/** Snapping to the grid is on (it's shown and its snapping is switched on). */
val EditorViewModel.snapsToGrid: Boolean get() = showGrid && gridSnap

/** The nearest grid line to [v] (canvas pixels). */
fun EditorViewModel.nearestGridLine(v: Float): Float {
    val s = gridStep.coerceAtLeast(1f)
    return (v / s).roundToInt() * s
}

/** Snaps a canvas point to the grid's crossings (each axis on its own, within [radius]). */
fun EditorViewModel.snapPoint(x: Float, y: Float, radius: Float): Pair<Float, Float> {
    if (!snapsToGrid || radius <= 0f) return x to y
    val gx = nearestGridLine(x)
    val gy = nearestGridLine(y)
    return (if (abs(gx - x) <= radius) gx else x) to (if (abs(gy - y) <= radius) gy else y)
}

/** Draws the grid over the canvas (screen space; [s] scale, ([ox], [oy]) where the canvas starts). */
fun DrawScope.drawGrid(vm: EditorViewModel, s: Float, ox: Float, oy: Float, docW: Int, docH: Int) {
    if (!vm.showGrid) return
    val left = ox
    val top = oy
    val right = ox + docW * s
    val bottom = oy + docH * s
    val visL = maxOf(left, 0f)
    val visR = minOf(right, size.width)
    val visT = maxOf(top, 0f)
    val visB = minOf(bottom, size.height)
    if (visL >= visR || visT >= visB) return
    val base = Color(vm.gridColor)
    val major = vm.gridSpacing.coerceAtLeast(1f)
    val sub = vm.gridSubdivisions.coerceAtLeast(1)
    // Lines closer than a few screen pixels would just tint the picture: leave them out
    val minGap = 6.dp.toPx()
    val stepMinor = major / sub
    val drawMinor = sub > 1 && stepMinor * s >= minGap * 1.5f
    var stepMajor = major
    while (stepMajor * s < minGap) stepMajor *= 2f
    val step = if (drawMinor) stepMinor else stepMajor
    val w = 1f
    fun lines(from: Float, to: Float, canvasMin: Float, canvasSize: Int, horizontal: Boolean) {
        val first = ceil(((from - canvasMin) / s) / step).toLong()
        val last = floor(((to - canvasMin) / s) / step).toLong()
        if (last - first > 2000) return
        for (i in first..last) {
            val v = i * step
            if (v < 0f || v > canvasSize) continue
            val isMajor = abs(v / stepMajor - (v / stepMajor).roundToInt()) < 1e-3f
            val c = base.copy(alpha = if (isMajor) 0.6f else 0.25f)
            // On a whole screen pixel, so lines are crisp and all the same
            val p = floor(canvasMin + v * s) + 0.5f
            if (horizontal) drawLine(c, Offset(visL, p), Offset(visR, p), w) else drawLine(c, Offset(p, visT), Offset(p, visB), w)
        }
    }
    lines(visL, visR, left, docW, horizontal = false)
    lines(visT, visB, top, docH, horizontal = true)
}

/** Grid settings: show, spacing, subdivisions, color, snapping. */
@Composable
fun GridDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(18.dp)
        ) {
            Text("Grid", color = Q.text, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            ToggleRow("Show grid", vm.showGrid) {
                vm.showGrid = it
                vm.saveGrid()
            }
            ToggleRow("Snap to grid", vm.gridSnap) {
                vm.gridSnap = it
                vm.saveGrid()
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Line every", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                PanelField(
                    vm.gridSpacing.roundToInt().toString(),
                    onCommit = { t ->
                        t.toFloatOrNull()?.let {
                            vm.gridSpacing = it.coerceIn(2f, 4096f)
                            vm.saveGrid()
                        }
                    },
                    numeric = true,
                    modifier = Modifier.width(80.dp),
                )
                Text(" px", color = Q.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            ChipRow(GRID_SPACINGS.map { it.roundToInt().toString() }, GRID_SPACINGS.indexOf(vm.gridSpacing)) { i ->
                vm.gridSpacing = GRID_SPACINGS[i]
                vm.saveGrid()
            }
            val d = vm.doc
            if (d != null) {
                Spacer(Modifier.height(6.dp))
                Text("Or split the canvas into equal parts", color = Q.textDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                val parts = listOf(2, 3, 4, 6, 8, 10, 12)
                ChipRow(parts.map { "$it" }, -1) { i ->
                    // Even across the canvas's longer side
                    vm.gridSpacing = maxOf(d.width, d.height).toFloat() / parts[i]
                    vm.saveGrid()
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Subdivisions", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            val subs = listOf(1, 2, 4, 5, 8)
            ChipRow(subs.map { if (it == 1) "None" else "$it" }, subs.indexOf(vm.gridSubdivisions)) { i ->
                vm.gridSubdivisions = subs[i]
                vm.saveGrid()
            }
            Spacer(Modifier.height(10.dp))
            Text("Color", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GRID_COLORS.forEach { c ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (c == vm.gridColor) 3.dp else 1.dp, if (c == vm.gridColor) Q.accent else Q.divider, CircleShape)
                            .clickable {
                                vm.gridColor = c
                                vm.saveGrid()
                            }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row {
                Spacer(Modifier.weight(1f))
                SmallButton("Done", primary = true) { onDismiss() }
            }
        }
    }
}
