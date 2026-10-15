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
 * The tool column on the left: tools, brush softness and size, and the two colors.
 * [onColorTap] opens the Color panel for the swatch you tapped.
 */
@Composable
fun Toolbar(vm: EditorViewModel, compact: Boolean, onColorTap: () -> Unit, modifier: Modifier = Modifier) {
    val buttonSize = if (compact) 40.dp else 48.dp
    BoxWithConstraints(
        modifier
            .width(if (compact) 52.dp else 64.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(Q.toolbar)
    ) {
        val availH = maxHeight
        val tight = availH < 560.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight())
                .padding(vertical = 10.dp),
        ) {
            val moveIcon = if (vm.tool == Tool.PAN) Icons.Outlined.PanTool else QIcons.Move
            ToolButton(moveIcon, if (vm.tool == Tool.PAN) "Pan (tap again to move layers)" else "Move layer (tap again to pan)", vm.tool == Tool.MOVE || vm.tool == Tool.PAN, buttonSize) { vm.onToolButton(Tool.MOVE) }
            ToolButton(Icons.Outlined.Colorize, "Eyedropper", vm.tool == Tool.EYEDROPPER, buttonSize) { vm.onToolButton(Tool.EYEDROPPER) }
            Spacer(Modifier.height(6.dp))
            Box {
                ToolButton(Icons.Outlined.Brush, "Brush (tap again for brush tips)", vm.tool == Tool.BRUSH, buttonSize, ring = true) { vm.onToolButton(Tool.BRUSH) }
                if (vm.brushPickerFor == Tool.BRUSH) BrushPicker(vm, Tool.BRUSH, buttonSize)
            }
            Box {
                ToolButton(QIcons.Eraser, "Eraser (tap again for brush tips)", vm.tool == Tool.ERASER, buttonSize) { vm.onToolButton(Tool.ERASER) }
                if (vm.brushPickerFor == Tool.ERASER) BrushPicker(vm, Tool.ERASER, buttonSize)
            }

            val sliderH = if (tight) 120.dp else ((availH - buttonSize * 4 - 210.dp) / 2).coerceIn(90.dp, 240.dp)
            val b = if (vm.sliderTool == Tool.ERASER) vm.eraser else vm.brush
            Spacer(Modifier.height(12.dp))
            VerticalSlider(
                value = b.softness,
                onChange = { v -> vm.updateBrush(vm.sliderTool) { it.copy(softness = v) } },
                height = sliderH,
                description = "Softness",
            )
            ToolLabel("${(b.softness * 100).roundToInt()}%")
            Spacer(Modifier.height(10.dp))
            VerticalSlider(
                value = BrushSizeScale.toT(b.size),
                onChange = { t -> vm.updateBrush(vm.sliderTool) { it.copy(size = BrushSizeScale.toPx(t).roundToInt().toFloat().coerceAtLeast(1f)) } },
                height = sliderH,
                description = "Size",
            )
            ToolLabel("${b.size.roundToInt()}px")
            if (!tight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
            ColorSwatches(vm, if (compact) 36.dp else 44.dp, onColorTap)
        }
    }
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
fun VerticalSlider(value: Float, onChange: (Float) -> Unit, height: Dp, description: String) {
    val density = LocalDensity.current
    var trackPx by remember { mutableFloatStateOf(1f) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
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
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        change((start - (c.position.y - y0) / range).coerceIn(0f, 1f))
                        c.consume()
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

// ===================================================================== brush tips

/** The "Brushes" pop-up next to the brush or eraser button. */
@Composable
private fun BrushPicker(vm: EditorViewModel, forTool: Tool, buttonSize: Dp) {
    val density = LocalDensity.current
    val dx = with(density) { (buttonSize + 20.dp).roundToPx() }
    val current = if (forTool == Tool.ERASER) vm.eraser else vm.brush
    Popup(offset = IntOffset(dx, 0), onDismissRequest = { vm.brushPickerFor = null }, properties = PopupProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Q.panel)
                .border(1.dp, Q.divider, RoundedCornerShape(18.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (forTool == Tool.ERASER) "Eraser tips" else "Brushes", color = Q.text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            BrushTip.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { tip ->
                        val img = remember(tip) { tipPreview(tip) }
                        val sel = tip == current.tip
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFDADADA))
                                .border(if (sel) 3.dp else 0.dp, if (sel) Q.accent else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    vm.updateBrush(forTool) { it.copy(tip = tip) }
                                    vm.brushPickerFor = null
                                }
                                .padding(6.dp),
                        ) {
                            Image(img, contentDescription = tip.label, modifier = Modifier.size(width = 104.dp, height = 46.dp))
                            Text(tip.label, color = Color(0xFF222222), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
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
