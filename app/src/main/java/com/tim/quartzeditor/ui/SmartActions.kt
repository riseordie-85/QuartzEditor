package com.tim.quartzeditor.ui

import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.tim.quartzeditor.data.Media
import com.tim.quartzeditor.engine.Change
import com.tim.quartzeditor.engine.Doc
import com.tim.quartzeditor.engine.ContentsSnap
import com.tim.quartzeditor.engine.History
import com.tim.quartzeditor.engine.Layer
import com.tim.quartzeditor.engine.LayerKind
import com.tim.quartzeditor.engine.LayerProps
import com.tim.quartzeditor.engine.SmartArt
import com.tim.quartzeditor.engine.SmartContentsChange
import com.tim.quartzeditor.engine.SmartSpec
import com.tim.quartzeditor.engine.TileGrid
import com.tim.quartzeditor.engine.placedAfterResize
import com.tim.quartzeditor.engine.linkedCopies
import com.tim.quartzeditor.engine.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Smart layers: a picture of its own (at its full size, with its own layers) placed on the canvas.
// Scaling, rotating or distorting it always redraws from the full-size contents, so nothing is
// lost. Placed images become smart layers. Double-tap one in the Layers panel to edit its contents
// in a tab of its own; going back to the picture's tab updates the layer.

/**
 * One open tab: a document and its own undo. [parent] is the tab holding the smart layer
 * [layerId] whose contents [doc] is (null for the picture itself).
 */
class EditorTab(val doc: Doc, var history: History, val parent: EditorTab?, val layerId: Long) {
    /** The view (zoom and position) when last left, and how far it was turned. */
    var view: Triple<Float, Float, Float>? = null
    var viewRotation = 0f

    /** The contents as they were when last put into the smart layer. */
    var baseline: ContentsSnap? = null

    /** [history]'s version then (anything newer is an edit not yet in the smart layer). */
    var committedVersion = 0

    val hasNewEdits: Boolean get() = parent != null && history.version != committedVersion

    /** [history]'s version when the picture was last saved with this tab's edits in it. */
    var savedVersion = -1

    /** [history]'s version when the picture's draft was last written with this tab's edits in it. */
    var draftedVersion = -1
}

/** The picture itself (not a smart layer's contents in a tab). */
val EditorViewModel.rootDoc: Doc? get() = tabs.firstOrNull()?.doc ?: doc

internal val EditorViewModel.currentTab: EditorTab? get() = tabs.getOrNull(tabIndex)

// ===================================================================== placing images

/**
 * Adds a photo as a smart layer at its full size (fitted inside the canvas, centered), and starts
 * Transform on it so you can size and place it before it's applied.
 */
fun EditorViewModel.placeImage(uri: Uri) {
    val d = doc ?: return
    if (pendingFirst { placeImage(uri) }) return
    runBusy("Placing…", onDone = { onToolButton(Tool.TRANSFORM) }) {
        val name = withContext(Dispatchers.IO) { Media.displayName(ctx, uri) }.substringBeforeLast('.').ifBlank { "Image" }
        val bmp = withContext(Dispatchers.IO) { Media.decodeForCanvas(ctx, uri) }
        val (spec, grid) = withContext(Dispatchers.Default) {
            val contents = Doc(bmp.width, bmp.height, name)
            val inner = Layer(contents.newId(), LayerProps(name), TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false))
            bmp.recycle()
            contents.layers.add(inner)
            contents.activeId = inner.id
            val spec = SmartSpec(contents, SmartSpec.fitted(contents.width, contents.height, d.width, d.height))
            spec to SmartArt.rasterize(spec, Transformer.limitFor(d.width, d.height))
        }
        if (doc !== d) return@runBusy
        stackEdit("Place image") {
            val (at, parent) = insertPoint(d)
            val l = Layer(d.newId(), LayerProps(name, parent = parent, smart = spec), grid, 0, 0, null, LayerKind.SMART)
            d.layers.add(at, l)
            d.joinClipGroup(l)
            d.activeId = l.id
            d.editMask = false
        }
        multiSelected = emptySet()
    }
}

// ===================================================================== replacing contents

/**
 * Smart layer [EditorViewModel.replaceTarget]'s contents become the file at [uri] (a picture, or a Photoshop
 * or TIFF file with its layers), at the same scale and place, warp and smart filters kept. Linked
 * copies change too, like Photoshop's Replace Contents.
 */
fun EditorViewModel.replaceSmartContents(uri: Uri?) {
    val id = replaceTarget
    replaceTarget = null
    if (uri == null || id == null) return
    replaceContentsOf(id, uri)
}

private fun EditorViewModel.replaceContentsOf(id: Long, uri: Uri) {
    val d = doc ?: return
    val l = d.layer(id)?.takeIf { it.isSmart } ?: return
    if (pendingFirst { replaceContentsOf(id, uri) }) return
    val old = l.props.smart ?: return
    runBusy("Replacing contents…") {
        val name = withContext(Dispatchers.IO) { Media.displayName(ctx, uri) }.substringBeforeLast('.').ifBlank { "Contents" }
        val contents: Doc = withContext(Dispatchers.IO) {
            val layered = runCatching { isPsd(uri) }.getOrDefault(false)
            val fromPsd = if (layered) {
                val tmp = java.io.File(ctx.cacheDir, "replace.psd")
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                        ?: throw IllegalArgumentException("Couldn't read that file.")
                    try {
                        com.tim.quartzeditor.engine.PsdImport.read(tmp, name).doc
                    } catch (e: com.tim.quartzeditor.engine.TiffRawException) {
                        null
                    }
                } finally {
                    tmp.delete()
                }
            } else null
            fromPsd ?: run {
                val bmp = Media.decodeForCanvas(ctx, uri)
                Doc(bmp.width, bmp.height, name).also { c ->
                    val inner = Layer(c.newId(), LayerProps(name), TileGrid.fromBitmap(bmp, 0, 0, alphaOnly = false))
                    bmp.recycle()
                    c.layers.add(inner)
                    c.activeId = inner.id
                }
            }
        }
        if (doc !== d || l.props.smart !== old) return@runBusy
        // The new contents at the old one's scale, centered where it was
        fun placed(sp: SmartSpec): SmartSpec {
            val ow = sp.contents.width.toFloat()
            val oh = sp.contents.height.toFloat()
            val sx = (ow - contents.width) / 2f
            val sy = (oh - contents.height) / 2f
            val corners = floatArrayOf(sx, sy, sx + contents.width, sy, sx + contents.width, sy + contents.height, sx, sy + contents.height)
            sp.matrix().mapPoints(corners)
            return sp.withContents(contents, corners)
        }
        val targets = listOf(l) + linkedCopies(d, l)
        val made = withContext(Dispatchers.Default) {
            targets.map { t ->
                val sp = placed(t.props.smart!!)
                val clip = Transformer.limitFor(d.width, d.height).apply { offset(-t.x, -t.y) }
                Triple(t, sp, SmartArt.rasterize(sp, clip))
            }
        }
        stackEdit("Replace contents") {
            for ((t, sp, grid) in made) {
                t.props = t.props.copy(smart = sp)
                t.tiles = grid
            }
        }
        // A tab showing the old contents no longer belongs to anything
        val here = currentTab
        if (here != null) {
            val gone = tabs.filter { it.doc === old.contents && it.parent === here }
            if (gone.isNotEmpty()) {
                tabs = tabs.filter { t -> gone.none { g -> t === g || isInside(t, g) } }
                tabIndex = tabs.indexOf(here).coerceAtLeast(0)
            }
        }
    }
}

/** Takes the warp off smart layer [layerId] (its contents placed flat again). */
fun EditorViewModel.removeSmartWarp(layerId: Long) {
    val d = doc ?: return
    val l = d.layer(layerId) ?: return
    val sp = l.props.smart?.takeIf { it.warp != null } ?: return
    if (pendingFirst { removeSmartWarp(layerId) }) return
    runBusy("Removing warp…") {
        val spec = sp.withWarp(null)
        val clip = Transformer.limitFor(d.width, d.height).apply { offset(-l.x, -l.y) }
        val grid = withContext(Dispatchers.Default) { SmartArt.rasterize(spec, clip) }
        if (doc !== d || l.props.smart !== sp) return@runBusy
        stackEdit("Remove warp") {
            l.props = l.props.copy(smart = spec)
            l.tiles = grid
        }
    }
}

// ===================================================================== converting

/**
 * Turns the picked layers (or the active one) into one smart layer holding them, where the topmost
 * of them was. Folders come along with what's in them.
 */
fun EditorViewModel.convertToSmart() {
    val d = doc ?: return
    if (pendingFirst { convertToSmart() }) return
    val roots = pickedRoots().ifEmpty { listOfNotNull(d.active) }
    if (roots.isEmpty()) return
    if (roots.any { it.props.locked }) {
        toast("Unlock the layers first")
        return
    }
    if (roots.map { it.props.parent }.distinct().size > 1) {
        toast("Pick layers from the same folder")
        return
    }
    val block = roots.flatMap { r -> d.layers.slice(d.block(r)) }
    // The contents are as big as what's in them (with room for their effects)
    var area: Rect? = null
    for (l in block) {
        if (!l.hasContent) continue
        val b = l.bounds() ?: continue
        val reach = if (l.props.style.any) ceil(l.props.style.reach).toInt() + 2 else 0
        b.inset(-reach, -reach)
        area = union(area, b)
    }
    val box = area?.apply { if (!intersect(Transformer.limitFor(d.width, d.height))) setEmpty() }
    if (box == null || box.isEmpty) {
        toast("There's nothing to put in a smart layer")
        return
    }
    val top = roots.last()
    runBusy("Making smart layer…") {
        val contents = Doc(box.width(), box.height(), if (roots.size == 1) top.props.name else "Smart layer")
        // Copies of the layers, moved so the box's corner is the contents' corner
        val ids = HashMap<Long, Long>()
        for (l in block) ids[l.id] = contents.newId()
        // Smart layers going inside get contents of their own (links only reach within one picture),
        // still shared among the ones going in together
        val inner = java.util.IdentityHashMap<Doc, Doc>()
        for (l in block) {
            val parent = ids[l.props.parent] ?: 0L
            val smart = l.props.smart?.let { sp -> sp.withContents(inner.getOrPut(sp.contents) { sp.contents.shallowCopy() }) }
            val copy = Layer(
                ids.getValue(l.id), l.props.copy(parent = parent, locked = false, smart = smart),
                TileGrid(alphaOnly = false).also { it.tiles.putAll(l.tiles.tiles) }, l.x - box.left, l.y - box.top,
                l.mask?.let { m -> TileGrid(alphaOnly = true).also { it.tiles.putAll(m.tiles) } }, l.kind,
            )
            // Shapes, text and smart layers inside keep what they are
            contents.layers.add(copy)
        }
        contents.activeId = ids.getValue(top.id)
        val spec = SmartSpec(contents, SmartSpec.quadOf(RectF(box)))
        val grid = withContext(Dispatchers.Default) { SmartArt.rasterize(spec, Transformer.limitFor(d.width, d.height)) }
        if (doc !== d) return@runBusy
        stackEdit("Convert to smart layer") {
            val at = d.indexOf(top.id)
            val l = Layer(d.newId(), LayerProps(contents.name, parent = top.props.parent, smart = spec), grid, 0, 0, null, LayerKind.SMART)
            d.layers.add(at + 1, l)
            d.layers.removeAll(block.toSet())
            d.activeId = l.id
            d.editMask = false
        }
        multiSelected = emptySet()
    }
}

/** Gives linked smart layer [layerId] contents of its own, so editing it no longer changes its copies. */
fun EditorViewModel.unlinkSmart(layerId: Long) {
    val d = doc ?: return
    if (pendingFirst { unlinkSmart(layerId) }) return
    val l = d.layer(layerId) ?: return
    val sp = l.props.smart ?: return
    if (linkedCopies(d, l).isEmpty()) {
        toast("This smart layer isn't linked to any others")
        return
    }
    stackEdit("Unlink smart layer") { l.props = l.props.copy(smart = sp.withContents(sp.contents.shallowCopy())) }
    toast("\"${l.props.name}\" has contents of its own now")
}

// ===================================================================== tabs

/** Opens smart layer [layerId]'s contents in a tab (or goes to it if it's open already). */
fun EditorViewModel.openSmartContents(layerId: Long) {
    val d = doc ?: return
    if (pendingFirst { openSmartContents(layerId) }) return
    val l = d.layer(layerId) ?: return
    val spec = l.props.smart ?: return
    val here = currentTab ?: return
    // Linked copies share their contents: one tab for all of them
    val open = tabs.indexOfFirst { it.parent === here && it.doc === spec.contents }
    if (open >= 0) {
        switchTab(open)
        return
    }
    val h = History(budgetBytes = (Runtime.getRuntime().maxMemory() * 0.15).toLong())
    val tab = EditorTab(spec.contents, h, here, layerId).apply {
        baseline = ContentsSnap.of(spec.contents)
        committedVersion = h.version
    }
    // Opened right after the tab it came from (and after that tab's other tabs)
    var at = tabIndex + 1
    while (at < tabs.size && isInside(tabs[at], here)) at++
    tabs = tabs.toMutableList().apply { add(at, tab) }
    enterTab(at)
    toast("Editing \"${l.props.name}\". Go back to the first tab to see it in the picture")
}

/** Whether [t] is (inside) one of [ancestor]'s tabs. */
private fun isInside(t: EditorTab, ancestor: EditorTab): Boolean {
    var p = t.parent
    while (p != null) {
        if (p === ancestor) return true
        p = p.parent
    }
    return false
}

/**
 * Goes to tab [i]. The tab being left updates its smart layer first (if its contents changed), so
 * the picture shows them.
 */
fun EditorViewModel.switchTab(i: Int) {
    if (i == tabIndex || i !in tabs.indices) return
    if (pendingFirst { switchTab(i) }) return
    val target = tabs[i]
    // The tab being left and the ones it's inside, up to (not including) one the target is inside,
    // so each one's edits reach the layer above it on the way
    val here = currentTab
    val leaving = ArrayList<EditorTab>()
    var t: EditorTab? = here
    while (t != null && t !== target && !isInside(target, t)) {
        leaving.add(t)
        t = t.parent
    }
    commitTabs(leaving) { enterTab(tabs.indexOf(target)) }
}

/** Closes tab [i] (a smart layer's contents), putting its edits into the smart layer. */
fun EditorViewModel.closeTab(i: Int) {
    val t = tabs.getOrNull(i) ?: return
    if (t.parent == null) return
    if (pendingFirst { closeTab(i) }) return
    // It and the tabs opened from it
    val closing = tabs.filter { it === t || isInside(it, t) }
    // Innermost first, so each one's edits reach the layer above it
    commitTabs(closing.reversed()) {
        val back = t.parent
        tabs = tabs.filter { it !in closing }
        enterTab(tabs.indexOf(back).coerceAtLeast(0), force = true)
    }
}

/**
 * Puts the edits of [list] (in order) into their smart layers, in the background, then runs
 * [then].
 */
private fun EditorViewModel.commitTabs(list: List<EditorTab>, then: () -> Unit) {
    saveView()
    // Whether a tab has edits is checked as it comes up: putting an inner tab's edits in adds one to
    // the tab it came from
    if (list.none { it.hasNewEdits }) {
        then()
        return
    }
    runBusy("Updating smart layer…", onDone = then) {
        for (t in list) if (t.hasNewEdits) commitNow(t)
    }
}

/**
 * Puts [t]'s edits into its smart layer (one undo step in the tab it came from). Runs with the busy
 * overlay up, so the contents can't change meanwhile.
 */
private suspend fun EditorViewModel.commitNow(t: EditorTab) {
    val parent = t.parent ?: return
    val pd = parent.doc
    val l = pd.layer(t.layerId)?.takeIf { it.props.smart?.contents === t.doc }
        ?: pd.layers.firstOrNull { it.props.smart?.contents === t.doc }
    if (l == null) {
        t.committedVersion = t.history.version
        return
    }
    val oldSpec = l.props.smart ?: return
    val before = t.baseline ?: ContentsSnap.of(t.doc)
    // Contents cropped, turned or resized: placed at the same scale, not stretched into the old corners
    val spec = oldSpec.withQuad(placedAfterResize(oldSpec.quad, before, t.doc))
    val clip = Transformer.limitFor(pd.width, pd.height).apply { offset(-l.x, -l.y) }
    val version = t.doc.renderVersion
    val grid = withContext(Dispatchers.Default) { SmartArt.rasterize(spec, clip, version = version) }
    // Linked copies (the same contents) are drawn again too, each where it is
    val others = linkedCopies(pd, l).map { o ->
        val os = o.props.smart!!
        val ospec = os.withQuad(placedAfterResize(os.quad, before, t.doc))
        val oclip = Transformer.limitFor(pd.width, pd.height).apply { offset(-o.x, -o.y) }
        val og = withContext(Dispatchers.Default) { SmartArt.rasterize(ospec, oclip, version = version) }
        SmartContentsChange.LinkedRedraw(o, o.props, if (ospec === os) o.props else o.props.copy(smart = ospec), o.tiles, og)
    }
    val after = ContentsSnap.of(t.doc)
    val beforeProps = l.props
    val afterProps = if (spec === oldSpec) beforeProps else beforeProps.copy(smart = spec)
    val beforeTiles = l.tiles
    l.props = afterProps
    l.tiles = grid
    pd.touched(l)
    for (o in others) {
        o.layer.props = o.afterProps
        o.layer.tiles = o.afterTiles
        pd.touched(o.layer)
    }
    parent.history.record(SmartContentsChange(l, t.doc, before, after, beforeProps, afterProps, beforeTiles, grid, others))
    t.baseline = after
    t.committedVersion = t.history.version
}

/** Whether any open tab has edits not yet in its smart layer. */
internal val EditorViewModel.tabsHaveEdits: Boolean get() = tabs.any { it.hasNewEdits }

/** Tabs with edits that aren't in the picture's last save either. */
internal val EditorViewModel.tabsUnsaved: List<EditorTab> get() = tabs.filter { it.hasNewEdits && it.history.version != it.savedVersion }

/** Smart layers' tabs with edits not yet in the picture's draft. */
internal val EditorViewModel.tabsUndrafted: List<EditorTab> get() = tabs.filter { it.hasNewEdits && it.history.version != it.draftedVersion }

/**
 * A copy of the picture to save or export (made on the main thread; cheap), including what's being
 * edited in smart layers' tabs. Their layers in the copy are drawn again by [SaveCopy.finish], in
 * the background; nothing in the picture or its undo changes.
 */
internal class SaveCopy(val doc: Doc, private val redraw: List<Pair<List<Long>, FloatArray>>) {
    /** Draws the copy's smart layers that tabs changed (innermost first). Background thread. */
    fun finish() {
        for ((path, quad) in redraw) {
            var pd = doc
            var l: Layer? = null
            for ((n, id) in path.withIndex()) {
                l = pd.layer(id) ?: break
                if (n < path.lastIndex) pd = l.props.smart?.contents ?: break
            }
            val layer = l?.takeIf { it.id == path.last() } ?: continue
            val sp = layer.props.smart?.withQuad(quad) ?: continue
            layer.props = layer.props.copy(smart = sp)
            val clip = Transformer.limitFor(pd.width, pd.height).apply { offset(-layer.x, -layer.y) }
            layer.tiles = SmartArt.rasterize(sp, clip, keep = false)
        }
    }
}

internal fun EditorViewModel.saveCopy(): SaveCopy? {
    saveView()
    val root = rootDoc ?: return null
    val copy = root.shallowCopy()
    // Tabs with edits, and every tab they're inside (their layers change too)
    val stale = LinkedHashSet<EditorTab>()
    for (t in tabs) if (t.hasNewEdits) {
        var p: EditorTab? = t
        while (p?.parent != null) {
            stale.add(p)
            p = p.parent
        }
    }
    val redraw = stale.sortedByDescending { depth(it) }.flatMap { t ->
        // Where the tab's document sits: the layer ids of the tabs it's inside
        val outer = ArrayList<Long>()
        var p: EditorTab? = t.parent
        while (p?.parent != null) {
            outer.add(0, p.layerId)
            p = p.parent
        }
        // Every layer showing these contents (linked copies too), each where it is
        t.parent?.doc?.layers.orEmpty().filter { it.props.smart?.contents === t.doc }.map { l ->
            val sp = l.props.smart!!
            val quad = t.baseline?.let { placedAfterResize(sp.quad, it, t.doc) } ?: sp.quad
            (outer + l.id) to quad
        }
    }
    return SaveCopy(copy, redraw)
}

private fun depth(t: EditorTab): Int {
    var n = 0
    var p = t.parent
    while (p != null) {
        n++
        p = p.parent
    }
    return n
}

internal fun EditorViewModel.saveView() {
    currentTab?.view = Triple(scale, offsetX, offsetY)
    currentTab?.viewRotation = rotation
}

/** Shows tab [i]: its document, its undo, its view. */
internal fun EditorViewModel.enterTab(i: Int, force: Boolean = false) {
    val t = tabs.getOrNull(i) ?: return
    if (!force && i == tabIndex && doc === t.doc) return
    // (What belongs to the picture left behind: mask view, reselect, waiting Select and mask)
    viewMaskOf = null
    lastSelection = null
    pendingMaskRefine = null
    // A smart layer that was deleted (or undone away) meanwhile: its tab goes
    val p = t.parent
    if (p != null && p.doc.layers.none { it.props.smart?.contents === t.doc }) {
        tabs = tabs.filter { it !== t && !isInside(it, t) }
        toast("That smart layer isn't there any more")
        enterTab(tabs.indexOf(p).coerceAtLeast(0), force = true)
        return
    }
    tabIndex = i
    switchDocument(t.doc, t.history)
    val v = t.view
    if (v != null) {
        scale = v.first
        offsetX = v.second
        offsetY = v.third
        rotation = t.viewRotation
    } else {
        fit()
    }
}

/**
 * Before Undo or Redo of [c] in this tab: if it changes a smart layer's contents, tabs showing those
 * contents close (their own undo would no longer fit).
 */
internal fun EditorViewModel.closeTabsChangedBy(c: Change?) {
    val sc = c as? SmartContentsChange ?: return
    val here = currentTab ?: return
    val gone = tabs.filter { it.doc === sc.contents && it.parent === here }
    if (gone.isEmpty()) return
    tabs = tabs.filter { t -> gone.none { g -> t === g || isInside(t, g) } }
    tabIndex = tabs.indexOf(here).coerceAtLeast(0)
}

// ===================================================================== the tab strip

/** The open tabs (shown when a smart layer's contents are open): tap one to go to it, ✕ to close it. */
@androidx.compose.runtime.Composable
fun TabStrip(vm: EditorViewModel, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    if (vm.tabs.size < 2) return
    androidx.compose.foundation.layout.Row(
        modifier
            .horizontalScrollSafe()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(Q.toolbar.copy(alpha = 0.95f))
            .padding(4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        vm.tabs.forEachIndexed { i, t ->
            val sel = i == vm.tabIndex
            val name = t.parent?.doc?.layer(t.layerId)?.props?.name ?: t.doc.name
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = androidx.compose.ui.Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(if (sel) Q.accent else Q.field)
                    .clickable { vm.switchTab(i) }
                    .padding(start = 12.dp, end = if (t.parent != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (t.parent != null) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Outlined.FilterNone, contentDescription = null,
                        tint = if (sel) androidx.compose.ui.graphics.Color.White else Q.textDim,
                        modifier = androidx.compose.ui.Modifier.size(14.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
                }
                androidx.compose.material3.Text(
                    name, color = if (sel) androidx.compose.ui.graphics.Color.White else Q.text, fontSize = 13.sp, maxLines = 1,
                    modifier = androidx.compose.ui.Modifier.widthIn(max = 160.dp),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (t.parent != null) {
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                        modifier = androidx.compose.ui.Modifier
                            .padding(start = 4.dp)
                            .size(24.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { vm.closeTab(i) },
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Close \"$name\" (its edits go into the picture)",
                            tint = if (sel) androidx.compose.ui.graphics.Color.White else Q.text,
                            modifier = androidx.compose.ui.Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.Modifier.horizontalScrollSafe(): androidx.compose.ui.Modifier =
    this.horizontalScroll(androidx.compose.foundation.rememberScrollState())

/** Whether smart layer [l] at ([x], [y]) reaches past where its pixels are kept (so some weren't drawn). */
internal fun smartClipped(l: Layer, x: Int, y: Int, d: Doc): Boolean {
    val spec = l.props.smart ?: return false
    val b = spec.bounds().apply { offset(x.toFloat(), y.toFloat()) }
    return !RectF(Transformer.limitFor(d.width, d.height)).contains(b)
}

/** Draws smart layer [l]'s pixels again from its contents, in the background (after moving it). */
internal fun EditorViewModel.redrawSmartSoon(d: Doc, l: Layer) {
    val spec = l.props.smart ?: return
    val before = l.tiles
    val clip = Transformer.limitFor(d.width, d.height).apply { offset(-l.x, -l.y) }
    // The contents as they are now (they could be opened and edited meanwhile)
    val version = spec.contents.renderVersion
    val source = spec.contents.shallowCopy()
    viewModelScope.launch {
        val grid = withContext(Dispatchers.Default) {
            runCatching { SmartArt.rasterize(spec, clip, source, version) }.getOrNull()
        } ?: return@launch
        // Only if nothing else changed it meanwhile
        if (doc === d && l.tiles === before && l.props.smart === spec) {
            l.tiles = grid
            d.touched(l)
        }
    }
}

/** Where each smart layer is (to see which ones an undo or redo moved). */
internal fun smartPositions(d: Doc): Map<Layer, Pair<Int, Int>> =
    d.layers.filter { it.isSmart }.associateWith { it.x to it.y }

/**
 * After undo or redo: smart layers it moved that reach past where pixels are kept are drawn again
 * from their contents (the pixels kept with a move are the ones from before it was redrawn).
 */
internal fun EditorViewModel.redrawMovedSmart(d: Doc, before: Map<Layer, Pair<Int, Int>>) {
    for (l in d.layers) {
        if (!l.isSmart) continue
        val (x, y) = before[l] ?: continue
        if (x == l.x && y == l.y) continue
        if (smartClipped(l, x, y, d) || smartClipped(l, l.x, l.y, d)) redrawSmartSoon(d, l)
    }
}
