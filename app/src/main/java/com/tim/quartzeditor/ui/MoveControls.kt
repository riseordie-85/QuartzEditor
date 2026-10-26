package com.tim.quartzeditor.ui

import android.graphics.RectF
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Move tool › Show transform controls (like Photoshop): a box with handles around what Free
// Transform would work on. Grabbing a handle switches to Free Transform with that drag; inside the
// box, dragging still just moves the layer.

/** The layers Free Transform works on: every picked one with pixels (a picked folder brings what's in it), or the active one. */
internal fun EditorViewModel.transformTargets(): List<Layer> {
    val d = doc ?: return emptyList()
    val chosen = selectedLayerIds().mapNotNull { d.layer(it) }
    return if (chosen.size > 1) {
        pickedRoots().flatMap { r -> if (r.isGroup) d.layers.slice(d.block(r)) else listOf(r) }.filter { it.hasContent }.distinct()
    } else listOfNotNull(d.active)
}

fun EditorViewModel.toggleTransformControls() {
    showTransformControls = !showTransformControls
    prefs.edit().putBoolean("moveTransformControls", showTransformControls).apply()
    if (!showTransformControls) moveBox = null
}

/**
 * Works out [moveBox] (in canvas pixels, in the background): the box around what Free Transform
 * would take, or null with nothing to transform. Called when the document, the picked layers or the tool change.
 */
internal suspend fun EditorViewModel.refreshMoveBox() {
    val d = doc
    if (d == null || !showTransformControls || tool != Tool.MOVE) {
        moveBox = null
        return
    }
    val layers = transformTargets().filter { !it.isGroup && !it.isAdjustment && !it.tiles.isEmpty }
    if (layers.isEmpty()) {
        moveBox = null
        return
    }
    val parts = layers.map { Triple(HashMap(it.tiles.tiles), it.x, it.y) }
    val box = withContext(Dispatchers.Default) {
        var u: RectF? = null
        for ((tiles, x, y) in parts) {
            val b = runCatching { Transformer.contentBounds(tiles) }.getOrNull() ?: continue
            val r = RectF(b).apply { offset(x.toFloat(), y.toFloat()) }
            if (u == null) u = r else u.union(r)
        }
        u
    }
    if (doc === d) {
        moveBox = box
        moveBoxLocked = layers.any { it.props.locked }
    }
}

/** A handle of the Move box was grabbed: Free Transform starts (the drag carries on once it's ready). */
internal fun EditorViewModel.transformFromMoveBox(): Boolean {
    if (moveBoxLocked) {
        toast("This layer is locked")
        return false
    }
    onToolButton(Tool.TRANSFORM)
    return tool == Tool.TRANSFORM
}
