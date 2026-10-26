package com.tim.quartzeditor.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Select and mask: a layer mask's edge made better. Masks here hold how much is hidden (0 shown,
 * 1 hidden); everything works on 0..1 floats at one size, with the picture's brightness as the
 * guide for edge detection.
 */
object MaskRefine {
    /**
     * [radius]: edge detection, in pixels (the soft edge snaps to the picture's own edges within it:
     * hair, fur, leaves). [smooth] 0..100 rounds off jagged outlines; [feather] blurs the edge, in
     * pixels; [contrast] 0..100 sharpens it; [shift] -100..100 moves it in (negative) or out.
     */
    data class Params(
        val radius: Float = 0f,
        val smooth: Float = 0f,
        val feather: Float = 0f,
        val contrast: Float = 0f,
        val shift: Float = 0f,
    ) {
        val isEmpty: Boolean get() = radius <= 0f && smooth <= 0f && feather <= 0f && contrast <= 0f && shift == 0f
    }

    /**
     * [mask] (how much is hidden) refined as [p] says; [gray] is the picture's brightness (0..1), both
     * [w]×[h]. [scale]: these pixels per picture pixel (pixel amounts are in picture pixels).
     */
    fun apply(mask: FloatArray, gray: FloatArray, w: Int, h: Int, p: Params, scale: Float, check: () -> Unit = {}): FloatArray {
        // Worked out on what's shown (1 = shown), the way the edge is thought of
        var a = FloatArray(mask.size) { 1f - mask[it] }
        // Edge detection: within the edge band, the edge follows the picture's
        // (At least a pixel when it's on, so a small preview shows it too)
        val r = if (p.radius > 0f) (p.radius * scale).roundToInt().coerceAtLeast(1) else 0
        if (r >= 1) {
            val refined = Cutout.guidedFilter(gray, a, w, h, r, 1e-3f)
            val band = Cutout.boxMean(a, w, h, r)
            for (i in a.indices) {
                val b = band[i]
                if (b > 0.01f && b < 0.99f) a[i] = refined[i].coerceIn(0f, 1f)
            }
        }
        check()
        // Smooth: blurred, then brought back to an edge as crisp as it was
        if (p.smooth > 0f) {
            val sr = (p.smooth / 100f * 6f * scale).coerceAtLeast(0.5f)
            a = blur(a, w, h, sr)
            val k = 1f + sr * 0.9f
            for (i in a.indices) a[i] = ((a[i] - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
        }
        check()
        // Shift edge: softened a little, then moved in or out
        if (p.shift != 0f) {
            val sr = max(1f, abs(p.shift) / 100f * 8f * scale)
            a = blur(a, w, h, sr)
            val d = p.shift / 100f * 0.5f
            for (i in a.indices) a[i] = (a[i] + d).coerceIn(0f, 1f)
            // (and crisp again, unless contrast or feather says otherwise)
            if (p.contrast <= 0f && p.feather <= 0f) {
                val k = 1f + sr * 0.9f
                for (i in a.indices) a[i] = ((a[i] - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
            }
        }
        // Contrast: a harder edge
        if (p.contrast > 0f) {
            val k = 1f + p.contrast / 100f * 9f
            for (i in a.indices) a[i] = ((a[i] - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
        }
        // Feather: a softer one
        check()
        if (p.feather > 0f && p.feather * scale >= 0.5f) a = blur(a, w, h, p.feather * scale)
        for (i in a.indices) a[i] = 1f - a[i]
        return a
    }

    /** A Gaussian-like blur of about [radius] pixels (three box blurs). */
    fun blur(src: FloatArray, w: Int, h: Int, radius: Float): FloatArray {
        val r = (radius / 1.7f).roundToInt().coerceAtLeast(1)
        var out = src
        repeat(3) { out = Cutout.boxMean(out, w, h, r) }
        return out
    }

    /** A reasonable edge detection radius for a picture [w]×[h] (Enhance edge). */
    fun autoRadius(w: Int, h: Int): Float = max(3f, max(w, h) / 220f)
}
