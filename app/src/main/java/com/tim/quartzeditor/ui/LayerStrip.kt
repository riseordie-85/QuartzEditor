package com.tim.quartzeditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tim.quartzeditor.engine.FxKind
import com.tim.quartzeditor.engine.Layer
import kotlin.math.abs

/**
 * The Layers panel folded away: a small stack of layer pictures at the edge of the picture (like
 * Photoshop on phones). The picked layer sits in the middle with its neighbours peeking out above
 * and below. Swipe up or down to go through the layers, tap one that peeks out to pick it, hold the
 * middle one to hide or show it. ⌃ opens the full panel; + adds a layer.
 */
@Composable
fun LayerStrip(vm: EditorViewModel, onExpand: () -> Unit, onPlaceImage: () -> Unit, modifier: Modifier = Modifier) {
    val d = vm.doc ?: return
    @Suppress("UNUSED_VARIABLE") val v = d.renderVersion
    // Top layer first, as in the panel (what's inside folded folders stays inside)
    val shown = ArrayList<Layer>()
    fun addRows(parent: Long) {
        for (l in d.children(parent).asReversed()) {
            shown.add(l)
            if (l.isGroup && !l.collapsed) addRows(l.id)
        }
    }
    addRows(0L)
    if (shown.isEmpty()) return
    // The active layer, or the folded folder it's in
    var at = shown.indexOfFirst { it.id == d.activeId }
    if (at < 0) {
        var p = d.active?.props?.parent ?: 0L
        while (at < 0 && p != 0L) {
            at = shown.indexOfFirst { it.id == p }
            p = d.layer(p)?.props?.parent ?: 0L
        }
    }
    at = at.coerceAtLeast(0)
    val current by rememberUpdatedState(at)
    val list by rememberUpdatedState(shown)
    val density = LocalDensity.current
    val step = with(density) { 30.dp.toPx() }
    fun pick(i: Int) {
        val l = list.getOrNull(i) ?: return
        if (l.id != vm.doc?.activeId) vm.selectLayer(l.id)
    }

    Column(modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StripButton(Icons.Filled.KeyboardArrowUp, "Open the Layers panel", onExpand)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(64.dp)
                .height(150.dp)
                .pointerInput(Unit) {
                    // Swiping goes through the layers: up for the ones below, down for the ones above
                    var travel = 0f
                    detectVerticalDragGestures(onDragStart = { travel = 0f }) { change, dy ->
                        change.consume()
                        travel += dy
                        while (travel <= -step) {
                            travel += step
                            pick((current + 1).coerceAtMost(list.lastIndex))
                        }
                        while (travel >= step) {
                            travel -= step
                            pick((current - 1).coerceAtLeast(0))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            // Above the middle card: the layer above; below it: the layer below
                            val mid = size.height / 2f
                            val half = with(density) { 27.dp.toPx() }
                            when {
                                o.y < mid - half -> pick(current - 1)
                                o.y > mid + half -> pick(current + 1)
                            }
                        },
                        onLongPress = { o ->
                            val mid = size.height / 2f
                            if (abs(o.y - mid) < with(density) { 27.dp.toPx() }) {
                                list.getOrNull(current)?.let { l ->
                                    val wasVisible = l.props.visible
                                    vm.setProps(l.id, if (wasVisible) "Hide layer" else "Show layer") { it.copy(visible = !wasVisible) }
                                    vm.toast(if (wasVisible) "\"${l.props.name}\" hidden" else "\"${l.props.name}\" shown")
                                }
                            }
                        },
                    )
                },
        ) {
            // Farthest first, so nearer cards sit on top
            for (dist in 3 downTo 1) {
                for (dir in listOf(-1, 1)) {
                    val l = shown.getOrNull(at + dir * dist) ?: continue
                    Card(
                        vm, l, picked = false,
                        modifier = Modifier
                            .offset(y = (dir * (20 + dist * 9)).dp)
                            .scale(1f - dist * 0.08f)
                            .alpha(1f - dist * 0.22f),
                    )
                }
            }
            Card(vm, shown[at], picked = true)
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            StripButton(Icons.Outlined.Add, "Add a layer") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New layer") }, leadingIcon = { Icon(Icons.Outlined.Layers, null) }, onClick = { menu = false; vm.addLayer() })
                DropdownMenuItem(text = { Text("Place image…") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { menu = false; onPlaceImage() })
                DropdownMenuItem(
                    text = { Text(if (vm.isMultiSelecting) "Put picked layers in a folder" else "New folder") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    onClick = {
                        menu = false
                        if (vm.isMultiSelecting) vm.groupActive() else vm.addFolder()
                    },
                )
            }
        }
    }
}

/** One layer's card in the stack. */
@Composable
private fun Card(vm: EditorViewModel, l: Layer, picked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(54.dp)
            .clip(shape)
            .background(Q.field)
            .then(if (l.hasContent) Modifier.checker(6f) else Modifier)
            .border(if (picked) 2.5.dp else 1.dp, if (picked) Q.accent else Q.text.copy(alpha = 0.3f), shape),
    ) {
        when {
            l.isGroup -> Icon(Icons.Outlined.Folder, contentDescription = l.props.name, tint = Q.text, modifier = Modifier.size(26.dp))
            l.isAdjustment -> Icon(fxKindIcon(l.props.adjust?.kind ?: FxKind.ADJUST), contentDescription = l.props.name, tint = Q.text, modifier = Modifier.size(26.dp))
            else -> l.thumb?.let { Image(it, contentDescription = l.props.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        if (picked && !l.props.visible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = "Hidden", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun StripButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Q.toolbar.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(24.dp))
    }
}
