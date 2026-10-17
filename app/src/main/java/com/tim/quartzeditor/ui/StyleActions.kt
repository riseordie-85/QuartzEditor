package com.tim.quartzeditor.ui

import com.tim.quartzeditor.engine.BuiltEffects
import com.tim.quartzeditor.engine.DocRenderer
import com.tim.quartzeditor.engine.Effects
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerStyle
import com.tim.quartzeditor.ui.EditorViewModel.Companion.FULL_FX_PX
import com.tim.quartzeditor.ui.EditorViewModel.Companion.SCREEN_FX_PX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Layer style effects: building them in the background and changing them. (Moved out of EditorViewModel.kt; same code, as extension functions.)

/** Keeps effect images up to date in the background, one layer at a time. */
internal suspend fun EditorViewModel.effectsLoop() {
    while (true) {
        delay(60)
        val d = doc ?: continue
        if (stroking || transform != null || busy != null) continue
        val todo = d.layers.firstOrNull {
            it.canHaveStyle && it.props.visible && it.props.style.needsBuild &&
                effectsKey(d, it).let { k -> effectsCache[it.id]?.key != k && k !in failedEffects }
        } ?: continue
        val key = effectsKey(d, todo)
        val built = if (todo.isGroup) {
            // A folder's effects are made from everything in it drawn together (from a copy: it may change meanwhile)
            val copy = d.shallowCopy()
            val group = copy.layer(todo.id) ?: continue
            val fx = HashMap(effectsCache)
            // Matched to the folder itself (the copy has its own tile grids)
            val tilesId = System.identityHashCode(todo.tiles)
            withContext(Dispatchers.Default) { DocRenderer.buildEffects(copy, group, SCREEN_FX_PX, key, tilesId) { fx[it.id] } }
        } else {
            val style = todo.props.style
            val tilesId = System.identityHashCode(todo.tiles)
            val tiles = HashMap(todo.tiles.tiles)
            val mask = if (todo.maskActive) todo.mask?.let { HashMap(it.tiles) } else null
            withContext(Dispatchers.Default) {
                try {
                    Effects.build(key, style, tiles, mask, SCREEN_FX_PX, tilesId)
                } catch (e: Throwable) {
                    null
                }
            }
        }
        if (doc !== d) continue
        if (built == null) {
            failedEffects.add(key)
            effectsCache.remove(todo.id)
        } else {
            effectsCache[todo.id] = built
        }
        d.invalidate()
    }
}

/**
 * What a layer's effects depend on. A folder's also depend on everything in it (and on their own
 * effects, which are drawn into the folder's look).
 */
internal fun EditorViewModel.effectsKey(d: com.tim.quartzeditor.engine.Doc, l: Layer): Any {
    if (!l.isGroup) return Effects.keyOf(l)
    // Worked out once per change to the picture (it looks at everything in the folder)
    groupKeys[l]?.let { (doc, v, k) -> if (doc === d && v == d.renderVersion) return k }
    val inside = d.layers.slice(d.block(l)).filter { it !== l }.map {
        listOf(it.id, it.props, System.identityHashCode(it.tiles), it.pixelVersion, it.x, it.y, System.identityHashCode(it.mask), System.identityHashCode(effectsCache[it.id]))
    }
    return listOf(Effects.keyOf(l), l.x, l.y, inside).also { groupKeys[l] = Triple(d, d.renderVersion, it) }
}

/** Folders' effect keys, by the picture's version they were worked out for. */
private val groupKeys = java.util.WeakHashMap<Layer, Triple<com.tim.quartzeditor.engine.Doc, Int, Any>>()

/** Full-size effect images for a background render (merge, flatten). */
internal fun EditorViewModel.fullEffects(layers: List<Layer>): Map<Long, BuiltEffects> {
    val out = HashMap<Long, BuiltEffects>()
    val d = doc
    // Bottom first, so a folder's layers have their effects before the folder's own are made
    val order = if (d != null) layers.sortedBy { d.layers.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } } else layers
    for (l in order) {
        if (!l.props.style.needsBuild) continue
        if (l.isGroup) {
            if (d == null || d.layer(l.id) !== l) continue
            DocRenderer.buildEffects(d, l, FULL_FX_PX) { out[it.id] }?.let { out[l.id] = it }
            continue
        }
        val mask = if (l.maskActive) l.mask?.tiles else null
        runCatching { Effects.build(Unit, l.props.style, l.tiles.tiles, mask, FULL_FX_PX) }.getOrNull()?.let { out[l.id] = it }
    }
    return out
}

/** Live change to the selected layer's style while a slider moves (call [commitProps] after). */
fun EditorViewModel.setStyleLive(change: (LayerStyle) -> LayerStyle) {
    val l = doc?.active ?: return
    setPropsLive(l.id) { it.copy(style = change(it.style)) }
}

fun EditorViewModel.setStyle(label: String, change: (LayerStyle) -> LayerStyle) {
    val l = doc?.active ?: return
    setProps(l.id, label) { it.copy(style = change(it.style)) }
}
