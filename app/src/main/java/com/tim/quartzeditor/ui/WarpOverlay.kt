package com.tim.quartzeditor.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tim.quartzeditor.engine.WarpKind
import com.tim.quartzeditor.engine.WarpMath

/** What's drawn over the picture while warping ([s], [ox], [oy]: the view's zoom and offset). */
internal fun DrawScope.drawWarpOverlay(vm: EditorViewModel, w: WarpSession, s: Float, ox: Float, oy: Float) {
    @Suppress("UNUSED_VARIABLE") val v = w.version
    fun sc(x: Float, y: Float) = Offset(x * s + ox, y * s + oy)
    val line = 1.dp.toPx()
    val shade = Color.Black.copy(alpha = 0.5f)
    when (w.kind) {
        WarpKind.WARP -> {
            // The grid at thirds, as curves
            val pt = FloatArray(2)
            for (n in 0..3) {
                val t = n / 3f
                for (dir in 0..1) {
                    val path = Path()
                    for (k in 0..32) {
                        val a = k / 32f
                        if (dir == 0) WarpMath.patchPoint(w.ctrl, a, t, pt) else WarpMath.patchPoint(w.ctrl, t, a, pt)
                        val o = sc(pt[0], pt[1])
                        if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, shade, style = Stroke(line * 2.5f))
                    drawPath(path, Color.White.copy(alpha = if (n == 0 || n == 3) 1f else 0.7f), style = Stroke(line))
                }
            }
            fun c(i: Int, j: Int) = sc(w.ctrl[(j * 4 + i) * 2], w.ctrl[(j * 4 + i) * 2 + 1])
            // Corner handles: lines to the controls beside each corner
            for ((ci, cj) in listOf(0 to 0, 3 to 0, 3 to 3, 0 to 3)) {
                val a = c(ci, cj)
                val ni = if (ci == 0) 1 else 2
                val nj = if (cj == 0) 1 else 2
                drawLine(Q.accent, a, c(ni, cj), line * 1.5f)
                drawLine(Q.accent, a, c(ci, nj), line * 1.5f)
            }
            val hs = 6.dp.toPx()
            for (j in 0..3) for (i in 0..3) {
                val o = c(i, j)
                val corner = (i == 0 || i == 3) && (j == 0 || j == 3)
                if (corner) {
                    drawRect(shade, Offset(o.x - hs - line, o.y - hs - line), androidx.compose.ui.geometry.Size(2 * (hs + line), 2 * (hs + line)))
                    drawRect(Color.White, Offset(o.x - hs, o.y - hs), androidx.compose.ui.geometry.Size(2 * hs, 2 * hs))
                } else {
                    val inner = i in 1..2 && j in 1..2
                    val r = if (inner) hs * 0.6f else hs * 0.8f
                    drawCircle(shade, r + line, o)
                    drawCircle(if (inner) Color.White.copy(alpha = 0.8f) else Q.accent, r, o)
                }
            }
        }
        WarpKind.PUPPET -> {
            // The mesh, faintly, so you can see what bends
            val m = w.mesh
            val step = maxOf(1, maxOf(m.cols, m.rows) / 16)
            val faint = Color.White.copy(alpha = 0.22f)
            for (j in 0..m.rows step step) {
                val path = Path()
                for (i in 0..m.cols) {
                    val k = (j * m.stride + i) * 2
                    val o = sc(m.pts[k], m.pts[k + 1])
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, faint, style = Stroke(line))
            }
            for (i in 0..m.cols step step) {
                val path = Path()
                for (j in 0..m.rows) {
                    val k = (j * m.stride + i) * 2
                    val o = sc(m.pts[k], m.pts[k + 1])
                    if (j == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, faint, style = Stroke(line))
            }
            val r = 8.dp.toPx()
            w.pinTo.forEachIndexed { i, p ->
                val o = sc(p.x, p.y)
                val picked = i == w.pinSelected
                drawCircle(shade, r + line * 1.5f, o)
                drawCircle(if (picked) Q.accent else Color.White, r, o)
                drawCircle(if (picked) Color.White else Color.Black, r * 0.35f, o)
            }
        }
        WarpKind.LIQUIFY -> {
            // The face being reshaped, when there are several
            if (w.faces.size > 1) w.faces.getOrNull(w.faceIndex)?.let { f ->
                val a = sc(f.box.left, f.box.top)
                drawRect(
                    Color.White.copy(alpha = 0.6f), a, androidx.compose.ui.geometry.Size(f.box.width() * s, f.box.height() * s),
                    style = Stroke(line, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            vm.warpCursor?.let { c ->
                val o = sc(c.x, c.y)
                val r = vm.liquifyRadius(w) * s
                drawCircle(shade, r, o, style = Stroke(line * 3f))
                drawCircle(Color.White, r, o, style = Stroke(line))
                drawCircle(Color.White, line * 2f, o)
            }
        }
    }
}
