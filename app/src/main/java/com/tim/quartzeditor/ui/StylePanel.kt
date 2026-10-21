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
import androidx.compose.runtime.remember
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
    // Photoshop's order, with Long Shadow at the end
    BEVEL("Bevel & Emboss"), STROKE("Stroke"), INNER_SHADOW("Inner Shadow"), INNER_GLOW("Inner Glow"), SATIN("Satin"),
    COLOR_OVERLAY("Color Overlay"), GRADIENT("Gradient Overlay"), PATTERN("Pattern Overlay"), OUTER_GLOW("Outer Glow"),
    DROP_SHADOW("Drop Shadow"), LONG_SHADOW("Long Shadow")
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

    // Blending Options (Blend If), above the effects like Photoshop's
    run {
        val expanded = open == "BLENDING"
        val active = l.props.blendIf.active
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
                    .clickable { open = if (expanded) null else "BLENDING" }
                    .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text("Blending Options", color = if (active) Q.text else Q.textDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (active) Text("Blend If", color = Q.accent, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Q.textDim)
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp)) { BlendIfControls(vm, l) }
            }
        }
    }

    StyleFx.entries.forEach { kind ->
        val on = when (kind) {
            StyleFx.BEVEL -> st.bevel.on
            StyleFx.GRADIENT -> st.gradientFill.on
            StyleFx.PATTERN -> st.patternOverlay.on
            StyleFx.LONG_SHADOW -> st.longShadow.on
            StyleFx.STROKE -> st.stroke.on
            StyleFx.DROP_SHADOW -> st.dropShadow.on
            StyleFx.INNER_SHADOW -> st.innerShadow.on
            StyleFx.INNER_GLOW -> st.innerGlow.on
            StyleFx.SATIN -> st.satin.on
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
                        StyleFx.PATTERN -> PatternOverlayControls(vm, st)
                        StyleFx.LONG_SHADOW -> {
                            val ls = st.longShadow
                            fun LayerStyle.lsh(change: LongShadowFx.() -> LongShadowFx): LayerStyle = copy(longShadow = longShadow.change().copy(on = true))
                            StyleColor(vm, ls.color, "Long shadow color") { c -> lsh { copy(color = c) } }
                            FxSlider(vm, "Opacity", ls.opacity, 0f..1f, "${(ls.opacity * 100).roundToInt()}%") { v -> lsh { copy(opacity = v) } }
                            FxSlider(vm, "Angle", ls.angle, -180f..180f, "${ls.angle.roundToInt()}°") { v -> lsh { copy(angle = v.roundToInt().toFloat()) } }
                            FxSlider(vm, "Length", ls.length, 1f..2000f, "${ls.length.roundToInt()} px") { v -> lsh { copy(length = v.roundToInt().toFloat()) } }
                            ToggleRow("Fade out", ls.fade) { f -> vm.setStyle("Long shadow fade") { it.lsh { copy(fade = f) } } }
                            FxBlend(ls.blend) { b -> vm.setStyle("Long shadow blend") { it.lsh { copy(blend = b) } } }
                        }
                        StyleFx.STROKE -> StrokeControls(vm, st)
                        StyleFx.DROP_SHADOW -> ShadowControls(vm, st.dropShadow, inner = false)
                        StyleFx.INNER_SHADOW -> ShadowControls(vm, st.innerShadow, inner = true)
                        StyleFx.INNER_GLOW -> {
                            val g = st.innerGlow
                            fun LayerStyle.ig(change: com.tim.quartzeditor.engine.InnerGlowFx.() -> com.tim.quartzeditor.engine.InnerGlowFx): LayerStyle = copy(innerGlow = innerGlow.change().copy(on = true))
                            StyleColor(vm, g.color, "Inner glow color") { c -> ig { copy(color = c) } }
                            FxSlider(vm, "Opacity", g.opacity, 0f..1f, "${(g.opacity * 100).roundToInt()}%") { v -> ig { copy(opacity = v) } }
                            FxSlider(vm, "Size", g.size, 1f..150f, "${g.size.roundToInt()} px") { v -> ig { copy(size = v.roundToInt().toFloat()) } }
                            FxSlider(vm, "Choke", g.choke, 0f..1f, "${(g.choke * 100).roundToInt()}%") { v -> ig { copy(choke = v) } }
                            Text("Source", color = Q.textDim, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            ChipRow(listOf("Edge", "Center"), if (g.center) 1 else 0) { i -> vm.setStyle("Inner glow source") { it.ig { copy(center = i == 1) } } }
                            FxBlend(g.blend) { b -> vm.setStyle("Inner glow blend") { it.ig { copy(blend = b) } } }
                        }
                        StyleFx.SATIN -> {
                            val sa = st.satin
                            fun LayerStyle.sat(change: com.tim.quartzeditor.engine.SatinFx.() -> com.tim.quartzeditor.engine.SatinFx): LayerStyle = copy(satin = satin.change().copy(on = true))
                            StyleColor(vm, sa.color, "Satin color") { c -> sat { copy(color = c) } }
                            FxSlider(vm, "Opacity", sa.opacity, 0f..1f, "${(sa.opacity * 100).roundToInt()}%") { v -> sat { copy(opacity = v) } }
                            FxSlider(vm, "Angle", sa.angle, -180f..180f, "${sa.angle.roundToInt()}°") { v -> sat { copy(angle = v.roundToInt().toFloat()) } }
                            FxSlider(vm, "Distance", sa.distance, 1f..250f, "${sa.distance.roundToInt()} px") { v -> sat { copy(distance = v.roundToInt().toFloat()) } }
                            FxSlider(vm, "Size", sa.size, 0f..250f, "${sa.size.roundToInt()} px") { v -> sat { copy(size = v.roundToInt().toFloat()) } }
                            ToggleRow("Invert", sa.invert) { f -> vm.setStyle("Satin invert") { it.sat { copy(invert = f) } } }
                            FxBlend(sa.blend) { b -> vm.setStyle("Satin blend") { it.sat { copy(blend = b) } } }
                        }
                        StyleFx.OUTER_GLOW -> {
                            val g = st.outerGlow
                            StyleColor(vm, g.color, "Glow color") { c -> copy(outerGlow = outerGlow.copy(color = c, on = true)) }
                            FxSlider(vm, "Opacity", g.opacity, 0f..1f, "${(g.opacity * 100).roundToInt()}%") { v -> copy(outerGlow = outerGlow.copy(opacity = v, on = true)) }
                            FxSlider(vm, "Size", g.size, 1f..150f, "${g.size.roundToInt()} px") { v -> copy(outerGlow = outerGlow.copy(size = v, on = true)) }
                            FxBlend(g.blend) { b -> vm.setStyle("Glow blend") { it.copy(outerGlow = it.outerGlow.copy(blend = b, on = true)) } }
                        }
                        StyleFx.COLOR_OVERLAY -> {
                            val o = st.colorOverlay
                            StyleColor(vm, o.color, "Overlay color") { c -> copy(colorOverlay = colorOverlay.copy(color = c, on = true)) }
                            FxSlider(vm, "Opacity", o.opacity, 0f..1f, "${(o.opacity * 100).roundToInt()}%") { v -> copy(colorOverlay = colorOverlay.copy(opacity = v, on = true)) }
                            FxBlend(o.blend) { b -> vm.setStyle("Overlay blend") { it.copy(colorOverlay = it.colorOverlay.copy(blend = b, on = true)) } }
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
    StyleFx.PATTERN -> copy(patternOverlay = patternOverlay.copy(on = on))
    StyleFx.LONG_SHADOW -> copy(longShadow = longShadow.copy(on = on))
    StyleFx.STROKE -> copy(stroke = stroke.copy(on = on))
    StyleFx.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(on = on))
    StyleFx.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(on = on))
    StyleFx.INNER_GLOW -> copy(innerGlow = innerGlow.copy(on = on))
    StyleFx.SATIN -> copy(satin = satin.copy(on = on))
    StyleFx.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(on = on))
    StyleFx.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(on = on))
}

@Composable
private fun StrokeControls(vm: EditorViewModel, st: LayerStyle) {
    val s = st.stroke
    ChipRow(listOf("Color", "Gradient"), if (s.useGradient) 1 else 0) { i -> vm.setStyle("Stroke fill") { it.copy(stroke = it.stroke.copy(useGradient = i == 1, on = true)) } }
    Spacer(Modifier.height(6.dp))
    if (s.useGradient) {
        GradientFxControls(vm, s.gradient, "Stroke gradient", showOpacity = false) { change -> copy(stroke = stroke.copy(gradient = stroke.gradient.change(), on = true)) }
    } else {
        StyleColor(vm, s.color, "Stroke color") { c -> copy(stroke = stroke.copy(color = c, on = true)) }
    }
    FxSlider(vm, "Size", s.size, 1f..100f, "${s.size.roundToInt()} px") { v -> copy(stroke = stroke.copy(size = v.roundToInt().toFloat(), on = true)) }
    FxSlider(vm, "Opacity", s.opacity, 0f..1f, "${(s.opacity * 100).roundToInt()}%") { v -> copy(stroke = stroke.copy(opacity = v, on = true)) }
    FxBlend(s.blend) { b -> vm.setStyle("Stroke blend") { it.copy(stroke = it.stroke.copy(blend = b, on = true)) } }
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
    Text("Gloss contour", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(4.dp))
    GlossPicker(b.gloss) { gc -> vm.setStyle("Gloss contour") { it.bv { copy(gloss = gc) } } }
    Text("Light", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    FxSlider(vm, "Angle", b.angle, -180f..180f, "${b.angle.roundToInt()}°") { v -> bv { copy(angle = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Altitude", b.altitude, 0f..90f, "${b.altitude.roundToInt()}°") { v -> bv { copy(altitude = v.roundToInt().toFloat()) } }
    Text("Highlight", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    StyleColor(vm, b.highlight, "Bevel highlight") { c -> bv { copy(highlight = c) } }
    FxSlider(vm, "Opacity", b.highlightOpacity, 0f..1f, "${(b.highlightOpacity * 100).roundToInt()}%") { v -> bv { copy(highlightOpacity = v) } }
    FxBlend(b.highlightBlend) { m -> vm.setStyle("Highlight blend") { it.bv { copy(highlightBlend = m) } } }
    Text("Shadow", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    StyleColor(vm, b.shadow, "Bevel shadow") { c -> bv { copy(shadow = c) } }
    FxSlider(vm, "Opacity", b.shadowOpacity, 0f..1f, "${(b.shadowOpacity * 100).roundToInt()}%") { v -> bv { copy(shadowOpacity = v) } }
    FxBlend(b.shadowBlend) { m -> vm.setStyle("Shadow blend") { it.bv { copy(shadowBlend = m) } } }
}

@Composable
private fun GradientFillControls(vm: EditorViewModel, st: LayerStyle) {
    GradientFxControls(vm, st.gradientFill, "Gradient overlay", showOpacity = true) { change -> copy(gradientFill = gradientFill.change().copy(on = true)) }
    FxBlend(st.gradientFill.blend) { b -> vm.setStyle("Gradient overlay blend") { it.copy(gradientFill = it.gradientFill.copy(blend = b, on = true)) } }
}

/**
 * Picking and setting up a gradient for a layer style (Gradient Fill, or a stroke's gradient).
 * [update] applies a change to that gradient within the style.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GradientFxControls(
    vm: EditorViewModel,
    g: GradientFx,
    what: String,
    showOpacity: Boolean,
    update: LayerStyle.(GradientFx.() -> GradientFx) -> LayerStyle,
) {
    var editor by remember { mutableStateOf<Pair<GradientPreset, Int?>?>(null) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.allGradients.forEachIndexed { i, p ->
            val mine = (i - GradientPreset.ALL.size).takeIf { it >= 0 }
            val cols = p.resolve(vm.foreground, vm.background)
            val sel = cols == g.colors && (p.positions ?: emptyList<Float>()) == (g.positions ?: emptyList<Float>())
            GradientTile(vm, p, sel, 52.dp, 26.dp, onPick = {
                vm.setStyle(what) { it.update { copy(colors = cols, positions = p.positions) } }
            }, userIndex = mine, onEdit = { n -> editor = vm.userGradients[n] to n })
        }
        // A new one starts from the gradient there is
        NewGradientTile(52.dp, 26.dp) { editor = GradientPreset("Gradient", g.colors, positions = g.positions) to null }
    }
    editor?.let { (start, editing) ->
        GradientEditorDialog(vm, start, editing, onDone = { editor = null }, pickForTool = false, onSaved = { saved ->
            vm.setStyle(what) { it.update { copy(colors = saved.colors, positions = saved.positions) } }
        })
    }
    Spacer(Modifier.height(6.dp))
    ChipRow(GradientType.entries.map { it.label }, g.type.ordinal) { i -> vm.setStyle("$what type") { it.update { copy(type = GradientType.entries[i]) } } }
    Spacer(Modifier.height(4.dp))
    if (showOpacity) FxSlider(vm, "Opacity", g.opacity, 0f..1f, "${(g.opacity * 100).roundToInt()}%") { v -> update { copy(opacity = v) } }
    FxSlider(vm, "Angle", g.angle, -180f..180f, "${g.angle.roundToInt()}°") { v -> update { copy(angle = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Scale", g.scale, 0.1f..2f, "${(g.scale * 100).roundToInt()}%") { v -> update { copy(scale = v) } }
    ToggleRow("Reverse", g.reverse) { r -> vm.setStyle("$what reverse") { it.update { copy(reverse = r) } } }
}

/** Pattern Overlay: which pattern, its size and strength. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PatternOverlayControls(vm: EditorViewModel, st: LayerStyle) {
    val po = st.patternOverlay
    fun LayerStyle.po(change: com.tim.quartzeditor.engine.PatternFx.() -> com.tim.quartzeditor.engine.PatternFx): LayerStyle =
        copy(patternOverlay = patternOverlay.change().copy(on = true))
    PatternGrid(vm, selected = po.key, colorA = po.colorA, colorB = po.colorB) { key ->
        // Ready-made patterns take your colors as they are now
        val a = vm.foreground or 0xFF000000.toInt()
        val b = vm.background or 0xFF000000.toInt()
        vm.warmPattern(key, a, b)
        vm.setStyle("Pattern overlay") { it.po { copy(key = key, colorA = a, colorB = b) } }
    }
    if (po.key.startsWith("b:")) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Colors", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SmallButton("Use foreground / background") {
                val a = vm.foreground or 0xFF000000.toInt()
                val b = vm.background or 0xFF000000.toInt()
                vm.setStyle("Pattern colors") { it.po { copy(colorA = a, colorB = b) } }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    FxSlider(vm, "Opacity", po.opacity, 0f..1f, "${(po.opacity * 100).roundToInt()}%") { v -> po { copy(opacity = v) } }
    FxBlend(po.blend) { b -> vm.setStyle("Pattern blend") { it.po { copy(blend = b) } } }
    FxSlider(
        vm, "Size", kotlin.math.log2(po.scale.coerceIn(0.125f, 4f)), -3f..2f, "${(po.scale * 100).roundToInt()}%",
        fromTyped = { pct -> kotlin.math.log2((pct / 100f).coerceIn(0.125f, 4f)) },
    ) { v -> po { copy(scale = Math.pow(2.0, v.toDouble()).toFloat().let { s -> if (kotlin.math.abs(s - 1f) < 0.04f) 1f else s }) } }
}

@Composable
private fun ShadowControls(vm: EditorViewModel, sh: ShadowFx, inner: Boolean) {
    fun LayerStyle.set(change: ShadowFx.() -> ShadowFx): LayerStyle =
        if (inner) copy(innerShadow = innerShadow.change().copy(on = true)) else copy(dropShadow = dropShadow.change().copy(on = true))
    StyleColor(vm, sh.color, "Shadow color") { c -> set { copy(color = c) } }
    FxSlider(vm, "Opacity", sh.opacity, 0f..1f, "${(sh.opacity * 100).roundToInt()}%") { v -> set { copy(opacity = v) } }
    FxSlider(vm, "Angle", sh.angle, -180f..180f, "${sh.angle.roundToInt()}°") { v -> set { copy(angle = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Distance", sh.distance, 0f..200f, "${sh.distance.roundToInt()} px") { v -> set { copy(distance = v.roundToInt().toFloat()) } }
    FxSlider(vm, "Size", sh.size, 0f..150f, "${sh.size.roundToInt()} px") { v -> set { copy(size = v.roundToInt().toFloat()) } }
    FxBlend(sh.blend) { b -> vm.setStyle("Shadow blend") { it.set { copy(blend = b) } } }
}

/** "Blend mode: Multiply ▾" for one effect. */
@Composable
private fun FxBlend(current: com.tim.quartzeditor.engine.Blend, onPick: (com.tim.quartzeditor.engine.Blend) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text("Blend mode", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(88.dp))
        // Effects are drawn by Android's own blend modes
        BlendMenu(current, Modifier.weight(1f), exactOnly = true, onPick = onPick)
    }
}

/** A slider that changes the style live and records one undo step when you let go. */
@Composable
private fun FxSlider(
    vm: EditorViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    fromTyped: ((Float) -> Float?)? = null,
    change: LayerStyle.(Float) -> LayerStyle,
) {
    PanelSlider(
        label = label,
        value = value.coerceIn(range.start, range.endInclusive),
        range = range,
        display = display,
        fromTyped = fromTyped,
        onChange = { v -> vm.setStyleLive { it.change(v) } },
        onDone = { vm.commitProps("Layer style") },
    )
}

/**
 * A layer style's color: a full color picker first, then white, black and the rainbow; your last
 * few colors; and the main colors in the picture. [onLive] (with [onLiveDone]) follows the picker
 * as it moves; without it the picker's color is used when it closes.
 */
@Composable
internal fun FxColor(vm: EditorViewModel, current: Int, onLive: ((Int) -> Unit)? = null, onLiveDone: (() -> Unit)? = null, onPick: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    // The picture's main colors (worked out in the background, when this shows)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.ensurePalette() }
    if (picking) {
        var last by remember { mutableStateOf<Int?>(null) }
        ColorPickDialog(
            vm,
            initial = current,
            title = "Color",
            onLive = { c ->
                last = c
                onLive?.invoke(c)
            },
            onDone = {
                picking = false
                if (onLive != null) onLiveDone?.invoke() else last?.let(onPick)
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Color", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(current))
                .border(1.dp, Q.text.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .clickable { picking = true }
        )
        Spacer(Modifier.width(8.dp))
        // The color picker: a rainbow wheel
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Brush.sweepGradient(PICKER_RAINBOW))
                .clickable { picking = true },
        ) {
            Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Q.panel))
        }
        Spacer(Modifier.width(6.dp))
        Text("Pick…", color = Q.text, fontSize = 12.sp, modifier = Modifier.clickable { picking = true }.padding(4.dp))
    }
    Spacer(Modifier.height(6.dp))
    ColorDots(STYLE_BASICS, current, onPick)
    val recent = vm.recentColors.take(4)
    if (recent.isNotEmpty()) {
        Text("Recent", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        ColorDots(recent, current, onPick)
    }
    val fromPicture = vm.imagePalette
    if (fromPicture.isNotEmpty()) {
        Text("From the picture", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        ColorDots(fromPicture, current, onPick)
    }
    Spacer(Modifier.height(4.dp))
}

/** [FxColor] for a layer style: the picker changes the layer live, as one undo step. */
@Composable
private fun StyleColor(vm: EditorViewModel, current: Int, label: String, change: LayerStyle.(Int) -> LayerStyle) {
    FxColor(
        vm, current,
        onLive = { c -> vm.setStyleLive { it.change(c) } },
        onLiveDone = { vm.commitProps(label) },
    ) { c -> vm.setStyle(label) { it.change(c) } }
}

/** White, black, then the rainbow. */
private val STYLE_BASICS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt(), 0xFFD81B60.toInt(),
)

private val PICKER_RAINBOW = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** The gloss contours, each drawn as its curve. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GlossPicker(current: com.tim.quartzeditor.engine.GlossContour, onPick: (com.tim.quartzeditor.engine.GlossContour) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.tim.quartzeditor.engine.GlossContour.entries.forEach { gc ->
            val sel = gc == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) Q.accent.copy(alpha = 0.25f) else Q.field)
                    .border(if (sel) 2.dp else 0.dp, if (sel) Q.accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onPick(gc) }
                    .padding(6.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(40.dp, 28.dp)) {
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0..40) {
                        val t = i / 40f
                        val y = glossCurve(gc, t)
                        val px = t * size.width
                        val py = size.height - y * size.height
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, Q.text, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                Text(gc.label, color = Q.textDim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** The same curves as the bevel's shader, for drawing the choices. */
private fun glossCurve(g: com.tim.quartzeditor.engine.GlossContour, t: Float): Float = when (g) {
    com.tim.quartzeditor.engine.GlossContour.LINEAR -> t
    com.tim.quartzeditor.engine.GlossContour.CONE -> 1f - kotlin.math.abs(2f * t - 1f)
    com.tim.quartzeditor.engine.GlossContour.CONE_INVERTED -> kotlin.math.abs(2f * t - 1f)
    com.tim.quartzeditor.engine.GlossContour.GAUSSIAN -> t * t * (3f - 2f * t)
    com.tim.quartzeditor.engine.GlossContour.HALF_ROUND -> kotlin.math.sqrt((1f - (1f - t) * (1f - t)).coerceAtLeast(0f))
    com.tim.quartzeditor.engine.GlossContour.RING -> 0.5f - 0.5f * kotlin.math.cos(t * 12.566371f)
    com.tim.quartzeditor.engine.GlossContour.STEPS -> {
        val s = t * 4f
        val f = s - kotlin.math.floor(s)
        ((kotlin.math.floor(s) + f * f * (3f - 2f * f)) / 4f).coerceAtMost(1f)
    }
}

/**
 * Blend If (gray): where the layer shows, by its own brightness and by the brightness of what's below.
 * Each end is a pair: between its two values the layer fades (the same value twice cuts sharply).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BlendIfControls(vm: EditorViewModel, l: com.tim.quartzeditor.engine.Layer) {
    val b = l.props.blendIf
    Text(
        "Blend If (gray): the layer shows only where it, and what's below it, are in these ranges. " +
            "Pull the black or white ends in to hide its darks or lights; spread an end's two values apart for a soft edge.",
        color = Q.textDim, fontSize = 12.sp,
    )
    fun live(change: (com.tim.quartzeditor.engine.BlendIf) -> com.tim.quartzeditor.engine.BlendIf) =
        vm.setPropsLive(l.id) { it.copy(blendIf = change(it.blendIf).sane()) }
    val done = { vm.commitProps("Blend If") }

    @Composable
    fun part(title: String, lo0: Int, lo1: Int, hi0: Int, hi1: Int, set: (com.tim.quartzeditor.engine.BlendIf, Int, Int, Int, Int) -> com.tim.quartzeditor.engine.BlendIf) {
        Spacer(Modifier.height(10.dp))
        Text(title, color = Q.text, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, Color.White)))
        )
        val colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Q.sliderThumb, activeTrackColor = Q.accent, inactiveTrackColor = Q.field)
        Text("Black end: hidden below ${lo0}, fully shown from ${lo1}", color = Q.textDim, fontSize = 12.sp)
        androidx.compose.material3.RangeSlider(
            value = lo0.toFloat()..lo1.toFloat(),
            onValueChange = { r -> live { set(it, r.start.toInt(), r.endInclusive.toInt().coerceAtMost(hi0), hi0, hi1) } },
            onValueChangeFinished = done,
            valueRange = 0f..255f,
            colors = colors,
            modifier = Modifier.height(36.dp),
        )
        Text("White end: fully shown up to ${hi0}, hidden above ${hi1}", color = Q.textDim, fontSize = 12.sp)
        androidx.compose.material3.RangeSlider(
            value = hi0.toFloat()..hi1.toFloat(),
            onValueChange = { r -> live { set(it, lo0, lo1, r.start.toInt().coerceAtLeast(lo1), r.endInclusive.toInt()) } },
            onValueChangeFinished = done,
            valueRange = 0f..255f,
            colors = colors,
            modifier = Modifier.height(36.dp),
        )
    }
    part("This Layer", b.thisLo0, b.thisLo1, b.thisHi0, b.thisHi1) { o, a, c, e, f -> o.copy(thisLo0 = a, thisLo1 = c, thisHi0 = e, thisHi1 = f) }
    part("Underlying Layer", b.underLo0, b.underLo1, b.underHi0, b.underHi1) { o, a, c, e, f -> o.copy(underLo0 = a, underLo1 = c, underHi0 = e, underHi1 = f) }
    if (b.active) {
        Spacer(Modifier.height(6.dp))
        SmallButton("Reset Blend If") { vm.setProps(l.id, "Reset Blend If") { it.copy(blendIf = com.tim.quartzeditor.engine.BlendIf()) } }
    }
}
