package com.tim.quartzeditor.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Description
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.data.ExportFormat
import com.tim.quartzeditor.engine.ProjectIO
import kotlinx.coroutines.delay

/** The editing screen: tools on the left, the picture in the middle, panels on the right. */
@Composable
fun Workspace(vm: EditorViewModel) {
    val context = LocalContext.current
    val placeImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { vm.placeImage(it) }
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ProjectIO.MIME)) { uri: Uri? ->
        uri?.let { vm.saveProjectFile(it) }
    }
    // Replace Contents: any picture, or a Photoshop or TIFF file
    val replaceContents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> vm.replaceSmartContents(uri) }
    LaunchedEffect(vm.replaceContentsFor) {
        val id = vm.replaceContentsFor ?: return@LaunchedEffect
        // Cleared first, so the picker doesn't open again when the screen is made again (turning it)
        vm.replaceContentsFor = null
        vm.replaceTarget = id
        try {
            replaceContents.launch(arrayOf("image/*", "application/octet-stream", "application/x-photoshop", "image/vnd.adobe.photoshop"))
        } catch (e: android.content.ActivityNotFoundException) {
            vm.replaceTarget = null
        }
    }
    val requestPlace = { placeImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    var exportOpen by remember { mutableStateOf(false) }
    var discardOpen by remember { mutableStateOf(false) }
    var gridOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    var editGuidesOpen by remember { mutableStateOf(false) }
    var imageSizeOpen by remember { mutableStateOf(false) }
    var upscaleOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(vm.canvasColor))
            .safeDrawingPadding()
    ) {
        val wide = maxWidth >= 760.dp
        val compact = maxWidth < 600.dp || maxHeight < 480.dp
        var panelsOpen by rememberSaveable { mutableStateOf(wide) }
        val docked = wide && panelsOpen
        // Tab on a keyboard shows or hides the panels
        var seenToggle by remember { mutableIntStateOf(vm.panelsToggle) }
        LaunchedEffect(vm.panelsToggle) {
            if (vm.panelsToggle != seenToggle) {
                seenToggle = vm.panelsToggle
                panelsOpen = !panelsOpen
            }
        }

        BackHandler {
            when {
                vm.textEdit != null -> vm.finishText()
                vm.penPoints.isNotEmpty() -> vm.cancelPen()
                !docked && panelsOpen -> panelsOpen = false
                else -> vm.closeDocument()
            }
        }

        Row(Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp)) {
            Toolbar(
                vm,
                compact = compact,
                onColorTap = {
                    vm.toggleSection(PanelSection.COLOR, open = true)
                    panelsOpen = true
                },
                menu = { size ->
                    // The app menu, at the top of the tools: short pages instead of one long list
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .size(size)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (menu) Q.text.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { menu = true },
                        ) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = Q.text, modifier = Modifier.size(size * 0.55f))
                        }
                        PagedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = androidx.compose.ui.unit.DpOffset(size + 8.dp, -size)) {
                            Item("Home (saves to Projects)", Icons.Outlined.Home) { vm.closeDocument() }
                            Divider()
                            Page("File", Icons.Outlined.Description) {
                                Item("Rename image…") { renameOpen = true }
                                Item("Export image…") { exportOpen = true }
                                Item("Save project file…") { saveProject.launch("${vm.rootDoc?.name ?: "Untitled"}.${ProjectIO.EXTENSION}") }
                                Item("Place image as layer…") { requestPlace() }
                                Divider()
                                Item("Close without saving…") { discardOpen = true }
                            }
                            Page("Image", Icons.Outlined.Image) {
                                Label("${vm.doc?.width ?: 0} × ${vm.doc?.height ?: 0} px")
                                Item("Image size…") { imageSizeOpen = true }
                                Divider()
                                Item("Rotate 90° right") { vm.rotateImage(clockwise = true) }
                                Item("Rotate 90° left") { vm.rotateImage(clockwise = false) }
                                Item("Flip horizontally") { vm.flipImage(horizontal = true) }
                                Item("Flip vertically") { vm.flipImage(horizontal = false) }
                                Divider()
                                Item("Flatten image") { vm.flattenImage() }
                            }
                            Page("Enhance", Icons.Outlined.AutoFixHigh) {
                                Item("Auto enhance") { vm.autoEnhance() }
                                Item("Upscale…") { upscaleOpen = true }
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
                            Page("View", Icons.Outlined.Visibility) {
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
                                    Item("Grid settings…") { gridOpen = true }
                                }
                                Page("Rulers and guides") {
                                    Item("Rulers", checked = vm.showRulers) { vm.toggleRulers() }
                                    Item("Show guides", checked = vm.showGuides) { vm.toggleGuides() }
                                    Item("Lock guides", checked = vm.guidesLocked) { vm.toggleGuidesLocked() }
                                    Divider()
                                    Item("New guide…") { guideOpen = true }
                                    Item("Edit guides…") { editGuidesOpen = true }
                                    Item("Clear guides") { vm.clearGuides() }
                                }
                            }
                            Divider()
                            Item("Settings…", Icons.Outlined.Settings) { settingsOpen = true }
                        }
                    }
                },
            )
            Spacer(Modifier.width(if (compact) 6.dp else 12.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                EditorCanvas(vm, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))

                // Undo / redo / fit
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).padding(top = if (vm.showRulers) RULER else 0.dp),
                ) {
                    val h = vm.history
                    @Suppress("UNUSED_VARIABLE") val v = h.version
                    RoundButton(Icons.AutoMirrored.Outlined.Undo, "Undo (two-finger tap)", enabled = h.canUndo || vm.pendingFx != null || vm.enhance != null || vm.textEdit != null || vm.penPoints.isNotEmpty()) { vm.undo() }
                    RoundButton(Icons.AutoMirrored.Outlined.Redo, "Redo (three-finger tap)", enabled = h.canRedo) { vm.redo() }
                    HistoryButton(vm)
                    RoundButton(Icons.Outlined.FitScreen, "Fit to screen (or pinch in quickly)") { vm.fit() }
                }

                // Rulers (under the buttons)
                Rulers(vm)

                // Transforming: cancel / flip / rotate / distort / apply
                TransformBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                SelectBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                CropBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                WarpBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                PenBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                TextBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 8.dp, end = 8.dp))

                // Cut, copy, paste
                EditButton(vm, Modifier.align(Alignment.BottomStart).padding(6.dp).padding(start = if (vm.showRulers) RULER else 0.dp))

                // The on-screen Shift / Alt
                ModifierButton(vm)

                // Tabs, while a smart layer's contents are open
                TabStrip(vm, Modifier.align(Alignment.TopStart).padding(6.dp).padding(start = if (vm.showRulers) RULER else 0.dp, top = if (vm.showRulers) RULER else 0.dp).widthIn(max = 420.dp))

                // Panels folded away: the layers as a small stack at the edge
                androidx.compose.animation.AnimatedVisibility(
                    !panelsOpen,
                    enter = fadeIn() + slideInHorizontally { it / 2 },
                    exit = fadeOut() + slideOutHorizontally { it / 2 },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                ) {
                    LayerStrip(vm, onPlaceImage = requestPlace)
                }

                // Show / hide panels
                RoundButton(
                    if (panelsOpen) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    if (panelsOpen) "Hide panels" else "Show panels",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) { panelsOpen = !panelsOpen }
            }
            // Docked panels slide in and out from the side
            AnimatedVisibility(
                docked,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
              Row {
                Spacer(Modifier.width(12.dp))
                // A window opened from the panels (Layer Style, Adjustments…) takes their place, so the
                // picture keeps its room; closing it brings the panels back
                Box(Modifier.width(310.dp).fillMaxHeight()) {
                    // The panels stay underneath (hidden), so they're as you left them when the window closes
                    PanelColumn(vm, requestPlace, Modifier.fillMaxSize().alpha(if (vm.popup != null) 0f else 1f))
                    if (vm.popup != null) {
                        FloatingPanel(
                            vm,
                            Modifier
                                .fillMaxSize()
                                .background(Q.workspace)
                                // Taps on the window never reach the hidden panels
                                .pointerInput(Unit) { detectTapGestures { } },
                        )
                    }
                }
              }
            }
        }

        // Phones and narrow windows: panels slide over the picture
        if (!wide) {
            AnimatedVisibility(panelsOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panelsOpen = false }
                )
            }
            AnimatedVisibility(
                panelsOpen,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                PanelColumn(
                    vm,
                    requestPlace,
                    Modifier
                        .padding(8.dp)
                        .width(minOf(340.dp, maxWidth * 0.9f))
                        .fillMaxHeight(),
                )
            }
        }

        // Notes at the bottom
        val msg = vm.message
        LaunchedEffect(vm.messageId) {
            if (msg != null) {
                delay(2600)
                vm.message = null
            }
        }
        if (msg != null) {
            Text(
                msg,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 80.dp, end = 80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // Layer Style / Adjustments / Filters float over the picture, so the preview stays visible
        // (Docked panels show them in their own place instead, above)
        LaunchedEffect(vm.popup) { if (!wide && vm.popup != null) panelsOpen = false }
        if (!docked) {
            FloatingPanel(
                vm,
                Modifier
                    .align(if (wide) Alignment.TopEnd else Alignment.Center)
                    .padding(top = if (wide) 64.dp else 16.dp, bottom = 16.dp, start = 16.dp, end = if (wide) 80.dp else 16.dp)
                    .widthIn(max = 340.dp)
                    .heightIn(max = maxHeight * 0.85f),
            )
        }

        BusyOverlay(vm.busy)
    }

    RasterizeDialog(vm)
    if (gridOpen) GridDialog(vm) { gridOpen = false }
    if (guideOpen) NewGuideDialog(vm) { guideOpen = false }
    // After opening a Photoshop file: what didn't come across exactly
    vm.importReport?.let { (name, notes) ->
        AlertDialog(
            onDismissRequest = { vm.importReport = null },
            title = { Text("Opened \"$name\"") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("${vm.doc?.layers?.size ?: 0} layers came across. A few things are different:", color = Q.textDim, fontSize = 14.sp)
                    for (n in notes) Text("• $n", color = Q.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.importReport = null }) { Text("OK") } },
        )
    }
    // After saving a Photoshop file: what Photoshop gets differently
    vm.exportReport?.let { (name, where, notes) ->
        AlertDialog(
            onDismissRequest = { vm.exportReport = null },
            title = { Text("Saved \"$name\"") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("It's in $where, with its layers. In Photoshop, a few things are different:", color = Q.textDim, fontSize = 14.sp)
                    for (n in notes) Text("• $n", color = Q.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.exportReport = null }) { Text("OK") } },
        )
    }
    if (imageSizeOpen) ImageSizeDialog(vm) { imageSizeOpen = false }
    if (upscaleOpen) {
        val room = vm.upscaleRoom()
        val d = vm.doc
        AlertDialog(
            onDismissRequest = { upscaleOpen = false },
            title = { Text("Upscale") },
            text = {
                Text(
                    if (room == 0) "This picture is already as big as the app can upscale (the result would be over ${com.tim.quartzeditor.engine.Doc.MAX_SIDE} px). Crop it or use Image size to make it smaller first."
                    else "Makes the whole picture bigger, with new detail from the on-device AI for photo layers (shapes and text are redrawn sharp). " +
                        "It's ${d?.width ?: 0} × ${d?.height ?: 0} px now. This can take a minute or two."
                )
            },
            confirmButton = {
                Row {
                    if (room >= 2) TextButton(onClick = { upscaleOpen = false; vm.upscaleImage(2) }) { Text("2× (${(d?.width ?: 0) * 2} px wide)") }
                    if (room >= 4) TextButton(onClick = { upscaleOpen = false; vm.upscaleImage(4) }) { Text("4×") }
                }
            },
            dismissButton = { TextButton(onClick = { upscaleOpen = false }) { Text("Cancel") } },
        )
    }
    BigPsdDialog(vm)
    if (editGuidesOpen) EditGuidesDialog(vm, onDismiss = { editGuidesOpen = false }, onNew = {
        editGuidesOpen = false
        guideOpen = true
    })

    if (discardOpen) {
        val saved = vm.doc?.projectId != null
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text("Close without saving?") },
            text = {
                Text(
                    if (saved) "Changes since this project was last saved will be lost. The saved version stays in Projects."
                    else "This image hasn't been saved. Everything you did will be lost."
                )
            },
            confirmButton = { TextButton(onClick = { discardOpen = false; vm.discardDocument() }) { Text("Close without saving") } },
            dismissButton = { TextButton(onClick = { discardOpen = false }) { Text("Keep editing") } },
        )
    }

    if (settingsOpen) SettingsScreen(vm) { settingsOpen = false }
    if (renameOpen) {
        RenameDialog(vm.rootDoc?.name ?: "", title = "Rename image", onDismiss = { renameOpen = false }) {
            vm.renameDocument(it)
            renameOpen = false
        }
    }
    if (exportOpen) {
        ExportDialog(
            name = vm.rootDoc?.name ?: "Untitled",
            onDismiss = { exportOpen = false },
            onExport = { format, share, fileName ->
                exportOpen = false
                // The image takes the name too (its project and project files use it)
                vm.renameDocument(fileName)
                vm.export(format) { uri ->
                    if (share) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = format.mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share image"))
                    }
                }
            },
        )
    }
}

@Composable
fun BusyOverlay(label: String?) {
    if (label == null) return
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Q.panelDark)
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            CircularProgressIndicator(color = Q.accent)
            Spacer(Modifier.height(12.dp))
            Text(label, color = Q.text)
        }
    }
}

/** Bottom left of the picture: Cut, Copy, Paste and friends. */
@Composable
private fun EditButton(vm: EditorViewModel, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        RoundButton(Icons.Outlined.ContentPaste, "Cut, copy, paste") { open = true }
        PagedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val d = vm.doc
            val sel = d?.selection?.isEmpty == false
            Label(if (sel) "What's selected on \"${d?.active?.props?.name ?: ""}\"" else "The whole layer \"${d?.active?.props?.name ?: ""}\" (select part of it to copy just that)")
            Item("Cut", Icons.Outlined.ContentCut) { vm.cutPixels() }
            Item("Copy", Icons.Outlined.ContentCopy) { vm.copyPixels() }
            Item("Copy merged (everything you see)", Icons.Outlined.Layers) { vm.copyPixels(merged = true) }
            Item("Paste", Icons.Outlined.ContentPaste, enabled = vm.clip != null || vm.otherAppHasImage()) { vm.pastePixels() }
            if (vm.clip != null && vm.otherAppHasImage()) Item("Paste from another app", Icons.Outlined.Image) { vm.pasteFromOtherApp() }
            Divider()
            Item("New layer via copy", enabled = sel) { vm.selectionToLayer(cut = false) }
            Item("New layer via cut", enabled = sel) { vm.selectionToLayer(cut = true) }
            Divider()
            Item("Select all") { vm.selectAll() }
            Item("Deselect", enabled = sel) { vm.deselect() }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Q.toolbar.copy(alpha = 0.9f))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) Q.text else Q.textDim.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ExportDialog(name: String, onDismiss: () -> Unit, onExport: (ExportFormat, Boolean, String) -> Unit) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.PNG) }
    var fileName by rememberSaveable { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export image") },
        text = {
            Column {
                Text("Saves a flattened copy to Pictures/Quartz Editor (it shows up in Quartz Gallery), or a Photoshop file with its layers.", color = Q.textDim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    fileName, { fileName = it.replace("/", "").take(80) }, label = { Text("File name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                // Wraps onto a second line on a narrow screen
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportFormat.entries.forEach { f ->
                        SmallButton(f.label, primary = f == format) { format = f }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        format == ExportFormat.PSD -> "Keeps every layer, mask, style, adjustment, shape, text and smart layer. Saved to Download/${com.tim.quartzeditor.data.Media.FOLDER}."
                        format == ExportFormat.TIFF_LAYERS -> "A TIFF any app shows, with the layers kept inside it like Photoshop's own (Photoshop and Quartz Editor open them)."
                        format.alpha -> "Keeps see-through areas."
                        else -> "See-through areas become white."
                    },
                    color = Q.textDim,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(format, false, fileName) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onExport(format, true, fileName) }) { Text("Save & share") }
            }
        },
    )
}

/**
 * The History panel: every step you can go back to, oldest first, with where you are marked.
 * Tap a step to go back (or forward) to the picture as it was after it.
 */
@Composable
private fun HistoryButton(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundButton(Icons.Outlined.Description, "History") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp).heightIn(max = 460.dp)) {
            val h = vm.history
            @Suppress("UNUSED_VARIABLE") val v = h.version
            val done = h.undoLabels
            val ahead = h.redoLabels
            Text("History", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            @Composable
            fun row(label: String, steps: Int, current: Boolean, undone: Boolean) {
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (undone) Q.textDim else Q.text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        if (current) Icon(Icons.Outlined.Check, contentDescription = "You're here", tint = Q.accent)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = { vm.jumpHistory(steps) },
                )
            }
            row(if (h.trimmed) "Earlier (older steps let go)" else "Opened", 0, done.isEmpty(), undone = false)
            done.forEachIndexed { i, label -> row(label, i + 1, i == done.lastIndex, undone = false) }
            ahead.forEachIndexed { j, label -> row(label, done.size + 1 + j, false, undone = true) }
            if (done.isEmpty() && ahead.isEmpty()) {
                Text("Nothing done yet", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
