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
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AutoFixNormal
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
    // The gradient being made or changed (null: none)
    var editor by remember { mutableStateOf<Pair<GradientPreset, Int?>?>(null) }
    val all = vm.allGradients
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
        all.forEachIndexed { i, p ->
            val mine = (i - GradientPreset.ALL.size).takeIf { it >= 0 }
            GradientTile(vm, p, i == vm.gradientPreset, 62.dp, 30.dp, onPick = {
                vm.gradientPreset = i
                vm.saveGradientPrefs()
                if (vm.tool != Tool.GRADIENT) vm.onToolButton(Tool.GRADIENT)
            }, userIndex = mine, onEdit = { n -> editor = vm.userGradients[n] to n })
        }
        // A new one starts from the gradient picked now
        NewGradientTile(62.dp, 30.dp) { editor = all.getOrElse(vm.gradientPreset) { GradientPreset.ALL[0] } to null }
    }
    Spacer(Modifier.height(4.dp))
    val current = all.getOrNull(vm.gradientPreset)
    Text(
        (current?.name ?: "") + if (current?.custom == true) " · hold it to edit or delete" else "",
        color = Q.text, fontSize = 12.sp,
    )
    editor?.let { (start, editing) -> GradientEditorDialog(vm, start, editing, onDone = { editor = null }, pickForTool = true) }
    Spacer(Modifier.height(8.dp))
    GradientOptions(vm)
}

@Composable
private fun GradientOptions(vm: EditorViewModel) {
    ChipRow(GradientType.entries.map { it.label }, vm.gradientType.ordinal) { i ->
        vm.gradientType = GradientType.entries[i]
        vm.saveGradientPrefs()
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
            if (vm.selectShape == SelectShape.OBJECT) {
                Text("Tap an object, or drag a box around it: the AI outlines it on your device. The first time on a picture it takes a moment to look. Hold ⇧ to add to the selection, slide out to Alt to take away.", color = Q.textDim, fontSize = 12.sp)
            }
            if (vm.selectShape == SelectShape.WAND) {
                Spacer(Modifier.height(6.dp))
                Text("Tap the picture to select pixels of that color.", color = Q.textDim, fontSize = 12.sp)
                PanelSlider("Tolerance", vm.wandTolerance, 0f..1f, "${(vm.wandTolerance * 255).roundToInt()}", onChange = { vm.wandTolerance = it }, onDone = { vm.prefs.edit().putFloat("wandTol", vm.wandTolerance).apply() }, fromTyped = { it / 255f })
                ToggleRow("Only touching pixels", vm.wandContiguous) {
                    vm.wandContiguous = it
                    vm.prefs.edit().putBoolean("wandContig", it).apply()
                }
                ToggleRow("Look at all layers", vm.wandAllLayers) {
                    vm.wandAllLayers = it
                    vm.prefs.edit().putBoolean("wandAll", it).apply()
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("Select subject") { vm.selectSubject(background = false) }
                SmallButton("Background") { vm.selectSubject(background = true) }
                SmallButton("Invert") { vm.invertSelection() }
            }
            Spacer(Modifier.height(8.dp))
            GrowRow(vm)
            Text("Finds the main subject on your device (it may download once through Google Play services).", color = Q.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
        }
        Tool.RETOUCH -> RetouchOptions(vm)
        Tool.BUCKET -> {
            Title(if (vm.bucketPattern) "Pattern fill" else "Paint bucket")
            ChipRow(listOf("Color", "Pattern"), if (vm.bucketPattern) 1 else 0) { vm.setBucketPattern(it == 1) }
            Spacer(Modifier.height(8.dp))
            if (vm.bucketPattern) {
                PatternPicker(vm)
                Spacer(Modifier.height(8.dp))
            }
            PanelSlider("Tolerance", vm.bucketTolerance, 0f..1f, "${(vm.bucketTolerance * 100).roundToInt()}%", onChange = { vm.bucketTolerance = it }, onDone = { vm.prefs.edit().putFloat("bucketTol", vm.bucketTolerance).apply() })
            ToggleRow("Only touching pixels", vm.bucketContiguous) {
                vm.bucketContiguous = it
                vm.prefs.edit().putBoolean("bucketContig", it).apply()
            }
            ToggleRow("Look at all layers", vm.bucketAllLayers) {
                vm.bucketAllLayers = it
                vm.prefs.edit().putBoolean("bucketAll", it).apply()
            }
            Text(if (vm.bucketPattern) "Tap the picture to fill that area with the pattern." else "Tap the picture to fill with the foreground color.", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }
        Tool.WARP -> WarpOptions(vm)
        Tool.GRADIENT -> {
            Title("Gradient")
            GradientsPanel(vm)
            Spacer(Modifier.height(10.dp))
        }
        Tool.CROP -> {
            val b = vm.cropBox
            val q = vm.cropQuad
            Title("Crop")
            ChipRow(listOf("Crop", "Perspective"), if (vm.cropPerspective) 1 else 0) { i -> vm.chooseCrop(perspective = i == 1) }
            if (!vm.cropPerspective) ChipRow(CropRatio.entries.map { it.label }, vm.cropRatio.ordinal) { i -> vm.applyCropRatio(CropRatio.entries[i]) }
            if (b != null) {
                Spacer(Modifier.height(6.dp))
                val (cw, ch) = if (q != null) quadOutputSize(q) else b.width().roundToInt() to b.height().roundToInt()
                Text("$cw × $ch px", color = Q.text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Cancel") { vm.cancelCrop() }
                    SmallButton("Apply", primary = true) { vm.applyCrop() }
                }
            }
            Text(
                if (vm.cropPerspective) "Drag each corner onto a corner of something slanted (a sign, a window, a screen). Apply straightens it into a flat, square-on picture."
                else "Drag the box past the edges to add space. Cropped-off pixels are kept on their layers.",
                color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
        }
        else -> Unit
    }
}

@Composable
internal fun Title(text: String) {
    Text(text, color = Q.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
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
internal fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Q.accent))
    }
}

// ===================================================================== bars over the picture

@Composable
internal fun Bar(modifier: Modifier, content: @Composable () -> Unit) {
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
internal fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
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
internal fun BarChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) Color.White else Q.text,
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
        BarChip("Deselect", false) { vm.deselect() }
        BarChip("Invert", false) { vm.invertSelection() }
        Box {
            var menu by remember { mutableStateOf(false) }
            BarIcon(Icons.Outlined.MoreVert, "More") { menu = true }
            PagedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Page("Modify edge") {
                    Label("Feather (soft edge)")
                    Custom {
                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 2, 5, 10, 25, 50).forEach { f ->
                                SmallButton("$f", primary = (d.selection?.feather?.roundToInt() ?: -1) == f) {
                                    menu = false
                                    vm.featherSelection(f.toFloat())
                                }
                            }
                        }
                    }
                    Label("Expand / shrink (pixels)")
                    Custom {
                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(-10, -5, -2, 2, 5, 10).forEach { g ->
                                SmallButton(if (g > 0) "+$g" else "$g") {
                                    menu = false
                                    vm.growSelection(g.toFloat())
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Page("Select") {
                    Item("Select subject", Icons.Outlined.PersonOutline) { vm.selectSubject(background = false) }
                    Item("Select background", Icons.Outlined.Landscape) { vm.selectSubject(background = true) }
                }
                Divider()
                Page("New layer") {
                    Item("Copy to new layer", Icons.Outlined.ContentCopy) { vm.selectionToLayer(cut = false) }
                    Item("Cut to new layer", Icons.Outlined.ContentCut) { vm.selectionToLayer(cut = true) }
                }
                Page("Fill and remove") {
                    Item("Fill with foreground color") { vm.fillWithColor(vm.foreground) }
                    Item("Fill with pattern") { vm.fillWithPattern() }
                    Divider()
                    Item("Delete selected pixels") { vm.clearSelected() }
                    Item("AI Remove selected", Icons.Outlined.AutoFixNormal) { vm.removeSelected() }
                }
                Divider()
                Item("Mask from selection") { vm.maskFromSelection() }
                Item("Define pattern from selection") { vm.definePattern() }
                Item("Crop to selection") { vm.cropToSelection() }
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
        val q = vm.cropQuad
        if (q != null) BarChip("Perspective", true) { vm.chooseCrop(perspective = false) }
        else CropRatio.entries.forEach { r -> BarChip(r.label, vm.cropRatio == r) { vm.applyCropRatio(r) } }
        val (cw, ch) = if (q != null) quadOutputSize(q) else b.width().roundToInt() to b.height().roundToInt()
        Text("$cw×$ch", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
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

/** Expand or shrink the selection by a typed number of pixels. */
@Composable
private fun GrowRow(vm: EditorViewModel) {
    // What's typed, read when a button is tapped
    var raw by remember { mutableStateOf("5") }
    fun px(): Float = raw.trim().toFloatOrNull()?.takeIf { it.isFinite() && it >= 1f }?.coerceAtMost(500f) ?: 5f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Shrink") { vm.growSelection(-px()) }
        SmallButton("Expand") { vm.growSelection(px()) }
        Spacer(Modifier.width(4.dp))
        Text("by", color = Q.textDim, fontSize = 13.sp)
        PanelField(
            "5",
            onCommit = { raw = it },
            modifier = Modifier.width(56.dp),
            numeric = true,
            onType = { raw = it },
        )
        Text("px", color = Q.textDim, fontSize = 13.sp)
    }
}

/** The retouch brush in use, and its settings (size, softness and strength are in the brush column). */
@Composable
private fun RetouchOptions(vm: EditorViewModel) {
    val k = vm.retouchKind
    Title(k.label)
    // Only this toolbar button's tools: healing, or toning
    val group = if (k.heals) com.tim.quartzeditor.engine.RetouchKind.entries.filter { it.heals }
    else listOf(
        com.tim.quartzeditor.engine.RetouchKind.BLUR, com.tim.quartzeditor.engine.RetouchKind.SHARPEN, com.tim.quartzeditor.engine.RetouchKind.SMUDGE,
        com.tim.quartzeditor.engine.RetouchKind.DODGE, com.tim.quartzeditor.engine.RetouchKind.BURN,
    )
    ChipRow(group.map { it.label }, group.indexOf(k)) { i -> vm.chooseRetouch(group[i]) }
    Spacer(Modifier.height(6.dp))
    val help = when (k) {
        com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL -> "Paint over a spot, scratch or small thing (up to about 250 px across); it's healed with texture from around it when you lift your finger. For bigger things, AI Remove does better."
        com.tim.quartzeditor.engine.RetouchKind.AI_REMOVE -> "Paint over something to remove (a person, a sign, a cone, wires): the AI fills in what would be behind it, on your device. Include its shadow. It looks at the picture as you see it, so it can work on an empty layer above the photo."
        com.tim.quartzeditor.engine.RetouchKind.CLONE -> "Hold ⇧ and tap where to copy from (or tap Pick source), then paint. Slide out to Alt for a straight line. Strength sets how strongly it covers."
        com.tim.quartzeditor.engine.RetouchKind.DODGE -> "Lightens where you paint."
        com.tim.quartzeditor.engine.RetouchKind.BURN -> "Darkens where you paint."
        com.tim.quartzeditor.engine.RetouchKind.SMUDGE -> "Drags the colors along with your finger. Strength sets how far."
        com.tim.quartzeditor.engine.RetouchKind.BLUR -> "Softens where you paint (paint over again for more)."
        com.tim.quartzeditor.engine.RetouchKind.SHARPEN -> "Crisps up detail where you paint. Go easy: it builds up."
    }
    Text(help, color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    when (k) {
        com.tim.quartzeditor.engine.RetouchKind.DODGE, com.tim.quartzeditor.engine.RetouchKind.BURN -> {
            Text("Range", color = Q.textDim, fontSize = 13.sp)
            ChipRow(com.tim.quartzeditor.engine.ToneRange.entries.map { it.label }, vm.dodgeRange.ordinal) { i ->
                vm.dodgeRange = com.tim.quartzeditor.engine.ToneRange.entries[i]
                vm.prefs.edit().putInt("dodgeRange", i).apply()
            }
        }
        com.tim.quartzeditor.engine.RetouchKind.CLONE -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (vm.pickingCloneSource) "Tap the picture…" else "Pick source", primary = vm.pickingCloneSource) {
                    vm.pickingCloneSource = !vm.pickingCloneSource
                }
                if (vm.cloneSource != null) Text("Source set", color = Q.textDim, fontSize = 12.sp)
            }
            ToggleRow("Aligned (the source follows each new stroke)", vm.cloneAligned) {
                vm.cloneAligned = it
                vm.cloneOffset = null
                vm.prefs.edit().putBoolean("cloneAligned", it).apply()
            }
            SampleAllRow(vm)
        }
        com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL -> SampleAllRow(vm)
        else -> Unit
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SampleAllRow(vm: EditorViewModel) {
    ToggleRow("Look at all layers (heal on an empty layer, keeping the photo as it is)", vm.retouchAllLayers) {
        vm.retouchAllLayers = it
        vm.cloneFlat = null
        vm.prefs.edit().putBoolean("retouchAll", it).apply()
    }
}

/** The size a perspective crop comes out at: the average length of opposite sides. */
internal fun quadOutputSize(q: List<android.graphics.PointF>): Pair<Int, Int> {
    fun len(a: android.graphics.PointF, b: android.graphics.PointF) = kotlin.math.hypot(b.x - a.x, b.y - a.y)
    val w = ((len(q[0], q[1]) + len(q[3], q[2])) / 2f).roundToInt().coerceIn(1, com.tim.quartzeditor.engine.Doc.MAX_SIDE)
    val h = ((len(q[0], q[3]) + len(q[1], q[2])) / 2f).roundToInt().coerceIn(1, com.tim.quartzeditor.engine.Doc.MAX_SIDE)
    return w to h
}
