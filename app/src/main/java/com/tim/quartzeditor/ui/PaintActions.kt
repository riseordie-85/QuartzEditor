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

/**
 * Starts a brush or eraser stroke at canvas point ([x], [y]). Returns false if painting isn't
 * possible. [pen] is the pen's pressure and tilt there (null for a finger).
 */
fun EditorViewModel.beginStroke(x: Float, y: Float, pen: PenSample? = null): Boolean {
    // Smoothing: the brush trails behind on a string (its length grows with the setting; on the
    // screen, so it feels the same at any zoom)
    smoother = currentBrush.smoothing.takeIf { it > 0.001f }?.let {
        StrokeSmoother(x, y, 150f * Math.pow(it.toDouble(), 1.3).toFloat() / scale)
    }
    if (paintTool == Tool.RETOUCH) {
        if (pendingFx != null) {
            applyFx()
            return false
        }
        return beginRetouch(x, y, pen?.pressure ?: 1f)
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
    val erase = paintTool == Tool.ERASER
    val b = if (erase) eraser else brush
    // Folders and adjustment layers have no pixels of their own: brushes paint their mask
    val target = if ((d.editMask || layer.isGroup || layer.isAdjustment) && layer.mask != null) StrokeTarget.MASK else StrokeTarget.PIXELS
    val s = StrokeSession(layer, target, erase, foreground, background, b.opacity, b.tip, b.size, b.softness, d.selection, strokeDynamics(b, pen != null), b.style, customTip = b.customTip)
    s.addPoint(x, y, pen?.pressure ?: 1f, pen?.tilt ?: 0f, pen?.azimuth ?: 0f)
    stroke = s
    d.invalidate()
    return true
}

/**
 * The brush on a string: it only moves once the pen is more than [radius] (canvas pixels) away, and
 * then just enough to keep up, so wobbles smaller than the string never reach the line.
 */
internal class StrokeSmoother(var x: Float, var y: Float, private val radius: Float) {
    /** Where the pen really is, and how it's held there. */
    var rawX = x
    var rawY = y
    var rawPen: PenSample? = null

    /** The pen moved to ([px], [py]): true when the brush moved too (to ([x], [y])). */
    fun pull(px: Float, py: Float, pen: PenSample?): Boolean {
        rawX = px
        rawY = py
        rawPen = pen
        val dx = px - x
        val dy = py - y
        val d = kotlin.math.hypot(dx, dy)
        if (d <= radius) return false
        val k = (d - radius) / d
        x += dx * k
        y += dy * k
        return true
    }
}

/** The stroke's next point; [pen] as for [beginStroke]. */
fun EditorViewModel.strokeTo(x: Float, y: Float, pen: PenSample? = null) {
    val sm = smoother
    if (sm != null) {
        if (!sm.pull(x, y, pen)) return
        addStrokePoint(sm.x, sm.y, pen)
    } else {
        addStrokePoint(x, y, pen)
    }
}

private fun EditorViewModel.addStrokePoint(x: Float, y: Float, pen: PenSample?) {
    retouch?.let {
        it.addPoint(x, y, retouchPressure(pen?.pressure ?: 1f))
        doc?.invalidate()
        return
    }
    val s = stroke ?: return
    s.addPoint(x, y, pen?.pressure ?: 1f, pen?.tilt ?: 0f, pen?.azimuth ?: 0f)
    if (strokeHeals == com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL) checkHealReach(x, y)
    doc?.invalidate()
}

fun EditorViewModel.endStroke() {
    // Smoothing: the line catches up to where the pen lifted
    smoother?.let { sm ->
        smoother = null
        if (kotlin.math.hypot(sm.rawX - sm.x, sm.rawY - sm.y) > 0.5f) addStrokePoint(sm.rawX, sm.rawY, sm.rawPen)
    }
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
    // The end taper: the last stretch thins out now that it's known where the stroke ends
    s.finish()
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
    smoother = null
    cancelRetouch()
    strokeHeals = null
    stroke = null
    doc?.invalidate()
}
