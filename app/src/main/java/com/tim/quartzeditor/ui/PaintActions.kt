package com.tim.quartzeditor.ui

import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.StrokeSession
import com.tim.quartzeditor.engine.StrokeTarget

// Brush and eraser strokes on the active layer. (Moved out of EditorViewModel.kt; same code, as extension functions.)

/** Why the active layer can't be painted on right now (null = it can). */
internal fun EditorViewModel.paintBlocker(layer: Layer?): String? = when {
    layer == null -> "Add a layer first"
    layer.props.locked -> "\"${layer.props.name}\" is locked"
    (layer.isGroup || layer.isAdjustment) && layer.mask == null -> if (layer.isGroup) "Pick a layer inside the folder to paint on (or add a mask to the folder)" else "This adjustment layer has no mask to paint"
    !layer.props.visible -> "\"${layer.props.name}\" is hidden"
    (doc?.editMask == true || layer.isGroup || layer.isAdjustment) && layer.mask != null && layer.props.maskDisabled -> "The mask is turned off. Turn it on to paint it"
    // Shape and text layers keep their shape or words: painting their pixels needs rasterizing
    layer.needsRaster && !(doc?.editMask == true && layer.mask != null) -> NEEDS_RASTER
    else -> null
}

/** Why a brush stroke can't start right now (null = it can). Checked before painting. */
fun EditorViewModel.strokeBlockReason(): String? = when {
    pendingFx != null -> "Apply or cancel the adjustment first"
    transform != null -> "Apply or cancel the transform first"
    warp != null -> "Apply or cancel the ${warp?.kind?.label?.lowercase() ?: "warp"} first"
    cropBox != null -> "Apply or cancel the crop first"
    else -> paintBlocker(doc?.active)
}

/** A one-finger touch that couldn't paint: apply a pending adjustment, or say why. */
fun EditorViewModel.onBlockedStroke() {
    when {
        pendingFx != null -> applyFx()
        transform != null -> applyTransform()
        else -> strokeBlockReason()?.let { r ->
            val l = doc?.active
            if (r == NEEDS_RASTER && l != null) askRasterize(l, null) else toast(r)
        }
    }
}

/** Starts a brush or eraser stroke at canvas point ([x], [y]). Returns false if painting isn't possible. */
fun EditorViewModel.beginStroke(x: Float, y: Float): Boolean {
    if (tool == Tool.RETOUCH) {
        if (pendingFx != null) {
            applyFx()
            return false
        }
        return beginRetouch(x, y)
    }
    strokeHeals = null
    val d = doc ?: return false
    if (pendingFx != null) {
        applyFx()
        return false
    }
    val layer = d.active
    if (blockPaint(layer)) return false
    layer!!
    val erase = tool == Tool.ERASER
    val b = if (erase) eraser else brush
    // Folders and adjustment layers have no pixels of their own: brushes paint their mask
    val target = if ((d.editMask || layer.isGroup || layer.isAdjustment) && layer.mask != null) StrokeTarget.MASK else StrokeTarget.PIXELS
    val s = StrokeSession(layer, target, erase, foreground, background, b.opacity, b.tip, b.size, b.softness, d.selection)
    s.addPoint(x, y)
    stroke = s
    d.invalidate()
    return true
}

fun EditorViewModel.strokeTo(x: Float, y: Float) {
    retouch?.let {
        it.addPoint(x, y)
        doc?.invalidate()
        return
    }
    val s = stroke ?: return
    s.addPoint(x, y)
    if (strokeHeals == com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL) checkHealReach(x, y)
    doc?.invalidate()
}

fun EditorViewModel.endStroke() {
    if (retouch != null) {
        endRetouch()
        return
    }
    val s = stroke ?: return
    val d = doc ?: return
    // Painted where to heal or remove (decided when the stroke started)
    val heals = strokeHeals
    strokeHeals = null
    if (heals != null) {
        s.lastPoint?.let { lastStrokeEnd = Triple(s.layer.id, it.x, it.y) }
        finishSpotHeal(s, ai = heals == com.tim.quartzeditor.engine.RetouchKind.AI_REMOVE)
        return
    }
    s.lastPoint?.let { lastStrokeEnd = Triple(s.layer.id, it.x, it.y) }
    val change = s.commit()
    stroke = null
    if (change != null) {
        history.record(change)
        d.touched(s.layer)
    } else {
        d.invalidate()
    }
}

/** Drops a stroke that turned out to be the start of a two-finger gesture. */
fun EditorViewModel.cancelStroke() {
    cancelRetouch()
    strokeHeals = null
    stroke = null
    doc?.invalidate()
}
