package com.tim.quartzeditor.ui

import android.graphics.Rect
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.TileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Several layers picked at once (swipe right on a row in the Layers panel). Moving, Transform,
// delete, put in a folder, merge and layer styles work on all of them. Tapping a layer picks just
// that one again.

/** The picked layers (just the active one when not picking several), that still exist. */
fun EditorViewModel.selectedLayerIds(): List<Long> {
    val d = doc ?: return emptyList()
    val ids = if (multiSelected.size > 1) multiSelected else setOf(d.activeId)
    return ids.filter { d.layer(it) != null }
}

/** Several layers are picked. */
val EditorViewModel.isMultiSelecting: Boolean get() = selectedLayerIds().size > 1

/** Adds [id] to the picked layers, or takes it out. */
fun EditorViewModel.toggleMultiSelect(id: Long) {
    val d = doc ?: return
    if (pendingFirst { toggleMultiSelect(id) }) return
    val current = (if (multiSelected.size > 1) multiSelected else setOf(d.activeId)).filter { d.layer(it) != null }.toMutableSet()
    if (id in current) {
        current.remove(id)
        if (current.isEmpty()) return
        if (d.activeId == id) d.activeId = current.last()
    } else {
        current.add(id)
        // The one just added is the one Properties shows
        d.activeId = id
        d.editMask = false
    }
    multiSelected = if (current.size > 1) current else emptySet()
    d.invalidate()
    if (current.size > 1) toast("${current.size} layers picked. Tap a layer to pick just that one")
}

fun EditorViewModel.clearMultiSelect() {
    multiSelected = emptySet()
}

/** The picked layers, bottom first, without any that sit inside another picked folder. */
internal fun EditorViewModel.pickedRoots(): List<Layer> {
    val d = doc ?: return emptyList()
    val chosen = selectedLayerIds().mapNotNull { d.layer(it) }
    return chosen.filter { c -> chosen.none { g -> g !== c && g.isGroup && d.isInside(c, g) } }.sortedBy { d.indexOf(it.id) }
}

/** Deletes all the picked layers (folders with what's in them). */
internal fun EditorViewModel.deletePicked() {
    val d = doc ?: return
    val roots = pickedRoots()
    val doomed = roots.flatMap { r -> d.layers.slice(d.block(r)) }.toSet()
    if (doomed.size >= d.layers.size) {
        toast("An image needs at least one layer")
        return
    }
    stackEdit("Delete ${roots.size} layers") {
        val lowest = doomed.minOf { d.indexOf(it.id) }
        d.layers.removeAll(doomed)
        d.activeId = d.layers[(lowest - 1).coerceIn(0, d.layers.lastIndex)].id
        d.editMask = false
    }
    multiSelected = emptySet()
}

/** Puts all the picked layers into a new folder where the topmost of them was. */
internal fun EditorViewModel.groupPicked() {
    val d = doc ?: return
    val roots = pickedRoots()
    if (roots.isEmpty()) return
    val top = roots.last()
    stackEdit("Put in folder") {
        val g = Layer(d.newId(), LayerProps(d.uniqueName("Folder"), parent = top.props.parent), TileGrid(alphaOnly = false), kind = LayerKind.GROUP)
        d.layers.add(d.indexOf(top.id) + 1, g)
        for (r in roots) r.props = r.props.copy(parent = g.id, clip = false)
        d.normalizeTree()
        d.activeId = g.id
        d.editMask = false
    }
    multiSelected = emptySet()
}

/** Merges the picked layers into one, where the topmost of them was (like Photoshop's Merge Layers). */
internal fun EditorViewModel.mergePicked() {
    val d = doc ?: return
    val roots = pickedRoots()
    if (roots.any { it.isGroup }) {
        toast("Merge folders on their own first (⋮ → Merge folder)")
        return
    }
    if (roots.any { it.props.locked }) {
        toast("Unlock the layers to merge them")
        return
    }
    if (roots.size < 2) return
    if (roots.map { it.props.parent }.distinct().size > 1) {
        toast("Pick layers from the same folder to merge them")
        return
    }
    val top = roots.last()
    // A clipped layer whose base isn't being merged is drawn as it is, not clipped to whatever
    // picked layer happens to be below it
    val drawn = roots.map { l ->
        val base = clipBaseOf(l)
        if (l.props.clip && (base == null || base !in roots)) Layer(l.id, l.props.copy(clip = false), l.tiles, l.x, l.y, l.mask, l.kind) else l
    }
    runBusy("Merging…") {
        var region: Rect? = null
        for (l in roots) region = union(region, l.bounds()?.apply {
            val r = kotlin.math.ceil(if (l.props.style.any) l.props.style.reach else 0f).toInt() + 4
            inset(-r, -r)
        })
        val area = union(region, if (roots.any { it.isAdjustment }) Rect(0, 0, d.width, d.height) else null) ?: Rect(0, 0, d.width, d.height)
        val merged = withContext(Dispatchers.Default) {
            val visible = drawn.filter { it.props.visible }
            val fx = fullEffects(visible)
            val bmp = DocRenderer.renderLayers(visible, area, d.width, d.height, effects = { fx[it.id] }, doc = d)
            TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() }
        }
        stackEdit("Merge layers") {
            val l = Layer(d.newId(), LayerProps(top.props.name, parent = top.props.parent), merged)
            val at = d.indexOf(top.id)
            d.layers.add(at + 1, l)
            d.layers.removeAll(roots.toSet())
            d.activeId = l.id
            d.editMask = false
        }
        multiSelected = emptySet()
    }
}

/** Puts [id] (with everything in it, for a folder) into folder [folderId], at the top of it. */
fun EditorViewModel.moveIntoFolder(id: Long, folderId: Long) {
    val d = doc ?: return
    if (pendingFirst { moveIntoFolder(id, folderId) }) return
    val l = d.layer(id) ?: return
    val g = d.layer(folderId)?.takeIf { it.isGroup } ?: return
    if (l === g || d.isInside(g, l)) return
    if (l.props.parent == g.id && d.children(g.id).lastOrNull() === l) return
    stackEdit("Move into ${g.props.name}") {
        val block = d.layers.slice(d.block(l))
        d.layers.removeAll(block.toSet())
        // Directly below the folder = at the top of what's in it
        d.layers.addAll(d.indexOf(g.id), block)
        l.props = l.props.copy(parent = g.id, clip = false)
        d.normalizeTree()
    }
    g.collapsed = false
    toast("Moved into \"${g.props.name}\"")
}

/**
 * Picks layer [id] and then runs [action] on it (for a layer's long-press menu). Anything pending
 * is applied first, and only then is the layer picked, so the action can't land on another layer.
 */
fun EditorViewModel.onLayer(id: Long, action: EditorViewModel.() -> Unit) {
    if (pendingFirst { onLayer(id, action) }) return
    if (doc?.layer(id) == null) return
    selectLayer(id)
    action()
}
