package com.tim.quartzeditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.tim.quartzeditor.engine.BrushSizeScale
import com.tim.quartzeditor.engine.BrushTip
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// The big-screen layout's own parts: a menu bar, a bar with the tool's settings under it, and a
// status bar at the bottom. Plus what makes a pen feel at home anywhere: names shown when it hovers
// over a button, and its side button opening an item's menu (like a right click).

/** Which layout the editor uses. */
enum class UiLayout(val label: String) { AUTO("Automatic"), DESKTOP("Menu bar"), CLASSIC("Classic") }

/** The menu bar layout, for this screen [w] × [h] (dp). */
internal fun EditorViewModel.desktopFor(w: Float, h: Float): Boolean = when (uiLayout) {
    UiLayout.AUTO -> w >= 900f && h >= 560f
    UiLayout.DESKTOP -> w >= 600f
    UiLayout.CLASSIC -> false
}

// ===================================================================== menu bar

@Composable
fun MenuBar(vm: EditorViewModel, a: MenuActions, panelsOpen: Boolean, togglePanels: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Q.toolbar)
            .padding(horizontal = 6.dp),
    ) {
        MenuTitle("Quartz", bold = true) { AppMenuItems(vm, a) }
        MenuTitle("File") { FileMenuItems(vm, a) }
        MenuTitle("Edit") { EditMenuItems(vm, a) }
        MenuTitle("Image") { ImageMenuItems(vm, a) }
        MenuTitle("Layer") { LayerMenuItems(vm, a) }
        MenuTitle("Select") { SelectMenuItems(vm) }
        MenuTitle("Filter") { FilterMenuItems(vm, a) }
        MenuTitle("View") { ViewMenuItems(vm, a) }
        MenuTitle("Window") { WindowMenuItems(vm, panelsOpen, togglePanels) }
        Text(
            vm.rootDoc?.name ?: "",
            color = Q.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        val h = vm.history
        @Suppress("UNUSED_VARIABLE") val v = h.version
        BarIconButton(Icons.AutoMirrored.Outlined.Undo, "Undo (two-finger tap)", enabled = h.canUndo || vm.pendingFx != null || vm.enhance != null || vm.textEdit != null || vm.penPoints.isNotEmpty()) { vm.undo() }
        BarIconButton(Icons.AutoMirrored.Outlined.Redo, "Redo (three-finger tap)", enabled = h.canRedo) { vm.redo() }
    }
}

@Composable
private fun MenuTitle(label: String, bold: Boolean = false, content: @Composable PagedMenuScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            label,
            color = Q.text,
            fontSize = 14.sp,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (open) Q.text.copy(alpha = 0.14f) else Color.Transparent)
                .clickable { open = true }
                .padding(horizontal = 11.dp, vertical = 7.dp),
        )
        PagedDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 620.dp)) { content() }
    }
}

@Composable
private fun BarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    HoverTip(label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) Q.text else Q.textDim.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

// ===================================================================== tool options bar

/** What the tool in use is called, for the options bar. */
private fun toolTitle(vm: EditorViewModel): String = when (vm.tool) {
    Tool.MOVE -> "Move"
    Tool.PAN -> "Hand"
    Tool.SELECT -> vm.selectShape.label
    Tool.CROP -> "Crop"
    Tool.TRANSFORM -> "Transform"
    Tool.EYEDROPPER -> "Eyedropper"
    Tool.BUCKET -> if (vm.bucketPattern) "Pattern fill" else "Paint bucket"
    Tool.GRADIENT -> "Gradient"
    Tool.SHAPE -> "Shape"
    Tool.PEN -> if (vm.penCurvature) "Curvature pen" else "Pen"
    Tool.TEXT -> "Text"
    Tool.BRUSH -> "Brush"
    Tool.ERASER -> "Eraser"
    Tool.WARP -> vm.warp?.kind?.label ?: "Warp"
    Tool.RETOUCH -> vm.retouchKind.label
}

/**
 * Under the menu bar: the settings of the tool in use. Brushes get their tip, size, opacity,
 * softness, smoothing, tapers and pen switches here; the other tools show their bar (selection
 * modes, transform, crop, warp, pen, text) here instead of over the picture.
 */
@Composable
fun ToolOptionsBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Q.toolbar)
            .padding(start = 14.dp, end = 6.dp),
    ) {
        Text(toolTitle(vm), color = Q.text, fontSize = 14.sp, maxLines = 1)
        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(24.dp).background(Q.divider))
        when (vm.tool) {
            Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> BrushOptions(vm, Modifier.weight(1f))
            else -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                // At most one of these is showing (each knows when it's needed)
                TransformBar(vm)
                SelectBar(vm)
                CropBar(vm)
                WarpBar(vm)
                PenBar(vm)
                TextBar(vm)
                if (vm.tool == Tool.MOVE || vm.tool == Tool.PAN || vm.tool == Tool.EYEDROPPER || vm.tool == Tool.BUCKET || vm.tool == Tool.GRADIENT || vm.tool == Tool.SHAPE) {
                    Text("More settings in Properties", color = Q.textDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BrushOptions(vm: EditorViewModel, modifier: Modifier) {
    val forTool = vm.sliderTool
    val b = when (forTool) {
        Tool.ERASER -> vm.eraser
        Tool.RETOUCH -> vm.retouchBrush
        else -> vm.brush
    }
    val retouch = forTool == Tool.RETOUCH
    val preview: (Boolean) -> Unit = { vm.brushPreview = it }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        if (!retouch) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BrushTip.entries.forEach { tip ->
                val img = remember(tip) { tipIcon(tip) }
                HoverTip(tip.label) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (tip == b.tip) Q.text.copy(alpha = 0.85f) else Q.text.copy(alpha = 0.10f))
                            .clickable { vm.updateBrush(forTool) { it.copy(tip = tip) } },
                    ) { Image(img, contentDescription = tip.label, modifier = Modifier.size(24.dp)) }
                }
            }
        }
        OptionSlider("Size", BrushSizeScale.toT(b.size), "${b.size.roundToInt()}px", onActive = preview) { t ->
            vm.updateBrush(forTool) { it.copy(size = BrushSizeScale.toPx(t).roundToInt().toFloat().coerceAtLeast(1f)) }
        }
        OptionSlider(if (retouch) "Strength" else "Opacity", b.opacity, "${(b.opacity * 100).roundToInt()}%", onActive = preview) { v ->
            vm.updateBrush(forTool) { it.copy(opacity = v.coerceAtLeast(0.01f)) }
        }
        OptionSlider("Soft", b.softness, "${(b.softness * 100).roundToInt()}%", onActive = preview) { v ->
            vm.updateBrush(forTool) { it.copy(softness = v) }
        }
        OptionSlider("Smoothing", b.smoothing, "${(b.smoothing * 100).roundToInt()}%") { v ->
            vm.updateBrush(forTool) { it.copy(smoothing = v) }
        }
        if (!retouch) {
            OptionSlider("Taper in", b.taperStart, "${(b.taperStart * 100).roundToInt()}%") { v -> vm.updateBrush(forTool) { it.copy(taperStart = v) } }
            OptionSlider("Taper out", b.taperEnd, "${(b.taperEnd * 100).roundToInt()}%") { v -> vm.updateBrush(forTool) { it.copy(taperEnd = v) } }
        }
        if (vm.penSeen) {
            Text("Pressure", color = Q.textDim, fontSize = 12.sp)
            if (!retouch) OptionChip("Size", b.pressureSize, Icons.Outlined.Draw) { vm.updateBrush(forTool) { it.copy(pressureSize = !b.pressureSize) } }
            OptionChip(if (retouch) "Strength" else "Opacity", b.pressureOpacity, Icons.Outlined.Draw) { vm.updateBrush(forTool) { it.copy(pressureOpacity = !b.pressureOpacity) } }
        }
    }
}

/** A labeled slider for the options bar, with its value. [onActive]: being dragged (true) or let go (false). */
@Composable
private fun OptionSlider(label: String, value: Float, display: String, onActive: (Boolean) -> Unit = {}, onChange: (Float) -> Unit) {
    val active by rememberUpdatedState(onActive)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Q.textDim, fontSize = 12.sp)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = {
                active(true)
                onChange(it)
            },
            onValueChangeFinished = { active(false) },
            colors = SliderDefaults.colors(thumbColor = Q.text, activeTrackColor = Q.accent, inactiveTrackColor = Q.text.copy(alpha = 0.2f)),
            modifier = Modifier.width(112.dp).padding(horizontal = 6.dp),
        )
        Text(display, color = Q.text, fontSize = 12.sp, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun OptionChip(label: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Q.accent else Q.text.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (on) Color.White else Q.textDim, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = if (on) Color.White else Q.text, fontSize = 12.sp)
    }
}

// ===================================================================== status bar

/** At the bottom: zoom, how far the canvas is turned, its size, the layer, and quick switches. */
@Composable
fun StatusBar(vm: EditorViewModel, panelsOpen: Boolean, togglePanels: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Q.toolbar)
            .padding(horizontal = 6.dp),
    ) {
        ZoomStatus(vm)
        RotationStatus(vm)
        val d = vm.doc
        StatusText("${d?.width ?: 0} × ${d?.height ?: 0} px")
        d?.active?.let { StatusText(it.props.name, Modifier.weight(1f, fill = false)) }
        Spacer(Modifier.weight(1f))
        if (vm.penSeen) {
            val nav = vm.penFingersNavigate
            StatusButton(
                if (nav) Icons.Outlined.PanTool else Icons.Outlined.TouchApp,
                if (nav) "Fingers move the view" else "Fingers paint",
                on = !nav,
            ) { vm.toggleFingerPainting() }
        }
        StatusButton(
            if (panelsOpen) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            if (panelsOpen) "Hide panels" else "Show panels",
        ) { togglePanels() }
    }
}

@Composable
private fun StatusText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Q.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(horizontal = 6.dp))
}

@Composable
private fun StatusButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    HoverTip(label) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (on) Q.accent else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (on) Color.White else Q.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (on) Color.White else Q.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ZoomStatus(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "${(vm.scale * 100).roundToInt()}%",
            color = Q.text, fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Fit on screen") }, onClick = { open = false; vm.fit() })
            listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { z ->
                DropdownMenuItem(text = { Text("${(z * 100).roundToInt()}%") }, onClick = { open = false; vm.zoomTo(z) })
            }
        }
    }
}

@Composable
private fun RotationStatus(vm: EditorViewModel) {
    val turned = vm.normalizedRotation()
    Row(verticalAlignment = Alignment.CenterVertically) {
        HoverTip(if (turned != 0f) "Straighten the canvas" else "The canvas is straight") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = turned != 0f) { vm.straightenView() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.Navigation, contentDescription = null, tint = Q.text, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = turned })
                Spacer(Modifier.width(4.dp))
                Text("${turned.roundToInt()}°", color = Q.text, fontSize = 12.sp)
            }
        }
        HoverTip(if (vm.rotationLocked) "Let two fingers turn the canvas" else "Lock canvas turning") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.toggleRotationLock() },
            ) {
                Icon(
                    if (vm.rotationLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = if (vm.rotationLocked) "Turning locked" else "Turning unlocked",
                    tint = if (vm.rotationLocked) Q.accent else Q.textDim, modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

// ===================================================================== history

/** Every step you can go back to, oldest first, with where you are marked. Tap one to go there. */
@Composable
fun HistoryList(vm: EditorViewModel) {
    val h = vm.history
    @Suppress("UNUSED_VARIABLE") val v = h.version
    val done = h.undoLabels
    val ahead = h.redoLabels
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        @Composable
        fun row(label: String, steps: Int, current: Boolean, undone: Boolean) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (current) Q.rowSelected else Color.Transparent)
                    .clickable { vm.jumpHistory(steps) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            ) {
                if (current) Icon(Icons.Outlined.Check, contentDescription = "You're here", tint = Q.accent, modifier = Modifier.size(16.dp))
                else Spacer(Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = if (undone) Q.textDim else Q.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        row(if (h.trimmed) "Earlier (older steps let go)" else "Opened", 0, done.isEmpty(), undone = false)
        done.forEachIndexed { i, label -> row(label, i + 1, i == done.lastIndex, undone = false) }
        ahead.forEachIndexed { j, label -> row(label, done.size + 1 + j, false, undone = true) }
    }
}

// ===================================================================== pen niceties

/** Where a hover tip goes: below its button (above, if there's no room), or beside it. */
private class TipPosition(private val beside: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        if (beside) {
            val x = anchorBounds.right + gap
            val y = (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
            return IntOffset(x, y)
        }
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        var y = anchorBounds.bottom + gap
        if (y + popupContentSize.height > windowSize.height) y = anchorBounds.top - popupContentSize.height - gap
        return IntOffset(x, y)
    }
}

/**
 * Shows [text] beside [content] while a pen (or mouse) hovers over it for a moment, like a desktop
 * tooltip. Fingers can't hover, so they never see it. [beside]: to the right instead of below.
 */
@Composable
fun HoverTip(text: String, beside: Boolean = false, content: @Composable () -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(hovering) {
        if (hovering) {
            delay(450)
            show = true
        } else {
            show = false
        }
    }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull() ?: continue
                    if (c.type != PointerType.Stylus && c.type != PointerType.Eraser && c.type != PointerType.Mouse) {
                        hovering = false
                        continue
                    }
                    hovering = when (e.type) {
                        PointerEventType.Enter, PointerEventType.Move -> !c.pressed
                        else -> false
                    }
                }
            }
        },
    ) {
        content()
        if (show) {
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
            Popup(popupPositionProvider = remember(beside, gap) { TipPosition(beside, gap) }) {
                Text(
                    text,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The pen's side button held while it touches (or a mouse's right button): [onClick], instead of
 * what a tap does. For opening an item's menu, like a right click.
 */
fun Modifier.penSecondaryClick(vm: EditorViewModel, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                val secondary = (c.isPen && vm.pen.buttonHeld) || (c.type == PointerType.Mouse && e.buttons.isSecondaryPressed)
                if (!secondary) continue
                e.changes.forEach { it.consume() }
                click()
                // The rest of this touch goes nowhere else
                do {
                    val more = awaitPointerEvent(PointerEventPass.Initial)
                    more.changes.forEach { it.consume() }
                } while (more.changes.any { it.pressed })
            }
        }
    }
}
