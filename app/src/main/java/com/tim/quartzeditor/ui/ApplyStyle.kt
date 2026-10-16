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
// opacity, blend mode, mask and clipping; Fill is baked in.

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
    // Drawn on its own: normal blending at full opacity, no mask, not clipped (those stay on the layer)
    val alone = Layer(
        l.id,
        l.props.copy(opacity = 1f, blend = Blend.NORMAL, clip = false, visible = true, maskDisabled = true),
        TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }, l.x, l.y, null, l.kind,
    )
    val source = l.tiles
    val area = l.props.style.effectRegion(content).apply {
        offset(l.x, l.y)
        if (!intersect(Transformer.limitFor(d.width, d.height))) setEmpty()
    }
    if (area.isEmpty) return
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
            val props = l.props.copy(style = LayerStyle(), fill = 1f, shape = null, text = null)
            if (l.isVector) {
                val px = Layer(l.id, props, grid, l.x, l.y, l.mask, LayerKind.PIXELS)
                px.maskThumb = l.maskThumb
                d.layers[d.indexOf(l.id)] = px
            } else {
                l.tiles = grid
                l.props = props
            }
        }
    }
}
