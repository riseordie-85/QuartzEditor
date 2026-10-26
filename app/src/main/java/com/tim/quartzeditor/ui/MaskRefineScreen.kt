package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.quartzeditor.engine.MaskRefine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Select and mask: the layer's mask, refined with a live preview, then OK makes it so (one undo step).

/** How the preview shows the result. */
private enum class RefineView(val label: String) { CHECKER("Checkerboard"), BLACK("On black"), WHITE("On white"), OVERLAY("Overlay"), MASK("Mask") }

/** The preview is worked out at most this big. */
private const val PREVIEW_SIDE = 1100

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaskRefineScreen(vm: EditorViewModel) {
    val id = vm.maskRefineFor ?: return
    val d = vm.doc
    val layer = d?.layer(id)
    if (d == null || layer?.mask == null) {
        LaunchedEffect(Unit) { vm.maskRefineFor = null }
        return
    }
    val close = { vm.maskRefineFor = null }
    // The mask and picture at preview size, once
    var work by remember { mutableStateOf<MaskWork?>(null) }
    LaunchedEffect(id) { work = withContext(Dispatchers.Default) { maskWork(d, layer, PREVIEW_SIDE) } }
    var radius by remember { mutableFloatStateOf(0f) }
    var smooth by remember { mutableFloatStateOf(0f) }
    var feather by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var shift by remember { mutableFloatStateOf(0f) }
    var view by remember { mutableStateOf(RefineView.CHECKER) }
    val params = MaskRefine.Params(radius, smooth, feather, contrast, shift)
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(work, params, view) {
        val wk = work ?: return@LaunchedEffect
        working = true
        delay(120)
        preview = withContext(Dispatchers.Default) {
            val m = if (params.isEmpty) wk.mask else MaskRefine.apply(wk.mask, wk.gray, wk.w, wk.h, params, wk.scale)
            ensureActive()
            refinePreview(wk, m, view)
        }
        working = false
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Q.workspace).safeDrawingPadding()) {
            val wide = maxWidth >= 760.dp
            val screenH = maxHeight
            val previewPane: @Composable (Modifier) -> Unit = { m ->
                Box(m.background(Q.palette.panelDark)) {
                    if (view == RefineView.CHECKER) Canvas(Modifier.fillMaxSize()) {
                        val s = 12.dp.toPx()
                        var y = 0f
                        var row = 0
                        while (y < size.height) {
                            var x = 0f
                            var col = row
                            while (x < size.width) {
                                drawRect(if (col % 2 == 0) Color(0xFF3A3A3A) else Color(0xFF2E2E2E), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(s, s))
                                x += s
                                col++
                            }
                            y += s
                            row++
                        }
                    }
                    preview?.let { b ->
                        val img = remember(b) { b.asImageBitmap() }
                        Image(img, contentDescription = "Preview", contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize().padding(16.dp))
                    }
                    if (work == null || working) CircularProgressIndicator(color = Q.accent, modifier = Modifier.align(Alignment.Center).size(32.dp))
                }
            }
            val settings: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text("Select and mask", color = Q.text, fontSize = 22.sp)
                    Text("\"${layer.props.name}\"'s mask", color = Q.textDim, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("View", color = Q.text, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RefineView.entries.forEach { v -> SmallButton(v.label, primary = v == view) { view = v } }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Edge detection", color = Q.text, fontSize = 15.sp)
                    PanelSlider("Radius", radius, 0f..60f, "${radius.roundToInt()} px", onChange = { radius = it })
                    Text("The edge follows the picture's own within this distance: hair, fur, leaves. Try 5–20 px.", color = Q.textDim, fontSize = 12.sp)
                    SmallButton("Auto") { radius = MaskRefine.autoRadius(d.width, d.height).coerceAtMost(60f) }
                    Spacer(Modifier.height(16.dp))
                    Text("Global refinements", color = Q.text, fontSize = 15.sp)
                    PanelSlider("Smooth", smooth, 0f..100f, "${smooth.roundToInt()}", onChange = { smooth = it })
                    PanelSlider("Feather", feather, 0f..100f, "${"%.1f".format(feather)} px", onChange = { feather = it })
                    PanelSlider("Contrast", contrast, 0f..100f, "${contrast.roundToInt()}%", onChange = { contrast = it })
                    PanelSlider("Shift edge", shift, -100f..100f, "${shift.roundToInt()}%", onChange = { shift = it })
                    Text("Smooth rounds off jagged outlines; feather softens the edge, contrast sharpens it; shift edge moves it in (−) or out (+).", color = Q.textDim, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton("Reset") {
                            radius = 0f; smooth = 0f; feather = 0f; contrast = 0f; shift = 0f
                        }
                        SmallButton("Cancel") { close() }
                        SmallButton("OK", primary = true, enabled = !params.isEmpty) {
                            close()
                            vm.applyMaskRefine(id, params)
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    previewPane(Modifier.weight(1f).fillMaxHeight())
                    settings(Modifier.width(380.dp).fillMaxHeight().background(Q.panel))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    previewPane(Modifier.fillMaxWidth().height(screenH * 0.45f))
                    settings(Modifier.fillMaxWidth().weight(1f).background(Q.panel))
                }
            }
        }
    }
}

/** The picture with [mask] (hidden amount) as [view] shows it. */
private fun refinePreview(wk: MaskWork, mask: FloatArray, view: RefineView): Bitmap {
    val w = wk.w
    val h = wk.h
    val px = IntArray(w * h)
    wk.color.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val shown = (1f - mask[i]).coerceIn(0f, 1f)
        val c = px[i]
        val a = (c ushr 24) / 255f * shown
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        px[i] = when (view) {
            RefineView.CHECKER -> ((a * 255).roundToInt() shl 24) or (c and 0xFFFFFF)
            RefineView.BLACK -> argb(1f, r * a, g * a, b * a)
            RefineView.WHITE -> argb(1f, r * a + 255 * (1 - a), g * a + 255 * (1 - a), b * a + 255 * (1 - a))
            // Hidden parts tinted red, like Photoshop's quick mask
            RefineView.OVERLAY -> {
                val k = mask[i].coerceIn(0f, 1f) * 0.55f
                argb(1f, r * (1 - k) + 255 * k, g * (1 - k), b * (1 - k))
            }
            RefineView.MASK -> {
                val v = shown * 255f
                argb(1f, v, v, v)
            }
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
    ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
