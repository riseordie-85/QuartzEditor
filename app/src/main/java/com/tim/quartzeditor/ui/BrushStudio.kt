package com.tim.quartzeditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.quartzeditor.engine.BrushSizeScale
import com.tim.quartzeditor.engine.BrushStyle
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.Grain
import com.tim.quartzeditor.engine.Grains
import com.tim.quartzeditor.engine.TiltMode
import kotlin.math.roundToInt

// Brush Studio: every setting of the brush in use, with a sample stroke that follows. Changes apply
// to the brush straight away; save them as a new brush in your library, or into your brush they
// came from.

private enum class StudioPage(val label: String) { BASICS("Basics"), STROKE("Stroke"), SHAPE("Shape"), GRAIN("Grain"), PEN("Pen") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrushStudio(vm: EditorViewModel) {
    val forTool = vm.brushStudioFor ?: return
    val b = vm.brushOf(forTool)
    val preset = vm.brushPreset(b.presetId)
    var page by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    fun set(change: (BrushSettings) -> BrushSettings) = vm.updateBrush(forTool) { change(it) }
    fun style(change: (BrushStyle) -> BrushStyle) = set { it.copy(style = change(it.style)) }
    fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    Dialog(onDismissRequest = { vm.brushStudioFor = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .clip(RoundedCornerShape(Q.corner(22.dp)))
                    .background(Q.panel)
                    .border(1.dp, Q.divider, RoundedCornerShape(Q.corner(22.dp)))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Brush Studio · " + (preset?.name ?: "Custom brush") + (if (vm.brushChanged(b)) " (changed)" else "") +
                            (if (forTool == Tool.ERASER) " · eraser" else ""),
                        color = Q.text, fontSize = 18.sp, modifier = Modifier.weight(1f),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { vm.brushStudioFor = null },
                    ) { Icon(Icons.Filled.Close, contentDescription = "Close Brush Studio", tint = Q.text) }
                }
                Spacer(Modifier.height(8.dp))
                // The sample stroke, as the brush is now
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Q.panelDark).padding(8.dp)) {
                    BrushStrokeImage(vm, b, Modifier.fillMaxWidth(), w = 900, h = 200, cache = false)
                }
                Spacer(Modifier.height(10.dp))
                ChipRow(StudioPage.entries.map { it.label }, page) { page = it }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (StudioPage.entries[page]) {
                        StudioPage.BASICS -> {
                            Text("Tip", color = Q.textDim, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                // (The Imported tip only for a brush that has one)
                                BrushTip.entries.filter { it != BrushTip.SAMPLED || b.customTip != null }.forEach { t ->
                                    val img = remember(t, b.customTip) { if (t == BrushTip.SAMPLED) customTipIcon(b.customTip) else tipIcon(t) }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (t == b.tip) Q.rowSelected else Q.field)
                                            .clickable { set { it.copy(tip = t) } }
                                            .padding(6.dp),
                                    ) {
                                        Image(img, contentDescription = null, modifier = Modifier.size(40.dp))
                                        Text(t.label, color = Q.text, fontSize = 11.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            PanelSlider("Size", BrushSizeScale.toT(b.size), 0f..1f, "${b.size.roundToInt()} px",
                                onChange = { t -> set { it.copy(size = BrushSizeScale.toPx(t).roundToInt().toFloat().coerceAtLeast(1f)) } },
                                fromTyped = { px -> BrushSizeScale.toT(px) })
                            PanelSlider("Opacity", b.opacity, 0.01f..1f, pct(b.opacity), onChange = { v -> set { it.copy(opacity = v) } })
                            PanelSlider("Flow (how much each dab adds)", b.style.flow, 0.01f..1f, pct(b.style.flow), onChange = { v -> style { it.copy(flow = v) } })
                            PanelSlider("Softness", b.softness, 0f..1f, pct(b.softness), onChange = { v -> set { it.copy(softness = v) } })
                        }
                        StudioPage.STROKE -> {
                            val own = b.style.spacing == null
                            ToggleRow("The tip's own spacing", own) { on -> style { it.copy(spacing = if (on) null else b.tip.spacing) } }
                            if (!own) PanelSlider("Spacing (of the size)", b.style.spacing ?: b.tip.spacing, 0.02f..2f, pct(b.style.spacing ?: b.tip.spacing), onChange = { v -> style { it.copy(spacing = v) } })
                            PanelSlider("Smoothing", b.smoothing, 0f..1f, pct(b.smoothing), onChange = { v -> set { it.copy(smoothing = v) } })
                            PanelSlider("Taper at the start", b.taperStart, 0f..1f, pct(b.taperStart), onChange = { v -> set { it.copy(taperStart = v) } })
                            PanelSlider("Taper at the end", b.taperEnd, 0f..1f, pct(b.taperEnd), onChange = { v -> set { it.copy(taperEnd = v) } })
                        }
                        StudioPage.SHAPE -> {
                            PanelSlider("Angle", b.style.angle, 0f..180f, "${b.style.angle.roundToInt()}°", onChange = { v -> style { it.copy(angle = v) } })
                            PanelSlider("Roundness (flatter like a chisel)", b.style.roundness, 0.05f..1f, pct(b.style.roundness), onChange = { v -> style { it.copy(roundness = v) } })
                            ToggleRow("Turns with the stroke", b.style.followStroke) { on -> style { it.copy(followStroke = on) } }
                            Spacer(Modifier.height(6.dp))
                            PanelSlider("Size jitter", b.style.sizeJitter, 0f..1f, pct(b.style.sizeJitter), onChange = { v -> style { it.copy(sizeJitter = v) } })
                            PanelSlider("Angle jitter", b.style.angleJitter, 0f..1f, pct(b.style.angleJitter), onChange = { v -> style { it.copy(angleJitter = v) } })
                            PanelSlider("Scatter", b.style.scatter, 0f..2f, pct(b.style.scatter), onChange = { v -> style { it.copy(scatter = v) } })
                        }
                        StudioPage.GRAIN -> {
                            val kinds = listOf<Grain?>(null) + Grain.entries
                            ChipRow(kinds.map { it?.label ?: "None" }, kinds.indexOf(b.style.grain)) { i -> style { it.copy(grain = kinds[i]) } }
                            val g = b.style.grain
                            if (g != null) {
                                Spacer(Modifier.height(8.dp))
                                val tile = remember(g, b.style.grainDepth) {
                                    val t = Grains.tile(g, (b.style.grainDepth * 20).roundToInt())
                                    android.graphics.Bitmap.createBitmap(t.width, t.height, android.graphics.Bitmap.Config.ARGB_8888).also { out ->
                                        val c = android.graphics.Canvas(out)
                                        c.drawColor(android.graphics.Color.WHITE)
                                        c.drawBitmap(t, 0f, 0f, android.graphics.Paint().apply { color = android.graphics.Color.BLACK })
                                    }.asImageBitmap()
                                }
                                Image(tile, contentDescription = "The grain (dark is where the stroke shows)", modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.height(8.dp))
                                PanelSlider("Depth (how much it shows)", b.style.grainDepth, 0f..1f, pct(b.style.grainDepth), onChange = { v -> style { it.copy(grainDepth = v) } })
                                PanelSlider("Scale", b.style.grainScale, 0.25f..4f, String.format(java.util.Locale.US, "%.2f×", b.style.grainScale), onChange = { v -> style { it.copy(grainScale = v) } })
                                Text("The grain stays put on the canvas, like paper's tooth: strokes over it catch the same bumps.", color = Q.textDim, fontSize = 12.sp)
                            }
                        }
                        StudioPage.PEN -> {
                            Text("Pressure changes", color = Q.textDim, fontSize = 12.sp)
                            ToggleRow("Size", b.pressureSize) { on -> set { it.copy(pressureSize = on) } }
                            ToggleRow("Opacity", b.pressureOpacity) { on -> set { it.copy(pressureOpacity = on) } }
                            ToggleRow("Flow", b.style.pressureFlow) { on -> style { it.copy(pressureFlow = on) } }
                            Spacer(Modifier.height(8.dp))
                            Text("Tilting the pen", color = Q.textDim, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            ChipRow(TiltMode.entries.map { it.label }, b.style.tiltMode.ordinal) { i -> style { it.copy(tiltMode = TiltMode.entries[i]) } }
                            Text(
                                when (b.style.tiltMode) {
                                    TiltMode.NONE -> "Tilt does nothing."
                                    TiltMode.STRETCH -> "The dab stretches along the way the pen leans, like a marker on its side."
                                    TiltMode.SHADE -> "Leaning the pen makes the dab bigger and lighter, like shading with the side of a pencil."
                                },
                                color = Q.textDim, fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("The pressure curve, smallest size and turning tilt off for every brush are in Settings → Pen.", color = Q.textDim, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("Save as new brush…") { naming = true }
                    if (preset != null && !preset.builtIn && vm.brushChanged(b)) {
                        SmallButton("Update \"${preset.name}\"") { vm.updateUserBrush(preset.id, b) }
                    }
                    if (preset != null && vm.brushChanged(b)) {
                        SmallButton("Back to \"${preset.name}\"") { vm.chooseBrushPreset(forTool, preset) }
                    }
                    Spacer(Modifier.weight(1f))
                    SmallButton("Done", primary = true) { vm.brushStudioFor = null }
                }
            }
        }
    }
    if (naming) {
        RenameDialog(preset?.name?.let { "$it copy" } ?: "My brush", title = "Name the new brush", onDismiss = { naming = false }) { name ->
            naming = false
            vm.saveBrushAsNew(forTool, name, b)
        }
    }
}

/** The brush in use and the library, for the options bar: tap to pick a brush, or open Brush Studio. */
@Composable
fun BrushPresetButton(vm: EditorViewModel, forTool: Tool) {
    var open by remember { mutableStateOf(false) }
    val b = vm.brushOf(forTool)
    val name = vm.brushPreset(b.presetId)?.name ?: "Custom brush"
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Q.field)
                .clickable { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(name + if (vm.brushChanged(b)) " •" else "", color = Q.text, fontSize = 13.sp, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Brushes", tint = Q.textDim, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 340.dp, max = 380.dp)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Brushes", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SmallButton("Import .abr…") {
                    open = false
                    vm.abrImportRequest++
                }
                Spacer(Modifier.size(6.dp))
                SmallButton("Brush Studio…") {
                    open = false
                    vm.brushStudioFor = forTool
                }
            }
            Column(Modifier.padding(horizontal = 6.dp)) { BrushLibraryList(vm, forTool) { open = false } }
        }
    }
}

/** The Brushes panel: the library for the brush or eraser in use. */
@Composable
fun BrushesPanel(vm: EditorViewModel) {
    val t = vm.tool
    if (t != Tool.BRUSH && t != Tool.ERASER) {
        Text("Pick the brush or the eraser to choose its brush here.", color = Q.textDim, fontSize = 12.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (t == Tool.ERASER) "For the eraser" else "For the brush", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        SmallButton("Import .abr…") { vm.abrImportRequest++ }
        Spacer(Modifier.size(6.dp))
        SmallButton("Brush Studio…") { vm.brushStudioFor = t }
    }
    BrushLibraryList(vm, t)
}


/** An imported tip's picture, for its button. */
private fun customTipIcon(id: String?): androidx.compose.ui.graphics.ImageBitmap {
    val size = 64
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val dab = com.tim.quartzeditor.engine.Dabs.make(BrushTip.SAMPLED, 52, 0f, id)
    android.graphics.Canvas(bmp).drawBitmap(dab, 6f, 6f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.rgb(40, 40, 40) })
    return bmp.asImageBitmap()
}
