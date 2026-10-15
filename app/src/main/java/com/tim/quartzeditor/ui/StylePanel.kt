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
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.ShadowFx
import com.tim.quartzeditor.engine.StrokePosition
import kotlin.math.roundToInt

private enum class FxKind(val label: String) {
    STROKE("Stroke"), DROP_SHADOW("Drop Shadow"), INNER_SHADOW("Inner Shadow"), OUTER_GLOW("Outer Glow"), COLOR_OVERLAY("Color Overlay")
}

/** Layer Style: switch effects on and off and tune them. They never change the layer's pixels. */
@Composable
fun StylePanel(vm: EditorViewModel) {
    val l = vm.doc?.active ?: return
    val st = l.props.style
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Text("Effects on \"${l.props.name}\". They follow the layer and never change its pixels.", color = Q.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))

    FxKind.entries.forEach { kind ->
        val on = when (kind) {
            FxKind.STROKE -> st.stroke.on
            FxKind.DROP_SHADOW -> st.dropShadow.on
            FxKind.INNER_SHADOW -> st.innerShadow.on
            FxKind.OUTER_GLOW -> st.outerGlow.on
            FxKind.COLOR_OVERLAY -> st.colorOverlay.on
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
                        FxKind.STROKE -> StrokeControls(vm, st)
                        FxKind.DROP_SHADOW -> ShadowControls(vm, st.dropShadow, inner = false)
                        FxKind.INNER_SHADOW -> ShadowControls(vm, st.innerShadow, inner = true)
                        FxKind.OUTER_GLOW -> {
                            val g = st.outerGlow
                            FxColor(vm, g.color) { c -> vm.setStyle("Glow color") { it.copy(outerGlow = it.outerGlow.copy(color = c, on = true)) } }
                            FxSlider(vm, "Opacity", g.opacity, 0f..1f, "${(g.opacity * 100).roundToInt()}%") { v -> copy(outerGlow = outerGlow.copy(opacity = v, on = true)) }
                            FxSlider(vm, "Size", g.size, 1f..150f, "${g.size.roundToInt()} px") { v -> copy(outerGlow = outerGlow.copy(size = v, on = true)) }
                        }
                        FxKind.COLOR_OVERLAY -> {
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

private fun LayerStyle.toggle(kind: FxKind, on: Boolean): LayerStyle = when (kind) {
    FxKind.STROKE -> copy(stroke = stroke.copy(on = on))
    FxKind.DROP_SHADOW -> copy(dropShadow = dropShadow.copy(on = on))
    FxKind.INNER_SHADOW -> copy(innerShadow = innerShadow.copy(on = on))
    FxKind.OUTER_GLOW -> copy(outerGlow = outerGlow.copy(on = on))
    FxKind.COLOR_OVERLAY -> copy(colorOverlay = colorOverlay.copy(on = on))
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
