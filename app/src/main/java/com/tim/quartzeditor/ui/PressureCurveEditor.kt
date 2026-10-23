package com.tim.quartzeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The pen's pressure curve, drawn: how hard you press (across) to how much it does (up). Drag the
 * two handles to shape it, or pick a ready-made one; press the strip below with the pen to try it.
 */
@Composable
internal fun PressureCurveEditor(vm: EditorViewModel) {
    val curve = vm.penCurve
    ChipRow(PenCurve.entries.map { it.label }, curve.preset?.ordinal ?: -1) {
        vm.penCurve = PressureCurve.of(PenCurve.entries[it])
        vm.savePenPrefs()
    }
    Spacer(Modifier.height(10.dp))
    // Which handle is being dragged (0 or 1), and the pen's latest pressure on the test strip
    var dragging by remember { mutableStateOf(-1) }
    var testRaw by remember { mutableFloatStateOf(-1f) }
    val accent = Q.accent
    val text = Q.text
    val dim = Q.textDim
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Q.panelDark)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pad = 14.dp.toPx()
                    val w = size.width - pad * 2
                    val h = size.height - pad * 2
                    fun toCurve(o: Offset) = Pair(((o.x - pad) / w).coerceIn(0f, 1f), (1f - (o.y - pad) / h).coerceIn(0f, 1f))
                    val hs = vm.penCurve.handles
                    val d0 = hypot(pad + hs[0] * w - down.position.x, pad + (1 - hs[1]) * h - down.position.y)
                    val d1 = hypot(pad + hs[2] * w - down.position.x, pad + (1 - hs[3]) * h - down.position.y)
                    // Only near a handle: elsewhere the touch scrolls the page
                    if (minOf(d0, d1) > 32.dp.toPx()) return@awaitEachGesture
                    dragging = if (d0 <= d1) 0 else 1
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val (x, y) = toCurve(c.position)
                        val cur = vm.penCurve.handles.toMutableList()
                        cur[dragging * 2] = (x * 100).roundToInt() / 100f
                        cur[dragging * 2 + 1] = (y * 100).roundToInt() / 100f
                        vm.penCurve = PressureCurve(cur)
                    }
                    dragging = -1
                    vm.savePenPrefs()
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val pad = 14.dp.toPx()
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            fun pt(x: Float, y: Float) = Offset(pad + x * w, pad + (1 - y) * h)
            // Grid in quarters, and the straight (normal) line
            for (i in 0..4) {
                val f = i / 4f
                drawLine(dim.copy(alpha = 0.25f), pt(f, 0f), pt(f, 1f), 1f)
                drawLine(dim.copy(alpha = 0.25f), pt(0f, f), pt(1f, f), 1f)
            }
            drawLine(dim.copy(alpha = 0.5f), pt(0f, 0f), pt(1f, 1f), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val hs = curve.handles
            val p = Path().apply {
                moveTo(pt(0f, 0f).x, pt(0f, 0f).y)
                val a = pt(hs[0], hs[1])
                val b = pt(hs[2], hs[3])
                val e = pt(1f, 1f)
                cubicTo(a.x, a.y, b.x, b.y, e.x, e.y)
            }
            drawLine(accent.copy(alpha = 0.6f), pt(0f, 0f), pt(hs[0], hs[1]), 2f)
            drawLine(accent.copy(alpha = 0.6f), pt(1f, 1f), pt(hs[2], hs[3]), 2f)
            drawPath(p, text, style = Stroke(3.dp.toPx()))
            for (i in 0..1) {
                val c = pt(hs[i * 2], hs[i * 2 + 1])
                drawCircle(accent, if (dragging == i) 11.dp.toPx() else 8.dp.toPx(), c)
                drawCircle(text, 3.dp.toPx(), c)
            }
            // Where the pen is pressing on the test strip
            if (testRaw >= 0f) drawCircle(text, 6.dp.toPx(), pt(testRaw, curve(testRaw)), style = Stroke(2.dp.toPx()))
        }
    }
    Spacer(Modifier.height(8.dp))
    // Test strip: press with the pen to see where it lands on the curve
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Q.panelDark)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    testRaw = down.pressure.coerceIn(0f, 1f)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        testRaw = c.pressure.coerceIn(0f, 1f)
                    }
                    testRaw = -1f
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (testRaw >= 0f) {
            Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(curve(testRaw).coerceAtLeast(0.01f)).background(accent.copy(alpha = 0.5f)))
            }
            Text(
                "Pressed ${(testRaw * 100).roundToInt()}%  →  ${(curve(testRaw) * 100).roundToInt()}%",
                color = text, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Text("Press here with the pen to try it", color = dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
