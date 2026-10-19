package com.tim.quartzeditor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.Swatch
import com.tim.quartzeditor.engine.SwatchGroup
import com.tim.quartzeditor.engine.Swatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Swatches: the second tab of every color picker. Your own swatches (add the color you're on),
// sets imported from Adobe swatch files (.aco, .ase), and ready-made sets.

private fun swatchFile(vm: EditorViewModel) = File(vm.ctx.filesDir, "swatches.json")

/** Your swatches and imported sets, read the first time they're needed. */
internal fun EditorViewModel.ensureSwatches() {
    if (swatchGroups != null) return
    swatchGroups = listOf(SwatchGroup("My swatches", emptyList(), mine = true))
    viewModelScope.launch {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { swatchFile(this@ensureSwatches).takeIf { it.isFile }?.readText()?.let { Swatches.fromJson(it) } }.getOrNull()
        }
        if (!loaded.isNullOrEmpty()) swatchGroups = if (loaded.any { it.mine }) loaded else listOf(SwatchGroup("My swatches", emptyList(), mine = true)) + loaded
    }
}

private fun EditorViewModel.saveSwatches(groups: List<SwatchGroup>) {
    swatchGroups = groups
    viewModelScope.launch(Dispatchers.IO) { runCatching { swatchFile(this@saveSwatches).writeText(Swatches.toJson(groups)) } }
}

/** Adds [color] to your swatches (named by its hex). */
fun EditorViewModel.addSwatch(color: Int) {
    ensureSwatches()
    val groups = swatchGroups.orEmpty()
    val c = color or 0xFF000000.toInt()
    val mine = groups.firstOrNull { it.mine } ?: SwatchGroup("My swatches", emptyList(), mine = true)
    if (mine.swatches.any { it.color == c }) {
        toast("That color is already in your swatches")
        return
    }
    val updated = mine.copy(swatches = mine.swatches + Swatch(c, hexOf(c)))
    saveSwatches(listOf(updated) + groups.filter { !it.mine })
    toast("Added to your swatches")
}

/** Takes a swatch out of your swatches or out of an imported set. */
fun EditorViewModel.removeSwatch(group: SwatchGroup, s: Swatch) {
    val groups = swatchGroups ?: return
    saveSwatches(groups.mapNotNull { g ->
        if (g != group) g else g.copy(swatches = g.swatches - s).takeIf { it.mine || it.swatches.isNotEmpty() }
    })
    toast("Removed ${s.name.ifBlank { hexOf(s.color) }}")
}

fun EditorViewModel.removeSwatchGroup(group: SwatchGroup) {
    val groups = swatchGroups ?: return
    saveSwatches(groups.filter { it != group })
    toast("Removed \"${group.name}\"")
}

/** Adds the sets in a Photoshop (.aco) or Adobe (.ase) swatch file. */
fun EditorViewModel.importSwatches(uri: Uri) {
    ensureSwatches()
    viewModelScope.launch {
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val name = com.tim.quartzeditor.data.Media.displayName(ctx, uri).take(40)
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    val bytes = s.readBytes()
                    if (bytes.size > 8 * 1024 * 1024) null else Swatches.parse(bytes, name)
                }
            }.getOrNull()
        }
        if (found.isNullOrEmpty()) {
            toast("That isn't a swatch file this can read (.aco or .ase)")
            return@launch
        }
        val groups = swatchGroups.orEmpty()
        saveSwatches(groups.filter { it.mine } + found + groups.filter { !it.mine && it.name !in found.map { f -> f.name } })
        toast("Added ${found.sumOf { it.swatches.size }} swatches")
    }
}

/** The Picker / Swatches tabs at the top of a color picker. */
@Composable
internal fun ColorTabs(vm: EditorViewModel) {
    ChipRow(listOf("Picker", "Swatches"), vm.colorTab) {
        vm.colorTab = it
        vm.prefs.edit().putInt("colorTab", it).apply()
    }
    Spacer(Modifier.height(8.dp))
}

/** The Swatches tab: tap one to use it; hold one of yours (or an imported one) to remove it. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SwatchGrid(vm: EditorViewModel, current: Int, onPick: (Int) -> Unit) {
    LaunchedEffect(Unit) { vm.ensureSwatches() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importSwatches(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("+ Add this color") { vm.addSwatch(current) }
        SmallButton("Import .aco / .ase…") { importer.launch(arrayOf("*/*")) }
    }
    val groups = vm.swatchGroups.orEmpty() + Swatches.BUILT_IN
    Column(Modifier.fillMaxWidth()) {
        for (g in groups) {
            if (g.mine && g.swatches.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(g.name, color = Q.textDim, fontSize = 12.sp)
                Text("Tap + Add this color to keep the color you're on here.", color = Q.textDim, fontSize = 11.sp)
                continue
            }
            Spacer(Modifier.height(10.dp))
            val removable = g.mine || g !in Swatches.BUILT_IN
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, color = Q.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (!g.mine && removable) {
                    Text(
                        "Remove set", color = Q.accent, fontSize = 11.sp,
                        modifier = Modifier.clip(CircleShape).combinedClickable(onClick = { vm.removeSwatchGroup(g) }).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in g.swatches) {
                    val sel = (s.color or 0xFF000000.toInt()) == (current or 0xFF000000.toInt())
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(s.color))
                            .border(if (sel) 2.dp else 1.dp, if (sel) Q.accent else Q.text.copy(alpha = 0.3f), CircleShape)
                            .combinedClickable(
                                onClick = {
                                    onPick(s.color or 0xFF000000.toInt())
                                    if (s.name.isNotBlank() && s.name != hexOf(s.color)) vm.toast(s.name)
                                },
                                onLongClick = if (removable) ({ vm.removeSwatch(g, s) }) else null,
                            )
                    )
                }
            }
        }
    }
}
