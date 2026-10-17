package com.tim.quartzeditor.ui

import com.tim.quartzeditor.engine.BuiltEffects
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
            it.hasContent && it.props.visible && it.props.style.needsBuild &&
                Effects.keyOf(it).let { k -> effectsCache[it.id]?.key != k && k !in failedEffects }
        } ?: continue
        val key = Effects.keyOf(todo)
        val style = todo.props.style
        val tilesId = System.identityHashCode(todo.tiles)
        val tiles = HashMap(todo.tiles.tiles)
        val mask = if (todo.maskActive) todo.mask?.let { HashMap(it.tiles) } else null
        val built = withContext(Dispatchers.Default) {
            try {
                Effects.build(key, style, tiles, mask, SCREEN_FX_PX, tilesId)
            } catch (e: Throwable) {
                null
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

/** Full-size effect images for a background render (merge, flatten). */
internal fun EditorViewModel.fullEffects(layers: List<Layer>): Map<Long, BuiltEffects> {
    val out = HashMap<Long, BuiltEffects>()
    for (l in layers) {
        if (!l.props.style.needsBuild) continue
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
