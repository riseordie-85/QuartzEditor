package com.tim.quartzeditor.ui

import com.tim.quartzeditor.engine.SelectShape
import com.tim.quartzeditor.engine.ShapeKind

// Picking a tool straight from a toolbar button's long-press list (instead of tapping through).

fun EditorViewModel.chooseMove(pan: Boolean) {
    if (pendingFirst { chooseMove(pan) }) return
    val want = if (pan) Tool.PAN else Tool.MOVE
    if (tool == want) return
    if (tool != Tool.MOVE && tool != Tool.PAN) {
        onToolButton(Tool.MOVE)
        if (tool != Tool.MOVE) return
    }
    // Tapping Move while on Move or Pan switches between them
    if (tool != want) onToolButton(Tool.MOVE)
}

fun EditorViewModel.chooseSelectShape(s: SelectShape) {
    if (pendingFirst { chooseSelectShape(s) }) return
    if (tool != Tool.SELECT) {
        onToolButton(Tool.SELECT)
        if (tool != Tool.SELECT) return
    }
    if (selectShape == s) return
    selectShape = s
    prefs.edit().putInt("selectShape", s.ordinal).apply()
    polygonPoints.clear()
    selectDraft = null
}

fun EditorViewModel.chooseShapeKind(k: ShapeKind) {
    if (pendingFirst { chooseShapeKind(k) }) return
    if (tool != Tool.SHAPE) {
        onToolButton(Tool.SHAPE)
        if (tool != Tool.SHAPE) return
    }
    shapeKind = k
    saveVectorPrefs()
}

fun EditorViewModel.choosePen(edit: Boolean) {
    if (pendingFirst { choosePen(edit) }) return
    if (edit) {
        startPenEditing()
        return
    }
    if (tool != Tool.PEN) onToolButton(Tool.PEN)
    penEditing = false
}

/** The healing button (Spot healing, AI Remove, Clone stamp) or the toning one (Blur … Burn), picking its last tool. */
fun EditorViewModel.retouchButton(heal: Boolean) {
    val k = retouchKind
    if (tool == Tool.RETOUCH && k.heals == heal) {
        // Already on it: fold or unfold the brush column
        onToolButton(Tool.RETOUCH)
        return
    }
    val last = com.tim.quartzeditor.engine.RetouchKind.byName(prefs.getString(if (heal) "lastHeal" else "lastTone", null))
    chooseRetouch(if (last.heals == heal) last else if (heal) com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL else com.tim.quartzeditor.engine.RetouchKind.BLUR)
}

/** The fill button: Gradient, Paint bucket or Pattern fill (tap again for the next one). */
fun EditorViewModel.fillButton() {
    when {
        tool == Tool.GRADIENT -> chooseBucket(pattern = false)
        tool == Tool.BUCKET && !bucketPattern -> chooseBucket(pattern = true)
        tool == Tool.BUCKET -> onToolButton(Tool.GRADIENT)
        else -> when (prefs.getString("lastFill", "GRADIENT")) {
            "BUCKET" -> chooseBucket(pattern = false)
            "PATTERN" -> chooseBucket(pattern = true)
            else -> onToolButton(Tool.GRADIENT)
        }
    }
    // (Unless something pending had to finish first and the tool hasn't changed yet)
    if (tool == Tool.GRADIENT || tool == Tool.BUCKET) {
        prefs.edit().putString("lastFill", if (tool == Tool.GRADIENT) "GRADIENT" else if (bucketPattern) "PATTERN" else "BUCKET").apply()
    }
}

/** The brush button: Brush or Eraser (hold for both); tapping it while on one folds the brush column. */
fun EditorViewModel.brushButton() {
    if (tool == Tool.BRUSH || tool == Tool.ERASER) {
        onToolButton(tool)
        return
    }
    onToolButton(if (prefs.getString("lastBrush", "BRUSH") == "ERASER") Tool.ERASER else Tool.BRUSH)
}

fun EditorViewModel.chooseBrush(eraser: Boolean) {
    val t = if (eraser) Tool.ERASER else Tool.BRUSH
    prefs.edit().putString("lastBrush", t.name).apply()
    if (tool != t) onToolButton(t)
}
