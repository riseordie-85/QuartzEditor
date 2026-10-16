package com.tim.quartzeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.CurvePoint
import com.tim.quartzeditor.engine.Curves
import com.tim.quartzeditor.engine.FxKind
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.Levels
import com.tim.quartzeditor.engine.LevelsChannel
import com.tim.quartzeditor.engine.Tone
import com.tim.quartzeditor.engine.ToneChannel
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The Levels, Curves and Gaussian Blur windows. Like Adjustments, they preview live on a pixel
// layer (Apply / Cancel) or change an adjustment layer directly.

private fun channelColor(c: ToneChannel): Color = when (c) {
    ToneChannel.RGB -> Color(0xFFBDBDBD)
    ToneChannel.RED -> Color(0xFFE57373)
    ToneChannel.GREEN -> Color(0xFF81C784)
    ToneChannel.BLUE -> Color(0xFF64B5F6)
}

/** On a pixel layer the change is about [k] (for its undo name); an adjustment layer keeps its own kind. */
private fun EditorViewModel.kindFor(fx: FxParams, k: FxKind): FxKind = if (fxTarget != null) fx.kind else k

/** Who this changes. */
@Composable
private fun AppliesTo(vm: EditorViewModel) {
    val layer = vm.doc?.active
    Text(
        if (vm.fxTarget != null) "Adjustment layer \"${layer?.props?.name}\": changes everything below it" else "Applies to: ${layer?.props?.name ?: "—"}",
        color = Q.textDim, fontSize = 12.sp, maxLines = 2,
    )
    Spacer(Modifier.height(6.dp))
}

/** The brightness spread of the layer (or, for an adjustment layer, the picture). */
@Composable
private fun rememberHistogram(vm: EditorViewModel, channel: ToneChannel): IntArray? {
    LaunchedEffect(vm.doc?.activeId, vm.doc?.active?.pixelVersion) { vm.ensureFxThumb() }
    val thumb = vm.fxThumb
    return remember(thumb, channel) { thumb?.let { Tone.histogram(it, channel) } }
}

private fun DrawScope.drawHistogram(hist: IntArray?, color: Color) {
    val h = hist ?: return
    // Ignore a few spikes (pure black or white) when scaling, so the rest shows
    val top = h.sortedArray()[252].coerceAtLeast(1).toFloat()
    val bw = size.width / 256f
    for (i in 0..255) {
        val v = (h[i] / top).coerceAtMost(1f) * size.height
        if (v > 0f) drawRect(color.copy(alpha = 0.45f), Offset(i * bw, size.height - v), Size(bw + 0.5f, v))
    }
}

// ===================================================================== Levels

@Composable
internal fun LevelsPanel(vm: EditorViewModel) {
    AppliesTo(vm)
    val fx = vm.currentFx()
    val levels = fx.levels ?: Levels()
    var ch by remember { mutableStateOf(ToneChannel.RGB) }
    val hist = rememberHistogram(vm, ch)
    val c = levels[ch]
    fun edit(nc: LevelsChannel) = vm.editFx(fx.copy(levels = levels.with(ch, nc), kind = vm.kindFor(fx, FxKind.LEVELS)))
    val done = { vm.editFxDone() }

    ChipRow(ToneChannel.entries.map { it.label }, ch.ordinal) { ch = ToneChannel.entries[it] }
    Spacer(Modifier.height(8.dp))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        drawRect(Q.field)
        drawHistogram(hist, channelColor(ch))
        // Input black, midtone and white markers
        val mid = c.inBlack + (c.inWhite - c.inBlack) * 0.5f.pow(c.gamma)
        for ((x, col) in listOf(c.inBlack to Color.Black, mid to Color.Gray, c.inWhite to Color.White)) {
            val px = x * size.width
            drawLine(col, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
        }
    }
    Spacer(Modifier.height(6.dp))
    PanelSlider("Shadows", c.inBlack * 255f, 0f..253f, "${(c.inBlack * 255).roundToInt()}",
        onChange = { v -> edit(c.copy(inBlack = (v / 255f).coerceAtMost(c.inWhite - 2f / 255f))) }, onDone = done)
    // Midtones: 0.1 … 9.99 on a log scale (1.00 in the middle)
    PanelSlider("Midtones", log10(c.gamma.coerceIn(0.1f, 9.99f)), -1f..1f, String.format(Locale.US, "%.2f", c.gamma),
        onChange = { t -> edit(c.copy(gamma = if (kotlin.math.abs(t) < 0.02f) 1f else 10f.pow(t).coerceIn(0.1f, 9.99f))) }, onDone = done,
        fromTyped = { g -> log10(g.coerceIn(0.1f, 9.99f)) })
    PanelSlider("Highlights", c.inWhite * 255f, 2f..255f, "${(c.inWhite * 255).roundToInt()}",
        onChange = { v -> edit(c.copy(inWhite = (v / 255f).coerceAtLeast(c.inBlack + 2f / 255f))) }, onDone = done)
    Text("Output", color = Q.text, fontSize = 13.sp)
    PanelSlider("Darkest", c.outBlack * 255f, 0f..255f, "${(c.outBlack * 255).roundToInt()}",
        onChange = { v -> edit(c.copy(outBlack = v / 255f)) }, onDone = done)
    PanelSlider("Lightest", c.outWhite * 255f, 0f..255f, "${(c.outWhite * 255).roundToInt()}",
        onChange = { v -> edit(c.copy(outWhite = v / 255f)) }, onDone = done)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Auto") {
            // Stretch the channel so its darkest pixels are black and its lightest white
            val h = hist ?: return@SmallButton
            val (lo, hi) = Tone.autoPoints(h)
            edit(c.copy(inBlack = lo, inWhite = hi.coerceAtLeast(lo + 2f / 255f)))
            done()
        }
        SmallButton("Reset ${ch.label}") {
            edit(LevelsChannel())
            done()
        }
    }
    FxButtons(vm)
}

// ===================================================================== Curves

private val CURVE_PRESETS: List<Pair<String, List<CurvePoint>>> = listOf(
    "Straight" to Curves.LINE,
    "More contrast" to listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.19f), CurvePoint(0.75f, 0.81f), CurvePoint(1f, 1f)),
    "Less contrast" to listOf(CurvePoint(0f, 0.04f), CurvePoint(0.25f, 0.29f), CurvePoint(0.75f, 0.71f), CurvePoint(1f, 0.96f)),
    "Lighter" to listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.62f), CurvePoint(1f, 1f)),
    "Darker" to listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.38f), CurvePoint(1f, 1f)),
    "Faded" to listOf(CurvePoint(0f, 0.12f), CurvePoint(0.5f, 0.52f), CurvePoint(1f, 0.94f)),
    "Negative" to listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f)),
)

@Composable
internal fun CurvesPanel(vm: EditorViewModel) {
    AppliesTo(vm)
    val fx = vm.currentFx()
    val curves = fx.curves ?: Curves()
    var ch by remember { mutableStateOf(ToneChannel.RGB) }
    var selected by remember { mutableIntStateOf(-1) }
    val hist = rememberHistogram(vm, ch)
    val pts = curves[ch]
    fun edit(np: List<CurvePoint>) = vm.editFx(fx.copy(curves = curves.with(ch, np), kind = vm.kindFor(fx, FxKind.CURVES)))

    ChipRow(ToneChannel.entries.map { it.label }, ch.ordinal) {
        ch = ToneChannel.entries[it]
        selected = -1
    }
    Spacer(Modifier.height(8.dp))
    CurveEditor(
        pts, hist, channelColor(ch), selected,
        onSelect = { selected = it },
        onChange = { edit(it) },
        onDone = { vm.editFxDone() },
    )
    Text("Drag a point to bend the curve. Tap to add a point; drag one off the top or bottom to remove it.", color = Q.textDim, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val canDelete = selected in 1 until pts.size - 1
        SmallButton("Delete point", enabled = canDelete) {
            if (canDelete) {
                edit(pts.filterIndexed { i, _ -> i != selected })
                vm.editFxDone()
                selected = -1
            }
        }
        SmallButton("Reset ${ch.label}") {
            edit(Curves.LINE)
            vm.editFxDone()
            selected = -1
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Presets", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    ChipRow(CURVE_PRESETS.map { it.first }, CURVE_PRESETS.indexOfFirst { it.second == pts }) { i ->
        edit(CURVE_PRESETS[i].second)
        vm.editFxDone()
        selected = -1
    }
    FxButtons(vm)
}

/**
 * A square graph of the curve over the histogram. Points can be dragged (staying between their
 * neighbors), added with a tap, and removed by dragging them out of the box.
 */
@Composable
private fun CurveEditor(
    pts: List<CurvePoint>,
    hist: IntArray?,
    color: Color,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (List<CurvePoint>) -> Unit,
    onDone: () -> Unit,
) {
    val current by rememberUpdatedState(pts)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val select by rememberUpdatedState(onSelect)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                val grab = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun toCurve(o: Offset) = CurvePoint((o.x / w).coerceIn(0f, 1f), (1f - o.y / h).coerceIn(0f, 1f))
                    var list = current.toMutableList()
                    // The point under the finger, or a new one there
                    var idx = list.indices.minByOrNull { i -> hypot(list[i].x * w - down.position.x, (1f - list[i].y) * h - down.position.y) }
                        ?.takeIf { i -> hypot(list[i].x * w - down.position.x, (1f - list[i].y) * h - down.position.y) <= grab } ?: -1
                    var changed = false
                    if (idx < 0) {
                        val p = toCurve(down.position)
                        // Not right on top of a neighbor
                        if (list.none { kotlin.math.abs(it.x - p.x) < 0.02f }) {
                            list.add(p)
                            list.sortBy { it.x }
                            idx = list.indexOf(p)
                            change(list.toList())
                            changed = true
                        }
                    }
                    select(idx)
                    var removed = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        if (idx < 0) continue
                        val inner = idx in 1 until list.size - 1
                        // Dragged well out of the box: an inner point goes away
                        val out = c.position.y < -h * 0.12f || c.position.y > h * 1.12f
                        if (inner && out) {
                            if (!removed) {
                                removed = true
                                change(list.filterIndexed { i, _ -> i != idx })
                                changed = true
                            }
                            continue
                        }
                        if (removed) {
                            removed = false
                        }
                        val p = toCurve(c.position)
                        val lo = if (idx > 0) list[idx - 1].x + 0.01f else 0f
                        val hi = if (idx < list.size - 1) list[idx + 1].x - 0.01f else 1f
                        list[idx] = CurvePoint(p.x.coerceIn(lo, hi.coerceAtLeast(lo)), p.y)
                        change(list.toList())
                        changed = true
                    }
                    if (removed) select(-1)
                    if (changed) done()
                }
            }
    ) {
        drawRect(Q.field)
        drawHistogram(hist, color)
        // Quarter grid and the straight line for reference
        val grid = Color.White.copy(alpha = 0.12f)
        for (k in 1..3) {
            val x = size.width * k / 4f
            val y = size.height * k / 4f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, size.height), Offset(size.width, 0f), 1f)
        val table = Tone.curveTable(pts)
        val path = Path()
        for (i in 0..255) {
            val x = i / 255f * size.width
            val y = (1f - table[i]) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (color == Color(0xFFBDBDBD)) Color.White else color, style = Stroke(2.dp.toPx()))
        pts.forEachIndexed { i, p ->
            val o = Offset(p.x * size.width, (1f - p.y) * size.height)
            drawCircle(Color.Black.copy(alpha = 0.6f), 7.dp.toPx(), o)
            drawCircle(if (i == selected) Q.accent else Color.White, 5.dp.toPx(), o)
        }
    }
}

// ===================================================================== Gaussian Blur

@Composable
internal fun BlurPanel(vm: EditorViewModel) {
    AppliesTo(vm)
    val fx = vm.currentFx()
    // 0 … 250 px, finer at the small end
    val t = sqrt((fx.blur / 250f).coerceIn(0f, 1f))
    PanelSlider("Radius", t, 0f..1f, if (fx.blur < 10f) String.format(Locale.US, "%.1f px", fx.blur) else "${fx.blur.roundToInt()} px",
        onChange = { v -> vm.editFx(fx.copy(blur = (v * v * 250f).let { if (it < 0.3f) 0f else it }, kind = vm.kindFor(fx, FxKind.BLUR))) },
        onDone = { vm.editFxDone() },
        fromTyped = { px -> kotlin.math.sqrt(px.coerceIn(0f, 250f) / 250f) })
    Row {
        listOf(2f, 5f, 10f, 25f, 50f).forEach { r ->
            SmallButton("${r.roundToInt()}", primary = fx.blur == r) {
                vm.editFx(fx.copy(blur = r, kind = vm.kindFor(fx, FxKind.BLUR)))
                vm.editFxDone()
            }
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (vm.fxTarget != null) "Blurs everything below this layer. Paint its mask black where things should stay sharp."
        else "Blurs this layer (only the selected part if something is selected).",
        color = Q.textDim, fontSize = 12.sp,
    )
    FxButtons(vm)
}
