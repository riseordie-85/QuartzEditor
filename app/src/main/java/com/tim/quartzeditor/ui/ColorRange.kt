package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.quartzeditor.engine.ColorRange
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.RangeKind
import com.tim.quartzeditor.engine.Selection
import com.tim.quartzeditor.engine.TileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Select → Color range (like Photoshop's): a color you sample, a family of hues, highlights,
// midtones, shadows or skin tones, with a black-and-white preview of what it picks out.

/** Color Range's settings. [color]: the sampled color. */
data class ColorRangeParams(
    val kind: RangeKind = RangeKind.SAMPLED,
    val color: Int,
    val fuzziness: Float = 40f,
    val allLayers: Boolean = true,
    val invert: Boolean = false,
)

fun EditorViewModel.openColorRange() {
    if (doc == null) return
    if (pendingFirst { openColorRange() }) return
    colorRange = colorRange ?: ColorRangeParams(color = foreground)
}

/** Picks the sampled color from the picture: the next touch on it, then the window comes back. */
fun EditorViewModel.pickRangeColor() {
    pickingForRange = true
    toast("Touch the picture to pick the color")
}

/** The color touched on the picture for Color Range (null: nothing there). */
fun EditorViewModel.rangeColorPicked(c: Int?) {
    pickingForRange = false
    val p = colorRange ?: return
    if (c != null) colorRange = p.copy(kind = RangeKind.SAMPLED, color = c or 0xFF000000.toInt())
}

/** The picture (or the layer) that Color Range looks at, at full size or smaller ([maxSide]). */
private fun EditorViewModel.rangeSource(allLayers: Boolean, maxSide: Int?): (() -> Bitmap)? {
    val d = doc ?: return null
    val l = d.active
    val all = allLayers || l == null || !l.hasContent
    val copy = if (all) d.shallowCopy() else null
    val tiles = if (all) null else HashMap(l!!.tiles.tiles)
    val lx = l?.x ?: 0
    val ly = l?.y ?: 0
    val w = d.width
    val h = d.height
    return {
        val full = if (copy != null) {
            DocRenderer.flatten(copy)
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles!!) }.drawTo(Canvas(b), lx.toFloat(), ly.toFloat(), null)
            }
        }
        val k = if (maxSide == null) 1f else min(1f, maxSide.toFloat() / max(w, h))
        if (k < 1f) Bitmap.createScaledBitmap(full, max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()), true).also { full.recycle() } else full
    }
}

/** Makes the selection from Color Range's settings (combined by the selection mode). */
fun EditorViewModel.applyColorRange(p: ColorRangeParams) {
    colorRange = null
    pickingForRange = false
    val d = doc ?: return
    val source = rangeSource(p.allLayers, null) ?: return
    val old = d.selection
    val mode = selectMode
    setSelectionComputed(if (mode == com.tim.quartzeditor.engine.SelectMode.NEW) "Color range" else "Change selection") {
        val src = source()
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        val cov = ColorRange.coverage(px, p.kind, p.color, p.fuzziness, p.invert)
        if (cov.none { it.toInt() != 0 }) throw IllegalStateException("Nothing in the picture matches")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(cov))
        val grid = TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = true)
        bmp.recycle()
        Selection.combineMask(old, grid, mode)
    }
}

@Composable
fun ColorRangeDialog(vm: EditorViewModel) {
    val p = vm.colorRange ?: return
    // Hidden while a color is being picked from the picture
    if (vm.pickingForRange) return
    // The picture small, for the preview (made again when the source changes)
    var small by remember { mutableStateOf<IntArray?>(null) }
    var size by remember { mutableStateOf(0 to 0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(p.allLayers) {
        val src = vm.rangeSource(p.allLayers, 360) ?: return@LaunchedEffect
        val (px, wh) = withContext(Dispatchers.Default) {
            val b = src()
            val a = IntArray(b.width * b.height)
            b.getPixels(a, 0, b.width, 0, 0, b.width, b.height)
            val s = b.width to b.height
            b.recycle()
            a to s
        }
        small = px
        size = wh
    }
    LaunchedEffect(small, p) {
        val px = small ?: return@LaunchedEffect
        delay(60)
        val (w, h) = size
        preview = withContext(Dispatchers.Default) {
            val cov = ColorRange.coverage(px, p.kind, p.color, p.fuzziness, p.invert)
            // White where it's selected, black where it isn't
            val out = IntArray(w * h) { i ->
                val v = cov[i].toInt() and 0xFF
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
    }
    fun set(change: (ColorRangeParams) -> ColorRangeParams) {
        vm.colorRange = change(p)
    }
    AlertDialog(
        onDismissRequest = { vm.colorRange = null },
        title = { Text("Color range") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                ChipRow(RangeKind.entries.map { it.label }, p.kind.ordinal) { i -> set { it.copy(kind = RangeKind.entries[i]) } }
                Spacer(Modifier.height(10.dp))
                if (p.kind == RangeKind.SAMPLED) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Color", color = Q.textDim, fontSize = 13.sp)
                        Box(
                            Modifier
                                .size(width = 44.dp, height = 28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(p.color))
                                .border(1.dp, Q.text.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        )
                        SmallButton("Pick on the picture") { vm.pickRangeColor() }
                        SmallButton("Foreground") { set { it.copy(color = vm.foreground) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    PanelSlider("Fuzziness", p.fuzziness, 0f..200f, "${p.fuzziness.roundToInt()}", onChange = { v -> set { it.copy(fuzziness = v) } })
                }
                ToggleRow("Look at every layer (off: just this one)", p.allLayers) { on -> set { it.copy(allLayers = on) } }
                ToggleRow("Invert", p.invert) { on -> set { it.copy(invert = on) } }
                Spacer(Modifier.height(8.dp))
                val pv = preview
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                ) {
                    if (pv != null) Image(pv.asImageBitmap(), contentDescription = "What's selected (white)", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(200.dp))
                    else Text("…", color = Color.White)
                }
                Spacer(Modifier.height(4.dp))
                Text("White is selected. It combines with the selection by the selection mode (New, Add, Subtract, Intersect).", color = Q.textDim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { vm.applyColorRange(p) }) { Text("Select") } },
        dismissButton = { TextButton(onClick = { vm.colorRange = null }) { Text("Cancel") } },
    )
}

/** Select → Sky: the sky, found from the on-device depth model (soft at its edges). */
fun EditorViewModel.selectSky() {
    val d = doc ?: return
    if (pendingFirst { selectSky() }) return
    if (!com.tim.quartzeditor.engine.Models.has(ctx, com.tim.quartzeditor.engine.Models.DEPTH)) {
        toast("This build doesn't include the depth model. Build the app with internet on once so Android Studio can download it.")
        return
    }
    val copy = d.shallowCopy()
    val w = d.width
    val h = d.height
    val old = d.selection
    val mode = selectMode
    val c = ctx
    setSelectionComputed("Select sky", busyLabel = "Finding the sky…") {
        val pic = DocRenderer.flatten(copy)
        val k = min(1f, 1024f / max(w, h))
        val proxy = if (k < 1f) Bitmap.createScaledBitmap(pic, max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()), true).also { pic.recycle() } else pic
        val pw = proxy.width
        val ph = proxy.height
        val mask = try {
            val depth = com.tim.quartzeditor.engine.Depth.estimate(c, proxy)
            com.tim.quartzeditor.engine.EnhanceArt.skyMask(proxy, depth, pw, ph, 0.12f)
        } finally {
            proxy.recycle()
        }
        mask ?: throw IllegalStateException("No sky found in this picture")
        val small = Bitmap.createBitmap(pw, ph, Bitmap.Config.ALPHA_8)
        small.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(ByteArray(pw * ph) { (mask[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }))
        val big = if (pw != w || ph != h) Bitmap.createScaledBitmap(small, w, h, true).also { small.recycle() } else small
        val grid = TileGrid.fromBitmap(big, 0, 0, alphaOnly = true)
        big.recycle()
        Selection.combineMask(old, grid, mode)
    }
}
