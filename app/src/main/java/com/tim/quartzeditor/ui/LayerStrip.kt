package com.tim.quartzeditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tim.quartzeditor.engine.FxKind
import com.tim.quartzeditor.engine.Layer

/**
 * The Layers panel folded away: a short column of layer pictures at the edge of the picture (like
 * Photoshop on phones). Three show at a time; scroll for the rest. Tap one to pick it, hold one to
 * hide or show it, + adds a layer. (The arrow at the bottom corner opens the full panels.)
 */
@Composable
fun LayerStrip(vm: EditorViewModel, onPlaceImage: () -> Unit, modifier: Modifier = Modifier) {
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
    val list = rememberLazyListState()
    // Keep the picked layer in view (centered when there's room)
    LaunchedEffect(shown[at].id) {
        val first = list.firstVisibleItemIndex
        if (at < first || at > first + 2) list.animateScrollToItem((at - 1).coerceAtLeast(0))
    }

    Column(modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Q.toolbar.copy(alpha = 0.75f))
                .padding(vertical = 5.dp),
        ) {
            LazyColumn(
                state = list,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.width(64.dp).height(CARD * 3 + 6.dp * 2),
            ) {
                items(shown, key = { it.id }) { l ->
                    Card(
                        vm, l, picked = l === shown[at],
                        modifier = Modifier
                            .animateItem()
                            .pointerInput(l.id) {
                                detectTapGestures(
                                    onTap = { if (l.id != vm.doc?.activeId) vm.selectLayer(l.id) },
                                    onLongPress = {
                                        val wasVisible = l.props.visible
                                        vm.setProps(l.id, if (wasVisible) "Hide layer" else "Show layer") { it.copy(visible = !wasVisible) }
                                        vm.toast(if (wasVisible) "\"${l.props.name}\" hidden" else "\"${l.props.name}\" shown")
                                    },
                                )
                            },
                    )
                }
            }
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

private val CARD = 54.dp

/** One layer's card in the stack. */
@Composable
private fun Card(vm: EditorViewModel, l: Layer, picked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(CARD)
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
        if (!l.props.visible) {
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
