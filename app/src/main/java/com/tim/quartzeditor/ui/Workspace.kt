package com.tim.quartzeditor.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val requestPlace = { placeImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    var exportOpen by remember { mutableStateOf(false) }
    var discardOpen by remember { mutableStateOf(false) }

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

        BackHandler {
            when {
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
            )
            Spacer(Modifier.width(if (compact) 6.dp else 12.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                EditorCanvas(vm, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))

                // Undo / redo / fit
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    val h = vm.history
                    @Suppress("UNUSED_VARIABLE") val v = h.version
                    RoundButton(Icons.AutoMirrored.Outlined.Undo, "Undo (two-finger tap)", enabled = h.canUndo || vm.pendingFx != null) { vm.undo() }
                    RoundButton(Icons.AutoMirrored.Outlined.Redo, "Redo (three-finger tap)", enabled = h.canRedo) { vm.redo() }
                    RoundButton(Icons.Outlined.FitScreen, "Fit to screen (or pinch in quickly)") { vm.fit() }
                }

                // Menu
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                    var menu by remember { mutableStateOf(false) }
                    RoundButton(Icons.Outlined.Menu, "Menu") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Home (saves to Projects)") }, onClick = { menu = false; vm.closeDocument() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Export image…") }, onClick = { menu = false; exportOpen = true })
                        DropdownMenuItem(text = { Text("Save project file…") }, onClick = {
                            menu = false
                            saveProject.launch("${vm.doc?.name ?: "Untitled"}.${ProjectIO.EXTENSION}")
                        })
                        DropdownMenuItem(text = { Text("Place image as layer…") }, onClick = { menu = false; requestPlace() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Flatten image") }, onClick = { menu = false; vm.flattenImage() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Rotate image 90° right") }, onClick = { menu = false; vm.rotateImage(clockwise = true) })
                        DropdownMenuItem(text = { Text("Rotate image 90° left") }, onClick = { menu = false; vm.rotateImage(clockwise = false) })
                        DropdownMenuItem(text = { Text("Flip image horizontally") }, onClick = { menu = false; vm.flipImage(horizontal = true) })
                        DropdownMenuItem(text = { Text("Flip image vertically") }, onClick = { menu = false; vm.flipImage(horizontal = false) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Close without saving…") }, onClick = { menu = false; discardOpen = true })
                    }
                }

                // Transforming: cancel / flip / rotate / distort / apply
                TransformBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                SelectBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
                CropBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 56.dp))

                // Show / hide panels
                RoundButton(
                    if (panelsOpen) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    if (panelsOpen) "Hide panels" else "Show panels",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) { panelsOpen = !panelsOpen }
            }
            if (docked) {
                Spacer(Modifier.width(12.dp))
                PanelColumn(vm, requestPlace, Modifier.width(310.dp).fillMaxHeight())
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

        BusyOverlay(vm.busy)
    }

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

    if (exportOpen) {
        ExportDialog(
            onDismiss = { exportOpen = false },
            onExport = { format, share ->
                exportOpen = false
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
private fun ExportDialog(onDismiss: () -> Unit, onExport: (ExportFormat, Boolean) -> Unit) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.PNG) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export image") },
        text = {
            Column {
                Text("Saves a flattened copy to Pictures/Quartz Editor (it shows up in Quartz Gallery).", color = Q.textDim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.entries.forEach { f ->
                        SmallButton(f.label, primary = f == format) { format = f }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (format.alpha) "Keeps see-through areas." else "See-through areas become white.",
                    color = Q.textDim,
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(format, false) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onExport(format, true) }) { Text("Save & share") }
            }
        },
    )
}
