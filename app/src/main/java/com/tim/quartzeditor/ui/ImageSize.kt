package com.tim.quartzeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tim.quartzeditor.engine.Doc
import kotlin.math.max
import kotlin.math.roundToInt

// Image › Image size: how big the picture is, and scaling all of it (every layer) to a new size.

/** Roughly how much memory the picture's layers take (MB), for the Image size window. */
internal fun Doc.memoryMb(): Int {
    var bytes = 0L
    for (l in layers) {
        bytes += l.tiles.tiles.size.toLong() * 512 * 512 * 4
        l.mask?.let { bytes += it.tiles.size.toLong() * 512 * 512 }
    }
    return (bytes / (1024 * 1024)).toInt()
}

@Composable
fun ImageSizeDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val d = vm.doc ?: return
    val ow = d.width
    val oh = d.height
    var w by remember { mutableIntStateOf(ow) }
    var h by remember { mutableIntStateOf(oh) }
    var linked by remember { mutableStateOf(true) }
    var styles by remember { mutableStateOf(true) }
    // (Kept within the limits: linked, the shape stays; unlinked, the other side shrinks to fit)
    fun setW(v: Int) {
        if (linked) {
            val nw = v.coerceAtLeast(1)
            Doc.fitted(nw, (nw.toFloat() * oh / ow).roundToInt().coerceAtLeast(1)).let { (a, b) -> w = a; h = b }
        } else {
            w = v.coerceIn(1, Doc.MAX_SIDE)
            if (!Doc.fits(w, h)) h = (Doc.MAX_PIXELS / w).toInt().coerceIn(1, Doc.MAX_SIDE)
        }
    }
    fun setH(v: Int) {
        if (linked) {
            val nh = v.coerceAtLeast(1)
            Doc.fitted((nh.toFloat() * ow / oh).roundToInt().coerceAtLeast(1), nh).let { (a, b) -> w = a; h = b }
        } else {
            h = v.coerceIn(1, Doc.MAX_SIDE)
            if (!Doc.fits(w, h)) w = (Doc.MAX_PIXELS / h).toInt().coerceIn(1, Doc.MAX_SIDE)
        }
    }
    fun scaleTo(k: Float) {
        val kk = minOf(k, Doc.maxScale(ow, oh))
        w = (ow * kk).toInt().coerceIn(1, Doc.MAX_SIDE)
        h = (oh * kk).toInt().coerceIn(1, Doc.MAX_SIDE)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Q.panel)
                .padding(18.dp),
        ) {
            Text("Image size", color = Q.text, fontSize = 18.sp)
            Text(
                "Now $ow × $oh px · ${d.layers.size} layers · about ${d.memoryMb()} MB",
                color = Q.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Width", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                PanelField("$w", onCommit = { t -> t.trim().toIntOrNull()?.let { setW(it) } }, numeric = true, modifier = Modifier.width(100.dp),
                    onType = { t -> t.trim().toIntOrNull()?.takeIf { it > 0 }?.let { setW(it) } })
                Text(" px", color = Q.textDim, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("${(w * 100f / ow).roundToInt()}%", color = Q.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Height", color = Q.textDim, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                PanelField("$h", onCommit = { t -> t.trim().toIntOrNull()?.let { setH(it) } }, numeric = true, modifier = Modifier.width(100.dp),
                    onType = { t -> t.trim().toIntOrNull()?.takeIf { it > 0 }?.let { setH(it) } })
                Text(" px", color = Q.textDim, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("${(h * 100f / oh).roundToInt()}%", color = Q.textDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Keep proportions", linked) {
                linked = it
                if (it) setW(w)
            }
            ToggleRow("Scale layer styles (strokes, shadows, glows)", styles) { styles = it }

            Spacer(Modifier.height(8.dp))
            Text("Quick sizes", color = Q.textDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val longSide = max(ow, oh)
            val quick = buildList {
                add("25%" to 0.25f)
                add("50%" to 0.5f)
                add("75%" to 0.75f)
                for (side in listOf(2048, 2560, 3000)) if (side < longSide) add("$side long side" to side.toFloat() / longSide)
            }
            ChipRow(quick.map { it.first }, -1) { i -> scaleTo(quick[i].second) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Every layer, mask, shape, text and guide is scaled (shapes and text stay editable). Big pictures with many layers use a lot of memory: around 2500–3000 px on the long side stays smooth on phones and tablets.",
                color = Q.textDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                SmallButton("Cancel") { onDismiss() }
                SmallButton("Resize", primary = true, enabled = w != ow || h != oh) {
                    onDismiss()
                    vm.resizeImage(w, h, scaleStyles = styles)
                }
            }
        }
    }
}

/** "This Photoshop file is very big": shown before opening one over [BIG_PSD_MB]. */
@Composable
fun BigPsdDialog(vm: EditorViewModel) {
    val (uri, bytes) = vm.bigPsdAsk ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ramGb = remember {
        val info = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(info)
        info.totalMem / (1024.0 * 1024 * 1024)
    }
    val size = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.bigPsdAsk = null },
        title = { Text("A very big file") },
        text = {
            Text(
                "This file is $size. Files this big can take minutes to open, and may close the app on this device" +
                    (if (ramGb > 0.5) " (it has about ${ramGb.roundToInt()} GB of memory)" else "") +
                    ". Big pictures are shrunk to fit as they open.\n\nA computer is the safer place for files like this.",
                color = Q.textDim, fontSize = 14.sp,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                vm.bigPsdAsk = null
                vm.openPsd(uri, anyway = true)
            }) { Text("Open anyway") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.bigPsdAsk = null }) { Text("Cancel") } },
    )
}
