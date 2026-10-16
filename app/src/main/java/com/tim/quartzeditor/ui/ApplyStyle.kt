package com.tim.quartzeditor.ui

import com.tim.quartzeditor.engine.Blend
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Apply layer style (Photoshop's "Rasterize Layer Style"): the layer's effects become part of its
// pixels, and the style is cleared. Shape and text layers become pixel layers. The layer keeps its
// opacity, blend mode and clipping; Fill is baked in. A mask that's on is applied too (effects are
// made from the masked shape and aren't cut by the mask, so only applying it keeps the look); a
// mask that's off stays as it is.

/** Whether layer [l] has a style that can be applied to its pixels. */
fun canApplyStyle(l: Layer?): Boolean = l != null && l.hasContent && l.props.style.any

fun EditorViewModel.applyLayerStyle(id: Long) {
    val d = doc ?: return
    if (pendingFirst { applyLayerStyle(id) }) return
    val l = d.layer(id) ?: return
    if (!canApplyStyle(l)) {
        toast("This layer has no layer style to apply")
        return
    }
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    val content = l.tiles.tileBounds() ?: run {
        toast("This layer is empty")
        return
    }
    // Drawn on its own, as on screen: normal blending at full opacity, not clipped (those stay on the layer)
    val applyMask = l.maskActive
    val alone = Layer(
        l.id,
        l.props.copy(opacity = 1f, blend = Blend.NORMAL, clip = false, visible = true),
        TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }, l.x, l.y,
        if (applyMask) l.mask?.let { m -> TileGrid(alphaOnly = true).also { it.tiles.putAll(m.tiles) } } else null, l.kind,
    )
    val source = l.tiles
    val area = l.props.style.effectRegion(content).apply {
        offset(l.x, l.y)
        if (!intersect(Transformer.limitFor(d.width, d.height))) setEmpty()
    }
    if (area.isEmpty) {
        toast("This layer is too far off the canvas")
        return
    }
    runBusy("Applying layer style…") {
        val grid = withContext(Dispatchers.Default) {
            val fx = fullEffects(listOf(alone))
            val bmp = DocRenderer.renderLayers(listOf(alone), area, d.width, d.height, effects = { fx[it.id] }, doc = d)
            // In the layer's own coordinates, so its mask still lines up
            TileGrid.fromBitmap(bmp, area.left - l.x, area.top - l.y, alphaOnly = false).also {
                bmp.recycle()
                it.dropEmptyTiles()
            }
        }
        if (doc !== d || l.tiles !== source) return@runBusy
        stackEdit("Apply layer style") {
            val props = l.props.copy(style = LayerStyle(), fill = 1f, shape = null, text = null, maskDisabled = if (applyMask) false else l.props.maskDisabled)
            val mask = if (applyMask) null else l.mask
            if (l.isVector) {
                val px = Layer(l.id, props, grid, l.x, l.y, mask, LayerKind.PIXELS)
                if (mask != null) px.maskThumb = l.maskThumb
                // Newer than anything cached for the old shape or text layer
                px.pixelVersion = l.pixelVersion + 1
                d.layers[d.indexOf(l.id)] = px
            } else {
                l.tiles = grid
                l.mask = mask
                l.props = props
            }
            if (applyMask) d.editMask = false
        }
        if (applyMask) toast("The mask was applied too")
    }
}
