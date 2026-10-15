package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.GradientPreset
import com.tim.quartzeditor.engine.GradientType
import com.tim.quartzeditor.engine.SelectMode
import com.tim.quartzeditor.engine.SelectShape
import kotlin.math.roundToInt

// ===================================================================== Gradients panel

/** The gradient the Gradient tool paints: a preset, its type, direction and strength. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradientsPanel(vm: EditorViewModel) {
    Text(
        if (vm.tool == Tool.GRADIENT) "Drag on the picture to paint it." else "Pick one, then drag on the picture.",
        color = Q.textDim, fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
        GradientPreset.ALL.forEachIndexed { i, p ->
            val cols = p.resolve(vm.foreground, vm.background).map { Color(it) }
            val sel = i == vm.gradientPreset
            Box(
                Modifier
                    .size(width = 62.dp, height = 30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .checker(8f)
                    .background(Brush.horizontalGradient(if (cols.size >= 2) cols else listOf(Color.Black, Color.White)))
                    .border(if (sel) 2.dp else 1.dp, if (sel) Q.accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .clickable {
                        vm.gradientPreset = i
                        vm.saveGradientPrefs()
                        if (vm.tool != Tool.GRADIENT) vm.onToolButton(Tool.GRADIENT)
                    }
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(GradientPreset.ALL.getOrNull(vm.gradientPreset)?.name ?: "", color = Q.text, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    GradientOptions(vm)
}

@Composable
private fun GradientOptions(vm: EditorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GradientType.entries.forEach { t ->
            SmallButton(t.label, primary = t == vm.gradientType) {
                vm.gradientType = t
                vm.saveGradientPrefs()
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Reverse", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = vm.gradientReverse,
            onCheckedChange = {
                vm.gradientReverse = it
                vm.saveGradientPrefs()
            },
            colors = SwitchDefaults.colors(checkedTrackColor = Q.accent),
        )
    }
    PanelSlider("Opacity", vm.gradientOpacity, 0.01f..1f, "${(vm.gradientOpacity * 100).roundToInt()}%", onChange = { vm.gradientOpacity = it }, onDone = { vm.saveGradientPrefs() })
}

// ===================================================================== tool options in Properties

@Composable
fun ToolOptions(vm: EditorViewModel) {
    when (vm.tool) {
        Tool.SELECT -> {
            Title("Selection")
            Text("Shape (tap Select again for the next one)", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            ChipRow(SelectShape.entries.map { it.label }, vm.selectShape.ordinal) { i -> vm.selectShape = SelectShape.entries[i] }
            Spacer(Modifier.height(6.dp))
            ChipRow(SelectMode.entries.map { it.label }, vm.selectMode.ordinal) { i -> vm.selectMode = SelectMode.entries[i] }
            Spacer(Modifier.height(6.dp))
            val sel = vm.doc?.selection
            Text(
                if (sel == null) "Nothing selected: changes reach the whole layer." else "Feather ${sel.feather.roundToInt()} px. Brushes, fills, gradients and adjustments only change what's selected.",
                color = Q.textDim, fontSize = 12.sp,
            )
            if (vm.selectShape == SelectShape.POLYGON) {
                Text("Tap to place corners; tap the first corner (or Close) to finish.", color = Q.textDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        Tool.BUCKET -> {
            Title("Paint bucket")
            PanelSlider("Tolerance", vm.bucketTolerance, 0f..1f, "${(vm.bucketTolerance * 100).roundToInt()}%", onChange = { vm.bucketTolerance = it }, onDone = { vm.prefs.edit().putFloat("bucketTol", vm.bucketTolerance).apply() })
            ToggleRow("Only touching pixels", vm.bucketContiguous) {
                vm.bucketContiguous = it
                vm.prefs.edit().putBoolean("bucketContig", it).apply()
            }
            ToggleRow("Look at all layers", vm.bucketAllLayers) {
                vm.bucketAllLayers = it
                vm.prefs.edit().putBoolean("bucketAll", it).apply()
            }
            Text("Tap the picture to fill with the foreground color.", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }
        Tool.GRADIENT -> {
            Title("Gradient")
            GradientOptions(vm)
            Text("Pick colors in the Gradients panel. Drag on the picture: the gradient runs from where you start to where you let go.", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }
        Tool.CROP -> {
            val b = vm.cropBox
            Title("Crop")
            ChipRow(CropRatio.entries.map { it.label }, vm.cropRatio.ordinal) { i -> vm.applyCropRatio(CropRatio.entries[i]) }
            if (b != null) {
                Spacer(Modifier.height(6.dp))
                Text("${b.width().roundToInt()} × ${b.height().roundToInt()} px", color = Q.text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Cancel") { vm.cancelCrop() }
                    SmallButton("Apply", primary = true) { vm.applyCrop() }
                }
            }
            Text("Drag the box past the edges to add space. Cropped-off pixels are kept on their layers.", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
        }
        else -> Unit
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Q.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            Text(
                l,
                color = Q.text,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (i == selected) Q.rowSelected else Q.field)
                    .clickable { onPick(i) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Q.accent))
    }
}

// ===================================================================== bars over the picture

@Composable
private fun Bar(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(24.dp))
            .background(Q.toolbar.copy(alpha = 0.95f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) { content() }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BarChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Q.accent else Q.field)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Selection tool: how shapes combine, and what to do with the selection. */
@Composable
fun SelectBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    if (vm.tool != Tool.SELECT) return
    val d = vm.doc ?: return
    Bar(modifier) {
        if (vm.polygonPoints.isNotEmpty()) {
            BarIcon(Icons.Outlined.Close, "Cancel shape") { vm.cancelPolygon() }
            BarChip("Close shape", true) { vm.finishPolygon() }
            return@Bar
        }
        BarChip(vm.selectShape.label, false) {
            vm.onToolButton(Tool.SELECT)
        }
        Spacer(Modifier.width(4.dp))
        SelectMode.entries.forEach { m -> BarChip(m.label, vm.selectMode == m) { vm.selectMode = m } }
        Spacer(Modifier.width(4.dp))
        BarIcon(Icons.Outlined.SelectAll, "Select all") { vm.selectAll() }
        BarIcon(Icons.Outlined.Deselect, "Deselect") { vm.deselect() }
        BarIcon(Icons.Outlined.InvertColors, "Invert selection") { vm.invertSelection() }
        Box {
            var menu by remember { mutableStateOf(false) }
            BarIcon(Icons.Outlined.MoreVert, "More") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text("Feather (soft edge)", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 2, 5, 10, 25, 50).forEach { f ->
                        SmallButton("$f", primary = (d.selection?.feather?.roundToInt() ?: -1) == f) {
                            menu = false
                            vm.featherSelection(f.toFloat())
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Copy to new layer") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; vm.selectionToLayer(cut = false) })
                DropdownMenuItem(text = { Text("Cut to new layer") }, leadingIcon = { Icon(Icons.Outlined.ContentCut, null) }, onClick = { menu = false; vm.selectionToLayer(cut = true) })
                DropdownMenuItem(text = { Text("Delete selected pixels") }, onClick = { menu = false; vm.clearSelected() })
                DropdownMenuItem(text = { Text("Fill with foreground color") }, onClick = { menu = false; vm.fillWithColor(vm.foreground) })
                DropdownMenuItem(text = { Text("Mask from selection") }, onClick = { menu = false; vm.maskFromSelection() })
                DropdownMenuItem(text = { Text("Crop to selection") }, onClick = { menu = false; vm.cropToSelection() })
            }
        }
    }
}

/** Crop tool: box shapes, cancel, apply. */
@Composable
fun CropBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val b = vm.cropBox ?: return
    Bar(modifier) {
        BarIcon(Icons.Outlined.Close, "Cancel crop") { vm.cancelCrop() }
        CropRatio.entries.forEach { r -> BarChip(r.label, vm.cropRatio == r) { vm.applyCropRatio(r) } }
        Text("${b.width().roundToInt()}×${b.height().roundToInt()}", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Q.accent)
                .clickable { vm.applyCrop() },
        ) {
            Icon(Icons.Outlined.Check, contentDescription = "Apply crop", tint = Color.White)
        }
    }
}
