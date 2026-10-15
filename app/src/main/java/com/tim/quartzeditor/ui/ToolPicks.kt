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
