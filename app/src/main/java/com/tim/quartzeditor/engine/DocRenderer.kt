package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A picture stretched over a grid of [cols]×[rows] cells whose points are [pts] (canvas pixels). */
class MeshPreview(val proxy: Bitmap, val cols: Int, val rows: Int, val pts: FloatArray)

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
    /** A gradient being dragged out on this layer. */
    val gradient: GradientDrag? = null,
    /** Adjustments being tried only change what's selected. */
    val selection: Selection? = null,
    /** Other layers drawn differently at the same time (moving or transforming several layers). */
    val others: Map<Long, LayerPreview> = emptyMap(),
    /** Being warped (Warp, Puppet Warp, Liquify): [proxy] (with its mask applied) stretched over these points. */
    val mesh: MeshPreview? = null,
) {
    /** How layer [id] is drawn for the moment, if it's one of these. */
    fun of(id: Long): LayerPreview? = if (id == layerId) this else others[id]

    private val shader by lazy { ColorShader() }

    /** A separate one for the blur preview: as a render effect, its picture comes from the effect. */
    private val effectShader by lazy { ColorShader() }

    fun drawFx(canvas: Canvas, layer: Layer, docW: Float, docH: Float) {
        val p = proxy ?: return
        val b = proxyBounds ?: return
        val f = fx ?: return
        canvas.save()
        canvas.translate(layer.x + dragX + b.left, layer.y + dragY + b.top)
        canvas.scale(b.width() / p.width, b.height() / p.height)
        // Blur radius in proxy pixels
        val k = p.width / b.width()
        val r = f.blur * k
        if (r > 0.3f && canvas.isHardwareAccelerated) {
            val colorFx = { effectShader.effect(f, max(1f, max(p.width, p.height) / 1500f)) }
            val left = layer.x + dragX + b.left
            val top = layer.y + dragY + b.top
            val covers = left <= 0f && top <= 0f && left + b.width() >= docW && top + b.height() >= docH
            val node = RenderNode("fxPreview")
            if (covers) {
                // Covers the canvas: blurred like the result, its edges carrying on at the canvas edge
                val cx = (-left * k).roundToInt()
                val cy = (-top * k).roundToInt()
                val cw = (docW * k).roundToInt().coerceAtLeast(1)
                val ch = (docH * k).roundToInt().coerceAtLeast(1)
                node.setPosition(cx, cy, cx + cw, cy + ch)
                val blur = Gpu.blurEffect(r, Shader.TileMode.CLAMP)
                node.setRenderEffect(if (f.copy(blur = 0f).isIdentity) blur else RenderEffect.createChainEffect(colorFx(), blur))
                node.beginRecording().drawBitmap(p, -cx.toFloat(), -cy.toFloat(), null)
                node.endRecording()
            } else {
                // Blurred, then adjusted, with room around it for the blur to spread
                val pad = Gpu.blurReach(r)
                node.setPosition(-pad, -pad, p.width + pad, p.height + pad)
                val blur = Gpu.blurEffect(r)
                node.setRenderEffect(if (f.copy(blur = 0f).isIdentity) blur else RenderEffect.createChainEffect(colorFx(), blur))
                node.beginRecording().drawBitmap(p, pad.toFloat(), pad.toFloat(), null)
                node.endRecording()
            }
            canvas.drawRenderNode(node)
        } else {
            shader.bind(f, p)
            canvas.drawRect(0f, 0f, p.width.toFloat(), p.height.toFloat(), shader.paint)
        }
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

    /** Everything a draw pass needs, passed down through folders. */
    private class Ctx(
        val doc: Doc?,
        val docW: Float,
        val docH: Float,
        /** Canvas area being drawn (canvas pixels, with a small margin). */
        val bounds: RectF,
        val tilePaint: Paint,
        val stroke: StrokeSession?,
        val preview: LayerPreview?,
        val smooth: Boolean,
        val effects: ((Layer) -> BuiltEffects?)?,
        /** On screen: canvas pixels → screen pixels, and the screen size (null when rendering a picture). */
        val view: Matrix?,
        val viewW: Int,
        val viewH: Int,
        /** Off screen: picture pixels per canvas pixel. */
        val pixelScale: Float,
        /** Layers to draw at the top (null = the document's top level). */
        val roots: List<Layer>? = null,
        /** Clipping: the layers clipped to each base layer (by the base's id), drawn with it. */
        val clips: Map<Long, List<Layer>> = emptyMap(),
        /** Drawing a clipping group: its base (the others are cut to its pixels). */
        val clipBase: Layer? = null,
    ) {
        fun with(clips: Map<Long, List<Layer>> = this.clips, clipBase: Layer? = this.clipBase) = Ctx(
            doc, docW, docH, bounds, tilePaint, stroke, preview, smooth, effects, view, viewW, viewH, pixelScale, roots, clips, clipBase,
        )
    }

    /**
     * Sorts sibling layers (bottom first) into what's drawn on its own and what's clipped: a
     * clipped layer goes with the nearest unclipped pixel, shape or text layer below it. Clipped
     * layers with no such base are drawn normally. A hidden base hides its clipped layers.
     */
    private fun clipGroups(list: List<Layer>): Pair<List<Layer>, Map<Long, List<Layer>>> {
        if (list.none { it.props.clip }) return list.filter { it.props.visible } to emptyMap()
        val shown = ArrayList<Layer>()
        val clips = HashMap<Long, MutableList<Layer>>()
        var base: Layer? = null
        for (l in list) {
            if (l.props.clip && base != null) {
                if (base.props.visible && l.props.visible) clips.getOrPut(base.id) { mutableListOf() }.add(l)
                continue
            }
            base = if (!l.props.clip && l.canBeClipBase) l else null
            if (l.props.visible) shown.add(l)
        }
        return shown to clips
    }

    /**
     * @param visible the part of the canvas that's on screen (canvas pixels), to skip hidden tiles
     * @param smooth bilinear filtering (off when zoomed in close, so pixels stay crisp)
     * @param view on screen: canvas → screen matrix and the view's size (needed by adjustment layers)
     * @param pixelScale off screen: output pixels per canvas pixel
     */
    fun draw(
        canvas: Canvas,
        doc: Doc,
        visible: RectF,
        stroke: StrokeSession? = null,
        preview: LayerPreview? = null,
        smooth: Boolean = true,
        effects: ((Layer) -> BuiltEffects?)? = null,
        view: Matrix? = null,
        viewW: Int = 0,
        viewH: Int = 0,
        pixelScale: Float = 1f,
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
        val isolate = doc.layers.any { it.props.visible && (it.props.blend != Blend.NORMAL || it.props.style.anyBlend) }
        if (isolate) canvas.saveLayer(bounds, null)
        val ctx = Ctx(doc, w, h, bounds, Paint().apply { isFilterBitmap = smooth }, stroke, preview, smooth, effects, view, viewW, viewH, pixelScale)
        drawList(canvas, doc.children(0L), ctx)
        if (isolate) canvas.restore()
        canvas.restore()
    }

    /** Draws sibling layers bottom to top. Adjustment layers change everything drawn before them in the list. */
    private fun drawList(canvas: Canvas, list: List<Layer>, ctx0: Ctx, regroup: Boolean = true) {
        val shown: List<Layer>
        val ctx: Ctx
        if (regroup) {
            val (s, clips) = passThrough(list, ctx0)
            shown = s
            ctx = ctx0.with(clips = clips, clipBase = null)
        } else {
            shown = list.filter { it.props.visible }
            ctx = ctx0
        }
        val adjusts = shown.any { (it.isAdjustment && it.props.adjust != null && it.props.opacity > 0f) || customBlend(it, ctx) }
        if (!adjusts) {
            for (l in shown) drawNode(canvas, l, ctx)
            return
        }
        val v = ctx.view
        if (v != null && canvas.isHardwareAccelerated && ctx.viewW > 0 && ctx.viewH > 0) drawListGpu(canvas, shown, ctx, v) else drawListPicture(canvas, shown, ctx)
    }

    /**
     * Drawn on its own and then mixed with what's below by [BlendShader] (a blend mode Android
     * doesn't have). A clipping group's base is mixed as the whole group, by the list around it.
     */
    private fun customBlend(l: Layer, ctx: Ctx): Boolean =
        l.props.blend.custom != 0 && !l.isAdjustment && l !== ctx.clipBase && !(l.isGroup && l.props.passThrough) && l.props.opacity > 0f

    /**
     * [clipGroups], with Pass Through folders opened up: their layers are drawn in among the layers
     * around them, so their blend modes and adjustment layers reach the layers below the folder
     * (like Photoshop). A folder with less than full opacity or a mask is still drawn as one picture.
     */
    private fun passThrough(list: List<Layer>, ctx: Ctx): Pair<List<Layer>, Map<Long, List<Layer>>> {
        val (shown, clips) = clipGroups(list)
        val doc = ctx.doc ?: return shown to clips
        fun opens(l: Layer) = l.isGroup && l.props.passThrough && !l.maskActive && !l.props.style.any &&
            l.props.opacity * l.props.fill >= 0.999f && clips[l.id].isNullOrEmpty() && ctx.preview?.of(l.id) == null
        if (shown.none { opens(it) }) return shown to clips
        val out = ArrayList<Layer>(shown.size + 8)
        val all = HashMap(clips)
        for (l in shown) {
            if (!opens(l)) {
                out.add(l)
                continue
            }
            val (inner, innerClips) = passThrough(doc.children(l.id), ctx)
            out.addAll(inner)
            all.putAll(innerClips)
        }
        return out to all
    }

    /** [forceNormal]: blended Normally whatever its blend mode (it's mixed in by [BlendShader] afterwards). */
    private fun drawNode(canvas: Canvas, l: Layer, ctx: Ctx, forceNormal: Boolean = false) {
        val base = ctx.clipBase
        if (base != null && l !== base && !l.isAdjustment) {
            // A clipped layer: drawn on its own, then cut to its base's pixels, then blended on
            // (its own blend mode applies here, when the cut result goes onto the group)
            canvas.saveLayer(ctx.bounds, Paint().apply { blendMode = if (forceNormal) Blend.NORMAL.mode else l.props.blend.mode })
            drawNode(canvas, l, ctx.with(clips = emptyMap(), clipBase = null), blendNormal = true, fullOpacity = false)
            canvas.saveLayer(ctx.bounds, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            drawClipShape(canvas, base, ctx)
            canvas.restore()
            canvas.restore()
            return
        }
        val members = ctx.clips[l.id]
        if (base !== l && !members.isNullOrEmpty()) {
            // A clipping group: the base's opacity and blend mode apply to all of it (like Photoshop)
            val p = l.props
            if (p.opacity <= 0f) return
            canvas.saveLayer(ctx.bounds, Paint().apply {
                alpha = (p.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                blendMode = if (forceNormal) Blend.NORMAL.mode else p.blend.mode
            })
            drawList(canvas, listOf(l) + members, ctx.with(clips = emptyMap(), clipBase = l), regroup = false)
            canvas.restore()
            return
        }
        // A clipping group's base: its opacity and blend mode are applied around the whole group
        val asBase = base === l
        drawNode(canvas, l, ctx, blendNormal = asBase || forceNormal, fullOpacity = asBase)
    }

    /**
     * What layers clipped to [base] are cut to: its own pixels (and mask), or for a folder,
     * everything in it.
     */
    private fun drawClipShape(canvas: Canvas, base: Layer, ctx: Ctx) {
        val bp = ctx.preview?.of(base.id)
        if (base.isGroup) {
            drawNode(canvas, base, ctx.with(clips = emptyMap(), clipBase = null), blendNormal = true, fullOpacity = true, styled = false)
            return
        }
        drawContent(
            canvas, ctx.docW, ctx.docH, base, ctx.bounds, ctx.tilePaint, ctx.stroke?.takeIf { it.layer === base }, bp, ctx.smooth,
            base.x + (bp?.dragX ?: 0f), base.y + (bp?.dragY ?: 0f),
        )
    }

    /**
     * [blendNormal]: blended Normally (its blend mode is applied around it instead);
     * [fullOpacity]: its opacity likewise.
     */
    private fun drawNode(canvas: Canvas, l: Layer, ctx: Ctx, blendNormal: Boolean, fullOpacity: Boolean, styled: Boolean = true) {
        when (l.kind) {
            LayerKind.PIXELS, LayerKind.SHAPE, LayerKind.TEXT, LayerKind.SMART -> drawLayer(
                canvas, ctx.docW, ctx.docH, l, ctx.bounds, ctx.tilePaint,
                ctx.stroke?.takeIf { it.layer === l }, ctx.preview?.of(l.id), ctx.smooth, ctx.effects,
                blendNormal = blendNormal, fullOpacity = fullOpacity,
            )
            LayerKind.GROUP -> {
                val p = l.props.let { if (blendNormal) it.copy(blend = Blend.NORMAL) else it }.let { if (fullOpacity) it.copy(opacity = 1f) else it }
                val alpha = (p.opacity * p.fill).coerceIn(0f, 1f)
                val dragX = ctx.preview?.of(l.id)?.dragX ?: 0f
                val dragY = ctx.preview?.of(l.id)?.dragY ?: 0f
                if (styled && p.style.any && p.opacity > 0f) {
                    drawStyledGroup(canvas, l, ctx, blendNormal = blendNormal || p.passThrough, fullOpacity = fullOpacity, dragX = dragX, dragY = dragY)
                    return
                }
                if (alpha <= 0f) return
                // A folder is drawn as one picture, then blended (its layers' blend modes stay inside it).
                // Pass Through folders only get here with less than full opacity or a mask: blended Normally.
                canvas.saveLayer(ctx.bounds, Paint().apply {
                    this.alpha = (alpha * 255).roundToInt()
                    blendMode = if (p.passThrough) Blend.NORMAL.mode else p.blend.mode
                })
                canvas.save()
                canvas.translate(dragX, dragY)
                drawList(canvas, ctx.doc?.children(l.id) ?: emptyList(), ctx)
                canvas.restore()
                drawMask(canvas, l, ctx, l.x + dragX, l.y + dragY)
                canvas.restore()
            }
            LayerKind.ADJUSTMENT -> Unit // drawn by drawList
        }
    }

    /**
     * A folder with a layer style: everything in it is drawn together (through its mask), then
     * styled like a layer's pixels. On screen what's in it is recorded once and replayed for each
     * effect that needs its shape.
     */
    private fun drawStyledGroup(canvas: Canvas, l: Layer, ctx: Ctx, blendNormal: Boolean, fullOpacity: Boolean, dragX: Float, dragY: Float) {
        val kids = ctx.doc?.children(l.id) ?: emptyList()
        val inner = ctx.with(clips = emptyMap(), clipBase = null)
        fun drawKids(c: Canvas) {
            c.save()
            c.translate(dragX, dragY)
            drawList(c, kids, inner)
            c.restore()
            drawMask(c, l, ctx, l.x + dragX, l.y + dragY)
        }
        var node: RenderNode? = null
        val content: (Canvas) -> Unit = content@{ c ->
            if (!c.isHardwareAccelerated) {
                drawKids(c)
                return@content
            }
            val b = ctx.bounds
            val n = node ?: RenderNode("folder").also { rn ->
                val left = kotlin.math.floor(b.left).toInt()
                val top = kotlin.math.floor(b.top).toInt()
                rn.setPosition(left, top, kotlin.math.ceil(b.right).toInt().coerceAtLeast(left + 1), kotlin.math.ceil(b.bottom).toInt().coerceAtLeast(top + 1))
                val rc = rn.beginRecording()
                rc.translate(-left.toFloat(), -top.toFloat())
                drawKids(rc)
                rn.endRecording()
                node = rn
            }
            c.drawRenderNode(n)
        }
        drawLayer(
            canvas, ctx.docW, ctx.docH, l, ctx.bounds, ctx.tilePaint, null, ctx.preview?.of(l.id), ctx.smooth, ctx.effects,
            blendNormal = blendNormal, fullOpacity = fullOpacity, content = content, offset = l.x + dragX to l.y + dragY,
        )
    }

    /** Cuts a layer's (or folder's) mask out of what's in the current saveLayer. */
    private fun drawMask(canvas: Canvas, l: Layer, ctx: Ctx, dx: Float, dy: Float) {
        val mask = if (l.maskActive) l.mask else null
        val maskStroke = ctx.stroke?.takeIf { it.layer === l && it.target == StrokeTarget.MASK }
        if (mask == null) return
        canvas.saveLayer(ctx.bounds, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        mask.drawTo(canvas, dx, dy, Paint().apply {
            isFilterBitmap = ctx.smooth
            color = Color.BLACK
        }, RectF(ctx.bounds))
        if (maskStroke != null) {
            canvas.save()
            canvas.translate(dx, dy)
            maskStroke.applyToMask(canvas, 0f, 0f, RectF(ctx.bounds).apply { offset(-dx, -dy) }, smooth = ctx.smooth)
            canvas.restore()
        }
        ctx.preview?.of(l.id)?.gradient?.takeIf { it.toMask }?.let { g ->
            val area = RectF(0f, 0f, ctx.docW, ctx.docH).apply { intersect(ctx.bounds) }
            GradientPainter.paintMask(canvas, area, g.spec, g.start, g.end, g.selection, 0f, 0f)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------ adjustment layers

    /** Shaders for adjustment layers on screen, kept so they aren't rebuilt every frame (main thread only). */
    private val screenShaders = HashMap<Long, ColorShader>()

    /**
     * On screen: what's below each adjustment layer is recorded into a RenderNode, and a copy of it
     * is drawn through the adjustment's shader on top (at the layer's opacity, through its mask).
     */
    private fun drawListGpu(canvas: Canvas, list: List<Layer>, ctx: Ctx, view: Matrix) {
        val w = ctx.viewW
        val h = ctx.viewH
        fun node(name: String) = RenderNode(name).apply { setPosition(0, 0, w, h) }
        var below = node("below")
        var rec = below.beginRecording(w, h)
        rec.concat(view)
        // Screen pixel → canvas pixel (for Dissolve's pattern)
        val toDoc = FloatArray(9).also { v -> Matrix().also { inv -> view.invert(inv) }.getValues(v) }
        for (l in list) {
            if (customBlend(l, ctx)) {
                // What's below on the left, the layer alone on the right, mixed by the blend shader
                below.endRecording()
                val pair = RenderNode("pair").apply { setPosition(0, 0, 2 * w, h) }
                val pc = pair.beginRecording(2 * w, h)
                pc.drawRenderNode(below)
                pc.save()
                pc.translate(w.toFloat(), 0f)
                pc.clipRect(0, 0, w, h)
                pc.concat(view)
                drawNode(pc, l, ctx, forceNormal = true)
                pc.restore()
                pair.endRecording()
                pair.setRenderEffect(BlendShader.pairEffect(
                    l.props.blend, w, toDoc[Matrix.MSCALE_X], toDoc[Matrix.MSCALE_Y], toDoc[Matrix.MTRANS_X], toDoc[Matrix.MTRANS_Y],
                ))
                val next = node("next")
                val nr = next.beginRecording(w, h)
                nr.drawRenderNode(pair)
                nr.concat(view)
                below = next
                rec = nr
                continue
            }
            val fx = l.props.adjust
            if (!(l.isAdjustment && fx != null)) {
                drawNode(rec, l, ctx)
                continue
            }
            below.endRecording()
            val shader = screenShaders.getOrPut(l.id) { ColorShader() }
            val scale = run {
                val pts = floatArrayOf(0f, 0f, 1f, 0f)
                view.mapPoints(pts)
                kotlin.math.hypot(pts[2] - pts[0], pts[3] - pts[1])
            }
            val blurR = fx.blur * scale
            val colorFx = { shader.effect(fx, max(1f, scale * max(ctx.docW, ctx.docH) / 1500f)) }
            val adjusted = if (blurR > 0.3f) {
                // Blur: only the canvas's part of the screen (plus room for the blur), so its edges
                // carry on past the canvas edge like the exported picture
                // (What's below is only recorded for the screen, so the area stops at the screen's edges)
                val r = RectF(0f, 0f, ctx.docW, ctx.docH).also { view.mapRect(it) }
                val area = android.graphics.Rect(
                    kotlin.math.floor(r.left).toInt().coerceAtLeast(0), kotlin.math.floor(r.top).toInt().coerceAtLeast(0),
                    kotlin.math.ceil(r.right).toInt().coerceAtMost(w), kotlin.math.ceil(r.bottom).toInt().coerceAtMost(h),
                )
                RenderNode("adjusted").apply {
                    if (area.isEmpty) area.set(0, 0, 1, 1)
                    setPosition(area)
                    val blur = Gpu.blurEffect(blurR, Shader.TileMode.CLAMP)
                    setRenderEffect(if (fx.copy(blur = 0f).isIdentity) blur else RenderEffect.createChainEffect(colorFx(), blur))
                    val c = beginRecording(area.width(), area.height())
                    c.translate(-area.left.toFloat(), -area.top.toFloat())
                    c.drawRenderNode(below)
                    endRecording()
                }
            } else {
                node("adjusted").apply {
                    // Sharpen/grain spacing follows the zoom, like the full-size result
                    setRenderEffect(colorFx())
                    beginRecording(w, h).drawRenderNode(below)
                    endRecording()
                }
            }
            val next = node("next")
            val nr = next.beginRecording(w, h)
            nr.drawRenderNode(below)
            val op = (l.props.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            val mx = l.x + (ctx.preview?.of(l.id)?.dragX ?: 0f)
            val my = l.y + (ctx.preview?.of(l.id)?.dragY ?: 0f)
            fun adjustedWithMask() {
                nr.drawRenderNode(adjusted)
                nr.save()
                nr.concat(view)
                if (fx.vignette > 0f) {
                    nr.save()
                    nr.clipRect(0f, 0f, ctx.docW, ctx.docH)
                    Gpu.drawVignette(nr, fx.vignette, 0f, 0f, ctx.docW, ctx.docH)
                    nr.restore()
                }
                drawMask(nr, l, ctx, mx, my)
                nr.restore()
            }
            if (l.props.blend == Blend.NORMAL) {
                // below × (1 − w) + adjusted × w, where w is the opacity through the mask
                nr.saveLayer(null, Paint().apply {
                    alpha = op
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                })
                nr.drawColor(Color.BLACK)
                nr.save()
                nr.concat(view)
                drawMask(nr, l, ctx, mx, my)
                nr.restore()
                nr.restore()
                nr.saveLayer(null, Paint().apply {
                    alpha = op
                    blendMode = android.graphics.BlendMode.PLUS
                })
                adjustedWithMask()
                nr.restore()
            } else {
                nr.saveLayer(null, Paint().apply {
                    alpha = op
                    blendMode = l.props.blend.mode
                })
                adjustedWithMask()
                nr.restore()
            }
            nr.concat(view)
            below = next
            rec = nr
        }
        below.endRecording()
        val inverse = Matrix()
        view.invert(inverse)
        canvas.save()
        canvas.concat(inverse)
        canvas.drawRenderNode(below)
        canvas.restore()
    }

    /**
     * Off screen (export, merge, thumbnails): the list is drawn into a picture, and each adjustment is
     * applied to that picture on the GPU.
     */
    private fun drawListPicture(canvas: Canvas, list: List<Layer>, ctx: Ctx) {
        val b = ctx.bounds
        val area = android.graphics.Rect(
            kotlin.math.floor(b.left).toInt(), kotlin.math.floor(b.top).toInt(),
            kotlin.math.ceil(b.right).toInt(), kotlin.math.ceil(b.bottom).toInt(),
        )
        val s = ctx.pixelScale
        val pw = (area.width() * s).roundToInt().coerceAtLeast(1)
        val ph = (area.height() * s).roundToInt().coerceAtLeast(1)
        var pic = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        var pc = Canvas(pic)
        fun prepare(c: Canvas) {
            c.scale(pw / area.width().toFloat(), ph / area.height().toFloat())
            c.translate(-area.left.toFloat(), -area.top.toFloat())
        }
        prepare(pc)
        for (l in list) {
            if (customBlend(l, ctx)) {
                // The layer drawn alone the same size, then the two mixed by the blend shader
                val alone = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                val ac = Canvas(alone)
                prepare(ac)
                drawNode(ac, l, ctx, forceNormal = true)
                val mixed = BlendShader.mix(pic, alone, l.props.blend, area.width() / pw.toFloat(), area.height() / ph.toFloat(), area.left.toFloat(), area.top.toFloat())
                alone.recycle()
                pic.recycle()
                pic = mixed
                pc = Canvas(pic)
                prepare(pc)
                continue
            }
            val fx = l.props.adjust
            if (!(l.isAdjustment && fx != null)) {
                drawNode(pc, l, ctx)
                continue
            }
            val adjusted = Gpu.applyFx(pic, fx, area.left * s, area.top * s, ctx.docW * s, ctx.docH * s, pixelScale = s, clampEdges = true)
            val next = pic.copy(Bitmap.Config.ARGB_8888, true)
            pic.recycle()
            pic = next
            pc = Canvas(pic)
            prepare(pc)
            val op = (l.props.opacity.coerceIn(0f, 1f) * 255).roundToInt()
            val normal = l.props.blend == Blend.NORMAL
            if (normal) {
                // below × (1 − w) + adjusted × w, where w is the opacity through the mask
                pc.saveLayer(RectF(area), Paint().apply {
                    alpha = op
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                })
                pc.drawColor(Color.BLACK)
                drawMask(pc, l, ctx, l.x.toFloat(), l.y.toFloat())
                pc.restore()
            }
            pc.saveLayer(RectF(area), Paint().apply {
                alpha = op
                blendMode = if (normal) android.graphics.BlendMode.PLUS else l.props.blend.mode
            })
            pc.drawBitmap(adjusted, null, RectF(area), Paint().apply { isFilterBitmap = true })
            drawMask(pc, l, ctx, l.x.toFloat(), l.y.toFloat())
            pc.restore()
            adjusted.recycle()
        }
        canvas.drawBitmap(pic, null, RectF(area), Paint().apply { isFilterBitmap = true })
        pic.recycle()
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
        blendNormal: Boolean = false,
        fullOpacity: Boolean = false,
        /** Draws the pixels being styled instead of the layer's own (a folder's contents). */
        content: ((Canvas) -> Unit)? = null,
        /** Where the effects sit, when [content] is given. */
        offset: Pair<Float, Float>? = null,
    ) {
        val p = layer.props.let { if (blendNormal) it.copy(blend = Blend.NORMAL) else it }.let { if (fullOpacity) it.copy(opacity = 1f) else it }
        val transforming = content == null && (preview?.transform != null || preview?.mesh != null)
        val style = p.style
        val built = if (style.needsBuild && !transforming) effects?.invoke(layer) else null
        val overlay = style.colorOverlay.takeIf { it.on && !transforming }
        val dx = offset?.first ?: (layer.x + (preview?.dragX ?: 0f))
        val dy = offset?.second ?: (layer.y + (preview?.dragY ?: 0f))
        fun drawContent(canvas: Canvas, docW: Float, docH: Float, layer: Layer, bounds: RectF, tilePaint: Paint, stroke: StrokeSession?, preview: LayerPreview?, smooth: Boolean, dx: Float, dy: Float) {
            if (content != null) content(canvas) else this.drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
        }

        val pattern = style.patternOverlay.takeIf { it.on && !transforming && it.opacity > 0f }
        if (built == null && overlay == null && pattern == null && !(style.gradientFill.on && !transforming)) {
            // No effects: the layer's opacity and fill simply multiply
            val alpha = (p.opacity * p.fill).coerceIn(0f, 1f)
            if (alpha <= 0f) return
            val mask = if (layer.maskActive && !transforming) layer.mask else null
            val plain = content == null && p.blend == Blend.NORMAL && alpha >= 1f && mask == null && stroke == null && preview?.fx == null && preview?.gradient == null && !transforming
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
        val op = p.opacity.coerceIn(0f, 1f)
        if (built != null) {
            // Effects behind the layer with a blend mode of their own blend with what's below it
            if (style.longShadow.on && style.longShadow.blend != Blend.NORMAL) Effects.drawImage(canvas, built, built.longShadow, dx, dy, style.longShadow.color, style.longShadow.opacity * op, style.longShadow.blend.mode)
            if (style.dropShadow.on && style.dropShadow.blend != Blend.NORMAL) Effects.drawImage(canvas, built, built.dropShadow, dx, dy, style.dropShadow.color, style.dropShadow.opacity * op, style.dropShadow.blend.mode)
            if (style.outerGlow.on && style.outerGlow.blend != Blend.NORMAL) Effects.drawImage(canvas, built, built.outerGlow, dx, dy, style.outerGlow.color, style.outerGlow.opacity * op, style.outerGlow.blend.mode)
        }
        canvas.saveLayer(bounds, Paint().apply {
            alpha = (op * 255).roundToInt()
            blendMode = p.blend.mode
        })
        if (built != null) {
            if (style.longShadow.on && style.longShadow.blend == Blend.NORMAL) Effects.drawImage(canvas, built, built.longShadow, dx, dy, style.longShadow.color, style.longShadow.opacity)
            if (style.dropShadow.on && style.dropShadow.blend == Blend.NORMAL) Effects.drawImage(canvas, built, built.dropShadow, dx, dy, style.dropShadow.color, style.dropShadow.opacity)
            if (style.outerGlow.on && style.outerGlow.blend == Blend.NORMAL) Effects.drawImage(canvas, built, built.outerGlow, dx, dy, style.outerGlow.color, style.outerGlow.opacity)
        }
        // Fill fades the pixels only
        if (p.fill > 0f) {
            canvas.saveLayer(bounds, Paint().apply { alpha = (p.fill.coerceIn(0f, 1f) * 255).roundToInt() })
            drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
            canvas.restore()
        }
        if (pattern != null) {
            // The layer's shape, filled with the pattern (it moves with the layer)
            canvas.saveLayer(bounds, Paint().apply {
                alpha = (pattern.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                blendMode = pattern.blend.mode
            })
            drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
            Effects.drawPatternOverlay(canvas, pattern, dx, dy)
            canvas.restore()
        }
        val gradient = style.gradientFill.takeIf { it.on && !transforming && it.opacity > 0f }
        if (gradient != null) {
            // The layer's shape, filled with the gradient across where its pixels are
            val area = (built?.content ?: layer.tiles.tileBounds()?.let { RectF(it) })?.let { RectF(it).apply { offset(dx, dy) } }
            if (area != null) {
                canvas.saveLayer(bounds, Paint().apply {
                    alpha = (gradient.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                    blendMode = gradient.blend.mode
                })
                drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
                Effects.drawGradientFill(canvas, gradient, area)
                canvas.restore()
            }
        }
        if (overlay != null && overlay.opacity > 0f) {
            // The layer's shape, colored (at full strength, whatever the fill)
            canvas.saveLayer(bounds, Paint().apply {
                alpha = (overlay.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                blendMode = overlay.blend.mode
            })
            drawContent(canvas, docW, docH, layer, bounds, tilePaint, stroke, preview, smooth, dx, dy)
            canvas.drawPaint(Paint().apply {
                color = overlay.color or 0xFF000000.toInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            })
            canvas.restore()
        }
        if (built != null && style.satin.on) {
            Effects.drawImage(canvas, built, built.satin, dx, dy, style.satin.color, style.satin.opacity, style.satin.blend.mode)
        }
        if (built != null && style.innerGlow.on) {
            Effects.drawImage(canvas, built, built.innerGlow, dx, dy, style.innerGlow.color, style.innerGlow.opacity, style.innerGlow.blend.mode)
        }
        if (built != null && style.innerShadow.on) {
            // Already limited to the layer's shape
            Effects.drawImage(canvas, built, built.innerShadow, dx, dy, style.innerShadow.color, style.innerShadow.opacity, style.innerShadow.blend.mode)
        }
        if (built != null && style.bevel.on) {
            // Highlights lighten, shadows darken (Screen and Multiply unless changed, like Photoshop)
            Effects.drawImage(canvas, built, built.bevelLight, dx, dy, style.bevel.highlight, style.bevel.highlightOpacity, style.bevel.highlightBlend.mode)
            Effects.drawImage(canvas, built, built.bevelShade, dx, dy, style.bevel.shadow, style.bevel.shadowOpacity, style.bevel.shadowBlend.mode)
        }
        if (built != null && style.stroke.on) {
            val area = (built.content ?: layer.tiles.tileBounds()?.let { RectF(it) })?.let { RectF(it).apply { offset(dx, dy) } }
            Effects.drawStroke(canvas, built, style.stroke, dx, dy, area)
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
        preview?.mesh?.let { m ->
            // Being warped: the proxy already has the mask applied (points are canvas pixels)
            canvas.save()
            canvas.translate(dx - layer.x, dy - layer.y)
            WarpMath.draw(canvas, m.proxy, m.cols, m.rows, m.pts)
            canvas.restore()
            return
        }
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
            val sel = preview.selection
            if (sel == null) {
                preview.drawFx(canvas, layer, docW, docH)
            } else {
                // Changed inside the selection, unchanged outside
                layer.tiles.drawTo(canvas, dx, dy, tilePaint, RectF(bounds))
                // original × (1 − selected) + adjusted × selected
                canvas.saveLayer(bounds, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                sel.mask().drawTo(canvas, 0f, 0f, Paint().apply { isFilterBitmap = true }, RectF(bounds))
                canvas.restore()
                canvas.saveLayer(bounds, Paint().apply { blendMode = android.graphics.BlendMode.PLUS })
                preview.drawFx(canvas, layer, docW, docH)
                sel.keepInside(canvas, 0f, 0f, bounds)
                canvas.restore()
            }
        } else {
            layer.tiles.drawTo(canvas, dx, dy, tilePaint, RectF(bounds))
        }
        preview?.gradient?.takeIf { !it.toMask }?.let { g ->
            val area = RectF(0f, 0f, docW, docH).apply { intersect(bounds) }
            GradientPainter.paintPixels(canvas, area, g.spec, g.start, g.end, g.selection, 0f, 0f, layer.props.lockAlpha)
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
            preview?.gradient?.takeIf { it.toMask }?.let { g ->
                val area = RectF(0f, 0f, docW, docH).apply { intersect(bounds) }
                GradientPainter.paintMask(canvas, area, g.spec, g.start, g.end, g.selection, 0f, 0f)
            }
            canvas.restore()
        }
    }

    /**
     * Draws [layers] (with their masks, opacity and blend modes) over a transparent background,
     * covering canvas area [region] — which may reach past the canvas edges.
     */
    fun renderLayers(layers: List<Layer>, region: android.graphics.Rect, docW: Int, docH: Int, effects: ((Layer) -> BuiltEffects?)? = null, doc: Doc? = null): Bitmap {
        val bmp = Bitmap.createBitmap(region.width().coerceAtLeast(1), region.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        val c = Canvas(bmp)
        c.translate(-region.left.toFloat(), -region.top.toFloat())
        val ctx = Ctx(doc, docW.toFloat(), docH.toFloat(), RectF(region), Paint(), null, null, false, effects, null, 0, 0, 1f)
        drawList(c, layers, ctx)
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
        val s0 = min(1f, maxPx.toFloat() / max(doc.width, doc.height))
        val s = min(1f, maxPx.toFloat() / max(doc.width, doc.height))
        val w = (doc.width * s).roundToInt().coerceAtLeast(1)
        val h = (doc.height * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (Color.alpha(background) > 0) c.drawColor(background)
        c.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        draw(c, doc, RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat()), effects = effects, pixelScale = s0)
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

    /**
     * Small picture of tiles (a layer's pixels, or with [mask] its mask: white shows, black hides)
     * over the canvas area. Works from copies of the tile maps, so it can run in the background.
     */
    fun gridThumb(tiles: Map<Long, Bitmap>, mask: Boolean, x: Int, y: Int, docW: Int, docH: Int, maxPx: Int): Bitmap {
        val s = maxPx.toFloat() / max(docW, docH)
        val w = (docW * s).roundToInt().coerceAtLeast(1)
        val h = (docH * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (mask) c.drawColor(Color.WHITE)
        c.scale(w / docW.toFloat(), h / docH.toFloat())
        c.clipRect(0f, 0f, docW.toFloat(), docH.toFloat())
        val grid = TileGrid(alphaOnly = mask).also { it.tiles.putAll(tiles) }
        grid.drawTo(c, x.toFloat(), y.toFloat(), Paint().apply { isFilterBitmap = true; if (mask) color = Color.BLACK }, RectF(0f, 0f, docW.toFloat(), docH.toFloat()))
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
        // Bottom first, so a folder's layers have their effects before the folder is drawn for its own
        val order = if (only === doc.layers) doc.layers else doc.layers.filter { it in only }
        for (l in order) {
            if (!l.canHaveStyle || !l.props.visible || !l.props.style.needsBuild) continue
            out.putAll(listOfNotNull(buildEffects(doc, l, maxSide) { out[it.id] }?.let { l.id to it }))
        }
        return out
    }

    /**
     * [l]'s effect images at most [maxSide] across, or null when there are none (or they can't be
     * made: the layer is then drawn without them). A folder's are made from everything in it drawn
     * together ([effects]: its layers' own effects).
     */
    fun buildEffects(doc: Doc, l: Layer, maxSide: Int, key: Any = Unit, effects: ((Layer) -> BuiltEffects?)? = null): BuiltEffects? {
        val mask = if (l.maskActive) l.mask?.tiles else null
        return runCatching {
            val tiles = if (l.isGroup) groupTiles(doc, l, effects) else l.tiles.tiles
            Effects.build(key, l.props.style, tiles, mask, maxSide, System.identityHashCode(l.tiles))
        }.getOrNull()
    }

    /**
     * A folder's layers drawn together (without the folder's own mask, opacity or effects), as
     * tiles at the folder's position: what its effects are made from.
     */
    fun groupTiles(doc: Doc, group: Layer, effects: ((Layer) -> BuiltEffects?)?): Map<Long, Bitmap> {
        val inside = doc.layers.slice(doc.block(group)).filter { it !== group && it.props.visible }
        var region: android.graphics.Rect? = null
        for (l in inside) {
            val b = l.bounds() ?: continue
            if (l.props.style.any) {
                val r = kotlin.math.ceil(l.props.style.reach).toInt() + 2
                b.inset(-r, -r)
            }
            if (region == null) region = android.graphics.Rect(b) else region.union(b)
        }
        // Adjustment layers only change what's there; nothing reaches further than the canvas and as much again around it
        val r = region ?: return emptyMap()
        if (!r.intersect(-doc.width, -doc.height, 2 * doc.width, 2 * doc.height)) return emptyMap()
        val bmp = renderLayers(doc.children(group.id), r, doc.width, doc.height, effects, doc)
        val grid = TileGrid.fromBitmap(bmp, r.left - group.x, r.top - group.y, alphaOnly = false)
        bmp.recycle()
        return grid.tiles
    }
}
