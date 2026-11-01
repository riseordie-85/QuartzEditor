package com.tim.quartzeditor.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

// The panel dock (the right-hand column): Color, Properties, Brushes, History and Layers, each in a
// slot of its own or stacked as tabs in one. Drag a header to move it up or down, onto another's
// header to stack them, or a tab out to split it off again. Drag the line between two open slots
// to share the room differently (double-tap it: back to automatic). Window → Reset panel layout.

/**
 * One place in the dock: one section, or several as tabs ([active] shown). [heightDp]: the height
 * you dragged it to (null: as tall as it needs; the slot showing Layers takes whatever's left).
 */
@Immutable
data class DockSlot(val sections: List<PanelSection>, val active: Int = 0, val heightDp: Float? = null, val floating: FloatSpec? = null) {
    val activeSection: PanelSection get() = sections[active.coerceIn(0, sections.lastIndex)]
}

/**
 * A panel group in a window of its own, over the picture (like Photoshop's floating panels): where
 * it is in the workspace and how big (dp), and whether it's folded to its title bar.
 */
@Immutable
data class FloatSpec(val x: Float, val y: Float, val w: Float = 300f, val h: Float = 420f, val folded: Boolean = false)

object DockStore {
    val DEFAULT: List<DockSlot> = PanelSection.entries.map { DockSlot(listOf(it)) }

    fun load(prefs: SharedPreferences): List<DockSlot> {
        val a = runCatching { JSONArray(prefs.getString("dockLayout", null) ?: return DEFAULT) }.getOrNull() ?: return DEFAULT
        val out = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val names = o.optJSONArray("s") ?: return@mapNotNull null
            val secs = (0 until names.length()).mapNotNull { k -> PanelSection.entries.firstOrNull { it.name == names.optString(k) } }
            val f = o.optJSONObject("f")?.let { fo ->
                FloatSpec(fo.optDouble("x").toFloat(), fo.optDouble("y").toFloat(), fo.optDouble("w", 300.0).toFloat(), fo.optDouble("h", 420.0).toFloat(), fo.optBoolean("fold"))
            }
            DockSlot(secs, o.optInt("a", 0), if (o.has("h")) o.optDouble("h").toFloat() else null, f)
        }
        return normalize(out)
    }

    fun save(prefs: SharedPreferences, dock: List<DockSlot>) {
        val a = JSONArray()
        dock.forEach { s ->
            a.put(JSONObject().apply {
                put("s", JSONArray().also { n -> s.sections.forEach { n.put(it.name) } })
                put("a", s.active)
                s.heightDp?.let { put("h", it.toDouble()) }
                s.floating?.let { f -> put("f", JSONObject().put("x", f.x.toDouble()).put("y", f.y.toDouble()).put("w", f.w.toDouble()).put("h", f.h.toDouble()).put("fold", f.folded)) }
            })
        }
        prefs.edit().putString("dockLayout", a.toString()).apply()
    }

    /** Every section once, no empty slots, tabs in range (a section added in an update gets a slot of its own). */
    fun normalize(dock: List<DockSlot>): List<DockSlot> {
        val seen = HashSet<PanelSection>()
        val out = ArrayList<DockSlot>()
        for (s in dock) {
            val secs = s.sections.filter { seen.add(it) }
            val h = s.heightDp?.takeIf { it.isFinite() && it > 0f }
            val f = s.floating?.takeIf { it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() }
            if (secs.isNotEmpty()) out.add(s.copy(sections = secs, active = s.active.coerceIn(0, secs.lastIndex), heightDp = h, floating = f))
        }
        PanelSection.entries.filter { it !in seen }.forEach { out.add(DockSlot(listOf(it))) }
        return out
    }
}

internal fun EditorViewModel.setDock(d: List<DockSlot>, save: Boolean = true) {
    dock = DockStore.normalize(d)
    if (save) DockStore.save(prefs, dock)
}

/** The panels as they came. */
fun EditorViewModel.resetDock() {
    setDock(DockStore.DEFAULT)
    // (Every panel back, in the column, opened out)
    hiddenSections.forEach { setPanelHidden(it, false) }
    if (dockCollapsed) toggleDockCollapsed()
    toast("Panels back to their usual places")
}

/** The tab a slot shows: the one picked, unless another of its sections is the one open. */
private fun EditorViewModel.shownTab(s: DockSlot): Int =
    if (isOpen(s.activeSection)) s.active.coerceIn(0, s.sections.lastIndex)
    else s.sections.indexOfFirst { isOpen(it) }.takeIf { it >= 0 } ?: s.active.coerceIn(0, s.sections.lastIndex)

/** What's being dragged: a whole slot, or one section (a tab) out of it. */
private class DockDrag(val fromSlot: Int, val fromShown: Int, val section: PanelSection?, val label: String) {
    var at by mutableStateOf(Offset.Zero)
}

/** Where a drag would land: before slot [gap] (0..n), stacked onto slot [stackOn], or out of the dock in a window of its own ([float]). */
private data class DockTarget(val gap: Int = -1, val stackOn: Int = -1, val float: Boolean = false)

/** [s] without [sec], still showing the tab it showed (or the first, if that's the one taken). */
private fun without(s: DockSlot, sec: PanelSection): DockSlot {
    val rest = s.sections - sec
    return s.copy(sections = rest, active = rest.indexOf(s.activeSection).coerceAtLeast(0))
}

/** Moves what's dragged to [t]. */
private fun EditorViewModel.dropOnDock(drag: DockDrag, t: DockTarget) {
    val list = dock.toMutableList()
    val from = drag.fromSlot
    if (from !in list.indices) return
    val src = list[from]
    // What moves: the whole slot, or one of its tabs (a slot of one tab moves whole)
    val moving: List<PanelSection>
    val whole = drag.section == null || src.sections.size == 1
    if (whole) {
        moving = src.sections
    } else {
        val s = drag.section!!
        moving = listOf(s)
    }
    if (t.stackOn >= 0) {
        if (t.stackOn == from || t.stackOn !in list.indices) return
        val into = list[t.stackOn]
        val merged = into.sections + moving
        list[t.stackOn] = into.copy(sections = merged, active = into.sections.size)
        if (whole) list.removeAt(from) else list[from] = without(src, moving.first())
        setDock(list)
        // The one dropped shows (and opens)
        toggleSection(moving.first(), open = true)
        return
    }
    if (t.gap < 0) return
    var gap = t.gap
    if (whole) {
        if (gap == from || gap == from + 1) return
        val slot = list.removeAt(from)
        if (from < gap) gap--
        list.add(gap.coerceIn(0, list.size), slot)
    } else {
        list[from] = without(src, moving.first())
        list.add(gap.coerceIn(0, list.size), DockSlot(moving))
    }
    setDock(list)
}

/**
 * The right-hand column: the panel sections in their slots. Slots fold to their header when
 * closed; open ones are as tall as they need (or as you dragged them) and the one showing Layers
 * fills what's left (its list scrolls; its settings and buttons stay on screen).
 */
@Composable
fun PanelColumn(vm: EditorViewModel, onPlaceImage: () -> Unit, modifier: Modifier = Modifier, floats: Boolean = true) {
    val density = LocalDensity.current
    // The slots shown here: not the closed panels, nor (where windows can float) the floating ones.
    // [real]: each one's place in the whole dock
    val entries = vm.dock.withIndex().mapNotNull { (ri, s) -> if (floats && s.floating != null) null else vm.visibleSlot(s)?.let { ri to it } }
    val real = entries.map { it.first }
    val dock = entries.map { it.second }
    val n = dock.size
    /** [t] (in the slots shown here) in the whole dock's places. */
    fun toReal(t: DockTarget): DockTarget = when {
        t.stackOn >= 0 -> DockTarget(stackOn = real.getOrElse(t.stackOn) { -1 })
        t.gap >= 0 -> DockTarget(gap = if (t.gap < n) real[t.gap] else (real.lastOrNull()?.plus(1) ?: 0))
        else -> t
    }
    // Where each slot is in the dock, and its header's height (for working out where a drag lands)
    val slotRects = remember { HashMap<Int, Rect>() }
    val headerH = remember { HashMap<Int, Float>() }
    androidx.compose.runtime.SideEffect {
        slotRects.keys.removeAll { it >= n }
        headerH.keys.removeAll { it >= n }
    }
    // Each slot's height as last laid out (for dragging the lines between them)
    val measured = remember { IntArray(32) }
    // Heights while a line is being dragged (read only when laying out, so only that redoes)
    val liveHeights = remember { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
    var drag by remember { mutableStateOf<DockDrag?>(null) }
    val dockCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shown = dock.map { vm.shownTab(it) }
    val open = dock.mapIndexed { i, s -> vm.isOpen(s.sections[shown[i]]) }
    val filler = dock.indices.firstOrNull { open[it] && dock[it].sections[shown[it]] == PanelSection.LAYERS } ?: -1
    val minSlot = with(density) { 96.dp.roundToPx() }
    val fillerMinFull = with(density) { Q.gap(290.dp, 230.dp).roundToPx() }
    val headerFloor = with(density) { 52.dp.roundToPx() }

    fun target(d: DockDrag): DockTarget {
        val dockH = dockCoords[0]?.takeIf { it.isAttached }?.size?.height?.toFloat() ?: Float.MAX_VALUE
        // Pulled out past the dock's left edge (big screens): a window of its own
        if (floats && d.at.x < -48f * density.density) return DockTarget(float = true)
        val y = d.at.y.coerceIn(0f, dockH)
        for (j in 0 until n) {
            val r = slotRects[j] ?: continue
            val h = headerH[j] ?: continue
            // (Slots pushed out of sight aren't targets)
            if (r.top >= dockH) continue
            if (y >= r.top && y <= r.top + h) {
                // The middle of a header: stack onto it; its top or bottom edge: just before or after it
                val f = (y - r.top) / h.coerceAtLeast(1f)
                return when {
                    f < 0.25f -> DockTarget(gap = j)
                    // (A folded slot is just its header: its bottom edge is the gap after it)
                    f > 0.75f && r.bottom - r.top <= h + 1f -> DockTarget(gap = j + 1)
                    j == d.fromShown -> DockTarget()
                    else -> DockTarget(stackOn = j)
                }
            }
        }
        var g = 0
        for (j in 0 until n) {
            val r = slotRects[j] ?: continue
            if (r.top >= dockH) continue
            if (y > (r.top + r.bottom) / 2f) g = j + 1
        }
        return DockTarget(gap = g)
    }

    /** The open slots a line between slot [i] and the next resizes: the nearest open one above and below it. */
    fun pairFor(i: Int): Pair<Int, Int>? {
        val up = (i downTo 0).firstOrNull { open[it] } ?: return null
        val lo = (i + 1 until n).firstOrNull { open[it] } ?: return null
        return up to lo
    }

    // (The column isn't on screen: no place to drop a floating panel)
    if (floats) androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.dockArea = null } }
    Box(
        modifier
            // (Rounded but not clipped here: a section pulled out past the edge still shows by the finger)
            .background(Q.panel, RoundedCornerShape(Q.corner(26.dp)))
            .onGloballyPositioned { c ->
                dockCoords[0] = c
                // (Where a floating panel let go docks again)
                if (floats) vm.workspaceCoords?.takeIf { it.isAttached && c.isAttached }?.let { ws ->
                    val tl = ws.localPositionOf(c, Offset.Zero)
                    vm.dockArea = Rect(tl.x, tl.y, tl.x + c.size.width, tl.y + c.size.height)
                }
            },
    ) {
        Layout(
            content = {
                dock.forEachIndexed { i, slot ->
                    key(slot.sections.first()) {
                        DockSlotView(
                            vm, slot, real[i], shown[i], open[i], onPlaceImage, floats,
                            onHeader = { h -> headerH[i] = h },
                            onBounds = { r -> slotRects[i] = r },
                            dockCoords = { dockCoords[0] },
                            onDragStart = { section, label, at -> drag = DockDrag(real[i], i, section, label).also { it.at = at } },
                            onDrag = { at -> drag?.at = at },
                            onDragEnd = { cancelled ->
                                val d = drag
                                drag = null
                                if (d != null && !cancelled) {
                                    val t = target(d)
                                    if (t.float) {
                                        // Where it was let go, in the workspace
                                        val ws = vm.workspaceCoords?.takeIf { it.isAttached }
                                        val dc = dockCoords[0]?.takeIf { it.isAttached }
                                        val p = if (ws != null && dc != null) ws.localPositionOf(dc, d.at) else d.at
                                        vm.floatSlot(d.fromSlot, d.section, (p.x / density.density - 140f).coerceAtLeast(8f), (p.y / density.density - 20f).coerceAtLeast(8f))
                                    } else {
                                        vm.dropOnDock(d, toReal(t))
                                    }
                                }
                            },
                        )
                    }
                    if (i < n - 1) {
                        val pair = pairFor(i)
                        DockDivider(
                            active = pair != null,
                            onDrag = { dy, start ->
                                val (u, l) = pair ?: return@DockDivider
                                // Heights in px from where the drag started
                                val (upH, loH, fillH) = start
                                val spare = (fillH - fillerMinFull).coerceAtLeast(0).toFloat()
                                when {
                                    u == filler -> {
                                        val lo = minOf(minSlot, loH).toFloat()
                                        liveHeights[l] = (loH - dy).coerceIn(lo, loH + spare)
                                    }
                                    l == filler -> {
                                        val lo = minOf(minSlot, upH).toFloat()
                                        liveHeights[u] = (upH + dy).coerceIn(lo, upH + spare)
                                    }
                                    else -> {
                                        val lo = minOf(minSlot, upH) - upH.toFloat()
                                        val hi = maxOf(loH - minOf(minSlot, loH).toFloat(), lo)
                                        val dd = dy.coerceIn(lo, hi)
                                        liveHeights[u] = upH + dd
                                        liveHeights[l] = loH - dd
                                    }
                                }
                            },
                            startHeights = {
                                val p = pair
                                if (p == null) Triple(0, 0, 0) else Triple(measured[p.first], measured[p.second], if (filler >= 0) measured[filler] else 0)
                            },
                            onDone = {
                                // Into the dock (in dp), kept
                                if (liveHeights.isNotEmpty()) {
                                    val list = vm.dock.toMutableList()
                                    liveHeights.forEach { (k, px) -> real.getOrNull(k)?.takeIf { it in list.indices }?.let { r -> list[r] = list[r].copy(heightDp = with(density) { px.toDp().value }) } }
                                    liveHeights.clear()
                                    vm.setDock(list)
                                }
                            },
                            onReset = {
                                val p = pair ?: return@DockDivider
                                val list = vm.dock.toMutableList()
                                val a = real[p.first]
                                val b = real[p.second]
                                list[a] = list[a].copy(heightDp = null)
                                list[b] = list[b].copy(heightDp = null)
                                vm.setDock(list)
                            },
                        )
                    }
                }
            },
            // (Big screens: a strip at the top for the fold-to-icons button)
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(Q.corner(26.dp))).padding(top = if (floats) 22.dp else Q.gap(6.dp, 2.dp), bottom = Q.gap(8.dp, 3.dp)),
        ) { ms, c ->
            val w = c.maxWidth
            val slotsM = ms.filterIndexed { k, _ -> k % 2 == 0 }
            val divsM = ms.filterIndexed { k, _ -> k % 2 == 1 }
            val divs = divsM.map { it.measure(Constraints(minWidth = w, maxWidth = w)) }
            val avail = (c.maxHeight - divs.sumOf { it.height }).coerceAtLeast(0)
            val places = arrayOfNulls<androidx.compose.ui.layout.Placeable>(slotsM.size)
            var used = 0
            // Folded slots: just their header
            slotsM.forEachIndexed { i, m ->
                if (!open.getOrElse(i) { false }) {
                    places[i] = m.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = avail))
                    used += places[i]!!.height
                }
            }
            // Open ones, top first: the height they were dragged to, or as much as they need. Layers
            // keeps room for a few rows (less on short screens), and nothing goes past the bottom.
            val fillerMin = if (filler >= 0) minOf(fillerMinFull, ((avail - used) * 0.6f).roundToInt()) else 0
            var budget = avail - used - fillerMin
            val openOthers = slotsM.indices.count { open.getOrElse(it) { false } && it != filler }
            var left = openOthers
            slotsM.forEachIndexed { i, m ->
                if (open.getOrElse(i) { false } && i != filler) {
                    left--
                    // Leave the ones below at least their header
                    val maxH = max(headerFloor, budget - left * headerFloor)
                    val fixedPx = liveHeights[i]?.roundToInt() ?: dock.getOrNull(i)?.heightDp?.let { with(density) { it.dp.roundToPx() } }
                    val p = if (fixedPx != null) {
                        val h = fixedPx.coerceIn(minOf(minSlot, maxH), maxH)
                        m.measure(Constraints(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
                    } else {
                        m.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = maxH))
                    }
                    places[i] = p
                    used += p.height
                    budget -= p.height
                }
            }
            if (filler >= 0 && filler < slotsM.size) {
                val h = max(avail - used, headerFloor)
                places[filler] = slotsM[filler].measure(Constraints(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
            }
            places.forEachIndexed { i, p -> if (i < measured.size) measured[i] = p?.height ?: 0 }
            layout(w, c.maxHeight) {
                var y = 0
                places.forEachIndexed { i, p ->
                    if (p != null) {
                        p.placeRelative(0, y)
                        y += p.height
                    }
                    divs.getOrNull(i)?.let { d ->
                        d.placeRelative(0, y)
                        y += d.height
                    }
                }
            }
        }

        // What's being dragged, and where it would go (only this redraws as it moves)
        drag?.let { d -> DockDragOverlay(d, dock, slotRects, headerH, ::target) }
        // The column folds to icons (››), like Photoshop's
        if (floats) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 1.dp, end = 10.dp)
                .size(20.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Fold the panels to icons") { vm.toggleDockCollapsed() },
        ) {
            Icon(Icons.Filled.KeyboardDoubleArrowRight, contentDescription = "Fold the panels to icons", tint = Q.textDim, modifier = Modifier.size(16.dp))
        }
    }
}

/** The dragged section's name by the finger, and the line or outline where it would go. */
@Composable
private fun DockDragOverlay(d: DockDrag, dock: List<DockSlot>, slotRects: Map<Int, Rect>, headerH: Map<Int, Float>, target: (DockDrag) -> DockTarget) {
    val density = LocalDensity.current
    val n = dock.size
    val t = target(d)
    if (t.float) {
        Text(
            d.label + "  → window",
            color = Q.text,
            fontSize = 14.sp,
            modifier = Modifier
                .offset { IntOffset((d.at.x - 40 * density.density).roundToInt(), (d.at.y - 34 * density.density).roundToInt()) }
                .clip(RoundedCornerShape(8.dp))
                .background(Q.panelDark)
                .border(1.dp, Q.accent, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        return
    }
    val line = when {
        t.stackOn >= 0 || t.gap < 0 -> null
        t.gap < n -> slotRects[t.gap]?.top
        else -> slotRects[n - 1]?.bottom
    }
    val whole = d.section == null || dock.getOrNull(d.fromShown)?.sections?.size == 1
    val moves = t.stackOn >= 0 || (t.gap >= 0 && (!whole || (t.gap != d.fromShown && t.gap != d.fromShown + 1)))
    if (t.stackOn >= 0) slotRects[t.stackOn]?.let { r ->
        val hh = headerH[t.stackOn] ?: 0f
        Box(
            Modifier
                .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(with(density) { r.width.toDp() }, with(density) { hh.toDp() })
                .border(2.dp, Q.accent, RoundedCornerShape(10.dp)),
        )
    }
    if (line != null && moves) {
        Box(
            Modifier
                .offset { IntOffset(0, (line - 1.5f * density.density).roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(3.dp)
                .background(Q.accent),
        )
    }
    Text(
        d.label + if (t.stackOn >= 0) "  → tabs" else "",
        color = Q.text,
        fontSize = 14.sp,
        modifier = Modifier
            .offset { IntOffset((d.at.x - 40 * density.density).roundToInt(), (d.at.y - 34 * density.density).roundToInt()) }
            .clip(RoundedCornerShape(8.dp))
            .background(Q.panelDark)
            .border(1.dp, Q.accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The line between two slots: drag it to share the room of the open ones above and below it; double-tap: automatic again. */
@Composable
private fun DockDivider(
    active: Boolean,
    onDrag: (dy: Float, start: Triple<Int, Int, Int>) -> Unit,
    startHeights: () -> Triple<Int, Int, Int>,
    onDone: () -> Unit,
    onReset: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val drag by rememberUpdatedState(onDrag)
    val done by rememberUpdatedState(onDone)
    val reset by rememberUpdatedState(onReset)
    val heights by rememberUpdatedState(startHeights)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .then(
                if (!active) Modifier else Modifier
                    .pointerInput(Unit) {
                        var start = Triple(0, 0, 0)
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                start = heights()
                                total = 0f
                                dragging = true
                            },
                            onDragEnd = {
                                dragging = false
                                done()
                            },
                            onDragCancel = {
                                dragging = false
                                done()
                            },
                        ) { change, dy ->
                            change.consume()
                            total += dy
                            drag(total, start)
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (dragging) 10.dp else Q.gap(20.dp, 12.dp))
                .height(if (dragging) 3.dp else 1.dp)
                .background(if (dragging) Q.accent else Q.divider),
        )
        // A grip in the middle, so it's clear it can be dragged
        if (active && !dragging) Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Q.textDim.copy(alpha = 0.5f)))
    }
}

/** One slot: its header (one section's name, or tabs) and, when open, what's in it. */
@Composable
private fun DockSlotView(
    vm: EditorViewModel,
    slot: DockSlot,
    index: Int,
    shown: Int,
    open: Boolean,
    onPlaceImage: () -> Unit,
    floats: Boolean,
    onHeader: (Float) -> Unit,
    onBounds: (Rect) -> Unit,
    dockCoords: () -> LayoutCoordinates?,
    onDragStart: (section: PanelSection?, label: String, at: Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (cancelled: Boolean) -> Unit,
) {
    val section = slot.sections[shown]
    val pinned = section in vm.pinnedSections
    val tabs = slot.sections.size > 1
    val headerCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    fun toDock(coords: LayoutCoordinates?, p: Offset): Offset {
        val dc = dockCoords() ?: return p
        val c = coords?.takeIf { it.isAttached } ?: return p
        return dc.localPositionOf(c, p)
    }
    /** Dragging [what] (null: the whole slot) from its header or tab. */
    fun Modifier.draggable(what: PanelSection?, label: String, coords: Array<LayoutCoordinates?>): Modifier = pointerInput(index, what, label) {
        var dragging = false
        try {
            detectDragGestures(
                onDragStart = { p ->
                    dragging = true
                    start(what, label, toDock(coords[0], p))
                },
                onDragEnd = {
                    dragging = false
                    end(false)
                },
                onDragCancel = {
                    dragging = false
                    end(true)
                },
            ) { change, _ ->
                change.consume()
                move(toDock(coords[0], change.position))
            }
        } finally {
            // (Cut short, say the slot moved away: the dragged name doesn't stay on the screen)
            if (dragging) end(true)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { c ->
                val dc = dockCoords()
                if (dc != null && c.isAttached) {
                    val tl = dc.localPositionOf(c, Offset.Zero)
                    onBounds(Rect(tl.x, tl.y, tl.x + c.size.width, tl.y + c.size.height))
                }
            },
    ) {
        // The header: drag it to move the slot, or onto another's header to stack them
        val label = slot.sections.joinToString(" · ") { it.label }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    headerCoords[0] = it
                    onHeader(it.size.height.toFloat())
                }
                .draggable(null, label, headerCoords)
                .clickable { vm.toggleSection(section) }
                .padding(horizontal = Q.gap(10.dp, 6.dp), vertical = Q.gap(6.dp, 1.dp)),
        ) {
            if (section != PanelSection.LAYERS) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { vm.togglePin(section) },
                ) {
                    Icon(
                        if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (pinned) "Unpin (fold away by itself)" else "Pin open",
                        tint = if (pinned) Q.accent else Q.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(34.dp))
            }
            if (!tabs) {
                Text(section.label, color = Q.text, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            } else {
                // Tabs: tap one to show it, drag one out to split it off
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    slot.sections.forEachIndexed { k, s ->
                        val tabCoords = remember(s) { arrayOfNulls<LayoutCoordinates>(1) }
                        val on = k == shown
                        Text(
                            s.label,
                            color = if (on) Q.text else Q.textDim,
                            fontSize = if (on) 17.sp else 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .onGloballyPositioned { tabCoords[0] = it }
                                .draggable(s, s.label, tabCoords)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (on) {
                                        vm.toggleSection(s)
                                    } else {
                                        vm.toggleSection(s, open = true)
                                        // (Shown even when it was open already)
                                        vm.showInDock(s)
                                    }
                                }
                                .then(if (on) Modifier.background(Q.field) else Modifier)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        if (k < slot.sections.lastIndex) Spacer(Modifier.width(4.dp))
                    }
                }
            }
            if (section == PanelSection.LAYERS) LayersPickedChip(vm)
            // In a window of its own (big screens), or closed (the Window menu brings it back)
            if (floats) SlotHeaderButton(Icons.Outlined.OpenInNew, "Float in a window") {
                vm.floatSlot(index, if (tabs) section else null, (vm.workspaceWidthDp - vm.dockWidth - 340f).coerceAtLeast(8f), 80f)
            }
            SlotHeaderButton(Icons.Filled.Close, "Close ${section.label} (Window menu brings it back)") { vm.setPanelHidden(section, true) }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Q.text, modifier = Modifier.size(28.dp))
        }
        if (open) {
            // (Layers fills the slot; the others are as tall as they need, each tab keeping its own place)
            PanelBody(vm, section, onPlaceImage, if (section == PanelSection.LAYERS) Modifier.weight(1f) else Modifier.weight(1f, fill = false))
        }
    }
}

/** A small icon button in a slot's header. */
@Composable
private fun SlotHeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label) { onClick() },
    ) {
        Icon(icon, contentDescription = label, tint = Q.textDim, modifier = Modifier.size(17.dp))
    }
}
