package com.tim.quartzeditor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.quartzeditor.engine.ProjectIO

// File → New… and File → Open image…, over the editor: what's made or opened joins the open files
// in a tab of its own (the one you're on stays open, its changes in Drafts).

/** What File → Open file… (and the start screen's Open) can open: pictures, projects, Photoshop files. */
val OPEN_FILE_TYPES = arrayOf("image/*", ProjectIO.MIME, "application/octet-stream", "application/x-photoshop", "application/photoshop", "application/psd")

/** The canvas size screen (presets, your own size, orientation, background), as on the start screen. */
@Composable
fun NewDocDialog(vm: EditorViewModel, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    // A new picture came up (or one was opened): done
    val start = remember { vm.doc }
    LaunchedEffect(vm.doc) { if (vm.doc !== start) onDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(Q.corner(24.dp)))
                .background(Q.launchCard)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New image", color = Q.launchText, fontSize = 22.sp, modifier = Modifier.weight(1f))
                SmallButton("Cancel") { onDismiss() }
            }
            Spacer(Modifier.height(12.dp))
            NewDocForm(vm, onPick = {
                onDismiss()
                onPickFile()
            })
        }
    }
}

/** Your recent photos to open one as a new image (or Browse… for any other). */
@Composable
fun OpenImageDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { u -> if (!vm.readyForAnotherFile { vm.openImage(u) }) vm.openImage(u) }
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(Q.corner(24.dp)))
                .background(Q.launchCard)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open image", color = Q.launchText, fontSize = 22.sp, modifier = Modifier.weight(1f))
                SmallButton("Browse…") {
                    try {
                        browse.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: android.content.ActivityNotFoundException) {
                    }
                }
                Spacer(Modifier.padding(4.dp))
                SmallButton("Cancel") { onDismiss() }
            }
            Spacer(Modifier.height(12.dp))
            Text("Recent photos in your gallery: tap one to open it in a new tab.", color = Q.launchDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                GalleryGrid(vm, onOpened = onDismiss)
            }
        }
    }
}
