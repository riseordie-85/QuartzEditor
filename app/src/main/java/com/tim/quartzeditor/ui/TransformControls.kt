package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.tim.quartzeditor.engine.TransformMode

/** Floating bar over the picture while transforming: cancel, flips, turns, distort, apply. */
@Composable
fun TransformBar(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val t = vm.transform ?: return
    val st = t.state
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(24.dp))
            .background(Q.toolbar.copy(alpha = 0.95f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BarIcon(Icons.Outlined.Close, "Cancel transform") { vm.cancelTransform() }
        BarIcon(Icons.Outlined.Flip, "Flip horizontally") { vm.updateTransform { it.flippedH() } }
        BarIcon(Icons.Outlined.Flip, "Flip vertically", rotate = 90f) { vm.updateTransform { it.flippedV() } }
        BarIcon(Icons.Outlined.Rotate90DegreesCcw, "Rotate 90° left") { vm.updateTransform { it.rotated(-90f) } }
        BarIcon(Icons.Outlined.Rotate90DegreesCw, "Rotate 90° right") { vm.updateTransform { it.rotated(90f) } }
        // How the handles work (Free goes away once the corners have been placed freely)
        TransformMode.entries.forEach { m ->
            if (m == TransformMode.FREE && st.distort) return@forEach
            Text(
                m.label,
                color = if (st.mode == m) Color.White else Q.text,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (st.mode == m) Q.accent else Q.field)
                    .clickable { vm.updateTransform { it.withMode(m) } }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Q.accent)
                .clickable { vm.applyTransform() },
        ) {
            Icon(Icons.Outlined.Check, contentDescription = "Apply transform", tint = Color.White)
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, rotate: Float = 0f, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(22.dp).rotate(rotate))
    }
}

/** Exact numbers for the transform, shown at the top of Properties. */
@Composable
fun TransformProperties(vm: EditorViewModel) {
    val t = vm.transform ?: return
    val st = t.state
    Text("Transform", color = Q.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    if (st.distort) {
        Text(
            when (st.mode) {
                TransformMode.SKEW -> "Skew: drag a side's middle to slant it, or a corner along one side. Drag inside to move."
                TransformMode.PERSPECTIVE -> "Perspective: drag a corner along a side; the other end of that side moves the opposite way. Drag inside to move."
                else -> "Distort: drag each corner anywhere. Drag inside to move."
            },
            color = Q.textDim, fontSize = 12.sp,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("W", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(20.dp))
            PanelField("${(st.scaleX * 100).roundToInt()}", onCommit = { v ->
                v.trim().removeSuffix("%").toFloatOrNull()?.let { p ->
                    vm.updateTransform { s -> if (s.keepAspect) s.withScale(p / 100f, s.scaleY * (p / 100f) / s.scaleX) else s.withScale(p / 100f, s.scaleY) }
                }
            }, numeric = true, modifier = Modifier.width(64.dp))
            Text("%", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (st.keepAspect) Q.accent else Q.field)
                    .clickable { vm.updateTransform { it.copy(keepAspect = !it.keepAspect) } },
            ) {
                Icon(if (st.keepAspect) Icons.Outlined.Link else Icons.Outlined.LinkOff, contentDescription = "Keep proportions", tint = if (st.keepAspect) Color.White else Q.text, modifier = Modifier.size(18.dp))
            }
            Text("H", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(20.dp))
            PanelField("${(st.scaleY * 100).roundToInt()}", onCommit = { v ->
                v.trim().removeSuffix("%").toFloatOrNull()?.let { p ->
                    vm.updateTransform { s -> if (s.keepAspect) s.withScale(s.scaleX * (p / 100f) / s.scaleY, p / 100f) else s.withScale(s.scaleX, p / 100f) }
                }
            }, numeric = true, modifier = Modifier.width(64.dp))
            Text("%", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Angle", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(48.dp))
            PanelField("${st.angle.roundToInt()}", onCommit = { v ->
                v.trim().removeSuffix("°").toFloatOrNull()?.let { a -> vm.updateTransform { s -> s.rotated(a - s.angle) } }
            }, numeric = true, modifier = Modifier.width(64.dp))
            Text("°", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("Corners scale, edges stretch, outside the box rotates.", color = Q.textDim, fontSize = 12.sp)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Reset") { vm.resetTransform() }
        SmallButton("Cancel") { vm.cancelTransform() }
        SmallButton("Apply", primary = true) { vm.applyTransform() }
    }
    Spacer(Modifier.height(12.dp))
}
