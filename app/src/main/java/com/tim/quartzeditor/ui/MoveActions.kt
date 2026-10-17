package com.tim.quartzeditor.ui

import android.graphics.Color
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.Transformer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Moving layers with the Move tool (snapping, several layers, vector redraws). (Moved out of EditorViewModel.kt; same code, as extension functions.)

fun EditorViewModel.beginMove(): Boolean {
    val d = doc ?: return false
    if (pendingFx != null) {
        applyFx()
        return false
    }
    val l = d.active ?: return false
    // Several selected: they all move (minus any inside a selected folder, which go with it)
    val chosen = selectedLayerIds().mapNotNull { d.layer(it) }.ifEmpty { listOf(l) }
    val roots = chosen.filter { c -> chosen.none { g -> g !== c && g.isGroup && d.isInside(c, g) } }
    roots.firstOrNull { it.props.locked }?.let {
        toast("\"${it.props.name}\" is locked")
        return false
    }
    moveLayer = l
    moveRoots = roots
    preview = movePreview(0f, 0f)
    // Where its pixels are (for snapping), worked out in the background
    moveContent = null
    val parts = roots.flatMap { r -> if (r.isGroup) d.layers.slice(d.block(r)) else listOf(r) }.filter { it.hasContent }.map { Triple(HashMap(it.tiles.tiles), it.x, it.y) }
    viewModelScope.launch {
        val r = withContext(Dispatchers.Default) {
            var u: RectF? = null
            for ((tiles, x, y) in parts) {
                val b = Transformer.contentBounds(tiles) ?: continue
                val rb = RectF(b).apply { offset(x.toFloat(), y.toFloat()) }
                u = u?.apply { union(rb) } ?: rb
            }
            u
        }
        if (moveLayer === l && moveRoots === roots) moveContent = r
    }
    return true
}

/** Drag offset ([dx], [dy]) in canvas pixels; it snaps within [snapRadius] canvas pixels. */
fun EditorViewModel.moveTo(dx: Float, dy: Float, snapRadius: Float = 0f, lockedAxis: Int = 0) {
    if (moveLayer == null) return
    val (sx, sy) = snapDelta(moveContent, dx, dy, snapRadius, lockedAxis)
    preview = movePreview(sx, sy)
    doc?.invalidate()
}

/** Every layer being moved drawn shifted by ([dx], [dy]). */
internal fun EditorViewModel.movePreview(dx: Float, dy: Float): LayerPreview {
    val first = moveRoots.firstOrNull() ?: moveLayer!!
    return LayerPreview(first.id, dragX = dx, dragY = dy, others = moveRoots.drop(1).associate { it.id to LayerPreview(it.id, dragX = dx, dragY = dy) })
}

fun EditorViewModel.endMove() {
    if (moveLayer == null) return
    val d = doc ?: return
    val p = preview
    val roots = moveRoots
    moveLayer = null
    moveRoots = emptyList()
    preview = null
    clearSnapGuides()
    val dx = (p?.dragX ?: 0f).roundToInt()
    val dy = (p?.dragY ?: 0f).roundToInt()
    if (dx == 0 && dy == 0) {
        d.invalidate()
        return
    }
    val smartRedo = ArrayList<com.tim.quartzeditor.engine.Layer>()
    stackEdit("Move") {
        // A folder moves everything in it
        val all = roots.flatMap { r -> if (r.isGroup) d.layers.slice(d.block(r)) else listOf(r) }.distinct()
        for (x in all) {
            val clipped = vectorClipped(x, x.x, x.y, d.width, d.height)
            if (smartClipped(x, x.x, x.y, d)) smartRedo.add(x)
            x.x += dx
            x.y += dy
            // A shape that reached far past the canvas has the rest drawn now
            if (clipped) redrawVectorNow(x)
            if (smartClipped(x, x.x, x.y, d)) smartRedo.add(x)
        }
    }
    // A smart layer that reached far past the canvas is drawn again from its contents
    for (x in smartRedo.distinct()) redrawSmartSoon(d, x)
}

fun EditorViewModel.cancelMove() {
    clearSnapGuides()
    moveLayer = null
    moveRoots = emptyList()
    preview = null
    doc?.invalidate()
}

/** Move tool tap: picks the top layer with something visible under the finger. */
fun EditorViewModel.selectLayerAt(x: Float, y: Float) {
    val d = doc ?: return
    val hit = d.layers.asReversed().firstOrNull {
        it.hasContent && d.effectivelyVisible(it) && DocRenderer.layerHitTest(it, x.toInt(), y.toInt()) &&
            // A clipped layer only shows where its base has pixels
            (!it.props.clip || clipBaseOf(it)?.let { b -> DocRenderer.layerHitTest(b, x.toInt(), y.toInt()) } != false)
    }
    if (hit != null && hit.id != d.activeId) selectLayer(hit.id)
}

/** Eyedropper: sets the color in the slot being edited from the image. */
fun EditorViewModel.sampleAt(x: Float, y: Float, commit: Boolean): Int? {
    val d = doc ?: return null
    val c = (if (d.layers.any { it.isAdjustment && d.effectivelyVisible(it) }) sampleFromPicture(d, x.toInt(), y.toInt()) else DocRenderer.sample(d, x.toInt(), y.toInt(), effectsLookup)) ?: return null
    if (commit) {
        setColor(colorSlot, c)
        rememberColor(c)
    }
    return c
}

internal fun EditorViewModel.sampleFromPicture(d: Doc, x: Int, y: Int): Int? {
    if (x !in 0 until d.width || y !in 0 until d.height) return null
    val v = d.renderVersion
    if (samplePictureVersion != v && sampleJob?.isActive != true) {
        val copy = d.shallowCopy()
        sampleJob = viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                try {
                    DocRenderer.flatten(copy, effects = { l -> effectsLookup(l) })
                } catch (e: Throwable) {
                    null
                }
            }
            if (bmp != null && doc === d) {
                samplePicture = bmp
                samplePictureVersion = v
            }
        }
    }
    val pic = samplePicture?.takeIf { it.width == d.width && it.height == d.height } ?: return null
    val px = pic.getPixel(x, y)
    return if (Color.alpha(px) < 8) null else px or 0xFF000000.toInt()
}
