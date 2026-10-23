package com.tim.quartzeditor.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tim.quartzeditor.engine.Fonts
import com.tim.quartzeditor.engine.ShapeKind
import com.tim.quartzeditor.engine.ShapeSpec
import com.tim.quartzeditor.engine.StrokeAlign
import com.tim.quartzeditor.engine.TextAlignOption
import com.tim.quartzeditor.engine.TextSpec
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.graphics.Color as AColor
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

// Properties for the Shape, Shape pen and Text tools (and for shape and text layers), the bars
// shown over the picture while drawing with the pen or typing, and the color and font pickers.

// ===================================================================== shapes

private val SHAPE_CHOICES = ShapeKind.TOOL_SHAPES

/**
 * Shape settings. With a shape layer selected they change that layer (and become the settings for
 * new shapes); otherwise they're the settings new shapes are drawn with.
 */
@Composable
fun ShapeOptions(vm: EditorViewModel) {
    val layer = vm.doc?.active?.takeIf { it.isShape }
    val spec = layer?.props?.shape ?: vm.shapeStyle.copy(kind = vm.shapeKind)
    var picking by remember { mutableStateOf<String?>(null) }

    fun set(label: String, live: Boolean = false, change: (ShapeSpec) -> ShapeSpec) {
        vm.shapeStyle = change(vm.shapeStyle)
        if (layer != null) {
            if (live) vm.editVectorLive(layer.id) { p -> p.copy(shape = p.shape?.let(change)) }
            else vm.editVector(layer.id, label) { p -> p.copy(shape = p.shape?.let(change)) }
            // A layer's fill/outline switches don't carry over to new shapes (its colors do)
            vm.resetShapeSwitches()
        } else if (!live) {
            vm.saveVectorPrefs()
        }
    }
    fun done(label: String) {
        if (layer != null) vm.commitVector(label)
        vm.saveVectorPrefs()
    }

    Title(
        when {
            layer != null -> "Shape layer"
            vm.tool == Tool.PEN -> "Shape pen"
            spec.kind == ShapeKind.CUSTOM -> "Custom shapes"
            else -> "Shape"
        }
    )
    if (vm.penEditTarget != null) {
        Text("Drag points and their handles. Tap a point to pick it, tap the outline to add a point. Tap the pen again (or Done) to draw a new shape.", color = Q.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }
    // Which pen draws (not while editing points)
    if (vm.tool == Tool.PEN && vm.penEditTarget == null) {
        ChipRow(listOf("Pen", "Curvature pen"), if (vm.penCurvature) 1 else 0) { i -> vm.choosePen(edit = false, curvature = i == 1) }
        Spacer(Modifier.height(6.dp))
    }
    if (vm.penEditTarget == null && layer?.props?.shape?.kind == ShapeKind.PATH && vm.tool == Tool.PEN) {
        Text(
            "Tap its outline to add a point" + (if (layer.props.shape?.closed == false) ", or one of its ends to carry it on" else "") +
                ". Tap the pen again to edit its points, or tap anywhere else to draw a new shape.",
            color = Q.textDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (layer == null && vm.tool == Tool.PEN) {
        Text(
            if (vm.penCurvature) "Tap to place points: the line curves smoothly through them. Double-tap a point to make it a sharp corner (or smooth again); drag a point to move it. Tap the first point to close the shape."
            else "Tap to place corners. Drag as you place a point to bend the line into a curve. Double-tap a point to switch it between a corner and a curve; drag a point to move it. Tap the first point to close the shape.",
            color = Q.textDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (spec.kind == ShapeKind.PATH) {
        if (layer != null && vm.penEditTarget == null) {
            SmallButton("Edit points") { vm.startPenEditing() }
            Spacer(Modifier.height(6.dp))
        }
        if (layer != null) {
            ToggleRow("Closed shape", spec.closed) { on ->
                if (on && spec.path.size < 3) return@ToggleRow
                set(if (on) "Close shape" else "Open shape") { it.copy(closed = on) }
            }
        }
    } else if (spec.kind == ShapeKind.CUSTOM) {
        Text(
            if (layer != null) "Tap a shape to change this one into it." else "Pick a shape, then drag on the picture to draw it. Hold one of yours to remove it.",
            color = Q.textDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        ShapeLibraryGrid(vm, selectedName = layer?.props?.shape?.shapeName)
        Spacer(Modifier.height(4.dp))
        if (layer == null) {
            ToggleRow("Keep its proportions", vm.shapeSquare) {
                vm.shapeSquare = it
                vm.saveVectorPrefs()
            }
        }
    } else if (vm.tool != Tool.PEN || layer != null) {
        ChipRow(SHAPE_CHOICES.map { it.label }, SHAPE_CHOICES.indexOf(spec.kind)) { i ->
            val k = SHAPE_CHOICES[i]
            vm.shapeKind = k
            if (layer != null) vm.editVector(layer.id, "Change shape") { p -> p.copy(shape = p.shape?.copy(kind = k)) }
            vm.saveVectorPrefs()
        }
        Spacer(Modifier.height(4.dp))
        if (layer == null) {
            ToggleRow(
                when (spec.kind) {
                    ShapeKind.ELLIPSE -> "Perfect circle"
                    ShapeKind.ARROW -> "Straight angles (45° steps)"
                    else -> "Same width and height"
                },
                vm.shapeSquare,
            ) {
                vm.shapeSquare = it
                vm.saveVectorPrefs()
            }
        }
    }

    when (spec.kind) {
        ShapeKind.RECT -> {
            val max = if (layer != null) (minOf(spec.w, spec.h) / 2f).coerceAtLeast(1f) else 300f
            PanelSlider("Corner radius", spec.radius.coerceIn(0f, max), 0f..max, "${spec.radius.coerceAtMost(max).roundToInt()} px",
                onChange = { v -> set("Corner radius", live = true) { it.copy(radius = v) } }, onDone = { done("Corner radius") })
        }
        ShapeKind.POLYGON -> {
            PanelSlider("Sides", spec.sides.toFloat(), 3f..24f, "${spec.sides}",
                onChange = { v -> set("Polygon sides", live = true) { it.copy(sides = v.roundToInt()) } }, onDone = { done("Polygon sides") })
        }
        ShapeKind.ARROW -> {
            PanelSlider("Head length", spec.headLength, 0.2f..3f, "${(spec.headLength * 100).roundToInt()}%",
                onChange = { v -> set("Arrow head", live = true) { it.copy(headLength = v) } }, onDone = { done("Arrow head") })
            PanelSlider("Head width", spec.headWidth, 0.3f..2f, "${(spec.headWidth * 100).roundToInt()}%",
                onChange = { v -> set("Arrow head", live = true) { it.copy(headWidth = v) } }, onDone = { done("Arrow head") })
            PanelSlider("Shaft", spec.shaftWidth, 0.05f..1f, "${(spec.shaftWidth * 100).roundToInt()}%",
                onChange = { v -> set("Arrow shaft", live = true) { it.copy(shaftWidth = v) } }, onDone = { done("Arrow shaft") })
        }
        ShapeKind.STAR -> {
            PanelSlider("Points", spec.points.toFloat(), 3f..24f, "${spec.points}",
                onChange = { v -> set("Star points", live = true) { it.copy(points = v.roundToInt()) } }, onDone = { done("Star points") })
            PanelSlider("Depth", spec.depth, 0f..0.95f, if (spec.depth < 0.005f) "Polygon" else "${(spec.depth * 100).roundToInt()}%",
                onChange = { v -> set("Star depth", live = true) { it.copy(depth = if (v < 0.02f) 0f else v) } }, onDone = { done("Star depth") })
            if (spec.depth >= 0.005f) {
                PanelSlider("Flatten points", spec.flat, 0f..1f,
                    when {
                        spec.flat < 0.005f -> "Sharp"
                        spec.flat > 0.995f -> "Square (cog)"
                        else -> "${(spec.flat * 100).roundToInt()}%"
                    },
                    onChange = { v -> set("Flatten points", live = true) { it.copy(flat = if (v < 0.02f) 0f else v) } }, onDone = { done("Flatten points") })
                Text("Flat tips and valleys make cogs and gears: try 12 points, 25% depth.", color = Q.textDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
        }
        else -> Unit
    }

    // Fill and outline
    Spacer(Modifier.height(4.dp))
    val canFill = spec.kind != ShapeKind.PATH || spec.closed
    if (canFill) {
        ColorToggleRow("Fill", spec.fill,
            onToggle = { on ->
                // Turning it back on brings back the color it had
                if (!on) vm.lastShapeFill = spec.fill
                val c = vm.lastShapeFill ?: vm.foreground
                set(if (on) "Fill on" else "Fill off") { it.copy(fill = if (on) c else null) }
            },
            onPick = { picking = "fill" })
        if (spec.fill != null) ShapeFillKind(vm, spec, done = { done(it) }) { label, live, change -> set(label, live, change) }
    }
    ColorToggleRow("Outline", spec.stroke,
        onToggle = { on ->
            if (!on) vm.lastShapeStroke = spec.stroke
            val c = vm.lastShapeStroke ?: AColor.BLACK
            set(if (on) "Outline on" else "Outline off") { it.copy(stroke = if (on) c else null) }
        },
        onPick = { picking = "stroke" })
    if (spec.hasStroke) {
        // 0.5 … 300 px, finer at the small end
        val t = sqrt(((spec.strokeWidth - 0.5f) / 299.5f).coerceIn(0f, 1f))
        PanelSlider("Outline width", t, 0f..1f, "${formatPx(spec.strokeWidth)} px",
            onChange = { v -> set("Outline width", live = true) { it.copy(strokeWidth = 0.5f + v * v * 299.5f) } }, onDone = { done("Outline width") },
            fromTyped = { px -> kotlin.math.sqrt(((px - 0.5f) / 299.5f).coerceIn(0f, 1f)) })
        if (canFill) {
            ChipRow(StrokeAlign.entries.map { it.label }, spec.strokeAlign.ordinal) { i -> set("Outline position") { it.copy(strokeAlign = StrokeAlign.entries[i]) } }
            Spacer(Modifier.height(6.dp))
        }
        // Solid, dashed or dotted
        val dashes = listOf<List<Float>?>(null, listOf(3f, 2f), listOf(0f, 2f))
        val which = dashes.indexOfFirst { it == spec.dash }.let { if (it < 0) 1 else it }
        ChipRow(listOf("Solid", "Dashed", "Dotted"), which) { i -> set("Outline style") { it.copy(dash = dashes[i]) } }
        if (spec.dash != null && spec.dash != dashes[2]) {
            val d = spec.dash
            PanelSlider("Dash", d.getOrElse(0) { 3f }, 0.5f..12f, String.format(java.util.Locale.US, "%.1f×", d.getOrElse(0) { 3f }),
                onChange = { v -> set("Dash", live = true) { it.copy(dash = listOf(v, d.getOrElse(1) { 2f })) } }, onDone = { done("Dash") })
            PanelSlider("Gap", d.getOrElse(1) { 2f }, 0.5f..12f, String.format(java.util.Locale.US, "%.1f×", d.getOrElse(1) { 2f }),
                onChange = { v -> set("Gap", live = true) { it.copy(dash = listOf(d.getOrElse(0) { 3f }, v)) } }, onDone = { done("Gap") })
        }
        Spacer(Modifier.height(6.dp))
    }
    if (layer != null) {
        Extrude3DOptions(vm, spec.extrude) { label, live, change ->
            if (live) vm.editVectorLive(layer.id) { p -> p.copy(shape = p.shape?.let { it.copy(extrude = change(it.extrude)) }) }
            else vm.editVector(layer.id, label) { p -> p.copy(shape = p.shape?.let { it.copy(extrude = change(it.extrude)) }) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Scale, rotate or distort it with Transform; it stays sharp.", color = Q.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        SmallButton("Rasterize layer") { vm.rasterizeLayer(layer.id) }
    }
    Spacer(Modifier.height(10.dp))

    picking?.let { which ->
        val start = (if (which == "fill") spec.fill else spec.stroke) ?: vm.foreground
        ColorPickDialog(
            vm, start, title = if (which == "fill") "Fill color" else "Outline color",
            onLive = { c -> set("Color", live = true) { if (which == "fill") it.copy(fill = c) else it.copy(stroke = c) } },
            onDone = {
                picking = null
                done(if (which == "fill") "Fill color" else "Outline color")
            },
        )
    }
}

private fun formatPx(v: Float): String = if (v < 10f) String.format(java.util.Locale.US, "%.1f", v) else v.roundToInt().toString()

/** "Fill  [color]  [on/off]" */
@Composable
private fun ColorToggleRow(label: String, color: Int?, onToggle: (Boolean) -> Unit, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (color != null) {
            ColorChip(color, onPick)
            Spacer(Modifier.width(10.dp))
        }
        Switch(checked = color != null, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Q.accent))
    }
}

@Composable
private fun ColorChip(color: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 44.dp, height = 28.dp)
            .clip(RoundedCornerShape(8.dp))
            .checker(6f)
            .background(Color(color))
            .border(1.dp, Q.text.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    )
}

// ===================================================================== text

/** Size slider: 4 … 1000 px, finer at the small end. */
private fun sizeToT(px: Float): Float = (ln(px.coerceIn(4f, 1000f) / 4f) / ln(250f))
private fun tToSize(t: Float): Float = (4f * 250f.pow(t.coerceIn(0f, 1f))).roundToInt().toFloat()

/**
 * Text settings. With a text layer selected they change it (and become the settings for new
 * text); otherwise they're what new text starts with.
 */
@Composable
fun TextOptions(vm: EditorViewModel) {
    val layer = vm.doc?.active?.takeIf { it.isText }
    val spec = layer?.props?.text ?: vm.textStyle
    var picking by remember { mutableStateOf(false) }
    var fonts by remember { mutableStateOf(false) }

    fun set(label: String, live: Boolean = false, change: (TextSpec) -> TextSpec) {
        vm.textStyle = change(vm.textStyle)
        if (layer != null) {
            if (live) vm.editVectorLive(layer.id) { p -> p.copy(text = p.text?.let(change)) }
            else vm.editVector(layer.id, label) { p -> p.copy(text = p.text?.let(change)) }
        }
        if (!live) vm.saveVectorPrefs()
    }
    fun done(label: String) {
        if (layer != null) vm.commitVector(label)
        vm.saveVectorPrefs()
    }

    Title(if (layer != null) "Text layer" else "Text")
    if (layer == null) {
        Text("Tap the picture to type. Tap text to change it; drag with the Text tool to move it.", color = Q.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    } else if (vm.textEdit?.layerId != layer.id) {
        SmallButton("Edit text", primary = true) { vm.beginTextEdit(layer) }
        Spacer(Modifier.height(8.dp))
    }

    // Font
    val fontVersion by Fonts.version.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Font", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        val tf = if (fontVersion >= 0) Fonts.cached(spec.fontKey) else null
        Text(
            Fonts.label(spec.fontKey),
            color = Q.text,
            fontSize = 16.sp,
            fontFamily = tf?.let { FontFamily(ComposeTypeface(it)) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Q.field)
                .clickable { fonts = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
    Spacer(Modifier.height(6.dp))

    // Size and color
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Size", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        PanelField(
            if (spec.size > 0f) spec.size.roundToInt().toString() else "Auto",
            onCommit = { s -> s.toFloatOrNull()?.let { v -> set("Text size") { it.copy(size = v.coerceIn(1f, 4000f)) } } },
            numeric = true,
            modifier = Modifier.width(72.dp),
        )
        Text(" px", color = Q.textDim, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        ColorChip(spec.color) { picking = true }
    }
    PanelSlider("", sizeToT(if (spec.size > 0f) spec.size else 96f), 0f..1f, "",
        onChange = { v -> set("Text size", live = true) { it.copy(size = tToSize(v)) } }, onDone = { done("Text size") })

    // Alignment and style
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val aligns = listOf(Icons.Outlined.FormatAlignLeft, Icons.Outlined.FormatAlignCenter, Icons.Outlined.FormatAlignRight)
        TextAlignOption.entries.forEachIndexed { i, a ->
            IconToggle(aligns[i], "Align ${a.label.lowercase()}", spec.align == a) { set("Align text") { it.copy(align = a) } }
        }
        Spacer(Modifier.width(8.dp))
        IconToggle(Icons.Outlined.FormatBold, "Faux bold", spec.fauxBold) { set("Bold") { it.copy(fauxBold = !it.fauxBold) } }
        IconToggle(Icons.Outlined.FormatItalic, "Faux italic", spec.fauxItalic) { set("Italic") { it.copy(fauxItalic = !it.fauxItalic) } }
        IconToggle(Icons.Outlined.FormatUnderlined, "Underline", spec.underline) { set("Underline") { it.copy(underline = !it.underline) } }
        TextToggle("AA", "All caps", spec.allCaps) { set("All caps") { it.copy(allCaps = !it.allCaps) } }
    }
    Spacer(Modifier.height(4.dp))
    PanelSlider("Letter spacing", spec.tracking, -0.2f..1f, "${(spec.tracking * 1000).roundToInt()}",
        onChange = { v -> set("Letter spacing", live = true) { it.copy(tracking = if (kotlin.math.abs(v) < 0.01f) 0f else v) } }, onDone = { done("Letter spacing") },
        fromTyped = { it / 1000f })
    PanelSlider("Line spacing", spec.leading, 0.6f..3f, String.format(java.util.Locale.US, "%.2f×", spec.leading),
        onChange = { v -> set("Line spacing", live = true) { it.copy(leading = if (kotlin.math.abs(v - 1f) < 0.03f) 1f else v) } }, onDone = { done("Line spacing") })
    PanelSlider("Arc", spec.curve, -1f..1f, if (kotlin.math.abs(spec.curve) < 0.02f) "Straight" else "${(spec.curve * 100).roundToInt()}%",
        onChange = { v -> set("Arc", live = true) { it.copy(curve = if (kotlin.math.abs(v) < 0.04f) 0f else v) } }, onDone = { done("Arc") })
    if (spec.curve != 0f && spec.text.contains('\n') && spec.warp == null) {
        Text("Arcs work on one line of text.", color = Q.textDim, fontSize = 11.sp)
    }
    if (layer != null) TextWarpOptions(vm, layer, spec)
    if (layer != null) {
        Extrude3DOptions(vm, spec.extrude) { label, live, change ->
            if (live) vm.editVectorLive(layer.id) { p -> p.copy(text = p.text?.let { it.copy(extrude = change(it.extrude)) }) }
            else vm.editVector(layer.id, label) { p -> p.copy(text = p.text?.let { it.copy(extrude = change(it.extrude)) }) }
        }
    }
    if (layer != null) {
        Spacer(Modifier.height(6.dp))
        Text("Outline, shadow and glow: Layer Style (fx). Scale or rotate it with Transform; it stays sharp.", color = Q.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        SmallButton("Rasterize layer") { vm.rasterizeLayer(layer.id) }
    }
    Spacer(Modifier.height(10.dp))

    if (picking) {
        ColorPickDialog(
            vm, spec.color, title = "Text color",
            onLive = { c -> set("Text color", live = true) { it.copy(color = c) } },
            onDone = {
                picking = false
                done("Text color")
            },
        )
    }
    if (fonts) {
        FontPickDialog(spec.fontKey, onDismiss = { fonts = false }) { key ->
            fonts = false
            set("Font") { it.copy(fontKey = key) }
        }
    }
}

@Composable
private fun IconToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Q.rowSelected else Q.field)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TextToggle(text: String, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Q.rowSelected else Q.field)
            .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Text(text, color = Q.text, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

// ===================================================================== bars over the picture

/** Shape pen: undo a point, cancel, finish open, close. */
@Composable
fun PenBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val editing = vm.penEditTarget
    if (editing != null) {
        val pts = editing.props.shape?.path.orEmpty()
        val sel = pts.getOrNull(vm.penSelected)
        Bar(modifier) {
            Text("Edit points", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            if (sel != null) {
                BarChip(if (sel.smooth) "Make corner" else "Make curve", false) { vm.togglePenPointCurve() }
                BarChip("Delete point", false) { vm.deletePenPoint() }
            }
            BarChip("Done", true) { vm.togglePenEditing() }
        }
        return
    }
    if (vm.tool != Tool.PEN || vm.penPoints.isEmpty()) return
    Bar(modifier) {
        BarIcon(Icons.Outlined.Close, "Cancel shape") { vm.cancelPen() }
        BarIcon(Icons.AutoMirrored.Outlined.Undo, "Remove the last point") { vm.undoPenPoint() }
        Text("${vm.penPoints.size} point${if (vm.penPoints.size == 1) "" else "s"}", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        if (vm.penPoints.size >= 2) BarChip("Open line", false) { vm.finishPen(closed = false) }
        if (vm.penPoints.size >= 3) BarChip("Close shape", true) { vm.finishPen(closed = true) }
    }
}

/** Typing: the text box (the keyboard opens), cancel and done. */
@Composable
fun TextBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val session = vm.textEdit ?: return
    val layer = vm.doc?.layer(session.layerId) ?: return
    val start = remember(session) { layer.props.text?.text.orEmpty() }
    var value by remember(session) { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(session) { runCatching { focus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Q.toolbar.copy(alpha = 0.96f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BarIcon(Icons.Outlined.Close, "Cancel") { vm.cancelText() }
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Q.field)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (value.text.isEmpty()) Text("Type your text", color = Q.textDim, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    vm.typeText(it.text)
                },
                textStyle = TextStyle(color = Q.text, fontSize = 16.sp),
                cursorBrush = SolidColor(Q.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 22.dp)
                    .focusRequester(focus),
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Q.accent)
                .clickable { vm.finishText() },
        ) {
            Icon(Icons.Outlined.Check, contentDescription = "Done", tint = Color.White)
        }
    }
}

/** "Rasterize this layer?" before painting on shape or text pixels. */
@Composable
fun RasterizeDialog(vm: EditorViewModel) {
    val a = vm.rasterAsk ?: return
    val what = if (a.isText) "text" else if (a.isSmart) "smart" else "shape"
    AlertDialog(
        onDismissRequest = { vm.answerRasterize(false) },
        title = { Text("Rasterize \"${a.name}\"?") },
        text = {
            Text(
                "Painting, filling, filters and adjustments change pixels, so this $what layer has to become a " +
                    "pixel layer first. It will look the same, but you won't be able to change its ${if (a.isText) "words, font or size" else if (a.isSmart) "contents, or scale it up again without losing quality," else "shape, fill or outline"} anymore."
            )
        },
        confirmButton = { TextButton(onClick = { vm.answerRasterize(true) }) { Text("Rasterize") } },
        dismissButton = { TextButton(onClick = { vm.answerRasterize(false) }) { Text("Cancel") } },
    )
}

// ===================================================================== pickers

/**
 * A color picker in a window: saturation/brightness square, hue bar, hex, and quick swatches.
 * [onLive] gets every change; [onDone] runs once when it closes.
 */
@Composable
fun ColorPickDialog(
    vm: EditorViewModel,
    initial: Int,
    title: String,
    onLive: (Int) -> Unit,
    onDone: () -> Unit,
    /** Shown as a pop-up beside its anchor (shifted by this) instead of a window in the middle. */
    popupOffset: androidx.compose.ui.unit.IntOffset? = null,
) {
    var color by remember { mutableStateOf(initial or 0xFF000000.toInt()) }
    var hue by remember { mutableFloatStateOf(hsv(initial)[0]) }
    fun pick(c: Int, keepHue: Boolean = false) {
        color = c or 0xFF000000.toInt()
        if (!keepHue) {
            val h = hsv(c)
            if (h[1] > 0.02f && h[2] > 0.02f) hue = h[0]
        }
        onLive(color)
    }
    // Closing for any reason (even the panel folding away under it) finishes the change once
    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (finished) return
        finished = true
        vm.rememberColor(color)
        onDone()
    }
    DisposableEffect(Unit) { onDispose { finish() } }
    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .border(1.dp, Q.divider, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                // On a short screen it scrolls rather than running off the edge
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Q.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(Color(color)).border(1.dp, Q.text.copy(alpha = 0.4f), RoundedCornerShape(8.dp)))
            }
            Spacer(Modifier.height(10.dp))
            ColorTabs(vm)
            if (vm.colorTab == 1) {
                SwatchGrid(vm, color) { pick(it) }
            } else {
            val h = hsv(color)
            Row(Modifier.fillMaxWidth().height(190.dp)) {
                SatValSquare(hue, h[1], h[2], onChange = { s, v -> pick(AColor.HSVToColor(floatArrayOf(hue, s, v)), keepHue = true) }, onDone = {}, modifier = Modifier.weight(1f).height(190.dp))
                Spacer(Modifier.width(10.dp))
                HueBar(hue, onChange = { nh ->
                    hue = nh
                    pick(AColor.HSVToColor(floatArrayOf(nh, if (h[1] < 0.02f) 1f else h[1], if (h[2] < 0.02f) 1f else h[2])), keepHue = true)
                }, onDone = {}, modifier = Modifier.width(22.dp).height(190.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hex", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(36.dp))
                PanelField(hexOf(color), onCommit = { t -> parseHex(t)?.let { pick(it) } }, mono = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text("Foreground, background and recent", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            ColorDots((listOf(vm.foreground, vm.background) + vm.recentColors).distinct().take(12), color) { pick(it) }
            Spacer(Modifier.height(8.dp))
            ColorDots(BASIC_COLORS, color) { pick(it) }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Spacer(Modifier.weight(1f))
                SmallButton("Done", primary = true) { finish() }
            }
        }
    }
    if (popupOffset != null) {
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember(popupOffset) { BesideAnchor(popupOffset) },
            onDismissRequest = { finish() },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = true),
        ) { content() }
    } else {
        Dialog(onDismissRequest = { finish() }) { content() }
    }
}

/**
 * Places a pop-up beside its anchor (shifted by [offset] from the anchor's bottom-left), its bottom
 * level with the anchor's, but always fully on screen.
 */
private class BesideAnchor(private val offset: androidx.compose.ui.unit.IntOffset) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = (anchorBounds.left + offset.x).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val y = (anchorBounds.bottom + offset.y - popupContentSize.height).coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/** Fonts: imported ones, Android's, and Google Fonts (each name shown in its own font). */
@Composable
fun FontPickDialog(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported by Fonts.imported.collectAsState()
    val fontVersion by Fonts.version.collectAsState()
    var importing by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val key = Fonts.import(context, uri)
            importing = false
            if (key != null) onPick(key)
            else Toast.makeText(context, "That file isn't a font Android can use (.ttf or .otf).", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        Fonts.refreshImported(context)
        // Fetch the Google Fonts in the background so their names show in their own style
        Fonts.googleEntries().forEach { e -> launch { Fonts.load(context, e.key) } }
    }
    val entries = imported + Fonts.systemEntries() + Fonts.googleEntries()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Font", color = Q.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                SmallButton(if (importing) "Importing…" else "Import font…") {
                    importer.launch(
                        arrayOf(
                            "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
                            "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
                        )
                    )
                }
            }
            Text(
                "Google Fonts download the first time you use them (through Google Play services).",
                color = Q.textDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                items(entries, key = { it.key }) { e ->
                    val tf = if (fontVersion >= 0) Fonts.cached(e.key) else null
                    val sel = e.key == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(e.key) }
                            .background(if (sel) Q.rowSelected else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            e.label,
                            color = Q.text,
                            fontSize = 19.sp,
                            fontFamily = tf?.let { FontFamily(ComposeTypeface(it)) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            tf == null && !Fonts.isFailed(e.key) -> CircularProgressIndicator(color = Q.textDim, strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
                            tf == null -> Text("Unavailable", color = Q.textDim, fontSize = 11.sp)
                            e.source == com.tim.quartzeditor.engine.FontSource.IMPORTED -> Icon(Icons.Outlined.FileUpload, contentDescription = "Imported", tint = Q.textDim, modifier = Modifier.size(16.dp))
                            else -> Unit
                        }
                        if (sel) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = Q.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}


/** Warp Text (like Photoshop's): a shape the text layer bends into, with its bend and distortion. */
@Composable
private fun TextWarpOptions(vm: EditorViewModel, layer: com.tim.quartzeditor.engine.Layer, spec: TextSpec) {
    // Only this layer bends (new text starts straight)
    fun set(label: String, live: Boolean, change: (com.tim.quartzeditor.engine.TextWarp?) -> com.tim.quartzeditor.engine.TextWarp?) {
        if (live) vm.editVectorLive(layer.id) { p -> p.copy(text = p.text?.let { it.copy(warp = change(it.warp)) }) }
        else vm.editVector(layer.id, label) { p -> p.copy(text = p.text?.let { it.copy(warp = change(it.warp)) }) }
    }
    val w = spec.warp
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Warp", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f)) {
            Text(
                w?.style?.label ?: "None",
                color = Q.text, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Q.field).clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("None") }, onClick = {
                    open = false
                    set("Warp text", live = false) { null }
                })
                com.tim.quartzeditor.engine.WarpStyle.entries.forEach { st ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(st.label) }, onClick = {
                        open = false
                        set("Warp text", live = false) { it?.copy(style = st) ?: com.tim.quartzeditor.engine.TextWarp(st) }
                    })
                }
            }
        }
    }
    if (w == null) return
    fun pct(v: Float) = (if (v > 0.005f) "+" else "") + "${(v * 100).roundToInt()}%"
    PanelSlider("Bend", w.bend, -1f..1f, pct(w.bend),
        onChange = { v -> set("Warp text", live = true) { it?.copy(bend = v) } }, onDone = { vm.commitVector("Warp text") }, fromTyped = { it / 100f })
    PanelSlider("Horizontal distortion", w.horizontal, -1f..1f, pct(w.horizontal),
        onChange = { v -> set("Warp text", live = true) { it?.copy(horizontal = v) } }, onDone = { vm.commitVector("Warp text") }, fromTyped = { it / 100f })
    PanelSlider("Vertical distortion", w.vertical, -1f..1f, pct(w.vertical),
        onChange = { v -> set("Warp text", live = true) { it?.copy(vertical = v) } }, onDone = { vm.commitVector("Warp text") }, fromTyped = { it / 100f })
    ChipRow(listOf("Horizontal", "Vertical"), if (w.verticalOrientation) 1 else 0) { i ->
        set("Warp text", live = false) { it?.copy(verticalOrientation = i == 1) }
    }
}


/**
 * A shape or text layer's 3D: off or a ready-made look, then depth, how it's turned, perspective,
 * the light and the sides' color. [set] changes it (label, live while a slider moves, the change).
 */
@Composable
private fun Extrude3DOptions(
    vm: EditorViewModel,
    e: com.tim.quartzeditor.engine.Extrude3D?,
    set: (String, Boolean, (com.tim.quartzeditor.engine.Extrude3D?) -> com.tim.quartzeditor.engine.Extrude3D?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val presets = com.tim.quartzeditor.engine.Extrude3D.PRESETS
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("3D", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f)) {
            val name = when {
                e == null -> "Off"
                else -> presets.firstOrNull { (_, p) -> p == e }?.first ?: "Custom"
            }
            Text(
                name, color = Q.text, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Q.field).clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Off") }, onClick = {
                    open = false
                    set("3D off", false) { null }
                })
                presets.forEach { (n, p) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(n) }, onClick = {
                        open = false
                        // A look keeps the sides' color you picked
                        set("3D", false) { old -> p.copy(sideColor = old?.sideColor) }
                    })
                }
            }
        }
    }
    if (e == null) return
    fun live(label: String, change: (com.tim.quartzeditor.engine.Extrude3D) -> com.tim.quartzeditor.engine.Extrude3D) = set(label, true) { it?.let(change) }
    fun deg(v: Float) = "${v.roundToInt()}°"
    PanelSlider("Depth", e.depth, 0f..600f, "${e.depth.roundToInt()} px",
        onChange = { v -> live("3D depth") { it.copy(depth = v) } }, onDone = { vm.commitVector("3D depth") })
    PanelSlider("Tilt", e.tilt, -80f..80f, deg(e.tilt),
        onChange = { v -> live("3D tilt") { it.copy(tilt = if (kotlin.math.abs(v) < 1f) 0f else v) } }, onDone = { vm.commitVector("3D tilt") })
    PanelSlider("Turn", e.turn, -80f..80f, deg(e.turn),
        onChange = { v -> live("3D turn") { it.copy(turn = if (kotlin.math.abs(v) < 1f) 0f else v) } }, onDone = { vm.commitVector("3D turn") })
    PanelSlider("Perspective", e.perspective, 0f..1f, "${(e.perspective * 100).roundToInt()}%",
        onChange = { v -> live("3D perspective") { it.copy(perspective = v) } }, onDone = { vm.commitVector("3D perspective") }, fromTyped = { it / 100f })
    PanelSlider("Light angle", e.lightAngle, 0f..360f, deg(e.lightAngle),
        onChange = { v -> live("3D light") { it.copy(lightAngle = v) } }, onDone = { vm.commitVector("3D light") })
    PanelSlider("Light height", e.lightHeight, 0f..90f, deg(e.lightHeight),
        onChange = { v -> live("3D light") { it.copy(lightHeight = v) } }, onDone = { vm.commitVector("3D light") })
    PanelSlider("Shading", e.shading, 0f..1f, "${(e.shading * 100).roundToInt()}%",
        onChange = { v -> live("3D shading") { it.copy(shading = v) } }, onDone = { vm.commitVector("3D shading") }, fromTyped = { it / 100f })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sides", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f)) {
            ChipRow(listOf("Its own colors", "A color"), if (e.sideColor == null) 0 else 1) { i ->
                set("3D sides", false) { it?.copy(sideColor = if (i == 0) null else (it.sideColor ?: vm.foreground)) }
            }
        }
        e.sideColor?.let { c ->
            Spacer(Modifier.width(6.dp))
            ColorChip(c) { picking = true }
        }
    }
    if (picking) {
        ColorPickDialog(
            vm, e.sideColor ?: vm.foreground, title = "Sides color",
            onLive = { c -> set("3D sides", true) { it?.copy(sideColor = c) } },
            onDone = {
                picking = false
                vm.commitVector("3D sides")
            },
        )
    }
}

/** A shape's fill: its color, a gradient or a pattern. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ShapeFillKind(vm: EditorViewModel, spec: com.tim.quartzeditor.engine.ShapeSpec, done: (String) -> Unit, set: (String, Boolean, (com.tim.quartzeditor.engine.ShapeSpec) -> com.tim.quartzeditor.engine.ShapeSpec) -> Unit) {
    val kind = when {
        spec.fillGradient != null -> 1
        spec.fillPattern != null -> 2
        else -> 0
    }
    ChipRow(listOf("Color", "Gradient", "Pattern"), kind) { i ->
        when (i) {
            0 -> set("Fill color", false) { it.copy(fillGradient = null, fillPattern = null) }
            1 -> set("Gradient fill", false) {
                it.copy(fillPattern = null, fillGradient = it.fillGradient ?: com.tim.quartzeditor.engine.GradientFx(on = true, colors = listOf(vm.foreground or 0xFF000000.toInt(), vm.background or 0xFF000000.toInt())))
            }
            else -> set("Pattern fill", false) {
                val a = vm.foreground or 0xFF000000.toInt()
                val b = vm.background or 0xFF000000.toInt()
                vm.warmPattern("b:checker", a, b)
                it.copy(fillGradient = null, fillPattern = it.fillPattern ?: com.tim.quartzeditor.engine.PatternFx(true, "b:checker", a, b))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    spec.fillGradient?.let { g ->
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.allGradients.forEach { p ->
                val cols = p.resolve(vm.foreground, vm.background)
                val sel = cols == g.colors && (p.positions ?: emptyList<Float>()) == (g.positions ?: emptyList<Float>())
                GradientTile(vm, p, sel, 52.dp, 26.dp, onPick = {
                    set("Gradient fill", false) { it.copy(fillGradient = it.fillGradient?.copy(colors = cols, positions = p.positions)) }
                }, userIndex = null, onEdit = {})
            }
        }
        Spacer(Modifier.height(6.dp))
        ChipRow(com.tim.quartzeditor.engine.GradientType.entries.map { it.label }, g.type.ordinal) { i ->
            set("Gradient fill", false) { it.copy(fillGradient = it.fillGradient?.copy(type = com.tim.quartzeditor.engine.GradientType.entries[i])) }
        }
        PanelSlider("Angle", g.angle, -180f..180f, "${g.angle.roundToInt()}°",
            onChange = { v -> set("Gradient angle", true) { it.copy(fillGradient = it.fillGradient?.copy(angle = v.roundToInt().toFloat())) } }, onDone = { done("Gradient angle") })
        PanelSlider("Scale", g.scale, 0.1f..2f, "${(g.scale * 100).roundToInt()}%",
            onChange = { v -> set("Gradient scale", true) { it.copy(fillGradient = it.fillGradient?.copy(scale = v)) } }, onDone = { done("Gradient scale") })
        ToggleRow("Reverse", g.reverse) { r -> set("Gradient fill", false) { it.copy(fillGradient = it.fillGradient?.copy(reverse = r)) } }
    }
    spec.fillPattern?.let { p ->
        PatternGrid(vm, selected = p.key, colorA = p.colorA, colorB = p.colorB) { key ->
            val a = vm.foreground or 0xFF000000.toInt()
            val b = vm.background or 0xFF000000.toInt()
            vm.warmPattern(key, a, b)
            set("Pattern fill", false) { it.copy(fillPattern = it.fillPattern?.copy(key = key, colorA = a, colorB = b)) }
        }
        PanelSlider("Pattern size", p.scale, 0.1f..4f, "${(p.scale * 100).roundToInt()}%",
            onChange = { v -> set("Pattern size", true) { it.copy(fillPattern = it.fillPattern?.copy(scale = v)) } }, onDone = { done("Pattern size") })
    }
}

// ===================================================================== the options bar (menu bar layout)

/** A label for the options bar. */
@Composable
private fun BarLabel(text: String) {
    Text(text, color = Q.textDim, fontSize = 12.sp)
}

/** A small drop-down for the options bar: [current] shown, [items] listed. */
@Composable
private fun BarDropdown(current: String, items: List<String>, width: androidx.compose.ui.unit.Dp = 110.dp, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(8.dp))
                .background(Q.field)
                .clickable { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(current, color = Q.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Q.textDim, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, label ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onPick(i)
                })
            }
        }
    }
}

/**
 * Shape options for the options bar: fill, outline (color, width, solid/dashed/dotted) and a
 * rectangle's corner radius. Like [ShapeOptions], they change the selected shape layer and are what
 * new shapes are drawn with.
 */
@Composable
fun ShapeBarOptions(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val layer = vm.doc?.active?.takeIf { it.isShape }
    val spec = layer?.props?.shape ?: vm.shapeStyle.copy(kind = vm.shapeKind)
    var picking by remember { mutableStateOf<String?>(null) }
    fun set(label: String, live: Boolean = false, change: (ShapeSpec) -> ShapeSpec) {
        vm.shapeStyle = change(vm.shapeStyle)
        if (layer != null) {
            if (live) vm.editVectorLive(layer.id) { p -> p.copy(shape = p.shape?.let(change)) }
            else vm.editVector(layer.id, label) { p -> p.copy(shape = p.shape?.let(change)) }
            vm.resetShapeSwitches()
        } else if (!live) {
            vm.saveVectorPrefs()
        }
    }
    fun done(label: String) {
        if (layer != null) vm.commitVector(label)
        vm.saveVectorPrefs()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        val canFill = spec.kind != ShapeKind.PATH || spec.closed
        if (canFill) {
            BarLabel("Fill")
            if (spec.fill != null) ColorChip(spec.fill) { picking = "fill" }
            BarChip(if (spec.fill != null) "On" else "Off", spec.fill != null) {
                val on = spec.fill == null
                if (!on) vm.lastShapeFill = spec.fill
                val c = vm.lastShapeFill ?: vm.foreground
                set(if (on) "Fill on" else "Fill off") { it.copy(fill = if (on) c else null) }
            }
            Spacer(Modifier.width(6.dp))
        }
        BarLabel("Stroke")
        if (spec.stroke != null) ColorChip(spec.stroke) { picking = "stroke" }
        BarChip(if (spec.stroke != null) "On" else "Off", spec.stroke != null) {
            val on = spec.stroke == null
            if (!on) vm.lastShapeStroke = spec.stroke
            val c = vm.lastShapeStroke ?: AColor.BLACK
            set(if (on) "Outline on" else "Outline off") { it.copy(stroke = if (on) c else null) }
        }
        if (spec.hasStroke) {
            PanelField(
                formatPx(spec.strokeWidth),
                onCommit = { s -> s.toFloatOrNull()?.let { v -> set("Outline width") { it.copy(strokeWidth = v.coerceIn(0.5f, 300f)) } } },
                numeric = true,
                modifier = Modifier.width(56.dp),
            )
            BarLabel("px")
            val dashes = listOf<List<Float>?>(null, listOf(3f, 2f), listOf(0f, 2f))
            val which = dashes.indexOfFirst { it == spec.dash }.let { if (it < 0) 1 else it }
            val styles = listOf("Solid", "Dashed", "Dotted")
            BarDropdown(styles[which], styles, width = 96.dp) { i -> set("Outline style") { it.copy(dash = dashes[i]) } }
        }
        if (spec.kind == ShapeKind.RECT) {
            Spacer(Modifier.width(6.dp))
            BarLabel("Corners")
            PanelField(
                spec.radius.roundToInt().toString(),
                onCommit = { s -> s.toFloatOrNull()?.let { v -> set("Corner radius") { it.copy(radius = v.coerceIn(0f, 5000f)) } } },
                numeric = true,
                modifier = Modifier.width(56.dp),
            )
            BarLabel("px")
        }
    }
    picking?.let { which ->
        val start = (if (which == "fill") spec.fill else spec.stroke) ?: vm.foreground
        ColorPickDialog(
            vm, start, title = if (which == "fill") "Fill color" else "Outline color",
            onLive = { c -> set("Color", live = true) { if (which == "fill") it.copy(fill = c) else it.copy(stroke = c) } },
            onDone = {
                picking = null
                done(if (which == "fill") "Fill color" else "Outline color")
            },
        )
    }
}

private val WEIGHTS = listOf(100 to "Thin", 200 to "Extra light", 300 to "Light", 400 to "Regular", 500 to "Medium", 600 to "Semibold", 700 to "Bold", 800 to "Extra bold", 900 to "Black")

/**
 * Text options for the options bar: font, weight, size, alignment, color and (for a text layer)
 * its warp. Like [TextOptions], they change the selected text layer and are what new text starts with.
 */
@Composable
fun TextBarOptions(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val layer = vm.doc?.active?.takeIf { it.isText }
    val spec = layer?.props?.text ?: vm.textStyle
    var picking by remember { mutableStateOf(false) }
    var fonts by remember { mutableStateOf(false) }
    fun set(label: String, live: Boolean = false, change: (TextSpec) -> TextSpec) {
        vm.textStyle = change(vm.textStyle)
        if (layer != null) {
            if (live) vm.editVectorLive(layer.id) { p -> p.copy(text = p.text?.let(change)) }
            else vm.editVector(layer.id, label) { p -> p.copy(text = p.text?.let(change)) }
        }
        if (!live) vm.saveVectorPrefs()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        // Font
        val fontVersion by Fonts.version.collectAsState()
        val tf = if (fontVersion >= 0) Fonts.cached(spec.fontKey) else null
        val google = spec.fontKey.startsWith("g:")
        val family = if (google) spec.fontKey.removePrefix("g:").substringBefore('@') else null
        Text(
            if (family != null) family else Fonts.label(spec.fontKey),
            color = Q.text,
            fontSize = 14.sp,
            fontFamily = tf?.let { FontFamily(ComposeTypeface(it)) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Q.field)
                .clickable { fonts = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        // Weight: a downloaded font's own weights; any other font can be made bold
        if (family != null) {
            val style = spec.fontKey.substringAfter('@', "")
            val weight = style.trimEnd('i').toIntOrNull() ?: 400
            val italic = style.endsWith('i')
            fun key(w: Int, it: Boolean) = "g:$family" + if (w == 400 && !it) "" else "@$w" + (if (it) "i" else "")
            BarDropdown(WEIGHTS.firstOrNull { it.first == weight }?.second ?: "$weight", WEIGHTS.map { it.second }, width = 110.dp) { i ->
                set("Font weight") { it.copy(fontKey = key(WEIGHTS[i].first, italic)) }
            }
            IconToggle(Icons.Outlined.FormatItalic, "Italic", italic) { set("Italic") { it.copy(fontKey = key(weight, !italic)) } }
        } else {
            IconToggle(Icons.Outlined.FormatBold, "Bold", spec.fauxBold) { set("Bold") { it.copy(fauxBold = !it.fauxBold) } }
            IconToggle(Icons.Outlined.FormatItalic, "Italic", spec.fauxItalic) { set("Italic") { it.copy(fauxItalic = !it.fauxItalic) } }
        }
        // Size
        PanelField(
            if (spec.size > 0f) spec.size.roundToInt().toString() else "Auto",
            onCommit = { s -> s.toFloatOrNull()?.let { v -> set("Text size") { it.copy(size = v.coerceIn(1f, 4000f)) } } },
            numeric = true,
            modifier = Modifier.width(60.dp),
        )
        BarLabel("px")
        // Alignment
        val aligns = listOf(Icons.Outlined.FormatAlignLeft, Icons.Outlined.FormatAlignCenter, Icons.Outlined.FormatAlignRight)
        TextAlignOption.entries.forEachIndexed { i, a ->
            IconToggle(aligns[i], "Align ${a.label.lowercase()}", spec.align == a) { set("Align text") { it.copy(align = a) } }
        }
        // Color
        ColorChip(spec.color) { picking = true }
        // Warp (a text layer's own; new text starts straight)
        if (layer != null) {
            BarLabel("Warp")
            val w = spec.warp
            val styles = listOf("None") + com.tim.quartzeditor.engine.WarpStyle.entries.map { it.label }
            BarDropdown(w?.style?.label ?: "None", styles, width = 120.dp) { i ->
                vm.editVector(layer.id, "Warp text") { p ->
                    p.copy(text = p.text?.let { t ->
                        t.copy(warp = if (i == 0) null else com.tim.quartzeditor.engine.WarpStyle.entries[i - 1].let { st -> t.warp?.copy(style = st) ?: com.tim.quartzeditor.engine.TextWarp(st) })
                    })
                }
            }
        }
    }
    if (picking) {
        ColorPickDialog(
            vm, spec.color, title = "Text color",
            onLive = { c -> set("Text color", live = true) { it.copy(color = c) } },
            onDone = {
                picking = false
                if (layer != null) vm.commitVector("Text color")
                vm.saveVectorPrefs()
            },
        )
    }
    if (fonts) {
        FontPickDialog(spec.fontKey, onDismiss = { fonts = false }) { key ->
            fonts = false
            set("Font") { it.copy(fontKey = key) }
        }
    }
}
