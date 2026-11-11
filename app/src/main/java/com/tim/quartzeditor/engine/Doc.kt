package com.tim.quartzeditor.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/** Layer blend modes, in Photoshop's order. [group] starts a new section in the menu. */
/**
 * Layer blend modes, in Photoshop's order. Most are drawn by Android itself ([mode]); the ones
 * Android doesn't have ([custom] > 0) are mixed by [BlendShader]. Where those can't be used
 * (effects, adjustment layers) [mode] is the nearest look.
 */
enum class Blend(val label: String, val mode: BlendMode, val group: Int, val custom: Int = 0) {
    NORMAL("Normal", BlendMode.SRC_OVER, 0),
    DISSOLVE("Dissolve", BlendMode.SRC_OVER, 0, 1),
    DARKEN("Darken", BlendMode.DARKEN, 1),
    MULTIPLY("Multiply", BlendMode.MULTIPLY, 1),
    COLOR_BURN("Color Burn", BlendMode.COLOR_BURN, 1),
    LINEAR_BURN("Linear Burn", BlendMode.MULTIPLY, 1, 2),
    DARKER_COLOR("Darker Color", BlendMode.DARKEN, 1, 3),
    LIGHTEN("Lighten", BlendMode.LIGHTEN, 2),
    SCREEN("Screen", BlendMode.SCREEN, 2),
    COLOR_DODGE("Color Dodge", BlendMode.COLOR_DODGE, 2),
    LINEAR_DODGE("Linear Dodge (Add)", BlendMode.PLUS, 2),
    LIGHTER_COLOR("Lighter Color", BlendMode.LIGHTEN, 2, 4),
    OVERLAY("Overlay", BlendMode.OVERLAY, 3),
    SOFT_LIGHT("Soft Light", BlendMode.SOFT_LIGHT, 3),
    HARD_LIGHT("Hard Light", BlendMode.HARD_LIGHT, 3),
    VIVID_LIGHT("Vivid Light", BlendMode.HARD_LIGHT, 3, 5),
    LINEAR_LIGHT("Linear Light", BlendMode.HARD_LIGHT, 3, 6),
    PIN_LIGHT("Pin Light", BlendMode.HARD_LIGHT, 3, 7),
    HARD_MIX("Hard Mix", BlendMode.HARD_LIGHT, 3, 8),
    DIFFERENCE("Difference", BlendMode.DIFFERENCE, 4),
    EXCLUSION("Exclusion", BlendMode.EXCLUSION, 4),
    SUBTRACT("Subtract", BlendMode.DIFFERENCE, 4, 9),
    DIVIDE("Divide", BlendMode.COLOR_DODGE, 4, 10),
    HUE("Hue", BlendMode.HUE, 5),
    SATURATION("Saturation", BlendMode.SATURATION, 5),
    COLOR("Color", BlendMode.COLOR, 5),
    LUMINOSITY("Luminosity", BlendMode.LUMINOSITY, 5),
    ;

    /** Drawn by Android itself (usable anywhere, including effects). */
    val exact: Boolean get() = custom == 0

    companion object {
        fun byName(n: String?): Blend = entries.firstOrNull { it.name == n } ?: NORMAL
    }
}

/**
 * Pixels, a folder of layers, an adjustment that changes everything below it, or a shape or text
 * layer (which keeps its shape or words, and draws its pixels from them).
 */
enum class LayerKind { PIXELS, GROUP, ADJUSTMENT, SHAPE, TEXT, SMART }

/** Everything about a layer that isn't pixels. Immutable, so undo can keep old copies. */
data class LayerProps(
    val name: String,
    val visible: Boolean = true,
    /** 0..1 */
    val opacity: Float = 1f,
    /** 0..1. Without layer effects this works like a second opacity. */
    val fill: Float = 1f,
    val blend: Blend = Blend.NORMAL,
    /** Painting only changes pixels that are already there. */
    val lockAlpha: Boolean = false,
    /** No painting, moving or changes at all. */
    val locked: Boolean = false,
    /** Mask switched off (kept, but not applied). */
    val maskDisabled: Boolean = false,
    /** Stroke, shadows, glow, color overlay. */
    val style: LayerStyle = LayerStyle(),
    /** The folder this layer is in (0 = not in a folder). */
    val parent: Long = 0L,
    /** Adjustment layers: what they do to everything below. */
    val adjust: FxParams? = null,
    /** Shape layers: the shape. */
    val shape: ShapeSpec? = null,
    /** Text layers: the text and how it looks. */
    val text: TextSpec? = null,
    /** Smart layers: the picture inside and where it's placed. */
    val smart: SmartSpec? = null,
    /**
     * Clipping mask: only shows where the layer below it (its base, the nearest unclipped
     * pixel, shape or text layer in the same folder) has pixels.
     */
    val clip: Boolean = false,
    /**
     * Folders: Pass Through (the default, like Photoshop): the layers inside blend with everything
     * below the folder, not just with each other. Off: the folder is drawn as one picture and then
     * blended with [blend].
     */
    val passThrough: Boolean = true,
    /** Blending Options → Blend If: shows the layer only where it, and what's below it, are in these ranges of gray. */
    val blendIf: BlendIf = BlendIf(),
    /** Quick Mask's temporary red layer (never saved, exported or sampled; leaving Quick Mask removes it). */
    val quickMask: Boolean = false,
)

/**
 * Photoshop's Blend If (gray), 0..255 each. The layer shows fully where its own brightness is between
 * [thisLo1] and [thisHi0] and fades out to nothing at [thisLo0] and [thisHi1] (a split slider fades
 * gradually; an unsplit one cuts sharply); likewise for what's below it ([underLo0] … [underHi1]).
 */
data class BlendIf(
    val thisLo0: Int = 0,
    val thisLo1: Int = 0,
    val thisHi0: Int = 255,
    val thisHi1: Int = 255,
    val underLo0: Int = 0,
    val underLo1: Int = 0,
    val underHi0: Int = 255,
    val underHi1: Int = 255,
) {
    /** It changes anything (the defaults let everything through). */
    val active: Boolean get() = this != BlendIf()

    val thisActive: Boolean get() = thisLo1 > 0 || thisHi0 < 255
    val underActive: Boolean get() = underLo1 > 0 || underHi0 < 255

    fun toJson(): org.json.JSONObject = org.json.JSONObject().put(
        "v", org.json.JSONArray(listOf(thisLo0, thisLo1, thisHi0, thisHi1, underLo0, underLo1, underHi0, underHi1)),
    )

    /** Keeps each pair in order (black below white, the two halves of a split the right way round). */
    fun sane(): BlendIf {
        fun c(v: Int) = v.coerceIn(0, 255)
        val a0 = c(thisLo0); val a1 = c(thisLo1).coerceAtLeast(a0)
        val b1 = c(thisHi1); val b0 = c(thisHi0).coerceAtMost(b1)
        val u0 = c(underLo0); val u1 = c(underLo1).coerceAtLeast(u0)
        val w1 = c(underHi1); val w0 = c(underHi0).coerceAtMost(w1)
        return BlendIf(a0, a1, b0, b1, u0, u1, w0, w1)
    }

    companion object {
        fun fromJson(o: org.json.JSONObject?): BlendIf {
            val v = o?.optJSONArray("v") ?: return BlendIf()
            if (v.length() < 8) return BlendIf()
            return BlendIf(v.optInt(0), v.optInt(1), v.optInt(2, 255), v.optInt(3, 255), v.optInt(4), v.optInt(5), v.optInt(6, 255), v.optInt(7, 255)).sane()
        }
    }
}

/**
 * One layer. Pixels live in [tiles], positioned at ([x], [y]) on the canvas. The optional [mask]
 * shares the layer's position; it stores how much is *hidden* (0 = shown), so a missing mask tile
 * means "fully shown" and an empty mask costs nothing.
 *
 * The mutable fields are only changed through [Doc]'s methods, which keep undo in step.
 */
class Layer(
    val id: Long,
    props: LayerProps,
    tiles: TileGrid,
    x: Int = 0,
    y: Int = 0,
    mask: TileGrid? = null,
    val kind: LayerKind = LayerKind.PIXELS,
) {
    val isGroup: Boolean get() = kind == LayerKind.GROUP
    val isAdjustment: Boolean get() = kind == LayerKind.ADJUSTMENT
    val isPixels: Boolean get() = kind == LayerKind.PIXELS
    val isShape: Boolean get() = kind == LayerKind.SHAPE
    val isText: Boolean get() = kind == LayerKind.TEXT
    /** Shape or text: its pixels are drawn from what it is, and painting needs rasterizing first. */
    val isVector: Boolean get() = kind == LayerKind.SHAPE || kind == LayerKind.TEXT
    /** A picture of its own, placed (see [SmartSpec]); its pixels are drawn from it. */
    val isSmart: Boolean get() = kind == LayerKind.SMART
    /** Shape, text and smart layers: changing their pixels directly means rasterizing them first. */
    val needsRaster: Boolean get() = isVector || isSmart
    /** Has pixels of its own to draw (pixel, shape, text and smart layers). */
    val hasContent: Boolean get() = kind == LayerKind.PIXELS || isVector || kind == LayerKind.SMART
    /** Layers can be clipped to it: anything with pixels, or a folder (clipped to everything in it). */
    val canBeClipBase: Boolean get() = hasContent || isGroup
    /** Can carry a layer style: anything with pixels, or a folder (styled as a whole). */
    val canHaveStyle: Boolean get() = hasContent || isGroup

    /** Folder folded shut in the Layers panel (not part of undo). */
    var collapsed by mutableStateOf(false)

    var props by mutableStateOf(props)
    var tiles by mutableStateOf(tiles)
    var x by mutableIntStateOf(x)
    var y by mutableIntStateOf(y)
    var mask by mutableStateOf(mask)

    /** Panel thumbnails, refreshed shortly after the pixels change. */
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var maskThumb by mutableStateOf<ImageBitmap?>(null)

    /** Bumped whenever pixels change (drives thumbnail refreshes). */
    var pixelVersion by mutableIntStateOf(0)

    val hasMask: Boolean get() = mask != null
    val maskActive: Boolean get() = mask != null && !props.maskDisabled

    /** Canvas-space bounds of the layer's tiles, or null when it's empty. */
    fun bounds(): Rect? = tiles.tileBounds()?.apply { offset(x, y) }
}

/** What a layer looked like at one moment (for undo). Holds references, not copies. */
data class LayerSnap(
    val layer: Layer,
    val props: LayerProps,
    val tiles: TileGrid,
    val x: Int,
    val y: Int,
    val mask: TileGrid?,
)

data class DocSnap(
    val layers: List<LayerSnap>,
    val activeId: Long,
    val editMask: Boolean,
    val width: Int,
    val height: Int,
    val selection: Selection?,
    val guidesX: List<Float> = emptyList(),
    val guidesY: List<Float> = emptyList(),
)

/** An open image: a stack of layers on a canvas of [width] × [height] pixels. */
class Doc(width: Int, height: Int, name: String) {
    var width by mutableIntStateOf(width)
    var height by mutableIntStateOf(height)
    var name by mutableStateOf(name)

    /** Bottom layer first. */
    val layers = mutableStateListOf<Layer>()

    var activeId by mutableLongStateOf(-1L)

    /** Painting goes to the active layer's mask instead of its pixels. */
    var editMask by mutableStateOf(false)

    /** The selected area (null = nothing selected, so everything can be changed). */
    var selection by mutableStateOf<Selection?>(null)

    /** Guides (canvas pixels): vertical ones at these x, horizontal ones at these y. */
    val guidesX = mutableStateListOf<Float>()
    val guidesY = mutableStateListOf<Float>()

    /** Bumped on every visible change, so the canvas redraws. */
    var renderVersion by mutableIntStateOf(0)

    /** Where this document is saved in the app's projects (null until first saved). */
    var projectId: String? = null

    /** Where its unsaved changes are kept as they're made (Drafts), so a crash loses nothing (null until the first). */
    var draftId: String? = null

    /** The Photoshop file it was opened from, when that can be written back to: Save writes it (null: Save goes to Projects). */
    var psdUri: String? = null

    /**
     * What came in differently when the .psd was opened (non-empty: saving over it asks first, once,
     * since those things would be lost from the original).
     */
    var psdConfirm: List<String> = emptyList()

    /** A layer put in only to show a preview (Enhance): left out of undo steps and saved files. */
    var previewLayerId = 0L

    private var nextId = 1L

    val active: Layer? get() = layers.firstOrNull { it.id == activeId } ?: layers.lastOrNull()

    fun newId(): Long = nextId++

    /** After loading a project: new ids must not clash with loaded ones. */
    fun reserveIds(maxUsed: Long) {
        if (maxUsed >= nextId) nextId = maxUsed + 1
    }

    fun indexOf(id: Long): Int = layers.indexOfFirst { it.id == id }

    fun layer(id: Long): Layer? = layers.firstOrNull { it.id == id }

    fun invalidate() {
        renderVersion++
    }

    fun touched(layer: Layer) {
        layer.pixelVersion++
        renderVersion++
    }

    fun uniqueName(base: String): String {
        val names = layers.map { it.props.name }.toSet()
        var n = layers.size + 1
        while ("$base $n" in names) n++
        return "$base $n"
    }

    fun snapshot(): DocSnap = DocSnap(
        layers.filter { it.id != previewLayerId || previewLayerId == 0L }.map { LayerSnap(it, it.props, it.tiles, it.x, it.y, it.mask) },
        activeId,
        editMask,
        width,
        height,
        selection,
        guidesX.toList(),
        guidesY.toList(),
    )

    fun restore(s: DocSnap) {
        for (ls in s.layers) {
            val l = ls.layer
            val pixelsChanged = l.tiles !== ls.tiles || l.mask !== ls.mask
            l.props = ls.props
            l.tiles = ls.tiles
            l.x = ls.x
            l.y = ls.y
            l.mask = ls.mask
            if (pixelsChanged) l.pixelVersion++
        }
        layers.clear()
        layers.addAll(s.layers.map { it.layer })
        activeId = s.activeId
        editMask = s.editMask && active?.hasMask == true
        width = s.width
        height = s.height
        selection = s.selection
        if (guidesX.toList() != s.guidesX) {
            guidesX.clear()
            guidesX.addAll(s.guidesX)
        }
        if (guidesY.toList() != s.guidesY) {
            guidesY.clear()
            guidesY.addAll(s.guidesY)
        }
        renderVersion++
    }

    /**
     * A layer just added inside a clipping group (with a clipped layer right above it) joins the
     * group, like Photoshop, so the layers above stay clipped to the same base.
     */
    fun joinClipGroup(l: Layer) {
        if (l.isGroup || l.props.clip) return
        val above = layers.getOrNull(indexOf(l.id) + 1) ?: return
        if (above.props.parent == l.props.parent && above.props.clip) l.props = l.props.copy(clip = true)
    }

    /**
     * Puts the list back in folder order after layers changed folders: each folder's contents
     * directly below it, keeping everyone's order otherwise.
     */
    fun normalizeTree() {
        val kids = HashMap<Long, MutableList<Layer>>()
        for (x in layers.asReversed()) kids.getOrPut(x.props.parent) { mutableListOf() }.add(x)
        val order = ArrayList<Layer>(layers.size)
        val seen = HashSet<Long>()
        fun emit(parent: Long) {
            for (x in (kids[parent] ?: return).asReversed()) {
                if (!seen.add(x.id)) continue
                if (x.isGroup) emit(x.id)
                order.add(x)
            }
        }
        emit(0L)
        // Anything whose folder is gone goes to the top level
        for (x in layers) if (x.id !in seen) {
            x.props = x.props.copy(parent = 0L)
            order.add(x)
        }
        layers.clear()
        layers.addAll(order)
    }

    // ------------------------------------------------------------ folders
    //
    // Layers stay in one bottom-first list. A folder's contents sit directly below it in that list
    // (its "block"), and each layer names its folder in props.parent.

    fun parentOf(l: Layer): Layer? = if (l.props.parent == 0L) null else layer(l.props.parent)

    /** True when [l] is somewhere inside folder [g]. */
    fun isInside(l: Layer, g: Layer): Boolean {
        var p = parentOf(l)
        var guard = 0
        while (p != null && guard++ < 64) {
            if (p.id == g.id) return true
            p = parentOf(p)
        }
        return false
    }

    /** Layers directly in folder [parentId] (0 = the top level), bottom first. */
    fun children(parentId: Long): List<Layer> = layers.filter { it.props.parent == parentId }

    /** Index range of [l] plus everything inside it (a folder's contents are just below it). */
    fun block(l: Layer): IntRange {
        val i = indexOf(l.id)
        if (!l.isGroup) return i..i
        var start = i
        while (start > 0 && isInside(layers[start - 1], l)) start--
        return start..i
    }

    /** How many folders deep [l] is. */
    fun depth(l: Layer): Int {
        var d = 0
        var p = parentOf(l)
        while (p != null && d < 64) {
            d++
            p = parentOf(p)
        }
        return d
    }

    /** Shown on the canvas: it and every folder it's in are visible. */
    fun effectivelyVisible(l: Layer): Boolean {
        if (!l.props.visible) return false
        var p = parentOf(l)
        var guard = 0
        while (p != null && guard++ < 64) {
            if (!p.props.visible) return false
            p = parentOf(p)
        }
        return true
    }

    /**
     * A copy that shares the same tile bitmaps but not the lists that hold them, so it can be
     * saved or exported on a background thread while you keep working. Call on the main thread.
     */
    fun shallowCopy(): Doc {
        val d = Doc(width, height, name)
        // Linked smart layers (sharing contents) share them in the copy too
        val contentsCopies = java.util.IdentityHashMap<Doc, Doc>()
        for (l in layers) {
            if (l.props.quickMask) continue
            val t = TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }
            val m = l.mask?.let { src -> TileGrid(alphaOnly = true).also { it.tiles.putAll(src.tiles) } }
            // A smart layer's contents are copied the same way (they may be edited meanwhile)
            val props = l.props.smart?.let { sp ->
                l.props.copy(smart = sp.withContents(contentsCopies.getOrPut(sp.contents) { sp.contents.shallowCopy() }))
            } ?: l.props
            d.layers.add(Layer(l.id, props, t, l.x, l.y, m, l.kind).also { it.collapsed = l.collapsed })
        }
        d.activeId = if (d.layers.any { it.id == activeId }) activeId else d.layers.lastOrNull()?.id ?: -1L
        d.selection = selection
        d.guidesX.addAll(guidesX)
        d.guidesY.addAll(guidesY)
        d.projectId = projectId
        d.draftId = draftId
        d.psdUri = psdUri
        d.psdConfirm = psdConfirm
        d.nextId = nextId
        d.previewLayerId = previewLayerId
        return d
    }

    companion object {
        /**
         * Longest side allowed for a canvas: long and thin panoramas keep every pixel. As big as the
         * GPU's biggest texture allows (its effects and blends work on whole layers), less room for a
         * blur to spread past the edges, and under WebP's 16383; at least the old 4096.
         */
        val MAX_SIDE: Int by lazy { (minOf(16383, Gpu.maxTexture - 1024)).coerceAtLeast(4096) }

        /** Most pixels a canvas can have: 4096 × 4096, about 17 MP (what keeps memory safe on every device). */
        const val MAX_PIXELS = 4096L * 4096L

        /** [w]×[h] fits the limits (side and pixels). */
        fun fits(w: Int, h: Int): Boolean = w in 1..MAX_SIDE && h in 1..MAX_SIDE && w.toLong() * h <= MAX_PIXELS

        /** The most [w]×[h] can be scaled by and still fit (below 1: it has to shrink). */
        fun maxScale(w: Int, h: Int): Float {
            val ww = w.coerceAtLeast(1)
            val hh = h.coerceAtLeast(1)
            return minOf(MAX_SIDE.toFloat() / maxOf(ww, hh), kotlin.math.sqrt(MAX_PIXELS.toDouble() / (ww.toLong() * hh)).toFloat())
        }

        /** How much [w]×[h] shrinks to fit (1 when it already does). */
        fun fitScale(w: Int, h: Int): Float = minOf(1f, maxScale(w, h))

        /** [w]×[h] made to fit, shape kept. */
        fun fitted(w: Int, h: Int): Pair<Int, Int> {
            val k = fitScale(w, h)
            if (k >= 1f) return w.coerceIn(1, MAX_SIDE) to h.coerceIn(1, MAX_SIDE)
            // (Rounded down, so it's never just over)
            return (w * k).toInt().coerceIn(1, MAX_SIDE) to (h * k).toInt().coerceIn(1, MAX_SIDE)
        }

        /** The limits in words, for messages. */
        val LIMIT_TEXT: String get() = "$MAX_SIDE px on a side and ${MAX_PIXELS / 1_000_000} megapixels"

        /** The pixel limit as a square ("8192 × 8192"). */
        val SQUARE_TEXT: String get() = kotlin.math.sqrt(MAX_PIXELS.toDouble()).toInt().let { "$it × $it" }

    }
}

/** Fills a whole canvas-sized area of a new grid with one color. */
fun solidGrid(width: Int, height: Int, color: Int): TileGrid {
    val g = TileGrid(alphaOnly = false)
    val r = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
    for (k in g.keysIn(r)) {
        val t = g.getOrCreate(k)
        val c = android.graphics.Canvas(t)
        val left = (TileGrid.tx(k) * TileGrid.SIZE).toFloat()
        val top = (TileGrid.ty(k) * TileGrid.SIZE).toFloat()
        c.translate(-left, -top)
        c.drawRect(r, android.graphics.Paint().apply { this.color = color })
    }
    return g
}

/** Byte cost of a bitmap held only by undo history. */
fun Bitmap?.cost(): Long = this?.allocationByteCount?.toLong() ?: 0L
