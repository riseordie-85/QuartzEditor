package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.TransformState
import com.tim.quartzeditor.engine.Transformer
import com.tim.quartzeditor.engine.SmartArt
import com.tim.quartzeditor.engine.SmartSpec
import com.tim.quartzeditor.engine.VectorArt
import com.tim.quartzeditor.ui.EditorViewModel.Companion.PREVIEW_PX
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Transform (scale, rotate, flip, skew, perspective, distort), and turning or flipping the whole image. (Moved out of EditorViewModel.kt; same code, as extension functions.)

/** One layer being transformed: its pixels (and mask) as they were, and a smaller copy to show. */
class TransformPart(
    val layer: Layer,
    val tiles: Map<Long, Bitmap>,
    val mask: Map<Long, Bitmap>?,
    val sourceGrid: TileGrid,
    /** Layer-local area of the pixels being transformed. */
    val content: Rect,
    val proxy: Bitmap,
    /** Proxy pixels per canvas pixel. */
    val proxyScale: Float,
) {
    /** This layer's pixels (layer-local) → the canvas, for a transform [st] made in canvas pixels. */
    fun matrixFor(st: TransformState): Matrix = Matrix().apply {
        setTranslate(layer.x.toFloat(), layer.y.toFloat())
        postConcat(st.matrix())
    }
}

/**
 * A transform in progress: one or more layers (several when they're multi-selected) moved by one
 * box. [state] is in canvas pixels around all of them.
 */
class TransformSession(val parts: List<TransformPart>, state: TransformState) {
    var state by mutableStateOf(state)
    val start: TransformState = state
    val layer: Layer get() = parts.first().layer
}

/** Starts transforming the selected layer(s) (when the Transform tool is picked). */
fun EditorViewModel.beginTransform(onReady: (() -> Unit)? = null) {
    val d = doc ?: return
    if (transform != null) return
    // Several picked: every one of them with pixels (a picked folder brings everything in it,
    // as with moving); otherwise the active layer
    val chosen = selectedLayerIds().mapNotNull { d.layer(it) }
    val layers = if (chosen.size > 1) {
        pickedRoots().flatMap { r -> if (r.isGroup) d.layers.slice(d.block(r)) else listOf(r) }.filter { it.hasContent }.distinct()
    } else listOfNotNull(d.active)
    if (layers.isEmpty()) return
    layers.firstOrNull { it.props.locked }?.let {
        toast("\"${it.props.name}\" is locked")
        return
    }
    if (layers.size == 1 && !layers[0].hasContent) {
        toast("Transform works on a pixel, shape or text layer")
        return
    }
    val inputs = layers.filter { !it.tiles.isEmpty }.map { l ->
        Triple(l, HashMap(l.tiles.tiles), if (l.maskActive) l.mask?.let { HashMap(it.tiles) } else null)
    }
    if (inputs.isEmpty()) {
        toast(if (layers.size == 1) "This layer is empty" else "These layers are empty")
        return
    }
    val sources = inputs.associate { it.first to it.first.tiles }
    runBusy("Preparing…") {
        val prepared = withContext(Dispatchers.Default) {
            inputs.mapNotNull { (l, tiles, mask) ->
                val bounds = Transformer.contentBounds(tiles) ?: return@mapNotNull null
                val ps = min(1f, PREVIEW_PX / max(bounds.width(), bounds.height()).toFloat())
                val w = (bounds.width() * ps).roundToInt().coerceAtLeast(1)
                val h = (bounds.height() * ps).roundToInt().coerceAtLeast(1)
                val proxy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(proxy)
                c.scale(ps, ps)
                c.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                c.saveLayer(null, null)
                val p = Paint().apply { isFilterBitmap = true }
                TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }.drawTo(c, 0f, 0f, p)
                if (mask != null) {
                    TileGrid(alphaOnly = true).also { it.tiles.putAll(mask) }.drawTo(c, 0f, 0f, Paint().apply {
                        isFilterBitmap = true
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    })
                }
                c.restore()
                TransformPart(l, tiles, l.mask?.let { HashMap(it.tiles) }, sources.getValue(l), bounds, proxy, ps)
            }
        }
        if (prepared.isEmpty()) {
            toast("Nothing to transform")
            return@runBusy
        }
        if (doc !== d || prepared.any { it.layer.tiles !== it.sourceGrid }) return@runBusy
        // One box around all of them, in canvas pixels
        val box = RectF()
        prepared.forEachIndexed { i, p ->
            val r = RectF(p.content).apply { offset(p.layer.x.toFloat(), p.layer.y.toFloat()) }
            if (i == 0) box.set(r) else box.union(r)
        }
        val session = TransformSession(prepared, TransformState.start(box, 0, 0))
        transform = session
        showTransform(session)
        onReady?.invoke()
    }
}

internal fun EditorViewModel.showTransform(t: TransformSession) {
    fun previewOf(p: TransformPart): LayerPreview {
        val m = Matrix()
        m.setScale(1f / p.proxyScale, 1f / p.proxyScale)
        m.postTranslate(p.content.left.toFloat(), p.content.top.toFloat())
        m.postConcat(p.matrixFor(t.state))
        return LayerPreview(p.layer.id, transform = m, proxy = p.proxy)
    }
    val first = previewOf(t.parts.first())
    preview = if (t.parts.size == 1) first else LayerPreview(
        first.layerId, transform = first.transform, proxy = first.proxy,
        others = t.parts.drop(1).associate { it.layer.id to previewOf(it) },
    )
    doc?.invalidate()
}

fun EditorViewModel.updateTransform(change: (TransformState) -> TransformState) {
    val t = transform ?: return
    t.state = change(t.state)
    showTransform(t)
}

fun EditorViewModel.resetTransform() {
    val t = transform ?: return
    t.state = t.start.copy(keepAspect = t.state.keepAspect)
    showTransform(t)
}

fun EditorViewModel.cancelTransform() {
    if (transform == null) return
    transform = null
    preview = null
    tool = Tool.MOVE
    doc?.invalidate()
}

/** Applies the transform at full size (pixels and masks of every layer in it), then runs [onDone]. */
fun EditorViewModel.applyTransform(onDone: () -> Unit = {}) {
    val d = doc ?: return
    val t = transform ?: return
    if (busy != null) return
    rememberTransform(t)
    // Text is redrawn in its own font: wait for it if it's still loading
    val fonts = t.parts.filter { it.layer.props.text?.psdFont == null }.flatMap { it.layer.props.text?.fonts.orEmpty() }.distinct().filter { !fontReady(it) }
    if (fonts.isNotEmpty()) {
        withFonts(fonts) { applyTransform(onDone) }
        return
    }
    var st = t.state
    if (st.copy(keepAspect = true) == t.start.copy(keepAspect = true)) {
        cancelTransform()
        onDone()
        return
    }
    st = st.pixelAligned()
    // Shapes and text stay sharp: their own geometry is transformed and redrawn
    val vectorProps = t.parts.associate { p -> p.layer to (if (p.layer.isVector) VectorArt.transformed(p.layer.props, p.matrixFor(st)) else null) }
    // Smart layers are drawn again from their full-size contents, at the new placement (no quality lost)
    val smartProps = t.parts.associate { p ->
        p.layer to p.layer.props.smart?.let { sp -> p.layer.props.copy(smart = sp.withQuad(SmartSpec.mapped(sp.quad, p.matrixFor(st)))) }
    }
    val matrices = t.parts.associate { it.layer to it.matrixFor(st) }
    runBusy("Transforming…", onDone) {
        val results = withContext(Dispatchers.Default) {
            val limit = Transformer.limitFor(d.width, d.height)
            t.parts.map { p ->
                val m = matrices.getValue(p.layer)
                val vp = vectorProps[p.layer]
                val sp = smartProps[p.layer]?.smart
                // Text from Photoshop that's still its own pixels is transformed as pixels
                val grid = if (vp != null && vp.text?.psdFont == null) {
                    VectorArt.rasterize(vp, VectorArt.clipFor(0, 0, d.width, d.height))
                } else if (sp != null) {
                    SmartArt.rasterize(sp, limit)
                } else {
                    Transformer.render(p.tiles, false, p.content, m, limit)
                        ?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = false).also { bmp.recycle() } }
                        ?: TileGrid(alphaOnly = false)
                }
                val maskGrid = p.mask?.let { mk ->
                    Transformer.render(mk, true, p.content, m, limit)
                        ?.let { (bmp, area) -> TileGrid.fromBitmap(bmp, area.left, area.top, alphaOnly = true).also { bmp.recycle() } }
                        ?: TileGrid(alphaOnly = true)
                }
                Triple(p, grid, maskGrid)
            }
        }
        transform = null
        preview = null
        if (doc === d && t.parts.all { it.layer.tiles === it.sourceGrid }) {
            stackEdit("Transform") {
                for ((p, grid, maskGrid) in results) {
                    val l = p.layer
                    vectorProps[l]?.let { l.props = it }
                    smartProps[l]?.let { l.props = it }
                    l.tiles = grid
                    l.x = 0
                    l.y = 0
                    if (l.mask != null) l.mask = maskGrid
                }
            }
        }
        tool = Tool.MOVE
    }
}

/** Rotates the whole image a quarter turn (every layer and mask; the canvas turns too). */
fun EditorViewModel.rotateImage(clockwise: Boolean) {
    val d = doc ?: return
    val w = d.width
    val h = d.height
    canvasTransform(if (clockwise) "Rotate image right" else "Rotate image left", h, w) { m ->
        if (clockwise) {
            m.postRotate(90f)
            m.postTranslate(h.toFloat(), 0f)
        } else {
            m.postRotate(-90f)
            m.postTranslate(0f, w.toFloat())
        }
    }
}

fun EditorViewModel.flipImage(horizontal: Boolean) {
    val d = doc ?: return
    val w = d.width
    val h = d.height
    canvasTransform(if (horizontal) "Flip image horizontally" else "Flip image vertically", w, h) { m ->
        if (horizontal) {
            m.postScale(-1f, 1f)
            m.postTranslate(w.toFloat(), 0f)
        } else {
            m.postScale(1f, -1f)
            m.postTranslate(0f, h.toFloat())
        }
    }
}

/**
 * Turns, flips or straightens the whole image through [build] into a [newW]×[newH] canvas.
 * [perspective]: the matrix bends lines (Perspective crop), so guides and the selection are
 * cleared and what's kept around the new canvas is limited to a margin.
 */
internal fun EditorViewModel.canvasTransform(
    label: String, newW: Int, newH: Int, perspective: Boolean = false, onDone: (() -> Unit)? = null,
    /** Image size: layer styles scale by this too (null = they stay as they are). */
    styleScale: Float? = null,
    /** Upscale: photo (pixel) layers are made this many times bigger by the AI instead (0 = no AI). */
    aiFactor: Int = 0,
    build: (Matrix) -> Unit,
) {
    val d = doc ?: return
    if (pendingFirst { canvasTransform(label, newW, newH, perspective, onDone, styleScale, aiFactor, build) }) return
    // (Text from Photoshop that's still its own pixels turns as pixels: no font needed)
    val fonts = d.layers.filter { it.props.text?.psdFont == null }.flatMap { it.props.text?.fonts.orEmpty() }.distinct().filter { !fontReady(it) }
    if (fonts.isNotEmpty()) {
        withFonts(fonts) { canvasTransform(label, newW, newH, perspective, onDone, styleScale, aiFactor, build) }
        return
    }
    val layers = d.layers.toList()
    val snaps = layers.map { Triple(it, HashMap(it.tiles.tiles), it.mask?.let { m -> HashMap(m.tiles) }) }
    val oldProps = layers.associateWith { it.props }
    val newProps = HashMap<Layer, LayerProps>()
    val aiLayers = if (aiFactor > 0) layers.filter { it.isPixels && it.props.smart == null && it.tiles.tiles.isNotEmpty() } else emptyList()
    var aiDone = 0
    runBusy(if (aiFactor > 0) "Upscaling…" else if (styleScale != null) "Resizing…" else "Turning…", onDone = onDone) {
        val results = withContext(Dispatchers.Default) {
            // Beyond a perspective's horizon pixels run off to infinity: keep only a margin
            val everywhere = if (perspective) Transformer.limitFor(newW, newH) else Rect(-1_000_000, -1_000_000, 1_000_000, 1_000_000)
            snaps.map { (l, tiles, mask) ->
                val m = Matrix().apply { setTranslate(l.x.toFloat(), l.y.toFloat()) }
                build(m)
                val area = TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }.tileBounds()
                val vp = if (l.isVector) VectorArt.transformed(oldProps.getValue(l), m) else null
                // A smart layer's placement turns with it (its pixels turn exactly, below)
                oldProps.getValue(l).smart?.let { sp ->
                    synchronized(newProps) { newProps[l] = oldProps.getValue(l).copy(smart = sp.withQuad(SmartSpec.mapped(sp.quad, m)).let { moved ->
                        // Resized: blurs in its smart filters keep their look
                        if (styleScale == null) moved else moved.withFilters(sp.filters.map { f -> f.copy(fx = f.fx.copy(blur = f.fx.blur * styleScale)) })
                    }) }
                }
                if (vp != null) synchronized(newProps) { newProps[l] = vp }
                val grid = if (l in aiLayers) {
                    // Upscale: new detail from the AI (one layer at a time; it's memory-hungry)
                    synchronized(aiLayers) {
                        aiUpscaleTiles(tiles, l.x, l.y, d.width, d.height, aiFactor) { f ->
                            updateBusy("Upscaling… ${((aiDone + f) * 100f / aiLayers.size).toInt()}%")
                        }.also { aiDone++ }
                    }
                } else if (vp != null && vp.text?.psdFont == null) {
                    VectorArt.rasterize(vp, VectorArt.clipFor(0, 0, newW, newH))
                } else area?.let { a -> Transformer.render(tiles, false, a, m, everywhere) }
                    ?.let { (bmp, at) -> TileGrid.fromBitmap(bmp, at.left, at.top, alphaOnly = false).also { bmp.recycle() } }
                    ?: TileGrid(alphaOnly = false)
                val maskGrid = mask?.let { mk ->
                    val ma = TileGrid(alphaOnly = true).also { it.tiles.putAll(mk) }.tileBounds()
                    ma?.let { a -> Transformer.render(mk, true, a, m, everywhere) }
                        ?.let { (bmp, at) -> TileGrid.fromBitmap(bmp, at.left, at.top, alphaOnly = true).also { bmp.recycle() } }
                        ?: TileGrid(alphaOnly = true)
                }
                Triple(l, grid, maskGrid)
            }
        }
        if (doc !== d) return@runBusy
        stackEdit(label) {
            for ((l, grid, maskGrid) in results) {
                val p = newProps[l] ?: l.props
                l.props = if (styleScale != null && p.style.any) p.copy(style = p.style.scaled(styleScale)) else p
                l.tiles = grid
                l.x = 0
                l.y = 0
                if (l.mask != null) l.mask = maskGrid
            }
            d.width = newW
            d.height = newH
            // A selection would no longer line up with the picture
            d.selection = null
            if (perspective) {
                // Straight guides don't stay straight through a perspective
                d.guidesX.clear()
                d.guidesY.clear()
                d.selection = null
                return@stackEdit
            }
            // Guides turn and flip with the picture
            val gm = Matrix().also(build)
            val nx = ArrayList<Float>()
            val ny = ArrayList<Float>()
            fun place(a: FloatArray) {
                gm.mapPoints(a)
                if (kotlin.math.abs(a[0] - a[2]) < 0.01f) nx.add(a[0]) else ny.add(a[1])
            }
            for (x in d.guidesX) place(floatArrayOf(x, 0f, x, 1f))
            for (y in d.guidesY) place(floatArrayOf(0f, y, 1f, y))
            d.guidesX.clear()
            d.guidesX.addAll(nx)
            d.guidesY.clear()
            d.guidesY.addAll(ny)
        }
        fit()
    }
}

/**
 * Image › Image size: scales the whole picture (every layer, mask, shape, text, smart layer and
 * guide) to [w]×[h] canvas pixels, as one undo step. [scaleStyles]: stroke widths, shadows and glows
 * scale with it, so the picture looks the same.
 */
fun EditorViewModel.resizeImage(w: Int, h: Int, scaleStyles: Boolean, onDone: (() -> Unit)? = null) {
    val d = doc ?: return
    val nw = w.coerceIn(1, com.tim.quartzeditor.engine.Doc.MAX_SIDE)
    val nh = h.coerceIn(1, com.tim.quartzeditor.engine.Doc.MAX_SIDE)
    if (nw == d.width && nh == d.height) return
    val sx = nw.toFloat() / d.width
    val sy = nh.toFloat() / d.height
    canvasTransform("Image size", nw, nh, onDone = onDone, styleScale = if (scaleStyles) kotlin.math.sqrt(sx * sy) else 1f) { m -> m.postScale(sx, sy) }
}
