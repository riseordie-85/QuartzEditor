package com.tim.quartzeditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tim.quartzeditor.engine.FxKind
import kotlin.math.roundToInt

// What the menus hold, shared by the menu bar (big screens) and the ☰ menu (its pages). Each is a
// list of items for a PagedDropdownMenu; dialogs are opened through [MenuActions].

/** The dialogs and pickers the workspace opens for the menus. */
class MenuActions(
    val rename: () -> Unit,
    val export: () -> Unit,
    val saveProject: () -> Unit,
    val placeImage: () -> Unit,
    val discard: () -> Unit,
    val newDoc: () -> Unit,
    val openFile: () -> Unit,
    val openImage: () -> Unit,
    val imageSize: () -> Unit,
    val upscale: () -> Unit,
    val grid: () -> Unit,
    val newGuide: () -> Unit,
    val editGuides: () -> Unit,
    val settings: () -> Unit,
    val comfy: () -> Unit,
    val renameLayer: () -> Unit,
)

/** The app itself: home, quick export, settings. */
@Composable
fun PagedMenuScope.AppMenuItems(vm: EditorViewModel, a: MenuActions) {
    Item("Home (your files stay open)", Icons.Outlined.Home) { vm.openHome() }
    Item("Quick export (.png)", Icons.Outlined.Image) { vm.quickExport() }
    Divider()
    Item("Settings…", Icons.Outlined.Settings) { a.settings() }
}

@Composable
fun PagedMenuScope.FileMenuItems(vm: EditorViewModel, a: MenuActions) {
    // (What's opened or made joins the open files, in a tab of its own)
    Item("New…", Icons.Outlined.Add) { a.newDoc() }
    Item("Open file…", Icons.Outlined.FolderOpen) { a.openFile() }
    Item("Open image…", Icons.Outlined.Image) { a.openImage() }
    Divider()
    Item("Save project", Icons.Outlined.Save, enabled = vm.hasUnsavedChanges) { vm.saveToProjects(quiet = false) }
    Item("Export image…", Icons.Outlined.IosShare) { a.export() }
    BigItem("Quick export (.png)", Icons.Outlined.Bolt) { vm.quickExport() }
    Divider()
    Item("Rename image…") { a.rename() }
    Item("Save a copy as a project file…") { a.saveProject() }
    Divider()
    Item("Close project", Icons.Outlined.Close) { vm.requestClose() }
}

@Composable
fun PagedMenuScope.EditMenuItems(vm: EditorViewModel, a: MenuActions) {
    val h = vm.history
    @Suppress("UNUSED_VARIABLE") val v = h.version
    Item("Undo", Icons.AutoMirrored.Outlined.Undo, enabled = h.canUndo || vm.pendingFx != null || vm.enhance != null || vm.textEdit != null || vm.penPoints.isNotEmpty()) { vm.undo() }
    Item("Redo", Icons.AutoMirrored.Outlined.Redo, enabled = h.canRedo) { vm.redo() }
    Divider()
    Item("Cut", Icons.Outlined.ContentCut) { vm.cutPixels() }
    Item("Copy", Icons.Outlined.ContentCopy) { vm.copyPixels() }
    Item("Copy merged (everything you see)", Icons.Outlined.Layers) { vm.copyPixels(merged = true) }
    Item("Paste", Icons.Outlined.ContentPaste, enabled = vm.clip != null || vm.otherAppHasImage()) { vm.pastePixels() }
    if (vm.clip != null && vm.otherAppHasImage()) Item("Paste from another app", Icons.Outlined.Image) { vm.pasteFromOtherApp() }
    Divider()
    Item("Fill with foreground color") { vm.fillWithColor(vm.foreground) }
    Item("Fill with pattern") { vm.fillWithPattern() }
    Item("Free transform") { vm.onToolButton(Tool.TRANSFORM) }
    Divider()
    Item("Settings…", Icons.Outlined.Settings) { a.settings() }
}

@Composable
fun PagedMenuScope.ImageMenuItems(vm: EditorViewModel, a: MenuActions, withEnhance: Boolean = true) {
    Label("${vm.doc?.width ?: 0} × ${vm.doc?.height ?: 0} px")
    Item("Image size…") { a.imageSize() }
    Item("Crop") { vm.onToolButton(Tool.CROP) }
    Divider()
    Item("Rotate 90° right") { vm.rotateImage(clockwise = true) }
    Item("Rotate 90° left") { vm.rotateImage(clockwise = false) }
    Item("Flip horizontally") { vm.flipImage(horizontal = true) }
    Item("Flip vertically") { vm.flipImage(horizontal = false) }
    Divider()
    // (Pages are told apart by their names: a menu with an Enhance page of its own leaves this one out)
    if (withEnhance) {
        Page("Enhance") { EnhanceMenuItems(vm, a) }
        Divider()
    }
    Item("Flatten image") { vm.flattenImage() }
}

@Composable
fun PagedMenuScope.EnhanceMenuItems(vm: EditorViewModel, a: MenuActions) {
    Item("Auto enhance") { vm.autoEnhance() }
    Item("Upscale…") { a.upscale() }
    Divider()
    Item("Denoise…") { vm.startEnhance(EnhanceKind.DENOISE) }
    Item("Sharpen…") { vm.startEnhance(EnhanceKind.SHARPEN) }
    Divider()
    Label("From depth (on-device AI)")
    Item("Depth blur…") { vm.startEnhance(EnhanceKind.DEPTH_BLUR) }
    Item("Depth fog…") { vm.startEnhance(EnhanceKind.FOG) }
    Item("Relight…") { vm.startEnhance(EnhanceKind.RELIGHT) }
    Item("Replace sky…") { vm.startEnhance(EnhanceKind.SKY) }
}

@Composable
fun PagedMenuScope.LayerMenuItems(vm: EditorViewModel, a: MenuActions) {
    val d = vm.doc ?: return
    val active = d.active
    Item("New layer") { vm.addLayer() }
    Item("New folder") { vm.addFolder() }
    Page("New adjustment layer") {
        FxKind.entries.forEach { k -> Item(FX_KIND_LABELS.getValue(k), fxKindIcon(k)) { vm.addAdjustmentLayer(k) } }
    }
    Divider()
    Item(if (active?.isGroup == true) "Duplicate folder" else "Duplicate layer") { vm.duplicateLayer() }
    Item("Delete layer") { vm.deleteLayer() }
    Item("Rename layer…", enabled = active != null) { a.renameLayer() }
    Divider()
    if (active?.isGroup == true) Item("Merge folder") { vm.mergeFolder() } else Item("Merge down") { vm.mergeDown() }
    Item("Put in a new folder") { vm.groupActive() }
    if (active != null && !active.isGroup) Item(if (active.props.clip) "Release clipping mask" else "Clip to layer below") { vm.toggleClip() }
    Divider()
    Item("Layer style…") { vm.openPopup(PopupKind.STYLE) }
    Page("Mask") {
        Item("Add a mask") { vm.addMask() }
        Item("Mask from selection", enabled = d.selection != null) { vm.maskFromSelection() }
        Item("Remove background") { vm.removeBackground() }
    }
    Page("Fill and clear") {
        Item("Fill with foreground color") { vm.fillLayer(vm.foreground) }
        Item("Fill with pattern") { vm.fillWithPattern() }
        Item(if (d.editMask) "Clear mask" else "Clear layer") { vm.clearLayer() }
    }
    Page("Convert") {
        if (active != null && !active.isAdjustment) Item(if (vm.isMultiSelecting) "Convert picked to smart layer" else "Convert to smart layer") { vm.convertToSmart() }
        if (active?.isSmart == true) Item("Edit smart layer contents") { vm.openSmartContents(active.id) }
        if (active?.isSmart == true) Item("Replace contents…") { vm.replaceContentsFor = active.id }
        Item("Rasterize layer", enabled = active?.needsRaster == true) { active?.let { vm.rasterizeLayer(it.id) } }
        Item("Apply layer style", enabled = canApplyStyle(active)) { vm.applyLayerStyle(active!!.id) }
    }
    Divider()
    Item("Place image as layer…") { a.placeImage() }
    Item("Flatten image") { vm.flattenImage() }
}

@Composable
fun PagedMenuScope.SelectMenuItems(vm: EditorViewModel) {
    val d = vm.doc ?: return
    val sel = d.selection?.isEmpty == false
    Item("Select all") { vm.selectAll() }
    Item("Deselect", enabled = sel) { vm.deselect() }
    Item("Invert", enabled = sel) { vm.invertSelection() }
    Divider()
    Item("Select subject", Icons.Outlined.PersonOutline) { vm.selectSubject(background = false) }
    Item("Select background", Icons.Outlined.Landscape) { vm.selectSubject(background = true) }
    Item("Select sky", Icons.Outlined.Cloud) { vm.selectSky() }
    Item("Color range…", Icons.Outlined.Palette) { vm.openColorRange() }
    Item("Layer's pixels", enabled = d.active?.let { !it.isAdjustment && !it.isGroup } == true) { d.active?.let { vm.selectLayerPixels(it.id, vm.selectMode) } }
    Page("Modify edge") {
        Label("Feather (soft edge)")
        Custom {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 2, 5, 10, 25, 50).forEach { f ->
                    SmallButton("$f", primary = (d.selection?.feather?.roundToInt() ?: -1) == f) {
                        dismiss()
                        vm.featherSelection(f.toFloat())
                    }
                }
            }
        }
        Label("Expand / shrink (pixels)")
        Custom {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-10, -5, -2, 2, 5, 10).forEach { g ->
                    SmallButton(if (g > 0) "+$g" else "$g") {
                        dismiss()
                        vm.growSelection(g.toFloat())
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    Divider()
    Item("Copy to new layer", Icons.Outlined.ContentCopy, enabled = sel) { vm.selectionToLayer(cut = false) }
    Item("Cut to new layer", Icons.Outlined.ContentCut, enabled = sel) { vm.selectionToLayer(cut = true) }
    Item("Shape from selection", enabled = sel) { vm.selectionToShape() }
    Item("Delete selected pixels", enabled = sel) { vm.clearSelected() }
    Item("AI Remove selected", Icons.Outlined.AutoFixNormal, enabled = sel) { vm.removeSelected() }
    Divider()
    Item("Mask from selection", enabled = sel) { vm.maskFromSelection() }
    Item("Define pattern from selection", enabled = sel) { vm.definePattern() }
    Item("Crop to selection", enabled = sel) { vm.cropToSelection() }
}

@Composable
fun PagedMenuScope.FilterMenuItems(vm: EditorViewModel, a: MenuActions) {
    val active = vm.doc?.active
    // ComfyUI first (it's the one used most)
    if (vm.online) {
        Item("ComfyUI…", Icons.Outlined.Cloud) { a.comfy() }
        Divider()
    }
    Label(
        when {
            active?.isSmart == true -> "Added as a smart filter (stays editable)"
            vm.doc?.selection != null -> "Changes this layer's pixels, inside the selection"
            else -> "Changes this layer's pixels"
        },
    )
    listOf(
        PopupKind.ADJUSTMENTS, PopupKind.LEVELS, PopupKind.CURVES, PopupKind.HUE_SAT, PopupKind.COLOR_BALANCE,
        PopupKind.VIBRANCE, PopupKind.BLACK_WHITE, PopupKind.PHOTO_FILTER, PopupKind.GRADIENT_MAP, PopupKind.CHANNEL_MIXER,
        PopupKind.SELECTIVE_COLOR, PopupKind.COLOR_LOOKUP, PopupKind.POSTERIZE, PopupKind.THRESHOLD,
    ).forEach { k -> Item("${k.title}…") { vm.openPopup(k) } }
    Divider()
    Item("${PopupKind.BLUR.title}…") { vm.openPopup(PopupKind.BLUR) }
    Item("${PopupKind.FILTERS.title}…", Icons.Outlined.Tune) { vm.openPopup(PopupKind.FILTERS) }
    Page("Enhance") { EnhanceMenuItems(vm, a) }
}

@Composable
fun PagedMenuScope.ViewMenuItems(vm: EditorViewModel, a: MenuActions) {
    Item("Fit on screen, straight") { vm.fit() }
    Item("Actual pixels (100%)") { vm.zoomTo(1f) }
    Item("Zoom in") { vm.zoomTo(vm.scale * 1.5f) }
    Item("Zoom out") { vm.zoomTo(vm.scale / 1.5f) }
    Divider()
    if (vm.online) {
        Item("ComfyUI bar", checked = vm.showComfyBar) { vm.showComfyBar = !vm.showComfyBar }
    }
    Item("Lock canvas turning", checked = vm.rotationLocked) { vm.toggleRotationLock() }
    if (vm.uiLayout != UiLayout.CLASSIC) Item("Brush sliders beside the tools (menu bar layout)", checked = vm.sideSliders) { vm.toggleSideSliders() }
    if (vm.normalizedRotation() != 0f) Item("Straighten canvas") { vm.straightenView() }
    if (vm.penSeen) Item("Fingers paint (not just the pen)", checked = !vm.penFingersNavigate) { vm.toggleFingerPainting() }
    Divider()
    Item("Snap to center, edges and guides", checked = vm.snapOn) { vm.toggleSnap() }
    Item("Checkerboard for see-through", checked = vm.showChecker) { vm.toggleChecker() }
    Item("Modifier button (Shift / Alt)", checked = vm.showModifier) {
        vm.showModifier = !vm.showModifier
        vm.prefs.edit().putBoolean("showModifier", vm.showModifier).apply()
        if (!vm.showModifier) {
            vm.modShift = false
            vm.modAlt = false
            vm.modLocked = false
        }
    }
    Divider()
    Page("Grid") {
        Item("Show grid", checked = vm.showGrid) { vm.toggleGrid() }
        Item("Grid settings…") { a.grid() }
    }
    Page("Rulers and guides") {
        Item("Rulers", checked = vm.showRulers) { vm.toggleRulers() }
        Item("Show guides", checked = vm.showGuides) { vm.toggleGuides() }
        Item("Lock guides", checked = vm.guidesLocked) { vm.toggleGuidesLocked() }
        Divider()
        Item("New guide…") { a.newGuide() }
        Item("Edit guides…") { a.editGuides() }
        Item("Clear guides") { vm.clearGuides() }
    }
}

/** The panels: which are open, and the panel side shown or hidden. */
@Composable
fun PagedMenuScope.WindowMenuItems(vm: EditorViewModel, panelsOpen: Boolean, togglePanels: () -> Unit) {
    Item(if (panelsOpen) "Hide panels" else "Show panels") { togglePanels() }
    Divider()
    PanelSection.entries.forEach { s ->
        Item(s.label, checked = vm.isOpen(s)) {
            if (!panelsOpen) togglePanels()
            vm.toggleSection(s)
        }
    }
    Divider()
    PanelSection.entries.filter { it != PanelSection.LAYERS }.forEach { s ->
        Item("Keep ${s.label} open", checked = s in vm.pinnedSections) { vm.togglePin(s) }
    }
    Divider()
    Item("Reset panel layout") { vm.resetDock() }
}
