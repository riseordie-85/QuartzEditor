package com.tim.quartzeditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollBy
import kotlin.math.abs
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.SideEffect
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Filter
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.ColorLens
import com.tim.quartzeditor.engine.FxKind
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.tim.quartzeditor.engine.Blend
import com.tim.quartzeditor.engine.Layer
import kotlin.math.roundToInt

/** [onPlaceImage] opens the photo picker to add an image as a new layer. */
@Composable
fun LayersPanel(vm: EditorViewModel, onPlaceImage: () -> Unit, fillList: Boolean, modifier: Modifier = Modifier) {
    val d = vm.doc ?: return
    val active = d.active
    var renaming by remember { mutableStateOf<Layer?>(null) }
    // Top settings and bottom buttons stay put; only the list between them scrolls (like Photoshop)
    SqueezeColumn(fillList, modifier.padding(horizontal = 12.dp)) {
        val open = vm.isOpen(PanelSection.LAYERS)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { vm.toggleSection(PanelSection.LAYERS) }
                .padding(vertical = 6.dp),
        ) {
            Spacer(Modifier.width(34.dp))
            Text("Layers", color = Q.text, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            // Several picked: how many, and a tap to go back to one
            val n = vm.selectedLayerIds().size
            if (n > 1) {
                Text(
                    "$n picked  ✕",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Q.accent)
                        .clickable { vm.clearMultiSelect() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Fold Layers" else "Open Layers",
                tint = Q.text,
                modifier = Modifier.size(28.dp),
            )
        }
        if (!open) return@SqueezeColumn

        // Blend mode and opacity
        Row(verticalAlignment = Alignment.CenterVertically) {
            val folder = active?.isGroup == true
            BlendMenu(
                active?.props?.blend ?: Blend.NORMAL, Modifier.weight(1f),
                passThrough = if (folder) active?.props?.passThrough == true else null,
                // Adjustment layers use Android's own blend modes
                exactOnly = active?.isAdjustment == true,
                onPassThrough = { active?.let { l -> vm.setProps(l.id, "Blend mode") { it.copy(passThrough = true) } } },
            ) { b ->
                active?.let { l -> vm.setProps(l.id, "Blend mode") { it.copy(blend = b, passThrough = false) } }
            }
            Spacer(Modifier.width(8.dp))
            PercentChip("Opacity", active?.props?.opacity ?: 1f,
                onLive = { v -> active?.let { l -> vm.setPropsLive(l.id) { it.copy(opacity = v) } } },
                onDone = { vm.commitProps("Opacity") })
        }
        Spacer(Modifier.height(8.dp))
        // Locks and fill
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lock:", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            LockToggle(QIcons.LockAlpha, "Lock transparent pixels", active?.props?.lockAlpha == true) {
                active?.let { l -> vm.setProps(l.id, if (l.props.lockAlpha) "Unlock transparency" else "Lock transparency") { it.copy(lockAlpha = !it.lockAlpha) } }
            }
            Spacer(Modifier.width(4.dp))
            LockToggle(Icons.Outlined.Lock, "Lock layer", active?.props?.locked == true) {
                active?.let { l -> vm.setProps(l.id, if (l.props.locked) "Unlock layer" else "Lock layer") { it.copy(locked = !it.locked) } }
            }
            Spacer(Modifier.weight(1f))
            PercentChip("Fill", active?.props?.fill ?: 1f,
                onLive = { v -> active?.let { l -> vm.setPropsLive(l.id) { it.copy(fill = v) } } },
                onDone = { vm.commitProps("Fill") })
        }
        Spacer(Modifier.height(8.dp))

        // The layers, top of the stack first
        val density = LocalDensity.current
        val rowH = 58.dp
        val rowPx = with(density) { rowH.toPx() }
        var dragId by remember { mutableStateOf<Long?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        // The folder a drop would go into (kept here so the drag's callbacks see the latest)
        val dropFolderRef = remember { mutableStateOf<Long?>(null) }
        var presetFor by remember { mutableStateOf<Layer?>(null) }
        // Rows top first; folders that are folded hide what's in them
        val shown = ArrayList<Layer>()
        fun addRows(parent: Long) {
            for (l in d.children(parent).asReversed()) {
                shown.add(l)
                if (l.isGroup && !l.collapsed) addRows(l.id)
            }
        }
        addRows(0L)
        val dragFrom = shown.indexOfFirst { it.id == dragId }
        // Hovering over the middle of a folder's row: dropping puts the layer into that folder
        val intoFolder: Long? = if (dragFrom < 0) null else {
            val pos = dragFrom + dragOffset / rowPx
            val r = pos.roundToInt().coerceIn(0, shown.lastIndex)
            val target = shown[r]
            val dragged = shown[dragFrom]
            if (r != dragFrom && kotlin.math.abs(pos - r) < 0.3f && target.isGroup && !d.isInside(target, dragged)) target.id else null
        }
        val dragTo = if (dragFrom >= 0 && intoFolder == null) (dragFrom + (dragOffset / rowPx).roundToInt()).coerceIn(0, shown.lastIndex) else dragFrom
        val picked = vm.selectedLayerIds().toSet()
        SideEffect { dropFolderRef.value = intoFolder }
        presetFor?.let { l ->
            SavePresetDialog(onDismiss = { presetFor = null }) { name ->
                presetFor = null
                vm.saveStylePreset(name, l.props.style)
            }
        }

        // Up to four rows while Color or Properties is open; it gives up rows before the buttons
        val listMod = if (fillList) Modifier.layoutId(LIST_ID) else Modifier.layoutId(LIST_ID).heightIn(max = rowH * 4)
        // Only the rows on screen are built (big files have hundreds of layers), and each picture
        // keeps its own place in the list (coming back from a smart layer's tab, it's where it was)
        val listKey = d
        val listState = remember(listKey) {
            val (index, offset) = vm.layerListPlace[d] ?: (0 to 0)
            androidx.compose.foundation.lazy.LazyListState(index, offset)
        }
        androidx.compose.runtime.DisposableEffect(listKey) {
            onDispose { vm.layerListPlace[d] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        }
        // Where the finger is while a row is dragged (list pixels), and where on the row it held it
        var fingerY by remember { mutableFloatStateOf(0f) }
        var grabY by remember { mutableFloatStateOf(0f) }
        var listH by remember { mutableIntStateOf(0) }
        // Scrolling at the edges starts only once the finger has moved there (not just from picking up the top row)
        var edgeArmed by remember { mutableStateOf(false) }
        var startY by remember { mutableFloatStateOf(0f) }
        val handlePx = with(density) { 48.dp.toPx() }
        val edgePx = minOf(with(density) { 40.dp.toPx() }, listH / 4f)
        // Dragging near the top or bottom scrolls the list (faster the closer to the edge)
        androidx.compose.runtime.LaunchedEffect(dragId) {
            if (dragId == null) return@LaunchedEffect
            while (dragId != null) {
                androidx.compose.runtime.withFrameNanos { }
                if (dragId == null) break
                if (!edgeArmed && abs(fingerY - startY) > rowPx / 2f) edgeArmed = true
                if (!edgeArmed) continue
                val y = fingerY
                val speed = when {
                    y < edgePx -> -((edgePx - y) / edgePx).coerceIn(0f, 1.5f) * rowPx * 0.35f
                    y > listH - edgePx -> ((y - (listH - edgePx)) / edgePx).coerceIn(0f, 1.5f) * rowPx * 0.35f
                    else -> 0f
                }
                if (speed != 0f) {
                    val moved = listState.scrollBy(speed)
                    dragOffset += moved
                }
            }
        }
        fun drop() {
            // Keep the list where it is: it would otherwise follow the moved row
            val keepIndex = listState.firstVisibleItemIndex
            val keepOffset = listState.firstVisibleItemScrollOffset
            val steps = (dragOffset / rowPx).roundToInt()
            val id = dragId
            val doc = vm.doc
            val dragged = id?.let { doc?.layer(it) }
            val folder = dropFolderRef.value
            dropFolderRef.value = null
            if (doc != null && dragged != null && folder != null) {
                vm.moveIntoFolder(dragged.id, folder)
            } else if (doc != null && dragged != null && steps != 0) {
                // Rows as they are now
                val rows = ArrayList<Layer>()
                fun add(parent: Long) {
                    for (x in doc.children(parent).asReversed()) {
                        rows.add(x)
                        if (x.isGroup && !x.collapsed) add(x.id)
                    }
                }
                add(0L)
                val from = rows.indexOf(dragged)
                val rest = rows.filter { it !== dragged && !doc.isInside(it, dragged) }
                val inside = rows.size - 1 - rest.size
                val target = (if (steps > 0) (from + steps - inside).coerceAtLeast(from) else from + steps).coerceIn(0, rest.size)
                // The row that ends up just above it decides where it goes
                vm.moveLayerUnder(dragged.id, if (target == 0) null else rest[target - 1].id)
            }
            dragId = null
            dragOffset = 0f
            listState.requestScrollToItem(keepIndex, keepOffset)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(listMod)
                .clip(RoundedCornerShape(10.dp))
                .onSizeChanged { listH = it.height }
                // Dragging a row by its handle (the right end): watched here, ahead of the list's own
                // scrolling, so the drag carries on even when the row scrolls out of view
                .pointerInput(listKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.position.x < size.width - handlePx) return@awaitEachGesture
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { down.position.y >= it.offset && down.position.y < it.offset + it.size }
                            ?: return@awaitEachGesture
                        val id = item.key as? Long ?: return@awaitEachGesture
                        var started = false
                        var cancelled = false
                        var lastY = down.position.y
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null) {
                                    cancelled = true
                                    break
                                }
                                if (!c.pressed) {
                                    // A touch Android called off arrives already used: not a drop
                                    cancelled = c.isConsumed
                                    break
                                }
                                if (!started && abs(c.position.y - down.position.y) >= viewConfiguration.touchSlop) {
                                    started = true
                                    dragId = id
                                    // From where it was picked up, so the row and the drop agree
                                    dragOffset = 0f
                                    lastY = down.position.y
                                    grabY = down.position.y - item.offset
                                    startY = c.position.y
                                    edgeArmed = false
                                }
                                if (started) {
                                    dragOffset += c.position.y - lastY
                                    lastY = c.position.y
                                    fingerY = c.position.y
                                    c.consume()
                                }
                            }
                            if (started && !cancelled) drop()
                        } finally {
                            if (started && dragId != null) {
                                dragId = null
                                dragOffset = 0f
                            }
                        }
                    }
                },
        ) {
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val l = shown[i]
                    val isDragged = l.id == dragId
                    val shift = when {
                        dragFrom < 0 || isDragged -> 0f
                        dragFrom < dragTo && i in (dragFrom + 1)..dragTo -> -rowPx
                        dragFrom > dragTo && i in dragTo until dragFrom -> rowPx
                        else -> 0f
                    }
                    LayerRow(
                        vm = vm,
                        layer = l,
                        selected = l.id == d.activeId,
                        maskSelected = l.id == d.activeId && d.editMask,
                        picked = picked.size > 1 && l.id in picked,
                        dropTarget = l.id == intoFolder,
                        onSavePreset = { presetFor = l },
                        height = rowH,
                        modifier = Modifier
                            // The dragged row follows the finger above the list (drawn below); its place stays empty
                            .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                            .offset { IntOffset(0, shift.roundToInt()) },
                        onRename = { renaming = l },
                        handle = Modifier,
                    )
                }
            }
            // The row being dragged, under the finger
            val dragged = shown.getOrNull(dragFrom)
            if (dragged != null) {
                LayerRow(
                    vm = vm,
                    layer = dragged,
                    selected = dragged.id == d.activeId,
                    maskSelected = false,
                    height = rowH,
                    modifier = Modifier
                        .zIndex(2f)
                        .offset { IntOffset(0, (fingerY - grabY).roundToInt()) }
                        .graphicsLayer { shadowElevation = 12f },
                    onRename = {},
                    handle = Modifier,
                )
            }
        }

        // Bottom bar: mask, new layer, delete, more
        Spacer(Modifier.height(6.dp))
        var more by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { vm.openPopup(PopupKind.STYLE) },
            ) {
                Text("fx", color = Q.text, fontSize = 18.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)
            }
            BarButton(QIcons.Mask, "Add a mask") { vm.addMask() }
            Box {
                var adj by remember { mutableStateOf(false) }
                BarButton(Icons.Outlined.Contrast, "Adjustments") { adj = true }
                PagedDropdownMenu(expanded = adj, onDismissRequest = { adj = false }) {
                    Page("New adjustment layer", Icons.Outlined.Layers) {
                        Label(if (d.selection != null) "Changes what's selected, everything below" else "Changes everything below, without touching pixels")
                        FxKind.entries.forEach { k -> Item(FX_KIND_LABELS.getValue(k), fxKindIcon(k)) { vm.addAdjustmentLayer(k) } }
                    }
                    Page(if (active?.isAdjustment == true) "Edit this adjustment layer" else "On this layer", Icons.Outlined.Tune) {
                        Label(
                            when {
                                active?.isSmart == true -> "Added as a smart filter (stays editable)"
                                d.selection != null -> "Changes this layer's pixels, inside the selection"
                                else -> "Changes this layer's pixels"
                            },
                        )
                        listOf(
                            PopupKind.ADJUSTMENTS, PopupKind.LEVELS, PopupKind.CURVES, PopupKind.HUE_SAT, PopupKind.COLOR_BALANCE,
                            PopupKind.VIBRANCE, PopupKind.BLACK_WHITE, PopupKind.PHOTO_FILTER, PopupKind.GRADIENT_MAP, PopupKind.CHANNEL_MIXER,
                            PopupKind.SELECTIVE_COLOR, PopupKind.COLOR_LOOKUP, PopupKind.POSTERIZE, PopupKind.THRESHOLD, PopupKind.BLUR, PopupKind.FILTERS,
                        ).forEach { k -> Item("${k.title}…") { vm.openPopup(k) } }
                    }
                }
            }
            // With several layers picked, they all go into the new folder
            BarButton(Icons.Outlined.CreateNewFolder, if (vm.isMultiSelecting) "Put picked layers in a folder" else "New folder") {
                if (vm.isMultiSelecting) vm.groupActive() else vm.addFolder()
            }
            BarButton(Icons.Outlined.Add, "New layer") { vm.addLayer() }
            BarButton(Icons.Outlined.DeleteOutline, "Delete layer") { vm.deleteLayer() }
            Box {
                BarButton(Icons.Outlined.MoreVert, "More layer actions") { more = true }
                PagedDropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    Item(if (active?.isGroup == true) "Duplicate folder" else "Duplicate layer") { vm.duplicateLayer() }
                    if (active?.isGroup == true) Item("Merge folder") { vm.mergeFolder() } else Item("Merge down") { vm.mergeDown() }
                    Item("Put in a new folder") { vm.groupActive() }
                    if (active != null && !active.isGroup) Item(if (active.props.clip) "Release clipping mask" else "Clip to layer below") { vm.toggleClip() }
                    Item("Rename layer…") { renaming = active }
                    Divider()
                    Page("Fill and clear") {
                        Item("Fill with foreground color") { vm.fillLayer(vm.foreground) }
                        Item("Fill with pattern") { vm.fillWithPattern() }
                        Item(if (d.editMask) "Clear mask" else "Clear layer") { vm.clearLayer() }
                    }
                    Page("Mask and background") {
                        Item("Remove background") { vm.removeBackground() }
                        Item("Mask from selection", enabled = d.selection != null) { vm.maskFromSelection() }
                    }
                    Page("Convert") {
                        if (active != null && !active.isAdjustment) Item(if (vm.isMultiSelecting) "Convert picked to smart layer" else "Convert to smart layer") { vm.convertToSmart() }
                        if (active?.isSmart == true) Item("Edit smart layer contents") { vm.openSmartContents(active.id) }
                        if (active?.isSmart == true) Item("Replace contents…") { vm.replaceContentsFor = active.id }
                        Item("Rasterize layer", enabled = active?.needsRaster == true) { active?.let { vm.rasterizeLayer(it.id) } }
                        Item("Apply layer style", enabled = canApplyStyle(active)) { vm.applyLayerStyle(active!!.id) }
                    }
                    Divider()
                    Item("Place image as layer…") { onPlaceImage() }
                    Item("Flatten image") { vm.flattenImage() }
                }
            }
        }

        renaming?.let { l ->
            RenameDialog(l.props.name, onDismiss = { renaming = null }) { name ->
                vm.setProps(l.id, "Rename layer") { it.copy(name = name) }
                renaming = null
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(
    vm: EditorViewModel,
    layer: Layer,
    selected: Boolean,
    maskSelected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onRename: () -> Unit,
    handle: Modifier,
    /** Picked along with others (several layers picked). */
    picked: Boolean = false,
    /** A layer being dragged would go into this folder. */
    dropTarget: Boolean = false,
    onSavePreset: () -> Unit = {},
) {
    val p = layer.props
    var menu by remember { mutableStateOf(false) }
    val swipe = with(LocalDensity.current) { 48.dp.toPx() }
    Box {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected || picked) Q.rowSelected else Q.row)
            .then(if (dropTarget) Modifier.border(2.dp, Q.accent, RoundedCornerShape(10.dp)) else Modifier)
            // Swipe right: add to (or take out of) the picked layers
            .pointerInput(layer.id) {
                var total = 0f
                var done = false
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f; done = false },
                    onHorizontalDrag = { change, amount ->
                        total += amount
                        if (!done && total > swipe) {
                            done = true
                            change.consume()
                            vm.toggleMultiSelect(layer.id)
                        }
                    },
                )
            }
            .combinedClickable(
                onClick = { vm.selectLayer(layer.id, mask = false) },
                onLongClick = { menu = true },
                // A smart layer: double-tap edits what's inside it
                onDoubleClick = if (layer.isSmart) ({ vm.openSmartContents(layer.id) }) else null,
            ),
    ) {
        if (picked) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Picked", tint = Q.accent, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clickable { vm.setProps(layer.id, if (p.visible) "Hide layer" else "Show layer") { it.copy(visible = !it.visible) } },
        ) {
            Icon(if (p.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, contentDescription = if (p.visible) "Hide" else "Show", tint = if (p.visible) Q.text else Q.textDim, modifier = Modifier.size(20.dp))
        }
        // Indent what's inside folders
        val depth = vm.doc?.depth(layer) ?: 0
        if (depth > 0) Spacer(Modifier.width((12 * depth).dp))
        // Clipped to the layer below: an arrow pointing down to it (like Photoshop)
        val clipBase = vm.clipBaseOf(layer)
        if (clipBase != null) {
            Icon(Icons.Outlined.SubdirectoryArrowRight, contentDescription = "Clipped to ${clipBase.props.name}", tint = Q.textDim, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
            Spacer(Modifier.width(2.dp))
        }
        when {
            layer.isGroup -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            layer.collapsed = !layer.collapsed
                            vm.doc?.invalidate()
                        },
                ) {
                    Icon(if (layer.collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, contentDescription = if (layer.collapsed) "Open folder" else "Fold folder", tint = Q.text)
                }
                IconThumb(if (layer.collapsed) Icons.Outlined.Folder else Icons.Outlined.FolderOpen, selected && !maskSelected) { vm.selectLayer(layer.id, mask = false) }
            }
            layer.isAdjustment -> {
                val kind = p.adjust?.kind ?: FxKind.ADJUST
                IconThumb(fxKindIcon(kind), selected && !maskSelected) {
                    vm.selectLayer(layer.id, mask = false)
                    vm.openPopup(PopupKind.forKind(kind))
                }
            }
            // Text layers show a T like Photoshop; tapping it on the selected layer edits the text
            layer.isText -> IconThumb(Icons.Outlined.Title, selected && !maskSelected) {
                if (selected && !maskSelected) vm.beginTextEdit(layer) else vm.selectLayer(layer.id, mask = false)
            }
            layer.isSmart -> Box {
                Thumb(layer.thumb, outlined = selected && !maskSelected, onDoubleTap = { vm.openSmartContents(layer.id) }) { vm.selectLayer(layer.id, mask = false) }
                // The smart layer badge
                Icon(
                    Icons.Outlined.FilterNone, contentDescription = "Smart layer (double-tap to edit)", tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(1.dp),
                )
            }
            else -> Thumb(layer.thumb, outlined = selected && !maskSelected) { vm.selectLayer(layer.id, mask = false) }
        }
        if (layer.hasMask) {
            Spacer(Modifier.width(4.dp))
            Box {
                var maskMenu by remember { mutableStateOf(false) }
                // Hold the mask for what you can do with it
                Thumb(layer.maskThumb, outlined = maskSelected, plain = true, onLongPress = { maskMenu = true }) { vm.selectLayer(layer.id, mask = true) }
                if (p.maskDisabled) {
                    Text("✕", color = Color(0xFFE53935), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                }
                DropdownMenu(expanded = maskMenu, onDismissRequest = { maskMenu = false }) {
                    fun item(label: String, enabled: Boolean = true, action: EditorViewModel.() -> Unit) = @Composable {
                        DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = {
                            maskMenu = false
                            // On this layer, still working on its mask (after anything pending is applied)
                            val run = {
                                vm.selectLayer(layer.id, mask = true)
                                vm.action()
                            }
                            if (!vm.pendingFirst(run)) run()
                        })
                    }
                    DropdownMenuItem(text = { Text("Mask as selection") }, onClick = {
                        maskMenu = false
                        vm.selectionFromMask(layer.id)
                    })
                    if (vm.doc?.selection != null) item("Replace with selection") { maskFromSelection() }()
                    HorizontalDivider()
                    item("Invert mask") { invertMask() }()
                    item(if (p.maskDisabled) "Enable mask" else "Disable mask") { toggleMaskEnabled() }()
                    item("Clear mask", enabled = layer.mask?.isEmpty == false) { clearMask() }()
                    HorizontalDivider()
                    item("Apply mask") { applyMask() }()
                    item("Delete mask") { deleteMask() }()
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = if (p.visible) Q.text else Q.textDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val notes = buildList {
                if (layer.isShape) add("Shape")
                if (layer.isSmart) {
                    val n = vm.doc?.let { com.tim.quartzeditor.engine.linkedCopies(it, layer).size } ?: 0
                    if (n > 0) add("Linked to $n other${if (n > 1) "s" else ""}")
                    p.smart?.let { sp ->
                        if (sp.warp != null) add("Warped")
                        val on = sp.filters.count { it.enabled }
                        if (sp.filters.isNotEmpty()) add(if (on == sp.filters.size) "${sp.filters.size} smart filter${if (sp.filters.size > 1) "s" else ""}" else "Smart filters ($on of ${sp.filters.size} on)")
                    }
                }
                if (clipBase != null) add("Clipped to ${clipBase.props.name}")
                if (p.blend != Blend.NORMAL && !(layer.isGroup && p.passThrough)) add(p.blend.label)
                if (layer.isGroup && !p.passThrough && p.blend == Blend.NORMAL) add("Normal")
                if (p.opacity < 0.995f) add("${(p.opacity * 100).roundToInt()}%")
                if (p.style.any) add("fx")
                if (p.locked) add("Locked")
                else if (p.lockAlpha) add("Transparency locked")
            }
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = Q.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.locked) Icon(Icons.Outlined.Lock, contentDescription = "Locked", tint = Q.textDim, modifier = Modifier.size(16.dp))
        Box(contentAlignment = Alignment.Center, modifier = handle.size(44.dp)) {
            Icon(Icons.Outlined.DragHandle, contentDescription = "Drag to reorder", tint = Q.text)
        }
    }
    // Long press: this layer's quick actions
    PagedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        val ids = if (vm.isMultiSelecting && layer.id in vm.selectedLayerIds()) vm.selectedLayerIds() else listOf(layer.id)
        Item("Rename…") { onRename() }
        Item("Duplicate") { vm.onLayer(layer.id) { duplicateLayer() } }
        if (!layer.isAdjustment) Item("Select pixels") { vm.selectLayerPixels(layer.id) }
        if (ids.size > 1) Item("Merge ${ids.size} layers") { vm.mergeDown() }
        else Item(if (layer.isGroup) "Merge folder" else "Merge down") { vm.onLayer(layer.id) { mergeDown() } }
        if (!layer.isGroup) Item(if (p.clip) "Release clipping mask" else "Clip to layer below") { vm.onLayer(layer.id) { toggleClip() } }
        Divider()
        Page("Layer style") {
            Item("Copy layer style", enabled = layer.canHaveStyle) { vm.copyLayerStyle(layer.id) }
            Item(if (ids.size > 1) "Paste layer style (${ids.size} layers)" else "Paste layer style", enabled = vm.copiedStyle != null) { vm.pasteLayerStyle(ids) }
            Item("Save layer style as preset…", enabled = p.style.any) { onSavePreset() }
            Divider()
            Item("Apply layer style", enabled = canApplyStyle(layer)) { vm.applyLayerStyle(layer.id) }
            Item("Clear layer style", enabled = p.style.any) { vm.applyStyleTo(ids, "Clear layer style", com.tim.quartzeditor.engine.LayerStyle()) }
        }
        val filters = p.smart?.filters.orEmpty()
        if (filters.isNotEmpty()) Page("Smart filters (${filters.size})") {
            // Last applied first, like Photoshop's list
            for (i in filters.indices.reversed()) {
                val f = filters[i]
                Page("${i + 1}. ${f.name}${if (!f.enabled) " (off)" else ""}") {
                    Item("Edit…") { vm.editSmartFilter(layer.id, i) }
                    Item(if (f.enabled) "Hide" else "Show") { vm.toggleSmartFilter(layer.id, i) }
                    Divider()
                    Label("Strength")
                    for (o in listOf(1f, 0.75f, 0.5f, 0.25f)) {
                        Item("${(o * 100).roundToInt()}%", checked = abs(f.opacity - o) < 0.01f) { vm.setSmartFilterOpacity(layer.id, i, o) }
                    }
                    Divider()
                    Item("Delete") { vm.deleteSmartFilter(layer.id, i) }
                }
            }
            Divider()
            Item(if (filters.none { it.enabled }) "Show all" else "Hide all") { vm.toggleSmartFilter(layer.id, -1) }
            Item("Delete all") { vm.deleteSmartFilter(layer.id, -1) }
        }
        if (layer.isSmart || !layer.isAdjustment || layer.needsRaster) Page("Convert") {
            if (layer.isSmart) Item("Edit contents") { vm.openSmartContents(layer.id) }
            if (layer.isSmart) Item("Replace contents…") { vm.replaceContentsFor = layer.id }
            if (p.smart?.warp != null) Item("Remove warp") { vm.removeSmartWarp(layer.id) }
            if (layer.isSmart && vm.doc?.let { com.tim.quartzeditor.engine.linkedCopies(it, layer).isNotEmpty() } == true) {
                Item("Unlink from copies") { vm.unlinkSmart(layer.id) }
            }
            if (!layer.isAdjustment) Item(if (ids.size > 1) "Convert ${ids.size} to smart layer" else "Convert to smart layer") {
                if (ids.size > 1) vm.convertToSmart() else vm.onLayer(layer.id) { convertToSmart() }
            }
            if (layer.needsRaster) Item("Rasterize layer") { vm.rasterizeLayer(layer.id) }
        }
    }
    }
}

private const val LIST_ID = "list"

private val FX_KIND_LABELS = mapOf(
    FxKind.ADJUST to "Adjustments",
    FxKind.LEVELS to "Levels",
    FxKind.CURVES to "Curves",
    FxKind.BLUR to "Gaussian Blur",
    FxKind.HUE_SAT to "Hue/Saturation",
    FxKind.COLOR_BALANCE to "Color Balance",
    FxKind.BLACK_WHITE to "Black & White",
    FxKind.PHOTO_FILTER to "Photo Filter",
    FxKind.GRADIENT_MAP to "Gradient Map",
    FxKind.VIBRANCE to "Vibrance",
    FxKind.CHANNEL_MIXER to "Channel Mixer",
    FxKind.SELECTIVE_COLOR to "Selective Color",
    FxKind.COLOR_LOOKUP to "Color Lookup",
    FxKind.POSTERIZE to "Posterize",
    FxKind.THRESHOLD to "Threshold",
)

internal fun fxKindIcon(k: FxKind): ImageVector = when (k) {
    FxKind.ADJUST -> Icons.Outlined.Tune
    FxKind.LEVELS -> Icons.Outlined.BarChart
    FxKind.CURVES -> Icons.Outlined.ShowChart
    FxKind.BLUR -> Icons.Outlined.BlurOn
    FxKind.HUE_SAT -> Icons.Outlined.ColorLens
    FxKind.COLOR_BALANCE -> Icons.Outlined.Balance
    FxKind.BLACK_WHITE -> Icons.Outlined.FilterBAndW
    FxKind.PHOTO_FILTER -> Icons.Outlined.PhotoFilter
    FxKind.GRADIENT_MAP -> Icons.Outlined.Gradient
    FxKind.VIBRANCE -> Icons.Outlined.AutoAwesome
    FxKind.CHANNEL_MIXER -> Icons.Outlined.Tune
    FxKind.SELECTIVE_COLOR -> Icons.Outlined.Palette
    FxKind.COLOR_LOOKUP -> Icons.Outlined.Filter
    FxKind.POSTERIZE -> Icons.Outlined.Layers
    FxKind.THRESHOLD -> Icons.Outlined.Contrast
}

/**
 * A column whose child tagged [LIST_ID] gets the height the others leave: all of it with [fill],
 * otherwise up to its own height. The other children always get their full height, so the
 * buttons under the list never get pushed off.
 */
@Composable
private fun SqueezeColumn(fill: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, c ->
        val bounded = c.hasBoundedHeight
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var used = 0
        measurables.forEachIndexed { i, m ->
            if (m.layoutId != LIST_ID) {
                val p = m.measure(Constraints(maxWidth = c.maxWidth))
                placeables[i] = p
                used += p.height
            }
        }
        // Without a height limit there's nothing to fill, and the list gets its own height (at most 4 rows)
        val left = if (bounded) (c.maxHeight - used).coerceAtLeast(0) else Constraints.Infinity
        measurables.forEachIndexed { i, m ->
            if (m.layoutId == LIST_ID) {
                val max = if (bounded) left else 1_000_000
                val p = m.measure(Constraints(maxWidth = c.maxWidth, minHeight = if (fill && bounded) left else 0, maxHeight = max))
                placeables[i] = p
                used += p.height
            }
        }
        val width = if (c.hasBoundedWidth) c.maxWidth else placeables.maxOf { it?.width ?: 0 }
        val height = (if (fill && bounded) c.maxHeight else used).coerceIn(c.minHeight, if (bounded) c.maxHeight else Int.MAX_VALUE)
        layout(width, height) {
            var y = 0
            val last = placeables.lastOrNull()
            for (p in placeables) {
                if (p == null) continue
                // Very short on room: the last row (the buttons) stays at the bottom, over the rest
                val at = if (p === last && y + p.height > height) (height - p.height).coerceAtLeast(0) else y
                p.placeRelative(0, at)
                y += p.height
            }
        }
    }
}

/** Folder or adjustment icon in place of a picture. */
@Composable
private fun IconThumb(icon: ImageVector, outlined: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .border(if (outlined) 2.dp else 1.dp, if (outlined) Q.text else Q.text.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(Q.field)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = Q.text, modifier = Modifier.size(24.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Thumb(img: androidx.compose.ui.graphics.ImageBitmap?, outlined: Boolean, plain: Boolean = false, onDoubleTap: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .border(if (outlined) 2.dp else 1.dp, if (outlined) Q.text else Q.text.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
            .padding(2.dp)
            .clip(RoundedCornerShape(3.dp))
            .then(if (plain) Modifier.background(Color.White) else Modifier.checker(6f))
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleTap, onLongClick = onLongPress),
    ) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun LockToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Q.accent else Q.field)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Q.text, modifier = Modifier.size(18.dp))
    }
}

/**
 * [passThrough]: for a folder, whether it's Pass Through (offered first, as in Photoshop); null for
 * other layers.
 */
@Composable
internal fun BlendMenu(current: Blend, modifier: Modifier, passThrough: Boolean? = null, onPassThrough: () -> Unit = {}, exactOnly: Boolean = false, onPick: (Blend) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Q.field)
                .clickable { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(if (passThrough == true) "Pass Through" else current.label, color = Q.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Blend mode", tint = Q.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (passThrough != null) {
                DropdownMenuItem(
                    text = { Text("Pass Through") },
                    leadingIcon = { if (passThrough) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onPassThrough()
                    },
                )
                HorizontalDivider()
            }
            var lastGroup = 0
            Blend.entries.forEach { b ->
                if (exactOnly && !b.exact && b != current) return@forEach
                if (b.group != lastGroup) {
                    HorizontalDivider()
                    lastGroup = b.group
                }
                DropdownMenuItem(
                    text = { Text(b.label) },
                    leadingIcon = { if (b == current && passThrough != true) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onPick(b)
                    },
                )
            }
        }
    }
}

/** "Opacity: 100% ▾" — tap for a slider. */
@Composable
private fun PercentChip(label: String, value: Float, onLive: (Float) -> Unit, onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text("$label:", color = Q.textDim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "${(value * 100).roundToInt()}%",
                color = Q.text,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Q.field)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Q.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(220.dp).padding(horizontal = 14.dp)) {
                PanelSlider(label, value, 0f..1f, "${(value * 100).roundToInt()}%", onChange = onLive, onDone = onDone)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.25f, 0.5f, 0.75f, 1f).forEach { v ->
                        SmallButton("${(v * 100).roundToInt()}%") {
                            onLive(v)
                            onDone()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun RenameDialog(current: String, title: String = "Rename layer", onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.replace("/", "") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onRename(text.trim()) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
