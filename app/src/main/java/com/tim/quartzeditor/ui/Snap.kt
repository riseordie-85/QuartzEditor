package com.tim.quartzeditor.ui

import android.graphics.RectF
import kotlin.math.abs

// Snapping while moving layers: their left / middle / right (and top / middle / bottom) snap to the
// canvas's edges and middle (and the grid's lines when it's shown), with a line showing where.

/**
 * Snaps a drag of [content] (canvas pixels) by ([dx], [dy]): returns the drag to use and shows the
 * snap lines. [radius] is how close (canvas pixels) counts.
 */
fun EditorViewModel.snapDelta(content: RectF?, dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
    val d = doc
    if ((!snapOn && !snapsToGrid) || content == null || d == null || radius <= 0f) {
        clearSnapGuides()
        return dx to dy
    }
    fun axis(lo: Float, hi: Float, size: Float, delta: Float): Pair<Float, Float?> {
        val canvasTargets = if (snapOn) listOf(0f, size / 2f, size) else emptyList()
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
    val (sx, gx) = axis(content.left, content.right, d.width.toFloat(), dx)
    val (sy, gy) = axis(content.top, content.bottom, d.height.toFloat(), dy)
    snapGuidesX = listOfNotNull(gx)
    snapGuidesY = listOfNotNull(gy)
    return sx to sy
}

fun EditorViewModel.clearSnapGuides() {
    if (snapGuidesX.isNotEmpty()) snapGuidesX = emptyList()
    if (snapGuidesY.isNotEmpty()) snapGuidesY = emptyList()
}
