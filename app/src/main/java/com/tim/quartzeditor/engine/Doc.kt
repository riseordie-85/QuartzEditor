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
enum class Blend(val label: String, val mode: BlendMode, val group: Int) {
    NORMAL("Normal", BlendMode.SRC_OVER, 0),
    DARKEN("Darken", BlendMode.DARKEN, 1),
    MULTIPLY("Multiply", BlendMode.MULTIPLY, 1),
    COLOR_BURN("Color Burn", BlendMode.COLOR_BURN, 1),
    LIGHTEN("Lighten", BlendMode.LIGHTEN, 2),
    SCREEN("Screen", BlendMode.SCREEN, 2),
    COLOR_DODGE("Color Dodge", BlendMode.COLOR_DODGE, 2),
    LINEAR_DODGE("Linear Dodge (Add)", BlendMode.PLUS, 2),
    OVERLAY("Overlay", BlendMode.OVERLAY, 3),
    SOFT_LIGHT("Soft Light", BlendMode.SOFT_LIGHT, 3),
    HARD_LIGHT("Hard Light", BlendMode.HARD_LIGHT, 3),
    DIFFERENCE("Difference", BlendMode.DIFFERENCE, 4),
    EXCLUSION("Exclusion", BlendMode.EXCLUSION, 4),
    HUE("Hue", BlendMode.HUE, 5),
    SATURATION("Saturation", BlendMode.SATURATION, 5),
    COLOR("Color", BlendMode.COLOR, 5),
    LUMINOSITY("Luminosity", BlendMode.LUMINOSITY, 5),
    ;

    companion object {
        fun byName(n: String?): Blend = entries.firstOrNull { it.name == n } ?: NORMAL
    }
}

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
)

/**
 * One layer. Pixels live in [tiles], positioned at ([x], [y]) on the canvas. The optional [mask]
 * shares the layer's position; it stores how much is *hidden* (0 = shown), so a missing mask tile
 * means "fully shown" and an empty mask costs nothing.
 *
 * The mutable fields are only changed through [Doc]'s methods, which keep undo in step.
 */
class Layer(val id: Long, props: LayerProps, tiles: TileGrid, x: Int = 0, y: Int = 0, mask: TileGrid? = null) {
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

data class DocSnap(val layers: List<LayerSnap>, val activeId: Long, val editMask: Boolean, val width: Int, val height: Int)

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

    /** Bumped on every visible change, so the canvas redraws. */
    var renderVersion by mutableIntStateOf(0)

    /** Where this document is saved in the app's projects (null until first saved). */
    var projectId: String? = null

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
        layers.map { LayerSnap(it, it.props, it.tiles, it.x, it.y, it.mask) },
        activeId,
        editMask,
        width,
        height,
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
        renderVersion++
    }

    /**
     * A copy that shares the same tile bitmaps but not the lists that hold them, so it can be
     * saved or exported on a background thread while you keep working. Call on the main thread.
     */
    fun shallowCopy(): Doc {
        val d = Doc(width, height, name)
        for (l in layers) {
            val t = TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }
            val m = l.mask?.let { src -> TileGrid(alphaOnly = true).also { it.tiles.putAll(src.tiles) } }
            d.layers.add(Layer(l.id, l.props, t, l.x, l.y, m))
        }
        d.activeId = activeId
        d.projectId = projectId
        d.nextId = nextId
        return d
    }

    companion object {
        /** Longest side allowed for a canvas (keeps memory safe on every device). */
        const val MAX_SIDE = 4096
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
