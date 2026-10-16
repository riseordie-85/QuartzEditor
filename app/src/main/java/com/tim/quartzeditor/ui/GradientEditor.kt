package com.tim.quartzeditor.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tim.quartzeditor.engine.GradientPreset
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// Gradients you make: colors (with see-through) placed anywhere along the bar. They're kept in the
// app and show after the ready-made ones, for the Gradient tool and the Gradient Fill style.

// ===================================================================== storage

internal fun loadUserGradients(prefs: SharedPreferences): List<GradientPreset> = runCatching {
    val a = JSONArray(prefs.getString("userGradients", "[]"))
    List(a.length()) { i ->
        val o = a.getJSONObject(i)
        val c = o.getJSONArray("colors")
        val p = o.getJSONArray("positions")
        GradientPreset(
            o.optString("name", "Gradient"),
            List(c.length()) { c.getLong(it).toInt() },
            positions = List(p.length()) { p.getDouble(it).toFloat() },
            custom = true,
        )
    }.filter { it.colors.size >= 2 && it.positions?.size == it.colors.size }
}.getOrDefault(emptyList())

private fun EditorViewModel.saveUserGradients(list: List<GradientPreset>) {
    userGradients = list
    val a = JSONArray()
    for (g in list) {
        a.put(
            JSONObject()
                .put("name", g.name)
                .put("colors", JSONArray().also { c -> g.colors.forEach { c.put(it.toLong() and 0xFFFFFFFFL) } })
                .put("positions", JSONArray().also { p -> (g.positions ?: evenly(g.colors.size)).forEach { p.put(it.toDouble()) } })
        )
    }
    prefs.edit().putString("userGradients", a.toString()).apply()
}

private fun evenly(n: Int) = List(n) { if (n <= 1) 0f else it / (n - 1f) }

/** The ready-made gradients, then yours. */
val EditorViewModel.allGradients: List<GradientPreset> get() = GradientPreset.ALL + userGradients

/** Adds [g] as a new gradient (or puts it in place of your gradient [replacing]) and picks it for the Gradient tool. */
fun EditorViewModel.saveGradient(g: GradientPreset, replacing: GradientPreset?) {
    val mine = g.copy(custom = true, dynamic = GradientPreset.Dynamic.NONE)
    val list = userGradients.toMutableList()
    val at = replacing?.let { list.indexOf(it) } ?: -1
    if (at >= 0) list[at] = mine else list.add(mine)
    saveUserGradients(list)
    gradientPreset = GradientPreset.ALL.size + (if (at >= 0) at else list.lastIndex)
    saveGradientPrefs()
}

fun EditorViewModel.deleteGradient(g: GradientPreset) {
    val i = userGradients.indexOf(g)
    if (i < 0) return
    saveUserGradients(userGradients.filterIndexed { n, _ -> n != i })
    val index = GradientPreset.ALL.size + i
    // The Gradient tool's pick stays on the same gradient (or goes back to the first)
    gradientPreset = when {
        gradientPreset == index -> 0
        gradientPreset > index -> gradientPreset - 1
        else -> gradientPreset
    }
    saveGradientPrefs()
}

// ===================================================================== pieces

/** A gradient shown left to right over a checkerboard (so see-through parts show). */
internal fun gradientBrush(colors: List<Int>, positions: List<Float>?): Brush {
    val cols = if (colors.size >= 2) colors else listOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE)
    val pos = positions?.takeIf { it.size == cols.size } ?: evenly(cols.size)
    return Brush.horizontalGradient(colorStops = cols.indices.map { pos[it].coerceIn(0f, 1f) to Color(cols[it]) }.sortedBy { it.first }.toTypedArray())
}

/**
 * One gradient to pick. Your own ones can be held for Edit / Delete (when [onEdit] is given).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GradientTile(
    vm: EditorViewModel,
    g: GradientPreset,
    selected: Boolean,
    width: Dp,
    height: Dp,
    onPick: () -> Unit,
    onEdit: ((GradientPreset) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(6.dp))
                .checker(8f)
                .background(gradientBrush(g.resolve(vm.foreground, vm.background), g.positions))
                .border(if (selected) 2.dp else 1.dp, if (selected) Q.accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                .combinedClickable(onClick = onPick, onLongClick = { if (g.custom && onEdit != null) menu = true })
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(g.name, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            DropdownMenuItem(text = { Text("Edit…") }, onClick = { menu = false; onEdit?.invoke(g) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.deleteGradient(g) })
        }
    }
}

/** The "+" tile that starts a new gradient. */
@Composable
internal fun NewGradientTile(width: Dp, height: Dp, onClick: () -> Unit) {
    Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = "New gradient", tint = Q.textDim, modifier = Modifier.size(18.dp))
    }
}

// ===================================================================== the editor

private class Stop(color: Int, pos: Float) {
    var color by mutableIntStateOf(color)
    var pos by mutableStateOf(pos)
}

/**
 * Makes a gradient (starting from [start]'s colors), or changes your gradient [editing]. Tap the
 * bar to add a color there, drag the markers to move them, tap one to change its color or
 * see-through, or delete it.
 */
@Composable
fun GradientEditorDialog(
    vm: EditorViewModel,
    start: GradientPreset,
    editing: GradientPreset?,
    onDone: () -> Unit,
    /** Called with the gradient just saved. */
    onSaved: (GradientPreset) -> Unit = {},
) {
    val stops = remember {
        val cols = start.resolve(vm.foreground, vm.background).ifEmpty { listOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE) }
        val pos = start.positions?.takeIf { it.size == cols.size } ?: evenly(cols.size)
        mutableStateListOf<Stop>().apply { cols.indices.forEach { add(Stop(cols[it], pos[it])) } }
    }
    var picked by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(editing?.name ?: "My gradient ${vm.userGradients.size + 1}") }
    var pickingColor by remember { mutableStateOf(false) }

    fun ordered() = stops.sortedBy { it.pos }
    fun colorAt(p: Float): Int {
        val s = ordered()
        if (p <= s.first().pos) return s.first().color
        if (p >= s.last().pos) return s.last().color
        val i = s.indexOfFirst { it.pos >= p }
        val a = s[i - 1]
        val b = s[i]
        val t = ((p - a.pos) / (b.pos - a.pos).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        return androidx.core.graphics.ColorUtils.blendARGB(a.color, b.color, t)
    }

    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .border(1.dp, Q.divider, RoundedCornerShape(22.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(if (editing != null) "Edit gradient" else "New gradient", color = Q.text, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            // The bar, and the color markers under it
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val barW = maxWidth
                val widthPx = with(density) { barW.toPx() }
                val s = ordered()
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .checker(10f)
                            .background(gradientBrush(s.map { it.color }, s.map { it.pos }))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                // A tap adds a color there (the color the gradient already has at that spot)
                                detectTapGestures { o ->
                                    val p = (o.x / size.width).coerceIn(0f, 1f)
                                    stops.add(Stop(colorAt(p), p))
                                    picked = stops.lastIndex
                                }
                            }
                    )
                    Box(Modifier.fillMaxWidth().height(30.dp)) {
                        stops.forEachIndexed { i, st ->
                            val x = with(density) { (st.pos * widthPx).toDp() } - 11.dp
                            Box(
                                Modifier
                                    .offset { IntOffset(with(density) { x.roundToPx() }, with(density) { 4.dp.roundToPx() }) }
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .checker(5f)
                                    .background(Color(st.color))
                                    .border(if (i == picked) 3.dp else 1.5.dp, if (i == picked) Q.accent else Color.White, CircleShape)
                                    .pointerInput(st) {
                                        detectTapGestures { picked = stops.indexOf(st) }
                                    }
                                    .pointerInput(st) {
                                        detectDragGestures(onDragStart = { picked = stops.indexOf(st) }) { change, drag ->
                                            change.consume()
                                            st.pos = (st.pos + drag.x / widthPx).coerceIn(0f, 1f)
                                        }
                                    }
                            )
                        }
                    }
                }
            }
            Text("Tap the bar to add a color. Drag a marker to move it; tap one to change it.", color = Q.textDim, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))

            // The picked color
            stops.getOrNull(picked)?.let { st ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Color", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                    Box(
                        Modifier
                            .size(width = 52.dp, height = 30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .checker(6f)
                            .background(Color(st.color))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { pickingColor = true }
                    )
                    Spacer(Modifier.width(8.dp))
                    SmallButton("Foreground") { st.color = (vm.foreground and 0x00FFFFFF) or (st.color and 0xFF000000.toInt()) }
                    Spacer(Modifier.width(6.dp))
                    SmallButton("Delete", enabled = stops.size > 2) {
                        if (stops.size > 2) {
                            stops.removeAt(picked)
                            picked = picked.coerceAtMost(stops.lastIndex)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                PanelSlider("Location", st.pos, 0f..1f, "${(st.pos * 100).roundToInt()}%", onChange = { st.pos = it })
                val alpha = (st.color ushr 24) / 255f
                PanelSlider("Opacity", alpha, 0f..1f, "${(alpha * 100).roundToInt()}%", onChange = {
                    st.color = (st.color and 0x00FFFFFF) or ((it * 255).roundToInt().coerceIn(0, 255) shl 24)
                })
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("Reverse") {
                    stops.forEach { it.pos = 1f - it.pos }
                }
                SmallButton("Even spacing") {
                    val s = ordered()
                    s.forEachIndexed { i, st -> st.pos = if (s.size <= 1) 0f else i / (s.size - 1f) }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row {
                Spacer(Modifier.weight(1f))
                SmallButton("Cancel") { onDone() }
                Spacer(Modifier.width(6.dp))
                if (editing != null) {
                    SmallButton("Save as new") {
                        val s = ordered()
                        val g = GradientPreset(name.ifBlank { "Gradient" }, s.map { it.color }, positions = s.map { it.pos }, custom = true)
                        vm.saveGradient(g, null)
                        onSaved(g)
                        onDone()
                    }
                    Spacer(Modifier.width(6.dp))
                }
                SmallButton("Save", primary = true) {
                    val s = ordered()
                    val g = GradientPreset(name.ifBlank { "Gradient" }, s.map { it.color }, positions = s.map { it.pos }, custom = true)
                    vm.saveGradient(g, editing)
                    onSaved(g)
                    onDone()
                }
            }
        }
    }
    if (pickingColor) {
        stops.getOrNull(picked)?.let { st ->
            val keepAlpha = st.color and 0xFF000000.toInt()
            ColorPickDialog(
                vm, st.color, "Gradient color",
                onLive = { c -> st.color = (c and 0x00FFFFFF) or keepAlpha },
                onDone = { pickingColor = false },
            )
        } ?: run { pickingColor = false }
    }
}
