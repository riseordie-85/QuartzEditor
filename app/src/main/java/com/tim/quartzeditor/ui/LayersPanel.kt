package com.tim.quartzeditor.ui

import androidx.compose.foundation.Image
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
            BlendMenu(active?.props?.blend ?: Blend.NORMAL, Modifier.weight(1f)) { b ->
                active?.let { l -> vm.setProps(l.id, "Blend mode") { it.copy(blend = b) } }
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
        Column(
            Modifier
                .fillMaxWidth()
                .then(listMod)
                .clip(RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState())
        ) {
            shown.forEachIndexed { i, l ->
                val isDragged = l.id == dragId
                val shift = when {
                    dragFrom < 0 || isDragged -> 0f
                    dragFrom < dragTo && i in (dragFrom + 1)..dragTo -> -rowPx
                    dragFrom > dragTo && i in dragTo until dragFrom -> rowPx
                    else -> 0f
                }
                val y = if (isDragged) dragOffset else shift
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
                        .zIndex(if (isDragged) 1f else 0f)
                        .offset { IntOffset(0, y.roundToInt()) },
                    onRename = { renaming = l },
                    handle = Modifier.pointerInput(l.id) {
                        detectDragGestures(
                            onDragStart = {
                                dragId = l.id
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                val steps = (dragOffset / rowPx).roundToInt()
                                val id = dragId
                                val doc = vm.doc
                                val dragged = id?.let { doc?.layer(it) }
                                val folder = dropFolderRef.value
                                dropFolderRef.value = null
                                if (doc != null && dragged != null && folder != null) {
                                    vm.moveIntoFolder(dragged.id, folder)
                                } else if (doc != null && dragged != null && steps != 0) {
                                    // Rows as they are now (this lambda may be older than the panel)
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
                            },
                            onDragCancel = {
                                dragId = null
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                        )
                    },
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
                DropdownMenu(expanded = adj, onDismissRequest = { adj = false }) {
                    Text("New adjustment layer", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    FxKind.entries.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(FX_KIND_LABELS.getValue(k)) },
                            leadingIcon = { Icon(fxKindIcon(k), contentDescription = null) },
                            onClick = { adj = false; vm.addAdjustmentLayer(k) },
                        )
                    }
                    HorizontalDivider()
                    Text(if (active?.isAdjustment == true) "Edit this adjustment layer" else "On this layer", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    listOf(PopupKind.ADJUSTMENTS, PopupKind.LEVELS, PopupKind.CURVES, PopupKind.BLUR, PopupKind.FILTERS).forEach { k ->
                        DropdownMenuItem(text = { Text("${k.title}…") }, onClick = { adj = false; vm.openPopup(k) })
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
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    fun item(label: String, action: () -> Unit) = @Composable {
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            more = false
                            action()
                        })
                    }
                    item(if (active?.isGroup == true) "Duplicate folder" else "Duplicate layer") { vm.duplicateLayer() }()
                    if (active?.isGroup == true) item("Merge folder") { vm.mergeFolder() }() else item("Merge down") { vm.mergeDown() }()
                    item("Put in a new folder") { vm.groupActive() }()
                    if (active != null && !active.isGroup) item(if (active.props.clip) "Release clipping mask" else "Clip to layer below") { vm.toggleClip() }()
                    if (active?.needsRaster == true) item("Rasterize layer") { vm.rasterizeLayer(active.id) }()
                    if (active?.isSmart == true) item("Edit smart layer contents") { vm.openSmartContents(active.id) }()
                    if (active != null && !active.isAdjustment) item(if (vm.isMultiSelecting) "Convert picked to smart layer" else "Convert to smart layer") { vm.convertToSmart() }()
                    if (canApplyStyle(active)) item("Apply layer style") { vm.applyLayerStyle(active!!.id) }()
                    item("Flatten image") { vm.flattenImage() }()
                    HorizontalDivider()
                    item("Place image as layer…") { onPlaceImage() }()
                    item("Remove background") { vm.removeBackground() }()
                    if (d.selection != null) item("Mask from selection") { vm.maskFromSelection() }()
                    item("Fill with foreground color") { vm.fillLayer(vm.foreground) }()
                    item("Fill with pattern") { vm.fillWithPattern() }()
                    item(if (d.editMask) "Clear mask" else "Clear layer") { vm.clearLayer() }()
                    if (active?.hasMask == true) {
                        HorizontalDivider()
                        item(if (active.props.maskDisabled) "Enable mask" else "Disable mask") { vm.toggleMaskEnabled() }()
                        item("Invert mask") { vm.invertMask() }()
                        item("Apply mask") { vm.applyMask() }()
                        item("Delete mask") { vm.deleteMask() }()
                    }
                    HorizontalDivider()
                    item("Rename layer…") { renaming = active }()
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
                Thumb(layer.maskThumb, outlined = maskSelected, plain = true) { vm.selectLayer(layer.id, mask = true) }
                if (p.maskDisabled) {
                    Text("✕", color = Color(0xFFE53935), fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = if (p.visible) Q.text else Q.textDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val notes = buildList {
                if (layer.isShape) add("Shape")
                if (clipBase != null) add("Clipped to ${clipBase.props.name}")
                if (p.blend != Blend.NORMAL) add(p.blend.label)
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
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) = @Composable {
            DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = {
                menu = false
                action()
            })
        }
        val ids = if (vm.isMultiSelecting && layer.id in vm.selectedLayerIds()) vm.selectedLayerIds() else listOf(layer.id)
        item("Rename…") { onRename() }()
        HorizontalDivider()
        item("Copy layer style", enabled = layer.hasContent) { vm.copyLayerStyle(layer.id) }()
        item(if (ids.size > 1) "Paste layer style (${ids.size} layers)" else "Paste layer style", enabled = vm.copiedStyle != null) { vm.pasteLayerStyle(ids) }()
        item("Save layer style as preset…", enabled = p.style.any) { onSavePreset() }()
        item("Apply layer style", enabled = canApplyStyle(layer)) { vm.applyLayerStyle(layer.id) }()
        item("Clear layer style", enabled = p.style.any) { vm.applyStyleTo(ids, "Clear layer style", com.tim.quartzeditor.engine.LayerStyle()) }()
        HorizontalDivider()
        if (ids.size > 1) {
            item("Merge ${ids.size} layers") { vm.mergeDown() }()
        } else {
            item(if (layer.isGroup) "Merge folder" else "Merge down") {
                vm.onLayer(layer.id) { mergeDown() }
            }()
        }
        item("Duplicate") {
            vm.onLayer(layer.id) { duplicateLayer() }
        }()
        if (!layer.isGroup) item(if (p.clip) "Release clipping mask" else "Clip to layer below") {
            vm.onLayer(layer.id) { toggleClip() }
        }()
        HorizontalDivider()
        if (layer.isSmart) item("Edit contents") { vm.openSmartContents(layer.id) }()
        if (!layer.isAdjustment) item(if (ids.size > 1) "Convert ${ids.size} to smart layer" else "Convert to smart layer") {
            if (ids.size > 1) vm.convertToSmart() else vm.onLayer(layer.id) { convertToSmart() }
        }()
        if (layer.needsRaster) item("Rasterize layer") { vm.rasterizeLayer(layer.id) }()
    }
    }
}

private const val LIST_ID = "list"

private val FX_KIND_LABELS = mapOf(
    FxKind.ADJUST to "Adjustments",
    FxKind.LEVELS to "Levels",
    FxKind.CURVES to "Curves",
    FxKind.BLUR to "Gaussian Blur",
)

internal fun fxKindIcon(k: FxKind): ImageVector = when (k) {
    FxKind.ADJUST -> Icons.Outlined.Tune
    FxKind.LEVELS -> Icons.Outlined.BarChart
    FxKind.CURVES -> Icons.Outlined.ShowChart
    FxKind.BLUR -> Icons.Outlined.BlurOn
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
private fun Thumb(img: androidx.compose.ui.graphics.ImageBitmap?, outlined: Boolean, plain: Boolean = false, onDoubleTap: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .border(if (outlined) 2.dp else 1.dp, if (outlined) Q.text else Q.text.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
            .padding(2.dp)
            .clip(RoundedCornerShape(3.dp))
            .then(if (plain) Modifier.background(Color.White) else Modifier.checker(6f))
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleTap),
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

@Composable
private fun BlendMenu(current: Blend, modifier: Modifier, onPick: (Blend) -> Unit) {
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
            Text(current.label, color = Q.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Blend mode", tint = Q.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = 0
            Blend.entries.forEach { b ->
                if (b.group != lastGroup) {
                    HorizontalDivider()
                    lastGroup = b.group
                }
                DropdownMenuItem(
                    text = { Text(b.label) },
                    leadingIcon = { if (b == current) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
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
