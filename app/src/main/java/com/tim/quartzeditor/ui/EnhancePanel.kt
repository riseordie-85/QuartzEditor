package com.tim.quartzeditor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.Blend
import com.tim.quartzeditor.engine.LightSettings
import com.tim.quartzeditor.engine.SkyPreset
import java.util.Locale
import kotlin.math.roundToInt

private fun pct(v: Float) = "${(v * 100f).roundToInt()}%"

/** The settings of the Enhance effect being set up (in the floating window). */
@Composable
internal fun EnhancePanel(vm: EditorViewModel) {
    val s = vm.enhance ?: return
    val p = vm.enhanceParams
    when (s.kind) {
        EnhanceKind.DEPTH_BLUR -> {
            Hint("Tap the picture on what should stay sharp. Nearer and farther things blur, like a wide-open lens.")
            PanelSlider("Blur", p.blur, 1f..80f, "${p.blur.roundToInt()} px", onChange = { v -> vm.setEnhance { it.copy(blur = v) } })
            PanelSlider("Focus depth", p.focus, 0f..1f, if (p.focus > 0.66f) "Near" else if (p.focus > 0.33f) "Middle" else "Far",
                onChange = { v -> vm.setEnhance { it.copy(focus = v, focusPoint = null) } })
            PanelSlider("In focus", p.range, 0.01f..0.5f, pct(p.range), onChange = { v -> vm.setEnhance { it.copy(range = v) } })
        }
        EnhanceKind.FOG -> {
            Hint("Haze that thickens with distance. Tap the picture where it should start.")
            PanelSlider("Starts", p.fogStart, 0f..0.95f, pct(p.fogStart), onChange = { v -> vm.setEnhance { it.copy(fogStart = v) } })
            PanelSlider("Fade in", p.fogSoftness, 0.02f..1f, pct(p.fogSoftness), onChange = { v -> vm.setEnhance { it.copy(fogSoftness = v) } })
            PanelSlider("Thickness", p.fogDensity, 0f..1f, pct(p.fogDensity), onChange = { v -> vm.setEnhance { it.copy(fogDensity = v) } })
            Text("Color", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            EnhanceColor(vm, p.fogColor, FOG_COLORS, "Fog color") { c -> vm.setEnhance { it.copy(fogColor = c) } }
        }
        EnhanceKind.RELIGHT -> {
            Hint("Drag on the picture to move the light. It's added as its own layer (Soft Light), so you can fade it or paint its mask.")
            val l = p.light ?: LightSettings(0f, 0f)
            fun set(f: (LightSettings) -> LightSettings) = vm.setEnhance { it.copy(light = f(it.light ?: l)) }
            PanelSlider("Strength", l.strength, 0f..1f, pct(l.strength), onChange = { v -> set { it.copy(strength = v) } })
            PanelSlider("Height", l.height, 0.03f..1.5f, pct(l.height), onChange = { v -> set { it.copy(height = v) } })
            PanelSlider("Reach", l.spread, 0.1f..2f, pct(l.spread), onChange = { v -> set { it.copy(spread = v) } })
            PanelSlider("Shadows", l.shadows, 0f..1f, pct(l.shadows), onChange = { v -> set { it.copy(shadows = v) } })
            PanelSlider("Relief", p.relief, 0.1f..2f, pct(p.relief), onChange = { v -> vm.setEnhance { it.copy(relief = v) } })
            Text("Color", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            EnhanceColor(vm, l.color, LIGHT_COLORS, "Light color") { c -> set { it.copy(color = c) } }
            Spacer(Modifier.height(8.dp))
            Text("Blends as", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            val blends = listOf(Blend.SOFT_LIGHT, Blend.OVERLAY, Blend.LINEAR_LIGHT)
            ChipRow(blends.map { it.label }, blends.indexOf(p.lightBlend)) { i -> vm.setEnhance { it.copy(lightBlend = blends[i]) } }
        }
        EnhanceKind.SKY -> {
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let { u -> vm.setEnhance { it.copy(skyPhoto = u) } }
            }
            Hint("The sky is found from depth: the farthest part that reaches the top edge. It's added as a layer with a mask you can touch up.")
            val presets = SkyPreset.entries
            ChipRow(presets.map { it.label } + "Your photo…", if (p.skyPhoto != null) presets.size else presets.indexOf(p.sky)) { i ->
                if (i < presets.size) vm.setEnhance { it.copy(sky = presets[i], skyPhoto = null) }
                else pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Spacer(Modifier.height(8.dp))
            if (p.skyPhoto == null) PanelSlider("Clouds", p.clouds, 0f..1f, pct(p.clouds), onChange = { v -> vm.setEnhance { it.copy(clouds = v) } })
            PanelSlider("Sky reaches", p.skyEdge, 0.02f..0.6f, pct(p.skyEdge), onChange = { v -> vm.setEnhance { it.copy(skyEdge = v) } })
            PanelSlider("Edge softness", p.skySoft, 0f..20f, String.format(Locale.US, "%.1f px", p.skySoft), onChange = { v -> vm.setEnhance { it.copy(skySoft = v) } })
        }
        EnhanceKind.DENOISE -> {
            Hint("Smooths grain while keeping edges. Changes this layer's pixels (Undo brings them back).")
            PanelSlider("Strength", p.denoise, 0f..1f, pct(p.denoise), onChange = { v -> vm.setEnhance { it.copy(denoise = v) } })
            PanelSlider("Color speckles", p.colorNoise, 0f..1f, pct(p.colorNoise), onChange = { v -> vm.setEnhance { it.copy(colorNoise = v) } })
        }
        EnhanceKind.SHARPEN -> {
            Hint("Crisper detail without color fringes. Changes this layer's pixels (Undo brings them back).")
            PanelSlider("Amount", p.sharpen, 0f..3f, pct(p.sharpen), onChange = { v -> vm.setEnhance { it.copy(sharpen = v) } })
            PanelSlider("Radius", p.sharpenRadius, 0.3f..6f, String.format(Locale.US, "%.1f px", p.sharpenRadius), onChange = { v -> vm.setEnhance { it.copy(sharpenRadius = v) } })
            PanelSlider("Leave smooth areas", p.sharpenThreshold, 0f..0.1f, "${(p.sharpenThreshold * 255f).roundToInt()}",
                onChange = { v -> vm.setEnhance { it.copy(sharpenThreshold = v) } }, fromTyped = { (it / 255f).coerceIn(0f, 0.1f) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Cancel", Modifier.weight(1f)) { vm.cancelEnhance() }
        SmallButton("Apply", Modifier.weight(1f), primary = true) { vm.applyEnhance() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
}

private val FOG_COLORS = listOf(0xFFE8ECF0.toInt(), 0xFFFFFFFF.toInt(), 0xFFB8C4D0.toInt(), 0xFF9AA6B4.toInt(), 0xFFF3E2C8.toInt(), 0xFFD8C8E8.toInt())
private val LIGHT_COLORS = listOf(0xFFFFE6C4.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFC27A.toInt(), 0xFFFF9A5C.toInt(), 0xFFCFE2FF.toInt(), 0xFF9FC3FF.toInt())

/** Ready-made colors, plus "Other…" for any color. */
@Composable
private fun EnhanceColor(vm: EditorViewModel, color: Int, choices: List<Int>, title: String, onPick: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ColorDots(choices, color.takeIf { it in choices }, onPick)
    Spacer(Modifier.height(6.dp))
    SmallButton("Other color…") { picking = true }
    if (picking) {
        ColorPickDialog(vm, color, title = title, onLive = onPick, onDone = { picking = false })
    }
}
