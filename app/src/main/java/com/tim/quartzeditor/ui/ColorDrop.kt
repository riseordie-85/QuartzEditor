package com.tim.quartzeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.Change
import kotlin.math.roundToInt

// ColorDrop (like Procreate's): drag a color from its swatch onto the picture and let go, and the
// area there fills with it. A bar then shows how alike the colors it filled are; sliding it fills
// again with more or less.

/** A color being dragged from its swatch, and where it is (window pixels). */
class ColorDrag(val color: Int, val window: Offset)

/** The last ColorDrop: where (canvas pixels), with what, and its fill in the history (to fill again). */
class ColorDropFill(val x: Float, val y: Float, val color: Int, val change: Change)

/** Lets go of a dragged color at [window] (window pixels): fills there when it's over the picture. */
fun EditorViewModel.colorDropAt(window: Offset, color: Int) {
    val local = canvasViewFromWindow?.invoke(window) ?: return
    val (cx, cy) = screenToCanvas(local.x, local.y)
    val d = doc ?: return
    if (cx < 0f || cy < 0f || cx >= d.width || cy >= d.height) return
    colorDropFill = null
    bucketFill(cx, cy, color, colorDropTolerance) { change -> colorDropFill = ColorDropFill(cx, cy, color, change) }
}

/** Fills the last ColorDrop again with the tolerance as it is now. */
fun EditorViewModel.colorDropRefill() {
    val f = colorDropFill ?: return
    val d = doc ?: return
    if (history.peekUndo() !== f.change || stroking || busy != null) {
        colorDropFill = null
        return
    }
    history.undo(d)
    d.invalidate()
    bucketFill(f.x, f.y, f.color, colorDropTolerance) { change -> colorDropFill = ColorDropFill(f.x, f.y, f.color, change) }
}

/** The dragged color, following the finger or pen (drawn over everything; touches go through). */
@Composable
internal fun ColorDragOverlay(vm: EditorViewModel) {
    val drag = vm.colorDrag ?: return
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { coords[0] = it }) {
        val c = coords[0]?.takeIf { it.isAttached } ?: return@Canvas
        val p = c.windowToLocal(drag.window)
        // Above the finger, so it shows
        val at = Offset(p.x, p.y - 36.dp.toPx())
        drawCircle(Color.Black.copy(alpha = 0.35f), 19.dp.toPx(), at + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(drag.color), 17.dp.toPx(), at)
        drawCircle(Color.White, 17.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        // A drop at the point it lands
        drawCircle(Color(drag.color), 4.dp.toPx(), p)
        drawCircle(Color.White, 4.dp.toPx(), p, style = Stroke(1.dp.toPx()))
    }
}

/** After a ColorDrop: how alike the colors it fills are, sliding to fill again. */
@Composable
internal fun ColorDropBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val f = vm.colorDropFill ?: return
    // Anything else done since (another edit, undo, another file): the bar goes
    val version = vm.history.version
    LaunchedEffect(version, vm.doc) {
        if (vm.busy == null && vm.history.peekUndo() !== f.change) vm.colorDropFill = null
    }
    Row(
        modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(if (Q.squared) 6.dp else 22.dp))
            .background(Q.panel)
            .border(1.dp, Q.text.copy(alpha = 0.12f), RoundedCornerShape(if (Q.squared) 6.dp else 22.dp))
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(f.color)).border(1.dp, Q.text.copy(alpha = 0.5f), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("ColorDrop tolerance", color = Q.text, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            PanelSlider(
                "", vm.colorDropTolerance, 0f..1f, "${(vm.colorDropTolerance * 100).roundToInt()}%",
                onChange = { vm.colorDropTolerance = it },
                onDone = {
                    vm.prefs.edit().putFloat("dropTol", vm.colorDropTolerance).apply()
                    vm.colorDropRefill()
                },
            )
        }
        Spacer(Modifier.width(6.dp))
        SmallButton("Done") { vm.colorDropFill = null }
    }
}
