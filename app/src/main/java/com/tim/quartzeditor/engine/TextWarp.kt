package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Warped text, like Photoshop's Warp Text: the text's box bent into one of 15 shapes, with a bend
// and horizontal / vertical distortion. The text is drawn straight, then stretched over a mesh
// that follows the shape (so any fonts, sizes and colors inside it bend together).

enum class WarpStyle(val label: String, val psd: String) {
    ARC("Arc", "warpArc"),
    ARC_LOWER("Arc Lower", "warpArcLower"),
    ARC_UPPER("Arc Upper", "warpArcUpper"),
    ARCH("Arch", "warpArch"),
    BULGE("Bulge", "warpBulge"),
    SHELL_LOWER("Shell Lower", "warpShellLower"),
    SHELL_UPPER("Shell Upper", "warpShellUpper"),
    FLAG("Flag", "warpFlag"),
    WAVE("Wave", "warpWave"),
    FISH("Fish", "warpFish"),
    RISE("Rise", "warpRise"),
    FISHEYE("Fisheye", "warpFisheye"),
    INFLATE("Inflate", "warpInflate"),
    SQUEEZE("Squeeze", "warpSqueeze"),
    TWIST("Twist", "warpTwist"),
}

/**
 * A text warp: [bend] −1 … 1 (= ±100%), [horizontal] and [vertical] distortion −1 … 1, and
 * whether it bends along the text's height instead ([verticalOrientation]).
 */
data class TextWarp(
    val style: WarpStyle,
    val bend: Float = 0.5f,
    val horizontal: Float = 0f,
    val vertical: Float = 0f,
    val verticalOrientation: Boolean = false,
) {
    /** It changes something. */
    val active: Boolean get() = abs(bend) > 0.001f || abs(horizontal) > 0.001f || abs(vertical) > 0.001f

    fun toJson(): JSONObject = JSONObject().put("style", style.name).put("bend", bend.toDouble())
        .put("h", horizontal.toDouble()).put("v", vertical.toDouble()).put("vert", verticalOrientation)

    /**
     * Where point ([x], [y]) of the box [box] goes. Writes the result into [out] (x, y).
     */
    fun map(box: RectF, x: Float, y: Float, out: FloatArray) {
        if (verticalOrientation) {
            // The same shapes, turned a quarter: work in the box's transposed space
            val t = RectF(box.top, box.left, box.bottom, box.right)
            mapFlat(t, y, x, out)
            val nx = out[1]
            out[1] = out[0]
            out[0] = nx
            return
        }
        mapFlat(box, x, y, out)
    }

    private fun mapFlat(box: RectF, x: Float, y: Float, out: FloatArray) {
        val w = max(1f, box.width())
        val h = max(1f, box.height())
        val cx = box.centerX()
        val cy = box.centerY()
        // u across (−1 … 1), t down (0 top … 1 bottom), v down (−1 … 1)
        val u = (x - cx) / (w / 2f)
        val t = (y - box.top) / h
        val v = t * 2f - 1f
        val b = bend.coerceIn(-1f, 1f)
        var nx = x
        var ny = y
        val bell = 1f - u.coerceIn(-1f, 1f) * u.coerceIn(-1f, 1f)
        when (style) {
            WarpStyle.ARC -> {
                // Bent around a circle: the top edge and the bottom edge both follow it
                if (abs(b) > 0.001f) {
                    val theta = abs(b) * (PI.toFloat() / 2f)
                    val r = (w / 2f) / sin(theta) + h / 2f
                    val phi = u * theta
                    if (b > 0f) {
                        val rr = r - (y - box.top)
                        nx = cx + rr * sin(phi)
                        ny = box.top + r - rr * cos(phi)
                    } else {
                        val rr = r - (box.bottom - y)
                        nx = cx + rr * sin(phi)
                        ny = box.bottom - r + rr * cos(phi)
                    }
                }
            }
            WarpStyle.ARC_LOWER -> ny = y + t * b * h * 0.6f * bell
            WarpStyle.ARC_UPPER -> ny = y - (1f - t) * b * h * 0.6f * bell
            WarpStyle.ARCH -> ny = y - b * h * 0.6f * bell
            WarpStyle.BULGE -> ny = y + v * b * h * 0.35f * bell
            WarpStyle.SHELL_LOWER -> {
                ny = y + t * b * h * 0.6f * bell
                nx = cx + (x - cx) * (1f - b * 0.35f * (1f - t))
            }
            WarpStyle.SHELL_UPPER -> {
                ny = y - (1f - t) * b * h * 0.6f * bell
                nx = cx + (x - cx) * (1f - b * 0.35f * t)
            }
            WarpStyle.FLAG -> ny = y - b * h * 0.35f * sin(PI.toFloat() * u)
            WarpStyle.WAVE -> {
                ny = y - b * h * 0.3f * sin(PI.toFloat() * u + PI.toFloat() * 0.5f * v)
                nx = x + b * w * 0.03f * sin(PI.toFloat() * v)
            }
            WarpStyle.FISH -> {
                ny = cy + (y - cy) * (1f + b * 0.6f * bell) - b * h * 0.25f * sin(PI.toFloat() * u) * (1f - abs(v) * 0.5f)
            }
            WarpStyle.RISE -> ny = y - b * h * 0.5f * sin(PI.toFloat() * 0.5f * u.coerceIn(-1f, 1f))
            WarpStyle.FISHEYE -> {
                // A lens over the middle: swells most there, mostly up and down
                val f = max(0f, 1f - (u * u + v * v * 0.25f))
                nx = cx + (x - cx) * (1f + b * 0.25f * f)
                ny = cy + (y - cy) * (1f + b * 0.9f * f)
            }
            WarpStyle.INFLATE -> {
                nx = cx + (x - cx) * (1f + b * 0.3f * (1f - v * v))
                ny = cy + (y - cy) * (1f + b * 0.5f * bell)
            }
            WarpStyle.SQUEEZE -> {
                nx = cx + (x - cx) * (1f + b * 0.25f * (1f - v * v))
                ny = cy + (y - cy) * (1f - b * 0.55f * bell)
            }
            WarpStyle.TWIST -> {
                val vv = v * (h / w)
                val r = sqrt(u * u + vv * vv)
                val a = b * PI.toFloat() * 0.5f * max(0f, 1f - r)
                val dx = x - cx
                val dy = y - cy
                nx = cx + dx * cos(a) - dy * sin(a)
                ny = cy + dx * sin(a) + dy * cos(a)
            }
        }
        // Distortion: one side taller (horizontal) or the top or bottom wider (vertical)
        if (abs(horizontal) > 0.001f) ny = cy + (ny - cy) * (1f + horizontal.coerceIn(-1f, 1f) * u * 0.7f)
        if (abs(vertical) > 0.001f) nx = cx + (nx - cx) * (1f + vertical.coerceIn(-1f, 1f) * v * 0.7f)
        out[0] = nx
        out[1] = ny
    }

    /** [box] bent: the bounds of where its edges and inside go. */
    fun bounds(box: RectF): RectF {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val o = FloatArray(2)
        val n = 24
        for (j in 0..n) for (i in 0..n) {
            map(box, box.left + box.width() * i / n, box.top + box.height() * j / n, o)
            r.left = min(r.left, o[0]); r.right = max(r.right, o[0]); r.top = min(r.top, o[1]); r.bottom = max(r.bottom, o[1])
        }
        return r
    }

    /**
     * Draws [bmp] (showing [box], at [bmp].width / box.width pixels per unit) bent, into [c] (in
     * the box's space).
     */
    fun draw(c: Canvas, bmp: Bitmap, box: RectF) {
        val cols = 48
        val rows = 24
        val verts = FloatArray((cols + 1) * (rows + 1) * 2)
        val o = FloatArray(2)
        var k = 0
        for (j in 0..rows) for (i in 0..cols) {
            map(box, box.left + box.width() * i / cols, box.top + box.height() * j / rows, o)
            verts[k++] = o[0]
            verts[k++] = o[1]
        }
        // drawBitmapMesh puts the bitmap's corners at the mesh's; the bitmap is in box units scaled
        c.drawBitmapMesh(bmp, cols, rows, verts, 0, null, 0, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    companion object {
        fun fromJson(o: JSONObject?): TextWarp? = o?.let {
            val style = WarpStyle.entries.firstOrNull { s -> s.name == it.optString("style") } ?: return null
            TextWarp(style, it.optDouble("bend", 0.5).toFloat(), it.optDouble("h", 0.0).toFloat(), it.optDouble("v", 0.0).toFloat(), it.optBoolean("vert"))
        }
    }
}
