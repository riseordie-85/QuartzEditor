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
    // (Not on the start screen shown over open files: the file isn't there to see)
    if (doc == null || showHome) return false
    val cmd = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    val alt = e.isAltPressed
    val repeat = e.repeatCount > 0
    // Typing on the picture, but the keyboard lost its place (a field in the panels was used): it
    // goes back to the text, and the key isn't taken as a shortcut (H, E, Backspace…)
    if (textEdit != null && !cmd && e.keyCode != KeyEvent.KEYCODE_ESCAPE && e.keyCode !in setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)) {
        textFocusTick++
        return true
    }

    // Shift and Alt held: the same as the modifier button
    when (e.keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
            keyShift = true
            return false
        }
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
            keyAlt = true
            return false
        }
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
            keyCtrl = true
            return false
        }
        // Caps Lock: a small crosshair instead of the brush's outline (and back)
        KeyEvent.KEYCODE_CAPS_LOCK -> {
            preciseCursor = !preciseCursor
            toast(if (preciseCursor) "Precise cursor" else "Brush outline cursor")
            return false
        }
    }
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
            val bigger = e.keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET
            // Ctrl+] / [: the layer up or down (with Shift: to the top or bottom); Alt+] / [: the layer above or below
            if (cmd) {
                if (shift) moveLayerToEdge(top = bigger) else moveLayerStep(up = bigger)
                return true
            }
            if (alt) {
                selectLayerStep(up = bigger)
                return true
            }
            if (tool == Tool.WARP && warpKind == com.tim.quartzeditor.engine.WarpKind.LIQUIFY) {
                liquifySize = (liquifySize * if (bigger) 1.15f else 1f / 1.15f).coerceIn(8f, 2000f)
                saveLiquifyPrefs()
                toast("Liquify size ${liquifySize.roundToInt()} px")
                return true
            }
            val forTool = sliderTool
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
            // Ctrl+Shift+T: the last transform again
            KeyEvent.KEYCODE_T -> if (shift) transformAgain() else onToolButton(Tool.TRANSFORM)
            // Ctrl+Shift+X: Liquify (like Photoshop)
            KeyEvent.KEYCODE_X -> if (shift) chooseWarp(com.tim.quartzeditor.engine.WarpKind.LIQUIFY) else cutPixels()
            // Ctrl+C copy (Ctrl+Shift+C: copy merged), Ctrl+V paste
            // Ctrl+Alt+C: canvas size
            KeyEvent.KEYCODE_C -> if (alt) dialogRequest = "canvasSize" else copyPixels(merged = shift)
            KeyEvent.KEYCODE_V -> pastePixels()
            // Ctrl+Shift+D: reselect
            KeyEvent.KEYCODE_D -> if (shift) reselect() else deselect()
            KeyEvent.KEYCODE_A -> selectAll()
            // Ctrl+I invert the layer, Ctrl+Shift+I invert the selection, Ctrl+Alt+I image size
            KeyEvent.KEYCODE_I -> when {
                shift -> invertSelection()
                alt -> dialogRequest = "imageSize"
                else -> invertLayer()
            }
            KeyEvent.KEYCODE_J -> when {
                // With a selection: copy (or with Shift, cut) it to a new layer; otherwise duplicate the layer
                doc?.selection != null -> selectionToLayer(cut = shift)
                else -> duplicateLayer()
            }
            // Ctrl+E merge down, Ctrl+Shift+E merge visible, Ctrl+Shift+Alt+E stamp visible
            KeyEvent.KEYCODE_E -> when {
                shift && alt -> stampVisible()
                shift -> mergeVisible()
                else -> mergeDown()
            }
            // Ctrl+Shift+N new layer, Ctrl+N new image
            KeyEvent.KEYCODE_N -> if (shift) addLayer() else dialogRequest = "new"
            KeyEvent.KEYCODE_O -> dialogRequest = "open"
            // Ctrl+W close, Ctrl+Alt+Shift+W export
            KeyEvent.KEYCODE_W -> if (alt && shift) dialogRequest = "export" else requestClose()
            KeyEvent.KEYCODE_K -> dialogRequest = "settings"
            KeyEvent.KEYCODE_TAB -> nextFile(forward = !shift)
            // Ctrl+/: lock the layer
            KeyEvent.KEYCODE_SLASH -> toggleLayerLock()
            // Ctrl+Shift+> / <: text size
            KeyEvent.KEYCODE_PERIOD -> if (shift) changeTextSize(bigger = true) else return false
            KeyEvent.KEYCODE_COMMA -> if (shift) changeTextSize(bigger = false) else return false
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (textEdit != null) finishText() else return false
            // Ctrl+Alt+G: clipping mask, Ctrl+Shift+G: ungroup (Ctrl+G: put in a folder)
            KeyEvent.KEYCODE_G -> when {
                alt -> toggleClip()
                shift -> ungroupActive()
                else -> groupActive()
            }
            // Ctrl+B color balance, Ctrl+Shift+Alt+B black & white
            KeyEvent.KEYCODE_B -> openPopup(if (shift && alt) PopupKind.BLACK_WHITE else PopupKind.COLOR_BALANCE)
            KeyEvent.KEYCODE_L -> openPopup(PopupKind.LEVELS)
            KeyEvent.KEYCODE_M -> openPopup(PopupKind.CURVES)
            // Ctrl+U hue/saturation, Ctrl+Shift+U desaturate
            KeyEvent.KEYCODE_U -> if (shift) desaturateLayer() else openPopup(PopupKind.HUE_SAT)
            // Ctrl+S save, Ctrl+Shift+S save a copy as a file
            KeyEvent.KEYCODE_S -> if (shift) dialogRequest = "saveCopy" else saveToProjects(quiet = false)
            KeyEvent.KEYCODE_0 -> fit()
            // Ctrl+' shows or hides the grid (like Photoshop)
            KeyEvent.KEYCODE_APOSTROPHE -> toggleGrid()
            // Ctrl+R rulers, Ctrl+; guides (like Photoshop)
            // Ctrl+Alt+R: Select and mask
            KeyEvent.KEYCODE_R -> if (alt) selectAndMaskShortcut() else toggleRulers()
            KeyEvent.KEYCODE_SEMICOLON -> if (alt) toggleGuidesLocked() else toggleGuides()
            KeyEvent.KEYCODE_1 -> zoomTo(1f)
            KeyEvent.KEYCODE_DEL -> fillWithColor(background)
            else -> return false
        }
        return true
    }

    when (e.keyCode) {
        // Transform (like Photoshop's Move tool with its transform controls); V again: plain Move
        KeyEvent.KEYCODE_V -> if (tool == Tool.TRANSFORM) chooseMove(pan = false) else onToolButton(Tool.TRANSFORM)
        KeyEvent.KEYCODE_H -> chooseMove(pan = true)
        // J: healing (Shift+J: spot healing / AI remove); S: clone stamp; O: dodge, burn… (Shift+O: the next)
        KeyEvent.KEYCODE_J -> {
            val heals = listOf(com.tim.quartzeditor.engine.RetouchKind.SPOT_HEAL, com.tim.quartzeditor.engine.RetouchKind.AI_REMOVE)
            val cur = retouchKind.takeIf { tool == Tool.RETOUCH && it in heals }
            chooseRetouch(if (shift && cur != null) heals[(heals.indexOf(cur) + 1) % heals.size] else cur ?: com.tim.quartzeditor.engine.RetouchKind.byName(prefs.getString("lastHeal", null)).takeIf { it in heals } ?: heals[0])
        }
        KeyEvent.KEYCODE_S -> chooseRetouch(com.tim.quartzeditor.engine.RetouchKind.CLONE)
        KeyEvent.KEYCODE_O -> {
            val tones = com.tim.quartzeditor.engine.RetouchKind.entries.filter { !it.heals }
            val cur = retouchKind.takeIf { tool == Tool.RETOUCH && it in tones }
            chooseRetouch(if (shift && cur != null) tones[(tones.indexOf(cur) + 1) % tones.size] else cur ?: com.tim.quartzeditor.engine.RetouchKind.byName(prefs.getString("lastTone", null)).takeIf { it in tones } ?: tones[0])
        }
        // R: rotate view, Z: zoom (press again, or Esc, to stop)
        KeyEvent.KEYCODE_R -> toggleViewTool(ViewTool.ROTATE)
        KeyEvent.KEYCODE_Z -> toggleViewTool(ViewTool.ZOOM)
        KeyEvent.KEYCODE_Q -> toggleQuickMask()
        KeyEvent.KEYCODE_F -> cycleScreenMode()
        // Shift+F6: feather
        KeyEvent.KEYCODE_F6 -> if (shift) dialogRequest = "feather" else return false
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
        // P: the pen; Shift+P switches between the Pen and the Curvature Pen
        KeyEvent.KEYCODE_P -> if (shift && tool == Tool.PEN) choosePen(edit = false, curvature = !penCurvature) else choosePen(edit = false)
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
            warp != null -> applyWarp()
            cropBox != null -> applyCrop()
            penPoints.size >= 2 -> finishPen(closed = penPoints.size >= 3)
            pendingFx != null -> applyFx { popup = null }
            else -> return false
        }
        KeyEvent.KEYCODE_ESCAPE -> when {
            textEdit != null -> cancelText()
            // Rotate view: Esc straightens the canvas; then (or with Zoom) it leaves the view tool
            viewTool == ViewTool.ROTATE && rotation % 360f != 0f -> straightenView()
            viewTool != null -> viewTool = null
            viewMaskOf != null -> viewMaskOf = null
            screenMode == 2 -> screenMode = 0
            transform != null -> cancelTransform()
            warp != null -> cancelWarp()
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
            // Shift+1 … 0: the brush's flow
            if (shift && (tool == Tool.BRUSH || tool == Tool.ERASER)) {
                setBrushFlow(o)
                return true
            }
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
    if (doc == null || showHome || e.keyCode != KeyEvent.KEYCODE_TAB || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) panelsToggle++
    return true
}

/**
 * Space, caught before anything on the screen sees it (a button or switch that had focus would
 * otherwise take it, and the view wouldn't move): held, one finger, the pen or the mouse moves the
 * view. Not while typing text on the picture.
 */
fun EditorViewModel.onSpaceKey(e: KeyEvent, typingInField: Boolean = false): Boolean {
    if (e.keyCode != KeyEvent.KEYCODE_SPACE || doc == null || showHome || textEdit != null || typingInField) {
        // (Let go even then, so it can't stay held)
        if (e.keyCode == KeyEvent.KEYCODE_SPACE && e.action == KeyEvent.ACTION_UP) tempPan = false
        return false
    }
    if (e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return false
    when (e.action) {
        KeyEvent.ACTION_DOWN -> tempPan = true
        KeyEvent.ACTION_UP -> tempPan = false
    }
    return true
}

/** The keyboard went away mid-press (another app, a window on top): let go of Space. */
fun EditorViewModel.releaseKeys() {
    tempPan = false
    keyShift = false
    keyAlt = false
    keyCtrl = false
}

fun EditorViewModel.onKeyUp(e: KeyEvent): Boolean {
    when (e.keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> keyShift = false
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> keyAlt = false
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> keyCtrl = false
    }
    if (e.keyCode == KeyEvent.KEYCODE_SPACE && tempPan) {
        tempPan = false
        return true
    }
    return false
}

private fun EditorViewModel.currentBrushFor(t: Tool) = when (t) {
    Tool.ERASER -> eraser
    Tool.RETOUCH -> retouchBrush
    else -> brush
}

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
