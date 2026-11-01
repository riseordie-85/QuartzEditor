package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Photoshop-style panels on big screens: the dock folds to a column of icons (each opens its panel
// beside it), any panel can float in a window of its own over the picture, and any can be closed
// (the Window menu brings it back).

/** Each panel's icon (the icon column, window headers). */
fun PanelSection.icon(): ImageVector = when (this) {
    PanelSection.COLOR -> Icons.Outlined.Palette
    PanelSection.PROPERTIES -> Icons.Outlined.Tune
    PanelSection.BRUSHES -> Icons.Outlined.Brush
    PanelSection.HISTORY -> Icons.Outlined.History
    PanelSection.LAYERS -> Icons.Outlined.Layers
}

// ===================================================================== state

/** [s] closed (it shows nowhere until the Window menu brings it back), or open again. */
fun EditorViewModel.setPanelHidden(s: PanelSection, hidden: Boolean) {
    hiddenSections = if (hidden) hiddenSections + s else hiddenSections - s
    prefs.edit().putStringSet("hiddenPanels", hiddenSections.map { it.name }.toSet()).apply()
    if (hidden && flyout == s) flyout = null
}

/** Window menu: [s] shown where it lives (its window, the icon column's flyout, or the dock). */
fun EditorViewModel.showPanel(s: PanelSection) {
    setPanelHidden(s, false)
    val i = dock.indexOfFirst { s in it.sections }
    if (i < 0) return
    val slot = dock[i]
    if (slot.floating != null) {
        val list = dock.toMutableList()
        val k = slot.sections.indexOf(s)
        val f = slot.floating
        list.removeAt(i)
        // (In front, unfolded, showing it)
        list.add(slot.copy(active = k, floating = f.copy(folded = false)))
        setDock(list)
        return
    }
    if (dockCollapsed) flyout = s
    toggleSection(s, open = true)
}

/** The dock folded to icons, or opened out again. */
fun EditorViewModel.toggleDockCollapsed() {
    dockCollapsed = !dockCollapsed
    flyout = null
    prefs.edit().putBoolean("dockCollapsed", dockCollapsed).apply()
}

/**
 * Slot [real] (or just its [section], when it has tabs) taken out of the dock into a window of its
 * own at ([x], [y]) dp in the workspace.
 */
internal fun EditorViewModel.floatSlot(real: Int, section: PanelSection?, x: Float, y: Float) {
    val list = dock.toMutableList()
    val src = list.getOrNull(real) ?: return
    val w = dockWidth.coerceIn(260f, 420f)
    val h = if ((section ?: src.activeSection) == PanelSection.LAYERS) 520f else 420f
    val spec = FloatSpec(x, y, w, h)
    if (section == null || src.sections.size == 1) {
        list.removeAt(real)
        list.add(src.copy(floating = spec, heightDp = null))
    } else {
        val rest = src.sections - section
        list[real] = src.copy(sections = rest, active = rest.indexOf(src.activeSection).coerceAtLeast(0))
        list.add(DockSlot(listOf(section), floating = spec))
    }
    if (flyout != null && (section == flyout || (section == null && flyout in src.sections))) flyout = null
    setDock(list)
}

/** Floating slot [real] back in the dock, at the bottom. */
internal fun EditorViewModel.dockSlot(real: Int) {
    val list = dock.toMutableList()
    val s = list.getOrNull(real) ?: return
    list.removeAt(real)
    val firstFloating = list.indexOfFirst { it.floating != null }.let { if (it < 0) list.size else it }
    list.add(firstFloating, s.copy(floating = null))
    setDock(list)
    toggleSection(s.activeSection, open = true)
}

/** Floating slot [real]'s place or size changed (kept when [save]). */
internal fun EditorViewModel.updateFloat(real: Int, spec: FloatSpec, save: Boolean) {
    val list = dock.toMutableList()
    val s = list.getOrNull(real) ?: return
    list[real] = s.copy(floating = spec)
    setDock(list, save)
}

/** Floating slot [real] in front of the others (it's drawn last). */
internal fun EditorViewModel.floatToFront(real: Int) {
    if (real == dock.lastIndex) return
    val list = dock.toMutableList()
    val s = list.removeAt(real)
    list.add(s)
    setDock(list)
}

/** Slot [real]'s tab [section] is the one it shows. */
internal fun EditorViewModel.showTabOf(real: Int, section: PanelSection) {
    val list = dock.toMutableList()
    val s = list.getOrNull(real) ?: return
    val k = s.sections.indexOf(section)
    if (k < 0 || k == s.active) return
    list[real] = s.copy(active = k)
    setDock(list)
}

/** What a slot shows when the closed panels are left out (null: all of them are closed). */
internal fun EditorViewModel.visibleSlot(s: DockSlot): DockSlot? {
    val secs = s.sections.filter { it !in hiddenSections }
    if (secs.isEmpty()) return null
    return s.copy(sections = secs, active = secs.indexOf(s.activeSection).coerceAtLeast(0))
}

// ===================================================================== a panel's contents

/** What's in panel [section] (Layers and History fill the room they're given; the rest scroll). */
@Composable
internal fun PanelBody(vm: EditorViewModel, section: PanelSection, onPlaceImage: () -> Unit, modifier: Modifier = Modifier) {
    when (section) {
        PanelSection.LAYERS -> LayersPanel(vm, onPlaceImage, fillList = true, modifier = modifier.fillMaxWidth(), showHeader = false)
        PanelSection.HISTORY -> Box(modifier.fillMaxWidth().padding(horizontal = Q.gap(12.dp, 8.dp)).padding(bottom = Q.gap(10.dp, 4.dp))) {
            HistoryList(vm, Modifier)
        }
        else -> {
            val scroll = key(section) { rememberScrollState() }
            Column(
                modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = Q.gap(12.dp, 8.dp))
                    .padding(bottom = Q.gap(10.dp, 4.dp)),
            ) {
                when (section) {
                    PanelSection.COLOR -> ColorPanel(vm)
                    PanelSection.PROPERTIES -> PropertiesPanel(vm)
                    PanelSection.BRUSHES -> BrushesPanel(vm)
                    else -> Unit
                }
            }
        }
    }
}

/** A small round icon button for panel headers. */
@Composable
private fun HeaderIcon(icon: ImageVector, label: String, size: Dp = 30.dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = label) { onClick() },
    ) {
        Icon(icon, contentDescription = label, tint = Q.textDim, modifier = Modifier.size(18.dp))
    }
}

/** Tabs for a panel group (tap one to show it). */
@Composable
private fun PanelTabs(sections: List<PanelSection>, shown: PanelSection, modifier: Modifier, onPick: (PanelSection) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEach { s ->
            val on = s == shown
            Text(
                s.label,
                color = if (on) Q.text else Q.textDim,
                fontSize = if (on) 15.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(s) }
                    .then(if (on) Modifier.background(Q.field) else Modifier)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

// ===================================================================== the icon column

/**
 * The dock folded to icons: one per panel (groups apart), and ›› to open it out again. Tapping an
 * icon opens its panel beside the column ([onIconAt]: where the icon is, in the workspace, for
 * placing it); tapping it again closes it.
 */
@Composable
fun PanelIconStrip(vm: EditorViewModel, modifier: Modifier = Modifier, onIconAt: (Float) -> Unit) {
    val density = LocalDensity.current
    Column(
        modifier
            .clip(RoundedCornerShape(Q.corner(18.dp)))
            .background(Q.panel)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeaderIcon(Icons.Filled.KeyboardDoubleArrowLeft, "Open the panels out", 40.dp) { vm.toggleDockCollapsed() }
        Spacer(Modifier.height(4.dp))
        val groups = vm.dock.filter { it.floating == null }.mapNotNull { vm.visibleSlot(it) }
        groups.forEachIndexed { gi, slot ->
            if (gi > 0) Box(Modifier.padding(vertical = 5.dp).width(28.dp).height(1.dp).background(Q.divider))
            slot.sections.forEach { s ->
                var y by remember { mutableStateOf(0f) }
                val on = vm.flyout == s
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Q.accent.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent)
                        .onGloballyPositioned { c ->
                            val ws = vm.workspaceCoords?.takeIf { it.isAttached }
                            y = if (ws != null && c.isAttached) ws.localPositionOf(c, Offset.Zero).y / density.density else 0f
                        }
                        .clickable(onClickLabel = s.label) {
                            if (on) {
                                vm.flyout = null
                            } else {
                                vm.flyout = s
                                onIconAt(y)
                            }
                        },
                ) {
                    Icon(s.icon(), contentDescription = s.label, tint = if (on) Q.text else Q.textDim, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * The panel opened from the icon column, beside it: [topDp] where its icon is (it's kept on the
 * screen), [rightDp] room left for the column, [heightDp] the workspace's height.
 */
@Composable
fun PanelFlyout(vm: EditorViewModel, onPlaceImage: () -> Unit, topDp: Float, rightDp: Float, heightDp: Float, widthDp: Float) {
    val s = vm.flyout ?: return
    val real = vm.dock.indexOfFirst { s in it.sections }
    val slot = vm.dock.getOrNull(real)?.let { vm.visibleSlot(it) }
    if (slot == null || slot.floating != null || s in vm.hiddenSections) {
        androidx.compose.runtime.SideEffect { vm.flyout = null }
        return
    }
    // Layers gets most of the height; the others as tall as they need, up to most of it
    val tall = s == PanelSection.LAYERS || s == PanelSection.HISTORY
    val h = if (tall) (heightDp * 0.75f).coerceAtLeast(260f) else null
    val maxH = heightDp - 16f
    val top = topDp.coerceIn(8f, (heightDp - (h ?: 320f) - 8f).coerceAtLeast(8f))
    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-rightDp).dp, y = top.dp)
                .width(widthDp.dp)
                .then(if (h != null) Modifier.height(h.coerceAtMost(maxH - top).dp) else Modifier.heightIn(max = (maxH - top).dp))
                .shadow(10.dp, RoundedCornerShape(Q.corner(18.dp)))
                .clip(RoundedCornerShape(Q.corner(18.dp)))
                .background(Q.panel)
                .border(1.dp, Q.divider, RoundedCornerShape(Q.corner(18.dp)))
                // (Taps on it stay on it)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)) {
                if (slot.sections.size > 1) {
                    PanelTabs(slot.sections, s, Modifier.weight(1f)) { t ->
                        vm.flyout = t
                        vm.showTabOf(real, t)
                    }
                } else {
                    Text(s.label, color = Q.text, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                }
                HeaderIcon(Icons.Outlined.OpenInNew, "Float in a window") {
                    val x = (vm.workspaceWidthDp - rightDp - widthDp - 40f).coerceAtLeast(8f)
                    vm.floatSlot(real, if (slot.sections.size > 1) s else null, x, top)
                }
                HeaderIcon(Icons.Filled.Close, "Close") { vm.flyout = null }
            }
            PanelBody(vm, s, onPlaceImage, if (h != null) Modifier.weight(1f) else Modifier.weight(1f, fill = false))
        }
    }
}

// ===================================================================== floating windows

/**
 * The panels floating in windows of their own: drag the title bar to move one (onto the dock to put
 * it back), the corner to size it; fold it to its title bar, dock it, or close it.
 */
@Composable
fun FloatingPanels(vm: EditorViewModel, onPlaceImage: () -> Unit, maxWDp: Float, maxHDp: Float) {
    vm.dock.forEachIndexed { real, raw ->
        val f = raw.floating ?: return@forEachIndexed
        val slot = vm.visibleSlot(raw) ?: return@forEachIndexed
        key(raw.sections.first()) {
            FloatingPanelWindow(vm, real, slot, f, onPlaceImage, maxWDp, maxHDp)
        }
    }
}

@Composable
private fun FloatingPanelWindow(vm: EditorViewModel, real: Int, slot: DockSlot, f: FloatSpec, onPlaceImage: () -> Unit, maxWDp: Float, maxHDp: Float) {
    val density = LocalDensity.current
    val section = slot.activeSection
    // Moving or sizing: live here, kept when the finger lifts
    var live by remember { mutableStateOf<FloatSpec?>(null) }
    val spec = live ?: f
    val w = spec.w.coerceIn(220f, maxWDp.coerceAtLeast(220f))
    val h = spec.h.coerceIn(160f, maxHDp.coerceAtLeast(160f))
    val x = spec.x.coerceIn(0f, (maxWDp - 120f).coerceAtLeast(0f))
    val y = spec.y.coerceIn(0f, (maxHDp - 40f).coerceAtLeast(0f))
    val realNow by rememberUpdatedState(real)
    val fNow by rememberUpdatedState(f)
    Column(
        Modifier
            .offset { IntOffset((x * density.density).roundToInt(), (y * density.density).roundToInt()) }
            .width(w.dp)
            .then(if (spec.folded) Modifier else Modifier.height(h.dp))
            .shadow(12.dp, RoundedCornerShape(Q.corner(16.dp)))
            .clip(RoundedCornerShape(Q.corner(16.dp)))
            .background(Q.panel)
            .border(1.dp, Q.divider, RoundedCornerShape(Q.corner(16.dp)))
            .pointerInput(Unit) { detectTapGestures(onPress = { vm.floatToFront(realNow) }) },
    ) {
        // Title bar: drag to move (onto the dock: back in it)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Q.panelDark)
                .pointerInput(Unit) {
                    var at = Offset.Zero
                    detectDragGestures(
                        onDragStart = { p ->
                            vm.floatToFront(realNow)
                            live = fNow
                            at = p
                        },
                        onDragEnd = {
                            val l = live
                            live = null
                            if (l != null) {
                                // Let go over the dock: back in it
                                val dockArea = vm.dockArea
                                val px = (l.x + (at.x / density.density)) * density.density
                                val py = (l.y + (at.y / density.density)) * density.density
                                if (dockArea != null && dockArea.contains(Offset(px, py))) vm.dockSlot(realNow)
                                else vm.updateFloat(realNow, l, save = true)
                            }
                        },
                        onDragCancel = {
                            live?.let { vm.updateFloat(realNow, it, save = true) }
                            live = null
                        },
                    ) { change, d ->
                        change.consume()
                        val cur = live ?: fNow
                        live = cur.copy(x = cur.x + d.x / density.density, y = cur.y + d.y / density.density)
                    }
                }
                .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (slot.sections.size > 1) {
                PanelTabs(slot.sections, section, Modifier.weight(1f)) { t -> vm.showTabOf(realNow, t) }
            } else {
                Icon(section.icon(), contentDescription = null, tint = Q.textDim, modifier = Modifier.size(16.dp))
                Text(section.label, color = Q.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
            if (section == PanelSection.LAYERS) LayersPickedChip(vm)
            HeaderIcon(if (spec.folded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, if (spec.folded) "Unfold" else "Fold to the title bar", 28.dp) {
                vm.updateFloat(realNow, fNow.copy(folded = !fNow.folded), save = true)
            }
            HeaderIcon(Icons.Outlined.Login, "Put back in the dock", 28.dp) { vm.dockSlot(realNow) }
            HeaderIcon(Icons.Filled.Close, "Close (Window menu brings it back)", 28.dp) {
                slot.sections.forEach { vm.setPanelHidden(it, true) }
            }
        }
        if (!spec.folded) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    PanelBody(vm, section, onPlaceImage, Modifier.weight(1f))
                }
                // The corner: drag to size it
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { live = fNow },
                                onDragEnd = {
                                    live?.let { vm.updateFloat(realNow, it, save = true) }
                                    live = null
                                },
                                onDragCancel = {
                                    live?.let { vm.updateFloat(realNow, it, save = true) }
                                    live = null
                                },
                            ) { change, d ->
                                change.consume()
                                val cur = live ?: fNow
                                live = cur.copy(w = (cur.w + d.x / density.density).coerceAtLeast(220f), h = (cur.h + d.y / density.density).coerceAtLeast(160f))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // A grip: two short diagonal lines
                    Box(Modifier.size(12.dp).border(width = 2.dp, color = Q.textDim.copy(alpha = 0.5f), shape = RoundedCornerShape(bottomEnd = 6.dp)))
                }
            }
        }
    }
}
