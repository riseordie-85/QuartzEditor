package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.BevelFx
import com.tim.quartzeditor.engine.BevelStyle
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.GradientFx
import com.tim.quartzeditor.engine.GradientPreset
import com.tim.quartzeditor.engine.GradientType
import com.tim.quartzeditor.engine.LongShadowFx
import com.tim.quartzeditor.engine.ShadowFx
import com.tim.quartzeditor.engine.StrokePosition
import kotlin.math.roundToInt

private enum class StyleFx(val label: String) {
    BEVEL("Bevel & Emboss"), GRADIENT("Gradient Fill"), LONG_SHADOW("Long Shadow"), STROKE("Stroke"), DROP_SHADOW("Drop Shadow"), INNER_SHADOW("Inner Shadow"), OUTER_GLOW("Outer Glow"), COLOR_OVERLAY("Color Overlay")
}

/** Layer Style: switch effects on and off and tune them. They never change the layer's pixels. */
@Composable
fun StylePanel(vm: EditorViewModel) {
    val l = vm.doc?.active ?: return
    val st = l.props.style
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Text("Effects on \"${l.props.name}\". They follow the layer and never change its pixels.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    StylePresetsRow(vm)
    Spacer(Modifier.height(6.dp))

    StyleFx.entries.forEach { kind ->
        val on = when (kind) {
            StyleFx.BEVEL -> st.bevel.on
            StyleFx.GRADIENT -> st.gradientFill.on
            StyleFx.LONG_SHADOW -> st.longShadow.on
            StyleFx.STROKE -> st.stroke.on
            StyleFx.DROP_SHADOW -> st.dropShadow.on
            StyleFx.INNER_SHADOW -> st.innerShadow.on
            StyleFx.OUTER_GLOW -> st.outerGlow.on
            StyleFx.COLOR_OVERLAY -> st.colorOverlay.on
        }
        val expanded = open == kind.name
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (expanded) Q.panelDark else Color.Transparent)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = if (expanded) null else kind.name }
                    .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Switch(
                    checked = on,
                    onCheckedChange = { v ->
                        vm.setStyle(if (v) "Add ${kind.label.lowercase()}" else "Remove ${kind.label.lowercase()}") { s -> s.toggle(kind, v) }
                        if (v) open = kind.name
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Q.accent),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(kind.label, color = if (on) Q.text else Q.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Q.textDim)
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp)) {
                    when (kind) {
                        StyleFx.BEVEL -> BevelControls(vm, st)
                        StyleFx.GRADIENT -> GradientFillControls(vm, st)
                        StyleFx.LONG_SHADOW -> {
                            val ls = st.longShadow
                            fun LayerStyle.lsh(change: LongShadowFx.() -> LongShadowFx): LayerStyle = copy(longShadow = longShadow.change().copy(on = true))
                            FxColor(vm, ls.color) { c -> vm.setStyle("Long shadow color") { it.lsh { copy(color = c) } } }
                            FxSlider(vm, "Opacity", ls.opacity, 0f..1f, "${(ls.opacity * 100).roundToInt()}%") { v -> lsh { copy(opacity = v) } }
                            FxSlider(vm, "Angle", ls.angle, -180f..180f, "${ls.angle.roundToInt()}°") { v -> lsh { copy(angle = v.roundToInt().toFloat()) } }
                            FxSlider(vm, "Length", ls.length, 1f..2000f, "${ls.length.roundToInt()} px") { v -> lsh { copy(length = v.roundToInt().toFloat()) } }
                            ToggleRow("Fade out", ls.fade) { f -> vm.setStyle("Long shadow fade") { it.lsh { copy(fade = f) } } }
                        }
                        StyleFx.STROKE -> StrokeControls(vm, st)
                        StyleFx.DROP_SHADOW -> ShadowControls(vm, st.dropShadow, inner = false)
                        StyleFx.INNER_SHADOW -> ShadowControls(vm, st.innerShadow, inner = true)
                        StyleFx.OUTER_GLOW -> {
                            val g = st.outerGlow
                            FxColor(vm, g.color) { c -> vm.setStyle("Glow color") { it.copy(outerGlow = it.outerGlow.copy(color = c, on = true)) } }
                            FxSlider(vm, "Opacity", g.opacity, 0f..1f, "${(g.opacity * 100).roundToInt()}%") { v -> copy(outerGlow = outerGlow.copy(opacity = v, on = true)) }
                            FxSlider(vm, "Size", g.size, 1f..150f, "${g.size.roundToInt()} px") { v -> copy(outerGlow = outerGlow.copy(size = v, on = true)) }
                        }
                        StyleFx.COLOR_OVERLAY -> {
                            val o = st.colorOverlay
                            FxColor(vm, o.color) { c -> vm.setStyle("Overlay color") { it.copy(colorOverlay = it.colorOverlay.copy(color = c, on = true)) } }
                            FxSlider(vm, "Opacity", o.opacity, 0f..1f, "${(o.opacity * 100).roundToInt()}%") { v -> copy(colorOverlay = colorOverlay.copy(opacity = v, on = true)) }
                        }
                    }
                }
            }
        }
    }
    if (st.any) {
        Spacer(Modifier.height(6.dp))
        SmallButton("Clear all effects") { vm.setStyle("Clear layer style") { LayerStyle() } }
    }
}

private fun LayerStyle.toggle(kind: StyleFx, on: Boolean): LayerStyle = when (kind) {
    StyleFx.BEVEL -> copy(bevel = bevel.copy(on = on))
    StyleFx.GRADIENT -> copy(gradientFill = gradientFill.copy(on = on))
    StyleFx.LONG_SHADOW -> copy(longShadow = longShadow.copy(on = on))
    StyleFx.STROKE -> copy(stroke = stroke.copy(on = on))
    StyleFx.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(on = on))
    StyleFx.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(on = on))
    StyleFx.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(on = on))
    StyleFx.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(on = on))
}

@Composable
private fun StrokeControls(vm: EditorViewModel, st: LayerStyle) {
    val s = st.stroke
    FxColor(vm, s.color) { c -> vm.setStyle("Stroke color") { it.copy(stroke = it.stroke.copy(color = c, on = true)) } }
    FxSlider(vm, "Size", s.size, 1f..100f, "${s.size.roundToInt()} px") { v -> copy(stroke = stroke.copy(size = v.roundToInt().toFloat(), on = true)) }
    FxSlider(vm, "Opacity", s.opacity, 0f..1f, "${(s.opacity * 100).roundToInt()}%") { v -> copy(stroke = stroke.copy(opacity = v, on = true)) }
    Text("Position", color = Q.textDim, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StrokePosition.entries.forEach { p ->
            SmallButton(p.label, primary = p == s.position) { vm.setStyle("Stroke position") { it.copy(stroke = it.stroke.copy(position = p, on = true)) } }
        }
    }
}

@Composable
private fun BevelControls(vm: EditorViewModel, st: LayerStyle) {
    val b = st.bevel
    fun LayerStyle.bv(change: BevelFx.() -> BevelFx): LayerStyle = copy(bevel = bevel.change().copy(on = true))
    Text("Style", color = Q.textDim, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    ChipRow(BevelStyle.entries.map { it.label }, b.style.ordinal) { i -> vm.setStyle("Bevel style") { it.bv { copy(style = BevelStyle.entries[i]) } } }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Direction", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        SmallButton("Up", primary = b.up) { vm.setStyle("Bevel direction") { it.bv { copy(up = true) } } }
        SmallButton("Down", primary = !b.up) { vm.setStyle("Bevel direction") { it.bv { copy(up = false) } } }
    }
    FxSlider(vm, "Depth", b.depth, 0.1f..5f, "${(b.depth * 100).roundToInt()}%") { v -> bv { copy(depth = v) } }
    FxSlider(vm, "Size", b.size, 1f..150f, "${b.size.roundToInt()} px") { v -> bv { copy(size = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Soften", b.soften, 0f..32f, "${b.soften.roundToInt()} px") { v -> bv { copy(soften = v.roundToInt().toFloat()) } }
    Text("Light", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    FxSlider(vm, "Angle", b.angle, -180f..180f, "${b.angle.roundToInt()}°") { v -> bv { copy(angle = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Altitude", b.altitude, 0f..90f, "${b.altitude.roundToInt()}°") { v -> bv { copy(altitude = v.roundToInt().toFloat()) } }
    Text("Highlight", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    FxColor(vm, b.highlight) { c -> vm.setStyle("Bevel highlight") { it.bv { copy(highlight = c) } } }
    FxSlider(vm, "Opacity", b.highlightOpacity, 0f..1f, "${(b.highlightOpacity * 100).roundToInt()}%") { v -> bv { copy(highlightOpacity = v) } }
    Text("Shadow", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    FxColor(vm, b.shadow) { c -> vm.setStyle("Bevel shadow") { it.bv { copy(shadow = c) } } }
    FxSlider(vm, "Opacity", b.shadowOpacity, 0f..1f, "${(b.shadowOpacity * 100).roundToInt()}%") { v -> bv { copy(shadowOpacity = v) } }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GradientFillControls(vm: EditorViewModel, st: LayerStyle) {
    val g = st.gradientFill
    fun LayerStyle.gf(change: GradientFx.() -> GradientFx): LayerStyle = copy(gradientFill = gradientFill.change().copy(on = true))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GradientPreset.ALL.forEach { p ->
            val cols = p.resolve(vm.foreground, vm.background)
            val sel = cols == g.colors
            Box(
                Modifier
                    .size(width = 52.dp, height = 26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .checker(8f)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(cols.map { Color(it) }.let { if (it.size >= 2) it else listOf(Color.Black, Color.White) }))
                    .border(if (sel) 2.dp else 1.dp, if (sel) Q.accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .clickable { vm.setStyle("Gradient fill") { it.gf { copy(colors = cols) } } }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    ChipRow(GradientType.entries.map { it.label }, g.type.ordinal) { i -> vm.setStyle("Gradient fill type") { it.gf { copy(type = GradientType.entries[i]) } } }
    Spacer(Modifier.height(4.dp))
    FxSlider(vm, "Opacity", g.opacity, 0f..1f, "${(g.opacity * 100).roundToInt()}%") { v -> gf { copy(opacity = v) } }
    FxSlider(vm, "Angle", g.angle, -180f..180f, "${g.angle.roundToInt()}°") { v -> gf { copy(angle = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Scale", g.scale, 0.1f..2f, "${(g.scale * 100).roundToInt()}%") { v -> gf { copy(scale = v) } }
    ToggleRow("Reverse", g.reverse) { r -> vm.setStyle("Gradient fill reverse") { it.gf { copy(reverse = r) } } }
}

@Composable
private fun ShadowControls(vm: EditorViewModel, sh: ShadowFx, inner: Boolean) {
    fun LayerStyle.set(change: ShadowFx.() -> ShadowFx): LayerStyle =
        if (inner) copy(innerShadow = innerShadow.change().copy(on = true)) else copy(dropShadow = dropShadow.change().copy(on = true))
    FxColor(vm, sh.color) { c -> vm.setStyle("Shadow color") { it.set { copy(color = c) } } }
    FxSlider(vm, "Opacity", sh.opacity, 0f..1f, "${(sh.opacity * 100).roundToInt()}%") { v -> set { copy(opacity = v) } }
    FxSlider(vm, "Angle", sh.angle, -180f..180f, "${sh.angle.roundToInt()}°") { v -> set { copy(angle = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Distance", sh.distance, 0f..200f, "${sh.distance.roundToInt()} px") { v -> set { copy(distance = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Size", sh.size, 0f..150f, "${sh.size.roundToInt()} px") { v -> set { copy(size = v.roundToInt().toFloat()) } }
}

/** A slider that changes the style live and records one undo step when you let go. */
@Composable
private fun FxSlider(
    vm: EditorViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    change: LayerStyle.(Float) -> LayerStyle,
) {
    PanelSlider(
        label = label,
        value = value.coerceIn(range.start, range.endInclusive),
        range = range,
        display = display,
        onChange = { v -> vm.setStyleLive { it.change(v) } },
        onDone = { vm.commitProps("Layer style") },
    )
}

/** Swatches: your foreground and background colors, recent colors, then the basics. */
@Composable
private fun FxColor(vm: EditorViewModel, current: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Color", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(current))
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Use foreground",
            color = Q.text,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Q.field)
                .clickable { onPick(vm.foreground) }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    val colors = (listOf(vm.foreground, vm.background) + vm.recentColors + BASIC_COLORS).distinct().take(18)
    ColorDots(colors, current, onPick)
    Spacer(Modifier.height(4.dp))
}
