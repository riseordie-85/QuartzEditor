package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A layer drawn differently for a moment: an adjustment/filter preview, or a layer being dragged.
 */
class LayerPreview(
    val layerId: Long,
    /** Extra offset while dragging the layer (canvas pixels). */
    val dragX: Float = 0f,
    val dragY: Float = 0f,
    /** Adjustments/filter being tried out, drawn from [proxy] (a smaller copy of the layer). */
    val fx: FxParams? = null,
    val proxy: Bitmap? = null,
    /** Layer-local area [proxy] covers. */
    val proxyBounds: RectF? = null,
    /** Being transformed: [proxy] (with its mask applied) drawn through this matrix (proxy pixels → canvas). */
    val transform: android.graphics.Matrix? = null,
) {
    private val shader by lazy { ColorShader() }

    fun drawFx(canvas: Canvas, layer: Layer, docW: Float, docH: Float) {
        val p = proxy ?: return
        val b = proxyBounds ?: return
        val f = fx ?: return
        canvas.save()
        canvas.translate(layer.x + dragX + b.left, layer.y + dragY + b.top)
        canvas.scale(b.width() / p.width, b.height() / p.height)
        shader.bind(f, p)
        canvas.drawRect(0f, 0f, p.width.toFloat(), p.height.toFloat(), shader.paint)
        canvas.restore()
        // The canvas is in canvas pixels here, so the vignette centers on the canvas
        Gpu.drawVignette(canvas, f.vignette, 0f, 0f, docW, docH)
    }
}

/**
 * Draws a document. The same code draws the screen (hardware canvas) and exports (software
 * canvas), so what you see is what you save. The canvas must already be in canvas-pixel units.
 */
object DocRenderer {

    /**
     * @param visible the part of the canvas that's on screen (canvas pixels), to skip hidden tiles
     * @param smooth bilinear filtering (off when zoomed in close, so pixels stay crisp)
     */
    fun draw(
        canvas: Canvas,
        doc: Doc,
        visible: RectF,
        stroke: StrokeSession? = null,
        preview: LayerPreview? = null,
        smooth: Boolean = true,
        effects: ((Layer) -> BuiltEffects?)? = null,
    ) {
        val w = doc.width.toFloat()
        val h = doc.height.toFloat()
        val area = RectF(0f, 0f, w, h)
        if (!area.intersect(visible)) return
        // A little margin so filtering at the edge has neighbors
        val bounds = RectF(area.left - 2f, area.top - 2f, area.right + 2f, area.bottom + 2f)
        canvas.save()
        canvas.clipRect(area)
        // Blend modes mix with the layers below, not with whatever is behind the canvas (the checkerboard)
        val isolate = doc.layers.any { it.props.visible && it.props.blend != Blend.NORMAL }
        if (isolate) canvas.saveLayer(bounds, null)
        val tilePaint = Paint().apply { isFilterBitmap = smooth }
        for (layer in doc.layers) {
            if (!layer.props.visible) continue
            drawLayer(canvas, doc.width.toFloat(), doc.height.toFloat(), layer, bounds, tilePaint, stroke?.takeIf { it.layer === layer }, preview?.takeIf { it.layerId == layer.id }, smooth, effects)
        }
        if (isolate) canvas.restore()
        canvas.restore()
    }

    private fun drawLayer(
        canvas: Canvas,
        docW: Float,
        docH: Float,
        layer: Layer,
        bounds: RectF,
        tilePaint: Paint,
        stroke: StrokeSession?,
        preview: LayerPreview?,
        smooth: Boolean,
        effects: ((Layer) -> BuiltEffects?)?,
    ) {
        val p = layer.props
        val transforming = preview?.transform != null
        val style = p.style
        val built = if (style.needsBuild && !transforming) effects?.invoke(layer) else null
        val overlay = style.colorOverlay.takeIf { it.on && !transforming }
        val dx = layer.x + (preview?.dragX ?: 0f)
        val dy = layer.y + (preview?.dragY ?: 0f)

        if (built == null && overlay == null) {
            // No effects: the layer's opacity and fill simply multiply
            val alpha = (p.opacity * p.fill).coerceIn(0f, 1f)
            if (alpha <= 0f) return
            val mask = if (layer.maskActive && !transforming) layer.mask else null
            val plain = p.blend == Blend.NORMAL && alpha >= 1f && mask == null && stroke == null && preview?.fx == null && !transforming
            if (plain) {
                layer.tiles.drawTo(canvas, dx, dy, tilePaint, RectF(bounds))
                return
            }
            canvas.saveLayer(bounds, Paint().apply {
                this.alpha = (alpha * 255).roundToInt()
                blendMode = p.blend.mode
            })
            drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
            canvas.restore()
            return
        }

        // With effects: opacity covers everything, fill only the layer's own pixels (like Photoshop)
        if (p.opacity <= 0f) return
        canvas.saveLayer(bounds, Paint().apply {
            alpha = (p.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            blendMode = p.blend.mode
        })
        if (built != null) {
            if (style.dropShadow.on) Effects.drawImage(canvas, built, built.dropShadow, dx, dy, style.dropShadow.color, style.dropShadow.opacity)
            if (style.outerGlow.on) Effects.drawImage(canvas, built, built.outerGlow, dx, dy, style.outerGlow.color, style.outerGlow.opacity)
        }
        // Fill fades the pixels only
        if (p.fill > 0f) {
            canvas.saveLayer(bounds, Paint().apply { alpha = (p.fill.coerceIn(0f, 1f) * 255).roundToInt() })
            drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
            canvas.restore()
        }
        if (overlay != null && overlay.opacity > 0f) {
            // The layer's shape, colored (at full strength, whatever the fill)
            canvas.saveLayer(bounds, Paint().apply { alpha = (overlay.opacity.coerceIn(0f, 1f) * 255).roundToInt() })
            drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
            canvas.drawPaint(Paint().apply {
                color = overlay.color or 0xFF000000.toInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            })
            canvas.restore()
        }
        if (built != null && style.innerShadow.on) {
            // Already limited to the layer's shape
            Effects.drawImage(canvas, built, built.innerShadow, dx, dy, style.innerShadow.color, style.innerShadow.opacity)
        }
        if (built != null && style.stroke.on) {
            Effects.drawImage(canvas, built, built.stroke, dx, dy, style.stroke.color, style.stroke.opacity)
        }
        canvas.restore()
    }

    /** The layer's own pixels: tiles (or a preview), a stroke in progress, and its mask. */
    private fun drawContent(
        canvas: Canvas,
        docW: Float,
        docH: Float,
        layer: Layer,
        bounds: RectF,
        tilePaint: Paint,
        stroke: StrokeSession?,
        preview: LayerPreview?,
        smooth: Boolean,
        dx: Float,
        dy: Float,
    ) {
        val local = RectF(bounds).apply { offset(-dx, -dy) }
        val t = preview?.transform
        if (t != null && preview.proxy != null) {
            // Being transformed: the proxy already has the mask applied
            canvas.save()
            canvas.concat(t)
            canvas.drawBitmap(preview.proxy, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return
        }
        if (preview?.fx != null) {
            preview.drawFx(canvas, layer, docW, docH)
        } else {
            layer.tiles.drawTo(canvas, dx, dy, tilePaint, RectF(bounds))
        }
        val pixelStroke = stroke?.takeIf { it.target == StrokeTarget.PIXELS }
        val maskStroke = stroke?.takeIf { it.target == StrokeTarget.MASK }
        if (pixelStroke != null) {
            canvas.save()
            canvas.translate(dx, dy)
            pixelStroke.applyToPixels(canvas, 0f, 0f, local, smooth = smooth)
            canvas.restore()
        }
        val mask = if (layer.maskActive) layer.mask else null
        if (mask != null) {
            canvas.saveLayer(bounds, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            mask.drawTo(canvas, dx, dy, Paint().apply {
                isFilterBitmap = smooth
                color = Color.BLACK
            }, RectF(bounds))
            if (maskStroke != null) {
                canvas.save()
                canvas.translate(dx, dy)
                maskStroke.applyToMask(canvas, 0f, 0f, local, smooth = smooth)
                canvas.restore()
            }
            canvas.restore()
        }
    }

    /**
     * Draws [layers] (with their masks, opacity and blend modes) over a transparent background,
     * covering canvas area [region] — which may reach past the canvas edges.
     */
    fun renderLayers(layers: List<Layer>, region: android.graphics.Rect, docW: Int, docH: Int, effects: ((Layer) -> BuiltEffects?)? = null): Bitmap {
        val bmp = Bitmap.createBitmap(region.width().coerceAtLeast(1), region.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        val c = Canvas(bmp)
        c.translate(-region.left.toFloat(), -region.top.toFloat())
        val b = RectF(region)
        val paint = Paint()
        for (l in layers) {
            if (!l.props.visible) continue
            drawLayer(c, docW.toFloat(), docH.toFloat(), l, b, paint, null, null, false, effects)
        }
        return bmp
    }

    /** The whole flattened image at full size (software rendering, any thread). */
    fun flatten(doc: Doc, background: Int = Color.TRANSPARENT, effects: ((Layer) -> BuiltEffects?)? = null): Bitmap {
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        val c = Canvas(bmp)
        if (Color.alpha(background) > 0) c.drawColor(background)
        draw(c, doc, RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat()), effects = effects)
        return bmp
    }

    /** A small copy of the whole image, longest side [maxPx]. */
    fun thumbnail(doc: Doc, maxPx: Int, background: Int = Color.TRANSPARENT, effects: ((Layer) -> BuiltEffects?)? = null): Bitmap {
        val s = min(1f, maxPx.toFloat() / max(doc.width, doc.height))
        val w = (doc.width * s).roundToInt().coerceAtLeast(1)
        val h = (doc.height * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (Color.alpha(background) > 0) c.drawColor(background)
        c.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        draw(c, doc, RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat()), effects = effects)
        return bmp
    }

    /** The color you see at one canvas pixel (all visible layers), or null where it's see-through. */
    fun sample(doc: Doc, x: Int, y: Int, effects: ((Layer) -> BuiltEffects?)? = null): Int? {
        if (x !in 0 until doc.width || y !in 0 until doc.height) return null
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-x.toFloat(), -y.toFloat())
        draw(c, doc, RectF(x.toFloat(), y.toFloat(), x + 1f, y + 1f), smooth = false, effects = effects)
        val px = bmp.getPixel(0, 0)
        bmp.recycle()
        return if (Color.alpha(px) < 8) null else px or 0xFF000000.toInt()
    }

    /** True when the layer has a visible pixel at canvas point ([x], [y]). */
    fun layerHitTest(layer: Layer, x: Int, y: Int): Boolean {
        val lx = x - layer.x
        val ly = y - layer.y
        val k = TileGrid.key(Math.floorDiv(lx, TileGrid.SIZE), Math.floorDiv(ly, TileGrid.SIZE))
        val tile = layer.tiles[k] ?: return false
        val px = tile.getPixel(Math.floorMod(lx, TileGrid.SIZE), Math.floorMod(ly, TileGrid.SIZE))
        if (Color.alpha(px) < 20) return false
        val m = if (layer.maskActive) layer.mask?.get(k) else null
        if (m != null && Color.alpha(m.getPixel(Math.floorMod(lx, TileGrid.SIZE), Math.floorMod(ly, TileGrid.SIZE))) > 235) return false
        return true
    }

    /** One layer (with its mask) as a bitmap covering its tiles, at [scale]. Returns the bitmap and the layer-local area. */
    fun layerBitmap(layer: Layer, scale: Float = 1f, includeMask: Boolean = false): Pair<Bitmap, RectF>? {
        val b = layer.tiles.tileBounds() ?: return null
        val w = (b.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (b.height() * scale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        val c = Canvas(bmp)
        c.scale(w / b.width().toFloat(), h / b.height().toFloat())
        c.translate(-b.left.toFloat(), -b.top.toFloat())
        val p = Paint().apply { isFilterBitmap = true }
        if (includeMask && layer.maskActive) {
            c.saveLayer(null, null)
            layer.tiles.drawTo(c, 0f, 0f, p)
            val mp = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                isFilterBitmap = true
            }
            layer.mask?.drawTo(c, 0f, 0f, mp)
            c.restore()
        } else {
            layer.tiles.drawTo(c, 0f, 0f, p)
        }
        return bmp to RectF(b)
    }

    /** Small picture of a layer's pixels over the canvas area (for the Layers panel). */
    fun layerThumb(layer: Layer, docW: Int, docH: Int, maxPx: Int): Bitmap {
        val s = maxPx.toFloat() / max(docW, docH)
        val w = (docW * s).roundToInt().coerceAtLeast(1)
        val h = (docH * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / docW.toFloat(), h / docH.toFloat())
        c.clipRect(0f, 0f, docW.toFloat(), docH.toFloat())
        layer.tiles.drawTo(c, layer.x.toFloat(), layer.y.toFloat(), Paint().apply { isFilterBitmap = true }, RectF(0f, 0f, docW.toFloat(), docH.toFloat()))
        return bmp
    }

    /** Small picture of a layer's mask: white shows, black hides. */
    fun maskThumb(layer: Layer, docW: Int, docH: Int, maxPx: Int): Bitmap? {
        val m = layer.mask ?: return null
        val s = maxPx.toFloat() / max(docW, docH)
        val w = (docW * s).roundToInt().coerceAtLeast(1)
        val h = (docH * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(w / docW.toFloat(), h / docH.toFloat())
        c.clipRect(0f, 0f, docW.toFloat(), docH.toFloat())
        m.drawTo(c, layer.x.toFloat(), layer.y.toFloat(), Paint().apply { isFilterBitmap = true; color = Color.BLACK }, RectF(0f, 0f, docW.toFloat(), docH.toFloat()))
        return bmp
    }

    /**
     * Prepares every visible layer's effects for a background render (export, merge, save
     * thumbnail). [doc] should be a [Doc.shallowCopy]. Images are at most [maxSide] across.
     */
    fun buildAllEffects(doc: Doc, maxSide: Int, only: Collection<Layer> = doc.layers): Map<Long, BuiltEffects> {
        val out = HashMap<Long, BuiltEffects>()
        for (l in only) {
            if (!l.props.visible || !l.props.style.needsBuild) continue
            val mask = if (l.maskActive) l.mask?.tiles else null
            Effects.build(Unit, l.props.style, l.tiles.tiles, mask, maxSide)?.let { out[l.id] = it }
        }
        return out
    }
}
