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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.fillMaxSize
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
    UiLayout.DESKTOP -> w >= 760f && h >= 480f
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
            .clip(RoundedCornerShape(Q.corner(14.dp)))
            .background(Q.toolbar)
            .padding(horizontal = 6.dp),
    ) {
        // (Scrolls when it doesn't fit, so undo and redo always stay on screen)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            MenuTitle("Quartz", bold = true) { AppMenuItems(vm, a) }
            MenuTitle("File") { FileMenuItems(vm, a) }
            MenuTitle("Edit") { EditMenuItems(vm, a) }
            MenuTitle("Image") { ImageMenuItems(vm, a) }
            MenuTitle("Layer") { LayerMenuItems(vm, a) }
            MenuTitle("Select") { SelectMenuItems(vm) }
            MenuTitle("Filter") { FilterMenuItems(vm, a) }
            MenuTitle("View") { ViewMenuItems(vm, a) }
            MenuTitle("Window") { WindowMenuItems(vm, panelsOpen, togglePanels) }
            Spacer(Modifier.width(16.dp))
            FileTabs(vm)
        }
        val h = vm.history
        @Suppress("UNUSED_VARIABLE") val v = h.version
        BarIconButton(Icons.AutoMirrored.Outlined.Undo, "Undo (two-finger tap)", enabled = h.canUndo || vm.pendingFx != null || vm.enhance != null || vm.textEdit != null || vm.penPoints.isNotEmpty()) { vm.undo() }
        BarIconButton(Icons.AutoMirrored.Outlined.Redo, "Redo (three-finger tap)", enabled = h.canRedo) { vm.redo() }
    }
}

/**
 * The open files, each followed by its smart layers' tabs (a smart layer's contents being edited),
 * then + to open or start another. Tap one to show it; × closes it (a file with unsaved changes asks whether to save them, a
 * smart layer's edits go into the layer).
 */
@Composable
private fun FileTabs(vm: EditorViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        vm.files.forEachIndexed { i, f ->
            val tabs = vm.fileTabs(i)
            val current = i == vm.fileIndex
            tabs.forEachIndexed { k, t ->
                val name = if (t.parent == null) t.doc.name else t.parent.doc.layer(t.layerId)?.props?.name ?: t.doc.name
                val shown = current && k == vm.tabIndex
                FileTab(
                    name,
                    smart = t.parent != null,
                    shown = shown,
                    // A file can always close; a smart layer's tab from its own file
                    closeLabel = if (t.parent == null) "Close \"$name\" (asks to save changes)" else "Close \"$name\" (its edits go into the layer)",
                    onClose = { if (t.parent == null) vm.closeFile(i) else { vm.switchFile(i); if (vm.fileIndex == i) vm.closeTab(k) } },
                ) { vm.switchFileTab(i, k) }
            }
            @Suppress("UNUSED_VARIABLE") val unused = f
        }
        BarIconButton(Icons.Outlined.Add, "Open or start another file") { vm.openHome() }
    }
}

@Composable
private fun FileTab(name: String, smart: Boolean, shown: Boolean, closeLabel: String, onClose: () -> Unit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(Q.corner(10.dp)))
            .background(if (shown) Q.text.copy(alpha = 0.16f) else Q.text.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp),
    ) {
        if (smart) {
            Icon(Icons.Outlined.FilterNone, contentDescription = "Smart layer", tint = Q.textDim, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(name, color = if (shown) Q.text else Q.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        HoverTip(closeLabel) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(start = 4.dp).size(24.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClose),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = closeLabel, tint = if (shown) Q.text else Q.textDim, modifier = Modifier.size(14.dp))
            }
        }
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
        // Its pages open beside it; long menus scroll instead of running down the screen
        PagedDropdownMenu(expanded = open, onDismissRequest = { open = false }, cascade = true) { content() }
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
            .clip(RoundedCornerShape(Q.corner(14.dp)))
            .background(Q.toolbar)
            .padding(start = 14.dp, end = 6.dp),
    ) {
        Text(toolTitle(vm), color = Q.text, fontSize = 14.sp, maxLines = 1)
        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(24.dp).background(Q.divider))
        when (vm.tool) {
            Tool.BRUSH, Tool.ERASER, Tool.RETOUCH -> BrushOptions(vm, Modifier.weight(1f))
            Tool.SHAPE -> ShapeBarOptions(vm, Modifier.weight(1f))
            Tool.TEXT -> TextBarOptions(vm, Modifier.weight(1f))
            Tool.EYEDROPPER -> EyedropperOptions(vm, Modifier.weight(1f))
            else -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                // At most one of these is showing (each knows when it's needed; the text field stays over the picture)
                TransformBar(vm)
                SelectBar(vm)
                CropBar(vm)
                WarpBar(vm)
                PenBar(vm)
                if (vm.tool == Tool.MOVE || vm.tool == Tool.PAN || vm.tool == Tool.BUCKET || vm.tool == Tool.GRADIENT) {
                    Text("More settings in Properties", color = Q.textDim, fontSize = 12.sp)
                }
            }
        }
        // Selecting: the AI selections, always at hand
        if (vm.tool == Tool.SELECT) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
                OptionChip("Subject", false, Icons.Outlined.PersonOutline) { vm.selectSubject(background = false) }
                OptionChip("Background", false, Icons.Outlined.Landscape) { vm.selectSubject(background = true) }
                OptionChip("Sky", false, Icons.Outlined.Cloud) { vm.selectSky() }
                OptionChip("Color range", false, Icons.Outlined.Palette) { vm.openColorRange() }
            }
        }
    }
}

/** Eyedropper: how much it averages, and whether it sees every layer or just the one you're on. */
@Composable
private fun EyedropperOptions(vm: EditorViewModel, modifier: Modifier) {
    val sizes = listOf(1, 3, 5, 11, 31, 51, 101)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.horizontalScroll(rememberScrollState())) {
        Text("Sample size", color = Q.textDim, fontSize = 12.sp)
        sizes.forEach { n ->
            OptionChip(if (n == 1) "Point" else "$n×$n", vm.eyedropSize == n) {
                vm.eyedropSize = n
                vm.prefs.edit().putInt("eyedropSize", n).apply()
            }
        }
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(Q.divider))
        OptionChip("All layers", vm.eyedropAllLayers) {
            vm.eyedropAllLayers = !vm.eyedropAllLayers
            vm.prefs.edit().putBoolean("eyedropAll", vm.eyedropAllLayers).apply()
        }
        Text(if (vm.eyedropAllLayers) "Everything you see" else "Just the layer you're on", color = Q.textDim, fontSize = 12.sp)
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
        // The brush from the library, and Brush Studio
        if (!retouch) BrushPresetButton(vm, forTool)
        if (!retouch) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (BrushTip.BASIC + listOfNotNull(b.tip.takeIf { it !in BrushTip.BASIC })).forEach { tip ->
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
        OptionChip("Side sliders", vm.sideSliders) { vm.toggleSideSliders() }
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
            .clip(RoundedCornerShape(Q.corner(12.dp)))
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
fun HistoryList(vm: EditorViewModel, modifier: Modifier = Modifier.heightIn(max = 280.dp)) {
    val h = vm.history
    @Suppress("UNUSED_VARIABLE") val v = h.version
    val done = h.undoLabels
    val ahead = h.redoLabels
    Column(Modifier.fillMaxWidth().then(modifier).verticalScroll(rememberScrollState())) {
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

/** A hover tip being shown: its text, where its button is (window pixels), and which side it goes. */
class TipSpec(val text: String, val anchor: androidx.compose.ui.geometry.Rect, val beside: Boolean, val owner: Any)

/** Where hover tips are drawn: a layer over the whole screen that touches go straight through. */
class TipHostState {
    var tip by mutableStateOf<TipSpec?>(null)
}

val LocalTipHost = androidx.compose.runtime.staticCompositionLocalOf<TipHostState?> { null }

/**
 * Shows [text] beside [content] while a pen (or mouse) hovers over it for a moment, like a desktop
 * tooltip. Fingers can't hover, so they never see it. [beside]: to the right instead of below.
 * Drawn by [TipLayer] (it never catches a tap); outside one, in a popup.
 */
@Composable
fun HoverTip(text: String, beside: Boolean = false, content: @Composable () -> Unit) {
    val host = LocalTipHost.current
    val owner = remember { Any() }
    var hovering by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    LaunchedEffect(hovering) {
        if (hovering) {
            delay(450)
            show = true
        } else {
            show = false
        }
    }
    if (host != null) {
        LaunchedEffect(show, text, bounds) {
            if (show) host.tip = TipSpec(text, bounds, beside, owner)
            else if (host.tip?.owner === owner) host.tip = null
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { if (host.tip?.owner === owner) host.tip = null }
        }
    }
    Box(
        Modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .pointerInput(Unit) {
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
        if (show && host == null) {
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
            Popup(popupPositionProvider = remember(beside, gap) { TipPosition(beside, gap) }) { TipText(text) }
        }
    }
}

@Composable
private fun TipText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Draws the hover tip being shown, over everything (put it last in the screen's box). */
@Composable
fun TipLayer(host: TipHostState) {
    val t = host.tip ?: return
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        TipText(
            t.text,
            Modifier.layout { m, c ->
                val p = m.measure(androidx.compose.ui.unit.Constraints())
                val w = c.maxWidth
                val h = c.maxHeight
                val a = t.anchor.translate(-origin.x, -origin.y)
                val x: Int
                val y: Int
                if (t.beside) {
                    x = (a.right.roundToInt() + gap).coerceAtMost((w - p.width).coerceAtLeast(0))
                    y = (a.center.y.roundToInt() - p.height / 2).coerceIn(0, (h - p.height).coerceAtLeast(0))
                } else {
                    x = (a.center.x.roundToInt() - p.width / 2).coerceIn(0, (w - p.width).coerceAtLeast(0))
                    val below = a.bottom.roundToInt() + gap
                    y = if (below + p.height <= h) below else (a.top.roundToInt() - p.height - gap).coerceAtLeast(0)
                }
                layout(w, h) { p.place(x, y) }
            },
        )
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
