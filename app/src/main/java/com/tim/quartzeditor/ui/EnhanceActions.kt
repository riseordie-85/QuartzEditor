package com.tim.quartzeditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.tim.quartzeditor.engine.Blend
import com.tim.quartzeditor.engine.Depth
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.EnhanceArt
import com.tim.quartzeditor.engine.FxKind
import com.tim.quartzeditor.engine.FxParams
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerPreview
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.engine.LightSettings
import com.tim.quartzeditor.engine.Models
import com.tim.quartzeditor.engine.SkyPreset
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.Upscaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Enhance: Depth blur, Depth fog, Relight and Replace sky (from the on-device depth model, each
// making a new layer above the photo you can mask or fade), Denoise and Sharpen (on the layer's
// own pixels), Auto enhance (an adjustment layer) and Upscale (the whole picture, bigger).

enum class EnhanceKind(val label: String, val depth: Boolean, val newLayer: Boolean) {
    DEPTH_BLUR("Depth blur", true, true),
    FOG("Depth fog", true, true),
    RELIGHT("Relight", true, true),
    SKY("Replace sky", true, true),
    DENOISE("Denoise", false, false),
    SHARPEN("Sharpen", false, false),
}

/** What an Enhance effect is set to (one set for all of them; each uses its own part). */
data class EnhanceParams(
    // Depth blur
    val focus: Float = 0.8f,
    val focusPoint: android.graphics.PointF? = null,
    val range: Float = 0.12f,
    val blur: Float = 18f,
    // Depth fog
    val fogColor: Int = 0xFFE8ECF0.toInt(),
    val fogStart: Float = 0.35f,
    val fogSoftness: Float = 0.45f,
    val fogDensity: Float = 0.75f,
    // Relight
    val light: LightSettings? = null,
    val relief: Float = 0.6f,
    val lightBlend: Blend = Blend.SOFT_LIGHT,
    // Replace sky
    val sky: SkyPreset = SkyPreset.CLEAR,
    val skyPhoto: Uri? = null,
    val skyEdge: Float = 0.12f,
    val clouds: Float = 0.4f,
    val skySoft: Float = 2f,
    // Denoise, Sharpen
    val denoise: Float = 0.5f,
    val colorNoise: Float = 0.6f,
    val sharpen: Float = 0.8f,
    val sharpenRadius: Float = 1.5f,
    val sharpenThreshold: Float = 0.01f,
)

/**
 * An Enhance effect being set up on layer [layerId]: [proxy] is the layer (area [area] of the
 * canvas) at [k] of full size for the live preview; [depth] its depth (0 far … 1 near) at that
 * size. New-layer effects preview on [tempId], a layer put in just for the preview.
 */
class EnhanceSession(
    /** The picture it's on (a session never touches another one). */
    val doc: com.tim.quartzeditor.engine.Doc,
    val kind: EnhanceKind,
    val layerId: Long,
    val area: Rect,
    val k: Float,
    val proxy: Bitmap,
    val depth: FloatArray?,
    val tempId: Long?,
    /** What was selected when it started: only that part changes. */
    val selection: com.tim.quartzeditor.engine.Selection?,
) {
    /** How selected each spot of [area] is (full size; made once). */
    val selBitmap: Bitmap? by lazy { selection?.bitmapFor(area) }
    val pw: Int get() = proxy.width
    val ph: Int get() = proxy.height
    val depthBmp: Bitmap? by lazy { depth?.let { EnhanceArt.depthBitmap(it, pw, ph) } }
    var normalsFor: Float = -1f
    var normals: Bitmap? = null
    var skyFor: Float = -1f
    var sky: FloatArray? = null
    var skyPhoto: Pair<Uri, Bitmap>? = null
}

/** The size the preview works at (long side). */
private const val PROXY_SIDE = 1400

/** Starts an Enhance effect on the active layer: the panel opens, the preview shows on the picture. */
fun EditorViewModel.startEnhance(kind: EnhanceKind) {
    val d = doc ?: return
    if (pendingFirst { startEnhance(kind) }) return
    val l = d.active
    if (l == null || !l.hasContent) {
        toast("Pick a photo layer first (${kind.label} works on a layer's pixels)")
        return
    }
    if (!kind.newLayer && l.needsRaster) {
        askRasterize(l) { startEnhance(kind) }
        return
    }
    if (!kind.newLayer && l.props.locked) {
        toast("\"${l.props.name}\" is locked")
        return
    }
    if (kind.depth && !Models.has(ctx, Models.DEPTH)) {
        toast("This build doesn't include the depth model. Build the app with internet on once so Android Studio can download it.")
        return
    }
    val area = l.bounds()?.takeIf { it.intersect(0, 0, d.width, d.height) }
    if (area == null || area.width() < 8 || area.height() < 8) {
        toast("\"${l.props.name}\" has nothing on the canvas")
        return
    }
    val alone = aloneCopy(l, withMask = true)
    runBusy(if (kind.depth) "Looking at the depth…" else "Getting ready…") {
        val (proxy, k, depth) = withContext(Dispatchers.Default) {
            val full = DocRenderer.renderLayers(listOf(alone), area, d.width, d.height, doc = d)
            val k = min(1f, PROXY_SIDE.toFloat() / max(area.width(), area.height()))
            val proxy = if (k < 1f) Bitmap.createScaledBitmap(full, (area.width() * k).roundToInt().coerceAtLeast(1), (area.height() * k).roundToInt().coerceAtLeast(1), true).also { full.recycle() } else full
            val depth = if (kind.depth) Depth.estimate(ctx, proxy) else null
            Triple(proxy, k, depth)
        }
        if (doc !== d || d.layer(l.id) == null) return@runBusy
        // New-layer effects preview on a layer of their own, right above the photo
        val tempId = if (kind.newLayer) {
            val t = Layer(d.newId(), effectProps(kind, l, enhanceParams), TileGrid(alphaOnly = false))
            d.layers.add(d.indexOf(l.id) + 1, t)
            d.previewLayerId = t.id
            t.id
        } else null
        val p0 = enhanceParams
        // A light starts up and to the left of the middle; focus starts on what's nearest
        enhanceParams = when (kind) {
            EnhanceKind.RELIGHT -> p0.copy(light = (p0.light ?: LightSettings(0f, 0f)).copy(x = area.left + area.width() * 0.3f, y = area.top + area.height() * 0.25f))
            EnhanceKind.DEPTH_BLUR -> p0.copy(focus = depth?.let { nearest(it) } ?: p0.focus, focusPoint = null)
            else -> p0
        }
        enhance = EnhanceSession(d, kind, l.id, area, k, proxy, depth, tempId, d.selection?.takeIf { !it.isEmpty })
        openPopup(PopupKind.ENHANCE)
        refreshEnhance()
        if (kind == EnhanceKind.DEPTH_BLUR) toast("Tap what should stay sharp")
        if (kind == EnhanceKind.RELIGHT) toast("Drag on the picture to move the light")
        if (kind == EnhanceKind.FOG) toast("Tap where the fog should start")
    }
}

/** How near the nearest big part of the picture is (where focus starts). */
private fun nearest(depth: FloatArray): Float {
    val s = depth.copyOf().also { it.sort() }
    return s[(s.size * 0.9f).toInt().coerceIn(0, s.lastIndex)]
}

/** [l] drawn on its own: normal blending, full opacity, no effects. */
private fun aloneCopy(l: Layer, withMask: Boolean): Layer = Layer(
    l.id, l.props.copy(opacity = 1f, fill = 1f, blend = Blend.NORMAL, clip = false, visible = true, style = LayerStyle(), parent = 0L, blendIf = com.tim.quartzeditor.engine.BlendIf()),
    l.tiles, l.x, l.y, if (withMask) l.mask else null, LayerKind.PIXELS,
)

/** The settings of a new effect layer above [source]. */
private fun effectProps(kind: EnhanceKind, source: Layer, p: EnhanceParams): LayerProps {
    // Clipped, so it only covers the photo (above a clipped layer it clips to the same one)
    val clip = true
    return when (kind) {
        EnhanceKind.DEPTH_BLUR -> LayerProps("Depth blur", parent = source.props.parent, clip = clip)
        EnhanceKind.FOG -> LayerProps("Depth fog", parent = source.props.parent, clip = clip)
        EnhanceKind.RELIGHT -> LayerProps("Light", parent = source.props.parent, clip = clip, blend = p.lightBlend)
        EnhanceKind.SKY -> LayerProps("Sky", parent = source.props.parent, clip = clip)
        else -> LayerProps(kind.label, parent = source.props.parent)
    }
}

/** A change to the settings: the preview follows (worked out in the background, the latest wins). */
fun EditorViewModel.setEnhance(change: (EnhanceParams) -> EnhanceParams) {
    enhanceParams = change(enhanceParams)
    refreshEnhance()
}

private var previewJob: Job? = null
private var previewAgain = false

/** Works the preview out again (at the preview's size), then shows it. */
internal fun EditorViewModel.refreshEnhance() {
    val s = enhance ?: return
    val d = doc ?: return
    // Blend mode of the light layer follows its setting
    s.tempId?.let { id -> d.layer(id)?.let { t -> if (s.kind == EnhanceKind.RELIGHT && t.props.blend != enhanceParams.lightBlend) t.props = t.props.copy(blend = enhanceParams.lightBlend) } }
    if (previewJob?.isActive == true) {
        previewAgain = true
        return
    }
    previewJob = viewModelScope.launch {
        do {
            previewAgain = false
            val params = enhanceParams
            val bmp = try {
                withContext(Dispatchers.Default) { render(s, params, full = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("QuartzEnhance", "Preview failed", e)
                toast(e.message ?: "Couldn't show that")
                null
            }
            if (enhance !== s || bmp == null) break
            val m = Matrix().apply {
                setScale(s.area.width() / bmp.width.toFloat(), s.area.height() / bmp.height.toFloat())
                postTranslate(s.area.left.toFloat(), s.area.top.toFloat())
            }
            // Drawn in place of the preview layer (or of the photo, for Denoise and Sharpen)
            preview = LayerPreview(s.tempId ?: s.layerId, proxy = bmp, transform = m)
            doc?.invalidate()
            // A moment between previews while a slider moves
            delay(16)
        } while (previewAgain && enhance === s)
    }
}

/**
 * The effect's picture: at the preview's size, or [full] (the photo at full size, cropped to the
 * session's area) when applying. For new-layer effects the mask is baked in (as transparency).
 */
private fun EditorViewModel.render(s: EnhanceSession, p: EnhanceParams, full: Bitmap?): Bitmap {
    val (pixels, hidden) = renderParts(s, p, full)
    if (hidden == null) return pixels
    // The mask as transparency (preview only)
    Canvas(pixels).drawBitmap(hidden, null, Rect(0, 0, pixels.width, pixels.height), Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    })
    hidden.recycle()
    return pixels
}

/** The effect's pixels and (for masked effects) its "how hidden" mask, at the size of [full] or the preview. */
private fun EditorViewModel.renderParts(s: EnhanceSession, p: EnhanceParams, full: Bitmap?): Pair<Bitmap, Bitmap?> {
    val parts = renderRaw(s, p, full)
    val sel = s.selBitmap ?: return parts
    val (pixels, hidden) = parts
    val w = pixels.width
    val h = pixels.height
    val dst = Rect(0, 0, w, h)
    val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    fun mode(m: PorterDuff.Mode) = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(m) }
    if (s.kind.newLayer) {
        // The new layer's mask also hides what isn't selected
        val notSel = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(notSel).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(sel, null, dst, mode(PorterDuff.Mode.DST_OUT))
        }
        if (hidden == null) return pixels to notSel
        Canvas(hidden).drawBitmap(notSel, 0f, 0f, filter)
        notSel.recycle()
        return pixels to hidden
    }
    // Denoise, Sharpen: changed where selected, as it was elsewhere
    val src = full ?: s.proxy
    Canvas(pixels).drawBitmap(sel, null, dst, mode(PorterDuff.Mode.DST_IN))
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    Canvas(out).apply {
        drawBitmap(sel, null, dst, mode(PorterDuff.Mode.DST_OUT))
        drawBitmap(pixels, 0f, 0f, mode(PorterDuff.Mode.ADD))
    }
    pixels.recycle()
    return out to hidden
}

private fun EditorViewModel.renderRaw(s: EnhanceSession, p: EnhanceParams, full: Bitmap?): Pair<Bitmap, Bitmap?> {
    val src = full ?: s.proxy
    val w = src.width
    val h = src.height
    // Pixel distances are full-size pixels: scaled for the preview
    val scale = w / s.area.width().toFloat()
    return when (s.kind) {
        EnhanceKind.DEPTH_BLUR -> {
            val db = s.depthBmp ?: throw IllegalStateException("No depth")
            EnhanceArt.depthBlur(src, db, p.focus, p.range, p.blur * scale)
        }
        EnhanceKind.FOG -> {
            val db = s.depthBmp ?: throw IllegalStateException("No depth")
            val color = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(p.fogColor or 0xFF000000.toInt()) }
            color to EnhanceArt.fogMask(db, w, h, p.fogStart, p.fogSoftness, p.fogDensity)
        }
        EnhanceKind.RELIGHT -> {
            val dp = s.depth ?: throw IllegalStateException("No depth")
            if (s.normalsFor != p.relief || s.normals == null) synchronized(s) {
                s.normals = EnhanceArt.normals(dp, s.pw, s.ph, p.relief * 0.5f)
                s.normalsFor = p.relief
            }
            val l = p.light ?: LightSettings(s.area.exactCenterX(), s.area.exactCenterY())
            EnhanceArt.light(s.normals!!, w, h, l, s.area.left.toFloat(), s.area.top.toFloat(), scale, max(s.area.width(), s.area.height()).toFloat()) to null
        }
        EnhanceKind.SKY -> {
            val dp = s.depth ?: throw IllegalStateException("No depth")
            if (s.skyFor != p.skyEdge || s.sky == null) synchronized(s) {
                s.sky = EnhanceArt.skyMask(s.proxy, dp, s.pw, s.ph, p.skyEdge)
                s.skyFor = p.skyEdge
            }
            val mask = s.sky ?: throw IllegalStateException("No sky found in this photo. Try a higher Sky edge.")
            val bottom = EnhanceArt.skyBottom(mask, s.pw, s.ph)
            val image = skyPicture(s, p, w, h, bottom)
            // Hidden where there's no sky, softened at the edge
            val m = Bitmap.createBitmap(s.pw, s.ph, Bitmap.Config.ALPHA_8)
            m.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(ByteArray(s.pw * s.ph) { ((1f - mask[it]) * 255f).roundToInt().toByte() }))
            val big = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(big).drawBitmap(m, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            m.recycle()
            val soft = p.skySoft * scale
            val hidden = if (soft >= 0.5f) {
                val argb = big.copy(Bitmap.Config.ARGB_8888, false)
                big.recycle()
                com.tim.quartzeditor.engine.Gpu.blurred(argb, soft, android.graphics.Shader.TileMode.CLAMP).also { argb.recycle() }.let { b ->
                    b.copy(Bitmap.Config.ALPHA_8, true).also { b.recycle() }
                }
            } else big
            image to hidden
        }
        EnhanceKind.DENOISE -> EnhanceArt.denoise(src, p.denoise, p.colorNoise, scale) to null
        EnhanceKind.SHARPEN -> EnhanceArt.sharpen(src, p.sharpen, (p.sharpenRadius * scale).coerceAtLeast(0.3f), p.sharpenThreshold) to null
    }
}

/** The new sky at [w]×[h]: a ready-made one, or your photo (covering the sky's area). */
private fun EditorViewModel.skyPicture(s: EnhanceSession, p: EnhanceParams, w: Int, h: Int, bottom: Float): Bitmap {
    val uri = p.skyPhoto ?: return EnhanceArt.skyImage(p.sky, w, h, bottom, p.clouds, (s.layerId % 97).toFloat())
    val photo = s.skyPhoto?.takeIf { it.first == uri }?.second ?: com.tim.quartzeditor.data.Media.decodeUpright(ctx, uri, 3000).also { s.skyPhoto = uri to it }
    // Covers the width and the sky's height, from the top
    val skyH = max(1f, bottom * h)
    val k = max(w / photo.width.toFloat(), skyH / photo.height.toFloat())
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val dw = photo.width * k
    c.drawBitmap(photo, null, android.graphics.RectF((w - dw) / 2f, 0f, (w + dw) / 2f, photo.height * k), Paint(Paint.FILTER_BITMAP_FLAG))
    // Below the photo (if the sky reaches lower): its bottom row stretched down
    if (photo.height * k < h) {
        c.drawBitmap(photo, Rect(0, photo.height - 1, photo.width, photo.height), android.graphics.RectF((w - dw) / 2f, photo.height * k, (w + dw) / 2f, h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return out
}

/** A finger on the picture while an effect is being set up (canvas pixels). */
fun EditorViewModel.enhanceTouch(x: Float, y: Float) {
    val s = enhance ?: return
    val lx = ((x - s.area.left) * s.k).roundToInt().coerceIn(0, s.pw - 1)
    val ly = ((y - s.area.top) * s.k).roundToInt().coerceIn(0, s.ph - 1)
    val d = s.depth?.get(ly * s.pw + lx)
    when (s.kind) {
        EnhanceKind.DEPTH_BLUR -> if (d != null) setEnhance { it.copy(focus = d, focusPoint = android.graphics.PointF(x, y)) }
        EnhanceKind.FOG -> if (d != null) setEnhance { it.copy(fogStart = (1f - d - 0.05f).coerceIn(0f, 0.95f)) }
        EnhanceKind.RELIGHT -> setEnhance { it.copy(light = (it.light ?: LightSettings(x, y)).copy(x = x, y = y)) }
        else -> Unit
    }
}

/** Applies the effect at full size: a new layer above the photo, or the photo's own pixels changed. */
fun EditorViewModel.applyEnhance(onDone: (() -> Unit)? = null) {
    val s = enhance ?: return
    val d = doc ?: return
    val l = d.layer(s.layerId)
    if (l == null) {
        cancelEnhance()
        return
    }
    val params = enhanceParams
    val alone = aloneCopy(l, withMask = s.kind.newLayer)
    val source = l.tiles
    runBusy("Applying ${s.kind.label.lowercase()}…", onDone = onDone) {
        val result = withContext(Dispatchers.Default) {
            val full = DocRenderer.renderLayers(listOf(alone), s.area, d.width, d.height, doc = d)
            try {
                renderParts(s, params, full)
            } finally {
                full.recycle()
            }
        }
        if (enhance !== s || doc !== d) return@runBusy
        val (pixels, hidden) = result
        // The preview layer goes; the real one (or the change) is one undo step
        endEnhance()
        if (s.kind.newLayer) {
            val grid = withContext(Dispatchers.Default) { TileGrid.fromBitmap(pixels, s.area.left, s.area.top, alphaOnly = false).also { it.dropEmptyTiles() } }
            // (A mask to paint on even when nothing is hidden yet: Relight)
            val mask = hidden?.let { hb -> withContext(Dispatchers.Default) { TileGrid.fromBitmap(hb, s.area.left, s.area.top, alphaOnly = true) } } ?: TileGrid(alphaOnly = true)
            pixels.recycle()
            hidden?.recycle()
            stackEdit(s.kind.label) {
                val at = d.indexOf(l.id) + 1
                val nl = Layer(d.newId(), effectProps(s.kind, l, params), grid, mask = mask)
                d.layers.add(at.coerceIn(0, d.layers.size), nl)
                d.activeId = nl.id
                d.editMask = false
            }
            toast(
                when (s.kind) {
                    EnhanceKind.RELIGHT -> "Light added as a layer: fade it with Opacity, or try Overlay for more"
                    else -> "${s.kind.label} added as a layer with a mask: paint the mask to touch it up"
                }
            )
        } else {
            if (l.tiles !== source) return@runBusy
            val grid = withContext(Dispatchers.Default) { pasteInto(l, pixels, s.area) }
            pixels.recycle()
            stackEdit(s.kind.label) { l.tiles = grid }
        }
    }
}

/** [l]'s tiles with [pixels] (canvas area [area]) put in their place; the rest kept. */
private fun pasteInto(l: Layer, pixels: Bitmap, area: Rect): TileGrid {
    val g = TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }
    val local = android.graphics.RectF(area).apply { offset(-l.x.toFloat(), -l.y.toFloat()) }
    val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    for (k in g.keysIn(local)) {
        val tile = g.editableCopy(g.tiles[k])
        val c = Canvas(tile)
        val ox = (TileGrid.tx(k) * TileGrid.SIZE).toFloat()
        val oy = (TileGrid.ty(k) * TileGrid.SIZE).toFloat()
        c.drawBitmap(pixels, local.left - ox, local.top - oy, src)
        g.tiles[k] = tile
    }
    g.dropEmptyTiles()
    return g
}

/** Drops the effect being set up. */
fun EditorViewModel.cancelEnhance() {
    if (enhance == null) return
    endEnhance()
    doc?.invalidate()
}

private fun EditorViewModel.endEnhance() {
    val s = enhance ?: return
    enhance = null
    previewJob?.cancel()
    preview = null
    // (Only ever on the picture it started on: ids are only unique within one picture)
    val d = s.doc
    s.tempId?.let { id ->
        if (d.previewLayerId == id) {
            d.layers.removeAll { it.id == id }
            d.previewLayerId = 0L
            if (d.activeId == id) d.activeId = s.layerId
        }
    }
    if (popup == PopupKind.ENHANCE) popup = null
    d.invalidate()
    doc?.invalidate()
    // The depth model is big and each effect only needs it once
    if (s.kind.depth) Models.forget(Models.DEPTH)
}

// ===================================================================== auto enhance

/** Auto enhance: a Levels adjustment layer (clipped to the photo) that evens out its tones and color. */
fun EditorViewModel.autoEnhance() {
    val d = doc ?: return
    if (pendingFirst { autoEnhance() }) return
    val l = d.active
    if (l == null || !l.hasContent) {
        toast("Pick a photo layer first")
        return
    }
    val area = l.bounds()?.takeIf { it.intersect(0, 0, d.width, d.height) } ?: run {
        toast("\"${l.props.name}\" has nothing on the canvas")
        return
    }
    val alone = aloneCopy(l, withMask = true)
    val sel = d.selection?.takeIf { !it.isEmpty }
    runBusy("Looking at the photo…") {
        // With a selection: the layer's mask shows only what's selected
        val mask = withContext(Dispatchers.Default) {
            sel?.let { selectionMask(it, Layer(-1L, LayerProps("Auto enhance"), TileGrid(alphaOnly = false), kind = LayerKind.ADJUSTMENT), d) }
        }
        val levels = withContext(Dispatchers.Default) {
            val full = DocRenderer.renderLayers(listOf(alone), area, d.width, d.height, doc = d)
            val k = min(1f, 800f / max(area.width(), area.height()))
            val small = if (k < 1f) Bitmap.createScaledBitmap(full, (area.width() * k).roundToInt().coerceAtLeast(1), (area.height() * k).roundToInt().coerceAtLeast(1), true).also { full.recycle() } else full
            EnhanceArt.autoLevels(small).also { small.recycle() }
        }
        if (doc !== d || d.layer(l.id) == null) return@runBusy
        stackEdit("Auto enhance") {
            val fx = FxParams(kind = FxKind.LEVELS, levels = levels)
            val nl = Layer(
                d.newId(), LayerProps("Auto enhance", parent = l.props.parent, clip = true, adjust = fx),
                TileGrid(alphaOnly = false), mask = mask ?: TileGrid(alphaOnly = true), kind = LayerKind.ADJUSTMENT,
            )
            d.layers.add(d.indexOf(l.id) + 1, nl)
            d.activeId = nl.id
            d.editMask = false
            if (mask != null) d.selection = null
        }
        toast("Auto enhance added as a Levels layer: fine-tune it like any Levels layer, or lower its opacity")
    }
}

// ===================================================================== upscale

/** The biggest Upscale that still fits (2 or 4), or 0 when even 2× would be too big. */
internal fun EditorViewModel.upscaleRoom(): Int {
    val d = doc ?: return 0
    val side = max(d.width, d.height)
    return when {
        side * 4 <= com.tim.quartzeditor.engine.Doc.MAX_SIDE -> 4
        side * 2 <= com.tim.quartzeditor.engine.Doc.MAX_SIDE -> 2
        else -> 0
    }
}

/** Upscale: the whole picture [factor] times bigger; photos (pixel layers) through the AI, everything else scaled sharply as usual. */
fun EditorViewModel.upscaleImage(factor: Int) {
    val d = doc ?: return
    if (!Models.has(ctx, Models.UPSCALE)) {
        toast("This build doesn't include the upscale model. Build the app with internet on once so Android Studio can download it.")
        return
    }
    if (max(d.width, d.height) * factor > com.tim.quartzeditor.engine.Doc.MAX_SIDE) {
        toast("That would be bigger than ${com.tim.quartzeditor.engine.Doc.MAX_SIDE} px")
        return
    }
    val f = factor.toFloat()
    canvasTransform("Upscale ${factor}×", d.width * factor, d.height * factor, styleScale = f, aiFactor = factor) { m -> m.postScale(f, f) }
}

/**
 * One pixel layer's tiles made [factor] times bigger by the AI (canvas position scaled with it).
 * Only what's on the [docW]×[docH] canvas is kept (a cropped photo can hold far more around it,
 * which at 4× wouldn't fit in memory). Called by [canvasTransform] in the background.
 */
internal fun EditorViewModel.aiUpscaleTiles(tiles: Map<Long, Bitmap>, x: Int, y: Int, docW: Int, docH: Int, factor: Int, progress: (Float) -> Unit): TileGrid {
    val g = TileGrid(alphaOnly = false).also { it.tiles.putAll(tiles) }
    val b = com.tim.quartzeditor.engine.Transformer.contentBounds(tiles)?.takeIf { it.intersect(-x, -y, docW - x, docH - y) }
        ?: return TileGrid(alphaOnly = false)
    val src = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
    g.drawTo(Canvas(src), -b.left.toFloat(), -b.top.toFloat(), null)
    val big = Upscaler.upscale(ctx, src, factor, progress)
    src.recycle()
    return TileGrid.fromBitmap(big, (x + b.left) * factor, (y + b.top) * factor, alphaOnly = false).also {
        big.recycle()
        it.dropEmptyTiles()
    }
}
