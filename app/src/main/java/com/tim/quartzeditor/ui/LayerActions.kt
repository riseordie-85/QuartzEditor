package com.tim.quartzeditor.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.StackChange
import com.tim.quartzeditor.engine.TileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Adding, deleting, ordering, grouping, merging and changing layers. (Moved out of EditorViewModel.kt; same code, as extension functions.)

fun EditorViewModel.selectLayer(id: Long, mask: Boolean = false) {
    val d = doc ?: return
    if (id != d.activeId && pendingFirst { selectLayer(id, mask) }) return
    // Picking one layer ends picking several (like Photoshop)
    multiSelected = emptySet()
    d.activeId = id
    val l = d.layer(id)
    d.editMask = l?.hasMask == true && (mask || l.isAdjustment)
    d.invalidate()
    if (pendingFx == null) fxThumb = null
}

/** Where a new layer goes: above the active one, or at the top inside an active folder. */
internal fun EditorViewModel.insertPoint(d: Doc): Pair<Int, Long> {
    val a = d.active ?: return d.layers.size to 0L
    return if (a.isGroup) d.indexOf(a.id) to a.id else (d.indexOf(a.id) + 1) to a.props.parent
}

fun EditorViewModel.addLayer() {
    val d = doc ?: return
    if (pendingFirst { addLayer() }) return
    stackEdit("New layer") {
        val (at, parent) = insertPoint(d)
        val l = Layer(d.newId(), LayerProps(d.uniqueName("Layer"), parent = parent), TileGrid(alphaOnly = false))
        d.layers.add(at, l)
        d.joinClipGroup(l)
        d.activeId = l.id
        d.editMask = false
    }
}

/** A new, empty folder above the active layer. */
fun EditorViewModel.addFolder() {
    val d = doc ?: return
    if (pendingFirst { addFolder() }) return
    stackEdit("New folder") {
        val a = d.active
        val parent = a?.props?.parent ?: 0L
        val at = a?.let { d.indexOf(it.id) + 1 } ?: d.layers.size
        val g = Layer(d.newId(), LayerProps(d.uniqueName("Folder"), parent = parent), TileGrid(alphaOnly = false), kind = LayerKind.GROUP)
        d.layers.add(at, g)
        d.activeId = g.id
        d.editMask = false
    }
    toast("Drag layers just under the folder to put them in it")
}

/** Puts the active layer into a new folder in its place. */
fun EditorViewModel.groupActive() {
    val d = doc ?: return
    if (pendingFirst { groupActive() }) return
    if (isMultiSelecting) {
        groupPicked()
        return
    }
    val a = d.active ?: return
    stackEdit("Put in folder") {
        val g = Layer(d.newId(), LayerProps(d.uniqueName("Folder"), parent = a.props.parent), TileGrid(alphaOnly = false), kind = LayerKind.GROUP)
        d.layers.add(d.indexOf(a.id) + 1, g)
        a.props = a.props.copy(parent = g.id)
        d.activeId = g.id
        d.editMask = false
    }
}

/** An adjustment layer above the active one: changes everything below it without touching pixels. */
fun EditorViewModel.addAdjustmentLayer(kind: com.tim.quartzeditor.engine.FxKind = com.tim.quartzeditor.engine.FxKind.ADJUST) {
    val d = doc ?: return
    if (pendingFirst { addAdjustmentLayer(kind) }) return
    val (name, fx) = when (kind) {
        com.tim.quartzeditor.engine.FxKind.ADJUST -> "Adjustments" to FxParams()
        com.tim.quartzeditor.engine.FxKind.LEVELS -> "Levels" to FxParams(kind = kind, levels = com.tim.quartzeditor.engine.Levels())
        com.tim.quartzeditor.engine.FxKind.CURVES -> "Curves" to FxParams(kind = kind, curves = com.tim.quartzeditor.engine.Curves())
        com.tim.quartzeditor.engine.FxKind.BLUR -> "Blur" to FxParams(kind = kind, blur = 8f)
        com.tim.quartzeditor.engine.FxKind.HUE_SAT -> "Hue/Saturation" to FxParams(kind = kind, hueSat = com.tim.quartzeditor.engine.HueSat())
        com.tim.quartzeditor.engine.FxKind.COLOR_BALANCE -> "Color Balance" to FxParams(kind = kind, colorBalance = com.tim.quartzeditor.engine.ColorBalance())
        com.tim.quartzeditor.engine.FxKind.BLACK_WHITE -> "Black & White" to FxParams(kind = kind, blackWhite = com.tim.quartzeditor.engine.BlackWhite())
        com.tim.quartzeditor.engine.FxKind.PHOTO_FILTER -> "Photo Filter" to FxParams(kind = kind, photoFilter = com.tim.quartzeditor.engine.PhotoFilter())
        com.tim.quartzeditor.engine.FxKind.GRADIENT_MAP -> "Gradient Map" to FxParams(kind = kind, gradientMap = com.tim.quartzeditor.engine.GradientMap(listOf(foreground or 0xFF000000.toInt(), background or 0xFF000000.toInt())))
        com.tim.quartzeditor.engine.FxKind.VIBRANCE -> "Vibrance" to FxParams(kind = kind)
    }
    stackEdit("New $name layer") {
        val (at, parent) = insertPoint(d)
        val l = Layer(
            d.newId(),
            LayerProps(d.uniqueName(name), parent = parent, adjust = fx),
            TileGrid(alphaOnly = false),
            mask = TileGrid(alphaOnly = true),
            kind = LayerKind.ADJUSTMENT,
        )
        d.layers.add(at, l)
        d.joinClipGroup(l)
        d.activeId = l.id
        d.editMask = true
    }
    openPopup(PopupKind.forKind(kind))
    toast("Changes everything below it. Paint its mask to limit where")
}

fun EditorViewModel.deleteLayer() {
    val d = doc ?: return
    if (pendingFirst { deleteLayer() }) return
    if (isMultiSelecting) {
        deletePicked()
        return
    }
    val l = d.active ?: return
    val range = d.block(l)
    if (d.layers.size - (range.last - range.first + 1) < 1) {
        toast("An image needs at least one layer")
        return
    }
    stackEdit(if (l.isGroup) "Delete folder" else "Delete layer") {
        val below = range.first - 1
        for (k in range.reversed()) d.layers.removeAt(k)
        d.activeId = d.layers[below.coerceIn(0, d.layers.lastIndex)].id
        d.editMask = d.active?.isAdjustment == true
    }
}

fun EditorViewModel.duplicateLayer() {
    val d = doc ?: return
    if (pendingFirst { duplicateLayer() }) return
    val l = d.active ?: return
    stackEdit(if (l.isGroup) "Duplicate folder" else "Duplicate layer") {
        val range = d.block(l)
        val ids = HashMap<Long, Long>()
        val copies = d.layers.slice(range).map { src ->
            val id = d.newId()
            ids[src.id] = id
            src to id
        }.map { (src, id) ->
            val parent = ids[src.props.parent] ?: src.props.parent
            val name = if (src === l) "${src.props.name} copy" else src.props.name
            // A smart layer's copy gets contents of its own (editing one doesn't change the other)
            val smart = src.props.smart?.let { sp -> com.tim.quartzeditor.engine.SmartSpec(sp.contents.shallowCopy(), sp.quad.copyOf()) }
            Layer(id, src.props.copy(name = name, locked = false, parent = parent, smart = smart), src.tiles.copy(), src.x, src.y, src.mask?.copy(), src.kind)
        }
        d.layers.addAll(range.last + 1, copies)
        d.activeId = ids.getValue(l.id)
    }
}

/**
 * Moves a layer (with everything in it, for a folder) to sit directly below the row [aboveId]
 * as the Layers panel shows it (top first). If that row is an open folder, the layer goes in at
 * the top of it. [aboveId] null = the very top.
 */
fun EditorViewModel.moveLayerUnder(id: Long, aboveId: Long?) {
    if (pendingFirst { moveLayerUnder(id, aboveId) }) return
    val d = doc ?: return
    val l = d.layer(id) ?: return
    val above = aboveId?.let { d.layer(it) }
    if (above != null && (above === l || d.isInside(above, l))) return
    val newParent: Long
    val anchor: Layer?
    if (above == null) {
        newParent = 0L
        anchor = null
    } else if (above.isGroup && !above.collapsed) {
        newParent = above.id
        anchor = null
    } else {
        newParent = above.props.parent
        anchor = above
    }
    // Children of each folder, top first
    val kids = HashMap<Long, MutableList<Layer>>()
    for (x in d.layers.asReversed()) kids.getOrPut(x.props.parent) { mutableListOf() }.add(x)
    kids[l.props.parent]?.remove(l)
    val list = kids.getOrPut(newParent) { mutableListOf() }
    val at = if (anchor == null) 0 else list.indexOf(anchor) + 1
    list.add(at.coerceIn(0, list.size), l)
    val order = ArrayList<Layer>(d.layers.size)
    fun emit(parent: Long) {
        for (x in (kids[parent] ?: return).asReversed()) {
            if (x.isGroup) emit(x.id)
            order.add(x)
        }
    }
    emit(0L)
    if (order.size != d.layers.size) return
    val sameOrder = order.indices.all { order[it] === d.layers[it] } && l.props.parent == newParent
    if (sameOrder) return
    stackEdit("Move layer") {
        l.props = l.props.copy(parent = newParent)
        d.layers.clear()
        d.layers.addAll(order)
    }
}

/**
 * Clipping mask on or off for the active layer: clipped, it only shows where the layer below
 * it has pixels (Photoshop's Create / Release Clipping Mask).
 */
fun EditorViewModel.toggleClip() {
    val d = doc ?: return
    if (pendingFirst { toggleClip() }) return
    val l = d.active ?: return
    if (l.isGroup && !l.props.clip) {
        toast("Folders can't be clipped. Clip the layers inside, or merge the folder first")
        return
    }
    if (!l.props.clip) {
        // Clipped to the nearest unclipped layer below it in the same folder
        val sibs = d.children(l.props.parent)
        val below = sibs.subList(0, sibs.indexOf(l).coerceAtLeast(0)).lastOrNull { !it.props.clip }
        if (below == null || !below.hasContent) {
            toast(if (below == null) "There's no layer below to clip to" else "Clip to a pixel, shape or text layer")
            return
        }
    }
    setProps(l.id, if (l.props.clip) "Release clipping mask" else "Create clipping mask") { it.copy(clip = !it.clip) }
}

/** The layer this one is clipped to (null when it isn't clipped, or has nothing to clip to). */
fun EditorViewModel.clipBaseOf(l: Layer): Layer? {
    if (!l.props.clip) return null
    val d = doc ?: return null
    val sibs = d.children(l.props.parent)
    val i = sibs.indexOf(l)
    if (i <= 0) return null
    return sibs.subList(0, i).lastOrNull { !it.props.clip }?.takeIf { it.hasContent }
}

/** The layer directly below [l] in the same folder, if any. */
internal fun EditorViewModel.siblingBelow(d: Doc, l: Layer): Layer? {
    val sibs = d.children(l.props.parent)
    val i = sibs.indexOf(l)
    return if (i > 0) sibs[i - 1] else null
}

fun EditorViewModel.mergeDown() {
    val d = doc ?: return
    if (pendingFirst { mergeDown() }) return
    if (isMultiSelecting) {
        mergePicked()
        return
    }
    val top = d.active ?: return
    if (top.isGroup) {
        mergeFolder()
        return
    }
    val below = siblingBelow(d, top)
    if (below == null || !below.hasContent) {
        toast(if (below == null) "There's no layer below to merge into" else "Merge into a pixel, shape or text layer (not a folder or adjustment)")
        return
    }
    if (top.props.locked || below.props.locked) {
        toast("Unlock both layers to merge them")
        return
    }
    runBusy("Merging…") {
        val region = union(top.bounds(), below.bounds()) ?: Rect(0, 0, d.width, d.height)
        val merged = withContext(Dispatchers.Default) {
            val pair = listOf(below, top).filter { it.props.visible }
            val fx = fullEffects(pair)
            // Room for shadows and strokes that reach past the pixels
            val reach = pair.maxOfOrNull { kotlin.math.ceil(if (it.props.style.any) it.props.style.reach else 0f).toInt() + 4 } ?: 0
            region.inset(-reach, -reach)
            if (top.isAdjustment) region.set(union(region, Rect(0, 0, d.width, d.height))!!)
            val bmp = DocRenderer.renderLayers(pair, region, d.width, d.height, effects = { fx[it.id] }, doc = d)
            TileGrid.fromBitmap(bmp, region.left, region.top, alphaOnly = false).also { bmp.recycle() }
        }
        stackEdit("Merge down") {
            val props = below.props.copy(opacity = 1f, fill = 1f, blend = com.tim.quartzeditor.engine.Blend.NORMAL, visible = true, maskDisabled = false, style = LayerStyle(), shape = null, text = null, smart = null)
            if (below.needsRaster) {
                // Merging into a shape or text layer leaves pixels
                d.layers[d.indexOf(below.id)] = Layer(below.id, props, merged, 0, 0, null, LayerKind.PIXELS)
            } else {
                below.tiles = merged
                below.x = 0
                below.y = 0
                below.mask = null
                below.props = props
            }
            d.layers.remove(top)
            d.activeId = below.id
            d.editMask = false
        }
    }
}

/** Turns the active folder into one pixel layer, in its place (keeping the folder's blend, opacity and visibility). */
fun EditorViewModel.mergeFolder() {
    val d = doc ?: return
    if (pendingFirst { mergeFolder() }) return
    val g = d.active?.takeIf { it.isGroup } ?: return
    runBusy("Merging folder…") {
        val inside = d.layers.slice(d.block(g)).filter { it !== g }
        var region: Rect? = null
        for (l in inside) region = union(region, l.bounds()?.apply {
            val r = kotlin.math.ceil(if (l.props.style.any) l.props.style.reach else 0f).toInt() + 4
            inset(-r, -r)
        })
        val area = union(region, Rect(0, 0, d.width, d.height))!!
        val mask = if (g.maskActive) g.mask else null
        val merged = withContext(Dispatchers.Default) {
            val fx = fullEffects(inside)
            // The folder's contents as they look inside it; its own blend and opacity stay on the result
            val bmp = DocRenderer.renderLayers(d.children(g.id), area, d.width, d.height, effects = { fx[it.id] }, doc = d)
            if (mask != null) {
                val c = Canvas(bmp)
                c.translate(-area.left.toFloat(), -area.top.toFloat())
                mask.drawTo(c, g.x.toFloat(), g.y.toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() }
        }
        stackEdit("Merge folder") {
            val range = d.block(g)
            val p = g.props
            val l = Layer(d.newId(), LayerProps(p.name, visible = p.visible, opacity = p.opacity, fill = p.fill, blend = if (p.passThrough) com.tim.quartzeditor.engine.Blend.NORMAL else p.blend, parent = p.parent, clip = p.clip), merged)
            for (k in range.reversed()) d.layers.removeAt(k)
            d.layers.add(range.first, l)
            d.activeId = l.id
            d.editMask = false
        }
    }
}

/** Merges every visible layer into one (hidden layers are discarded, like Photoshop). */
fun EditorViewModel.flattenImage() {
    val d = doc ?: return
    if (pendingFirst { flattenImage() }) return
    if (d.layers.size <= 1 && d.layers.firstOrNull()?.isPixels == true) return
    runBusy("Flattening…") {
        val top = d.children(0L)
        val region = Rect(0, 0, d.width, d.height)
        val merged = withContext(Dispatchers.Default) {
            val fx = fullEffects(d.layers.filter { d.effectivelyVisible(it) })
            val bmp = DocRenderer.renderLayers(top, region, d.width, d.height, effects = { fx[it.id] }, doc = d)
            TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false).also { bmp.recycle() }
        }
        stackEdit("Flatten image") {
            val l = Layer(d.newId(), LayerProps("Background"), merged)
            d.layers.clear()
            d.layers.add(l)
            d.activeId = l.id
            d.editMask = false
        }
    }
}

internal fun EditorViewModel.union(a: Rect?, b: Rect?): Rect? = when {
    a == null -> b
    b == null -> a
    else -> Rect(a).apply { union(b) }
}

fun EditorViewModel.setPropsLive(id: Long, change: (LayerProps) -> LayerProps) {
    val d = doc ?: return
    val l = d.layer(id) ?: return
    if (textEdit != null) finishText()
    if (propsBefore == null) propsBefore = d.snapshot()
    l.props = change(l.props)
    d.invalidate()
}

fun EditorViewModel.commitProps(label: String) {
    val d = doc ?: return
    val before = propsBefore ?: return
    propsBefore = null
    history.record(StackChange(before, d.snapshot(), label))
}

/** A one-shot change (toggle, rename, blend mode). */
fun EditorViewModel.setProps(id: Long, label: String, change: (LayerProps) -> LayerProps) {
    val d = doc ?: return
    val l = d.layer(id) ?: return
    val next = change(l.props)
    if (next == l.props) return
    stackEdit(label) { l.props = next }
}

fun EditorViewModel.setLayerPosition(id: Long, x: Int, y: Int) {
    if (pendingFirst { setLayerPosition(id, x, y) }) return
    val d = doc ?: return
    val l = d.layer(id) ?: return
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    if (l.x == x && l.y == y) return
    val dx = x - l.x
    val dy = y - l.y
    val smartRedo = ArrayList<Layer>()
    stackEdit("Move") {
        // A folder moves everything in it
        for (m in if (l.isGroup) d.layers.slice(d.block(l)) else listOf(l)) {
            val clipped = vectorClipped(m, m.x, m.y, d.width, d.height)
            if (smartClipped(m, m.x, m.y, d)) smartRedo.add(m)
            m.x += dx
            m.y += dy
            if (clipped) redrawVectorNow(m)
            if (smartClipped(m, m.x, m.y, d)) smartRedo.add(m)
        }
    }
    // A smart layer that reached past the canvas is drawn again from its contents
    for (m in smartRedo.distinct()) redrawSmartSoon(d, m)
}

internal fun EditorViewModel.editableActive(): Layer? {
    val l = doc?.active ?: return null
    if (l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return null
    }
    return l
}

fun EditorViewModel.clearLayer() {
    if (pendingFirst { clearLayer() }) return
    val l = editableActive() ?: return
    if (l.needsRaster && !(doc?.editMask == true && l.mask != null)) {
        askRasterize(l) { clearLayer() }
        return
    }
    if (doc?.editMask == true && l.mask != null) {
        stackEdit("Clear mask") { l.mask = TileGrid(alphaOnly = true) }
        return
    }
    if (l.tiles.isEmpty) return
    stackEdit("Clear layer") { l.tiles = TileGrid(alphaOnly = false) }
}

/** Fills the selection (or the canvas area) of the active layer (or its mask) with [color]. */
fun EditorViewModel.fillLayer(color: Int) = fillWithColor(color)
