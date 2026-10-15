package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Polyline
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
    val showBrush = (vm.tool == Tool.BRUSH || vm.tool == Tool.ERASER) && !vm.brushColumnHidden
    Row(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(Q.toolbar)
    ) {
        ToolColumn(vm, compact, buttonSize, onColorTap)
        AnimatedVisibility(showBrush, enter = expandHorizontally(), exit = shrinkHorizontally()) {
            Row {
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 18.dp).background(Color.White.copy(alpha = 0.12f)))
                BrushColumn(vm, compact)
            }
        }
    }
}

@Composable
private fun ToolColumn(vm: EditorViewModel, compact: Boolean, buttonSize: Dp, onColorTap: () -> Unit) {
    BoxWithConstraints(Modifier.width(if (compact) 52.dp else 62.dp).fillMaxHeight()) {
        val tight = maxHeight < buttonSize * 9 + 190.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                .padding(vertical = 10.dp),
        ) {
            val t = vm.tool
            val moveIcon = if (t == Tool.PAN) Icons.Outlined.PanTool else QIcons.Move
            ToolButton(moveIcon, if (t == Tool.PAN) "Pan (tap again to move layers)" else "Move layer (tap again to pan)", t == Tool.MOVE || t == Tool.PAN, buttonSize) { vm.onToolButton(Tool.MOVE) }
            val selIcon = when (vm.selectShape) {
                SelectShape.RECT -> QIcons.SelectRect
                SelectShape.ELLIPSE -> QIcons.SelectEllipse
                SelectShape.LASSO -> Icons.Outlined.Gesture
                SelectShape.POLYGON -> Icons.Outlined.Polyline
            }
            ToolButton(selIcon, "Select: ${vm.selectShape.label} (tap again for the next shape)", t == Tool.SELECT, buttonSize) { vm.onToolButton(Tool.SELECT) }
            ToolButton(Icons.Outlined.Crop, "Crop", t == Tool.CROP, buttonSize) { vm.onToolButton(Tool.CROP) }
            ToolButton(Icons.Outlined.Transform, "Transform (scale, rotate, flip, distort)", t == Tool.TRANSFORM, buttonSize) { vm.onToolButton(Tool.TRANSFORM) }
            ToolButton(Icons.Outlined.Colorize, "Eyedropper", t == Tool.EYEDROPPER, buttonSize) { vm.onToolButton(Tool.EYEDROPPER) }
            ToolButton(Icons.Outlined.FormatColorFill, "Paint bucket", t == Tool.BUCKET, buttonSize) { vm.onToolButton(Tool.BUCKET) }
            ToolButton(Icons.Outlined.Gradient, "Gradient", t == Tool.GRADIENT, buttonSize) { vm.onToolButton(Tool.GRADIENT) }
            ToolButton(Icons.Outlined.Brush, "Brush (tap again to fold the brush column)", t == Tool.BRUSH, buttonSize, ring = true) { vm.onToolButton(Tool.BRUSH) }
            ToolButton(QIcons.Eraser, "Eraser (tap again to fold the brush column)", t == Tool.ERASER, buttonSize) { vm.onToolButton(Tool.ERASER) }
            if (!tight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
            ColorSwatches(vm, if (compact) 34.dp else 42.dp, onColorTap)
        }
    }
}

/** Brush tips and the three sliders, for whichever of brush or eraser is in use. */
@Composable
private fun BrushColumn(vm: EditorViewModel, compact: Boolean) {
    val forTool = vm.sliderTool
    val b = if (forTool == Tool.ERASER) vm.eraser else vm.brush
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
            BrushTip.entries.forEach { tip ->
                val img = remember(tip) { tipIcon(tip) }
                val sel = tip == b.tip
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(vertical = 3.dp)
                        .size(if (compact) 40.dp else 46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f))
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
            SliderCaption("Opacity")
            VerticalSlider(
                value = b.opacity,
                onChange = { v -> vm.updateBrush(forTool) { it.copy(opacity = v.coerceAtLeast(0.01f)) } },
                height = sliderH,
                description = "Opacity",
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

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, size: Dp, ring: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 3.dp)
            .size(size)
            .clip(if (ring) CircleShape else RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .then(if (selected && ring) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(size * 0.55f))
    }
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
                .background(Color.White.copy(alpha = 0.35f))
        )
        val range = (with(density) { height.toPx() } - thumbPx).coerceAtLeast(0f)
        Box(
            Modifier
                .offset { IntOffset(0, ((1f - value.coerceIn(0f, 1f)) * range).roundToInt()) }
                .size(width = 36.dp, height = thumbH)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
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
    val tap by rememberUpdatedState(onColorTap)
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
                        tap()
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
            .border(if (selected) 3.dp else 1.dp, if (selected) Q.accent else Color.White.copy(alpha = 0.5f), CircleShape)
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
