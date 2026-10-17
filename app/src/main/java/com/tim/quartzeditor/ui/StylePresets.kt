package com.tim.quartzeditor.ui

import android.content.SharedPreferences
import android.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.BevelFx
import com.tim.quartzeditor.engine.GlowFx
import com.tim.quartzeditor.engine.GradientFx
import com.tim.quartzeditor.engine.GradientPreset
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.LongShadowFx
import com.tim.quartzeditor.engine.ShadowFx
import com.tim.quartzeditor.engine.StrokeFx
import org.json.JSONArray
import org.json.JSONObject

// Layer style presets (ready-made ones and your own), and copying a style from layer to layer.

class StylePreset(val name: String, val style: LayerStyle, val builtIn: Boolean)

private fun gradientNamed(name: String): List<Int> = GradientPreset.ALL.firstOrNull { it.name == name }?.colors ?: listOf(Color.BLACK, Color.WHITE)

private val BUILT_IN = listOf(
    StylePreset("Soft shadow", LayerStyle(dropShadow = ShadowFx(on = true, opacity = 0.45f, distance = 10f, size = 24f)), true),
    StylePreset("Outline", LayerStyle(stroke = StrokeFx(on = true, size = 4f)), true),
    StylePreset("Sticker", LayerStyle(stroke = StrokeFx(on = true, size = 12f, color = Color.WHITE), dropShadow = ShadowFx(on = true, opacity = 0.35f, distance = 6f, size = 14f)), true),
    StylePreset("Raised", LayerStyle(bevel = BevelFx(on = true, size = 8f, soften = 2f), dropShadow = ShadowFx(on = true, opacity = 0.4f, distance = 6f, size = 12f)), true),
    StylePreset("Pressed in", LayerStyle(bevel = BevelFx(on = true, size = 6f, up = false), innerShadow = ShadowFx(on = true, opacity = 0.5f, distance = 4f, size = 8f)), true),
    StylePreset("Long shadow", LayerStyle(longShadow = LongShadowFx(on = true)), true),
    StylePreset("Neon", LayerStyle(outerGlow = GlowFx(on = true, color = 0xFF00F5D4.toInt(), opacity = 0.9f, size = 30f), stroke = StrokeFx(on = true, size = 2f, color = 0xFFB9FFF4.toInt())), true),
    StylePreset("Gold", LayerStyle(
        gradientFill = GradientFx(on = true, colors = gradientNamed("Gold"), angle = 90f),
        bevel = BevelFx(on = true, size = 6f, soften = 1f),
        stroke = StrokeFx(on = true, size = 2f, color = 0xFF5C3A00.toInt()),
    ), true),
    StylePreset("Chrome", LayerStyle(
        gradientFill = GradientFx(on = true, colors = gradientNamed("Chrome"), angle = 90f),
        bevel = BevelFx(on = true, size = 8f, depth = 1.5f),
    ), true),
)

internal fun loadStylePresets(prefs: SharedPreferences): List<StylePreset> {
    val mine = runCatching {
        val a = JSONArray(prefs.getString("stylePresets", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            StylePreset(o.getString("name"), LayerStyle.fromJson(o.getJSONObject("style")), false)
        }
    }.getOrDefault(emptyList())
    return BUILT_IN + mine
}

private fun EditorViewModel.saveStylePresets() {
    val a = JSONArray()
    stylePresets.filter { !it.builtIn }.forEach { a.put(JSONObject().put("name", it.name).put("style", it.style.toJson())) }
    prefs.edit().putString("stylePresets", a.toString()).apply()
}

/** Saves [style] as a preset called [name] (replacing one of yours with the same name). */
fun EditorViewModel.saveStylePreset(name: String, style: LayerStyle) {
    val n = name.trim().ifEmpty { "My style" }
    stylePresets = stylePresets.filter { it.builtIn || it.name != n } + StylePreset(n, style, false)
    saveStylePresets()
    toast("Saved style \"$n\"")
}

fun EditorViewModel.deleteStylePreset(p: StylePreset) {
    if (p.builtIn) return
    stylePresets = stylePresets.filter { it !== p }
    saveStylePresets()
}

/** Gives the selected layers [p]'s style. */
fun EditorViewModel.applyStylePreset(p: StylePreset) {
    applyStyleTo(selectedLayerIds(), "Style: ${p.name}", p.style)
}

/** Remembers [layerId]'s style for pasting onto other layers. */
fun EditorViewModel.copyLayerStyle(layerId: Long) {
    val l = doc?.layer(layerId) ?: return
    copiedStyle = l.props.style
    toast(if (l.props.style.any) "Layer style copied" else "This layer has no style (pasting clears styles)")
}

fun EditorViewModel.pasteLayerStyle(ids: Collection<Long>) {
    val s = copiedStyle ?: return
    applyStyleTo(ids, "Paste layer style", s)
}

/** One undo step that gives every layer in [ids] (that can have a style) the style [s]. */
internal fun EditorViewModel.applyStyleTo(ids: Collection<Long>, label: String, s: LayerStyle) {
    val d = doc ?: return
    if (pendingFirst { applyStyleTo(ids, label, s) }) return
    val targets = ids.mapNotNull { d.layer(it) }.filter { it.canHaveStyle && !it.props.locked }
    if (targets.isEmpty()) {
        toast("Pick a pixel, shape or text layer, or a folder")
        return
    }
    stackEdit(label) { for (l in targets) l.props = l.props.copy(style = s) }
}

/** Preset chips at the top of Layer Style: tap to apply, Save adds the current style. Hold one of yours to delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StylePresetsRow(vm: EditorViewModel) {
    val l = vm.doc?.active ?: return
    var naming by remember { mutableStateOf(false) }
    Text("Presets", color = Q.textDim, fontSize = 12.sp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) {
        if (l.props.style.any) {
            Text(
                "+ Save",
                color = Q.text,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Q.accent)
                    .combinedClickable(onClick = { naming = true })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        vm.stylePresets.forEach { p ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Text(
                    p.name,
                    color = Q.text,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (p.style == l.props.style) Q.rowSelected else Q.field)
                        .combinedClickable(onClick = { vm.applyStylePreset(p) }, onLongClick = { if (!p.builtIn) menu = true })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete \"${p.name}\"") }, onClick = { menu = false; vm.deleteStylePreset(p) })
                }
            }
        }
    }
    if (naming) {
        SavePresetDialog(onDismiss = { naming = false }) { name ->
            naming = false
            vm.saveStylePreset(name, l.props.style)
        }
    }
}

@Composable
fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("My style") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save layer style") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
