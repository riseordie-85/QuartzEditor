package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.OpenInFull
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.ComfyKind

// The ComfyUI bar (the online app): a workflow, its prompt and Run, floating at the bottom of the
// picture, so a run is a tap away. Everything else (models, LoRAs, sizes, presets) stays as set in
// the ComfyUI window, which the bar opens too. View → ComfyUI bar shows or hides it.

/** The ComfyUI bar is showing. */
var EditorViewModel.showComfyBar: Boolean
    get() {
        // (Read so what shows it redraws when it's switched)
        @Suppress("UNUSED_VARIABLE") val v = comfyBarVersion
        return prefs.getBoolean("comfyBar", true)
    }
    set(v) {
        prefs.edit().putBoolean("comfyBar", v).apply()
        comfyBarVersion++
    }

@Composable
fun ComfyBar(vm: EditorViewModel, onOpenWindow: () -> Unit, modifier: Modifier = Modifier) {
    if (!vm.online || !vm.showComfyBar) return
    LaunchedEffect(vm.comfyCloud) { vm.refreshComfyWorkflows() }
    val cloud = vm.comfyCloud
    val list = vm.comfyWorkflows?.takeIf { vm.comfyWorkflowsCloud == cloud }.orEmpty()
    val lastKey = if (cloud) "comfyLast:cloud" else "comfyLast"
    var pickedFile by remember(cloud) { mutableStateOf(vm.prefs.getString(lastKey, null)) }
    val w = list.firstOrNull { it.fileName == pickedFile } ?: list.firstOrNull()
    var prompt by remember(w?.fileName) { mutableStateOf(w?.let { vm.comfyPrompt(it) }.orEmpty()) }
    var open by remember { mutableStateOf(false) }
    fun keepPrompt() {
        val f = w?.fileName ?: return
        vm.prefs.edit().putString("comfyPrompt:$f", prompt).apply()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .widthIn(max = 760.dp)
            .clip(RoundedCornerShape(Q.corner(18.dp)))
            .background(Q.toolbar.copy(alpha = 0.96f))
            .border(1.dp, Q.divider, RoundedCornerShape(Q.corner(18.dp)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Q.textDim, modifier = Modifier.size(18.dp))
        // Which workflow (on your server or Comfy Cloud, as picked in the window)
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Q.field)
                    .clickable { open = true }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(w?.name ?: if (vm.comfyWorkflows == null) "Loading…" else "No workflows", color = Q.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Workflows", tint = Q.textDim, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Text(if (cloud) "Comfy Cloud" else "My server", color = Q.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                list.forEach { wf ->
                    DropdownMenuItem(text = { Text(wf.name) }, onClick = {
                        open = false
                        keepPrompt()
                        pickedFile = wf.fileName
                        vm.prefs.edit().putString(lastKey, wf.fileName).apply()
                    })
                }
            }
        }
        // Its prompt
        if (w?.hasPrompt != false) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Q.field)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (prompt.isEmpty()) Text("Prompt", color = Q.textDim, fontSize = 13.sp)
                BasicTextField(
                    prompt,
                    onValueChange = { prompt = it },
                    textStyle = TextStyle(color = Q.text, fontSize = 13.sp),
                    cursorBrush = SolidColor(Q.accent),
                    maxLines = 3,
                    modifier = Modifier.widthIn(min = 160.dp),
                )
            }
        } else {
            Text(w.kind.label, color = Q.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        // Run, with what the window has set for this workflow
        val d = vm.doc
        val ready = w != null && (w.kind != ComfyKind.INPAINT || d?.selection?.isEmpty == false) && (!w.hasPrompt || prompt.isNotBlank()) && vm.comfyJob.let { it == null || it.error != null }
        SmallButton("Run", primary = true, enabled = ready) {
            val wf = w ?: return@SmallButton
            keepPrompt()
            val f = wf.fileName
            vm.runComfy(
                wf, prompt.trim(),
                enhance = vm.prefs.getBoolean("comfyEnhance:$f", wf.savedEnhance),
                merged = vm.prefs.getBoolean("comfyMerged", false),
                overrides = wf.keep(vm.comfyOverrides(f)),
                megapixels = vm.prefs.getFloat("comfyMP:$f", wf.savedMegapixels),
                count = vm.prefs.getInt("comfyCount", 1),
                oneBatch = vm.prefs.getBoolean(if (cloud) "comfyOneBatch:cloud" else "comfyOneBatch", cloud),
            )
        }
        // Everything else
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable {
                keepPrompt()
                onOpenWindow()
            },
        ) { Icon(Icons.Outlined.OpenInFull, contentDescription = "Open the ComfyUI window", tint = Q.text, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(2.dp))
    }
}
