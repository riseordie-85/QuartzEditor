package com.tim.quartzeditor.ui

import android.view.MotionEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import com.tim.quartzeditor.engine.StrokeDynamics
import kotlin.math.pow
import kotlin.math.roundToInt

// A pen (stylus) on the canvas: its pressure, tilt, eraser end and side button. Compose passes on
// where the pen is but not how it's held, so every touch event is also read here as it comes in
// (EditorCanvas spies on them) and the canvas asks for the pen's state point by point.

/** How pressure feels: the curve from how hard you press to how much it does. */
enum class PenCurve(val label: String, val power: Float) {
    LIGHT("Light hand", 0.6f), NORMAL("Normal", 1f), FIRM("Firm hand", 1.7f)
}

/** What the pen's side button does. */
enum class PenButton(val label: String) {
    EYEDROPPER("Eyedropper"), ERASER("Eraser"), UNDO("Undo"), NOTHING("Nothing");

    companion object {
        fun byName(n: String?): PenButton = entries.firstOrNull { it.name == n } ?: EYEDROPPER
    }
}

/** One point of a pen stroke: [pressure] already on the pen's curve (0..1), [tilt] and [azimuth] in radians. */
class PenSample(val pressure: Float, val tilt: Float, val azimuth: Float)

/** The pen's state as the latest touch events tell it. */
class PenTracker {
    // The pen's samples in the latest event (older ones first, then the newest)
    private var n = 0
    private var pressure = FloatArray(16)
    private var tilt = FloatArray(16)
    private var azimuth = FloatArray(16)
    /** When the latest event with the pen in it happened (uptime ms), to match it to Compose's. */
    private var eventTime = -1L
    /** When the pen was last seen near the screen or on it (uptime ms). */
    private var lastSeen = Long.MIN_VALUE / 2

    var touching = false
        private set
    var hovering = false
        private set
    /** The side button is held. */
    var buttonHeld = false
        private set
    /** The pen's eraser end is the one on the screen. */
    var eraserEnd = false
        private set

    /** The side button was pressed while the pen wasn't touching. */
    var onButtonPress: (() -> Unit)? = null

    /** The pen is over the canvas (not the panels or bars), as the canvas sees it. */
    var overCanvas = false
        private set
    /** When the pen was last over the canvas (uptime ms). */
    private var lastOnCanvas = Long.MIN_VALUE / 2

    /** The canvas saw the pen (hovering or touching) at [time]. */
    fun onCanvas(time: Long) {
        overCanvas = true
        lastOnCanvas = time
    }

    /** Reads one touch event. True when a pen is in it. */
    fun onMotion(e: MotionEvent): Boolean {
        var idx = -1
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                idx = i
                break
            }
        }
        if (idx < 0) return false
        lastSeen = e.eventTime
        eraserEnd = e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        val action = e.actionMasked
        val mine = e.actionIndex == idx
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                touching = false
            }
            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                hovering = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mine) {
                touching = true
                hovering = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            MotionEvent.ACTION_POINTER_UP -> if (mine) touching = false
        }
        // Pens report their barrel button(s) as the stylus buttons, or some as a right click
        val held = (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY)) != 0
        if (held && !buttonHeld && !touching) onButtonPress?.invoke()
        buttonHeld = held
        val h = if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) e.historySize else 0
        if (pressure.size < h + 1) {
            pressure = FloatArray(h + 16)
            tilt = FloatArray(h + 16)
            azimuth = FloatArray(h + 16)
        }
        for (j in 0 until h) {
            pressure[j] = e.getHistoricalPressure(idx, j)
            tilt[j] = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, j)
            azimuth[j] = e.getHistoricalOrientation(idx, j)
        }
        pressure[h] = e.getPressure(idx)
        tilt[h] = e.getAxisValue(MotionEvent.AXIS_TILT, idx)
        azimuth[h] = e.getOrientation(idx)
        n = h + 1
        eventTime = e.eventTime
        return true
    }

    /**
     * The pen is on the screen or close above it (or was a moment ago): fingers are probably a resting
     * hand. A hovering pen keeps sending events, so going quiet means it's gone (even when its leaving
     * was told to another part of the screen); one held still on the screen may be quiet a while.
     */
    fun near(now: Long): Boolean = (touching && overCanvas && now - lastSeen < 3000) || now - lastOnCanvas < 500

    /** How long since the pen was last seen (uptime ms). */
    fun idleFor(now: Long): Long = now - lastSeen

    /** The pen left the canvas, or its touch was taken back. */
    fun left() {
        touching = false
        hovering = false
        overCanvas = false
    }

    /**
     * Which of the latest samples goes with point [k] of [m] (the change's historical points, then
     * the change itself) at time [time]; the newest when they don't line up.
     */
    private fun index(k: Int, m: Int, time: Long): Int {
        if (n == 0) return -1
        if (time != eventTime) return n - 1
        if (m == n) return k.coerceIn(0, n - 1)
        if (m <= 1) return n - 1
        return (k.toFloat() / (m - 1) * (n - 1)).roundToInt().coerceIn(0, n - 1)
    }

    /** The raw pressure, tilt and lean for point [k] of [m] (null before the pen's been seen). */
    fun sample(k: Int, m: Int, time: Long, curve: (Float) -> Float): PenSample? {
        val i = index(k, m, time)
        if (i < 0) return null
        return PenSample(curve(pressure[i]), tilt[i], azimuth[i])
    }
}

/** A pen (or its eraser end), not a finger or a mouse. */
internal val PointerInputChange.isPen: Boolean
    get() = type == PointerType.Stylus || type == PointerType.Eraser

/** Pressure on the pen's curve. */
internal fun EditorViewModel.penPressure(raw: Float): Float = raw.coerceIn(0f, 1f).pow(penCurve.power)

/** The pen's state at point [k] of [m] of [change] (its historical points, then itself); null for a finger. */
internal fun EditorViewModel.penSample(change: PointerInputChange, k: Int, m: Int, time: Long): PenSample? {
    if (!change.isPen) return null
    val s = pen.sample(k, m, time) { penPressure(it) } ?: return null
    // The pen leans a way on the screen; on a turned canvas that's a different way on the picture
    if (rotation == 0f) return s
    return PenSample(s.pressure, s.tilt, s.azimuth - Math.toRadians(rotation.toDouble()).toFloat())
}

/** How long a taper of [t] (0..1) is on a brush [size] pixels across (canvas pixels). */
internal fun taperLength(t: Float, size: Float): Float = if (t <= 0f) 0f else t * (size * 6f + 80f)

/** What a stroke with [b]'s settings does as it goes: its tapers, and (by pen) pressure and tilt. */
internal fun EditorViewModel.strokeDynamics(b: BrushSettings, byPen: Boolean): StrokeDynamics {
    val taper = StrokeDynamics(taperStart = taperLength(b.taperStart, b.size), taperEnd = taperLength(b.taperEnd, b.size))
    return if (!byPen) taper
    else taper.copy(minSize = if (b.pressureSize) penMinSize.coerceIn(0.01f, 1f) else null, opacity = b.pressureOpacity, tilt = penTilt)
}

/** The retouch strength the pen's pressure gives (full when pressure doesn't set it). */
internal fun EditorViewModel.retouchPressure(p: Float): Float = if (retouchBrush.pressureOpacity) p else 1f

/** A pen was used: remember it, so fingers move the view from now on (when that's on). */
internal fun EditorViewModel.notePenSeen() {
    if (penSeen) return
    penSeen = true
    prefs.edit().putBoolean("penSeen", true).apply()
    if (penFingersNavigate) toast("Pen found: it paints, and fingers move the view. Settings → Pen to change")
}

/** Fingers move the view instead of painting. */
internal val EditorViewModel.fingersNavigate: Boolean get() = penSeen && penFingersNavigate

/** Fingers paint, or move the view (once a pen's been used). */
internal fun EditorViewModel.toggleFingerPainting() {
    penFingersNavigate = !penFingersNavigate
    savePenPrefs()
    toast(if (penFingersNavigate) "Fingers move the view; the pen paints" else "Fingers paint too")
}

/** Saves the pen settings. */
internal fun EditorViewModel.savePenPrefs() {
    prefs.edit()
        .putBoolean("penFingers", penFingersNavigate)
        .putInt("penCurve", penCurve.ordinal)
        .putFloat("penMinSize", penMinSize)
        .putBoolean("penTilt", penTilt)
        .putBoolean("penEraserEnd", penEraserEnd)
        .putString("penButton", penButton.name)
        .putBoolean("penHover", penHover)
        .apply()
}
