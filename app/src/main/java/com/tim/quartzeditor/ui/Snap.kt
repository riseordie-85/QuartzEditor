package com.tim.quartzeditor.ui

import android.graphics.RectF
import kotlin.math.abs

// Snapping while moving layers: their left / middle / right (and top / middle / bottom) snap to the
// canvas's edges and middle (and the grid's lines when it's shown), with a line showing where.

/**
 * Snaps a drag of [content] (canvas pixels) by ([dx], [dy]): returns the drag to use and shows the
 * snap lines. [radius] is how close (canvas pixels) counts.
 */
fun EditorViewModel.snapDelta(content: RectF?, dx: Float, dy: Float, radius: Float, lockedAxis: Int = 0): Pair<Float, Float> {
    val d = doc
    if ((!snapOn && !snapsToGrid) || content == null || d == null || radius <= 0f) {
        clearSnapGuides()
        return dx to dy
    }
    fun axis(lo: Float, hi: Float, size: Float, delta: Float, guides: List<Float>): Pair<Float, Float?> {
        // The canvas's edges and middle, and your guides
        val canvasTargets = if (snapOn) listOf(0f, size / 2f, size) + (if (showGuides) guides else emptyList()) else emptyList()
        var best = 0f
        var bestD = radius
        var at: Float? = null
        for (p in floatArrayOf(lo + delta, (lo + hi) / 2f + delta, hi + delta)) {
            // The canvas's edges and middle, and the nearest grid line
            val targets = if (snapsToGrid) canvasTargets + nearestGridLine(p) else canvasTargets
            for (t in targets) {
                val diff = t - p
                if (abs(diff) <= bestD) {
                    bestD = abs(diff)
                    best = diff
                    at = t
                }
            }
        }
        return (delta + best) to at
    }
    // Shift: straight along one way, where only the middle snaps to the canvas's middle
    fun middleOnly(lo: Float, hi: Float, size: Float, delta: Float): Pair<Float, Float?> {
        if (!snapOn) return delta to null
        val diff = size / 2f - ((lo + hi) / 2f + delta)
        return if (abs(diff) <= radius) (delta + diff) to size / 2f else delta to null
    }
    // The axis held still with Shift doesn't snap at all
    val (sx, gx) = when (lockedAxis) {
        1 -> dx to null
        2 -> middleOnly(content.left, content.right, d.width.toFloat(), dx)
        else -> axis(content.left, content.right, d.width.toFloat(), dx, d.guidesX)
    }
    val (sy, gy) = when (lockedAxis) {
        2 -> dy to null
        1 -> middleOnly(content.top, content.bottom, d.height.toFloat(), dy)
        else -> axis(content.top, content.bottom, d.height.toFloat(), dy, d.guidesY)
    }
    snapGuidesX = listOfNotNull(gx)
    snapGuidesY = listOfNotNull(gy)
    return sx to sy
}

fun EditorViewModel.clearSnapGuides() {
    if (snapGuidesX.isNotEmpty()) snapGuidesX = emptyList()
    if (snapGuidesY.isNotEmpty()) snapGuidesY = emptyList()
}

/** The guide nearest [v] within [radius] (canvas pixels), or null. */
internal fun nearestGuide(guides: List<Float>, v: Float, radius: Float): Float? {
    var best: Float? = null
    var bestD = radius
    for (g in guides) {
        val dd = abs(g - v)
        if (dd <= bestD) {
            bestD = dd
            best = g
        }
    }
    return best
}
