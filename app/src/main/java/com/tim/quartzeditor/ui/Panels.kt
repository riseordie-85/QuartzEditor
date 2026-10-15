package com.tim.quartzeditor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.Adjust
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.ColorShader
import com.tim.quartzeditor.engine.DUOTONE_PRESETS
import com.tim.quartzeditor.engine.FilterPreset
import com.tim.quartzeditor.engine.FxParams
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color as AColor

/** The right-hand column: collapsible Color, Adjustments, Filters, Properties and Layers sections. */
@Composable
fun PanelColumn(vm: EditorViewModel, onPlaceImage: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Q.panel)
            .verticalScroll(scroll)
            .padding(vertical = 8.dp),
    ) {
        Section(vm, PanelSection.COLOR) { ColorPanel(vm) }
        Section(vm, PanelSection.ADJUSTMENTS) { AdjustPanel(vm) }
        Section(vm, PanelSection.FILTERS) { FiltersPanel(vm) }
        Section(vm, PanelSection.PROPERTIES) { PropertiesPanel(vm) }
        Section(vm, PanelSection.STYLE) { StylePanel(vm) }
        Section(vm, PanelSection.LAYERS) { LayersPanel(vm, onPlaceImage) }
    }
}

@Composable
private fun Section(vm: EditorViewModel, s: PanelSection, content: @Composable () -> Unit) {
    val open = s in vm.openSections
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.toggleSection(s) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Spacer(Modifier.width(28.dp))
            Text(s.label, color = Q.text, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Q.text, modifier = Modifier.size(28.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) { content() }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Q.divider))
    }
}

// ===================================================================== small pieces

@Composable
fun PanelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(display, color = Q.text, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Q.accent, inactiveTrackColor = Q.field),
            modifier = Modifier.height(30.dp),
        )
    }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Q.accent else Q.field)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (enabled) Color.White else Q.textDim, fontSize = 14.sp, maxLines = 1)
    }
}

/** A text field that commits on Done / when focus leaves. */
@Composable
fun PanelField(
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    mono: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(value) }
    var wasFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = Q.text, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(Q.accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            capitalization = if (mono) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            onCommit(text)
            focus.clearFocus()
        }),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Q.field)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onFocusChanged {
                if (it.isFocused) {
                    wasFocused = true
                } else if (wasFocused) {
                    wasFocused = false
                    if (text != value) onCommit(text)
                }
            },
    )
}

// ===================================================================== color

fun hexOf(c: Int): String = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.isEmpty() || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return when (t.length) {
        6 -> (0xFF000000L or t.toLong(16)).toInt()
        3 -> (0xFF000000L or "${t[0]}${t[0]}${t[1]}${t[1]}${t[2]}${t[2]}".toLong(16)).toInt()
        else -> null
    }
}

@Composable
private fun ColorPanel(vm: EditorViewModel) {
    val slot = vm.colorSlot
    val color = if (slot == ColorSlot.FOREGROUND) vm.foreground else vm.background
    // Hue is kept separately so grays don't make the hue bar jump
    var hue by remember { mutableFloatStateOf(hsv(color)[0]) }
    LaunchedEffect(color) {
        val h = hsv(color)
        if (h[1] > 0.02f && h[2] > 0.02f) hue = h[0]
    }
    val (_, sat, value) = hsv(color).let { Triple(it[0], it[1], it[2]) }
    val set: (Int) -> Unit = { vm.setColor(slot, it) }

    Row(Modifier.fillMaxWidth().height(170.dp)) {
        SatValSquare(hue, sat, value, onChange = { s, v -> set(AColor.HSVToColor(floatArrayOf(hue, s, v))) }, onDone = { vm.rememberColor(color) }, modifier = Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(10.dp))
        HueBar(hue, onChange = { h ->
            hue = h
            set(AColor.HSVToColor(floatArrayOf(h, if (sat < 0.02f) 1f else sat, if (value < 0.02f) 1f else value)))
        }, onDone = { vm.rememberColor(color) }, modifier = Modifier.width(22.dp).fillMaxHeight())
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SlotChip("Foreground", vm.foreground, slot == ColorSlot.FOREGROUND) { vm.colorSlot = ColorSlot.FOREGROUND }
        SlotChip("Background", vm.background, slot == ColorSlot.BACKGROUND) { vm.colorSlot = ColorSlot.BACKGROUND }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hex", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(36.dp))
        PanelField(hexOf(color), onCommit = { t -> parseHex(t)?.let { set(it); vm.rememberColor(it) } }, mono = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("RGB ${AColor.red(color)} ${AColor.green(color)} ${AColor.blue(color)}", color = Q.textDim, fontSize = 12.sp)
    }
    if (vm.recentColors.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        ColorDots(vm.recentColors, color) { set(it) }
    }
    Spacer(Modifier.height(8.dp))
    ColorDots(BASIC_COLORS, color) { set(it); vm.rememberColor(it) }
}

val BASIC_COLORS = listOf(
    AColor.BLACK, AColor.WHITE, 0xFF808080.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
    0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorDots(colors: List<Int>, selected: Int?, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (c == selected) 2.dp else 1.dp, if (c == selected) Q.accent else Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun SlotChip(label: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Q.rowSelected else Q.field)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(color)).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Q.text, fontSize = 13.sp)
    }
}

private fun hsv(c: Int): FloatArray = FloatArray(3).also { AColor.colorToHSV(c, it) }

@Composable
private fun SatValSquare(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                fun report(p: Offset) = change((p.x / size.width).coerceIn(0f, 1f), (1f - p.y / size.height).coerceIn(0f, 1f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    report(down.position)
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        report(c.position)
                        c.consume()
                    }
                    done()
                }
            }
    ) {
        val pure = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val p = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.6f), 9f, p, style = Stroke(4f))
        drawCircle(Color.White, 9f, p, style = Stroke(2f))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                fun report(p: Offset) = change(((p.y / size.height).coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    report(down.position)
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        report(c.position)
                        c.consume()
                    }
                    done()
                }
            }
    ) {
        // Top = 0° (red), going through magenta, blue, cyan, green, yellow, back to red
        drawRect(Brush.verticalGradient(HUE_ORDER))
        val y = hue / 360f * size.height
        drawRect(Color.White, topLeft = Offset(0f, y - 2f), size = androidx.compose.ui.geometry.Size(size.width, 4f), style = Stroke(2f))
    }
}

/** Hue bar colors top to bottom: 0° → 360°. */
private val HUE_ORDER = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

// ===================================================================== adjustments

@Composable
private fun AdjustPanel(vm: EditorViewModel) {
    val layer = vm.doc?.active
    val fx = vm.pendingFx ?: FxParams()
    Text("Applies to: ${layer?.props?.name ?: "—"}", color = Q.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(4.dp))
    Adjust.entries.forEach { a ->
        val v = fx.adjust(a)
        PanelSlider(
            label = a.label,
            value = v,
            range = if (a.bipolar) -1f..1f else 0f..1f,
            display = (if (a.bipolar && v > 0.005f) "+" else "") + "${(v * 100).roundToInt()}",
            onChange = { nv -> vm.updateFx(fx.copy(adjust = fx.adjust + (a to nv))) },
        )
    }
    FxButtons(vm)
}

@Composable
private fun FxButtons(vm: EditorViewModel) {
    if (vm.pendingFx == null) return
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Cancel", Modifier.weight(1f)) { vm.cancelFx() }
        SmallButton("Apply", Modifier.weight(1f), primary = true) { vm.applyFx() }
    }
}

// ===================================================================== filters

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersPanel(vm: EditorViewModel) {
    LaunchedEffect(vm.doc?.activeId, vm.doc?.active?.pixelVersion) { vm.ensureFxThumb() }
    val fx = vm.pendingFx ?: FxParams()
    val thumb = vm.fxThumb
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
        FilterPreset.entries.forEach { f ->
            val sel = f == fx.filter
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(if (sel) 2.dp else 0.dp, if (sel) Q.accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { vm.updateFx(fx.copy(filter = f)) }
                    .padding(3.dp),
            ) {
                val shader = remember { ColorShader() }
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).checker(10f)) {
                    val b = thumb ?: return@Canvas
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        val s = minOf(size.width / b.width, size.height / b.height)
                        nc.save()
                        nc.translate((size.width - b.width * s) / 2f, (size.height - b.height * s) / 2f)
                        nc.scale(s, s)
                        shader.bind(FxParams(filter = f, duoShadow = fx.duoShadow, duoHighlight = fx.duoHighlight), b)
                        nc.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), shader.paint)
                        nc.restore()
                    }
                }
                Text(f.label, color = Q.text, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
    if (fx.filter != FilterPreset.NONE) {
        Spacer(Modifier.height(6.dp))
        PanelSlider("Strength", fx.filterStrength, 0f..1f, "${(fx.filterStrength * 100).roundToInt()}%", onChange = { vm.updateFx(fx.copy(filterStrength = it)) })
    }
    if (fx.filter == FilterPreset.DUOTONE) {
        Text("Color pairs", color = Q.textDim, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (DUOTONE_PRESETS + listOf(vm.foreground to vm.background)).forEach { (a, b) ->
                val sel = fx.duoShadow == a && fx.duoHighlight == b
                Box(
                    Modifier
                        .size(width = 44.dp, height = 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(listOf(Color(a), Color(b))))
                        .border(if (sel) 2.dp else 1.dp, if (sel) Q.accent else Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .clickable { vm.updateFx(fx.copy(duoShadow = a, duoHighlight = b)) }
                )
            }
        }
        Text("The last pair uses your two colors.", color = Q.textDim, fontSize = 11.sp)
    }
    FxButtons(vm)
}

// ===================================================================== properties

@Composable
private fun PropertiesPanel(vm: EditorViewModel) {
    TransformProperties(vm)
    val tool = vm.tool
    if (tool == Tool.BRUSH || tool == Tool.ERASER) {
        val b = if (tool == Tool.ERASER) vm.eraser else vm.brush
        Text(if (tool == Tool.ERASER) "Eraser" else "Brush", color = Q.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BrushTip.entries.forEach { t ->
                val sel = t == b.tip
                Text(
                    t.label,
                    color = Q.text,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Q.rowSelected else Q.field)
                        .clickable { vm.updateBrush(tool) { it.copy(tip = t) } }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        PanelSlider("Opacity", b.opacity, 0.01f..1f, "${(b.opacity * 100).roundToInt()}%", onChange = { v -> vm.updateBrush(tool) { it.copy(opacity = v) } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size (px)", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            PanelField(b.size.roundToInt().toString(), onCommit = { t -> t.toIntOrNull()?.let { n -> vm.updateBrush(tool) { it.copy(size = n.coerceIn(1, 1000).toFloat()) } } }, numeric = true, modifier = Modifier.width(80.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
    if (tool == Tool.EYEDROPPER) {
        Text("Picks into the ${if (vm.colorSlot == ColorSlot.FOREGROUND) "foreground" else "background"} color. Tap the other swatch to switch.", color = Q.textDim, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
    }
    val d = vm.doc ?: return
    val l = d.active ?: return
    Text("Layer", color = Q.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Name", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        PanelField(l.props.name, onCommit = { n -> if (n.isNotBlank()) vm.setProps(l.id, "Rename layer") { it.copy(name = n.trim()) } }, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("X", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(20.dp))
        PanelField(l.x.toString(), onCommit = { t -> t.toIntOrNull()?.let { vm.setLayerPosition(l.id, it, l.y) } }, numeric = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text("Y", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(20.dp))
        PanelField(l.y.toString(), onCommit = { t -> t.toIntOrNull()?.let { vm.setLayerPosition(l.id, l.x, it) } }, numeric = true, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Text("Canvas ${d.width} × ${d.height} px", color = Q.textDim, fontSize = 12.sp)
    if (l.hasMask) {
        Spacer(Modifier.height(10.dp))
        Text("Mask", color = Q.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (d.editMask) "Painting mask" else "Paint mask", Modifier.weight(1f), primary = d.editMask) { vm.selectLayer(l.id, mask = true) }
            SmallButton(if (!d.editMask) "Painting pixels" else "Paint pixels", Modifier.weight(1f), primary = !d.editMask) { vm.selectLayer(l.id, mask = false) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (l.props.maskDisabled) "Enable" else "Disable", Modifier.weight(1f)) { vm.toggleMaskEnabled() }
            SmallButton("Invert", Modifier.weight(1f)) { vm.invertMask() }
            SmallButton("Apply", Modifier.weight(1f)) { vm.applyMask() }
        }
        Text("Black hides, white shows. Gray is in between.", color = Q.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
