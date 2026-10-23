package com.tim.quartzeditor.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.quartzeditor.engine.Fonts
import com.tim.quartzeditor.engine.GradientPreset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// App settings: how the app looks (theme, accent color, see-through panels), how a pen works, and
// the things you've made or added (patterns, gradients, layer style presets, fonts) to tidy up.

private enum class SettingsTab(val label: String) { APPEARANCE("Appearance"), PEN("Pen and drawing"), LIBRARY("Your library") }

@Composable
fun SettingsScreen(vm: EditorViewModel, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(SettingsTab.APPEARANCE) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Q.workspace).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", color = Q.text, fontSize = 24.sp, modifier = Modifier.weight(1f))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Close settings", tint = Q.text) }
                }
                Spacer(Modifier.height(10.dp))
                ChipRow(SettingsTab.entries.map { it.label }, tab.ordinal) { tab = SettingsTab.entries[it] }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Q.palette.panel)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    when (tab) {
                        SettingsTab.APPEARANCE -> AppearanceSettings(vm)
                        SettingsTab.PEN -> PenSettings(vm)
                        SettingsTab.LIBRARY -> LibrarySettings(vm)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettings(vm: EditorViewModel) {
    Heading("Theme")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Palette.entries.forEach { p ->
            val sel = p == Q.palette
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(if (sel) 2.dp else 1.dp, if (sel) Q.accent else Q.divider, RoundedCornerShape(12.dp))
                    .clickable {
                        Q.palette = p
                        Q.save(vm.prefs)
                    }
                    .padding(8.dp),
            ) {
                // A tiny picture of the workspace in that theme
                Box(Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)).background(p.workspace)) {
                    Box(Modifier.padding(4.dp).size(width = 14.dp, height = 52.dp).clip(RoundedCornerShape(4.dp)).background(p.toolbar))
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(width = 30.dp, height = 52.dp).clip(RoundedCornerShape(4.dp)).background(p.panel)) {
                        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(4) { Box(Modifier.fillMaxWidth().height(3.dp).background(p.text.copy(alpha = 0.6f))) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(p.label, color = Q.text, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Heading("Accent color")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ACCENTS.forEach { c ->
            val sel = c == Q.accentColor
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(if (sel) 3.dp else 0.dp, if (sel) Q.text else Color.Transparent, CircleShape)
                    .clickable {
                        Q.accentColor = c
                        Q.save(vm.prefs)
                    }
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    Heading("Layout")
    ChipRow(UiLayout.entries.map { it.label }, vm.uiLayout.ordinal) { vm.chooseLayout(UiLayout.entries[it]) }
    Text(
        "Menu bar: a menu bar at the top (File, Edit, Image, Layer, Select, Filter, View, Window), the tool's settings in a bar under it, " +
            "slim size and opacity sliders by the tools, resizable panels and a status bar. Classic: the ☰ menu and the brush column. " +
            "Automatic: the menu bar on big screens like tablets, classic on phones.",
        color = Q.textDim, fontSize = 12.sp,
    )
    Spacer(Modifier.height(18.dp))
    Heading("Panels")
    PanelSlider(
        "How solid the toolbar, panels and windows are", Q.panelAlpha, 0.4f..1f, "${(Q.panelAlpha * 100).roundToInt()}%",
        onChange = { Q.panelAlpha = it },
        onDone = { Q.save(vm.prefs) },
    )
    Text("Lower lets the picture show through behind them.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    ToggleRow("Compact interface", Q.compact) {
        Q.compact = it
        Q.save(vm.prefs)
    }
    Text("Smaller margins and padding around the toolbar, panels, buttons and layer rows, so the picture gets more room.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    SmallButton("Back to the original look") {
        Q.palette = Palette.DARK
        Q.accentColor = ACCENTS[0]
        Q.panelAlpha = 1f
        Q.compact = false
        Q.save(vm.prefs)
    }
}

/** How a pen (stylus) works: pressure, tilt, its eraser end and button, and what fingers do. */
@Composable
private fun PenSettings(vm: EditorViewModel) {
    if (!vm.penSeen) {
        Text("These take effect once you use a pen on the canvas.", color = Q.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
    }
    Heading("Fingers")
    ToggleRow("Fingers move the view", vm.penFingersNavigate) {
        vm.penFingersNavigate = it
        vm.savePenPrefs()
    }
    Text(
        "With the brush, eraser and retouch brushes, the pen paints and one finger moves the view (two pinch and turn). " +
            "Off: fingers paint too. Either way, touches are ignored while the pen is near the screen, so your hand can rest on it.",
        color = Q.textDim, fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    ToggleRow("Two fingers turn the canvas", !vm.rotationLocked) { vm.toggleRotationLock() }
    Text("Twist to turn it (it snaps straight near 0°, 90°, 180°); a quick pinch in fits it on the screen, straight.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(18.dp))
    Heading("Pressure")
    PressureCurveEditor(vm)
    Text(
        "Across: how hard you press. Up: how much it does. Drag the two handles to shape it: above the dotted line, a gentle touch does more; below, you press harder for the same.",
        color = Q.textDim, fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    PanelSlider(
        "Smallest size at the lightest touch", vm.penMinSize, 0.01f..1f, "${(vm.penMinSize * 100).roundToInt()}%",
        onChange = { vm.penMinSize = it },
        onDone = { vm.savePenPrefs() },
    )
    Text(
        "For brushes with pressure on size. Each brush has its own switches for size and opacity (strength for the retouch brushes), under its sliders.",
        color = Q.textDim, fontSize = 12.sp,
    )
    Spacer(Modifier.height(18.dp))
    Heading("Draw and hold (QuickShape)")
    ToggleRow("Hold at the end of a stroke to make it a shape", vm.quickShapeOn) {
        vm.quickShapeOn = it
        vm.prefs.edit().putBoolean("quickShape", it).apply()
    }
    Text(
        "Draw a line, an ellipse, a rectangle or a triangle, and keep the pen (or finger) still at the end: it snaps clean. " +
            "Keep holding and move to size and turn it. Shift, the Modifier button or a finger on the screen makes it perfect: a circle, a square, a line at 45°.",
        color = Q.textDim, fontSize = 12.sp,
    )
    if (vm.quickShapeOn) {
        Spacer(Modifier.height(6.dp))
        PanelSlider(
            "How long to hold", vm.quickShapeDelay.toFloat(), 250f..1500f, "${"%.2f".format(vm.quickShapeDelay / 1000f)} s",
            onChange = { vm.quickShapeDelay = (it / 50f).roundToInt() * 50 },
            onDone = { vm.prefs.edit().putInt("quickShapeDelay", vm.quickShapeDelay).apply() },
        )
    }
    Spacer(Modifier.height(18.dp))
    Heading("Tilt")
    ToggleRow("Tilting the pen stretches the brush", vm.penTilt) {
        vm.penTilt = it
        vm.savePenPrefs()
    }
    Text("Lean the pen over to shade with the side of the brush, like a pencil. (Pens that don't sense tilt paint as usual.)", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(18.dp))
    Heading("Eraser end and button")
    ToggleRow("The eraser end erases", vm.penEraserEnd) {
        vm.penEraserEnd = it
        vm.savePenPrefs()
    }
    Text("With any tool, using the eraser's own size and settings.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    Text("Side button", color = Q.text, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    ChipRow(PenButton.entries.map { it.label }, vm.penButton.ordinal) {
        vm.penButton = PenButton.entries[it]
        vm.savePenPrefs()
    }
    Text(
        when (vm.penButton) {
            PenButton.EYEDROPPER -> "Hold it and touch the picture to pick a color."
            PenButton.ERASER -> "Hold it while you draw to erase."
            PenButton.UNDO -> "Press it with the pen off the screen to undo."
            PenButton.NOTHING -> "The button does nothing here."
        },
        color = Q.textDim, fontSize = 12.sp,
    )
    Spacer(Modifier.height(18.dp))
    Heading("Hover")
    ToggleRow("Show the brush while the pen hovers", vm.penHover) {
        vm.penHover = it
        vm.savePenPrefs()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibrarySettings(vm: EditorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ------------------------------------------------------------ patterns
    Heading("Patterns")
    Text("Hold one of yours to delete it; + adds one from your photos. The ready-made ones stay.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    PatternGrid(vm, selected = vm.patternKey, colorA = vm.foreground or 0xFF000000.toInt(), colorB = vm.background or 0xFF000000.toInt()) { vm.choosePattern(it) }
    Divider()

    // ------------------------------------------------------------ gradients
    Heading("Gradients")
    Text("Hold one of yours to edit or delete it; + makes a new one.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    var editor by remember { mutableStateOf<Pair<GradientPreset, Int?>?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.allGradients.forEachIndexed { i, p ->
            val mine = (i - GradientPreset.ALL.size).takeIf { it >= 0 }
            GradientTile(vm, p, i == vm.gradientPreset, 62.dp, 30.dp, onPick = {
                vm.gradientPreset = i
                vm.saveGradientPrefs()
            }, userIndex = mine, onEdit = { n -> editor = vm.userGradients[n] to n })
        }
        NewGradientTile(62.dp, 30.dp) { editor = vm.allGradients.getOrElse(vm.gradientPreset) { GradientPreset.ALL[0] } to null }
    }
    editor?.let { (start, editing) -> GradientEditorDialog(vm, start, editing, onDone = { editor = null }, pickForTool = true) }
    Divider()

    // ------------------------------------------------------------ layer style presets
    Heading("Layer style presets")
    val mine = vm.stylePresets.filter { !it.builtIn }
    if (mine.isEmpty()) {
        Text("None of your own yet. In Layer Style, \"+ Save\" keeps the style you've set up.", color = Q.textDim, fontSize = 12.sp)
    } else {
        mine.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(p.name, color = Q.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                IconAction(Icons.Outlined.DeleteOutline, "Delete ${p.name}") { vm.deleteStylePreset(p) }
            }
        }
    }
    Divider()

    // ------------------------------------------------------------ fonts
    Heading("Imported fonts")
    val imported by Fonts.imported.collectAsState()
    var importing by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val key = Fonts.import(context, uri)
            importing = false
            if (key == null) Toast.makeText(context, "That file isn't a font Android can use (.ttf or .otf).", Toast.LENGTH_LONG).show()
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { Fonts.refreshImported(context) }
    if (imported.isEmpty()) {
        Text("No fonts imported yet.", color = Q.textDim, fontSize = 12.sp)
    } else {
        imported.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                val tf = Fonts.cached(f.key)
                Text(
                    f.label, color = Q.text, fontSize = 16.sp, modifier = Modifier.weight(1f),
                    fontFamily = tf?.let { FontFamily(it) },
                )
                IconAction(Icons.Outlined.DeleteOutline, "Delete ${f.label}") { Fonts.deleteImported(context, f.key) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SmallButton(if (importing) "Importing…" else "Import font…") {
        importer.launch(
            arrayOf(
                "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
                "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
            )
        )
    }
    Text("Text using a deleted font shows in a stand-in font until you import it again.", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Q.text, fontSize = 17.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = Q.divider)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
    ) { Icon(icon, contentDescription = label, tint = Q.textDim) }
}
