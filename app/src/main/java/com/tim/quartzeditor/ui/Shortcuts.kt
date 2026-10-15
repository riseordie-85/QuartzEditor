package com.tim.quartzeditor.ui

import android.graphics.Color
import android.view.KeyEvent
import com.tim.quartzeditor.engine.BrushSizeScale
import com.tim.quartzeditor.engine.SelectShape
import kotlin.math.roundToInt

// Photoshop's keyboard shortcuts, for a keyboard on a tablet or a Chromebook. Keys a text box is
// using never get here (the activity only passes on keys nothing on screen took).
// Ctrl and Cmd (Meta) both work as Photoshop's Ctrl/Cmd.

/** Handles a key press. True when it did something. */
fun EditorViewModel.onKeyDown(e: KeyEvent): Boolean {
    if (doc == null) return false
    val cmd = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    val alt = e.isAltPressed
    val repeat = e.repeatCount > 0

    // Hold Space to move the view with one finger (whatever tool is picked)
    if (e.keyCode == KeyEvent.KEYCODE_SPACE && !cmd) {
        tempPan = true
        return true
    }
    // While something is being worked out, only looking around is allowed
    val zoomKey = cmd && e.keyCode in setOf(KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1)
    if (busy != null && !zoomKey) return true
    // Keys that may repeat while held
    when (e.keyCode) {
        KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET -> {
            if (cmd) return false
            val bigger = e.keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET
            val forTool = if (tool == Tool.ERASER) Tool.ERASER else Tool.BRUSH
            if (shift) {
                // Shift+[ softer, Shift+] harder (Photoshop's hardness, the other way round)
                updateBrush(forTool) { it.copy(softness = (it.softness + if (bigger) -0.25f else 0.25f).coerceIn(0f, 1f)) }
                toast("Softness ${(currentBrushFor(forTool).softness * 100).roundToInt()}%")
            } else {
                updateBrush(forTool) { b ->
                    val s = b.size
                    val step = when {
                        s < 10f -> 1f
                        s < 50f -> 5f
                        s < 100f -> 10f
                        s < 500f -> 25f
                        else -> 50f
                    }
                    b.copy(size = (if (bigger) s + step else s - step).coerceIn(BrushSizeScale.MIN, BrushSizeScale.MAX))
                }
                toast("Size ${currentBrushFor(forTool).size.roundToInt()} px")
            }
            return true
        }
        KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> if (cmd) {
            zoomBy(1.25f)
            return true
        }
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> if (cmd) {
            zoomBy(0.8f)
            return true
        }
    }
    if (repeat) return false

    if (cmd) {
        when (e.keyCode) {
            KeyEvent.KEYCODE_Z -> if (shift) redo() else undo()
            KeyEvent.KEYCODE_Y -> redo()
            KeyEvent.KEYCODE_T -> onToolButton(Tool.TRANSFORM)
            KeyEvent.KEYCODE_D -> deselect()
            KeyEvent.KEYCODE_A -> selectAll()
            KeyEvent.KEYCODE_I -> if (shift) invertSelection() else return false
            KeyEvent.KEYCODE_J -> when {
                // With a selection: copy (or with Shift, cut) it to a new layer; otherwise duplicate the layer
                doc?.selection != null -> selectionToLayer(cut = shift)
                else -> duplicateLayer()
            }
            KeyEvent.KEYCODE_E -> if (shift) return false else mergeDown()
            KeyEvent.KEYCODE_N -> if (shift) addLayer() else return false
            // Ctrl+Alt+G: clipping mask (Ctrl+G: put in a folder)
            KeyEvent.KEYCODE_G -> if (alt) toggleClip() else groupActive()
            KeyEvent.KEYCODE_L -> openPopup(PopupKind.LEVELS)
            KeyEvent.KEYCODE_M -> openPopup(PopupKind.CURVES)
            KeyEvent.KEYCODE_U -> openPopup(PopupKind.ADJUSTMENTS)
            KeyEvent.KEYCODE_S -> saveToProjects(quiet = false)
            KeyEvent.KEYCODE_0 -> fit()
            KeyEvent.KEYCODE_1 -> zoomTo(1f)
            KeyEvent.KEYCODE_DEL -> fillWithColor(background)
            else -> return false
        }
        return true
    }

    when (e.keyCode) {
        KeyEvent.KEYCODE_V -> chooseMove(pan = false)
        KeyEvent.KEYCODE_H -> chooseMove(pan = true)
        KeyEvent.KEYCODE_M -> chooseSelectShape(
            if (shift && tool == Tool.SELECT) (if (selectShape == SelectShape.RECT) SelectShape.ELLIPSE else SelectShape.RECT)
            else if (selectShape == SelectShape.ELLIPSE) SelectShape.ELLIPSE else SelectShape.RECT
        )
        KeyEvent.KEYCODE_L -> chooseSelectShape(
            if (shift && tool == Tool.SELECT) (if (selectShape == SelectShape.LASSO) SelectShape.POLYGON else SelectShape.LASSO)
            else if (selectShape == SelectShape.POLYGON) SelectShape.POLYGON else SelectShape.LASSO
        )
        KeyEvent.KEYCODE_W -> chooseSelectShape(SelectShape.WAND)
        KeyEvent.KEYCODE_C -> if (tool != Tool.CROP) onToolButton(Tool.CROP)
        KeyEvent.KEYCODE_I -> if (tool != Tool.EYEDROPPER) onToolButton(Tool.EYEDROPPER)
        // Paint bucket and gradient share G (Shift+G switches between them)
        KeyEvent.KEYCODE_G -> when {
            shift && tool == Tool.GRADIENT -> onToolButton(Tool.BUCKET)
            shift && tool == Tool.BUCKET -> onToolButton(Tool.GRADIENT)
            tool != Tool.GRADIENT && tool != Tool.BUCKET -> onToolButton(Tool.GRADIENT)
        }
        KeyEvent.KEYCODE_U -> if (shift && tool == Tool.SHAPE) onToolButton(Tool.SHAPE) else if (tool != Tool.SHAPE) onToolButton(Tool.SHAPE)
        KeyEvent.KEYCODE_P -> choosePen(edit = false)
        KeyEvent.KEYCODE_A -> choosePen(edit = true)
        KeyEvent.KEYCODE_T -> if (tool != Tool.TEXT) onToolButton(Tool.TEXT)
        KeyEvent.KEYCODE_B -> if (tool != Tool.BRUSH) onToolButton(Tool.BRUSH)
        KeyEvent.KEYCODE_E -> if (tool != Tool.ERASER) onToolButton(Tool.ERASER)
        KeyEvent.KEYCODE_X -> swapColors()
        KeyEvent.KEYCODE_D -> {
            setColor(ColorSlot.FOREGROUND, Color.BLACK)
            setColor(ColorSlot.BACKGROUND, Color.WHITE)
        }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
            transform != null -> applyTransform()
            cropBox != null -> applyCrop()
            penPoints.size >= 2 -> finishPen(closed = penPoints.size >= 3)
            pendingFx != null -> applyFx { popup = null }
            else -> return false
        }
        KeyEvent.KEYCODE_ESCAPE -> when {
            textEdit != null -> cancelText()
            transform != null -> cancelTransform()
            cropBox != null -> cancelCrop()
            penPoints.isNotEmpty() -> cancelPen()
            polygonPoints.isNotEmpty() -> cancelPolygon()
            popup != null -> closePopup()
            else -> return false
        }
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> when {
            alt -> fillWithColor(foreground)
            doc?.selection != null -> clearSelected()
            else -> deleteLayer()
        }
        else -> {
            // 1 … 9, 0: opacity 10% … 90%, 100% (brush with Brush/Eraser, the layer with Move)
            val n = numberKey(e.keyCode) ?: return false
            val o = if (n == 0) 1f else n / 10f
            when (tool) {
                Tool.BRUSH, Tool.ERASER -> {
                    updateBrush(tool) { it.copy(opacity = o) }
                    toast("Brush opacity ${(o * 100).roundToInt()}%")
                }
                else -> doc?.active?.let { l ->
                    setProps(l.id, "Opacity") { it.copy(opacity = o) }
                    toast("Layer opacity ${(o * 100).roundToInt()}%")
                }
            }
        }
    }
    return true
}

/** Tab shows or hides the panels. Taken before anything on screen can use it to move focus. */
fun EditorViewModel.onTabKey(e: KeyEvent): Boolean {
    if (doc == null || e.keyCode != KeyEvent.KEYCODE_TAB || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) panelsToggle++
    return true
}

/** The keyboard went away mid-press (another app, a window on top): let go of Space. */
fun EditorViewModel.releaseKeys() {
    tempPan = false
}

fun EditorViewModel.onKeyUp(e: KeyEvent): Boolean {
    if (e.keyCode == KeyEvent.KEYCODE_SPACE && tempPan) {
        tempPan = false
        return true
    }
    return false
}

private fun EditorViewModel.currentBrushFor(t: Tool) = if (t == Tool.ERASER) eraser else brush

private fun numberKey(code: Int): Int? = when (code) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/** Zooms around the middle of the view. */
private fun EditorViewModel.zoomBy(f: Float) {
    transformView(f, viewW / 2f, viewH / 2f, 0f, 0f)
}

/** Zooms so one canvas pixel is one screen pixel (around the middle of the view). */
private fun EditorViewModel.zoomTo(s: Float) {
    if (scale > 0f) zoomBy(s / scale)
}
