package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tim.quartzeditor.engine.Doc
import kotlin.math.roundToInt

/** Image › Canvas size (Ctrl+Alt+C): new width and height, and where the picture sits (the anchor). */
@Composable
fun CanvasSizeDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val d = vm.doc ?: return
    var w by remember { mutableIntStateOf(d.width) }
    var h by remember { mutableIntStateOf(d.height) }
    var ax by remember { mutableFloatStateOf(0.5f) }
    var ay by remember { mutableFloatStateOf(0.5f) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(18.dp),
        ) {
            Text("Canvas size", color = Q.text, fontSize = 18.sp)
            Text("Now ${d.width} × ${d.height} px. The picture stays its size; new room is see-through, and what's outside is kept off the canvas.", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Width", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                PanelField("$w", onCommit = { t -> t.trim().toIntOrNull()?.let { w = it.coerceIn(1, Doc.MAX_SIDE) } }, numeric = true, modifier = Modifier.width(100.dp),
                    onType = { t -> t.trim().toIntOrNull()?.takeIf { it > 0 }?.let { w = it.coerceIn(1, Doc.MAX_SIDE) } })
                Text(" px", color = Q.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Height", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                PanelField("$h", onCommit = { t -> t.trim().toIntOrNull()?.let { h = it.coerceIn(1, Doc.MAX_SIDE) } }, numeric = true, modifier = Modifier.width(100.dp),
                    onType = { t -> t.trim().toIntOrNull()?.takeIf { it > 0 }?.let { h = it.coerceIn(1, Doc.MAX_SIDE) } })
                Text(" px", color = Q.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text("Anchor", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            // Where the picture stays: a 3×3 grid of spots
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0..2) {
                        val on = (ax * 2).roundToInt() == c && (ay * 2).roundToInt() == r
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) Q.accent else Q.field)
                                .border(1.dp, Q.divider, RoundedCornerShape(6.dp))
                                .clickable {
                                    ax = c / 2f
                                    ay = r / 2f
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Cancel") { onDismiss() }
                SmallButton("OK", primary = true, enabled = w != d.width || h != d.height) {
                    onDismiss()
                    vm.resizeCanvas(w, h, ax, ay)
                }
            }
        }
    }
}

/** Select › Feather (Shift+F6): how many pixels the selection's edge fades over. */
@Composable
fun FeatherDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var px by remember { mutableFloatStateOf(vm.doc?.selection?.feather?.takeIf { it > 0f } ?: 5f) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(18.dp),
        ) {
            Text("Feather selection", color = Q.text, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            PanelSlider("Radius", px, 0f..250f, "${px.roundToInt()} px", onChange = { px = it })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Cancel") { onDismiss() }
                SmallButton("OK", primary = true) {
                    onDismiss()
                    vm.featherSelection(px)
                }
            }
        }
    }
}
