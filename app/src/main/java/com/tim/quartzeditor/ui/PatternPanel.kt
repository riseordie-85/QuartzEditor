package com.tim.quartzeditor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.PatternEntry
import com.tim.quartzeditor.engine.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The patterns to fill with: the ready-made ones (in your foreground and background colors) and
 * your own. Hold one of yours to delete it; + adds one from your photos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternPicker(vm: EditorViewModel) {
    val addPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.addPatternFromPhoto(it) }
    }
    val fg = vm.foreground or 0xFF000000.toInt()
    val bg = vm.background or 0xFF000000.toInt()
    val all = remember(vm.userPatterns) { Patterns.builtIns() + vm.userPatterns }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            all.forEach { p ->
                PatternThumb(vm, p, fg, bg, selected = p.key == vm.patternKey)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(THUMB)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .clickable { addPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add a pattern from your photos", tint = Q.textDim)
            }
        }
        val name = all.firstOrNull { it.key == vm.patternKey }?.name ?: ""
        Text(
            if (name.isEmpty()) "" else if (vm.patternKey.startsWith("b:")) "$name · in your foreground and background colors" else "$name · hold it to delete",
            color = Q.textDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        // Size goes in doublings, so small and large sizes are both easy to set
        PanelSlider(
            "Size", log2(vm.patternScale.coerceIn(0.125f, 4f)), -3f..2f, "${(vm.patternScale * 100).roundToInt()}%",
            onChange = { vm.patternScale = 2f.pow(it).let { s -> if (kotlin.math.abs(s - 1f) < 0.04f) 1f else s } },
            onDone = { vm.savePatternScale() },
            fromTyped = { pct -> log2((pct / 100f).coerceIn(0.125f, 4f)) },
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (vm.doc?.selection != null) "Fill selection" else "Fill layer") { vm.fillWithPattern() }
            SmallButton(if (vm.doc?.selection != null) "Make from selection" else "Make from picture") { vm.definePattern() }
        }
    }
}

private val THUMB = 46.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PatternThumb(vm: EditorViewModel, p: PatternEntry, fg: Int, bg: Int, selected: Boolean) {
    val context = LocalContext.current
    // Your own patterns are read from storage in the background
    val bmp by produceState<android.graphics.Bitmap?>(null, p.key, fg, bg) {
        value = if (p.builtIn) Patterns.bitmap(context, p.key, fg, bg)
        else withContext(Dispatchers.IO) { Patterns.bitmap(context, p.key, fg, bg) }
    }
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(THUMB)
            .clip(RoundedCornerShape(8.dp))
            .background(Q.field)
            .border(if (selected) 2.dp else 1.dp, if (selected) Q.accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { vm.choosePattern(p.key) },
                onLongClick = { if (!p.builtIn) menu = true },
            ),
    ) {
        bmp?.let { b ->
            Canvas(Modifier.fillMaxSize().padding(if (selected) 2.dp else 1.dp)) {
                // Ready-made ones show a few repeats; your own show the whole picture
                val s = if (p.builtIn) size.width / (b.width * 2f) else size.width / max(b.width, b.height).toFloat()
                drawIntoCanvas { c -> c.nativeCanvas.drawRect(0f, 0f, size.width, size.height, Patterns.paint(b, s, 0f, 0f)) }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(p.name, color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Delete pattern") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = {
                    menu = false
                    vm.deletePattern(p.key)
                },
            )
        }
    }
}
