package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.Cutout
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.TileGrid
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Layer masks: adding, applying, inverting, masks from selections. (Moved out of EditorViewModel.kt; same code, as extension functions.)

fun EditorViewModel.addMask() {
    val d = doc ?: return
    if (pendingFirst { addMask() }) return
    val l = editableActive() ?: return
    if (l.mask != null) {
        selectLayer(l.id, mask = true)
        if (d.selection != null) toast("This layer already has a mask. Mask from selection (in ⋮) replaces it")
        return
    }
    // With a selection (like Photoshop): the mask shows what's selected and hides the rest
    if (d.selection != null) {
        maskFromSelection()
        return
    }
    stackEdit("Add mask") {
        l.mask = TileGrid(alphaOnly = true)
        l.props = l.props.copy(maskDisabled = false)
        d.editMask = true
    }
    toast("Mask added: paint black to hide, white to show")
}

fun EditorViewModel.deleteMask() {
    if (pendingFirst { deleteMask() }) return
    val d = doc ?: return
    val l = editableActive() ?: return
    if (l.mask == null) return
    stackEdit("Delete mask") {
        l.mask = null
        d.editMask = false
    }
}

/** Bakes the mask into the pixels and removes it. */
fun EditorViewModel.applyMask() {
    if (pendingFirst { applyMask() }) return
    val d = doc ?: return
    val l = editableActive() ?: return
    val m = l.mask ?: return
    if (l.needsRaster) {
        askRasterize(l) { applyMask() }
        return
    }
    val grid = l.tiles.copy()
    val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    for ((k, tile) in grid.tiles) {
        val mt = m[k] ?: continue
        Canvas(tile).drawBitmap(mt, 0f, 0f, p)
    }
    stackEdit("Apply mask") {
        l.tiles = grid
        l.mask = null
        l.props = l.props.copy(maskDisabled = false)
        d.editMask = false
    }
}

fun EditorViewModel.invertMask() {
    if (pendingFirst { invertMask() }) return
    val d = doc ?: return
    val l = editableActive() ?: return
    val m = l.mask ?: return
    val grid = m.copy()
    val area = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat()).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
    for (k in grid.keysIn(area)) grid.getOrCreate(k)
    val inv = Paint().apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.XOR)
    }
    for (tile in grid.tiles.values) {
        // XOR with opaque black: alpha becomes 1 - alpha
        Canvas(tile).drawRect(0f, 0f, TileGrid.SIZE.toFloat(), TileGrid.SIZE.toFloat(), inv)
    }
    stackEdit("Invert mask") { l.mask = grid }
}

fun EditorViewModel.toggleMaskEnabled() {
    if (pendingFirst { toggleMaskEnabled() }) return
    val l = doc?.active ?: return
    if (l.mask == null) return
    setProps(l.id, if (l.props.maskDisabled) "Enable mask" else "Disable mask") { it.copy(maskDisabled = !it.maskDisabled) }
}

/** Masks out the background of the active layer (on-device). The mask can be touched up with the brush. */
fun EditorViewModel.removeBackground() {
    val d = doc ?: return
    if (pendingFirst { removeBackground() }) return
    val l = editableActive() ?: return
    if (l.tiles.isEmpty) {
        toast("This layer is empty")
        return
    }
    runBusy("Finding the subject…") {
        val (bmp, bounds) = withContext(Dispatchers.Default) { DocRenderer.layerBitmap(l) } ?: return@runBusy
        val hidden = Cutout.backgroundMask(bmp)
        bmp.recycle()
        val mask = withContext(Dispatchers.Default) {
            TileGrid.fromBitmap(hidden, bounds.left.toInt(), bounds.top.toInt(), alphaOnly = true).also { hidden.recycle() }
        }
        stackEdit("Remove background") {
            l.mask = mask
            l.props = l.props.copy(maskDisabled = false)
            d.activeId = l.id
            d.editMask = true
        }
        toast("Background masked. Paint white on the mask to bring parts back")
    }
}

