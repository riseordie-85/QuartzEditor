package com.tim.quartzeditor.ui

import android.graphics.Canvas
import android.graphics.Rect
import com.tim.quartzeditor.engine.Blend
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Cut, Copy and Paste (the button at the bottom left of the picture, and Ctrl+X / C / V).
// What's copied stays in the app: pixels, at the place they were, ready to paste as a new layer.

/** Copied pixels: tiles at canvas position, and what they were called. */
class ClipItem(val tiles: Map<Long, android.graphics.Bitmap>, val name: String)

/**
 * Copies what's selected on the active layer (or all of it, without a selection) as you see it on
 * that layer: its mask applied, without its effects. [merged]: everything visible instead.
 */
fun EditorViewModel.copyPixels(merged: Boolean = false, then: (() -> Unit)? = null) {
    val d = doc ?: return
    if (pendingFirst { copyPixels(merged, then) }) return
    val sel = d.selection?.takeIf { !it.isEmpty }
    val l = d.active
    if (!merged && (l == null || l.isAdjustment)) {
        toast(if (l == null) "Pick a layer to copy" else "Adjustment layers have no pixels to copy (Copy merged copies what you see)")
        return
    }
    // What's drawn, and where to look
    val layers: List<Layer>
    var region: Rect?
    if (merged) {
        layers = d.children(0L)
        region = Rect(0, 0, d.width, d.height)
    } else {
        val a = l!!
        val alone = Layer(
            a.id, a.props.copy(opacity = 1f, fill = 1f, blend = Blend.NORMAL, clip = false, visible = true, style = LayerStyle(), parent = 0L, blendIf = com.tim.quartzeditor.engine.BlendIf()),
            a.tiles, a.x, a.y, a.mask, if (a.isGroup) LayerKind.GROUP else LayerKind.PIXELS,
        )
        layers = listOf(alone)
        region = if (a.isGroup) Rect(0, 0, d.width, d.height) else a.bounds()
    }
    if (sel != null) {
        val r = Rect(kotlin.math.floor(sel.reach.left).toInt(), kotlin.math.floor(sel.reach.top).toInt(), kotlin.math.ceil(sel.reach.right).toInt(), kotlin.math.ceil(sel.reach.bottom).toInt())
        region = if (region == null || !r.intersect(region)) null else r
    }
    val area = region?.takeIf { it.width() > 0 && it.height() > 0 && it.intersect(Transformer.limitFor(d.width, d.height)) }
    if (area == null) {
        toast("Nothing there to copy")
        return
    }
    val name = if (merged) "Merged" else l!!.props.name
    runBusy("Copying…", onDone = then) {
        val grid = withContext(Dispatchers.Default) {
            val fx = if (merged) fullEffects(d.layers.filter { d.effectivelyVisible(it) }) else emptyMap()
            val bmp = DocRenderer.renderLayers(layers, area, d.width, d.height, effects = { fx[it.id] }, doc = d)
            // Only what's selected
            if (sel != null) {
                val c = Canvas(bmp)
                c.translate(-area.left.toFloat(), -area.top.toFloat())
                sel.keepInside(c, 0f, 0f)
            }
            TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also {
                bmp.recycle()
                it.dropEmptyTiles()
            }
        }
        if (grid.isEmpty) {
            toast("Nothing there to copy")
            return@runBusy
        }
        clip = ClipItem(grid.tiles.toMap(), name)
        toast(if (sel != null) "Copied what's selected" else if (merged) "Copied everything you see" else "Copied \"$name\"")
    }
}

/** Copies, then takes away what was copied: the selected pixels, or the whole layer without a selection. */
fun EditorViewModel.cutPixels() {
    val d = doc ?: return
    if (pendingFirst { cutPixels() }) return
    val l = d.active ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    if (l.isAdjustment) {
        toast("Adjustment layers have no pixels to cut")
        return
    }
    val hasSel = d.selection?.isEmpty == false
    if (hasSel && l.needsRaster) {
        // Cutting part of a shape, text or smart layer changes its pixels: rasterize first
        askRasterize(l) { cutPixels() }
        return
    }
    if (hasSel && l.isGroup) {
        toast("Pick a layer inside the folder to cut part of it (Copy works on the whole folder)")
        return
    }
    val id = l.id
    copyPixels {
        val now = doc?.layer(id) ?: return@copyPixels
        if (clip == null) return@copyPixels
        if (hasSel) clearSelected() else {
            selectLayer(now.id)
            deleteLayer()
        }
    }
}

/**
 * Pastes what was copied as a new layer above the active one, where it was copied from (or in the
 * middle of the picture if that's off the canvas), and picks Move to place it.
 */
fun EditorViewModel.pastePixels() {
    val d = doc ?: return
    if (pendingFirst { pastePixels() }) return
    val c = clip
    if (c == null) {
        if (otherAppHasImage()) pasteFromOtherApp() else toast("Nothing copied yet: select something (or pick a layer) and Copy")
        return
    }
    val grid = TileGrid(alphaOnly = false).also { it.tiles.putAll(c.tiles) }
    val b = grid.tileBounds()?.let { r ->
        com.tim.quartzeditor.engine.Transformer.contentBounds(grid.tiles) ?: r
    } ?: return
    // Off the canvas (copied from a bigger picture): in the middle
    var dx = 0
    var dy = 0
    if (!Rect.intersects(b, Rect(0, 0, d.width, d.height))) {
        dx = d.width / 2 - b.centerX()
        dy = d.height / 2 - b.centerY()
    }
    stackEdit("Paste") {
        val (at, parent) = insertPoint(d)
        val l = Layer(d.newId(), LayerProps(d.uniqueName(c.name.take(30).let { if (it == "Merged") "Pasted" else "$it copy" }), parent = parent), grid, dx, dy)
        d.layers.add(at, l)
        d.activeId = l.id
        d.editMask = false
    }
    if (tool != Tool.MOVE) chooseMove(pan = false)
    toast("Pasted as a new layer. Drag to place it")
}

/** Whether Android's clipboard holds a picture from another app. */
internal fun EditorViewModel.otherAppHasImage(): Boolean = try {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    val desc = cm?.primaryClipDescription
    desc != null && (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
} catch (e: Exception) {
    false
}

/** Pastes a picture another app copied, as a new layer in the middle of the picture. */
fun EditorViewModel.pasteFromOtherApp() {
    val d = doc ?: return
    if (pendingFirst { pasteFromOtherApp() }) return
    val uri = try {
        ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    } catch (e: Exception) {
        null
    }
    if (uri == null) {
        toast("There's no picture to paste from other apps")
        return
    }
    runBusy("Pasting…") {
        val bmp = withContext(Dispatchers.IO) { com.tim.quartzeditor.data.Media.decodeForCanvas(ctx, uri) }
        val grid = withContext(Dispatchers.Default) {
            TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false).also {
                it.dropEmptyTiles()
            }
        }
        val x = (d.width - bmp.width) / 2
        val y = (d.height - bmp.height) / 2
        bmp.recycle()
        if (doc !== d) return@runBusy
        stackEdit("Paste") {
            val (at, parent) = insertPoint(d)
            val l = Layer(d.newId(), LayerProps(d.uniqueName("Pasted"), parent = parent), grid, x, y)
            d.layers.add(at, l)
            d.activeId = l.id
            d.editMask = false
        }
        if (tool != Tool.MOVE) chooseMove(pan = false)
        toast("Pasted as a new layer. Drag to place it, or use Transform to size it")
    }
}
