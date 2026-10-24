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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
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
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    // Photoshop brushes (.abr) into the brush library
    val importAbr = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { vm.importAbr(it) } }
    var abrSeen by remember { mutableIntStateOf(vm.abrImportRequest) }
    LaunchedEffect(vm.abrImportRequest) {
        if (vm.abrImportRequest != abrSeen) {
            abrSeen = vm.abrImportRequest
            try {
                importAbr.launch(arrayOf("application/octet-stream", "application/x-photoshop", "*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }
    // ComfyUI (the online app): adding a workflow file
    var comfyOpen by remember { mutableStateOf(false) }
    val importWorkflow = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { vm.importComfyWorkflow(it) } }
    val requestPlace = { placeImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    var exportOpen by remember { mutableStateOf(false) }
    var discardOpen by remember { mutableStateOf(false) }
    // File → New…, Open file…, Open image…
    var newDocOpen by remember { mutableStateOf(false) }
    var openImageOpen by remember { mutableStateOf(false) }
    val openFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { u -> if (!vm.readyForAnotherFile { vm.openPicked(u) }) vm.openPicked(u) }
    }
    val launchOpenFile = {
        try {
            openFilePicker.launch(OPEN_FILE_TYPES)
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
    var gridOpen by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    var editGuidesOpen by remember { mutableStateOf(false) }
    var imageSizeOpen by remember { mutableStateOf(false) }
    var upscaleOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var renameLayerOpen by remember { mutableStateOf(false) }
    val menuActions = MenuActions(
        rename = { renameOpen = true },
        export = { exportOpen = true },
        saveProject = { saveProject.launch("${vm.rootDoc?.name ?: "Untitled"}.${ProjectIO.EXTENSION}") },
        placeImage = requestPlace,
        discard = { discardOpen = true },
        // (Anything half done here is applied first)
        newDoc = { if (!vm.readyForAnotherFile { newDocOpen = true }) newDocOpen = true },
        openFile = { if (!vm.readyForAnotherFile { launchOpenFile() }) launchOpenFile() },
        openImage = { if (!vm.readyForAnotherFile { openImageOpen = true }) openImageOpen = true },
        imageSize = { imageSizeOpen = true },
        upscale = { upscaleOpen = true },
        grid = { gridOpen = true },
        newGuide = { guideOpen = true },
        editGuides = { editGuidesOpen = true },
        settings = { settingsOpen = true },
        comfy = { comfyOpen = true },
        renameLayer = { renameLayerOpen = true },
    )

    // Hover tips are drawn over everything, in a layer touches go straight through
    val tipHost = remember { TipHostState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalTipHost provides tipHost) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(vm.canvasColor))
            // Every touch, as it comes in: the pen's pressure, tilt, eraser end and button (anywhere on the screen)
            .motionEventSpy { if (vm.pen.onMotion(it) && vm.pen.touching) vm.notePenSeen() }
            .safeDrawingPadding()
    ) {
        // Big screens: a menu bar, the tool's settings in a bar under it, a status bar at the bottom
        val desktop = vm.desktopFor(maxWidth.value, maxHeight.value)
        // The menu bar layout's squarer look
        androidx.compose.runtime.SideEffect { Q.squared = desktop }
        val screenW = maxWidth
        val wide = maxWidth >= 760.dp || desktop
        val compact = !desktop && (maxWidth < 600.dp || maxHeight < 480.dp)
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
                // Back: the start screen, files kept open (File → Close closes one)
                else -> vm.openHome()
            }
        }

        val pad = if (compact || Q.compact || desktop) (if (desktop && !Q.compact) 6.dp else 4.dp) else 12.dp
        Column(Modifier.fillMaxSize().padding(pad)) {
        if (desktop) {
            MenuBar(vm, menuActions, panelsOpen, togglePanels = { panelsOpen = !panelsOpen })
            Spacer(Modifier.height(pad))
            ToolOptionsBar(vm)
            Spacer(Modifier.height(pad))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Toolbar(
                vm,
                compact = compact,
                slim = desktop,
                onColorTap = {
                    vm.toggleSection(PanelSection.COLOR, open = true)
                    panelsOpen = true
                },
                menu = { size ->
                    // The app menu, at the top of the tools: short pages instead of one long list
                    // (the menu bar has it all on big screens)
                    if (desktop) return@Toolbar
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
                            Item("Home (your files stay open)", Icons.Outlined.Home) { vm.openHome() }
                            Item("Quick export (.png)", Icons.Outlined.Image) { vm.quickExport() }
                            Divider()
                            Page("File", Icons.Outlined.Description) { FileMenuItems(vm, menuActions) }
                            Page("Image", Icons.Outlined.Image) { ImageMenuItems(vm, menuActions, withEnhance = false) }
                            Page("Enhance", Icons.Outlined.AutoFixHigh) { EnhanceMenuItems(vm, menuActions) }
                            if (vm.online) Item("ComfyUI…", Icons.Outlined.Cloud) { comfyOpen = true }
                            Page("View", Icons.Outlined.Visibility) { ViewMenuItems(vm, menuActions) }
                            Divider()
                            Item("Settings…", Icons.Outlined.Settings) { settingsOpen = true }
                        }
                    }
                },
            )
            Spacer(Modifier.width(if (compact || Q.compact) 4.dp else 12.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                EditorCanvas(vm, Modifier.fillMaxSize().clip(RoundedCornerShape(Q.corner(12.dp))))

                // Undo / redo / fit (on big screens: the menu bar and status bar)
                if (!desktop) Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).padding(top = if (vm.showRulers) RULER else 0.dp),
                ) {
                    val h = vm.history
                    @Suppress("UNUSED_VARIABLE") val v = h.version
                    RoundButton(Icons.AutoMirrored.Outlined.Undo, "Undo (two-finger tap)", enabled = h.canUndo || vm.pendingFx != null || vm.enhance != null || vm.textEdit != null || vm.penPoints.isNotEmpty()) { vm.undo() }
                    RoundButton(Icons.AutoMirrored.Outlined.Redo, "Redo (three-finger tap)", enabled = h.canRedo) { vm.redo() }
                    HistoryButton(vm)
                    RoundButton(Icons.Outlined.FitScreen, "Fit to screen, straight (or pinch in quickly)") { vm.fit() }
                }
                // The canvas is turned: how far (tap to straighten it)
                if (!desktop) RotationChip(vm, Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 6.dp))

                // Rulers (under the buttons)
                Rulers(vm)

                // Color Range's color being picked from the picture: say so, with a way out
                if (vm.pickingForRange) {
                    Bar(Modifier.align(Alignment.TopCenter).padding(top = if (desktop) 8.dp else 56.dp)) {
                        Text("Touch the picture to pick Color range's color", color = Q.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        BarChip("Cancel", false) { vm.cancelRangePick() }
                    }
                }

                // Typing: the text field (it needs more room than the options bar has, so it's here in both layouts)
                TextBar(vm, Modifier.align(Alignment.TopCenter).padding(top = if (desktop) 8.dp else 56.dp, start = 8.dp, end = 8.dp))

                // Transforming: cancel / flip / rotate / distort / apply (big screens: in the options bar)
                if (!desktop) {
                    TransformBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                    SelectBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                    CropBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                    WarpBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                    PenBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))

                    // Cut, copy, paste (big screens: the Edit menu)
                    EditButton(vm, Modifier.align(Alignment.BottomStart).padding(6.dp).padding(start = if (vm.showRulers) RULER else 0.dp))
                }

                // The on-screen Shift / Alt
                ModifierButton(vm)

                // Tabs, while a smart layer's contents are open
                // (The menu bar has them on big screens, with the open files)
                if (!desktop) TabStrip(vm, Modifier.align(Alignment.TopStart).padding(6.dp).padding(start = if (vm.showRulers) RULER else 0.dp, top = if (vm.showRulers) RULER else 0.dp).widthIn(max = 420.dp))

                // Panels folded away: the layers as a small stack at the edge
                androidx.compose.animation.AnimatedVisibility(
                    !panelsOpen,
                    enter = fadeIn() + slideInHorizontally { it / 2 },
                    exit = fadeOut() + slideOutHorizontally { it / 2 },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                ) {
                    LayerStrip(vm, onPlaceImage = requestPlace)
                }

                // Something selected: delete what's selected on the layer, or deselect (beside the panels button)
                if (vm.doc?.selection?.isEmpty == false) {
                    RoundButton(
                        Icons.Outlined.Delete, "Delete selected pixels",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).padding(end = if (desktop) 0.dp else 52.dp),
                    ) { vm.clearSelected() }
                    RoundButton(
                        Icons.Outlined.Deselect, "Deselect",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).padding(end = if (desktop) 52.dp else 104.dp),
                    ) { vm.deselect() }
                }

                // After a ColorDrop: its tolerance, to fill again with more or less
                ColorDropBar(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = if (vm.online && vm.showComfyBar) 76.dp else 10.dp, start = 64.dp, end = if (desktop) 110.dp else 160.dp))

                // The ComfyUI bar (the online app): a workflow, its prompt, Run
                ComfyBar(vm, onOpenWindow = { comfyOpen = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 64.dp, end = if (desktop) 110.dp else 160.dp))

                // Show / hide panels (big screens: the status bar)
                if (!desktop) RoundButton(
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
                if (desktop) DockHandle(vm, pad) else Spacer(Modifier.width(Q.gap(12.dp, 4.dp)))
                // A window opened from the panels (Layer Style, Adjustments…) takes their place, so the
                // picture keeps its room; closing it brings the panels back
                // (Never more than about half the screen, so the picture keeps room)
                Box(Modifier.width(if (desktop) vm.dockWidth.dp.coerceAtMost(screenW * 0.45f) else 310.dp).fillMaxHeight()) {
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
        if (desktop) {
            Spacer(Modifier.height(pad))
            StatusBar(vm, panelsOpen, togglePanels = { panelsOpen = !panelsOpen })
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
                    .padding(bottom = if (desktop) 60.dp else 24.dp, start = 80.dp, end = 80.dp)
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
                    .padding(top = if (desktop) 124.dp else if (wide) 64.dp else 16.dp, bottom = if (desktop) 56.dp else 16.dp, start = 16.dp, end = if (wide) 80.dp else 16.dp)
                    .widthIn(max = 340.dp)
                    .heightIn(max = maxHeight * 0.85f),
            )
        }

        // A ComfyUI run working away (you can keep editing), above the ComfyUI bar
        ComfyStatus(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = if (vm.online && vm.showComfyBar) 136.dp else 76.dp, start = 64.dp, end = 64.dp))

        BusyOverlay(vm.busy)
        ColorDragOverlay(vm)
        TipLayer(tipHost)
    }
    }

    if (comfyOpen) ComfyDialog(vm, onImport = {
        try {
            importWorkflow.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }, onDismiss = { comfyOpen = false })

    RasterizeDialog(vm)
    ColorRangeDialog(vm)
    BrushStudio(vm)
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
    if (newDocOpen) NewDocDialog(vm, onPickFile = launchOpenFile, onDismiss = { newDocOpen = false })
    if (openImageOpen) OpenImageDialog(vm, onDismiss = { openImageOpen = false })
    if (editGuidesOpen) EditGuidesDialog(vm, onDismiss = { editGuidesOpen = false }, onNew = {
        editGuidesOpen = false
        guideOpen = true
    })

    if (settingsOpen) SettingsScreen(vm) { settingsOpen = false }
    if (renameLayerOpen) {
        val l = vm.doc?.active
        if (l == null) renameLayerOpen = false
        else RenameDialog(l.props.name, onDismiss = { renameLayerOpen = false }) { name ->
            vm.setProps(l.id, "Rename layer") { it.copy(name = name) }
            renameLayerOpen = false
        }
    }
    if (renameOpen) {
        RenameDialog(vm.rootDoc?.name ?: "", title = "Rename image", onDismiss = { renameOpen = false }) {
            vm.renameDocument(it)
            renameOpen = false
        }
    }
    if (exportOpen) {
        ExportScreen(
            vm,
            onDismiss = { exportOpen = false },
            onShare = { uri, mime ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share image"))
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

/** The panels' edge on big screens: drag it to make them wider or narrower. */
@Composable
private fun DockHandle(vm: EditorViewModel, width: androidx.compose.ui.unit.Dp) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width.coerceAtLeast(14.dp))
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { vm.saveDockWidth() }, onDragCancel = { vm.saveDockWidth() }) { change, dx ->
                    change.consume()
                    vm.dockWidth = (vm.dockWidth - dx / density.density).coerceIn(260f, 560f)
                }
            },
    ) {
        Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(Q.text.copy(alpha = 0.25f)))
    }
}

/** How far the canvas is turned (only while it is); a tap straightens it. Its own scope, so turning doesn't redraw the workspace. */
@Composable
private fun RotationChip(vm: EditorViewModel, modifier: Modifier) {
    val turned = vm.normalizedRotation()
    if (turned == 0f) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Q.toolbar.copy(alpha = 0.9f))
            .clickable(onClickLabel = "Straighten the canvas") { vm.straightenView() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Outlined.Navigation, contentDescription = null, tint = Q.text,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turned },
        )
        Spacer(Modifier.width(6.dp))
        Text("${turned.roundToInt()}°", color = Q.text, fontSize = 13.sp)
        if (vm.rotationLocked) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Lock, contentDescription = "Turning locked", tint = Q.textDim, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier) { HoverTip(label) { RoundButtonFace(icon, label, enabled, onClick) } }
}

@Composable
private fun RoundButtonFace(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Q.toolbar.copy(alpha = 0.9f))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) Q.text else Q.textDim.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
    }
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
