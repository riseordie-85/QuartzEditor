package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.DocRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The picture's main colors, offered as swatches in the layer style windows.

/** Works out the picture's main colors in the background (again only when it has changed since). */
fun EditorViewModel.ensurePalette() {
    val d = doc ?: return
    val key = d to d.renderVersion
    if (paletteFrom == key) return
    paletteFrom = key
    val copy = d.shallowCopy()
    viewModelScope.launch {
        val colors = withContext(Dispatchers.Default) {
            runCatching {
                val small = DocRenderer.thumbnail(copy, 96)
                dominantColors(small).also { small.recycle() }
            }.getOrDefault(emptyList())
        }
        if (doc === d) imagePalette = colors
    }
}

/**
 * The [count] colors covering most of [bmp] (see-through pixels left out), each clearly different
 * from the others: colors are grouped into small boxes and the fullest boxes win.
 */
internal fun dominantColors(bmp: Bitmap, count: Int = 4): List<Int> {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    // Per box (16 levels a channel): how many, and the sums of red, green and blue
    val n = IntArray(4096)
    val rs = LongArray(4096)
    val gs = LongArray(4096)
    val bs = LongArray(4096)
    for (p in px) {
        if ((p ushr 24) < 128) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        n[k]++
        rs[k] += r.toLong()
        gs[k] += g.toLong()
        bs[k] += b.toLong()
    }
    val out = ArrayList<Int>()
    for (k in (0 until 4096).filter { n[it] > 0 }.sortedByDescending { n[it] }) {
        val r = (rs[k] / n[k]).toInt()
        val g = (gs[k] / n[k]).toInt()
        val b = (bs[k] / n[k]).toInt()
        // Clearly different from the ones already picked
        val far = out.all { c ->
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            dr * dr + dg * dg + db * db > 56 * 56
        }
        if (far) out.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        if (out.size >= count) break
    }
    return out
}
