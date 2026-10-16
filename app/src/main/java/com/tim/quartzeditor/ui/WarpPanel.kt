package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.FaceGroup
import com.tim.quartzeditor.engine.FaceSlider
import com.tim.quartzeditor.engine.LiquifyTool
import com.tim.quartzeditor.engine.WarpKind
import com.tim.quartzeditor.engine.WarpPreset
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/** Warp / Puppet Warp / Liquify: cancel, undo a step, reset, (delete pin), apply. */
@Composable
fun WarpBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val w = vm.warp ?: return
    @Suppress("UNUSED_VARIABLE") val v = w.version
    Bar(modifier) {
        BarIcon(Icons.Outlined.Close, "Cancel ${w.kind.label.lowercase()}") { vm.cancelWarp() }
        Text(w.kind.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
        BarIcon(Icons.AutoMirrored.Outlined.Undo, "Undo the last change") { vm.warpUndo() }
        BarIcon(Icons.Outlined.RestartAlt, "Reset") { vm.resetWarp() }
        if (w.kind == WarpKind.PUPPET && w.pinSelected in w.pinTo.indices) {
            BarIcon(Icons.Outlined.Delete, "Delete pin") { vm.deletePin() }
        }
        Spacer(Modifier.width(4.dp))
        Box(
            contentAlignment = androidx.compose.ui.Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Q.accent)
                .clickable { vm.applyWarp() },
        ) {
            Icon(Icons.Outlined.Check, contentDescription = "Apply ${w.kind.label.lowercase()}", tint = Color.White)
        }
    }
}

/** The Warp tool's settings in Properties. */
@Composable
fun WarpOptions(vm: EditorViewModel) {
    val w = vm.warp
    val kind = w?.kind ?: vm.warpKind
    Title(kind.label)
    Spacer(Modifier.height(6.dp))
    ChipRow(WarpKind.entries.map { it.label }, kind.ordinal) { i -> vm.chooseWarp(WarpKind.entries[i]) }
    Spacer(Modifier.height(8.dp))
    if (w == null) {
        Text("Tap the picture to start on the selected layer.", color = Q.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        return
    }
    @Suppress("UNUSED_VARIABLE") val v = w.version
    when (kind) {
        WarpKind.WARP -> {
            Text("Drag the points, or anywhere on the picture to bend it there. Or start from a shape:", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            ChipRow(WarpPreset.entries.map { it.label }, w.preset.ordinal) { i -> vm.setWarpPreset(WarpPreset.entries[i]) }
            if (w.preset != WarpPreset.CUSTOM) {
                Spacer(Modifier.height(6.dp))
                // One undo step per slide
                val started = remember { booleanArrayOf(false) }
                PanelSlider(
                    "Bend", w.bend, -1f..1f, "${(w.bend * 100).roundToInt()}%",
                    onChange = {
                        if (!started[0]) {
                            started[0] = true
                            vm.beginWarpBend()
                        }
                        vm.setWarpBend(it)
                    },
                    onDone = { started[0] = false },
                )
            }
        }
        WarpKind.PUPPET -> {
            Text(
                "Tap the picture to add pins (put a few where it should stay still), then drag a pin. Tap a pin to pick it; the bin above deletes it.",
                color = Q.textDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            PanelSlider(
                "Stiffness", w.stiffness, 1f..2.5f, when {
                    w.stiffness < 1.4f -> "Smooth"
                    w.stiffness < 2f -> "Firm"
                    else -> "Stiff"
                },
                onChange = { vm.setPuppetStiffness(it) },
            )
            Text("${w.pinTo.size} pin${if (w.pinTo.size == 1) "" else "s"}", color = Q.textDim, fontSize = 12.sp)
        }
        WarpKind.LIQUIFY -> {
            ChipRow(LiquifyTool.entries.map { it.label }, vm.liquifyTool.ordinal) { i -> vm.chooseLiquifyTool(LiquifyTool.entries[i]) }
            Spacer(Modifier.height(6.dp))
            Text(
                when (vm.liquifyTool) {
                    LiquifyTool.PUSH -> "Drag to push the picture along."
                    LiquifyTool.BLOAT -> "Hold to swell what's under the brush."
                    LiquifyTool.PUCKER -> "Hold to shrink what's under the brush."
                    LiquifyTool.TWIRL_RIGHT, LiquifyTool.TWIRL_LEFT -> "Hold to swirl what's under the brush."
                    LiquifyTool.SMOOTH -> "Brush to soften sharp changes."
                    LiquifyTool.RESTORE -> "Brush to bring back how it was."
                },
                color = Q.textDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            // Size goes on a curve: fine control for small brushes
            val lo = ln(8f)
            val hi = ln(2000f)
            PanelSlider(
                "Size", ((ln(vm.liquifySize.coerceIn(8f, 2000f)) - lo) / (hi - lo)), 0f..1f, "${vm.liquifySize.roundToInt()} px",
                onChange = { vm.liquifySize = exp(lo + (hi - lo) * it) },
                onDone = { vm.saveLiquifyPrefs() },
            )
            PanelSlider(
                "Strength", vm.liquifyStrength, 0.05f..1f, "${(vm.liquifyStrength * 100).roundToInt()}%",
                onChange = { vm.liquifyStrength = it },
                onDone = { vm.saveLiquifyPrefs() },
            )
            Text("[ and ] change the size on a keyboard.", color = Q.textDim, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            FaceControls(vm, w)
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** Face-aware Liquify: find faces, then reshape the eyes, nose, mouth and face with sliders. */
@Composable
private fun FaceControls(vm: EditorViewModel, w: WarpSession) {
    Title("Face-aware")
    Spacer(Modifier.height(4.dp))
    if (!w.facesLooked || w.faces.isEmpty()) {
        Text(
            if (w.facesLooked) "No faces found on this layer." else "Finds faces on your device (it may download once through Google Play services).",
            color = Q.textDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        SmallButton(if (w.facesLooked) "Look again" else "Find faces", primary = !w.facesLooked) { vm.findFaces() }
        return
    }
    if (w.faces.size > 1) {
        ChipRow(w.faces.indices.map { "Face ${it + 1}" }, w.faceIndex) { w.faceIndex = it; w.version++ }
        Spacer(Modifier.height(6.dp))
    }
    ChipRow(FaceGroup.entries.map { it.label }, w.faceGroup.ordinal) { w.faceGroup = FaceGroup.entries[it] }
    Spacer(Modifier.height(6.dp))
    val values = w.faceValues.getOrNull(w.faceIndex) ?: return
    // One undo step per slide
    val started = remember { booleanArrayOf(false) }
    FaceSlider.entries.filter { it.group == w.faceGroup }.forEach { f ->
        val v = values[f] ?: 0f
        PanelSlider(
            f.label, v, -1f..1f, "${(v * 100).roundToInt()}",
            onChange = { x ->
                if (!started[0]) {
                    started[0] = true
                    vm.beginFaceSlide()
                }
                // A little catch at zero
                vm.setFaceValue(f, if (kotlin.math.abs(x) < 0.03f) 0f else x)
            },
            onDone = { started[0] = false },
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Reset face") { vm.resetFace() }
        SmallButton("Look again") { vm.findFaces() }
    }
}
