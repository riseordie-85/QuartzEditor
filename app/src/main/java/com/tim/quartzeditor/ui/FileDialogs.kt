package com.tim.quartzeditor.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

// Asked when a file closes with unsaved changes, and when a project opens that has unsaved changes
// in Drafts (the app closed before they were saved or thrown away). Shown over the editor and the
// start screen alike.

@Composable
fun FileDialogs(vm: EditorViewModel) {
    vm.closeAsk?.let { target ->
        // (Another file came up meanwhile, from a share say: the question was about that one)
        if (vm.rootDoc !== target) {
            androidx.compose.runtime.LaunchedEffect(target) { vm.closeAsk = null }
            return@let
        }
        val d = target
        val name = d.name
        val saved = d.projectId != null
        AlertDialog(
            onDismissRequest = { vm.closeAsk = null },
            title = { Text("Save changes to \"$name\"?") },
            text = {
                Text(
                    if (saved) "Save puts them into the project. Don't save throws them away; the project stays as it was last saved."
                    else "It hasn't been saved as a project yet. Save keeps it in Projects; Don't save throws it away."
                )
            },
            confirmButton = { TextButton(onClick = { vm.closeDocument(save = true) }) { Text("Save") } },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { vm.closeAsk = null }) { Text("Cancel") }
                    TextButton(onClick = { vm.closeDocument(save = false) }) { Text("Don't save") }
                }
            },
        )
    }
    vm.comfyGrowAsk?.let { g ->
        AlertDialog(
            onDismissRequest = { vm.comfyGrowAsk = null },
            title = { Text("Enlarge the image to ${g.w} × ${g.h}?") },
            text = { Text("${g.name} came back bigger. You're in the middle of something (a crop, transform, adjustment or text): it's applied first, then the image is enlarged to match.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.comfyGrowAsk = null
                    vm.growImage(g, ask = false)
                }) { Text("Enlarge") }
            },
            dismissButton = { TextButton(onClick = { vm.comfyGrowAsk = null }) { Text("Not now") } },
        )
    }
    vm.draftAsk?.let { draft ->
        val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(draft.modified))
        AlertDialog(
            onDismissRequest = { vm.draftAsk = null },
            title = { Text("\"${draft.name}\" has unsaved changes") },
            text = { Text("From $at, kept in Drafts when the app closed. Open them, or the project as it was last saved (the changes are thrown away)?") },
            confirmButton = { TextButton(onClick = { vm.openDraft(draft.id) }) { Text("Open the changes") } },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { vm.draftAsk = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        val id = draft.project ?: draft.id
                        vm.draftAsk = null
                        vm.discardDraft(draft.id)
                        vm.openProject(id, skipDraft = true)
                    }) { Text("Open saved") }
                }
            },
        )
    }
}
