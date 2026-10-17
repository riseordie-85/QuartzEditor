package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The floating modifier button: a Shift key for the screen (like Photoshop's touch shortcut).
 * Hold it with one finger while you work with another. Slide out to the ring for Alt instead.
 * Double-tap to keep Shift on (tap again to let go). Drag the grip under it to move it.
 */
@Composable
fun ModifierButton(vm: EditorViewModel, modifier: Modifier = Modifier) {
    if (!vm.showModifier || vm.doc == null) return
    val density = LocalDensity.current
    val btn = 58.dp
    val ring = with(density) { 46.dp.toPx() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val bPx = with(density) { btn.toPx() }
        val gripPx = with(density) { 22.dp.toPx() }
        // Where it sits (fractions of the room it has), bottom left to start
        var at by remember { mutableStateOf(vm.modifierAt ?: Offset(0.02f, 0.78f)) }
        val room = Offset((wPx - bPx).coerceAtLeast(1f), (hPx - bPx - gripPx).coerceAtLeast(1f))
        val x = (at.x.coerceIn(0f, 1f) * room.x).roundToInt()
        val y = (at.y.coerceIn(0f, 1f) * room.y).roundToInt()
        var lastTap by remember { mutableStateOf(0L) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.offset { IntOffset(x, y) },
        ) {
            val on = vm.modShift || vm.modAlt
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(btn)
                    .clip(CircleShape)
                    .background(if (vm.modAlt) Q.accent.copy(alpha = 0.6f) else if (on) Q.accent else Q.toolbar.copy(alpha = 0.85f))
                    .border(if (vm.modLocked) 3.dp else 1.dp, if (vm.modLocked) Color.White else Q.text.copy(alpha = 0.35f), CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val t0 = down.uptimeMillis
                            val wasLocked = vm.modLocked
                            vm.modShift = true
                            vm.modAlt = false
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                // Slid out past the ring: Alt instead of Shift
                                val d = (c.position - Offset(size.width / 2f, size.height / 2f)).getDistance()
                                if (d > ring) moved = true
                                vm.modAlt = d > ring
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            val quick = !moved && now - t0 < 260
                            when {
                                // Double-tap: keep Shift on
                                quick && !wasLocked && now - lastTap < 400 -> {
                                    vm.modLocked = true
                                    vm.modShift = true
                                    vm.modAlt = false
                                    vm.toast("Shift kept on (tap to let go)")
                                }
                                quick && wasLocked -> {
                                    vm.modLocked = false
                                    vm.modShift = false
                                    vm.modAlt = false
                                }
                                vm.modLocked -> {
                                    vm.modShift = true
                                    vm.modAlt = false
                                }
                                else -> {
                                    vm.modShift = false
                                    vm.modAlt = false
                                }
                            }
                            lastTap = if (quick) now else 0L
                        }
                    },
            ) {
                Text(if (vm.modAlt) "Alt" else "⇧", color = if (on) Color.White else Q.text, fontSize = if (vm.modAlt) 16.sp else 24.sp)
            }
            // The grip: drag to move the button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 40.dp, height = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Q.toolbar.copy(alpha = 0.7f))
                    .pointerInput(room) {
                        detectDragGestures(
                            onDragEnd = {
                                vm.modifierAt = at
                                vm.prefs.edit().putFloat("modX", at.x).putFloat("modY", at.y).apply()
                            },
                        ) { change, drag ->
                            change.consume()
                            at = Offset((at.x + drag.x / room.x).coerceIn(0f, 1f), (at.y + drag.y / room.y).coerceIn(0f, 1f))
                        }
                    },
            ) {
                Icon(Icons.Filled.DragIndicator, contentDescription = "Move the modifier button", tint = Q.textDim, modifier = Modifier.size(16.dp))
            }
        }
        // What it does right now, beside it
        if (vm.modShift || vm.modAlt) {
            val hint = modifierHint(vm)
            if (hint != null) {
                val right = x + bPx + with(density) { 10.dp.toPx() } < wPx - with(density) { 200.dp.toPx() }
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                if (right) (x + bPx + 10.dp.toPx()).roundToInt() else (x - 210.dp.toPx()).roundToInt().coerceAtLeast(0),
                                (y + bPx / 2f - 14.dp.toPx()).roundToInt(),
                            )
                        }
                        .widthIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** What Shift or Alt does with the tool you're using. */
private fun modifierHint(vm: EditorViewModel): String? {
    val alt = vm.modAlt
    return when (vm.tool) {
        Tool.BRUSH, Tool.ERASER -> if (alt) null else "Straight lines (0°, 45°, 90°). Tap: a line from the last stroke"
        Tool.SHAPE -> if (alt) "Draw from the middle" else "Square, circle, 45° lines"
        Tool.TRANSFORM -> if (alt) "Resize from the middle" else "Keep proportions (or not); rotate in 15° steps"
        Tool.MOVE -> if (alt) "Move a copy" else "Straight across or up and down"
        Tool.SELECT -> when (vm.selectShape) {
            com.tim.quartzeditor.engine.SelectShape.RECT, com.tim.quartzeditor.engine.SelectShape.ELLIPSE ->
                if (alt) "Draw from the middle" else "Square or circle"
            com.tim.quartzeditor.engine.SelectShape.WAND, com.tim.quartzeditor.engine.SelectShape.LASSO ->
                if (alt) "Take away from the selection" else "Add to the selection"
            else -> null
        }
        Tool.GRADIENT -> if (alt) null else "Straight, or at 45°"
        Tool.CROP -> if (alt) "From the middle" else "Keep the box's shape"
        else -> null
    }
}
