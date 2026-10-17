package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.BlackWhite
import com.tim.quartzeditor.engine.ColorBalance
import com.tim.quartzeditor.engine.ColorShift
import com.tim.quartzeditor.engine.FxKind
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.GradientMap
import com.tim.quartzeditor.engine.GradientPreset
import com.tim.quartzeditor.engine.HueBand
import com.tim.quartzeditor.engine.HueSat
import com.tim.quartzeditor.engine.HueShift
import com.tim.quartzeditor.engine.PhotoFilter
import com.tim.quartzeditor.engine.ToneRange
import kotlin.math.roundToInt

// The Photoshop-style color windows: Hue/Saturation, Color Balance, Vibrance, Black & White,
// Photo Filter and Gradient Map. Like the others, they preview live on a pixel layer (Apply /
// Cancel) or change an adjustment layer directly.

/** On a pixel layer the change is about [k] (for its undo name); an adjustment layer keeps its own kind. */
private fun EditorViewModel.kindOf(fx: FxParams, k: FxKind): FxKind = if (fxTarget != null) fx.kind else k

@Composable
private fun Applies(vm: EditorViewModel) {
    if (!com.tim.quartzeditor.engine.ColorShader.colorSupported) {
        Text("This device couldn't run this adjustment, so it won't change anything.", color = Color(0xFFE57373), fontSize = 12.sp)
    }
    val layer = vm.doc?.active
    Text(
        if (vm.fxTarget != null) "Adjustment layer \"${layer?.props?.name}\": changes everything below it" else "Applies to: ${layer?.props?.name ?: "—"}",
        color = Q.textDim, fontSize = 12.sp, maxLines = 2,
    )
    Spacer(Modifier.height(6.dp))
}

private fun signed(v: Float, scale: Float = 100f): String = (if (v > 0.005f) "+" else "") + "${(v * scale).roundToInt()}"

// ===================================================================== Hue/Saturation

@Composable
internal fun HueSatPanel(vm: EditorViewModel) {
    Applies(vm)
    val fx = vm.currentFx()
    val hs = fx.hueSat ?: HueSat()
    var band by remember { mutableStateOf(HueBand.MASTER) }
    fun edit(n: HueSat) = vm.editFx(fx.copy(hueSat = n, kind = vm.kindOf(fx, FxKind.HUE_SAT)))
    ToggleRow("Colorize", hs.colorize) { on ->
        edit(hs.copy(colorize = on))
        vm.editFxDone()
    }
    if (hs.colorize) {
        PanelSlider("Hue", hs.colorHue, 0f..360f, "${hs.colorHue.roundToInt()}°", onChange = { edit(hs.copy(colorHue = it.roundToInt().toFloat())) }, onDone = { vm.editFxDone() })
        HueBar()
        PanelSlider("Saturation", hs.colorSat, 0f..1f, "${(hs.colorSat * 100).roundToInt()}", onChange = { edit(hs.copy(colorSat = it)) }, onDone = { vm.editFxDone() }, fromTyped = { it / 100f })
        val m = hs.master
        PanelSlider("Lightness", m.light, -1f..1f, signed(m.light), onChange = { edit(hs.copy(master = m.copy(light = it))) }, onDone = { vm.editFxDone() }, fromTyped = { it / 100f })
    } else {
        // Which colors: all (Master) or one range
        BandChips(band) { band = it }
        Spacer(Modifier.height(6.dp))
        val s = hs[band]
        PanelSlider("Hue", s.hue, -1f..1f, signed(s.hue, 180f) + "°", onChange = { edit(hs.with(band, s.copy(hue = it))) }, onDone = { vm.editFxDone() }, fromTyped = { it / 180f })
        PanelSlider("Saturation", s.sat, -1f..1f, signed(s.sat), onChange = { edit(hs.with(band, s.copy(sat = it))) }, onDone = { vm.editFxDone() }, fromTyped = { it / 100f })
        PanelSlider("Lightness", s.light, -1f..1f, signed(s.light), onChange = { edit(hs.with(band, s.copy(light = it))) }, onDone = { vm.editFxDone() }, fromTyped = { it / 100f })
        if (band != HueBand.MASTER) {
            Text("Changes only the ${band.label.lowercase()} (fading into the colors next to them).", color = Q.textDim, fontSize = 12.sp)
        }
        if (!hs[band].isIdentity) {
            Spacer(Modifier.height(4.dp))
            SmallButton("Reset ${band.label}") {
                edit(hs.with(band, HueShift()))
                vm.editFxDone()
            }
        }
    }
    FxButtons(vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandChips(current: HueBand, onPick: (HueBand) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HueBand.entries.forEach { b ->
            val sel = b == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Q.accent else Q.field)
                    .clickable { onPick(b) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                if (b != HueBand.MASTER) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(b.color)))
                    Spacer(Modifier.width(5.dp))
                }
                Text(b.label, color = if (sel) Color.White else Q.text, fontSize = 13.sp)
            }
        }
    }
}

/** The hues from 0° to 360°, under the Hue slider. */
@Composable
private fun HueBar() {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
    )
    Spacer(Modifier.height(6.dp))
}

// ===================================================================== Color Balance

@Composable
internal fun ColorBalancePanel(vm: EditorViewModel) {
    Applies(vm)
    val fx = vm.currentFx()
    val cb = fx.colorBalance ?: ColorBalance()
    var range by remember { mutableStateOf(ToneRange.MIDTONES) }
    fun edit(n: ColorBalance) = vm.editFx(fx.copy(colorBalance = n, kind = vm.kindOf(fx, FxKind.COLOR_BALANCE)))
    ChipRow(ToneRange.entries.map { it.label }, range.ordinal) { range = ToneRange.entries[it] }
    Spacer(Modifier.height(6.dp))
    val s = cb[range]
    BalanceSlider(vm, "Cyan", "Red", Color(0xFF00BCD4), Color(0xFFE53935), s.cyanRed) { edit(cb.with(range, s.copy(cyanRed = it))) }
    BalanceSlider(vm, "Magenta", "Green", Color(0xFFD81B60), Color(0xFF43A047), s.magentaGreen) { edit(cb.with(range, s.copy(magentaGreen = it))) }
    BalanceSlider(vm, "Yellow", "Blue", Color(0xFFFDD835), Color(0xFF1E88E5), s.yellowBlue) { edit(cb.with(range, s.copy(yellowBlue = it))) }
    ToggleRow("Preserve luminosity", cb.preserveLuminosity) {
        edit(cb.copy(preserveLuminosity = it))
        vm.editFxDone()
    }
    if (!s.isIdentity) {
        SmallButton("Reset ${range.label.lowercase()}") {
            edit(cb.with(range, ColorShift()))
            vm.editFxDone()
        }
    }
    FxButtons(vm)
}

@Composable
private fun BalanceSlider(vm: EditorViewModel, left: String, right: String, lc: Color, rc: Color, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = lc, fontSize = 12.sp, modifier = Modifier.width(58.dp))
        Box(Modifier.weight(1f)) {
            PanelSlider("", value, -1f..1f, signed(value), onChange = onChange, onDone = { vm.editFxDone() }, fromTyped = { it / 100f })
        }
        Text(right, color = rc, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp).width(44.dp))
    }
}

// ===================================================================== Vibrance

@Composable
internal fun VibrancePanel(vm: EditorViewModel) {
    Applies(vm)
    val fx = vm.currentFx()
    for (a in listOf(com.tim.quartzeditor.engine.Adjust.VIBRANCE, com.tim.quartzeditor.engine.Adjust.SATURATION)) {
        val v = fx.adjust(a)
        PanelSlider(
            a.label, v, -1f..1f, signed(v),
            onChange = { nv -> vm.editFx(fx.copy(adjust = fx.adjust + (a to nv), kind = vm.kindOf(fx, FxKind.VIBRANCE))) },
            onDone = { vm.editFxDone() },
            fromTyped = { it / 100f },
        )
    }
    Text("Vibrance boosts the dull colors more than the strong ones, and goes easy on skin tones.", color = Q.textDim, fontSize = 12.sp)
    FxButtons(vm)
}

// ===================================================================== Black & White

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlackWhitePanel(vm: EditorViewModel) {
    Applies(vm)
    val fx = vm.currentFx()
    val bw = fx.blackWhite ?: BlackWhite()
    fun edit(n: BlackWhite) = vm.editFx(fx.copy(blackWhite = n, kind = vm.kindOf(fx, FxKind.BLACK_WHITE)))
    // A pixel layer opening this turns gray right away (like Photoshop)
    androidx.compose.runtime.LaunchedEffect(Unit) { if (fx.blackWhite == null) edit(bw) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BlackWhite.PRESETS.forEach { (name, w) ->
            SmallButton(name, primary = bw.weights == w) {
                edit(bw.copy(weights = w))
                vm.editFxDone()
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    val swatch = listOf(0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFFD81B60)
    BlackWhite.NAMES.forEachIndexed { i, name ->
        val v = bw.weights[i]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(swatch[i])))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                PanelSlider(
                    name, v, -2f..3f, "${(v * 100).roundToInt()}%",
                    onChange = { nv -> edit(bw.copy(weights = bw.weights.mapIndexed { j, x -> if (j == i) nv else x })) },
                    onDone = { vm.editFxDone() },
                    fromTyped = { it / 100f },
                )
            }
        }
    }
    ToggleRow("Tint", bw.tint) {
        edit(bw.copy(tint = it))
        vm.editFxDone()
    }
    if (bw.tint) {
        FxColor(vm, bw.tintColor) { c ->
            edit(bw.copy(tintColor = c or 0xFF000000.toInt()))
            vm.editFxDone()
        }
    }
    FxButtons(vm)
}

// ===================================================================== Photo Filter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoFilterPanel(vm: EditorViewModel) {
    Applies(vm)
    val fx = vm.currentFx()
    val pf = fx.photoFilter ?: PhotoFilter()
    fun edit(n: PhotoFilter) = vm.editFx(fx.copy(photoFilter = n, kind = vm.kindOf(fx, FxKind.PHOTO_FILTER)))
    androidx.compose.runtime.LaunchedEffect(Unit) { if (fx.photoFilter == null) edit(pf) }
    Text("Filter", color = Q.textDim, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PhotoFilter.PRESETS.forEach { (name, c) ->
            val sel = pf.color == c
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Q.accent else Q.field)
                    .clickable {
                        edit(pf.copy(color = c))
                        vm.editFxDone()
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c)).border(1.dp, Q.text.copy(alpha = 0.3f), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(name, color = if (sel) Color.White else Q.text, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Or your own color", color = Q.textDim, fontSize = 13.sp)
    FxColor(vm, pf.color) { c ->
        edit(pf.copy(color = c or 0xFF000000.toInt()))
        vm.editFxDone()
    }
    PanelSlider("Density", pf.density, 0f..1f, "${(pf.density * 100).roundToInt()}%", onChange = { edit(pf.copy(density = it)) }, onDone = { vm.editFxDone() })
    ToggleRow("Preserve luminosity", pf.preserveLuminosity) {
        edit(pf.copy(preserveLuminosity = it))
        vm.editFxDone()
    }
    FxButtons(vm)
}

// ===================================================================== Gradient Map

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GradientMapPanel(vm: EditorViewModel) {
    Applies(vm)
    val fx = vm.currentFx()
    val gm = fx.gradientMap ?: GradientMap(listOf(vm.foreground or 0xFF000000.toInt(), vm.background or 0xFF000000.toInt()))
    fun edit(n: GradientMap) = vm.editFx(fx.copy(gradientMap = n, kind = vm.kindOf(fx, FxKind.GRADIENT_MAP)))
    androidx.compose.runtime.LaunchedEffect(Unit) { if (fx.gradientMap == null) edit(gm) }
    var editor by remember { mutableStateOf<Pair<GradientPreset, Int?>?>(null) }
    Text("Dark parts take the left color, light parts the right.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.allGradients.forEachIndexed { i, p ->
            val mine = (i - GradientPreset.ALL.size).takeIf { it >= 0 }
            val cols = p.resolve(vm.foreground, vm.background)
            val sel = cols == gm.colors && (p.positions ?: emptyList<Float>()) == (gm.positions ?: emptyList<Float>())
            GradientTile(vm, p, sel, 52.dp, 26.dp, onPick = {
                edit(gm.copy(colors = cols, positions = p.positions))
                vm.editFxDone()
            }, userIndex = mine, onEdit = { n -> editor = vm.userGradients[n] to n })
        }
        NewGradientTile(52.dp, 26.dp) { editor = GradientPreset("Gradient", gm.colors, positions = gm.positions) to null }
    }
    editor?.let { (start, editing) ->
        GradientEditorDialog(vm, start, editing, onDone = { editor = null }, pickForTool = false, onSaved = { saved ->
            edit(gm.copy(colors = saved.colors, positions = saved.positions))
            vm.editFxDone()
        })
    }
    Spacer(Modifier.height(6.dp))
    ToggleRow("Reverse", gm.reverse) {
        edit(gm.copy(reverse = it))
        vm.editFxDone()
    }
    FxButtons(vm)
}
