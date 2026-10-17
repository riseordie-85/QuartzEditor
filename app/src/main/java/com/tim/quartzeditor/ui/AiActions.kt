package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.RectF
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Models
import com.tim.quartzeditor.engine.Sam
import com.tim.quartzeditor.engine.SelectMode
import com.tim.quartzeditor.engine.Selection
import com.tim.quartzeditor.engine.TileGrid

// Select Object: tap an object (or drag a box around it) and the on-device Segment Anything model
// outlines it. The first time on a picture it studies it for a moment; after that it's quick.

/** The picture the model last looked at (kept so the next tap is quick). */
private var aiPicture: Pair<Any, Bitmap>? = null

/** What the picture looks like, for knowing when the model has to look again. */
private fun pictureKey(d: Doc): Any = listOf(d, d.width, d.height) + d.layers.map {
    listOf(it.id, System.identityHashCode(it.tiles), System.identityHashCode(it.mask), it.pixelVersion, it.props, it.x, it.y)
}

/** Lets go of the AI models and what they looked at. */
fun releaseAi() {
    aiPicture?.second?.recycle()
    aiPicture = null
    Models.release()
}

/** Select Object at a tap (canvas pixels). */
fun EditorViewModel.selectObjectAt(x: Float, y: Float) = selectObject(listOf(Triple(x, y, 1)), "Select object")

/** Select Object inside a dragged box (canvas pixels). */
fun EditorViewModel.selectObjectIn(r: RectF) {
    if (r.width() < 4f || r.height() < 4f) {
        selectObjectAt(r.centerX(), r.centerY())
        return
    }
    selectObject(listOf(Triple(r.left, r.top, 2), Triple(r.right, r.bottom, 3)), "Select object")
}

private fun EditorViewModel.selectObject(points: List<Triple<Float, Float, Int>>, label: String) {
    val d = doc ?: return
    if (pendingFirst { selectObject(points, label) }) return
    if (!Models.has(ctx, Models.SAM_ENCODER) || !Models.has(ctx, Models.SAM_DECODER)) {
        toast("This build doesn't include the Select Object model. Build once with internet on so Android Studio downloads it")
        return
    }
    val key = pictureKey(d)
    val fresh = aiPicture?.first == key && Sam.hasEmbeddingFor(key)
    val copy = if (fresh) null else d.shallowCopy()
    val old = d.selection
    val mode = selectMode
    val app = ctx
    setSelectionComputed(if (mode == SelectMode.NEW) label else "Change selection", busyLabel = if (fresh) "Selecting…" else "Looking at the picture…") {
        val pic = if (copy != null) {
            aiPicture?.second?.recycle()
            aiPicture = null
            DocRenderer.flatten(copy).also { aiPicture = key to it }
        } else aiPicture!!.second
        Sam.encode(app, pic, key)
        val (conf, size) = Sam.mask(app, points)
        val sel = Sam.refined(pic, conf, size.first, size.second)
        val grid = TileGrid.fromBitmap(sel, 0, 0, alphaOnly = true)
        sel.recycle()
        Selection.combineMask(old, grid, mode)
    }
}
