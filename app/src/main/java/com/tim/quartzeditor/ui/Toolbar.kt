package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Approval
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Polyline
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Hexagon
import androidx.compose.material.icons.outlined.East
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Title
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import com.tim.quartzeditor.engine.SelectShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tim.quartzeditor.engine.BrushSizeScale
import com.tim.quartzeditor.engine.BrushTip
import com.tim.quartzeditor.engine.Dabs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The tools on the left. Picking Brush or Eraser opens a second column beside it with the brush
 * tips and the softness, size and opacity sliders; any other tool folds it away again.
 * [onColorTap] opens the Color panel for the swatch you tapped.
 */
@Composable
fun Toolbar(vm: EditorViewModel, compact: Boolean, onColorTap: () -> Unit, modifier: Modifier = Modifier) {
    val buttonSize = if (compact) 40.dp else 46.dp
    val showBrush = (vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER || vm.tool == Tool.RETOUCH) && !vm.brushColumnHidden
    Row(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(Q.toolbar)
    ) {
        ToolColumn(vm, compact, buttonSize, onColorTap)
        AnimatedVisibility(showBrush, enter = expandHorizontally(), exit = shrinkHorizontally()) {
            Row {
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 18.dp).background(Q.text.copy(alpha = 0.12f)))
                BrushColumn(vm, compact)
            }
        }
    }
}

@Composable
private fun ToolColumn(vm: EditorViewModel, compact: Boolean, maxButton: Dp, onColorTap: () -> Unit) {
    BoxWithConstraints(Modifier.width(if (compact) 52.dp else 62.dp).fillMaxHeight()) {
        // Buttons shrink to fit the screen's height: 12 tools (each with 6dp of space) and the two
        // color swatches (0.9 of a button each), plus padding. Only a very short screen scrolls.
        val fitted = (maxHeight - 124.dp) / 14.8f
        val buttonSize = fitted.coerceIn(MIN_TOOL_BUTTON, maxButton)
        val swatchSize = buttonSize * 0.9f
        val tight = fitted < MIN_TOOL_BUTTON
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                .padding(vertical = 10.dp),
        ) {
            val t = vm.tool
            val moveIcon = if (t == Tool.PAN) Icons.Outlined.PanTool else QIcons.Move
            ToolButton(
                moveIcon, if (t == Tool.PAN) "Pan (tap again to move layers)" else "Move layer (tap again to pan)", t == Tool.MOVE || t == Tool.PAN, buttonSize,
                options = listOf(
                    ToolOption(QIcons.Move, "Move layer", t == Tool.MOVE) { vm.chooseMove(pan = false) },
                    ToolOption(Icons.Outlined.PanTool, "Pan", t == Tool.PAN) { vm.chooseMove(pan = true) },
                ),
            ) { vm.onToolButton(Tool.MOVE) }
            ToolButton(
                selectIcon(vm.selectShape), "Select: ${vm.selectShape.label} (tap again for the next shape, hold for all)", t == Tool.SELECT, buttonSize,
                options = SelectShape.entries.map { s -> ToolOption(selectIcon(s), s.label, t == Tool.SELECT && vm.selectShape == s) { vm.chooseSelectShape(s) } } +
                    listOf(
                        ToolOption(Icons.Outlined.PersonOutline, "Select subject", false, divider = true) { vm.selectSubject(background = false) },
                        ToolOption(Icons.Outlined.Landscape, "Select background", false) { vm.selectSubject(background = true) },
                        ToolOption(Icons.Outlined.InvertColors, "Invert selection", false) { vm.invertSelection() },
                    ),
            ) { vm.onToolButton(Tool.SELECT) }
            ToolButton(Icons.Outlined.Crop, "Crop", t == Tool.CROP, buttonSize) { vm.onToolButton(Tool.CROP) }
            ToolButton(
                if (t == Tool.WARP) warpIcon(vm.warpKind) else QIcons.Transform,
                if (t == Tool.WARP) "${vm.warpKind.label} (hold for Transform and the others)" else "Transform (hold for Warp, Puppet Warp, Liquify)",
                t == Tool.TRANSFORM || t == Tool.WARP, buttonSize,
                options = listOf(ToolOption(QIcons.Transform, "Transform", t == Tool.TRANSFORM) { vm.onToolButton(Tool.TRANSFORM) }) +
                    com.tim.quartzeditor.engine.WarpKind.entries.map { k ->
                        ToolOption(warpIcon(k), k.label, t == Tool.WARP && vm.warpKind == k) { vm.chooseWarp(k) }
                    },
            ) { if (t == Tool.WARP) vm.beginWarp() else vm.onToolButton(Tool.TRANSFORM) }
            ToolButton(Icons.Outlined.Colorize, "Eyedropper", t == Tool.EYEDROPPER, buttonSize) { vm.onToolButton(Tool.EYEDROPPER) }
            ToolButton(
                if (vm.bucketPattern) Icons.Outlined.Texture else Icons.Outlined.FormatColorFill,
                if (vm.bucketPattern) "Pattern fill (tap again for the paint bucket)" else "Paint bucket (tap again for pattern fill)", t == Tool.BUCKET, buttonSize,
                options = listOf(
                    ToolOption(Icons.Outlined.FormatColorFill, "Paint bucket", t == Tool.BUCKET && !vm.bucketPattern) { vm.chooseBucket(pattern = false) },
                    ToolOption(Icons.Outlined.Texture, "Pattern fill", t == Tool.BUCKET && vm.bucketPattern) { vm.chooseBucket(pattern = true) },
                ),
            ) { vm.onToolButton(Tool.BUCKET) }
            ToolButton(Icons.Outlined.Gradient, "Gradient", t == Tool.GRADIENT, buttonSize) { vm.onToolButton(Tool.GRADIENT) }
            ToolButton(
                shapeIcon(vm.shapeKind), "Shape: ${vm.shapeKind.label} (tap again for the next shape, hold for all)", t == Tool.SHAPE, buttonSize,
                options = SHAPE_CHOICES.map { k -> ToolOption(shapeIcon(k), k.label, t == Tool.SHAPE && vm.shapeKind == k) { vm.chooseShapeKind(k) } },
            ) { vm.onToolButton(Tool.SHAPE) }
            ToolButton(
                QIcons.Pen, "Shape pen (hold for point editing)", t == Tool.PEN, buttonSize,
                options = listOf(
                    ToolOption(QIcons.Pen, "Draw a shape", t == Tool.PEN && !vm.penEditing) { vm.choosePen(edit = false) },
                    ToolOption(Icons.Outlined.Polyline, "Edit points", t == Tool.PEN && vm.penEditing) { vm.choosePen(edit = true) },
                ),
            ) { vm.onToolButton(Tool.PEN) }
            ToolButton(Icons.Outlined.Title, "Text", t == Tool.TEXT, buttonSize) { vm.onToolButton(Tool.TEXT) }
            ToolButton(Icons.Outlined.Brush, "Brush (tap again to fold the brush column)", t == Tool.BRUSH, buttonSize, ring = true) { vm.onToolButton(Tool.BRUSH) }
            ToolButton(QIcons.Eraser, "Eraser (tap again to fold the brush column)", t == Tool.ERASER, buttonSize) { vm.onToolButton(Tool.ERASER) }
            ToolButton(
                retouchIcon(vm.retouchKind), "${vm.retouchKind.label} (hold for Clone Stamp, Dodge, Burn, Smudge, Blur, Sharpen)", t == Tool.RETOUCH, buttonSize,
                options = com.tim.quartzeditor.engine.RetouchKind.entries.mapIndexed { i, k ->
                    ToolOption(retouchIcon(k), k.label, t == Tool.RETOUCH && vm.retouchKind == k, divider = i == 2 || i == 4) { vm.chooseRetouch(k) }
                },
            ) { vm.onToolButton(Tool.RETOUCH) }
            if (!tight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
            ColorSwatches(vm, swatchSize, onColorTap)
        }
    }
}

private val MIN_TOOL_BUTTON = 30.dp

internal fun retouchIcon(k: com.tim.quartzeditor.engine.RetouchKind) = when (k) {
    com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL -> Icons.Outlined.Healing
    com.tim.quartzeditor.engine.RetouchKind.CLONE -> Icons.Outlined.Approval
    com.tim.quartzeditor.engine.RetouchKind.DODGE -> Icons.Outlined.LightMode
    com.tim.quartzeditor.engine.RetouchKind.BURN -> Icons.Outlined.LocalFireDepartment
    com.tim.quartzeditor.engine.RetouchKind.SMUDGE -> Icons.Outlined.TouchApp
    com.tim.quartzeditor.engine.RetouchKind.BLUR -> Icons.Outlined.BlurOn
    com.tim.quartzeditor.engine.RetouchKind.SHARPEN -> Icons.Outlined.Details
}

internal fun warpIcon(k: com.tim.quartzeditor.engine.WarpKind) = when (k) {
    com.tim.quartzeditor.engine.WarpKind.WARP -> Icons.Outlined.GridOn
    com.tim.quartzeditor.engine.WarpKind.PUPPET -> Icons.Outlined.PushPin
    com.tim.quartzeditor.engine.WarpKind.LIQUIFY -> Icons.Outlined.WaterDrop
}

/** Brush tips and the three sliders, for whichever of brush or eraser is in use. */
@Composable
private fun BrushColumn(vm: EditorViewModel, compact: Boolean) {
    val forTool = vm.sliderTool
    val b = when (forTool) {
        Tool.ERASER -> vm.eraser
        Tool.RETOUCH -> vm.retouchBrush
        else -> vm.brush
    }
    val retouch = forTool == Tool.RETOUCH
    val preview: (Boolean) -> Unit = { vm.brushPreview = it }
    BoxWithConstraints(Modifier.width(if (compact) 56.dp else 66.dp).fillMaxHeight()) {
        val tipsH = (if (compact) 40.dp else 46.dp) * 4 + 24.dp
        val sliderH = ((maxHeight - tipsH - 150.dp) / 3).coerceIn(56.dp, 220.dp)
        val tight = maxHeight < tipsH + 150.dp + 56.dp * 3
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                .padding(vertical = 10.dp),
        ) {
            // The retouch brushes are always round
            if (!retouch) BrushTip.entries.forEach { tip ->
                val img = remember(tip) { tipIcon(tip) }
                val sel = tip == b.tip
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(vertical = 3.dp)
                        .size(if (compact) 40.dp else 46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Q.text.copy(alpha = 0.85f) else Q.text.copy(alpha = 0.10f))
                        .clickable { vm.updateBrush(forTool) { it.copy(tip = tip) } },
                ) {
                    Image(img, contentDescription = tip.label, modifier = Modifier.size(if (compact) 28.dp else 32.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            SliderCaption("Soft")
            VerticalSlider(
                value = b.softness,
                onChange = { v -> vm.updateBrush(forTool) { it.copy(softness = v) } },
                height = sliderH,
                description = "Softness",
                onActive = preview,
            )
            ToolLabel("${(b.softness * 100).roundToInt()}%")
            Spacer(Modifier.height(6.dp))
            SliderCaption("Size")
            VerticalSlider(
                value = BrushSizeScale.toT(b.size),
                onChange = { t -> vm.updateBrush(forTool) { it.copy(size = BrushSizeScale.toPx(t).roundToInt().toFloat().coerceAtLeast(1f)) } },
                height = sliderH,
                description = "Size",
                onActive = preview,
            )
            ToolLabel("${b.size.roundToInt()}px")
            Spacer(Modifier.height(6.dp))
            SliderCaption(if (retouch) "Strength" else "Opacity")
            VerticalSlider(
                value = b.opacity,
                onChange = { v -> vm.updateBrush(forTool) { it.copy(opacity = v.coerceAtLeast(0.01f)) } },
                height = sliderH,
                description = if (retouch) "Strength" else "Opacity",
                onActive = preview,
            )
            ToolLabel("${(b.opacity * 100).roundToInt()}%")
        }
    }
}

/** A single dab of the tip, for its button. */
private fun tipIcon(tip: BrushTip): ImageBitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val dab = Dabs.make(tip, 52, if (tip == BrushTip.GRUNGE) 0.35f else 0.08f)
    Canvas(bmp).drawBitmap(dab, 6f, 6f, Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.rgb(40, 40, 40) })
    return bmp.asImageBitmap()
}

@Composable
private fun SliderCaption(text: String) {
    Text(text, color = Q.textDim, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun ToolLabel(text: String) {
    Text(text, color = Q.text, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    size: Dp,
    ring: Boolean = false,
    /** Other tools in this button: a long press lists them. */
    options: List<ToolOption>? = null,
    onClick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(vertical = 3.dp)
                .size(size)
                .clip(if (ring) CircleShape else RoundedCornerShape(10.dp))
                .background(if (selected) Q.text.copy(alpha = 0.16f) else Color.Transparent)
                .then(if (selected && ring) Modifier.border(2.dp, Q.text, CircleShape) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = if (options != null) ({ open = true }) else null),
        ) {
            Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(size * 0.55f))
            // A small corner mark: there's more in here
            if (options != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(4.dp).clip(CircleShape).background(Q.textDim))
            }
        }
        if (options != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(size + 8.dp, -size)) {
                options.forEach { o ->
                    if (o.divider) androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(o.label) },
                        leadingIcon = { Icon(o.icon, contentDescription = null) },
                        trailingIcon = if (o.current) ({ Icon(Icons.Outlined.Check, contentDescription = "Current", tint = Q.accent) }) else null,
                        onClick = {
                            open = false
                            o.onPick()
                        },
                    )
                }
            }
        }
    }
}

/** One of the tools a toolbar button holds. */
private class ToolOption(val icon: ImageVector, val label: String, val current: Boolean, val divider: Boolean = false, val onPick: () -> Unit)

private val SHAPE_CHOICES = com.tim.quartzeditor.engine.ShapeKind.TOOL_SHAPES

private fun selectIcon(s: SelectShape): ImageVector = when (s) {
    SelectShape.RECT -> QIcons.SelectRect
    SelectShape.ELLIPSE -> QIcons.SelectEllipse
    SelectShape.LASSO -> Icons.Outlined.Gesture
    SelectShape.POLYGON -> Icons.Outlined.Polyline
    SelectShape.WAND -> Icons.Outlined.AutoFixHigh
}

private fun shapeIcon(k: com.tim.quartzeditor.engine.ShapeKind): ImageVector = when (k) {
    com.tim.quartzeditor.engine.ShapeKind.ELLIPSE -> Icons.Outlined.Circle
    com.tim.quartzeditor.engine.ShapeKind.STAR -> Icons.Outlined.StarOutline
    com.tim.quartzeditor.engine.ShapeKind.TRIANGLE -> Icons.Outlined.ChangeHistory
    com.tim.quartzeditor.engine.ShapeKind.POLYGON -> Icons.Outlined.Hexagon
    com.tim.quartzeditor.engine.ShapeKind.ARROW -> Icons.Outlined.East
    else -> Icons.Outlined.Rectangle
}

/** A tall slider like the ones in the mock-up: drag up for more. */
@Composable
fun VerticalSlider(value: Float, onChange: (Float) -> Unit, height: Dp, description: String, onActive: (Boolean) -> Unit = {}) {
    val density = LocalDensity.current
    var trackPx by remember { mutableFloatStateOf(1f) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val active by rememberUpdatedState(onActive)
    val thumbH = 30.dp
    val thumbPx = with(density) { thumbH.toPx() }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .width(44.dp)
            .height(height)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var start = current
                    val y0 = down.position.y
                    val range = (trackPx - thumbPx).coerceAtLeast(1f)
                    // Tapping away from the thumb jumps there
                    val thumbY = (1f - current) * range
                    if (down.position.y < thumbY - 4f || down.position.y > thumbY + thumbPx + 4f) {
                        start = (1f - (down.position.y - thumbPx / 2f) / range).coerceIn(0f, 1f)
                        change(start)
                    }
                    down.consume()
                    active(true)
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            change((start - (c.position.y - y0) / range).coerceIn(0f, 1f))
                            c.consume()
                        }
                    } finally {
                        active(false)
                    }
                }
            },
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .padding(vertical = thumbH / 2)
                .clip(RoundedCornerShape(2.dp))
                .background(Q.text.copy(alpha = 0.35f))
        )
        val range = (with(density) { height.toPx() } - thumbPx).coerceAtLeast(0f)
        Box(
            Modifier
                .offset { IntOffset(0, ((1f - value.coerceIn(0f, 1f)) * range).roundToInt()) }
                .size(width = 36.dp, height = thumbH)
                .clip(RoundedCornerShape(9.dp))
                .background(Q.text)
        )
    }
}

/**
 * Foreground (top) and background colors. Tap one to edit it in the Color panel; swipe up or
 * down on either to swap them.
 */
@Composable
private fun ColorSwatches(vm: EditorViewModel, size: Dp, onColorTap: () -> Unit) {
    val density = LocalDensity.current
    val swipePx = with(density) { 22.dp.toPx() }
    @Suppress("UNUSED_VARIABLE") val tap by rememberUpdatedState(onColorTap)
    // Tapping a swatch opens a color picker beside the toolbar
    var picking by remember { mutableStateOf<ColorSlot?>(null) }
    val gap = with(density) { (size + 24.dp).roundToPx() }
    picking?.let { slot ->
        ColorPickDialog(
            vm,
            initial = if (slot == ColorSlot.FOREGROUND) vm.foreground else vm.background,
            title = if (slot == ColorSlot.FOREGROUND) "Foreground" else "Background",
            onLive = { vm.setColor(slot, it) },
            onDone = { picking = null },
            popupOffset = androidx.compose.ui.unit.IntOffset(gap, 0),
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var swapped = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (!swapped && abs(c.position.y - down.position.y) > swipePx) {
                            vm.swapColors()
                            swapped = true
                        }
                        c.consume()
                    }
                    if (!swapped) {
                        val half = this.size.height / 2f
                        vm.colorSlot = if (down.position.y < half) ColorSlot.FOREGROUND else ColorSlot.BACKGROUND
                        picking = vm.colorSlot
                    }
                }
            },
    ) {
        Swatch(Color(vm.foreground), vm.colorSlot == ColorSlot.FOREGROUND, size)
        Swatch(Color(vm.background), vm.colorSlot == ColorSlot.BACKGROUND, size)
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, size: Dp) {
    Box(
        Modifier
            .size(size)
            .border(if (selected) 3.dp else 1.dp, if (selected) Q.accent else Q.text.copy(alpha = 0.5f), CircleShape)
            .padding(if (selected) 4.dp else 1.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** A sample squiggle drawn with the real brush engine. */
private fun tipPreview(tip: BrushTip): ImageBitmap {
    val w = 208
    val h = 92
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val size = 22
    val dab = Dabs.make(tip, size, if (tip == BrushTip.GRUNGE) 0.3f else 0.15f)
    val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.BLACK }
    val rng = java.util.Random(7)
    var x = 18f
    val step = size * tip.spacing.coerceAtLeast(0.08f)
    while (x < w - 18f) {
        val y = h / 2f + sin(x / w * 2 * Math.PI).toFloat() * h * 0.25f
        c.save()
        if (tip.randomRotation) c.rotate(rng.nextFloat() * 360f, x, y)
        c.drawBitmap(dab, x - size / 2f, y - size / 2f, p)
        c.restore()
        x += step
    }
    return bmp.asImageBitmap()
}
